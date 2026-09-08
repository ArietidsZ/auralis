import Foundation

/// One recording's bounded callback-to-async channel. The tap produces
/// 3,200-sample chunks, so eight queued chunks retain at most 100 KiB of PCM.
/// End on the first overflow: preserving the contiguous prefix lets the
/// pipeline report capture failure instead of transcribing across a gap.
struct AudioChunkStream: Sendable {
    static let maxChunkSamples = 3200
    let stream: AsyncStream<[Float]>
    private let continuation: AsyncStream<[Float]>.Continuation

    init(capacity: Int = 8) {
        precondition(capacity > 0)
        let pair = AsyncStream<[Float]>.makeStream(bufferingPolicy: .bufferingOldest(capacity))
        stream = pair.stream
        continuation = pair.continuation
    }

    func yield(_ chunk: [Float]) -> Bool {
        guard !chunk.isEmpty, chunk.count <= Self.maxChunkSamples else {
            continuation.finish()
            return false
        }
        switch continuation.yield(chunk) {
        case .enqueued:
            return true
        case .dropped:
            continuation.finish()
            return false
        case .terminated:
            return false
        @unknown default:
            continuation.finish()
            return false
        }
    }

    func finish() { continuation.finish() }
}
