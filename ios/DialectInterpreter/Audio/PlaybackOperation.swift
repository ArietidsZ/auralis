import Foundation

/// Coordinates the render callback with cancellation without holding a lock
/// while calling AVAudioPlayerNode. After admission, cancellation only finishes
/// once the main actor has stopped the node; a late completion cannot race ahead.
final class PlaybackOperation: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var admitted = false
    private var cancellationRequested = false
    private var finished = false

    func admit(_ continuation: CheckedContinuation<Void, Error>) -> Bool {
        lock.lock()
        if cancellationRequested || finished {
            finished = true
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return false
        }
        precondition(!admitted)
        admitted = true
        self.continuation = continuation
        lock.unlock()
        return true
    }

    /// True means the owner must stop the node before calling finishCancellation.
    func requestCancellation() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !finished else { return false }
        cancellationRequested = true
        return admitted
    }

    func finishCancellation() {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        cancellationRequested = true
        finished = true
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(throwing: CancellationError())
    }

    func complete() {
        lock.lock()
        guard !finished, !cancellationRequested else { lock.unlock(); return }
        finished = true
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume()
    }
}
