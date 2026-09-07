import Foundation
#if canImport(DialectInterpreter)
@testable import DialectInterpreter
#endif

@MainActor
enum PlaybackOperationTests {
    static func cancelBeforeAdmission(_ f: FailBox) async {
        let op = PlaybackOperation()
        f.expectTrue(!op.requestCancellation(), "no scheduled node needs stopping")
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                f.expectTrue(!op.admit(continuation), "cancellation must prevent scheduling")
            }
            f.expectTrue(false, "expected cancellation")
        } catch is CancellationError { } catch {
            f.expectTrue(false, "unexpected error: \(error)")
        }
    }

    static func waitsForNodeStop(_ f: FailBox) async {
        let op = PlaybackOperation()
        var admitted = false
        var completed = false
        var cancelled = false
        let waiter = Task { @MainActor in
            do {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    admitted = op.admit(continuation)
                }
            } catch is CancellationError { cancelled = true } catch { }
            completed = true
        }
        _ = await waitUntil { admitted }
        f.expectTrue(op.requestCancellation(), "admitted playback requires a stop")
        op.complete() // render callback racing cancellation must not win
        try? await Task.sleep(for: .milliseconds(20))
        f.expectTrue(!completed, "cancellation must wait until the node has stopped")
        op.finishCancellation()
        await waiter.value
        f.expectTrue(cancelled, "stop acknowledgement must complete as cancellation")
    }

    static func completedPlaybackIgnoresLateCancel(_ f: FailBox) async throws {
        let op = PlaybackOperation()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            f.expectTrue(op.admit(continuation), "playback should be admitted")
            op.complete()
        }
        f.expectTrue(!op.requestCancellation(), "late cancellation must not stop a reused node")
    }

    static func duplicateCallbacksResumeOnce(_ f: FailBox) async throws {
        let op = PlaybackOperation()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            f.expectTrue(op.admit(continuation), "playback should be admitted")
            op.complete()
            op.complete()
            op.finishCancellation()
        }
        f.expectTrue(!op.requestCancellation(), "finished operation must remain finished")
    }
}
