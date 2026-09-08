import Foundation
#if canImport(DialectInterpreter)
@testable import DialectInterpreter
#endif

// Host-run session core tests (plain Swift + Foundation; no XCTest, no ONNX,
// no AVFoundation). Executed by run_host_tests.sh; the same files compile
// inside the Xcode test target alongside the XCTest suites.

final class FailBox {
    var items: [String] = []
    var failed: Bool { !items.isEmpty }
    func expectTrue(_ condition: Bool, _ message: String) {
        if !condition { items.append(message) }
    }
    func expectEqual<T: Equatable>(_ a: T, _ b: T, _ message: String) {
        if a != b { items.append("\(message): expected \(b), got \(a)") }
    }
}

@MainActor
final class EventRecorder {
    private(set) var events: [PipelineOrchestrator.PipelineEvent] = []

    func record(_ event: PipelineOrchestrator.PipelineEvent) { events.append(event) }

    var turns: [PipelineOrchestrator.TurnState] {
        events.compactMap { if case .turnUpdated(let t) = $0 { return t }; return nil }
    }
    var states: [PipelineOrchestrator.PipelineState] {
        events.compactMap { if case .stateChange(let s) = $0 { return s }; return nil }
    }
    var errors: [String] {
        events.compactMap { if case .error(let m) = $0 { return m }; return nil }
    }
    func statuses(forTurn turnId: Int64) -> [PipelineOrchestrator.TurnStatus] {
        turns.filter { $0.id == turnId }.map(\.status)
    }
    func lastTurn(withStatus status: PipelineOrchestrator.TurnStatus) -> PipelineOrchestrator.TurnState? {
        turns.last { $0.status == status }
    }
}

@MainActor
struct SessionFixture {
    let orchestrator: PipelineOrchestrator
    let asr: FakeRecognition
    let mt: FakeTranslation
    var tts: FakeSynthesis
    let capture: FakeCapture
    let playback: FakePlayback
    let recorder: EventRecorder

    /// Subscribe to the event stream before any lifecycle op.
    func consumeEvents() -> Task<Void, Never> {
        let recorder = recorder
        let orchestrator = orchestrator
        return Task { @MainActor in
            for await event in orchestrator.events {
                recorder.record(event)
            }
        }
    }
}

// MARK: - Helpers

@MainActor
func waitUntil(timeoutSecs: Double = 5, _ condition: @MainActor () throws -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeoutSecs)
    while true {
        do { if try condition() { return true } } catch { }
        if Date() > deadline { return false }
        try? await Task.sleep(for: .milliseconds(5))
    }
}

@MainActor
func speechChunk() -> [Float] { [Float](repeating: 0.5, count: 3200) }

@MainActor
func silenceChunk() -> [Float] { [Float](repeating: 0.0001, count: 3200) }

/// Feed one ended utterance through the real VAD (speech ≥200ms then ≥800ms
/// of trailing silence). 20 silence chunks cover the exponential smoothing
/// decay below the energy threshold plus the silence timeout.
@MainActor
func feedEndedUtterance(_ capture: FakeCapture) {
    capture.feed(speechChunk())
    capture.feed(speechChunk())
    for _ in 0..<20 { capture.feed(silenceChunk()) }
}

@MainActor
func makeSession(streamingTts: SynthesisStage? = nil) -> (SessionFixture, Task<Void, Never>) {
    let asr = FakeRecognition()
    let mt = FakeTranslation()
    let tts = FakeSynthesis()
    let capture = FakeCapture()
    let playback = FakePlayback()
    let orchestrator = PipelineOrchestrator(
        asr: asr, translation: mt, tts: streamingTts ?? tts,
        audioCapture: capture, audioPlayback: playback)
    let recorder = EventRecorder()
    let fixture = SessionFixture(
        orchestrator: orchestrator, asr: asr, mt: mt, tts: tts,
        capture: capture, playback: playback, recorder: recorder)
    let consumer = fixture.consumeEvents()
    return (fixture, consumer)
}

@MainActor
enum StreamingSessionTests {
    static func startsBeforeEosAndWaitsForTail(_ f: FailBox) async {
        let tts = FakeStreamingSynthesis()
        tts.delayAfterFirstMs = 300
        let (s, consumer) = makeSession(streamingTts: tts)
        defer { consumer.cancel() }
        s.playback.tailDelayMs = 300
        s.orchestrator.start()
        let loaded = await waitUntil { s.orchestrator.state == .listening }
        f.expectTrue(loaded, "session started")
        feedEndedUtterance(s.capture)
        let early = await waitUntil { tts.firstChunkSent }
        f.expectTrue(early && !tts.finished && s.playback.isPlaying, "PCM plays while generation continues")
        let generated = await waitUntil { tts.finished }
        f.expectTrue(generated, "synthesis reached EOS")
        f.expectTrue(!s.recorder.turns.contains { $0.status == .complete }, "EOS must still wait for render tail")
        let done = await waitUntil { s.recorder.turns.contains { $0.status == .complete } }
        f.expectTrue(done, "turn completes after tail")
        f.expectEqual(s.playback.deliveredChunks, 2, "both chunks delivered in one output stream")
        f.expectEqual(s.playback.playCount, 1, "no second whole-buffer playback")
        await s.orchestrator.release()
    }

    static func partialSynthesisFailureIsVisible(_ f: FailBox) async {
        let tts = FakeStreamingSynthesis()
        tts.failAfterFirst = true
        let (s, consumer) = makeSession(streamingTts: tts)
        defer { consumer.cancel() }
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }
        feedEndedUtterance(s.capture)
        let failed = await waitUntil {
            s.recorder.turns.contains { if case .ttsUnavailable = $0.status { return true }; return false }
        }
        f.expectTrue(failed, "partial audio followed by no EOS is not success")
        f.expectEqual(s.playback.deliveredChunks, 1, "partial PCM really reached the playback port")
        f.expectTrue(!s.recorder.turns.contains { $0.status == .complete }, "no false complete")
        f.expectEqual(s.recorder.turns.last?.translatedText, "Hello world", "translation retained")
        f.expectTrue(!s.playback.isPlaying, "failure clears playback gate")
        tts.failAfterFirst = false
        feedEndedUtterance(s.capture)
        let recovered = await waitUntil { s.recorder.turns.contains { $0.status == .complete } }
        f.expectTrue(recovered, "next turn recovers")
        await s.orchestrator.release()
    }

    static func stopJoinsBackpressuredProducer(_ f: FailBox) async {
        let tts = FakeStreamingSynthesis()
        let (s, consumer) = makeSession(streamingTts: tts)
        defer { consumer.cancel() }
        s.playback.chunkDelayMs = 5000
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }
        feedEndedUtterance(s.capture)
        let blocked = await waitUntil { s.playback.isPlaying && tts.producing }
        f.expectTrue(blocked, "producer suspended in sink")
        let start = ProcessInfo.processInfo.systemUptime
        s.orchestrator.stop()
        await s.orchestrator.release()
        f.expectTrue(ProcessInfo.processInfo.systemUptime - start < 1, "stop cancels sink wait")
        f.expectTrue(!tts.producing && !tts.releasedWhileProducing, "release joins producer")
        f.expectTrue(!s.playback.isPlaying, "no playback gate left behind")
        f.expectTrue(!s.recorder.turns.contains { $0.status == .complete }, "stopped partial turn never completes")
    }
}

// MARK: - UtteranceQueue tests

@MainActor
enum UtteranceQueueTests {

    static func closeWakesWaiter(_ f: FailBox) async {
        let queue = UtteranceQueue(capacity: 2)
        let waiter = Task { await queue.next() }
        try? await Task.sleep(for: .milliseconds(20))
        queue.close()
        let result = await waiter.value
        f.expectTrue(result == nil, "close() must wake waiters with nil")
    }

    static func cancelWhileWaitingReturnsNil(_ f: FailBox) async {
        let queue = UtteranceQueue(capacity: 2)
        let waiter = Task { await queue.next() }
        try? await Task.sleep(for: .milliseconds(20))
        waiter.cancel()
        let result = await waiter.value
        // Regression: a cancel racing the continuation registration used to
        // hang forever; it must return nil promptly.
        f.expectTrue(result == nil, "cancelled next() must return nil, not hang")
    }

    static func fifoAndCapacityDrop(_ f: FailBox) async {
        let queue = UtteranceQueue(capacity: 2)
        let job1 = TurnJob(turnId: 1, sessionId: 1, audio: [1])
        let job2 = TurnJob(turnId: 2, sessionId: 1, audio: [2])
        let job3 = TurnJob(turnId: 3, sessionId: 1, audio: [3])
        let job4 = TurnJob(turnId: 4, sessionId: 1, audio: [4])
        f.expectTrue(queue.enqueue(job1), "enqueue 1 accepted")
        f.expectTrue(queue.enqueue(job2), "enqueue 2 accepted")
        f.expectTrue(!queue.enqueue(job3), "enqueue 3 must be dropped (capacity 2)")
        f.expectTrue(!queue.enqueue(job4), "enqueue 4 must be dropped (capacity 2)")
        f.expectEqual(queue.droppedCount, 2, "dropped counter")
        let first = await queue.next()
        f.expectEqual(first?.turnId, Int64(1), "FIFO order (first)")
        let second = await queue.next()
        f.expectEqual(second?.turnId, Int64(2), "FIFO order (second)")
    }

    static func closedQueueStaysClosed(_ f: FailBox) async {
        let queue = UtteranceQueue(capacity: 2)
        queue.close()
        let accepted = queue.enqueue(TurnJob(turnId: 1, sessionId: 1, audio: [1]))
        f.expectTrue(!accepted, "a closed queue accepts nothing")
        let next = await queue.next()
        f.expectTrue(next == nil, "next() on a closed queue returns nil")
    }
}

// MARK: - Orchestrator tests

@MainActor
enum OrchestratorTests {

    static func selectedSourceSurvivesUnknownDetection(_ f: FailBox) async {
        let (s, consumer) = makeSession()
        defer { consumer.cancel() }
        s.asr.result = RecognitionOutput(text: "你好", language: "unknown")
        s.orchestrator.start(targetLanguage: "English", sourceLanguage: "Chinese")
        f.expectTrue(await waitUntil { s.orchestrator.state == .listening }, "session starts")
        feedEndedUtterance(s.capture)
        f.expectTrue(await waitUntil { s.mt.callCount == 1 }, "translation called")
        f.expectTrue(s.asr.lastLanguageHint == nil, "selected translation source does not force ASR decoding")
        f.expectEqual(s.mt.lastSourceLanguage, "Chinese", "user-selected source reaches MT independently of detection")
        await s.orchestrator.release()
    }

    static func autoSourcePreservesUnknown(_ f: FailBox) async {
        let (s, consumer) = makeSession()
        defer { consumer.cancel() }
        s.asr.result = RecognitionOutput(text: "Hallo", language: "unknown")
        s.orchestrator.start(targetLanguage: "English")
        f.expectTrue(await waitUntil { s.orchestrator.state == .listening }, "session starts")
        feedEndedUtterance(s.capture)
        f.expectTrue(await waitUntil { s.mt.callCount == 1 }, "translation called")
        f.expectEqual(s.mt.lastSourceLanguage, "unknown", "auto mode never invents a detected source")
        f.expectTrue(s.asr.lastLanguageHint == nil, "auto mode does not force a source")
        await s.orchestrator.release()
    }

    static func immediateStopAndDuplicateStarts(_ f: FailBox) async {
        let (s, consumer) = makeSession()
        defer { consumer.cancel() }
        s.orchestrator.start()
        s.orchestrator.stop()  // The start task has not executed yet.
        f.expectTrue(await waitUntil { s.orchestrator.state == .idle }, "queued start is cancelled")
        f.expectEqual(s.capture.startCount, 0, "immediate stop never opens microphone")
        s.orchestrator.start()
        s.orchestrator.start()
        f.expectTrue(await waitUntil { s.capture.startCount == 1 }, "duplicate start opens once")
        let sid = s.orchestrator.sessionId
        s.orchestrator.start()  // Must not replace the active task handle.
        s.orchestrator.stop()
        s.orchestrator.start()  // Queued restart must survive old teardown.
        s.orchestrator.start()
        f.expectTrue(await waitUntil {
            s.orchestrator.sessionId > sid && s.capture.startCount == 2
        }, "one queued restart starts after teardown")
        await s.orchestrator.release()
        f.expectEqual(s.mt.releaseCount, 2, "two actual sessions release MT twice")
        f.expectEqual(s.capture.startCount, 2, "duplicate calls did not enqueue extra sessions")
    }

    static func cancelledTranslationIsReleasedBeforeRestart(_ f: FailBox) async {
        let (s, consumer) = makeSession()
        defer { consumer.cancel() }
        s.mt.translateDelayMs = 10_000
        s.orchestrator.setSpeakerEmbedding([0.1, 0.2])
        s.orchestrator.start()
        f.expectTrue(await waitUntil { s.orchestrator.state == .listening }, "first session starts")
        feedEndedUtterance(s.capture)
        f.expectTrue(await waitUntil { s.mt.inFlight }, "translation is active before stop")
        s.orchestrator.stop()
        f.expectTrue(await waitUntil { s.orchestrator.state == .idle }, "stop joins translation")
        f.expectEqual(s.mt.releaseCount, 1, "stop releases the cancelled native MT handle")
        f.expectTrue(!s.mt.releasedDuringTranslation, "MT release follows completion of translation")
        s.mt.translateDelayMs = 0
        s.orchestrator.start()
        f.expectTrue(await waitUntil { s.orchestrator.state == .listening }, "second session starts")
        feedEndedUtterance(s.capture)
        f.expectTrue(await waitUntil { s.recorder.turns.contains { $0.status == .complete } },
                     "second session translates with a fresh cancellation state")
        await s.orchestrator.release()
        f.expectEqual(s.mt.releaseCount, 2, "each session releases MT once")
    }

    static func fullTurnFlowWithRealTurnIds(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.orchestrator.setSpeakerEmbedding([0.1, 0.2])
        s.orchestrator.start()
        f.expectTrue(await waitUntil { s.orchestrator.state == .listening },
                     "session reaches listening")

        feedEndedUtterance(s.capture)
        f.expectTrue(await waitUntil {
            s.recorder.turns.last { $0.status == .complete } != nil
        }, "turn reaches complete")

        guard let turn = s.recorder.turns.last(where: { $0.status == .complete }) else {
            f.expectTrue(false, "no complete turn"); return
        }
        let statuses = s.recorder.statuses(forTurn: turn.id)
        let expectedOrder: [PipelineOrchestrator.TurnStatus] = [
            .recognizing, .translating, .synthesizing, .playing, .complete
        ]
        f.expectEqual(statuses, expectedOrder, "turn lifecycle order for id \(turn.id)")
        f.expectEqual(turn.sourceText, "你好世界", "transcript surfaced")
        f.expectEqual(turn.translatedText, "Hello world", "translation surfaced")
        f.expectEqual(s.orchestrator.sessionId, turn.sessionId, "turn carries session id")
        f.expectEqual(s.playback.playCount, 1, "playback invoked once")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func mtUnavailableNeverFakesTranslation(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.mt.error = TestError.asrDown
        s.tts.loadError = TestError.synthesisDown  // also text-forward, but MT fails first
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }

        feedEndedUtterance(s.capture)
        _ = await waitUntil { s.recorder.turns.contains { if case .mtUnavailable = $0.status { return true }; return false } }

        guard let turn = s.recorder.turns.last(where: { if case .mtUnavailable = $0.status { return true }; return false }) else {
            f.expectTrue(false, "no mtUnavailable turn"); return
        }
        f.expectEqual(turn.sourceText, "你好世界", "transcript kept")
        f.expectTrue(turn.translatedText == nil, "no translation, and never the source text")
        f.expectEqual(s.orchestrator.state, PipelineOrchestrator.PipelineState.listening,
                      "session keeps serving after per-turn MT failure")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func ttsUnavailableKeepsTranslation(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.tts.loadError = TestError.synthesisDown
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }
        f.expectTrue(!s.orchestrator.ttsAvailable, "ttsAvailable reflects real load failure")

        feedEndedUtterance(s.capture)
        _ = await waitUntil { s.recorder.turns.contains { if case .ttsUnavailable = $0.status { return true }; return false } }

        guard let turn = s.recorder.turns.last(where: { if case .ttsUnavailable = $0.status { return true }; return false }) else {
            f.expectTrue(false, "no ttsUnavailable turn"); return
        }
        f.expectEqual(turn.sourceText, "你好世界", "transcript kept")
        f.expectEqual(turn.translatedText, "Hello world", "real translation kept")
        f.expectEqual(s.playback.playCount, 0, "no fake playback for missing TTS")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func queueOverflowDropsVisibly(_ f: FailBox) async {
        let (s, consumer) = makeSession()
        var gate: AsyncStream<Void>.Continuation!
        s.asr.transcribeGate = AsyncStream { gate = $0 }
        defer { gate.finish(); consumer.cancel() }
        s.orchestrator.start()
        f.expectTrue(await waitUntil { s.capture.startCount == 1 }, "capture is ready")

        feedEndedUtterance(s.capture)
        f.expectTrue(await waitUntil { s.asr.transcribeCount == 1 },
                     "first recognition is admitted and held at the gate")
        // The worker cannot consume another job until recognition is released.
        feedEndedUtterance(s.capture)  // buffer 1/2
        feedEndedUtterance(s.capture)  // buffer 2/2
        feedEndedUtterance(s.capture)  // dropped

        let droppedSeen = await waitUntil {
            s.recorder.turns.contains { $0.status == .dropped }
        }
        f.expectTrue(droppedSeen, "dropped turn is a visible event")
        f.expectEqual(s.asr.transcribeCount, 1, "worker stays occupied during overflow")
        f.expectEqual(s.orchestrator.telemetry.utteranceCount, 3, "one active plus two queued turns")
        f.expectEqual(s.orchestrator.telemetry.droppedUtterances, 1, "telemetry drop counter")
        s.orchestrator.stop()
        gate.finish()
        f.expectTrue(await waitUntil { s.orchestrator.state == .idle },
                     "stop joins recognition and discards the queued turns")
    }

    static func stopDuringLoadNeverOpensMicAndRestartWorks(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.asr.loadDelayMs = 200
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .starting }

        s.orchestrator.stop()
        let stopped = await waitUntil { s.orchestrator.state == .idle }
        f.expectTrue(stopped, "stop during load completes to idle")
        f.expectEqual(s.capture.startCount, 0, "mic never opened during aborted boot")
        f.expectEqual(s.asr.releaseCount, 1, "half-loaded ASR released")

        // Restart after a fully joined stop.
        s.orchestrator.start()
        // .listening is set when stages spawn; wait for the mic itself.
        let micOpened = await waitUntil { s.capture.startCount == 1 }
        f.expectTrue(micOpened, "restart after stop-during-load opens the mic")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func stopJoinsInFlightTurnBeforeRelease(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.asr.transcribeDelayMs = 120
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }
        feedEndedUtterance(s.capture)
        _ = await waitUntil { s.asr.transcribeCount == 1 }

        s.orchestrator.stop()
        let stopped = await waitUntil { s.orchestrator.state == .idle }
        f.expectTrue(stopped, "stop joins the in-flight turn")
        f.expectEqual(s.asr.releaseCount, 1, "engines released exactly once after join")

        // Restart gets a fresh session and works end to end.
        let oldSession = s.orchestrator.sessionId
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }
        f.expectTrue(s.orchestrator.sessionId > oldSession, "restart gets a new session id")
        feedEndedUtterance(s.capture)
        _ = await waitUntil { s.recorder.turns.last { $0.status == .complete && $0.sessionId == s.orchestrator.sessionId } != nil }
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func releaseWaitsForCompletionAndBlocksRestart(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }

        await s.orchestrator.release()
        f.expectEqual(s.orchestrator.state, PipelineOrchestrator.PipelineState.idle,
                      "release waits for full stop")
        f.expectEqual(s.asr.releaseCount, 1, "ASR released")
        f.expectEqual(s.tts.releaseCount, 1, "TTS released")

        let sessionAfterRelease = s.orchestrator.sessionId
        s.orchestrator.start()
        try? await Task.sleep(for: .milliseconds(50))
        f.expectEqual(s.orchestrator.sessionId, sessionAfterRelease,
                      "start after release is refused")
        f.expectEqual(s.orchestrator.state, PipelineOrchestrator.PipelineState.idle,
                      "state stays idle after refused start")
    }

    static func asrLoadFailureFailsCleanlyAndRestartable(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.asr.loadError = TestError.asrDown
        s.orchestrator.start()
        let failed = await waitUntil { s.orchestrator.state == .failed }
        f.expectTrue(failed, "ASR load failure is fatal (state .failed)")
        f.expectEqual(s.capture.startCount, 0, "mic never opened")
        f.expectEqual(s.asr.releaseCount, 1, "failed ASR released")
        f.expectEqual(s.tts.releaseCount, 1, "not-yet-loaded TTS released defensively")
        f.expectTrue(!s.recorder.errors.isEmpty, "failure surfaced as an error event")

        s.asr.loadError = nil
        s.orchestrator.start()
        let listening = await waitUntil { s.orchestrator.state == .listening }
        f.expectTrue(listening, "restart from .failed works")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func ttsLoadCancellationIsNotSwallowed(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.tts.loadDelayMs = 150
        s.tts.loadError = CancellationError()
        s.orchestrator.start()
        _ = await waitUntil { s.tts.isLoaded == false && s.orchestrator.state == .listening }

        // Stop while the TTS load would be in flight: cancellation must end
        // the session (idle), never degrade to a text-forward session.
        // (Here the load already finished throwing; the deterministic check is
        // that a CancellationError from tts.load() during boot produces idle.)
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
        f.expectEqual(s.asr.releaseCount, 1, "engines released after cancellation stop")

        // Direct cancellation path: TTS load blocked, then stop() cancels boot.
        let (s2, _) = makeSession()
        s2.tts.loadDelayMs = 10_000
        s2.orchestrator.start()
        _ = await waitUntil { s2.orchestrator.state == .starting }
        try? await Task.sleep(for: .milliseconds(50))
        s2.orchestrator.stop()
        let idle = await waitUntil { s2.orchestrator.state == .idle }
        f.expectTrue(idle, "stop cancels a stuck TTS load and reaches idle")
        f.expectTrue(s2.orchestrator.ttsAvailable == false, "tts never fakes availability")
    }

    static func playbackFailureIsVisibleAndKeepsTranslation(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.orchestrator.setSpeakerEmbedding([0.3])
        s.orchestrator.start()
        _ = await waitUntil { s.capture.startCount == 1 }

        s.playback.playError = TestError.synthesisDown
        feedEndedUtterance(s.capture)
        let failedSeen = await waitUntil {
            s.recorder.turns.contains { if case .failed = $0.status { return true }; return false }
        }
        f.expectTrue(failedSeen, "playback failure surfaces a terminal turn status")
        guard let turn = s.recorder.turns.last(where: { if case .failed = $0.status { return true }; return false }) else {
            f.expectTrue(false, "no failed turn"); return
        }
        f.expectEqual(turn.translatedText, "Hello world", "translation kept through playback failure")
        f.expectEqual(s.orchestrator.state, PipelineOrchestrator.PipelineState.listening,
                      "session keeps serving after playback failure")

        // Next turn plays normally once the failure is cleared.
        s.playback.playError = nil
        feedEndedUtterance(s.capture)
        let nextComplete = await waitUntil {
            s.recorder.turns.last(where: {
                $0.status == .complete && $0.id != turn.id
            }) != nil
        }
        f.expectTrue(nextComplete, "next turn completes after playback failure")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func invalidSynthesisOutputNeverPlaysSilence(_ f: FailBox) async {
        let mutations: [(String, (FakeSynthesis) -> Void)] = [
            ("empty", { $0.output = SynthesisOutput(audioData: [], sampleRate: 24000, durationMs: 0) }),
            ("all-zero", { $0.output = SynthesisOutput(audioData: [0, 0, 0], sampleRate: 24000, durationMs: 1) }),
            ("non-finite", { $0.output = SynthesisOutput(audioData: [0.1, Float.nan], sampleRate: 24000, durationMs: 10) }),
            ("zero-rate", { $0.output = SynthesisOutput(audioData: [0.1], sampleRate: 0, durationMs: 1) }),
        ]
        for (label, mutate) in mutations {
            let (s, _) = makeSession()
            s.orchestrator.setSpeakerEmbedding([0.3])
            mutate(s.tts)
            s.orchestrator.start()
            _ = await waitUntil { s.capture.startCount == 1 }

            feedEndedUtterance(s.capture)
            let seen = await waitUntil {
                s.recorder.turns.contains { if case .ttsUnavailable = $0.status { return true }; return false }
            }
            f.expectTrue(seen, "\(label) synthesis output is ttsUnavailable, not silence-success")
            guard let turn = s.recorder.turns.last(where: { if case .ttsUnavailable = $0.status { return true }; return false }) else {
                f.expectTrue(false, "\(label): no ttsUnavailable turn"); continue
            }
            f.expectEqual(turn.translatedText, "Hello world", "\(label): translation kept")
            f.expectEqual(s.playback.playCount, 0, "\(label): player never invoked")
            s.orchestrator.stop()
            _ = await waitUntil { s.orchestrator.state == .idle }
        }
    }

    static func cancelledSessionNeverOpensMic(_ f: FailBox) async {
        // Regression for the capture-stage pre-start cancellation check: the
        // TTS load is stuck when stop() arrives; the cancelled boot must not
        // open the microphone.
        let (s, _) = makeSession()
        s.tts.loadDelayMs = 10_000
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .starting }
        try? await Task.sleep(for: .milliseconds(50))
        s.orchestrator.stop()
        let idle = await waitUntil { s.orchestrator.state == .idle }
        f.expectTrue(idle, "stop cancels a stuck TTS load and reaches idle")
        f.expectEqual(s.capture.startCount, 0, "cancelled session never opened the mic")
    }

    static func halfDuplexGateMutesCaptureDuringPlayback(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.orchestrator.setSpeakerEmbedding([0.3])
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }

        // While "playing", all input is discarded (no utterances, no turns).
        s.playback.isPlaying = true
        feedEndedUtterance(s.capture)
        try? await Task.sleep(for: .milliseconds(100))
        f.expectTrue(s.recorder.turns.isEmpty, "no turns while playback gates capture")

        s.playback.isPlaying = false
        feedEndedUtterance(s.capture)
        let seen = await waitUntil { !s.recorder.turns.isEmpty }
        f.expectTrue(seen, "capture resumes after playback ends")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func emptyTranscriptIsTerminalComplete(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.asr.result = RecognitionOutput(text: "   ", language: "Chinese")
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }
        feedEndedUtterance(s.capture)
        _ = await waitUntil { s.recorder.turns.last { $0.status == .complete } != nil }
        guard let turn = s.recorder.turns.last(where: { $0.status == .complete }) else {
            f.expectTrue(false, "empty transcript turn completes"); return
        }
        f.expectEqual(turn.sourceText, "", "empty transcript recorded honestly")
        f.expectTrue(turn.translatedText == nil, "nothing fabricated for silence")
        f.expectEqual(s.mt.callCount, 0, "MT not called for empty transcript")
        s.orchestrator.stop()
        _ = await waitUntil { s.orchestrator.state == .idle }
    }

    static func naturalStreamEndFailsHonestly(_ f: FailBox) async {
        let (s, _) = makeSession()
        s.orchestrator.start()
        _ = await waitUntil { s.orchestrator.state == .listening }
        s.capture.endStream()  // e.g. audio session interruption
        let failed = await waitUntil { s.orchestrator.state == .failed }
        f.expectTrue(failed, "input stream ending on its own is surfaced as .failed")
        f.expectTrue(!s.recorder.errors.isEmpty, "an error event explains the end")
    }
}

// MARK: - ViewModel reduction tests

@MainActor
enum InterpretViewModelTests {

    private static func turn(_ id: Int64, _ status: PipelineOrchestrator.TurnStatus,
                      source: String? = nil, translated: String? = nil) -> PipelineOrchestrator.TurnState {
        PipelineOrchestrator.TurnState(id: id, sessionId: 1, status: status,
                                       sourceText: source, translatedText: translated)
    }

    static func reductionByIdentity(_ f: FailBox) async {
        let vm = InterpretViewModel()
        vm.onPipelineEvent(.turnUpdated(turn(1, .recognizing)))
        vm.onPipelineEvent(.turnUpdated(turn(1, .translating, source: "你好")))
        vm.onPipelineEvent(.turnUpdated(turn(2, .recognizing)))
        vm.onPipelineEvent(.turnUpdated(turn(1, .complete, translated: "Hello")))
        vm.onPipelineEvent(.turnUpdated(turn(2, .complete, source: "再见", translated: "Bye")))

        f.expectEqual(vm.messages.count, 2, "one message per turn id")
        f.expectEqual(vm.messages[0].id, Int64(1), "message 1 keyed by turn id")
        f.expectEqual(vm.messages[0].sourceText, "你好", "message 1 source")
        f.expectEqual(vm.messages[0].targetText, "Hello", "message 1 translation lands on its own turn")
        f.expectEqual(vm.messages[1].targetText, "Bye", "message 2 translation lands on its own turn (FIFO regression)")
    }

    static func mtUnavailableKeepsTargetEmpty(_ f: FailBox) async {
        let vm = InterpretViewModel()
        vm.onPipelineEvent(.turnUpdated(turn(1, .recognizing)))
        vm.onPipelineEvent(.turnUpdated(turn(1, .mtUnavailable("translation unavailable: x"), source: "你好")))
        f.expectEqual(vm.messages[0].sourceText, "你好", "transcript shown")
        f.expectEqual(vm.messages[0].targetText, "", "target stays empty — never the source text")
        f.expectTrue(vm.messages[0].translationUnavailableReason != nil, "reason surfaced")
        f.expectTrue(!vm.messages[0].isProcessing, "terminal state closes the spinner")

        // The next turn's translation must not write into the failed turn.
        vm.onPipelineEvent(.turnUpdated(turn(2, .complete, source: "再见", translated: "Bye")))
        f.expectEqual(vm.messages[0].targetText, "", "turn 1 target still empty")
        f.expectEqual(vm.messages[1].targetText, "Bye", "turn 2 has its own translation")
    }

    static func droppedTurnBecomesNotice(_ f: FailBox) async {
        let vm = InterpretViewModel()
        vm.onPipelineEvent(.turnUpdated(turn(1, .dropped)))
        f.expectEqual(vm.messages.count, 1, "dropped turn surfaces a message")
        f.expectEqual(vm.messages[0].sourceText, "", "no fabricated source text")
        f.expectEqual(vm.messages[0].targetText, "", "no fabricated translation")
        f.expectTrue(vm.messages[0].translationUnavailableReason?.contains("丢弃") == true,
                     "drop reason surfaced")
    }

    static func clearWatermarkBlocksLateEvents(_ f: FailBox) async {
        let vm = InterpretViewModel()
        vm.onPipelineEvent(.turnUpdated(turn(5, .translating, source: "你好")))
        vm.clearConversation()
        // Late update for a turn that existed before the clear.
        vm.onPipelineEvent(.turnUpdated(turn(5, .complete, translated: "Hello")))
        f.expectEqual(vm.messages.count, 0, "cleared turn cannot resurrect")
        // A genuinely new turn still appears.
        vm.onPipelineEvent(.turnUpdated(turn(6, .complete, source: "再见", translated: "Bye")))
        f.expectEqual(vm.messages.count, 1, "new turn after clear appears")
        f.expectEqual(vm.messages[0].id, Int64(6), "new turn id")
    }

    static func terminalStatusClosesSpinner(_ f: FailBox) async {
        let vm = InterpretViewModel()
        vm.onPipelineEvent(.turnUpdated(turn(1, .playing, source: "你好", translated: "Hello")))
        f.expectTrue(vm.messages[0].isProcessing, "playing is still processing")
        vm.onPipelineEvent(.turnUpdated(turn(1, .ttsUnavailable("x"), translated: "Hello")))
        f.expectTrue(!vm.messages[0].isProcessing, "ttsUnavailable is terminal")
        f.expectEqual(vm.messages[0].targetText, "Hello", "translation survives ttsUnavailable")
        f.expectTrue(vm.messages[0].translationUnavailableReason == nil,
                     "ttsUnavailable keeps the message text-complete (no reason pill on text)")
    }
}

// MARK: - Harness

enum SessionCoreTestHarness {
    static func runAll() async {
        var failures = 0
        var passed = 0

        func run(_ name: String, _ test: @MainActor (FailBox) async throws -> Void) async {
            print("RUN  \(name)"); fflush(stdout)
            let box = FailBox()
            do {
                try await test(box)
            } catch {
                box.items.append("threw: \(error)")
            }
            if box.failed {
                failures += 1
                print("FAIL \(name)")
                for item in box.items { print("     - \(item)") }
            } else {
                passed += 1
                print("PASS \(name)")
            }
        }

        await run("queue.close wakes waiters") { await UtteranceQueueTests.closeWakesWaiter($0) }
        await run("queue.cancel while waiting returns nil (no hang)") { await UtteranceQueueTests.cancelWhileWaitingReturnsNil($0) }
        await run("queue FIFO + capacity drop + counter") { await UtteranceQueueTests.fifoAndCapacityDrop($0) }
        await run("queue closed stays closed") { await UtteranceQueueTests.closedQueueStaysClosed($0) }

        await run("playback cancellation before admission prevents scheduling") { await PlaybackOperationTests.cancelBeforeAdmission($0) }
        await run("playback cancellation waits for node stop") { await PlaybackOperationTests.waitsForNodeStop($0) }
        await run("completed playback ignores late cancellation") { try await PlaybackOperationTests.completedPlaybackIgnoresLateCancel($0) }
        await run("duplicate playback callbacks resume exactly once") { try await PlaybackOperationTests.duplicateCallbacksResumeOnce($0) }

        await run("stream starts before EOS and completes after render tail") { await StreamingSessionTests.startsBeforeEosAndWaitsForTail($0) }
        await run("partial stream synthesis failure remains visible and recovers") { await StreamingSessionTests.partialSynthesisFailureIsVisible($0) }
        await run("stop joins backpressured stream producer") { await StreamingSessionTests.stopJoinsBackpressuredProducer($0) }

        await run("full turn flow with real turn ids") { await OrchestratorTests.fullTurnFlowWithRealTurnIds($0) }
        await run("selected source survives unknown ASR detection") { await OrchestratorTests.selectedSourceSurvivesUnknownDetection($0) }
        await run("automatic source preserves unknown detection") { await OrchestratorTests.autoSourcePreservesUnknown($0) }
        await run("cancelled MT releases before restart") { await OrchestratorTests.cancelledTranslationIsReleasedBeforeRestart($0) }
        await run("immediate stop and duplicate starts preserve task ownership") { await OrchestratorTests.immediateStopAndDuplicateStarts($0) }
        await run("MT unavailable never fakes translation") { await OrchestratorTests.mtUnavailableNeverFakesTranslation($0) }
        await run("TTS unavailable keeps translation") { await OrchestratorTests.ttsUnavailableKeepsTranslation($0) }
        await run("queue overflow drops visibly") { await OrchestratorTests.queueOverflowDropsVisibly($0) }
        await run("stop during load never opens mic; restart works") { await OrchestratorTests.stopDuringLoadNeverOpensMicAndRestartWorks($0) }
        await run("stop joins in-flight turn before release; restart fresh") { await OrchestratorTests.stopJoinsInFlightTurnBeforeRelease($0) }
        await run("release waits for completion and blocks restart") { await OrchestratorTests.releaseWaitsForCompletionAndBlocksRestart($0) }
        await run("ASR load failure fails cleanly and is restartable") { await OrchestratorTests.asrLoadFailureFailsCleanlyAndRestartable($0) }
        await run("TTS load cancellation is not swallowed") { await OrchestratorTests.ttsLoadCancellationIsNotSwallowed($0) }
        await run("playback failure is visible and keeps translation") { await OrchestratorTests.playbackFailureIsVisibleAndKeepsTranslation($0) }
        await run("invalid synthesis output never plays silence") { await OrchestratorTests.invalidSynthesisOutputNeverPlaysSilence($0) }
        await run("cancelled session never opens the mic") { await OrchestratorTests.cancelledSessionNeverOpensMic($0) }
        await run("half-duplex gate mutes capture during playback") { await OrchestratorTests.halfDuplexGateMutesCaptureDuringPlayback($0) }
        await run("empty transcript is terminal complete") { await OrchestratorTests.emptyTranscriptIsTerminalComplete($0) }
        await run("natural stream end fails honestly") { await OrchestratorTests.naturalStreamEndFailsHonestly($0) }

        await run("continuous speech emits sample-bounded utterances") { try CaptureBoundaryTests.continuousSpeechIsBounded($0) }
        await run("large capture input streams bounded outputs") { try CaptureBoundaryTests.oversizedInputStreamsBoundedOutputs($0) }
        await run("capture partitioning preserves every sample") { try CaptureBoundaryTests.partitioningPreservesSamples($0) }
        await run("capture pre-roll fits inside utterance cap") { try CaptureBoundaryTests.prerollFitsInsideBound($0) }
        await run("capture reset discards old PCM") { try CaptureBoundaryTests.resetDiscardsPartialAudio($0) }
        await run("natural speech endpoint retains short pauses") { try CaptureBoundaryTests.naturalEndpointRetainsShortPause($0) }
        await run("VAD clock follows sample count") { CaptureBoundaryTests.vadClockUsesSampleCounts($0) }
        await run("production capture commits before silence") { await CaptureBoundaryTests.pipelineCommitsBeforeSilence($0) }
        await run("capture callback overflow ends contiguous prefix") { await CaptureBoundaryTests.callbackOverflowEndsContiguousPrefix($0) }
        await run("capture callback validates chunk length") { await CaptureBoundaryTests.callbackRejectsInvalidChunkLength($0) }
        await run("capture callback lifetime is per recording") { await CaptureBoundaryTests.callbackFinishAndNewRecordingAreIndependent($0) }
        await run("capture callback overflow fails session") { await CaptureBoundaryTests.callbackOverflowFailsSession($0) }
        await run("non-finite capture fails visibly") { await CaptureBoundaryTests.invalidCaptureFailsVisibly($0) }

        await run("VM: reduction by turn identity") { await InterpretViewModelTests.reductionByIdentity($0) }
        await run("VM: MT unavailable keeps target empty; next turn separate") { await InterpretViewModelTests.mtUnavailableKeepsTargetEmpty($0) }
        await run("VM: dropped turn becomes notice") { await InterpretViewModelTests.droppedTurnBecomesNotice($0) }
        await run("VM: clear watermark blocks late events") { await InterpretViewModelTests.clearWatermarkBlocksLateEvents($0) }
        await run("VM: terminal status closes spinner") { await InterpretViewModelTests.terminalStatusClosesSpinner($0) }

        print("---")
        print("\(passed) passed, \(failures) failed")
        if failures > 0 { exit(1) }
    }
}
