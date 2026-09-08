import Foundation
import Observation

// MARK: - Session core ports
//
// The session core (this file) depends only on Foundation + Observation and
// these narrow ports. Platform adapters (ONNX engines, AVAudioEngine capture/
// playback) conform in PipelinePlatformPorts.swift; the same ports admit the
// in-memory fakes used by the host-run session tests.

/// Speech recognition stage. Loading half-way is a thrown error, never a
/// degraded success.
@MainActor
protocol RecognitionStage: AnyObject {
    func load() async throws
    /// Throws on failure; never returns the source audio "as text".
    func transcribe(audioData: [Float], languageHint: String?) async throws -> RecognitionOutput
    func release() async
}

struct RecognitionOutput: Equatable {
    let text: String
    let language: String
}

/// Machine translation stage. A missing/failed MT throws; it never returns
/// the input text as a fake translation (spec 02/04).
protocol TranslationStage: AnyObject {
    func translate(text: String, sourceLanguage: String, targetLanguage: String) async throws -> String
    func release() async
}

/// Speech synthesis stage. Without a real speaker embedding or bundle voice
/// it throws — there is no zero-vector fallback (spec 01 C04).
@MainActor
protocol SynthesisStage: AnyObject {
    func load() async throws
    func synthesize(text: String, language: String, speakerEmbedding: [Float]?) async throws -> SynthesisOutput
    /// The format is known before playback opens. API1 implementations can
    /// use the default whole-buffer producer below.
    var outputSampleRate: Int { get }
    func synthesizeStream(text: String, language: String, speakerEmbedding: [Float]?,
                          onAudioChunk: @escaping SpeechChunkSink) async throws -> SynthesisOutput
    func release() async
}

typealias SpeechChunkSink = @Sendable ([Float]) async throws -> Void
typealias SpeechProducer = @MainActor (@escaping SpeechChunkSink) async throws -> Void

enum SpeechDeliveryFailure: Error {
    case synthesis(Error)
    case playback(Error)
    case invalidSynthesis
}

extension SynthesisStage {
    var outputSampleRate: Int { 24000 }
    func synthesizeStream(text: String, language: String, speakerEmbedding: [Float]?,
                          onAudioChunk: @escaping SpeechChunkSink) async throws -> SynthesisOutput {
        let result = try await synthesize(text: text, language: language, speakerEmbedding: speakerEmbedding)
        guard !result.audioData.isEmpty, result.audioData.allSatisfy(\.isFinite),
              result.audioData.contains(where: { $0 != 0 }), result.sampleRate == outputSampleRate else {
            throw SpeechDeliveryFailure.invalidSynthesis
        }
        try await onAudioChunk(result.audioData)
        return result
    }
}

@MainActor
private final class SpeechDeliveryProgress {
    var firstChunkAt: TimeInterval?
    var result: SynthesisOutput?
    var synthesisMs: Int64 = 0
    var samples = 0
    var hasSignal = false
}

struct SynthesisOutput {
    let audioData: [Float]
    let sampleRate: Int
    let durationMs: Int64
}

/// Microphone capture port. `stop()` must be safe to call from any state and
/// must terminate the `chunks` stream.
@MainActor
protocol CaptureStagePort: AnyObject {
    func start() throws
    func stop()
    var chunks: AsyncStream<[Float]> { get }
    var amplitude: Float { get }
}

/// Speaker playback port. `isPlaying` gates the half-duplex capture path.
@MainActor
protocol PlaybackStagePort: AnyObject {
    var isPlaying: Bool { get }
    /// Plays the whole buffer. Throws on initialization/PCM/hardware failure
    /// and on cancellation; empty/non-finite/invalid-rate audio is an
    /// explicit failure, never silence-as-success.
    func play(audioData: [Float], sampleRate: Int) async throws
    func playStream(sampleRate: Int, producer: @escaping SpeechProducer) async throws
    func release()
}

// MARK: - Turn identity

/// One ended utterance captured for processing. The turn id is assigned at
/// capture time (when the utterance is enqueued), not when compute picks it
/// up, so queueing can never reorder or merge identities.
struct TurnJob {
    let turnId: Int64
    let sessionId: Int64
    let audio: [Float]
}

// MARK: - Bounded ended-utterance queue

/// Bounded ended-utterance queue (capacity N, drop-newest + visible counter).
///
/// Concurrency contract:
///   - `close()` sets the closed flag and wakes every waiter (including
///     waiters registered after a cancel that raced the registration).
///   - `next()` cancellation is handled by closing: a cancel that fires
///     before the continuation body runs is still observed, because the body
///     re-checks the closed flag under the lock before parking.
///   - A closed queue stays closed; a new session gets a fresh queue.
final class UtteranceQueue {
    private let capacity: Int
    private var buffer: [TurnJob] = []
    private var waiters: [CheckedContinuation<TurnJob?, Never>] = []
    private let lock = NSLock()
    private var closed = false
    private(set) var droppedCount = 0

    init(capacity: Int) { self.capacity = capacity }

    var isClosed: Bool {
        lock.lock(); defer { lock.unlock() }
        return closed
    }

    @discardableResult
    func enqueue(_ item: TurnJob) -> Bool {
        lock.lock()
        if closed {
            lock.unlock()
            return false
        }
        if buffer.count >= capacity {
            droppedCount += 1
            lock.unlock()
            return false
        }
        buffer.append(item)
        if !waiters.isEmpty {
            let waiter = waiters.removeFirst()
            let next = buffer.removeFirst()
            lock.unlock()
            waiter.resume(returning: next)
            return true
        }
        lock.unlock()
        return true
    }

    /// Next utterance, or nil when the queue is closed (drained or cancelled).
    func next() async -> TurnJob? {
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<TurnJob?, Never>) in
                lock.lock()
                if let next = buffer.first {
                    buffer.removeFirst()
                    lock.unlock()
                    continuation.resume(returning: next)
                } else if closed {
                    // Covers: drained, closed, and a cancel that fired before
                    // this body ran (onCancel calls close()).
                    lock.unlock()
                    continuation.resume(returning: nil)
                } else {
                    waiters.append(continuation)
                    lock.unlock()
                }
            }
        } onCancel: {
            close()
        }
    }

    /// Close the queue and wake every waiter with nil.
    func close() {
        lock.lock()
        closed = true
        let waiters = self.waiters
        self.waiters.removeAll()
        lock.unlock()
        for waiter in waiters {
            waiter.resume(returning: nil)
        }
    }
}

// MARK: - Orchestrator

/// Orchestrates the real-time ASR → MT → TTS pipeline (spec 02-runtime R01–R03).
///
/// Contract highlights:
///   - One session per start(); sessions and turns carry monotonic IDs. Turn
///     ids are assigned at capture time (when the utterance enters the queue).
///   - Capture (audio in) and playback (audio out) run independently of the
///     single serial compute worker; the ended-utterance queue is bounded
///     (capacity 2) and overflow is DROPPED with a visible counter.
///   - Half-duplex: while playback is active the capture path discards audio
///     instead of feeding speaker output back into ASR.
///   - MT/TTS unavailability is per-turn (transcript-only / text-forward),
///     never fatal; ASR load failure is fatal and cleans up everything.
///   - Lifecycle: every start/stop/release operation is serialized through a
///     saved lifecycle task chain. stop() joins all in-flight tasks (boot,
///     capture, worker) and only then releases; a restart is impossible
///     until the previous session has fully torn down, so a late release can
///     never touch a newer session's engines. Release waits for completion.
///   - CancellationError is never swallowed by generic error handling.
@MainActor
@Observable
final class PipelineOrchestrator {
    private static let tag = "Pipeline"
    private static let queueCapacity = 2

    // MARK: - Session/turn identity (R01)

    private static let idLock = NSLock()
    private static var lastSessionId: Int64 = 0
    private static var lastTurnId: Int64 = 0

    private static func nextSessionId() -> Int64 {
        idLock.lock(); defer { idLock.unlock() }
        lastSessionId += 1
        return lastSessionId
    }

    static func nextTurnId() -> Int64 {
        idLock.lock(); defer { idLock.unlock() }
        lastTurnId += 1
        return lastTurnId
    }

    /// R03 turn lifecycle, surfaced verbatim to the UI.
    enum TurnStatus: Equatable {
        case recognizing
        case translating
        case synthesizing
        case playing
        case complete
        case failed(String)
        case mtUnavailable(String)   // transcript-only: MT missing/failed
        case ttsUnavailable(String)  // translation present, speech not
        case cancelled
        case dropped
    }

    struct TurnState: Identifiable, Equatable {
        let id: Int64            // globally monotonic turn id
        let sessionId: Int64
        var status: TurnStatus
        var sourceText: String?
        var translatedText: String?
    }

    // MARK: - Events (unified, id-bearing reduction; no legacy aliases)

    enum PipelineEvent {
        case turnUpdated(TurnState)
        case stateChange(PipelineState)
        case error(String)
    }

    enum PipelineState: String {
        case idle, starting, stopping, listening, recognizing, translating, synthesizing, playing, failed
    }

    struct PipelineTelemetry {
        var utteranceCount: Int64 = 0
        var droppedUtterances: Int64 = 0
        var asrCount: Int64 = 0
        var ttsCount: Int64 = 0
        var lastAsrMs: Int64 = 0
        var avgAsrMs: Int64 = 0
        var lastTtsMs: Int64 = 0
        var avgTtsMs: Int64 = 0
        var lastPlaybackMs: Int64 = 0
        var avgPlaybackMs: Int64 = 0
        var lastRtf: Float = 0
        var avgRtf: Float = 0
    }

    // MARK: - Public state

    private(set) var state: PipelineState = .idle
    private(set) var sessionId: Int64 = 0
    private(set) var amplitude: Float = 0
    private(set) var telemetry = PipelineTelemetry()
    /// False until a TTS bundle actually loads; text-forward mode when false.
    private(set) var ttsAvailable = false

    // Event streaming (one live subscriber; a new stream ends the old one)
    private var eventContinuation: AsyncStream<PipelineEvent>.Continuation?
    var events: AsyncStream<PipelineEvent> {
        AsyncStream { continuation in
            eventContinuation?.finish()
            eventContinuation = continuation
        }
    }

    // MARK: - Stages (ports)

    private let asr: RecognitionStage
    private let translation: TranslationStage
    private let tts: SynthesisStage
    private let audioCapture: CaptureStagePort
    private let audioPlayback: PlaybackStagePort

    // MARK: - Lifecycle state

    /// Serial lifecycle chain: every start/stop/release op awaits the previous
    /// op before running. Saved so `release()` can await completion.
    private var lifecycleTask: Task<Void, Never>?
    /// The run-start op task; stop joins it before returning.
    private var sessionTask: Task<Void, Never>?
    /// True between a synchronous start() call and its op finishing, so a
    /// stop() arriving in the same main-actor turn cannot be lost.
    private var pendingStartId: UUID?
    private var awaitingStart: Bool { pendingStartId != nil }
    private var isReleased = false

    private var captureTask: Task<Void, Never>?
    private var workerTask: Task<Void, Never>?
    private var activeQueue: UtteranceQueue?
    private var activeQueueSession: Int64 = 0
    private var speakerEmbedding: [Float]?
    private var targetLanguage: String = "Chinese"
    private var sourceLanguage: String = "auto"

    init(asr: RecognitionStage, translation: TranslationStage, tts: SynthesisStage,
         audioCapture: CaptureStagePort, audioPlayback: PlaybackStagePort) {
        self.asr = asr
        self.translation = translation
        self.tts = tts
        self.audioCapture = audioCapture
        self.audioPlayback = audioPlayback
    }

    /// Set the voice profile speaker embedding.
    func setSpeakerEmbedding(_ embedding: [Float]) {
        speakerEmbedding = embedding
    }

    // MARK: - Lifecycle

    /// Start the full pipeline. ASR load failure is fatal (phase .failed +
    /// cleanup); MT/TTS problems degrade to transcript-only, never to fake
    /// speech or fake translations.
    ///
    /// Synchronous entry point; the op is appended to the lifecycle chain, so
    /// it never begins before a pending stop has fully torn down.
    func start(targetLanguage: String = "Chinese", sourceLanguage: String = "auto") {
        guard !isReleased, !awaitingStart else { return }
        guard state == .idle || state == .failed || state == .stopping else { return }
        let requestId = UUID()
        pendingStartId = requestId
        let task = enqueueLifecycle { await self.runStart(targetLanguage: targetLanguage,
            sourceLanguage: sourceLanguage, requestId: requestId) }
        sessionTask = task
    }

    /// Stop the active session (or one whose start is still queued). The mic
    /// is silenced, the boot/stages cancelled and the queue closed
    /// SYNCHRONOUSLY (a start op suspended mid-boot is interrupted by the
    /// cancellation at its current await); only the join + final state
    /// transition runs on the lifecycle chain, so a restart cannot begin
    /// before every in-flight task of the stopped session finished.
    func stop() {
        guard (state != .idle && state != .failed) || awaitingStart else { return }
        performStopActions()
        let task = sessionTask
        _ = enqueueLifecycle { await self.finalizeStop(task: task) }
    }

    /// Synchronous stop surface (runs on the main actor even while a start op
    /// is suspended mid-boot).
    private func performStopActions() {
        audioCapture.stop()
        audioPlayback.release()
        amplitude = 0
        pendingStartId = nil
        // These are unstructured Tasks. Cancelling sessionTask does not
        // propagate to them; each must be cancelled before the join.
        captureTask?.cancel()
        workerTask?.cancel()
        activeQueue?.close()               // wake the worker now
        sessionTask?.cancel()
        updateState(.stopping)
    }

    /// Join the stopped session's task (boot/capture/worker + its own
    /// teardown, including engine release) before declaring idle.
    private func finalizeStop(task: Task<Void, Never>?) async {
        await task?.value
        guard state == .stopping else { return }  // teardown set the final state
        updateState(.idle)
    }

    /// Release all resources (app teardown). Waits for the full stop to
    /// complete — it never merely requests it. Subsequent start() is refused.
    func release() async {
        guard !isReleased else { return }
        isReleased = true
        if state != .idle && state != .failed {
            performStopActions()
            let task = sessionTask
            _ = enqueueLifecycle { await self.finalizeStop(task: task) }
        }
        await lifecycleTask?.value
        activeQueue?.close()
        activeQueue = nil
        eventContinuation?.finish()
        eventContinuation = nil
    }

    private func enqueueLifecycle(_ op: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        let previous = lifecycleTask
        let task = Task { [previous] in
            await previous?.value
            await op()
        }
        lifecycleTask = task
        return task
    }

    private func runStart(targetLanguage: String, sourceLanguage: String, requestId: UUID) async {
        defer {
            // An older session finishing must not clear a queued restart.
            if pendingStartId == requestId { pendingStartId = nil }
        }
        guard !Task.isCancelled, pendingStartId == requestId else { return }
        guard state == .idle || state == .failed else { return }
        guard !isReleased else { return }

        sessionId = Self.nextSessionId()
        let sid = sessionId
        self.targetLanguage = targetLanguage
        self.sourceLanguage = sourceLanguage
        telemetry = PipelineTelemetry()
        ttsAvailable = false
        updateState(.starting)

        // ASR load — fatal on failure (without recognition there is no pipeline).
        do {
            try await asr.load()
        } catch is CancellationError {
            await teardown(sid: sid, terminal: .idle)
            return
        } catch {
            emitEvent(.error("ASR unavailable: \(error)"))
            await teardown(sid: sid, terminal: .failed)
            return
        }

        guard !Task.isCancelled else {
            await teardown(sid: sid, terminal: .idle)
            return
        }

        // TTS load failure is NOT fatal: text-forward mode (R03). Cancellation
        // during the load is still cancellation — it is never swallowed here.
        do {
            try await tts.load()
            ttsAvailable = true
        } catch is CancellationError {
            await teardown(sid: sid, terminal: .idle)
            return
        } catch {
            ttsAvailable = false
            emitEvent(.error("TTS unavailable — text-forward mode: \(error)"))
        }

        // Fresh queue per session: a queue closed by a previous session/abort
        // can never leak into a new start.
        let queue = UtteranceQueue(capacity: Self.queueCapacity)
        activeQueue = queue
        activeQueueSession = sid

        let worker = Task { await self.computeWorker(queue: queue, sessionId: sid) }
        let capture = Task { await self.captureStage(queue: queue, sessionId: sid) }
        workerTask = worker
        captureTask = capture

        updateState(.listening)

        // Capture exits on: cancellation, input stream end (interruption,
        // route loss, engine stop). Whatever the reason, the worker must not
        // linger on a dead queue.
        await capture.value
        captureTask = nil
        queue.close()
        await worker.value
        workerTask = nil
        if activeQueueSession == sid {
            activeQueue = nil
            activeQueueSession = 0
        }

        guard sessionId == sid else { return }  // superseded; owner changed

        if Task.isCancelled {
            await teardown(sid: sid, terminal: .idle)
        } else {
            // The input stream ended on its own (interruption / route loss):
            // report honestly instead of pretending the mic is still live.
            emitEvent(.error("Recording input ended unexpectedly; session \(sid) stopped"))
            await teardown(sid: sid, terminal: .failed)
        }
    }

    /// Single ownership: teardown happens here, exactly once per session,
    /// after all in-flight stage tasks have been joined by the caller.
    private func teardown(sid: Int64, terminal: PipelineState) async {
        audioCapture.stop()
        audioPlayback.release()
        amplitude = 0
        if activeQueueSession == sid {
            activeQueue?.close()
            activeQueue = nil
            activeQueueSession = 0
        }
        await asr.release()
        await translation.release()
        await tts.release()
        guard sessionId == sid else { return }  // superseded; leave state alone
        updateState(terminal)
    }

    // MARK: - Capture stage (stage 1)

    private func captureStage(queue: UtteranceQueue, sessionId sid: Int64) async {
        // Never open the microphone for a session that is already being torn
        // down (stop during boot, etc.).
        guard !Task.isCancelled else { return }

        let segmenter = UtteranceSegmenter()

        do {
            try audioCapture.start()
        } catch is CancellationError {
            return
        } catch {
            emitEvent(.error("Failed to start recording: \(error)"))
            return
        }

        defer { audioCapture.stop() }

        for await chunk in audioCapture.chunks {
            if Task.isCancelled { return }

            amplitude = audioCapture.amplitude

            // Half-duplex gate: while the speaker is playing, discard input so
            // synthesized audio is never fed back into recognition. Also
            // reset VAD state so the post-playback tail is not glued to the
            // pre-playback utterance.
            if audioPlayback.isPlaying {
                segmenter.reset()
                continue
            }

            do {
                try segmenter.process(chunk) { utterance in
                    // Identity is assigned at capture time, including forced
                    // long-speech splits; the ended queue keeps its own bound.
                    let job = TurnJob(turnId: Self.nextTurnId(), sessionId: sid, audio: utterance)
                    if queue.enqueue(job) {
                        telemetry.utteranceCount += 1
                    } else {
                        telemetry.droppedUtterances = Int64(queue.droppedCount)
                        emitEvent(.turnUpdated(TurnState(
                            id: job.turnId, sessionId: sid, status: .dropped,
                            sourceText: nil, translatedText: nil)))
                    }
                }
            } catch {
                emitEvent(.error("Captured audio is invalid. Restart recording."))
                return
            }
        }
    }

    // MARK: - Compute worker (stage 2–4, strictly serial)

    private func computeWorker(queue: UtteranceQueue, sessionId sid: Int64) async {
        while !Task.isCancelled {
            guard let job = await queue.next() else { return }
            guard job.sessionId == sid else { continue }

            let turn = TurnState(id: job.turnId, sessionId: sid, status: .recognizing,
                                 sourceText: nil, translatedText: nil)
            emitEvent(.turnUpdated(turn))

            do {
                try await processTurn(utterance: job.audio, turn: turn)
            } catch is CancellationError {
                return  // worker exits on cancellation; stop() joins it
            } catch {
                // Per-stage failures are reported inside processTurn; this is
                // the truly unexpected path.
                emitEvent(.error("worker error: \(error)"))
            }
        }
    }

    private func processTurn(utterance: [Float], turn: TurnState) async throws {
        // ASR — failure marks the turn failed; the worker keeps serving.
        updateState(.recognizing)
        let t0 = ProcessInfo.processInfo.systemUptime
        let asrResult: RecognitionOutput
        do {
            asrResult = try await asr.transcribe(audioData: utterance, languageHint: nil)
        } catch is CancellationError {
            emitTurnUpdate(turn, status: .cancelled, sourceText: nil, translatedText: nil)
            throw CancellationError()
        } catch {
            emitTurnUpdate(turn, status: .failed("recognition failed: \(error)"),
                           sourceText: nil, translatedText: nil)
            emitEvent(.error("recognition failed: \(error)"))
            updateState(.listening)
            return
        }
        let latencyMs = Int64((ProcessInfo.processInfo.systemUptime - t0) * 1000)
        recordAsrMetrics(latencyMs: latencyMs)

        let transcript = asrResult.text.trimmingCharacters(in: .whitespaces)
        guard !transcript.isEmpty else {
            // Empty recognition: a real, terminal turn with nothing to forward.
            emitTurnUpdate(turn, status: .complete, sourceText: "", translatedText: nil)
            updateState(.listening)
            return
        }
        emitTurnUpdate(turn, status: .translating, sourceText: transcript, translatedText: nil)

        // MT stage. A missing/failed MT never becomes a translation: the turn
        // stays transcript-only with an explicit mtUnavailable status, and no
        // TTS is attempted for it.
        updateState(.translating)
        let translated: String
        do {
            translated = try await translation.translate(
                text: transcript,
                sourceLanguage: sourceLanguage == "auto" ? asrResult.language : sourceLanguage,
                targetLanguage: targetLanguage)
        } catch is CancellationError {
            emitTurnUpdate(turn, status: .cancelled, sourceText: transcript, translatedText: nil)
            throw CancellationError()
        } catch {
            let reason = "translation unavailable: \(error)"
            emitTurnUpdate(turn, status: .mtUnavailable(reason), sourceText: transcript, translatedText: nil)
            updateState(.listening)
            return
        }
        emitTurnUpdate(turn, status: .synthesizing, sourceText: transcript, translatedText: translated)

        // TTS only when loaded and for a real translation. TTS failure never
        // fabricates silence-as-success: the turn reports ttsUnavailable.
        guard ttsAvailable else {
            let reason = "tts not loaded (text-forward mode)"
            emitTurnUpdate(turn, status: .ttsUnavailable(reason), sourceText: transcript, translatedText: translated)
            updateState(.listening)
            return
        }

        updateState(.synthesizing)
        let ttsT0 = ProcessInfo.processInfo.systemUptime
        let progress = SpeechDeliveryProgress()
        do {
            guard (8000...192000).contains(tts.outputSampleRate) else {
                throw SpeechDeliveryFailure.invalidSynthesis
            }
            try await audioPlayback.playStream(sampleRate: tts.outputSampleRate) { sink in
                do {
                    let result = try await self.tts.synthesizeStream(
                        text: translated, language: self.targetLanguage,
                        speakerEmbedding: self.speakerEmbedding) { chunk in
                            try await self.deliverSpeechChunk(chunk, sink: sink, progress: progress,
                                turn: turn, transcript: transcript, translated: translated)
                        }
                    guard progress.samples > 0, progress.hasSignal,
                          result.sampleRate == self.tts.outputSampleRate,
                          result.audioData.count == progress.samples else {
                        throw SpeechDeliveryFailure.invalidSynthesis
                    }
                    try Task.checkCancellation()
                    progress.result = result
                    progress.synthesisMs = Int64((ProcessInfo.processInfo.systemUptime - ttsT0) * 1000)
                } catch is CancellationError {
                    throw CancellationError()
                } catch let failure as SpeechDeliveryFailure {
                    throw failure
                } catch {
                    throw SpeechDeliveryFailure.synthesis(error)
                }
            }
        } catch is CancellationError {
            emitTurnUpdate(turn, status: .cancelled, sourceText: transcript, translatedText: translated)
            throw CancellationError()
        } catch {
            let status: TurnStatus
            switch error {
            case SpeechDeliveryFailure.synthesis, SpeechDeliveryFailure.invalidSynthesis:
                status = .ttsUnavailable("translation present but speech synthesis unavailable: \(error)")
            default:
                status = .failed("playback failed: \(error)")
            }
            emitTurnUpdate(turn, status: status, sourceText: transcript, translatedText: translated)
            updateState(.listening)
            return
        }
        guard let result = progress.result else {
            emitTurnUpdate(turn, status: .failed("playback returned before synthesis finished"),
                           sourceText: transcript, translatedText: translated)
            updateState(.listening)
            return
        }
        let playbackMs = progress.firstChunkAt.map {
            Int64((ProcessInfo.processInfo.systemUptime - $0) * 1000)
        } ?? 0
        // These wall-time intervals overlap during streaming. Synthesis time
        // includes sink backpressure; it is not CPU-only inference time.
        let rtf = result.durationMs > 0 ? Float(progress.synthesisMs) / Float(result.durationMs) : 0
        recordTtsMetrics(inferenceMs: progress.synthesisMs, playbackMs: playbackMs, rtf: rtf)
        emitTurnUpdate(turn, status: .complete, sourceText: transcript, translatedText: translated)
        updateState(.listening)
    }

    private func deliverSpeechChunk(_ chunk: [Float], sink: SpeechChunkSink,
                                    progress: SpeechDeliveryProgress, turn: TurnState,
                                    transcript: String, translated: String) async throws {
        try Task.checkCancellation()
        guard !chunk.isEmpty, chunk.allSatisfy(\.isFinite) else {
            throw SpeechDeliveryFailure.invalidSynthesis
        }
        if progress.firstChunkAt == nil {
            progress.firstChunkAt = ProcessInfo.processInfo.systemUptime
            emitTurnUpdate(turn, status: .playing, sourceText: transcript, translatedText: translated)
            updateState(.playing)
        }
        do { try await sink(chunk) }
        catch is CancellationError { throw CancellationError() }
        catch { throw SpeechDeliveryFailure.playback(error) }
        progress.samples += chunk.count
        progress.hasSignal = progress.hasSignal || chunk.contains(where: { $0 != 0 })
    }

    // MARK: - Turn/event helpers

    private func emitTurnUpdate(_ turn: TurnState, status: TurnStatus, sourceText: String?, translatedText: String?) {
        var updated = turn
        updated.status = status
        if let sourceText { updated.sourceText = sourceText }
        if let translatedText { updated.translatedText = translatedText }
        emitEvent(.turnUpdated(updated))
    }

    // MARK: - Metrics

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
}
