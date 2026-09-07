import Foundation
import AuralisSherpaAsr
import OnnxRuntimeBindings

/// TTS engine for the Qwen3-TTS-12Hz-0.6B-Base ONNX bundle
/// (elbruno/Qwen3-TTS-12Hz-0.6B-Base-ONNX; see shared/model-manifests/tts.json).
///
/// The protocol is a direct port of the bundle author's reference inference
/// (github.com/elbruno/ElBruno.QwenTTS, LanguageModel.cs / VoiceClonePipeline.cs)
/// and was verified end-to-end by the host runner convert/tts_runner.py: real
/// zh/en synthesis whose audio round-trips through the real ASR runner.
///
/// Pipeline (API1, shared tts.json apiContractVersion 1):
///   reference PCM -> resample 24 kHz -> log-mel -> speaker_encoder.onnx
///   text -> byte-level BPE -> embeddings .npy tables + text projection
///        -> talker_prefill.onnx (logits/hidden/KV)
///        -> per frame: talker_decode.onnx + code_predictor.onnx (groups 1..15)
///        -> vocoder.onnx (codes [1,16,T] -> 24 kHz waveform)
///
/// API2 is used only when the shared manifest apiContractVersion is "2" or an
/// explicit experimental graph map is injected for host verification. It keeps
/// one `talker` session for prefill+decode, optional ICL reference codes, and
/// the state streaming vocoder. Missing reference text stays xvector-only.
///
/// Honesty rules (spec 01 C04, 02):
///   - Graph I/O names/shapes are validated at use; mismatch throws
///     OrtInferenceFailure.protocolMismatch, never a guess.
///   - Missing tokenizer, unknown language, wrong embedding dim: thrown error.
///   - A speaker embedding is required; there is no zero-vector clone path.
///   - Empty or all-silent synthesis output is an error, never a "success".
///   - Device memory is unproven: prefill+decode graphs each reference ~1.7 GB
///     of weights; the device memory gate is open (reports/continuation-tts.md).
final class TtsEngine {
    private static let packageId = "tts"
    static let outputSampleRate = 24000

    private let modelManager: OnnxModelManager
    private let experimentalApi2: ExperimentalApi2Graphs?
    private var config: Qwen3TtsConfig?
    private var embeddings: Qwen3TtsEmbeddings?
    private var tokenizer: Qwen3TtsTokenizer?
    private var isLoaded = false
    private var speakerEncoderLoaded = false
    private var manifestApiContractVersion = "1"
    private var api2Env: ORTEnv?
    private var api2Talker: OrtInferenceSession?
    private var api2Vocoder: OrtInferenceSession?
    private var api2Encoder: OrtInferenceSession?
    // One loaded lifetime of one engine. Paths alone cannot invalidate a
    // prepared snapshot when the same package is unloaded and loaded again.
    private var referenceGeneration = UUID()

    /// Explicit multi-source experimental API2 graphs. Not a shipping package
    /// and not a fictional HF download path.
    struct ExperimentalApi2Graphs {
        let talker: URL
        let referenceEncoder: URL
        let streamingVocoder: URL

        func validatePresent() throws {
            for url in [talker, referenceEncoder, streamingVocoder] {
                guard FileManager.default.fileExists(atPath: url.path) else {
                    throw OrtInferenceFailure.protocolMismatch("API2 graph missing: \(url.path)")
                }
                let data = URL(fileURLWithPath: url.path + ".data")
                guard FileManager.default.fileExists(atPath: data.path) else {
                    throw OrtInferenceFailure.protocolMismatch("API2 external data missing: \(data.path)")
                }
            }
        }
    }

    /// Prepared speaker/ICL conditioning. `referenceText` must be the actual
    /// transcript; a missing text keeps xvector-only and never invents ASR.
    struct PreparedReference {
        fileprivate let generation: UUID
        let embedding: [Float]
        let pcm24kSamples: Int
        let pcmIdentity: String
        let vocoderIdentity: String
        let referenceText: String?
        let referenceTokenIds: [Int]?
        /// Group-major `[16][R]` codec ids in `0..<2048`.
        let referenceCodesGroupMajor: [[Int]]?
        /// Immutable streaming-vocoder state after one reference warm-up.
        /// ~5.2 MB; each turn copies this, it is never mutated in place.
        let vocoderWarmup: VocoderWarmup?
        var isIcl: Bool { referenceCodesGroupMajor != nil }

        struct VocoderWarmup {
            let conv: [Float]
            let keys: [Float]
            let values: [Float]
            let position: Int64
        }
    }

    struct SynthesisResult {
        let audioData: [Float]
        let sampleRate: Int
        let durationMs: Int64
        let inferenceTimeMs: Int64
        let frames: Int
        let peak: Float
        /// Fraction of samples clamped to ±1 by the sink-side guard (raw
        /// vocoder samples at or beyond ±0.999, mirroring the host runner).
        let clippingRatio: Double
        /// Group-0 (coarse) codec tokens of this synthesis — the primary
        /// diagnostic trace for content/loop regressions.
        let group0Tokens: [Int]
        /// Complete 16-group target frames (frame-major). Empty on API1.
        let codecFrames: [[Int]]
        let conditioningMode: String
    }

    init(modelManager: OnnxModelManager, experimentalApi2: ExperimentalApi2Graphs? = nil) {
        self.modelManager = modelManager
        self.experimentalApi2 = experimentalApi2
    }

    private var usesApi2: Bool {
        experimentalApi2 != nil || manifestApiContractVersion == "2"
    }

    var supportsReferenceText: Bool { isLoaded && usesApi2 }

    // MARK: - Lifecycle

    /// Load config, embedding tables and the tokenizer. Graphs load lazily
    /// through the session store on first use.
    func load() async throws {
        guard !isLoaded else { return }
        let modelsDir = modelManager.modelsDir
        let configURL = modelsDir.appendingPathComponent("tts/embeddings/config.json")
        guard FileManager.default.fileExists(atPath: configURL.path) else {
            throw OrtInferenceFailure.protocolMismatch(
                "TTS bundle config missing at \(configURL.path) — model protocol unsupported")
        }
        let configData = try Data(contentsOf: configURL)
        config = try Qwen3TtsConfig(data: configData)
        embeddings = try Qwen3TtsEmbeddings(modelsDir: modelsDir)
        tokenizer = try Qwen3TtsTokenizer(modelsDir: modelsDir)
        if let graphs = experimentalApi2 {
            try graphs.validatePresent()
        }
        // Shared package is still API1. A missing host bundle resource must not
        // block the verified API1 path; overlay still selects API2.
        if let manifest = try? SharedContracts.loadManifest(packageId: Self.packageId) {
            manifestApiContractVersion = manifest.apiContractVersion
        }
        isLoaded = true
    }

    /// Lightweight path used by voice-profile creation: encoder module only.
    func loadSpeakerEncoder() async throws {
        guard !speakerEncoderLoaded else { return }
        _ = try await modelManager.sessionStore.sessionInfo(
            role: "speaker_encoder", packageId: Self.packageId)
        speakerEncoderLoaded = true
    }

    // MARK: - Speaker embedding

    /// Extract the raw ECAPA-TDNN speaker embedding via the real graph.
    ///
    /// The bundle speaker encoder consumes a 24 kHz log-mel frontend, so 16 kHz
    /// capture is resampled first. The vector is NOT L2-normalized: the
    /// verified host protocol injects the raw embedding.
    func extractSpeakerEmbedding(referenceAudio: [Float],
                                 profileId _: String? = nil,
                                 inputSampleRate: Int = 16000) async throws -> [Float] {
        try Task.checkCancellation()
        // The session adapter owns its prepared voice. A second cache keyed
        // only by profile ID could return a voice for different/invalid PCM.
        let audio24k = try Qwen3TtsProtocol.resampleBandlimited(
            referenceAudio, fromRate: inputSampleRate, toRate: Self.outputSampleRate)
        guard audio24k.count >= 1024,
              audio24k.contains(where: { abs($0) >= 1e-4 }) else {
            throw OrtInferenceFailure.badInput("reference voice is too short or silent")
        }
        if !speakerEncoderLoaded { try await loadSpeakerEncoder() }
        try Task.checkCancellation()
        let mel = Qwen3TtsProtocol.logMelSpectrogram(audio24k, sampleRate: Self.outputSampleRate)
        let input = try OnnxTensor(
            floatData: mel.data, shape: [1, mel.frames, 128])
        let outputs = try await modelManager.sessionStore.run(
            role: "speaker_encoder", packageId: Self.packageId,
            inputs: ["mel_spectrogram": input])
        guard let output = outputs["speaker_embedding"] else {
            throw OrtInferenceFailure.missingOutput(
                "speaker_encoder", expected: ["speaker_embedding"])
        }
        let embedding = try output.floatArray()
        guard embedding.count == Qwen3TtsProtocol.speakerEmbeddingDim else {
            throw OrtInferenceFailure.protocolMismatch(
                "speaker encoder returned dim \(embedding.count), "
                + "expected \(Qwen3TtsProtocol.speakerEmbeddingDim)")
        }
        guard embedding.allSatisfy({ $0.isFinite }) else {
            throw OrtInferenceFailure.badOutput("speaker encoder produced non-finite values")
        }

        return embedding
    }

    // MARK: - Synthesis

    /// API2 entry: uses the caller-supplied prepared snapshot, never an implicit
    /// embedding lookup. Existing `synthesize` remains the API1 Pipeline path.
    func synthesizePrepared(text: String, language: String,
                            preparedReference: PreparedReference,
                            temperature: Float = 0.9, topK: Int = 50,
                            repetitionPenalty: Float = 1.05,
                            maxFrames: Int = 2048,
                            seed: UInt64 = 2026_0906,
                            onAudioChunk: (@Sendable ([Float]) async throws -> Void)? = nil) async throws -> SynthesisResult {
        try await synthesize(text: text, language: language,
                             speakerEmbedding: preparedReference.embedding,
                             temperature: temperature, topK: topK,
                             repetitionPenalty: repetitionPenalty,
                             maxFrames: maxFrames, seed: seed,
                             preparedReference: preparedReference,
                             onAudioChunk: onAudioChunk)
    }

    /// Reference sampling parameters (author default): temperature 0.9,
    /// top-k 50, repetition penalty 1.05, hard frame budget 2048 (≈163.8 s).
    /// `seed` feeds SplitMix64; the host runner uses numpy PCG64, so seeds are
    /// NOT interchangeable across runtimes — only within one.
    func synthesize(text: String, language: String,
                    speakerEmbedding: [Float]?,
                    temperature: Float = 0.9, topK: Int = 50,
                    repetitionPenalty: Float = 1.05,
                    maxFrames: Int = 2048,
                    seed: UInt64 = 2026_0906,
                    preparedReference: PreparedReference? = nil,
                    onAudioChunk: (@Sendable ([Float]) async throws -> Void)? = nil) async throws -> SynthesisResult {
        try Task.checkCancellation()
        guard (1...2048).contains(maxFrames), temperature.isFinite, temperature >= 0,
              topK >= 0, repetitionPenalty.isFinite, repetitionPenalty > 0 else {
            throw OrtInferenceFailure.badInput("invalid synthesis budget or sampling parameters")
        }
        guard isLoaded, let cfg = config, let tables = embeddings, let tok = tokenizer else {
            throw OrtInferenceFailure.notLoaded("TTS")
        }
        if let preparedReference, preparedReference.generation != referenceGeneration {
            throw OrtInferenceFailure.badInput("prepared reference belongs to another engine or loaded lifetime")
        }
        if preparedReference?.isIcl == true || (preparedReference?.referenceText != nil) {
            guard usesApi2 else {
                throw OrtInferenceFailure.badInput("ICL reference text/codes require TTS API2")
            }
        }
        if usesApi2 {
            return try await synthesizeApi2(
                text: text, language: language, speakerEmbedding: speakerEmbedding,
                temperature: temperature, topK: topK, repetitionPenalty: repetitionPenalty,
                maxFrames: maxFrames, seed: seed, preparedReference: preparedReference,
                onAudioChunk: onAudioChunk, config: cfg, tables: tables, tokenizer: tok)
        }

        guard let embedding = preparedReference?.embedding ?? speakerEmbedding, !embedding.isEmpty else {
            throw OrtInferenceFailure.badInput(
                "no speaker profile selected and the bundle provides no default voice; "
                + "refusing to synthesize with a zero embedding")
        }
        guard embedding.count == cfg.hiddenSize else {
            throw OrtInferenceFailure.protocolMismatch(
                "speaker embedding dim \(embedding.count) != talker hidden \(cfg.hiddenSize)")
        }
        guard embedding.allSatisfy(\.isFinite), embedding.contains(where: { $0 != 0 }) else {
            throw OrtInferenceFailure.badInput("speaker embedding must be finite and nonzero")
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw OrtInferenceFailure.badInput("empty text cannot be synthesized")
        }

        do {
            let t0 = ProcessInfo.processInfo.systemUptime
            let tokenIds = try tok.buildPromptIds(text: trimmed)
            let languageKey = try Self.languageKey(for: language, config: cfg)
            let built = try Qwen3TtsProtocol.buildPrefillEmbeddings(
                tokenIds: tokenIds, speakerEmbedding: embedding,
                languageKey: languageKey, config: cfg, tables: tables)

            // 1. Prefill.
            let seqLen = built.embeds.count / cfg.hiddenSize
            var positionIds: [Int64] = []
            positionIds.reserveCapacity(3 * seqLen)
            for _ in 0..<3 {
                for p in 0..<seqLen { positionIds.append(Int64(p)) }
            }
            let prefillOutputs = try await runGraph(
                role: "talker_prefill",
                inputs: [
                    "inputs_embeds": try OnnxTensor(
                        floatData: built.embeds, shape: [1, seqLen, cfg.hiddenSize]),
                    "attention_mask": try OnnxTensor(
                        int64Data: Array(repeating: 1, count: seqLen), shape: [1, seqLen]),
                    "position_ids": try OnnxTensor(
                        int64Data: positionIds, shape: [3, 1, seqLen]),
                ])
            let state = try Qwen3TtsProtocol.PrefillState(outputs: prefillOutputs, config: cfg)
            // The prefill graph's ~1.7 GB of weights are dead after this point for
            // the rest of the loop (decode carries its own KV). Release before the
            // code_predictor/decode sessions load so the synthesis memory peak is
            // max(prefill, decode+CP+vocoder+KV), not their sum. A later synthesis
            // reloads the prefill session transparently.
            await modelManager.sessionStore.release(role: "talker_prefill", packageId: Self.packageId)

            // 2. Autoregressive loop (decode + code predictor).
            let (codes, group0Tokens) = try await generateFrames(
                config: cfg, tables: tables, state: state, trailing: built.trailing,
                temperature: temperature, topK: topK, repetitionPenalty: repetitionPenalty,
                maxFrames: maxFrames, seed: seed)

            // Serial weight residency (Android parity): the decode graph's ~1.7 GB
            // are dead once the loop ends; releasing it before the vocoder loads
            // keeps the per-turn peak ≈ max(prefill, decode+CP+KV), not the sum.
            await modelManager.sessionStore.release(role: "talker_decode", packageId: Self.packageId)

            // 3. Vocoder.
            let waveform = try await runVocoder(codes: codes)
            let peak = waveform.lazy.map { abs($0) }.max() ?? 0
            guard peak >= 1e-4 else {
                throw OrtInferenceFailure.badOutput(
                    "synthesis is silent (peak \(peak)); refusing to report success")
            }
            let clippingCount = waveform.lazy.filter { abs($0) >= 0.999 }.count
            let clippingRatio = Double(clippingCount) / Double(max(waveform.count, 1))

            let elapsedMs = Int64((ProcessInfo.processInfo.systemUptime - t0) * 1000)
            if let onAudioChunk {
                try await onAudioChunk(Array(waveform))
            }
            return SynthesisResult(
                audioData: waveform,
                sampleRate: Self.outputSampleRate,
                durationMs: Int64(waveform.count) * 1000 / Int64(Self.outputSampleRate),
                inferenceTimeMs: elapsedMs,
                frames: codes.count,
                peak: peak,
                clippingRatio: clippingRatio,
                group0Tokens: group0Tokens,
                codecFrames: codes,
                conditioningMode: "xvector")
        } catch {
            // Await graph release before another turn may load its weights.
            // An async task from defer would race that next synthesis.
            for role in ["talker_prefill", "talker_decode", "vocoder"] {
                await modelManager.sessionStore.release(role: role, packageId: Self.packageId)
            }
            throw error
        }
    }

    /// Prepare speaker embedding and, when a real transcript is supplied, ICL codes.
    /// Passing nil text keeps xvector-only. Empty text is rejected rather than forged.
    func prepareReference(referenceAudio: [Float],
                          inputSampleRate: Int,
                          referenceText: String?) async throws -> PreparedReference {
        try Task.checkCancellation()
        guard isLoaded, let cfg = config, let tok = tokenizer else {
            throw OrtInferenceFailure.notLoaded("TTS")
        }
        let generation = referenceGeneration
        let trimmed = referenceText?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed {
            guard !trimmed.isEmpty, usesApi2 else {
                throw OrtInferenceFailure.badInput("ICL requires nonempty reference text and TTS API2")
            }
        }
        let pcm24k = try Qwen3TtsProtocol.resampleBandlimited(
            referenceAudio, fromRate: inputSampleRate, toRate: Self.outputSampleRate)
        guard pcm24k.count >= 1024,
              pcm24k.count <= Self.outputSampleRate * 30,
              pcm24k.allSatisfy({ $0.isFinite }),
              (pcm24k.map { abs($0) }.max() ?? 0) >= 1e-4 else {
            throw OrtInferenceFailure.badInput(
                "reference preparation requires finite 24 kHz PCM, 1024 samples..30 seconds, peak ≥ 1e-4")
        }
        let embedding = try await extractSpeakerEmbedding(
            referenceAudio: pcm24k, inputSampleRate: Self.outputSampleRate)
        try Task.checkCancellation()
        guard isLoaded, referenceGeneration == generation else {
            throw OrtInferenceFailure.notLoaded("TTS reference preparation was released")
        }
        let pcmIdentity = Self.pcmIdentity(pcm24k)
        let vocoderIdentity = currentVocoderIdentity()
        if trimmed == nil {
            return PreparedReference(
                generation: generation,
                embedding: embedding, pcm24kSamples: pcm24k.count,
                pcmIdentity: pcmIdentity, vocoderIdentity: vocoderIdentity,
                referenceText: nil, referenceTokenIds: nil, referenceCodesGroupMajor: nil,
                vocoderWarmup: nil)
        }
        guard let text = trimmed, !text.isEmpty else {
            throw OrtInferenceFailure.badInput(
                "ICL requires nonempty reference text matching the reference audio")
        }
        guard usesApi2 else {
            throw OrtInferenceFailure.badInput("reference text requires TTS API2")
        }
        let tokenIds = try tok.buildReferencePromptIds(text: text)
        guard tokenIds.count >= 6 else {
            throw OrtInferenceFailure.badInput("reference transcript tokenized to an unusable sequence")
        }
        let codes = try await encodeReferenceCodes(pcm24k: pcm24k, config: cfg)
        let (_, warmed) = try await streamingVocoderStep(
            groupMajor: codes, state: StreamingVocoderState.zero())
        try Task.checkCancellation()
        guard isLoaded, referenceGeneration == generation else {
            throw OrtInferenceFailure.notLoaded("TTS reference preparation was released")
        }
        let warmup = PreparedReference.VocoderWarmup(
            conv: Array(warmed.conv), keys: Array(warmed.keys),
            values: Array(warmed.values), position: warmed.position)
        return PreparedReference(
            generation: generation,
            embedding: embedding, pcm24kSamples: pcm24k.count,
            pcmIdentity: pcmIdentity, vocoderIdentity: vocoderIdentity,
            referenceText: text, referenceTokenIds: tokenIds, referenceCodesGroupMajor: codes,
            vocoderWarmup: warmup)
    }

    /// Decode already-generated target frames with the state vocoder.
    /// Warm-up consumes reference codes and discards that PCM. Host uses this
    /// to compare chunk-4 against a single positive-length step on the same codes.
    func decodeStreamingVocoder(targetFrames: [[Int]],
                                referenceCodesGroupMajor: [[Int]]? = nil,
                                warmup: PreparedReference.VocoderWarmup? = nil,
                                chunkFrames: Int = 4,
                                onAudioChunk: (@Sendable ([Float]) async throws -> Void)? = nil) async throws -> [Float] {
        guard usesApi2 else {
            throw OrtInferenceFailure.badInput("streaming vocoder requires TTS API2")
        }
        return try await runStreamingVocoder(
            targetFrames: targetFrames, referenceCodesGroupMajor: referenceCodesGroupMajor,
            warmup: warmup, chunkFrames: chunkFrames, onAudioChunk: onAudioChunk)
    }

    // MARK: - API2

    private static let streamingVocoderChunkFrames = 4
    private static let streamingConvState = 135_232
    private static let streamingKvFloats = 8 * 1 * 16 * 71 * 64

    private func synthesizeApi2(text: String, language: String,
                                speakerEmbedding: [Float]?,
                                temperature: Float, topK: Int, repetitionPenalty: Float,
                                maxFrames: Int, seed: UInt64,
                                preparedReference: PreparedReference?,
                                onAudioChunk: (@Sendable ([Float]) async throws -> Void)?,
                                config cfg: Qwen3TtsConfig, tables: Qwen3TtsEmbeddings,
                                tokenizer tok: Qwen3TtsTokenizer) async throws -> SynthesisResult {
        guard let embedding = preparedReference?.embedding ?? speakerEmbedding, !embedding.isEmpty else {
            throw OrtInferenceFailure.badInput(
                "no speaker profile selected and the bundle provides no default voice; "
                + "refusing to synthesize with a zero embedding")
        }
        guard embedding.count == cfg.hiddenSize else {
            throw OrtInferenceFailure.protocolMismatch(
                "speaker embedding dim \(embedding.count) != talker hidden \(cfg.hiddenSize)")
        }
        guard embedding.allSatisfy({ $0.isFinite }), embedding.contains(where: { $0 != 0 }) else {
            throw OrtInferenceFailure.badInput("speaker embedding must be finite and nonzero")
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw OrtInferenceFailure.badInput("empty text cannot be synthesized")
        }
        if let prepared = preparedReference, prepared.vocoderIdentity != currentVocoderIdentity() {
            throw OrtInferenceFailure.protocolMismatch(
                "prepared reference is bound to a different vocoder/model identity")
        }
        let icl = preparedReference?.isIcl == true
        if icl {
            guard preparedReference?.referenceTokenIds != nil,
                  preparedReference?.referenceCodesGroupMajor != nil,
                  preparedReference?.referenceText != nil,
                  preparedReference?.vocoderWarmup != nil else {
                throw OrtInferenceFailure.badInput("ICL requires reference text, codes, and vocoder warm-up state")
            }
        } else if preparedReference?.referenceText != nil {
            throw OrtInferenceFailure.badInput("reference text without codes is not ICL")
        }
        do {
            let t0 = ProcessInfo.processInfo.systemUptime
            let tokenIds = try tok.buildPromptIds(text: trimmed)
            let languageKey = try Self.languageKey(for: language, config: cfg)
            let built: Qwen3TtsProtocol.PrefillEmbeddings
            if icl, let refIds = preparedReference?.referenceTokenIds,
               let refCodes = preparedReference?.referenceCodesGroupMajor {
                built = try Qwen3TtsProtocol.buildIclPrefillEmbeddings(
                    tokenIds: tokenIds, referenceTokenIds: refIds,
                    referenceCodesGroupMajor: refCodes, speakerEmbedding: embedding,
                    languageKey: languageKey, config: cfg, tables: tables)
            } else {
                built = try Qwen3TtsProtocol.buildPrefillEmbeddings(
                    tokenIds: tokenIds, speakerEmbedding: embedding,
                    languageKey: languageKey, config: cfg, tables: tables)
            }
            let seqLen = built.embeds.count / cfg.hiddenSize
            var positionIds: [Int64] = []
            positionIds.reserveCapacity(3 * seqLen)
            for _ in 0..<3 {
                for p in 0..<seqLen { positionIds.append(Int64(p)) }
            }
            let emptyPast = [cfg.numLayers, 1, cfg.numKvHeads, 0, cfg.headDim]
            let prefillOutputs = try await runUnifiedTalker(inputs: [
                "inputs_embeds": try OnnxTensor(
                    floatData: built.embeds, shape: [1, seqLen, cfg.hiddenSize]),
                "attention_mask": try OnnxTensor(
                    int64Data: Array(repeating: 1, count: seqLen), shape: [1, seqLen]),
                "position_ids": try OnnxTensor(int64Data: positionIds, shape: [3, 1, seqLen]),
                "past_keys": try OnnxTensor(floatData: [], shape: emptyPast),
                "past_values": try OnnxTensor(floatData: [], shape: emptyPast),
            ])
            let state = try Qwen3TtsProtocol.PrefillState(api2Outputs: prefillOutputs, config: cfg)
            var vocoderState = StreamingVocoderState(warmup: preparedReference?.vocoderWarmup)
            var pendingFrames: [[Int]] = []
            var waveform: [Float] = []
            func flushPending() async throws {
                guard !pendingFrames.isEmpty else { return }
                try Task.checkCancellation()
                let (pcm, next) = try await streamingVocoderStep(
                    groupMajor: Self.framesToGroupMajor(pendingFrames), state: vocoderState)
                if let onAudioChunk { try await onAudioChunk(pcm) }
                try Task.checkCancellation()
                waveform.append(contentsOf: pcm)
                vocoderState = next
                pendingFrames.removeAll(keepingCapacity: true)
            }
            let (codes, group0Tokens) = try await generateFrames(
                config: cfg, tables: tables, state: state, trailing: built.trailing,
                temperature: temperature, topK: topK, repetitionPenalty: repetitionPenalty,
                maxFrames: maxFrames, seed: seed, unifiedTalker: true,
                onFrame: { frame in
                    pendingFrames.append(frame)
                    if pendingFrames.count == Self.streamingVocoderChunkFrames {
                        try await flushPending()
                    }
                })
            // Only a real EOS permits the positive-length tail. A budget
            // failure may have emitted full chunks, but never becomes success.
            try await flushPending()
            let peak = waveform.lazy.map { abs($0) }.max() ?? 0
            guard peak >= 1e-4 else {
                throw OrtInferenceFailure.badOutput(
                    "synthesis is silent (peak \(peak)); refusing to report success")
            }
            let clippingCount = waveform.lazy.filter { abs($0) >= 0.999 }.count
            let clippingRatio = Double(clippingCount) / Double(max(waveform.count, 1))
            let elapsedMs = Int64((ProcessInfo.processInfo.systemUptime - t0) * 1000)
            return SynthesisResult(
                audioData: waveform,
                sampleRate: Self.outputSampleRate,
                durationMs: Int64(waveform.count) * 1000 / Int64(Self.outputSampleRate),
                inferenceTimeMs: elapsedMs,
                frames: codes.count,
                peak: peak,
                clippingRatio: clippingRatio,
                group0Tokens: group0Tokens,
                codecFrames: codes,
                conditioningMode: icl ? "icl" : "xvector")
        } catch {
            await releaseApi2GenerationSessions()
            throw error
        }
    }

    private func encodeReferenceCodes(pcm24k: [Float], config: Qwen3TtsConfig) async throws -> [[Int]] {
        let expectedFrames = (pcm24k.count + Qwen3TtsProtocol.samplesPerFrame - 1)
            / Qwen3TtsProtocol.samplesPerFrame
        let outputs = try await runReferenceEncoder(inputs: [
            "pcm": try OnnxTensor(floatData: pcm24k, shape: [1, 1, pcm24k.count])
        ])
        api2Encoder = nil
        if experimentalApi2 == nil {
            await modelManager.sessionStore.release(role: "reference_encoder", packageId: Self.packageId)
        }
        guard let tensor = outputs["codes"] else {
            throw OrtInferenceFailure.missingOutput("reference_encoder", expected: ["codes"])
        }
        guard tensor.dtype == .int64,
              tensor.shape == [1, Qwen3TtsProtocol.numCodebooks, expectedFrames] else {
            throw OrtInferenceFailure.protocolMismatch(
                "reference encoder output must be int64 [1,16,\(expectedFrames)], got \(tensor.shape)")
        }
        let flat = try tensor.int64Array()
        let maxId = config.cpVocab
        var groups = Array(repeating: [Int](), count: Qwen3TtsProtocol.numCodebooks)
        for g in 0..<Qwen3TtsProtocol.numCodebooks {
            var row = [Int]()
            row.reserveCapacity(expectedFrames)
            for t in 0..<expectedFrames {
                let id = Int(flat[g * expectedFrames + t])
                guard (0..<maxId).contains(id) else {
                    throw OrtInferenceFailure.badOutput("reference encoder returned a reserved or out-of-range codec id")
                }
                row.append(id)
            }
            groups[g] = row
        }
        guard (1...Qwen3TtsProtocol.maxReferenceFrames).contains(expectedFrames) else {
            throw OrtInferenceFailure.badInput("reference codes must cover a nonempty reference of at most 30 seconds")
        }
        return groups
    }

    private func runStreamingVocoder(targetFrames: [[Int]],
                                     referenceCodesGroupMajor: [[Int]]?,
                                     warmup: PreparedReference.VocoderWarmup?,
                                     chunkFrames: Int,
                                     onAudioChunk: (@Sendable ([Float]) async throws -> Void)?) async throws -> [Float] {
        guard chunkFrames >= 1, !targetFrames.isEmpty else {
            throw OrtInferenceFailure.badInput("streaming vocoder requires a positive chunk and target frames")
        }
        var state: StreamingVocoderState
        if let warmup {
            // Independent working copy; PreparedReference stays immutable.
            state = StreamingVocoderState(
                conv: Array(warmup.conv), keys: Array(warmup.keys),
                values: Array(warmup.values), position: warmup.position)
        } else {
            state = StreamingVocoderState.zero()
            if let reference = referenceCodesGroupMajor {
                let (_, next) = try await streamingVocoderStep(groupMajor: reference, state: state)
                state = next
            }
        }
        var waveform: [Float] = []
        var offset = 0
        while offset < targetFrames.count {
            try Task.checkCancellation()
            let n = min(chunkFrames, targetFrames.count - offset)
            let chunk = Array(targetFrames[offset..<(offset + n)])
            let groups = Self.framesToGroupMajor(chunk)
            let (pcm, next) = try await streamingVocoderStep(groupMajor: groups, state: state)
            if let onAudioChunk {
                try await onAudioChunk(Array(pcm))
            }
            waveform.append(contentsOf: pcm)
            state = next
            offset += n
        }
        return waveform
    }

    private struct StreamingVocoderState {
        var conv: [Float]
        var keys: [Float]
        var values: [Float]
        var position: Int64
        init(conv: [Float], keys: [Float], values: [Float], position: Int64) {
            self.conv = conv; self.keys = keys; self.values = values; self.position = position
        }
        init(warmup: PreparedReference.VocoderWarmup?) {
            if let warmup {
                self.init(conv: warmup.conv, keys: warmup.keys, values: warmup.values, position: warmup.position)
            } else { self = Self.zero() }
        }
        static func zero() -> StreamingVocoderState {
            StreamingVocoderState(
                conv: [Float](repeating: 0, count: TtsEngine.streamingConvState),
                keys: [Float](repeating: 0, count: TtsEngine.streamingKvFloats),
                values: [Float](repeating: 0, count: TtsEngine.streamingKvFloats),
                position: 0)
        }
    }

    private func streamingVocoderStep(groupMajor: [[Int]],
                                      state: StreamingVocoderState) async throws -> ([Float], StreamingVocoderState) {
        let frames = groupMajor.first?.count ?? 0
        guard groupMajor.count == Qwen3TtsProtocol.numCodebooks, frames > 0,
              groupMajor.allSatisfy({ $0.count == frames && $0.allSatisfy { (0..<2048).contains($0) } }) else {
            throw OrtInferenceFailure.badInput("streaming vocoder codes must be group-major [16][F], F>0")
        }
        var flat: [Int64] = []
        flat.reserveCapacity(Qwen3TtsProtocol.numCodebooks * frames)
        for g in 0..<Qwen3TtsProtocol.numCodebooks {
            for t in 0..<frames { flat.append(Int64(groupMajor[g][t])) }
        }
        let kvShape = [8, 1, 16, 71, 64]
        let outputs: [String: OnnxTensor]
        do {
            outputs = try await runStreamingVocoderGraph(inputs: [
                "codes": try OnnxTensor(int64Data: flat, shape: [1, Qwen3TtsProtocol.numCodebooks, frames]),
                "conv_state": try OnnxTensor(floatData: state.conv, shape: [Self.streamingConvState]),
                "past_keys": try OnnxTensor(floatData: state.keys, shape: kvShape),
                "past_values": try OnnxTensor(floatData: state.values, shape: kvShape),
                "position": try OnnxTensor(int64Data: [state.position], shape: [1]),
            ])
        } catch {
            throw error
        }
        guard let wave = outputs["waveform"],
              let conv = outputs["conv_state_out"],
              let keys = outputs["present_keys"],
              let values = outputs["present_values"],
              let pos = outputs["position_out"] else {
            throw OrtInferenceFailure.missingOutput(
                "vocoder", expected: ["waveform", "conv_state_out", "present_keys", "present_values", "position_out"])
        }
        var pcm = try wave.floatArray()
        let expected = frames * Qwen3TtsProtocol.samplesPerFrame
        guard pcm.count == expected, pcm.allSatisfy({ $0.isFinite }) else {
            throw OrtInferenceFailure.protocolMismatch(
                "streaming vocoder produced \(pcm.count) samples, expected \(expected)")
        }
        for i in pcm.indices { pcm[i] = max(-1, min(1, pcm[i])) }
        let nextPos = try pos.int64Array()
        guard nextPos.count == 1, nextPos[0] == state.position + Int64(frames),
              conv.shape == [Self.streamingConvState], keys.shape == kvShape, values.shape == kvShape else {
            throw OrtInferenceFailure.protocolMismatch("streaming vocoder state shape or position is invalid")
        }
        let next = StreamingVocoderState(
            conv: try conv.floatArray(), keys: try keys.floatArray(),
            values: try values.floatArray(), position: nextPos[0])
        guard next.conv.allSatisfy(\.isFinite), next.keys.allSatisfy(\.isFinite), next.values.allSatisfy(\.isFinite) else {
            throw OrtInferenceFailure.badOutput("streaming vocoder produced non-finite state")
        }
        return (pcm, next)
    }

    private static func framesToGroupMajor(_ frames: [[Int]]) -> [[Int]] {
        (0..<Qwen3TtsProtocol.numCodebooks).map { g in frames.map { $0[g] } }
    }

    private func currentVocoderIdentity() -> String {
        experimentalApi2?.streamingVocoder.path ?? "store:tts/vocoder"
    }

    private static func pcmIdentity(_ pcm: [Float]) -> String {
        var hash: UInt64 = 14_695_981_039_346_656_037
        pcm.withUnsafeBytes { raw in
            for byte in raw {
                hash ^= UInt64(byte)
                hash = hash &* 1_099_511_628_211
            }
        }
        return "\(pcm.count):\(String(hash, radix: 16))"
    }

    private func runUnifiedTalker(inputs: [String: OnnxTensor]) async throws -> [String: OnnxTensor] {
        if let graphs = experimentalApi2 {
            return try runOverlay(session: &api2Talker, modelURL: graphs.talker, inputs: inputs,
                                  expectedInputs: ["inputs_embeds", "attention_mask", "position_ids", "past_keys", "past_values"],
                                  expectedOutputs: ["logits", "last_hidden_state", "present_keys", "present_values"])
        }
        return try await runGraph(role: "talker", inputs: inputs)
    }

    private func runReferenceEncoder(inputs: [String: OnnxTensor]) async throws -> [String: OnnxTensor] {
        if let graphs = experimentalApi2 {
            let outputs = try runOverlay(session: &api2Encoder, modelURL: graphs.referenceEncoder, inputs: inputs,
                                         expectedInputs: ["pcm"], expectedOutputs: ["codes"])
            return outputs
        }
        return try await runGraph(role: "reference_encoder", inputs: inputs)
    }

    private func runStreamingVocoderGraph(inputs: [String: OnnxTensor]) async throws -> [String: OnnxTensor] {
        if let graphs = experimentalApi2 {
            return try runOverlay(session: &api2Vocoder, modelURL: graphs.streamingVocoder, inputs: inputs,
                                  expectedInputs: ["codes", "conv_state", "past_keys", "past_values", "position"],
                                  expectedOutputs: ["waveform", "conv_state_out", "present_keys", "present_values", "position_out"])
        }
        return try await runGraph(role: "vocoder", inputs: inputs)
    }

    private func runOverlay(session: inout OrtInferenceSession?, modelURL: URL,
                            inputs: [String: OnnxTensor],
                            expectedInputs: [String], expectedOutputs: [String]) throws -> [String: OnnxTensor] {
        try Task.checkCancellation()
        if session == nil {
            if api2Env == nil {
                api2Env = try ORTEnv(loggingLevel: ORTLoggingLevel.warning)
            }
            let created = try OrtInferenceSession(
                env: api2Env!, modelPath: modelURL.path,
                intraOpNumThreads: modelManager.sessionStore.intraOpThreads)
            let haveIn = Set(created.inputNames)
            let haveOut = Set(created.outputNames)
            guard expectedInputs.allSatisfy({ haveIn.contains($0) }),
                  expectedOutputs.allSatisfy({ haveOut.contains($0) }) else {
                throw OrtInferenceFailure.protocolMismatch(
                    "API2 graph \(modelURL.lastPathComponent) I/O \(created.inputNames)/\(created.outputNames) "
                    + "!= \(expectedInputs)/\(expectedOutputs)")
            }
            session = created
        }
        guard let loaded = session else {
            throw OrtInferenceFailure.notLoaded("API2")
        }
        let outputs = try loaded.run(inputs: inputs, outputNames: Set(loaded.outputNames))
        try Task.checkCancellation()
        return outputs
    }

    private func releaseApi2GenerationSessions() async {
        api2Talker = nil
        api2Vocoder = nil
        api2Encoder = nil
        if experimentalApi2 == nil {
            for role in ["talker", "vocoder", "reference_encoder"] {
                await modelManager.sessionStore.release(role: role, packageId: Self.packageId)
            }
        }
    }

    // MARK: - Graph passes

    private func generateFrames(config: Qwen3TtsConfig, tables: Qwen3TtsEmbeddings,
                                state: Qwen3TtsProtocol.PrefillState,
                                trailing: [Float],
                                temperature: Float, topK: Int,
                                repetitionPenalty: Float,
                                maxFrames: Int, seed: UInt64,
                                unifiedTalker: Bool = false,
                                onFrame: (([Int]) async throws -> Void)? = nil) async throws -> ([[Int]], [Int]) {
        var logits = state.logits
        var hidden = state.hidden
        var pastKeys = state.pastKeys
        var pastValues = state.pastValues
        var pastLen = state.seqLen

        var generator = Qwen3TtsProtocol.SplitMix64(seed: seed)
        var generated: [Int] = []
        var allCodes: [[Int]] = []
        let H = config.hiddenSize
        let trailingRows = trailing.count / H  // text rows + final tts_eos row

        for step in 0..<maxFrames {
            // Each frame is one cancellation checkpoint (16 code-predictor +
            // 1 decode graph calls); a stop must not wait for the full budget.
            try Task.checkCancellation()
            // Group 0 from the talker logits.
            let lastLogits = Array(logits.suffix(config.talkerVocab))
            guard lastLogits.allSatisfy({ !$0.isNaN && $0 != .infinity }) else {
                throw OrtInferenceFailure.badOutput(
                    "talker logits contain non-finite values at step \(step)")
            }
            let suppressEos = step < 2  // min_new_tokens=2 (reference behavior)
            let g0 = try Qwen3TtsProtocol.sampleGroup0(
                logits: lastLogits, config: config, temperature: temperature, topK: topK,
                repetitionPenalty: repetitionPenalty, generated: generated,
                random: &generator, suppressEos: suppressEos)
            if g0 == config.codecEosId { break }
            generated.append(g0)

            // Code predictor: groups 1..15, fresh KV per frame.
            var frame = [Int](repeating: 0, count: Qwen3TtsProtocol.numCodebooks)
            frame[0] = g0
            var cpKeys: [Float] = []
            var cpValues: [Float] = []
            var cpPastLen = 0
            for g in 1..<Qwen3TtsProtocol.numCodebooks {
                let cpInput: [Float]
                let cpInputSeq: Int
                if g == 1 {
                    // CP prefill: [talker hidden last position, group-0 embedding]
                    cpInput = Array(hidden.suffix(H))
                        + (try tables.talkerCodecEmbedding(tokenId: g0))
                    cpInputSeq = 2
                } else {
                    cpInput = try tables.cpCodecEmbedding(groupIndex: g - 2, tokenId: frame[g - 1])
                    cpInputSeq = 1
                }
                let cpOutputs = try await runGraph(role: "code_predictor", inputs: [
                    "inputs_embeds": try OnnxTensor(
                        floatData: cpInput, shape: [1, cpInputSeq, H]),
                    "generation_steps": try OnnxTensor(
                        int64Data: [Int64(g - 1)], shape: [1]),
                    "past_keys": try OnnxTensor(
                        floatData: cpKeys,
                        shape: [config.cpLayers, 1, config.cpKvHeads, cpPastLen, config.cpHeadDim]),
                    "past_values": try OnnxTensor(
                        floatData: cpValues,
                        shape: [config.cpLayers, 1, config.cpKvHeads, cpPastLen, config.cpHeadDim]),
                ])
                guard let cpLogits = cpOutputs["logits"] else {
                    throw OrtInferenceFailure.missingOutput(
                        "code_predictor", expected: ["logits"])
                }
                let logitsFlat = try cpLogits.floatArray()
                let cpSlice = Array(logitsFlat.suffix(config.cpVocab))
                guard cpSlice.allSatisfy({ !$0.isNaN && $0 != .infinity }) else {
                    throw OrtInferenceFailure.badOutput(
                        "code_predictor logits contain non-finite values (group \(g), step \(step))")
                }
                let token = try Qwen3TtsProtocol.sampleCodePredictor(
                    logits: cpSlice, config: config,
                    temperature: temperature, topK: topK, random: &generator)
                frame[g] = token
                cpKeys = try readKv(cpOutputs, name: "present_keys", expected: [
                    config.cpLayers, 1, config.cpKvHeads, cpPastLen + cpInputSeq, config.cpHeadDim])
                cpValues = try readKv(cpOutputs, name: "present_values", expected: [
                    config.cpLayers, 1, config.cpKvHeads, cpPastLen + cpInputSeq, config.cpHeadDim])
                cpPastLen += cpInputSeq
            }
            allCodes.append(frame)
            if let onFrame { try await onFrame(frame) }

            // Next talker input: sum of 16 group embeddings + text/pad.
            var next = try tables.talkerCodecEmbedding(tokenId: frame[0])
            for g in 1..<Qwen3TtsProtocol.numCodebooks {
                let cpEmb = try tables.cpCodecEmbedding(groupIndex: g - 1, tokenId: frame[g])
                for j in 0..<H { next[j] += cpEmb[j] }
            }
            if step < trailingRows {
                for j in 0..<H { next[j] += trailing[step * H + j] }
            } else {
                let ttsPad = tables.project(try tables.textEmbed(tokenId: config.ttsPadTokenId))
                for j in 0..<H { next[j] += ttsPad[j] }
            }

            // One decode step.
            let totalLen = pastLen + 1
            let position = Int64(state.seqLen + step)
            let decodeInputs: [String: OnnxTensor] = [
                "inputs_embeds": try OnnxTensor(floatData: next, shape: [1, 1, H]),
                "attention_mask": try OnnxTensor(
                    int64Data: Array(repeating: 1, count: totalLen), shape: [1, totalLen]),
                "position_ids": try OnnxTensor(
                    int64Data: [position, position, position], shape: [3, 1, 1]),
                "past_keys": try OnnxTensor(
                    floatData: pastKeys,
                    shape: [config.numLayers, 1, config.numKvHeads, pastLen, config.headDim]),
                "past_values": try OnnxTensor(
                    floatData: pastValues,
                    shape: [config.numLayers, 1, config.numKvHeads, pastLen, config.headDim]),
            ]
            let decOutputs: [String: OnnxTensor]
            if unifiedTalker {
                decOutputs = try await runUnifiedTalker(inputs: decodeInputs)
            } else {
                decOutputs = try await runGraph(role: "talker_decode", inputs: decodeInputs)
            }
            let hiddenName = unifiedTalker ? "last_hidden_state" : "hidden_states"
            guard let decLogits = decOutputs["logits"],
                  let decHidden = decOutputs[hiddenName] else {
                throw OrtInferenceFailure.missingOutput(
                    unifiedTalker ? "talker" : "talker_decode",
                    expected: ["logits", hiddenName])
            }
            logits = try decLogits.floatArray()
            hidden = try decHidden.floatArray()
            guard hidden.count == H else {
                throw OrtInferenceFailure.protocolMismatch(
                    "decode hidden size \(hidden.count) != \(H)")
            }
            pastKeys = try readKv(decOutputs, name: "present_keys", expected: [
                config.numLayers, 1, config.numKvHeads, totalLen, config.headDim])
            pastValues = try readKv(decOutputs, name: "present_values", expected: [
                config.numLayers, 1, config.numKvHeads, totalLen, config.headDim])
            pastLen = totalLen
        }
        guard allCodes.count < maxFrames else {
            throw OrtInferenceFailure.badOutput("frame budget exhausted before codec EOS; refusing truncated speech")
        }
        return (allCodes, generated)
    }

    private func runVocoder(codes: [[Int]]) async throws -> [Float] {
        // The vocoder consumes codes[1, 16, T] GROUP-major: flat[g * T + t].
        // Emitting frame-major data under this shape transposes the code
        // matrix — sample count still matches, so only the audio is garbage.
        var flat: [Int64] = []
        flat.reserveCapacity(codes.count * Qwen3TtsProtocol.numCodebooks)
        for group in 0..<Qwen3TtsProtocol.numCodebooks {
            for frame in codes {
                flat.append(Int64(frame[group]))
            }
        }
        let outputs = try await runGraph(role: "vocoder", inputs: [
            "codes": try OnnxTensor(
                int64Data: flat,
                shape: [1, Qwen3TtsProtocol.numCodebooks, codes.count]),
        ])
        guard let output = outputs["waveform"] else {
            throw OrtInferenceFailure.missingOutput("vocoder", expected: ["waveform"])
        }
        var waveform = try output.floatArray()
        let expected = codes.count * Qwen3TtsProtocol.samplesPerFrame
        guard waveform.count == expected else {
            throw OrtInferenceFailure.protocolMismatch(
                "vocoder produced \(waveform.count) samples, expected \(expected)")
        }
        for i in waveform.indices { waveform[i] = max(-1, min(1, waveform[i])) }
        return waveform
    }

    private func readKv(_ outputs: [String: OnnxTensor], name: String,
                        expected: [Int]) throws -> [Float] {
        guard let tensor = outputs[name] else {
            throw OrtInferenceFailure.missingOutput(name, expected: [name])
        }
        guard tensor.shape == expected else {
            throw OrtInferenceFailure.protocolMismatch(
                "KV tensor '\(name)' shape \(tensor.shape) != expected \(expected)")
        }
        return try tensor.floatArray()
    }

    private func runGraph(role: String,
                          inputs: [String: OnnxTensor]) async throws -> [String: OnnxTensor] {
        try Task.checkCancellation()
        let outputs = try await modelManager.sessionStore.run(
            role: role, packageId: Self.packageId, inputs: inputs)
        try Task.checkCancellation()
        return outputs
    }

    private static func languageKey(for language: String,
                                    config: Qwen3TtsConfig) throws -> String {
        let aliases: [String: String] = [
            "zh": "chinese", "zh-cn": "chinese", "chinese": "chinese",
            "en": "english", "en-us": "english", "english": "english",
            "de": "german", "german": "german",
            "it": "italian", "italian": "italian",
            "pt": "portuguese", "portuguese": "portuguese",
            "es": "spanish", "spanish": "spanish",
            "ja": "japanese", "japanese": "japanese",
            "ko": "korean", "korean": "korean",
            "fr": "french", "french": "french",
            "ru": "russian", "russian": "russian",
        ]
        let key = language.lowercased().replacingOccurrences(of: "_", with: "-")
        guard let name = aliases[key] else {
            throw OrtInferenceFailure.badInput("unsupported TTS language: \(language)")
        }
        guard config.languageIds[name] != nil else {
            throw OrtInferenceFailure.protocolMismatch(
                "bundle config has no language id for '\(name)'; "
                + "known: \(config.languageIds.keys.sorted())")
        }
        return name
    }

    func release() async {
        referenceGeneration = UUID()
        isLoaded = false
        speakerEncoderLoaded = false
        tokenizer = nil
        embeddings = nil
        config = nil
        api2Talker = nil
        api2Vocoder = nil
        api2Encoder = nil
        api2Env = nil
        manifestApiContractVersion = "1"
        await modelManager.sessionStore.release(packageId: Self.packageId)
    }
}

// ---------------------------------------------------------------------------
// Bundle config

struct Qwen3TtsConfig {
    let talker: [String: Int]
    let codePredictor: [String: Int]
    let tts: [String: Int]
    let languageIds: [String: Int]

    var hiddenSize: Int { talker["hidden_size"] ?? 0 }              // 1024
    var numLayers: Int { talker["num_hidden_layers"] ?? 0 }         // 28
    var numKvHeads: Int { talker["num_key_value_heads"] ?? 0 }      // 8
    var headDim: Int { talker["head_dim"] ?? 0 }                    // 128
    var talkerVocab: Int { talker["vocab_size"] ?? 0 }              // 3072
    var codecEosId: Int { talker["codec_eos_token_id"] ?? 0 }       // 2150
    var codecThinkId: Int { talker["codec_think_id"] ?? 0 }
    var codecThinkBosId: Int { talker["codec_think_bos_id"] ?? 0 }
    var codecThinkEosId: Int { talker["codec_think_eos_id"] ?? 0 }
    var codecPadId: Int { talker["codec_pad_id"] ?? 0 }
    var codecBosId: Int { talker["codec_bos_id"] ?? 0 }
    var cpVocab: Int { codePredictor["vocab_size"] ?? 0 }           // 2048
    var cpLayers: Int { codePredictor["num_hidden_layers"] ?? 0 }
    var cpKvHeads: Int { codePredictor["num_key_value_heads"] ?? 0 }
    var cpHeadDim: Int { codePredictor["head_dim"] ?? 0 }
    var ttsPadTokenId: Int { tts["tts_pad_token_id"] ?? 0 }
    var ttsBosTokenId: Int { tts["tts_bos_token_id"] ?? 0 }
    var ttsEosTokenId: Int { tts["tts_eos_token_id"] ?? 0 }

    init(data: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw OrtInferenceFailure.protocolMismatch("TTS config.json is not an object")
        }
        func section(_ key: String) throws -> [String: Int] {
            guard let dict = root[key] as? [String: Any] else {
                throw OrtInferenceFailure.protocolMismatch("TTS config missing '\(key)' section")
            }
            var result: [String: Int] = [:]
            for (name, value) in dict {
                guard let number = value as? NSNumber else { continue }
                result[name] = number.intValue
            }
            return result
        }
        talker = try section("talker")
        codePredictor = try section("code_predictor")
        tts = try section("tts")
        languageIds = try section("language_ids")
        guard talker["hidden_size"] != nil, talker["num_hidden_layers"] != nil,
              talker["num_key_value_heads"] != nil, talker["head_dim"] != nil,
              talker["vocab_size"] != nil, talker["codec_eos_token_id"] != nil else {
            throw OrtInferenceFailure.protocolMismatch("TTS talker config incomplete")
        }
    }
}

// ---------------------------------------------------------------------------
// Byte-level BPE tokenizer (Qwen2 style; vocab.json + merges.txt from bundle)

final class Qwen3TtsTokenizer {
    static let specialTokenIds: [(String, Int)] = [
        ("<|endoftext|>", 151643),
        ("<|im_start|>", 151644),
        ("<|im_end|>", 151645),
        ("<|audio_start|>", 151669),
        ("<|audio_end|>", 151670),
        ("<tts_pad>", 151671),
        ("<tts_text_bos>", 151672),
        ("<tts_text_eod>", 151673),
        ("<tts_text_bos_single>", 151674),
        ("<|audio_pad|>", 151675),
    ]

    /// GPT-2 pre-tokenization pattern (matches HF Qwen2Tokenizer / author C#).
    private static let gpt2Pattern =
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}|"
        + " ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"

    private let vocab: [String: Int]
    private let mergeRanks: [String: Int]
    private let byteEncoder: [String]
    private let regex: NSRegularExpression

    init(modelsDir: URL) throws {
        let vocabURL = modelsDir.appendingPathComponent("tts/tokenizer/vocab.json")
        let mergesURL = modelsDir.appendingPathComponent("tts/tokenizer/merges.txt")
        guard FileManager.default.fileExists(atPath: vocabURL.path),
              FileManager.default.fileExists(atPath: mergesURL.path) else {
            throw OrtInferenceFailure.tokenizerUnavailable(vocabURL.deletingLastPathComponent().path)
        }
        let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: vocabURL))
        guard let dict = raw as? [String: Any] else {
            throw OrtInferenceFailure.protocolMismatch("vocab.json is not an object")
        }
        var loaded: [String: Int] = [:]
        loaded.reserveCapacity(dict.count + Self.specialTokenIds.count)
        for (piece, value) in dict {
            guard let number = value as? NSNumber else { continue }
            loaded[piece] = number.intValue
        }
        for (token, id) in Self.specialTokenIds {
            if loaded[token] == nil { loaded[token] = id }
            guard loaded[token] == id else {
                throw OrtInferenceFailure.protocolMismatch(
                    "special token '\(token)' must map to \(id), "
                    + "vocab says \(loaded[token] ?? -1)")
            }
        }
        vocab = loaded

        var ranks: [String: Int] = [:]
        var rank = 0
        for line in try String(contentsOf: mergesURL, encoding: .utf8)
            .components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#"),
                  trimmed.components(separatedBy: " ").count == 2 else { continue }
            ranks[trimmed] = rank
            rank += 1
        }
        mergeRanks = ranks

        // GPT-2 bytes_to_unicode: printable bytes map to themselves, the rest
        // to 256+n in byte order.
        var printable = Set<Int>()
        for b in 33...126 { printable.insert(b) }
        for b in 161...172 { printable.insert(b) }
        for b in 174...255 { printable.insert(b) }
        var table: [String] = []
        table.reserveCapacity(256)
        var n = 0
        for byte in 0..<256 {
            let codePoint = printable.contains(byte) ? byte : { defer { n += 1 }; return 256 + n }()
            if let scalar = Unicode.Scalar(codePoint) {
                table.append(String(Character(scalar)))
            } else {
                table.append("\u{FFFD}")
            }
        }
        byteEncoder = table

        regex = try NSRegularExpression(pattern: Self.gpt2Pattern)
    }

    func encode(_ text: String) throws -> [Int] {
        var ids: [Int] = []
        var index = text.startIndex
        while index < text.endIndex {
            // Special tokens are atomic and must not be byte-split.
            var handled = false
            for (token, id) in Self.specialTokenIds {
                if text[index...].hasPrefix(token) {
                    ids.append(id)
                    index = text.index(index, offsetBy: token.count, limitedBy: text.endIndex)
                        ?? text.endIndex
                    handled = true
                    break
                }
            }
            if handled { continue }

            // Run up to the next special token occurrence.
            var runEnd = text.endIndex
            let searchStart = text.index(after: index)
            if searchStart < text.endIndex {
                for (token, _) in Self.specialTokenIds {
                    if let at = text.range(of: token, range: searchStart..<text.endIndex),
                       at.lowerBound < runEnd {
                        runEnd = at.lowerBound
                    }
                }
            }
            let chunk = String(text[index..<runEnd])
            let nsrange = NSRange(chunk.startIndex..<chunk.endIndex, in: chunk)
            for match in regex.matches(in: chunk, range: nsrange) {
                guard let pieceRange = Range(match.range, in: chunk) else { continue }
                for piece in try bpe(String(chunk[pieceRange])) {
                    guard let id = vocab[piece] else {
                        // Unknown piece is a tokenizer/decoder mismatch, not a
                        // byte-offset guess.
                        throw OrtInferenceFailure.protocolMismatch(
                            "BPE piece '\(piece)' not in vocab; tokenizer/decoder mismatch")
                    }
                    ids.append(id)
                }
            }
            index = runEnd
        }
        return ids
    }

    func buildPromptIds(text: String) throws -> [Int] {
        try encode("<|im_start|>assistant\n" + text + "<|im_end|>\n<|im_start|>assistant\n")
    }

    func buildReferencePromptIds(text: String) throws -> [Int] {
        try encode("<|im_start|>assistant\n" + text + "<|im_end|>\n")
    }

    /// Classic lowest-rank-pair BPE over one pre-token.
    private func bpe(_ preToken: String) throws -> [String] {
        guard !preToken.isEmpty else { return [] }
        var symbols = Array(preToken.utf8).map { byteEncoder[Int($0)] }
        while symbols.count > 1 {
            var bestRank = Int.max
            var bestIdx = -1
            for j in 0..<(symbols.count - 1) {
                if let rank = mergeRanks["\(symbols[j]) \(symbols[j + 1])"], rank < bestRank {
                    bestRank = rank
                    bestIdx = j
                }
            }
            guard bestIdx >= 0 else { break }
            let merged = symbols[bestIdx] + symbols[bestIdx + 1]
            var next: [String] = []
            next.reserveCapacity(symbols.count - 1)
            var j = 0
            while j < symbols.count {
                if j == bestIdx { next.append(merged); j += 2 }
                else { next.append(symbols[j]); j += 1 }
            }
            symbols = next
        }
        return symbols
    }
}

// ---------------------------------------------------------------------------
// .npy tables (float32, C order; large files memory-mapped). 1-D vectors load
// as a single row — the bundle's projection biases ship as (N,), not (1, N).

final class NpyFloat2D {
    let rows: Int
    let cols: Int
    private let data: Data
    private let headerBytes: Int

    init(url: URL) throws {
        let raw = try Data(contentsOf: url, options: .mappedIfSafe)
        guard raw.count >= 10 else {
            throw OrtInferenceFailure.badOutput("npy too small: \(url.lastPathComponent)")
        }
        let magicBytes = [UInt8](raw[0..<6])
        let magic = String(bytes: magicBytes, encoding: .isoLatin1)
        guard magic == "\u{93}NUMPY" else {
            throw OrtInferenceFailure.badOutput("not an npy file: \(url.lastPathComponent)")
        }
        let major = Int(raw[6])
        let prefixLen: Int
        let headerLen: Int
        if major == 1 {
            prefixLen = 10
            headerLen = Int(raw[8]) | (Int(raw[9]) << 8)
        } else if major == 2 {
            prefixLen = 12
            headerLen = Int(raw[8]) | (Int(raw[9]) << 8)
                | (Int(raw[10]) << 16) | (Int(raw[11]) << 24)
        } else {
            throw OrtInferenceFailure.badOutput("unsupported npy major version \(major)")
        }
        headerBytes = prefixLen + headerLen
        guard raw.count >= headerBytes else {
            throw OrtInferenceFailure.badOutput("truncated npy header: \(url.lastPathComponent)")
        }
        let header = String(bytes: [UInt8](raw[prefixLen..<headerBytes]),
                            encoding: .isoLatin1) ?? ""

        let descr = try Self.captured(header, pattern: "'descr'\\s*:\\s*'([^']*)'",
                                      field: "descr")
        guard descr.contains("f4") || descr.contains("float32") else {
            throw OrtInferenceFailure.badOutput("expected float32 npy, got \(descr)")
        }
        let fortran = try Self.captured(header,
                                        pattern: "'fortran_order'\\s*:\\s*(True|False|true|false)",
                                        field: "fortran_order")
        guard fortran.lowercased() == "false" else {
            throw OrtInferenceFailure.badOutput("fortran-order npy not supported")
        }
        let shapeRaw = try Self.captured(header, pattern: "'shape'\\s*:\\s*\\(([^)]*)\\)",
                                         field: "shape")
        let dims = shapeRaw
            .components(separatedBy: CharacterSet(charactersIn: ","))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .compactMap { Int($0) }
        guard (1...2).contains(dims.count) else {
            throw OrtInferenceFailure.badOutput("expected 1-D or 2-D npy, got [\(shapeRaw)]")
        }
        // 1-D (N,) is a single row for row-indexing purposes.
        rows = dims.count == 1 ? 1 : dims[0]
        cols = dims.last ?? 0
        data = raw
    }

    /// First capture group of a single-capture pattern.
    private static func captured(_ text: String, pattern: String, field: String) throws -> String {
        let regex = try NSRegularExpression(pattern: pattern)
        let nsrange = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, range: nsrange),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: text) else {
            throw OrtInferenceFailure.badOutput("npy header missing \(field)")
        }
        return String(text[range])
    }

    func row(_ index: Int) throws -> [Float] {
        guard index >= 0 && index < rows else {
            throw OrtInferenceFailure.badOutput("row \(index) out of range (\(rows))")
        }
        let start = headerBytes + index * cols * 4
        let end = start + cols * 4
        return try data[start..<end].withUnsafeBytes { (buffer: UnsafeRawBufferPointer) -> [Float] in
            let pointer = buffer.baseAddress?.assumingMemoryBound(to: Float.self)
            guard let pointer else {
                throw OrtInferenceFailure.badOutput("npy row unreadable")
            }
            return Array(UnsafeBufferPointer(start: pointer, count: cols))
        }
    }
}

final class Qwen3TtsEmbeddings {
    private let textEmbedding: NpyFloat2D
    private let fc1Weight: NpyFloat2D
    private let fc1Bias: [Float]
    private let fc2Weight: NpyFloat2D
    private let fc2Bias: [Float]
    private let talkerCodec: NpyFloat2D
    private let cpCodecs: [NpyFloat2D]

    init(modelsDir: URL) throws {
        let dir = modelsDir.appendingPathComponent("tts/embeddings")
        textEmbedding = try NpyFloat2D(url: dir.appendingPathComponent("text_embedding.npy"))
        fc1Weight = try NpyFloat2D(url: dir.appendingPathComponent("text_projection_fc1_weight.npy"))
        fc2Weight = try NpyFloat2D(url: dir.appendingPathComponent("text_projection_fc2_weight.npy"))
        // Biases ship as (1, N) 2-D npy.
        let fc1BiasTable = try NpyFloat2D(
            url: dir.appendingPathComponent("text_projection_fc1_bias.npy"))
        let fc2BiasTable = try NpyFloat2D(
            url: dir.appendingPathComponent("text_projection_fc2_bias.npy"))
        talkerCodec = try NpyFloat2D(url: dir.appendingPathComponent("talker_codec_embedding.npy"))
        cpCodecs = try (0..<15).map {
            try NpyFloat2D(url: dir.appendingPathComponent("cp_codec_embedding_\($0).npy"))
        }

        func vector(_ table: NpyFloat2D) throws -> [Float] {
            guard table.rows == 1 else {
                throw OrtInferenceFailure.badOutput("expected 1-D or (1, N) bias npy")
            }
            return try table.row(0)
        }
        fc1Bias = try vector(fc1BiasTable)
        fc2Bias = try vector(fc2BiasTable)
        guard fc1Weight.cols == textEmbedding.cols,
              fc2Weight.cols == fc1Weight.rows,
              talkerCodec.cols == fc2Weight.rows,
              cpCodecs.allSatisfy({ $0.cols == fc2Weight.rows }) else {
            throw OrtInferenceFailure.protocolMismatch(
                "text projection/embedding shapes inconsistent")
        }
    }

    var hiddenSize: Int { fc2Weight.rows }
    var textHiddenSize: Int { textEmbedding.cols }

    func textEmbed(tokenId: Int) throws -> [Float] { try textEmbedding.row(tokenId) }

    /// fc2(silu(fc1(x))) with biases; maps 2048-d text space -> talker space.
    func project(_ raw: [Float]) -> [Float] {
        var hidden = [Float](repeating: 0, count: fc1Weight.rows)
        for i in 0..<fc1Weight.rows {
            guard let row = try? fc1Weight.row(i) else { continue }
            var sum: Float = 0
            for j in raw.indices { sum += row[j] * raw[j] }
            let h = sum + fc1Bias[i]
            hidden[i] = h / (1 + exp(-h))  // SiLU
        }
        var out = [Float](repeating: 0, count: fc2Weight.rows)
        for i in 0..<fc2Weight.rows {
            guard let row = try? fc2Weight.row(i) else { continue }
            var sum: Float = 0
            for j in hidden.indices { sum += row[j] * hidden[j] }
            out[i] = sum + fc2Bias[i]
        }
        return out
    }

    func talkerCodecEmbedding(tokenId: Int) throws -> [Float] {
        try talkerCodec.row(tokenId)
    }

    /// CP groups 1..15 use embedding tables indexed 0..14.
    func cpCodecEmbedding(groupIndex: Int, tokenId: Int) throws -> [Float] {
        guard groupIndex >= 0 && groupIndex < 15 else {
            throw OrtInferenceFailure.badOutput(
                "cp group index must be 0..14, got \(groupIndex)")
        }
        return try cpCodecs[groupIndex].row(tokenId)
    }
}

// ---------------------------------------------------------------------------
// Pure protocol implementation (ports verified against the host runner)

enum Qwen3TtsProtocol {
    static let samplesPerFrame = 1920
    static let numCodebooks = 16
    static let speakerEmbeddingDim = 1024
    static let maxReferenceFrames = 24000 * 30 / 1920

    /// Deterministic PRNG (Swift stdlib has no seeded generator).
    struct SplitMix64 {
        private var state: UInt64
        init(seed: UInt64) { state = seed &+ 0x1234_5678_9abc_def0 }
        mutating func nextDouble() -> Double {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            z = z ^ (z >> 31)
            return Double(z) / Double(UInt64.max)
        }
    }

    struct PrefillState {
        let logits: [Float]
        let hidden: [Float]
        let pastKeys: [Float]    // stacked [layers, 1, kvHeads, seqLen, headDim]
        let pastValues: [Float]
        let seqLen: Int

        init(outputs: [String: OnnxTensor], config: Qwen3TtsConfig) throws {
            guard let logitsTensor = outputs["logits"],
                  let hiddenTensor = outputs["hidden_states"] else {
                throw OrtInferenceFailure.missingOutput(
                    "talker_prefill", expected: ["logits", "hidden_states"])
            }
            logits = try logitsTensor.floatArray()
            guard logits.count == config.talkerVocab else {
                throw OrtInferenceFailure.protocolMismatch(
                    "prefill logits size \(logits.count) != vocab \(config.talkerVocab)")
            }
            hidden = try hiddenTensor.floatArray()
            seqLen = hidden.count / config.hiddenSize
            let expectedKv = [1, config.numKvHeads, seqLen, config.headDim]
            var keys: [Float] = []
            var values: [Float] = []
            keys.reserveCapacity(config.numLayers * config.numKvHeads * seqLen * config.headDim)
            values.reserveCapacity(keys.capacity)
            for layer in 0..<config.numLayers {
                guard let key = outputs["present_key_\(layer)"],
                      let value = outputs["present_value_\(layer)"] else {
                    throw OrtInferenceFailure.missingOutput(
                        "talker_prefill", expected: ["present_key_\(layer)"])
                }
                guard key.shape == expectedKv, value.shape == expectedKv else {
                    throw OrtInferenceFailure.protocolMismatch(
                        "prefill KV layer \(layer) shape \(key.shape) != \(expectedKv)")
                }
                keys += try key.floatArray()
                values += try value.floatArray()
            }
            pastKeys = keys
            pastValues = values
        }

        init(api2Outputs: [String: OnnxTensor], config: Qwen3TtsConfig) throws {
            guard let logitsTensor = api2Outputs["logits"],
                  let hiddenTensor = api2Outputs["last_hidden_state"],
                  let keysTensor = api2Outputs["present_keys"],
                  let valuesTensor = api2Outputs["present_values"] else {
                throw OrtInferenceFailure.missingOutput(
                    "talker", expected: ["logits", "last_hidden_state", "present_keys", "present_values"])
            }
            logits = try logitsTensor.floatArray()
            guard logits.count == config.talkerVocab else {
                throw OrtInferenceFailure.protocolMismatch(
                    "API2 logits size \(logits.count) != vocab \(config.talkerVocab)")
            }
            hidden = try hiddenTensor.floatArray()
            guard hidden.count == config.hiddenSize else {
                throw OrtInferenceFailure.protocolMismatch(
                    "API2 last_hidden_state size \(hidden.count) != \(config.hiddenSize)")
            }
            guard keysTensor.shape.count == 5 else {
                throw OrtInferenceFailure.protocolMismatch("API2 present_keys rank \(keysTensor.shape)")
            }
            let total = keysTensor.shape[3]
            let expectedKv = [config.numLayers, 1, config.numKvHeads, total, config.headDim]
            guard total >= 1, keysTensor.shape == expectedKv, valuesTensor.shape == expectedKv else {
                throw OrtInferenceFailure.protocolMismatch(
                    "API2 KV shape \(keysTensor.shape) != \(expectedKv)")
            }
            seqLen = total
            pastKeys = try keysTensor.floatArray()
            pastValues = try valuesTensor.floatArray()
        }
    }

    struct PrefillEmbeddings {
        let embeds: [Float]    // [seqLen * hidden]
        let trailing: [Float]  // text rows + final tts_eos row
    }

    /// Prefill embedding sequence (author's BuildPrefillEmbedding): role embeds,
    /// codec prefix with the speaker embedding injected at its slot (each
    /// combined with tts_pad; second-to-last slot with tts_bos), first text
    /// token + codec_bos. Trailing: projected tokens[4..len-6] then tts_eos.
    static func buildPrefillEmbeddings(
        tokenIds: [Int], speakerEmbedding: [Float], languageKey: String,
        config: Qwen3TtsConfig, tables: Qwen3TtsEmbeddings
    ) throws -> PrefillEmbeddings {
        guard tokenIds.count >= 9 else {
            throw OrtInferenceFailure.badInput(
                "prompt too short (\(tokenIds.count) tokens); text is unusable")
        }
        let H = config.hiddenSize
        guard let languageId = config.languageIds[languageKey] else {
            throw OrtInferenceFailure.protocolMismatch(
                "bundle config has no language id for '\(languageKey)'")
        }
        var prefix = [config.codecThinkId, config.codecThinkBosId, languageId,
                      config.codecThinkEosId]
        let speakerPos = prefix.count
        prefix.append(config.codecPadId)  // speaker placeholder
        prefix.append(config.codecPadId)
        prefix.append(config.codecBosId)

        let ttsPad = tables.project(try tables.textEmbed(tokenId: config.ttsPadTokenId))
        let ttsBos = tables.project(try tables.textEmbed(tokenId: config.ttsBosTokenId))
        let ttsEos = tables.project(try tables.textEmbed(tokenId: config.ttsEosTokenId))

        var positions: [[Float]] = []
        for i in 0..<3 {
            positions.append(tables.project(try tables.textEmbed(tokenId: tokenIds[i])))
        }
        for i in 0..<(prefix.count - 2) {
            var combined = ttsPad
            let codec = (i == speakerPos)
                ? speakerEmbedding
                : (try tables.talkerCodecEmbedding(tokenId: prefix[i]))
            for j in 0..<H { combined[j] += codec[j] }
            positions.append(combined)
        }
        var last = ttsBos
        let lastCodec = try tables.talkerCodecEmbedding(tokenId: prefix[prefix.count - 2])
        for j in 0..<H { last[j] += lastCodec[j] }
        positions.append(last)

        var firstText = tables.project(try tables.textEmbed(tokenId: tokenIds[3]))
        let codecBos = try tables.talkerCodecEmbedding(tokenId: config.codecBosId)
        for j in 0..<H { firstText[j] += codecBos[j] }
        positions.append(firstText)

        var embeds: [Float] = []
        embeds.reserveCapacity(positions.count * H)
        for vector in positions { embeds.append(contentsOf: vector) }

        let trailingCount = max(0, tokenIds.count - 9)
        var trailing: [Float] = []
        trailing.reserveCapacity((trailingCount + 1) * H)
        for t in 0..<trailingCount {
            trailing.append(contentsOf:
                tables.project(try tables.textEmbed(tokenId: tokenIds[4 + t])))
        }
        trailing.append(contentsOf: ttsEos)
        return PrefillEmbeddings(embeds: embeds, trailing: trailing)
    }

    /// Official streaming-text ICL body: ref_ids[3:-2] + target_ids[3:-5], 16-group
    /// codec sum, codec BOS, then pad or trailing. Does not change xvector math.
    static func buildIclPrefillEmbeddings(
        tokenIds: [Int], referenceTokenIds: [Int], referenceCodesGroupMajor: [[Int]],
        speakerEmbedding: [Float], languageKey: String,
        config: Qwen3TtsConfig, tables: Qwen3TtsEmbeddings
    ) throws -> PrefillEmbeddings {
        guard tokenIds.count >= 9, referenceTokenIds.count >= 6 else {
            throw OrtInferenceFailure.badInput(
                "ICL requires nonempty wrapped target and reference token sequences")
        }
        guard referenceCodesGroupMajor.count == numCodebooks else {
            throw OrtInferenceFailure.badInput("reference codes must be group-major [16][R]")
        }
        let R = referenceCodesGroupMajor[0].count
        guard (1...maxReferenceFrames).contains(R),
              referenceCodesGroupMajor.allSatisfy({ $0.count == R }) else {
            throw OrtInferenceFailure.badInput(
                "reference codes must be int64 [16, frames] for a nonempty reference of at most 30 seconds")
        }
        let H = config.hiddenSize
        guard let languageId = config.languageIds[languageKey] else {
            throw OrtInferenceFailure.protocolMismatch(
                "bundle config has no language id for '\(languageKey)'")
        }
        var prefix = [config.codecThinkId, config.codecThinkBosId, languageId,
                      config.codecThinkEosId]
        let speakerPos = prefix.count
        prefix.append(config.codecPadId)
        prefix.append(config.codecPadId)
        prefix.append(config.codecBosId)
        let ttsPad = tables.project(try tables.textEmbed(tokenId: config.ttsPadTokenId))
        let ttsBos = tables.project(try tables.textEmbed(tokenId: config.ttsBosTokenId))
        let ttsEos = tables.project(try tables.textEmbed(tokenId: config.ttsEosTokenId))
        var positions: [[Float]] = []
        for i in 0..<3 {
            positions.append(tables.project(try tables.textEmbed(tokenId: tokenIds[i])))
        }
        for i in 0..<(prefix.count - 2) {
            var combined = ttsPad
            let codec = (i == speakerPos)
                ? speakerEmbedding
                : (try tables.talkerCodecEmbedding(tokenId: prefix[i]))
            for j in 0..<H { combined[j] += codec[j] }
            positions.append(combined)
        }
        var last = ttsBos
        let lastCodec = try tables.talkerCodecEmbedding(tokenId: prefix[prefix.count - 2])
        for j in 0..<H { last[j] += lastCodec[j] }
        positions.append(last)

        let refSlice = Array(referenceTokenIds.dropFirst(3).dropLast(2))
        let tgtSlice = Array(tokenIds.dropFirst(3).dropLast(5))
        var textEmbeds: [[Float]] = []
        textEmbeds.reserveCapacity(refSlice.count + tgtSlice.count + 1)
        for token in refSlice + tgtSlice {
            textEmbeds.append(tables.project(try tables.textEmbed(tokenId: token)))
        }
        textEmbeds.append(ttsEos)
        var codecEmbeds: [[Float]] = [try tables.talkerCodecEmbedding(tokenId: config.codecBosId)]
        codecEmbeds.reserveCapacity(R + 1)
        for f in 0..<R {
            var summed = try tables.talkerCodecEmbedding(tokenId: referenceCodesGroupMajor[0][f])
            for g in 1..<numCodebooks {
                let part = try tables.cpCodecEmbedding(
                    groupIndex: g - 1, tokenId: referenceCodesGroupMajor[g][f])
                for j in 0..<H { summed[j] += part[j] }
            }
            codecEmbeds.append(summed)
        }
        let length = codecEmbeds.count
        var trailing: [Float] = []
        if textEmbeds.count > length {
            for i in 0..<length {
                var row = textEmbeds[i]
                for j in 0..<H { row[j] += codecEmbeds[i][j] }
                positions.append(row)
            }
            for i in length..<textEmbeds.count {
                trailing.append(contentsOf: textEmbeds[i])
            }
        } else {
            for i in 0..<length {
                var row = i < textEmbeds.count ? textEmbeds[i] : ttsPad
                for j in 0..<H { row[j] += codecEmbeds[i][j] }
                positions.append(row)
            }
            trailing.append(contentsOf: ttsPad)
        }
        var embeds: [Float] = []
        embeds.reserveCapacity(positions.count * H)
        for vector in positions { embeds.append(contentsOf: vector) }
        return PrefillEmbeddings(embeds: embeds, trailing: trailing)
    }

    /// Group-0 sampling: repetition penalty, suppression of the codec range
    /// outside [0, cpVocab) except codec EOS, temperature, top-k, softmax.
    static func sampleGroup0(logits: [Float], config: Qwen3TtsConfig,
                             temperature: Float, topK: Int, repetitionPenalty: Float,
                             generated: [Int], random: inout SplitMix64,
                             suppressEos: Bool) throws -> Int {
        let vocab = config.talkerVocab
        guard vocab > 0, config.cpVocab > 0, config.cpVocab <= vocab,
              (0..<vocab).contains(config.codecEosId), logits.count >= vocab,
              logits.allSatisfy({ !$0.isNaN && $0 != .infinity }),
              generated.allSatisfy({ (0..<vocab).contains($0) }),
              repetitionPenalty.isFinite, repetitionPenalty > 0 else {
            throw OrtInferenceFailure.badInput("invalid group-0 logits, token IDs, or repetition penalty")
        }
        var probs = Array(logits.suffix(vocab))
        if suppressEos { probs[config.codecEosId] = -Float.infinity }
        for token in Set(generated) {
            if probs[token] > 0 { probs[token] /= repetitionPenalty }
            else { probs[token] *= repetitionPenalty }
        }
        for i in config.cpVocab..<vocab where i != config.codecEosId {
            probs[i] = -Float.infinity
        }
        return try sampleFromLogits(&probs, temperature: temperature, topK: topK, random: &random)
    }

    static func sampleCodePredictor(logits: [Float], config: Qwen3TtsConfig,
                                    temperature: Float, topK: Int,
                                    random: inout SplitMix64) throws -> Int {
        guard config.cpVocab > 0, logits.count >= config.cpVocab,
              logits.allSatisfy({ !$0.isNaN && $0 != .infinity }) else {
            throw OrtInferenceFailure.badInput("invalid code-predictor logits")
        }
        var probs = Array(logits.suffix(config.cpVocab))
        return try sampleFromLogits(&probs, temperature: temperature, topK: topK, random: &random)
    }

    private static func sampleFromLogits(_ probs: inout [Float], temperature: Float,
                                         topK: Int, random: inout SplitMix64) throws -> Int {
        guard temperature.isFinite, temperature >= 0, topK >= 0, !probs.isEmpty,
              probs.allSatisfy({ !$0.isNaN && $0 != .infinity }),
              probs.contains(where: \.isFinite) else {
            throw OrtInferenceFailure.badInput("invalid sampling parameters or no unmasked finite logits")
        }
        var best = 0
        for i in probs.indices where probs[i] > probs[best] { best = i }
        // Greedy mode never consumes randomness; a tie keeps the first token.
        if temperature == 0 || topK == 1 { return best }
        if topK > 0 && topK < probs.count {
            let threshold = probs.sorted(by: >)[topK - 1]
            for i in probs.indices where probs[i] < threshold { probs[i] = -Float.infinity }
        }
        // Subtract before division; Double also avoids overflow when two
        // finite Float logits span the full Float range at tiny temperature.
        let maxLogit = Double(probs[best])
        var sum = 0.0
        for i in probs.indices {
            probs[i] = Float(exp((Double(probs[i]) - maxLogit) / Double(temperature)))
            sum += Double(probs[i])
        }
        let r = random.nextDouble()
        var cum = 0.0, lastPositive = best
        for i in probs.indices {
            if probs[i] > 0 { lastPositive = i }
            cum += Double(probs[i]) / sum
            if r < cum { return i }
        }
        return lastPositive
    }

    // MARK: Mel frontend (PyTorch-style, verified vs torch+librosa, cos > 0.9999)

    static func buildMelFilterbank(sr: Int, nFft: Int, nMels: Int,
                                   fmin: Double, fmax: Double) -> [Float] {
        let nFreqs = nFft / 2 + 1

        func hzToMel(_ f: Double) -> Double {
            let fSp = 200.0 / 3.0
            let mels = f / fSp
            let minLogHz = 1000.0
            let minLogMel = minLogHz / fSp
            let logstep = log(6.4) / 27.0
            if f >= minLogHz {
                return minLogMel + log(max(f, 1e-30) / minLogHz) / logstep
            }
            return mels
        }

        func melToHz(_ m: Double) -> Double {
            let fSp = 200.0 / 3.0
            let minLogMel = 1000.0 / fSp
            let logstep = log(6.4) / 27.0
            if m >= minLogMel {
                return 1000.0 * exp(logstep * (m - minLogMel))
            }
            return fSp * m
        }

        let melMin = hzToMel(fmin)
        let melMax = hzToMel(fmax)
        // Filter band edges in Hz (librosa slaney scale: mel -> Hz).
        let melF = (0..<(nMels + 2)).map {
            melToHz(melMin + (melMax - melMin) * Double($0) / Double(nMels + 1))
        }
        let fftFreqs = (0..<nFreqs).map { Double($0) * Double(sr) / Double(nFft) }

        var weights = [Float](repeating: 0, count: nMels * nFreqs)
        for m in 0..<nMels {
            let fdiffLow = melF[m + 1] - melF[m]
            let fdiffHigh = melF[m + 2] - melF[m + 1]
            let enorm = 2.0 / (melF[m + 2] - melF[m])
            for k in 0..<nFreqs {
                let lower = -(melF[m] - fftFreqs[k]) / fdiffLow
                let upper = (melF[m + 2] - fftFreqs[k]) / fdiffHigh
                weights[m * nFreqs + k] = Float(max(0.0, min(lower, upper)) * enorm)
            }
        }
        return weights
    }

    struct LogMelResult {
        let data: [Float]
        let frames: Int
    }

    /// [1, T, 128] log-mel frontend (n_fft 1024, hop 256, slaney norm).
    static func logMelSpectrogram(_ audio: [Float], sampleRate: Int) -> LogMelResult {
        let nFft = 1024
        let hop = 256
        let nMels = 128
        precondition(audio.count >= 2, "reference audio too short for mel frontend")
        let pad = (nFft - hop) / 2
        var padded = [Float](repeating: 0, count: audio.count + 2 * pad)
        for i in padded.indices {
            padded[i] = audio[reflectIndex(i - pad, length: audio.count)]
        }
        let frames = 1 + (padded.count - nFft) / hop
        let basis = buildMelFilterbank(sr: sampleRate, nFft: nFft, nMels: nMels,
                                       fmin: 0.0, fmax: Double(sampleRate) / 2.0)
        var window = [Double](repeating: 0, count: nFft)
        for i in 0..<nFft {
            window[i] = 0.5 - 0.5 * cos(2.0 * Double.pi * Double(i) / Double(nFft))
        }
        var out = [Float](repeating: 0, count: frames * nMels)
        let nFreqs = nFft / 2 + 1
        for f in 0..<frames {
            let start = f * hop
            var re = [Double](repeating: 0, count: nFft)
            var im = [Double](repeating: 0, count: nFft)
            for i in 0..<nFft {
                re[i] = Double(padded[start + i] * Float(window[i]))
            }
            fftRadix2(&re, &im)
            for m in 0..<nMels {
                var energy = 0.0
                for k in 0..<nFreqs {
                    let mag = (re[k] * re[k] + im[k] * im[k] + 1e-9).squareRoot()
                    energy += Double(basis[m * nFreqs + k]) * mag
                }
                out[f * nMels + m] = Float(log(max(energy, 1e-5)))
            }
        }
        return LogMelResult(data: out, frames: frames)
    }

    /// Reflect-pad index mapping matching torch F.pad(mode="reflect").
    static func reflectIndex(_ idx: Int, length: Int) -> Int {
        var i = idx < 0 ? -idx : idx
        let period = 2 * (length - 1)
        if period == 0 { return 0 }
        i %= period
        return i >= length ? period - i : i
    }

    /// In-place iterative radix-2 FFT (port of the author's MelSpectrogram.Fft).
    static func fftRadix2(_ re: inout [Double], _ im: inout [Double]) {
        let n = re.count
        var bits = 0
        while (1 << bits) < n { bits += 1 }
        precondition(1 << bits == n, "FFT size must be a power of two")
        for i in 0..<n {
            var x = i
            var j = 0
            for _ in 0..<bits { j = (j << 1) | (x & 1); x >>= 1 }
            if j > i {
                re.swapAt(i, j)
                im.swapAt(i, j)
            }
        }
        var size = 2
        while size <= n {
            let half = size / 2
            let angle = -2.0 * Double.pi / Double(size)
            var i = 0
            while i < n {
                for k in 0..<half {
                    let c = cos(angle * Double(k))
                    let s = sin(angle * Double(k))
                    let tReal = c * re[i + k + half] - s * im[i + k + half]
                    let tImag = s * re[i + k + half] + c * im[i + k + half]
                    re[i + k + half] = re[i + k] - tReal
                    im[i + k + half] = im[i + k] - tImag
                    re[i + k] += tReal
                    im[i + k] += tImag
                }
                i += size
            }
            size *= 2
        }
    }

    /// Sherpa's windowed-sinc low-pass resampler, already linked for ASR.
    /// The C API calls this "LinearResampler"; it is not two-point interpolation.
    static func resampleBandlimited(_ input: [Float], fromRate: Int, toRate: Int) throws -> [Float] {
        try Task.checkCancellation()
        guard (8000...192000).contains(fromRate), (8000...192000).contains(toRate),
              !input.isEmpty, input.count <= fromRate * 30, input.allSatisfy(\.isFinite) else {
            throw OrtInferenceFailure.badInput("reference PCM must be finite, nonempty, at most 30 seconds, and 8–192 kHz")
        }
        if fromRate == toRate { return input }
        // The native implementation computes its rate LCM in int32_t.
        var a = fromRate, b = toRate
        while b != 0 { (a, b) = (b, a % b) }
        guard Int64(fromRate / a) * Int64(toRate) <= Int64(Int32.max) else {
            throw OrtInferenceFailure.badInput("reference sample-rate ratio exceeds native resampler limits")
        }
        let expectedCount = (Int64(input.count) * Int64(toRate) + Int64(fromRate) - 1) / Int64(fromRate)
        guard expectedCount > 0, expectedCount <= Int64(Int32.max) else {
            throw OrtInferenceFailure.badInput("resampled reference length is invalid")
        }
        // One shared setting: midpoint of soxr HQ's passband/stopband edges.
        // This approximates its response; it is not sample-identical to soxr.
        let cutoff = Float(0.9568718266 * 0.5 * Double(min(fromRate, toRate)))
        guard let resampler = SherpaOnnxCreateLinearResampler(Int32(fromRate), Int32(toRate), cutoff, 64) else {
            throw OrtInferenceFailure.badInput("unable to create reference resampler")
        }
        defer { SherpaOnnxDestroyLinearResampler(resampler) }
        try Task.checkCancellation()
        let output = input.withUnsafeBufferPointer {
            SherpaOnnxLinearResamplerResample(resampler, $0.baseAddress, Int32($0.count), 1)
        }
        guard let output else { throw OrtInferenceFailure.badInput("reference resampling failed") }
        defer { SherpaOnnxLinearResamplerResampleFree(output) }
        try Task.checkCancellation()
        guard Int64(output.pointee.n) == expectedCount, let samples = output.pointee.samples else {
            throw OrtInferenceFailure.badInput("reference resampler returned an invalid length or buffer")
        }
        let result = Array(UnsafeBufferPointer(start: samples, count: Int(output.pointee.n)))
        guard result.allSatisfy(\.isFinite) else {
            throw OrtInferenceFailure.badInput("reference resampler returned non-finite PCM")
        }
        return result
    }
}
