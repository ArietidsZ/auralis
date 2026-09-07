import Foundation

/// Bounded in-flight buffer coordinator for one streaming playback session.
/// Render-thread completions never call AVAudioPlayerNode.stop().
final class StreamPlaybackOperation: @unchecked Sendable {
    let generation: UInt64
    private let maxScheduled: Int
    private let lock = NSLock()
    private var reserved = 0
    private var producerDone = false
    private var cancellationRequested = false
    private var finished = false
    private var failure: Error?
    private var spaceWaiters: [CheckedContinuation<Void, Error>] = []
    private var idleWaiter: CheckedContinuation<Void, Error>?

    init(generation: UInt64, maxScheduled: Int = 2) {
        self.generation = generation
        self.maxScheduled = maxScheduled
    }

    /// Closes admission and wakes every space/idle waiter. Node stop stays
    /// with the player after the producer has been joined.
    func requestCancellation() -> Bool {
        lock.lock()
        if finished {
            lock.unlock()
            return false
        }
        cancellationRequested = true
        let waiters = takeWaitersLocked()
        lock.unlock()
        resumeAll(waiters, error: CancellationError())
        return true
    }

    func finishCancellation() {
        finishLocked(error: CancellationError())
    }

    func fail(_ error: Error) {
        finishLocked(error: error)
    }

    func reserveSlot() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            lock.lock()
            if let early = terminalError() {
                lock.unlock()
                continuation.resume(throwing: early)
                return
            }
            if reserved < maxScheduled {
                reserved += 1
                lock.unlock()
                continuation.resume()
                return
            }
            spaceWaiters.append(continuation)
            lock.unlock()
        }
    }

    func releaseReservedSlot() {
        lock.lock()
        if !finished, reserved > 0 { reserved -= 1 }
        let waiter = takeSpaceLocked()
        lock.unlock()
        waiter?.resume()
    }

    func bufferCompleted(generation: UInt64) {
        guard generation == self.generation else { return }
        lock.lock()
        if finished || cancellationRequested {
            lock.unlock()
            return
        }
        if reserved > 0 { reserved -= 1 }
        let space = takeSpaceLocked()
        let idle = takeIdleIfDrained()
        lock.unlock()
        space?.resume()
        idle?.resume()
    }

    func markCompleted() {
        lock.lock()
        finished = true
        failure = nil
        lock.unlock()
    }

    func waitUntilIdle() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            lock.lock()
            if let early = terminalError() {
                lock.unlock()
                continuation.resume(throwing: early)
                return
            }
            producerDone = true
            if reserved == 0 {
                lock.unlock()
                continuation.resume()
                return
            }
            precondition(idleWaiter == nil)
            idleWaiter = continuation
            lock.unlock()
        }
    }

    var isFinished: Bool {
        lock.lock(); defer { lock.unlock() }
        return finished
    }

    private func takeSpaceLocked() -> CheckedContinuation<Void, Error>? {
        guard !spaceWaiters.isEmpty else { return nil }
        reserved += 1
        return spaceWaiters.removeFirst()
    }

    private func takeIdleIfDrained() -> CheckedContinuation<Void, Error>? {
        guard producerDone, reserved == 0 else { return nil }
        let idle = idleWaiter
        idleWaiter = nil
        return idle
    }

    private func takeWaitersLocked() -> [CheckedContinuation<Void, Error>] {
        var waiters = spaceWaiters
        spaceWaiters.removeAll()
        if let idle = idleWaiter {
            waiters.append(idle)
            idleWaiter = nil
        }
        return waiters
    }

    private func resumeAll(_ waiters: [CheckedContinuation<Void, Error>], error: Error) {
        for waiter in waiters { waiter.resume(throwing: error) }
    }

    private func terminalError() -> Error? {
        if finished { return failure ?? CancellationError() }
        if cancellationRequested { return CancellationError() }
        return nil
    }

    private func finishLocked(error: Error) {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        cancellationRequested = true
        failure = error
        let waiters = takeWaitersLocked()
        lock.unlock()
        resumeAll(waiters, error: error)
    }
}
