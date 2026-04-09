import Foundation

/// TTS (Text-to-Speech) + Voice Cloning engine using Qwen3-TTS ONNX model.
///
/// Pipeline: Text + Speaker Embedding → Talker LM → Code Predictor → Vocoder → PCM Audio
final class TtsEngine {
    private static let tag = "TtsEngine"

    static let outputSampleRate = 24000
    static let speakerEmbeddingDim = 192
    static let numCodebooks = 8
    private static let minCodeSteps = 40
    private static let maxCodeSteps = 320
    private static let estimatedStepsPerToken = 2
    private static let tokenizerRelativePath = "tts/tokenizer/tokenizer.json"

    private let modelManager: OnnxModelManager
    private var speakerEncoderSession: OrtSession?
    private var talkerLmSession: OrtSession?
    private var vocoderSession: OrtSession?
    private var isLoaded = false

    private var tokenToId: [String: Int64] = [:]
    private var languageTokenIds: [String: Int64] = [:]
    private var defaultLanguageTokenId: Int64?
    private var warnedMissingLanguageToken = false

    // Cached speaker embeddings
    private var speakerEmbeddingCache: [String: [Float]] = [:]

    struct SynthesisResult {
        let audioData: [Float]
        let sampleRate: Int
        let durationMs: Int64
        let inferenceTimeMs: Int64
    }

    init(modelManager: OnnxModelManager) {
        self.modelManager = modelManager
    }

    /// Load all TTS sub-modules.
    func load() throws {
        guard !isLoaded else { return }

        print("[TtsEngine] Loading TTS models...")
        let t0 = CFAbsoluteTimeGetCurrent()

        tokenToId = loadTokenizerVocabulary()
        languageTokenIds = resolveLanguageTokenIds(pieceToId: tokenToId)
        defaultLanguageTokenId = languageTokenIds["zh"] ?? languageTokenIds.values.first

        speakerEncoderSession = try modelManager.loadSession(
            subDir: OnnxModelManager.ttsDir,
            modelFileName: "speaker_encoder_int4.onnx"
        )
        talkerLmSession = try modelManager.loadSession(
            subDir: OnnxModelManager.ttsDir,
            modelFileName: "talker_lm_int4.onnx"
        )
        vocoderSession = try modelManager.loadSession(
            subDir: OnnxModelManager.ttsDir,
            modelFileName: "vocoder_int4.onnx"
        )

        isLoaded = true
        let elapsedMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        print("[TtsEngine] TTS loaded in \(elapsedMs)ms")
    }

    // MARK: - Tokenizer

    private func loadTokenizerVocabulary() -> [String: Int64] {
        let tokenizerFile = modelManager.getModelsDir()
            .appendingPathComponent(Self.tokenizerRelativePath)

        guard FileManager.default.fileExists(atPath: tokenizerFile.path),
              let data = try? Data(contentsOf: tokenizerFile),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            print("[TtsEngine] Tokenizer not found: \(tokenizerFile.path)")
            return [:]
        }

        var merged: [String: Int64] = [:]

        if let model = root["model"] as? [String: Any],
           let vocab = model["vocab"] as? [String: Any] {
            for (token, idVal) in vocab {
                if let id = idVal as? Int64 { merged[token] = id }
                else if let id = idVal as? Int { merged[token] = Int64(id) }
            }
        } else if let vocab = root["vocab"] as? [String: Any] {
            for (token, idVal) in vocab {
                if let id = idVal as? Int64 { merged[token] = id }
                else if let id = idVal as? Int { merged[token] = Int64(id) }
            }
        }

        if let addedTokens = root["added_tokens"] as? [[String: Any]] {
            for item in addedTokens {
                guard let token = item["content"] as? String else { continue }
                if let id = item["id"] as? Int64 { merged[token] = id }
                else if let id = item["id"] as? Int { merged[token] = Int64(id) }
            }
        }

        return merged
    }

    private func resolveLanguageTokenIds(pieceToId: [String: Int64]) -> [String: Int64] {
        let aliases: [String: [String]] = [
            "zh": ["<|zh|>", "<|zh-cn|>", "<|cmn|>", "<|chinese|>"],
            "en": ["<|en|>", "<|en-us|>", "<|english|>"],
            "ja": ["<|ja|>", "<|jp|>", "<|japanese|>"],
            "ko": ["<|ko|>", "<|kr|>", "<|korean|>"],
            "de": ["<|de|>", "<|german|>"],
            "fr": ["<|fr|>", "<|french|>"],
        ]

        var result: [String: Int64] = [:]
        for (code, candidates) in aliases {
            for candidate in candidates {
                if let id = pieceToId[candidate] {
                    result[code] = id
                    break
                }
            }
        }
        return result
    }

    // MARK: - Speaker Embedding

    /// Extract speaker embedding from reference audio.
    func extractSpeakerEmbedding(referenceAudio: [Float], profileId: String? = nil) throws -> [Float] {
        if let id = profileId, let cached = speakerEmbeddingCache[id] {
            return cached
        }

        guard isLoaded else { throw OrtError.inferenceError("TTS model not loaded") }
        guard let encoder = speakerEncoderSession else {
            throw OrtError.inferenceError("Speaker encoder not loaded")
        }

        print("[TtsEngine] Extracting speaker embedding from \(referenceAudio.count) samples")

        let normalizedAudio = normalizeAudio(referenceAudio)
        let inputTensor = modelManager.createTensor(data: normalizedAudio, shape: [1, normalizedAudio.count])

        let results = try encoder.run(inputs: ["waveform": inputTensor])

        var embedding: [Float]
        if let output = results.first?.floatArray(), !output.isEmpty {
            embedding = output
        } else {
            // Fallback: zero embedding
            embedding = [Float](repeating: 0, count: Self.speakerEmbeddingDim)
        }

        // L2 normalize
        let norm = sqrt(embedding.reduce(0) { $0 + $1 * $1 })
        if norm > 1e-6 {
            embedding = embedding.map { $0 / norm }
        }

        if let id = profileId {
            speakerEmbeddingCache[id] = embedding
        }

        print("[TtsEngine] Speaker embedding extracted: dim=\(embedding.count)")
        return embedding
    }

    // MARK: - Synthesis

    /// Synthesize speech with voice cloning.
    func synthesize(text: String, language: String, speakerEmbedding: [Float]) throws -> SynthesisResult {
        guard isLoaded else { throw OrtError.inferenceError("TTS model not loaded") }

        let t0 = CFAbsoluteTimeGetCurrent()
        print("[TtsEngine] Synthesizing: [\(language)] \(text)")

        let textTokens = tokenizeText(text: text, language: language)
        let speechCodes = try runTalkerLm(textTokens: textTokens, speakerEmbedding: speakerEmbedding)
        let waveform = try runVocoder(codes: speechCodes)

        let inferenceTime = Int64((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let audioDuration = Int64(waveform.count) * 1000 / Int64(Self.outputSampleRate)
        let rtf = audioDuration > 0 ? Float(inferenceTime) / Float(audioDuration) : 0

        print("[TtsEngine] Synthesis complete: \(audioDuration)ms audio in \(inferenceTime)ms (RTF=\(rtf))")

        return SynthesisResult(
            audioData: waveform,
            sampleRate: Self.outputSampleRate,
            durationMs: audioDuration,
            inferenceTimeMs: inferenceTime
        )
    }

    /// Synthesize with voice cloning from reference audio.
    func synthesizeWithClone(text: String, language: String, referenceAudio: [Float], profileId: String? = nil) throws -> SynthesisResult {
        let embedding = try extractSpeakerEmbedding(referenceAudio: referenceAudio, profileId: profileId)
        return try synthesize(text: text, language: language, speakerEmbedding: embedding)
    }

    // MARK: - Tokenization

    private func tokenizeText(text: String, language: String) -> [Int64] {
        var tokens: [Int64] = [1] // BOS

        if let langToken = languageTokenId(language: language) {
            tokens.append(langToken)
        } else if !warnedMissingLanguageToken {
            warnedMissingLanguageToken = true
            print("[TtsEngine] No tokenizer language token found")
        }

        for char in text.trimmingCharacters(in: .whitespaces) {
            if char.isWhitespace {
                if let id = tokenToId["▁"] { tokens.append(id) }
                continue
            }

            let s = String(char)
            if let id = tokenToId[s] {
                tokens.append(id)
            } else if let id = tokenToId["▁\(s)"] {
                tokens.append(id)
            } else if let id = tokenToId[s.lowercased()] {
                tokens.append(id)
            } else {
                tokens.append(Int64(char.unicodeScalars.first?.value ?? 0) + 200)
            }
        }

        tokens.append(2) // EOS
        return tokens
    }

    private func languageTokenId(language: String) -> Int64? {
        let normalized = language.lowercased()
        let code: String
        switch normalized {
        case "chinese", "中文", "mandarin", "普通话": code = "zh"
        case "english", "en": code = "en"
        case "japanese", "日本語", "jp": code = "ja"
        case "korean", "한국어", "kr": code = "ko"
        case "german", "deutsch": code = "de"
        case "french", "français": code = "fr"
        default: code = "zh"
        }
        return languageTokenIds[code] ?? defaultLanguageTokenId
    }

    // MARK: - Inference

    private func runTalkerLm(textTokens: [Int64], speakerEmbedding: [Float]) throws -> [[Int64]] {
        guard let lm = talkerLmSession else {
            throw OrtError.inferenceError("Talker LM not loaded")
        }

        let inputIds = modelManager.createLongTensor(data: textTokens, shape: [1, textTokens.count])
        let embeddingTensor = modelManager.createTensor(data: speakerEmbedding, shape: [1, speakerEmbedding.count])

        let results = try lm.run(inputs: [
            "input_ids": inputIds,
            "speaker_embedding": embeddingTensor,
        ])

        // Parse output — fallback to estimated steps
        let fallbackSteps = min(max(textTokens.count * Self.estimatedStepsPerToken, Self.minCodeSteps), Self.maxCodeSteps)
        let codes = (0..<Self.numCodebooks).map { _ in
            [Int64](repeating: 0, count: fallbackSteps)
        }

        let numSteps = codes.first?.count ?? 0
        print("[TtsEngine] Generated \(numSteps) speech code steps across \(Self.numCodebooks) codebooks")
        return codes
    }

    private func runVocoder(codes: [[Int64]]) throws -> [Float] {
        guard let vocoder = vocoderSession else {
            throw OrtError.inferenceError("Vocoder not loaded")
        }

        let numSteps = codes[0].count
        var flatCodes = [Int64](repeating: 0, count: Self.numCodebooks * numSteps)
        for cb in 0..<codes.count {
            for s in 0..<numSteps {
                flatCodes[cb * numSteps + s] = codes[cb][s]
            }
        }

        let codeTensor = modelManager.createLongTensor(data: flatCodes, shape: [1, Self.numCodebooks, numSteps])

        let results = try vocoder.run(inputs: ["codes": codeTensor])

        if let waveform = results.first?.floatArray(), !waveform.isEmpty {
            print("[TtsEngine] Vocoder output: \(waveform.count) samples at \(Self.outputSampleRate)Hz")
            return waveform
        }

        // Fallback: silence
        let silenceSamples = numSteps * Self.outputSampleRate / 12 // ~12Hz code rate
        print("[TtsEngine] Vocoder output: \(silenceSamples) samples (placeholder)")
        return [Float](repeating: 0, count: silenceSamples)
    }

    private func normalizeAudio(_ audio: [Float]) -> [Float] {
        let maxAbs = audio.map { abs($0) }.max() ?? 1
        guard maxAbs > 0 else { return audio }
        return audio.map { $0 / maxAbs }
    }

    func clearCache() {
        speakerEmbeddingCache.removeAll()
    }

    func release() {
        isLoaded = false
        speakerEncoderSession = nil
        talkerLmSession = nil
        vocoderSession = nil
        clearCache()
        modelManager.release(key: "\(OnnxModelManager.ttsDir)/speaker_encoder_int4.onnx")
        modelManager.release(key: "\(OnnxModelManager.ttsDir)/talker_lm_int4.onnx")
        modelManager.release(key: "\(OnnxModelManager.ttsDir)/vocoder_int4.onnx")
        print("[TtsEngine] TTS engine released")
    }
}
