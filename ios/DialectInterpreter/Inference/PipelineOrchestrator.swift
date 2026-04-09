import Foundation

/// Orchestrates the real-time ASR → TTS pipeline with Swift Concurrency.
///
/// Architecture (4-stage pipeline running concurrently):
///   Stage 1 (Audio):   AudioRecorder → PCM chunks → VAD → utterance buffer
///   Stage 2 (Compute): ASR encoder+decoder → text
///   Stage 3 (Compute): TTS synthesize → PCM audio
///   Stage 4 (Audio):   AudioPlayer playback
@Observable
final class PipelineOrchestrator {
    private static let tag = "Pipeline"

    // MARK: - Events

    enum PipelineEvent {
        case asrResult(text: String, language: String, latencyMs: Int64)
        case ttsStarted(text: String)
        case ttsComplete(durationMs: Int64, inferenceMs: Int64, playbackMs: Int64, rtf: Float)
        case stateChange(PipelineState)
        case error(String)
    }

    enum PipelineState: String {
        case idle, loading, listening, recognizing, synthesizing, playing
    }

    struct PipelineTelemetry {
        var utteranceCount: Int64 = 0
        var droppedUtterances: Int64 = 0
        var droppedSynthesisJobs: Int64 = 0
        var asrCount: Int64 = 0
        var ttsCount: Int64 = 0
        var lastUtteranceMs: Int64 = 0
        var avgUtteranceMs: Int64 = 0
        var lastAsrMs: Int64 = 0
        var avgAsrMs: Int64 = 0
        var lastTtsMs: Int64 = 0
        var avgTtsMs: Int64 = 0
        var lastPlaybackMs: Int64 = 0
        var avgPlaybackMs: Int64 = 0
        var lastRtf: Float = 0
        var avgRtf: Float = 0
    }

    private struct SynthesisJob {
        let text: String
        let language: String
    }

    // MARK: - Public state

    private(set) var state: PipelineState = .idle
    private(set) var amplitude: Float = 0
    private(set) var telemetry = PipelineTelemetry()

    // Event streaming
    private var eventContinuation: AsyncStream<PipelineEvent>.Continuation?
    var events: AsyncStream<PipelineEvent> {
        AsyncStream { continuation in
            self.eventContinuation = continuation
        }
    }

    // MARK: - Private

    private let asrEngine: AsrEngine
    private let ttsEngine: TtsEngine
    private let audioRecorder: AudioRecorder
    private let audioPlayer: AudioPlayer

    private var pipelineTask: Task<Void, Never>?
    private var speakerEmbedding: [Float]?

    // Bounded channels (Swift doesn't have Channel, so we use AsyncStream with manual buffering)
    private var utteranceBuffer: [[Float]] = []
    private var synthesisBuffer: [SynthesisJob] = []
    private let bufferLock = NSLock()

    init(asrEngine: AsrEngine, ttsEngine: TtsEngine, audioRecorder: AudioRecorder, audioPlayer: AudioPlayer) {
        self.asrEngine = asrEngine
        self.ttsEngine = ttsEngine
        self.audioRecorder = audioRecorder
        self.audioPlayer = audioPlayer
    }

    /// Set the voice profile speaker embedding.
    func setSpeakerEmbedding(_ embedding: [Float]) {
        speakerEmbedding = embedding
    }

    /// Start the full pipeline.
    func start(targetLanguage: String = "Chinese") {
        guard pipelineTask == nil else { return }

        pipelineTask = Task { @MainActor in
            do {
                updateState(.loading)

                // Load models concurrently
                try await withThrowingTaskGroup(of: Void.self) { group in
                    group.addTask { try self.asrEngine.load() }
                    group.addTask { try self.ttsEngine.load() }
                    try await group.waitForAll()
                }

                // Start pipeline stages
                await withTaskGroup(of: Void.self) { group in
                    group.addTask { await self.captureAndProcessStage(targetLanguage: targetLanguage) }
                }

            } catch is CancellationError {
                print("[Pipeline] Pipeline cancelled")
            } catch {
                print("[Pipeline] Pipeline error: \(error)")
                emitEvent(.error(error.localizedDescription))
            }

            updateState(.idle)
        }
    }

    /// Capture audio, run VAD, process ASR → TTS → Playback sequentially.
    private func captureAndProcessStage(targetLanguage: String) async {
        let vad = VoiceActivityDetector()
        var utteranceChunks: [[Float]] = []

        updateState(.listening)

        do {
            try audioRecorder.startRecording()
        } catch {
            emitEvent(.error("Failed to start recording: \(error)"))
            return
        }

        for await chunk in audioRecorder.audioChunks {
            guard !Task.isCancelled else { break }

            await MainActor.run {
                self.amplitude = self.audioRecorder.amplitude
            }

            let vadResult = vad.process(
                audioChunk: chunk,
                chunkDurationMs: Int64(AudioRecorder.chunkSizeMs)
            )

            if vadResult.isSpeech {
                utteranceChunks.append(chunk)
            }

            if vadResult.utteranceComplete && !utteranceChunks.isEmpty {
                // Concatenate chunks
                let utterance = utteranceChunks.flatMap { $0 }
                utteranceChunks.removeAll()

                let utteranceDurationMs = Int64(utterance.count) * 1000 / Int64(AudioRecorder.sampleRate)
                recordUtteranceMetrics(durationMs: utteranceDurationMs)

                print("[Pipeline] Utterance: \(utterance.count) samples (\(utteranceDurationMs)ms)")

                // Process ASR
                updateState(.recognizing)
                let t0 = CFAbsoluteTimeGetCurrent()

                do {
                    let asrResult = try asrEngine.transcribe(audioData: utterance)
                    let latencyMs = Int64((CFAbsoluteTimeGetCurrent() - t0) * 1000)
                    recordAsrMetrics(latencyMs: latencyMs)
                    emitEvent(.asrResult(text: asrResult.text, language: asrResult.language, latencyMs: latencyMs))

                    if !asrResult.text.trimmingCharacters(in: .whitespaces).isEmpty {
                        // Process TTS
                        updateState(.synthesizing)
                        emitEvent(.ttsStarted(text: asrResult.text))

                        let embedding = speakerEmbedding ?? [Float](repeating: 0, count: TtsEngine.speakerEmbeddingDim)

                        let ttsT0 = CFAbsoluteTimeGetCurrent()
                        let ttsResult = try ttsEngine.synthesize(
                            text: asrResult.text,
                            language: targetLanguage,
                            speakerEmbedding: embedding
                        )
                        let inferenceMs = Int64((CFAbsoluteTimeGetCurrent() - ttsT0) * 1000)

                        // Playback
                        updateState(.playing)
                        let playbackT0 = CFAbsoluteTimeGetCurrent()
                        await audioPlayer.play(audioData: ttsResult.audioData, sampleRate: Double(ttsResult.sampleRate))
                        let playbackMs = Int64((CFAbsoluteTimeGetCurrent() - playbackT0) * 1000)

                        let rtf = ttsResult.durationMs > 0 ? Float(inferenceMs) / Float(ttsResult.durationMs) : 0
                        recordTtsMetrics(inferenceMs: inferenceMs, playbackMs: playbackMs, rtf: rtf)
                        emitEvent(.ttsComplete(durationMs: ttsResult.durationMs, inferenceMs: inferenceMs, playbackMs: playbackMs, rtf: rtf))

                        print("[Pipeline] TTS: \(ttsResult.durationMs)ms audio in \(inferenceMs)ms, playback=\(playbackMs)ms (RTF=\(String(format: "%.2f", rtf)))")
                    }
                } catch {
                    print("[Pipeline] Processing error: \(error)")
                    emitEvent(.error(error.localizedDescription))
                }

                updateState(.listening)
            }
        }

        audioRecorder.stopRecording()
    }

    // MARK: - Metrics

    private func recordUtteranceMetrics(durationMs: Int64) {
        let count = telemetry.utteranceCount
        telemetry.utteranceCount = count + 1
        telemetry.lastUtteranceMs = durationMs
        telemetry.avgUtteranceMs = nextAvg(telemetry.avgUtteranceMs, count, durationMs)
    }

    private func recordAsrMetrics(latencyMs: Int64) {
        let count = telemetry.asrCount
        telemetry.asrCount = count + 1
        telemetry.lastAsrMs = latencyMs
        telemetry.avgAsrMs = nextAvg(telemetry.avgAsrMs, count, latencyMs)
    }

    private func recordTtsMetrics(inferenceMs: Int64, playbackMs: Int64, rtf: Float) {
        let count = telemetry.ttsCount
        telemetry.ttsCount = count + 1
        telemetry.lastTtsMs = inferenceMs
        telemetry.avgTtsMs = nextAvg(telemetry.avgTtsMs, count, inferenceMs)
        telemetry.lastPlaybackMs = playbackMs
        telemetry.avgPlaybackMs = nextAvg(telemetry.avgPlaybackMs, count, playbackMs)
        telemetry.lastRtf = rtf
        telemetry.avgRtf = nextAvgF(telemetry.avgRtf, count, rtf)
    }

    private func nextAvg(_ current: Int64, _ count: Int64, _ value: Int64) -> Int64 {
        (current * count + value) / (count + 1)
    }

    private func nextAvgF(_ current: Float, _ count: Int64, _ value: Float) -> Float {
        (current * Float(count) + value) / Float(count + 1)
    }

    private func updateState(_ newState: PipelineState) {
        state = newState
        emitEvent(.stateChange(newState))
    }

    private func emitEvent(_ event: PipelineEvent) {
        eventContinuation?.yield(event)
    }

    /// Stop the pipeline.
    func stop() {
        pipelineTask?.cancel()
        pipelineTask = nil
        audioRecorder.stopRecording()
        audioPlayer.release()
        state = .idle
        amplitude = 0
        telemetry = PipelineTelemetry()
        print("[Pipeline] Pipeline stopped")
    }

    /// Release all resources.
    func release() {
        stop()
        eventContinuation?.finish()
        eventContinuation = nil
        asrEngine.release()
        ttsEngine.release()
    }
}
