import Foundation

// Platform adapters: bind the session core's ports (PipelineOrchestrator.swift)
// to the real engines (Inference/) and AVAudioEngine capture/playback (Audio/).
// Nothing here is needed by the host-run session tests, which use in-memory
// fakes over the same ports.

// MARK: - Inference engines

/// ASR engine → RecognitionStage.
final class AsrRecognitionAdapter: RecognitionStage {
    private let engine: AsrEngine
    private let isReady: @MainActor () -> Bool
    init(engine: AsrEngine, isReady: @escaping @MainActor () -> Bool) {
        self.engine = engine
        self.isReady = isReady
    }

    func load() async throws {
        guard isReady() else { throw OrtInferenceFailure.badInput("语音识别模型尚未通过验证") }
        try await engine.load()
    }

    func transcribe(audioData: [Float], languageHint: String?) async throws -> RecognitionOutput {
        guard isReady() else { throw OrtInferenceFailure.badInput("语音识别模型尚未通过验证") }
        let result: AsrEngine.TranscriptionResult = try await engine.transcribe(
            audioData: audioData, language: languageHint)
        return RecognitionOutput(text: result.text, language: result.language)
    }

    func release() async { await engine.release() }
}

/// TTS engine → SynthesisStage.
final class TtsSynthesisAdapter: SynthesisStage {
    private let engine: TtsEngine
    private let profiles: VoiceProfileRepository
    private let referenceRecognizer: RecognitionStage
    private var preparedReference: TtsEngine.PreparedReference?
    private let isReady: @MainActor () -> Bool
    init(engine: TtsEngine, referenceRecognizer: RecognitionStage,
         profiles: VoiceProfileRepository = VoiceProfileRepository(),
         isReady: @escaping @MainActor () -> Bool) {
        self.engine = engine
        self.profiles = profiles
        self.referenceRecognizer = referenceRecognizer
        self.isReady = isReady
    }

    func load() async throws {
        preparedReference = nil
        do {
            guard isReady() else { throw OrtInferenceFailure.badInput("语音合成模型尚未通过验证") }
            guard let id = profiles.selectedProfileID else {
                throw OrtInferenceFailure.badInput("请选择声音档案后启用语音合成")
            }
            let audio = try await profiles.loadReference(id: id)
            guard audio.count >= VoiceProfileRepository.minimumSamples else {
                throw OrtInferenceFailure.badInput("参考录音至少需要 3 秒")
            }
            try Task.checkCancellation()
            try await engine.load()
            let referenceText: String?
            if engine.supportsReferenceText {
                // ASR is already loaded; session capture starts only after
                // this preparation. Keep the text in this snapshot only.
                let result = try await referenceRecognizer.transcribe(audioData: audio, languageHint: nil)
                let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else {
                    throw OrtInferenceFailure.badInput("参考录音未识别出文字，请重新录制清晰语音")
                }
                referenceText = text
            } else { referenceText = nil }
            preparedReference = try await engine.prepareReference(
                referenceAudio: audio, inputSampleRate: VoiceProfileRepository.sampleRate,
                referenceText: referenceText)
            try Task.checkCancellation()
        } catch {
            preparedReference = nil
            await engine.release()
            throw error
        }
    }

    func synthesize(text: String, language: String, speakerEmbedding: [Float]?) async throws -> SynthesisOutput {
        try await synthesizePrepared(text: text, language: language, speakerEmbedding: speakerEmbedding,
                                     onAudioChunk: nil)
    }

    var outputSampleRate: Int { TtsEngine.outputSampleRate }

    func synthesizeStream(text: String, language: String, speakerEmbedding: [Float]?,
                          onAudioChunk: @escaping SpeechChunkSink) async throws -> SynthesisOutput {
        try await synthesizePrepared(text: text, language: language, speakerEmbedding: speakerEmbedding,
                                     onAudioChunk: onAudioChunk)
    }

    private func synthesizePrepared(text: String, language: String, speakerEmbedding: [Float]?,
                                    onAudioChunk: SpeechChunkSink?) async throws -> SynthesisOutput {
        guard isReady() else { throw OrtInferenceFailure.badInput("语音合成模型尚未通过验证") }
        let result: TtsEngine.SynthesisResult
        if let speakerEmbedding {
            result = try await engine.synthesize(text: text, language: language, speakerEmbedding: speakerEmbedding,
                                                onAudioChunk: onAudioChunk)
        } else if let preparedReference {
            result = try await engine.synthesizePrepared(text: text, language: language, preparedReference: preparedReference,
                                                        onAudioChunk: onAudioChunk)
        } else { throw OrtInferenceFailure.notLoaded("TTS reference") }
        return SynthesisOutput(
            audioData: result.audioData,
            sampleRate: result.sampleRate,
            durationMs: result.durationMs)
    }

    func release() async {
        preparedReference = nil
        await engine.release()
    }
}

/// Hy-MT engine → TranslationStage (the engine's own protocol is defined in
/// TranslationEngine.swift and includes `isAvailable`, which the session
/// core does not need; this adapter keeps the core file standalone).
final class HyMtTranslationAdapter: TranslationStage {
    private let engine: HyMtTranslationEngine
    private let isReady: @MainActor () -> Bool
    init(engine: HyMtTranslationEngine, isReady: @escaping @MainActor () -> Bool) {
        self.engine = engine
        self.isReady = isReady
    }

    func translate(text: String, sourceLanguage: String, targetLanguage: String) async throws -> String {
        guard await isReady() else { throw OrtInferenceFailure.badInput("翻译模型尚未通过验证") }
        return try await engine.translate(text: text, sourceLanguage: sourceLanguage, targetLanguage: targetLanguage)
    }

    func release() async { await engine.unload() }
}

// MARK: - Audio

/// AudioRecorder → CaptureStagePort.
final class AudioCaptureAdapter: CaptureStagePort {
    private let recorder: AudioRecorder
    init(recorder: AudioRecorder) { self.recorder = recorder }

    func start() throws { try recorder.startRecording() }
    func stop() { recorder.stopRecording() }
    var chunks: AsyncStream<[Float]> { recorder.audioChunks }
    var amplitude: Float { recorder.amplitude }
}

/// AudioPlayer → PlaybackStagePort.
final class AudioPlaybackAdapter: PlaybackStagePort {
    private let player: AudioPlayer
    init(player: AudioPlayer) { self.player = player }

    var isPlaying: Bool { player.isPlaying }

    func play(audioData: [Float], sampleRate: Int) async throws {
        try await player.play(audioData: audioData, sampleRate: Double(sampleRate))
    }

    func playStream(sampleRate: Int, producer: @escaping SpeechProducer) async throws {
        try await player.playStream(sampleRate: Double(sampleRate)) { sink in
            try await producer(sink)
        }
    }

    func release() { player.release() }
}
