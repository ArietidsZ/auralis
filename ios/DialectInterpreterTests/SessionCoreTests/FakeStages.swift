import Foundation
#if canImport(DialectInterpreter)
@testable import DialectInterpreter
#endif

// In-memory fakes over the session core ports. These make the pipeline's
// lifecycle, queue and reduction behaviour testable on host Swift (no ONNX,
// no AVFoundation, no real model bundles) — the same boundary the production
// adapters conform to in PipelinePlatformPorts.swift.

enum TestError: Error {
    case asrDown
    case synthesisDown
}

@MainActor
final class FakeRecognition: RecognitionStage {
    var result = RecognitionOutput(text: "你好世界", language: "Chinese")
    var loadError: Error?
    var loadDelayMs: UInt64 = 0
    var transcribeDelayMs: UInt64 = 0
    /// Finishing the stream releases recognition; task cancellation also wakes it.
    var transcribeGate: AsyncStream<Void>?
    private(set) var isLoaded = false
    private(set) var loadCount = 0
    private(set) var releaseCount = 0
    private(set) var transcribeCount = 0
    private(set) var lastLanguageHint: String?

    func load() async throws {
        loadCount += 1
        if loadDelayMs > 0 { try await Task.sleep(for: .milliseconds(loadDelayMs)) }
        if let loadError { throw loadError }
        isLoaded = true
    }

    func transcribe(audioData: [Float], languageHint: String?) async throws -> RecognitionOutput {
        transcribeCount += 1
        lastLanguageHint = languageHint
        if let transcribeGate {
            for await _ in transcribeGate {}
            try Task.checkCancellation()
        }
        if transcribeDelayMs > 0 { try await Task.sleep(for: .milliseconds(transcribeDelayMs)) }
        guard isLoaded else { throw TestError.asrDown }
        return result
    }

    func release() async {
        isLoaded = false
        releaseCount += 1
    }
}

@MainActor
final class FakeTranslation: TranslationStage {
    var translated = "Hello world"
    var error: Error?
    private(set) var callCount = 0
    private(set) var lastSourceLanguage: String?
    private(set) var releaseCount = 0
    private(set) var inFlight = false
    private(set) var releasedDuringTranslation = false
    var translateDelayMs: UInt64 = 0
    private var cancelled = false

    func translate(text: String, sourceLanguage: String, targetLanguage: String) async throws -> String {
        callCount += 1
        lastSourceLanguage = sourceLanguage
        guard !cancelled else { throw CancellationError() }
        inFlight = true
        defer { inFlight = false }
        do {
            if translateDelayMs > 0 { try await Task.sleep(for: .milliseconds(translateDelayMs)) }
        } catch is CancellationError {
            cancelled = true  // Native MT cancellation persists until release.
            throw CancellationError()
        }
        if let error { throw error }
        return translated
    }

    func release() async {
        if inFlight { releasedDuringTranslation = true }
        releaseCount += 1
        cancelled = false
    }
}

@MainActor
final class FakeSynthesis: SynthesisStage {
    var loadError: Error?
    var loadDelayMs: UInt64 = 0
    var synthesizeDelayMs: UInt64 = 0
    private(set) var isLoaded = false
    private(set) var releaseCount = 0
    private(set) var synthesizeCount = 0
    /// Configurable synthesis result; tests override to inject empty /
    /// non-finite / zero-rate outputs (silence-as-success regressions).
    var output = SynthesisOutput(audioData: [0.1, -0.1, 0.2], sampleRate: 24000, durationMs: 125)
    var outputSampleRate: Int { output.sampleRate }

    func load() async throws {
        if loadDelayMs > 0 { try await Task.sleep(for: .milliseconds(loadDelayMs)) }
        if let loadError { throw loadError }
        isLoaded = true
    }

    func synthesize(text: String, language: String, speakerEmbedding: [Float]?) async throws -> SynthesisOutput {
        synthesizeCount += 1
        if synthesizeDelayMs > 0 { try await Task.sleep(for: .milliseconds(synthesizeDelayMs)) }
        guard isLoaded, speakerEmbedding != nil else { throw TestError.synthesisDown }
        return output
    }

    func release() async {
        isLoaded = false
        releaseCount += 1
    }
}

@MainActor
final class FakeCapture: CaptureStagePort {
    var startError: Error?
    private(set) var startCount = 0
    private(set) var stopCount = 0
    private var continuation: AsyncStream<[Float]>.Continuation?
    private(set) var amplitudeValue: Float = 0

    var chunks: AsyncStream<[Float]> {
        AsyncStream { self.continuation = $0 }
    }

    var amplitude: Float { amplitudeValue }

    func start() throws {
        if let startError { throw startError }
        startCount += 1
    }

    func stop() {
        stopCount += 1
        continuation?.finish()
        continuation = nil
    }

    func feed(_ samples: [Float]) {
        continuation?.yield(samples)
    }

    func endStream() {
        continuation?.finish()
    }
}

@MainActor
final class FakePlayback: PlaybackStagePort {
    var isPlaying = false
    var playError: Error?
    private(set) var playCount = 0
    private(set) var releaseCount = 0
    var tailDelayMs: UInt64 = 20
    var chunkDelayMs: UInt64 = 0
    private(set) var deliveredChunks = 0

    func play(audioData: [Float], sampleRate: Int) async throws {
        playCount += 1
        if let playError { throw playError }
        guard !audioData.isEmpty else { throw TestError.synthesisDown }
        // Simulate audible output for the half-duplex window.
        isPlaying = true
        try? await Task.sleep(for: .milliseconds(20))
        isPlaying = false
    }

    func playStream(sampleRate: Int, producer: @escaping SpeechProducer) async throws {
        defer { isPlaying = false }
        try await producer { chunk in try await self.accept(chunk) }
        try await Task.sleep(for: .milliseconds(tailDelayMs))
    }

    private func accept(_ chunk: [Float]) async throws {
        if deliveredChunks == 0 || !isPlaying { playCount += 1 }
        if let playError { throw playError }
        isPlaying = true
        deliveredChunks += 1
        if chunkDelayMs > 0 { try await Task.sleep(for: .milliseconds(chunkDelayMs)) }
    }

    func release() { releaseCount += 1 }
}

@MainActor
final class FakeStreamingSynthesis: SynthesisStage {
    var delayAfterFirstMs: UInt64 = 0
    var failAfterFirst = false
    private(set) var firstChunkSent = false
    private(set) var finished = false
    private(set) var producing = false
    private(set) var releasedWhileProducing = false
    func load() async throws { }
    func release() async { releasedWhileProducing = producing }
    func synthesize(text: String, language: String, speakerEmbedding: [Float]?) async throws -> SynthesisOutput {
        throw TestError.synthesisDown // A streaming test must consume the stream port.
    }
    func synthesizeStream(text: String, language: String, speakerEmbedding: [Float]?,
                          onAudioChunk: @escaping SpeechChunkSink) async throws -> SynthesisOutput {
        producing = true
        finished = false
        firstChunkSent = false
        defer { producing = false }
        try await onAudioChunk([0.1, 0.2])
        firstChunkSent = true
        if delayAfterFirstMs > 0 { try await Task.sleep(for: .milliseconds(delayAfterFirstMs)) }
        if failAfterFirst { throw TestError.synthesisDown }
        try await onAudioChunk([-0.1, -0.2])
        finished = true
        return SynthesisOutput(audioData: [0.1, 0.2, -0.1, -0.2], sampleRate: 24000, durationMs: 125)
    }
}
