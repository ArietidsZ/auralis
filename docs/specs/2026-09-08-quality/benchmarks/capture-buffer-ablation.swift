import Foundation
func inspect(_ label: String, _ policy: AsyncStream<[Float]>.Continuation.BufferingPolicy) async -> [String: Any] {
    let pair = AsyncStream<[Float]>.makeStream(bufferingPolicy: policy)
    var drops = 0
    for i in 0..<1000 {
        if case .dropped = pair.continuation.yield([Float](repeating: Float(i), count: 3200)) { drops += 1 }
    }
    pair.continuation.finish()
    var count = 0, first = -1, last = -1
    for await chunk in pair.stream {
        if count == 0 { first = Int(chunk[0]) }
        last = Int(chunk[0]); count += 1
    }
    return ["candidate": label, "bufferedChunksObserved": count, "logicalPcmBytes": count * 3200 * 4,
            "drops": drops, "firstId": first, "lastId": last]
}

@main
enum CaptureBufferAblation {
    static func main() async throws {
        var results: [[String: Any]] = []
        results.append(await inspect("unbounded baseline", .unbounded))
        results.append(await inspect("keep newest eight and continue", .bufferingNewest(8)))
        let production = AudioChunkStream()
        var firstRejected = -1
        for i in 0..<1000 {
            if !production.yield([Float](repeating: Float(i), count: 3200)) { firstRejected = i; break }
        }
        var ids: [Int] = []
        for await chunk in production.stream { ids.append(Int(chunk[0])) }
        results.append(["candidate": "bounded contiguous prefix then terminate", "bufferedChunksObserved": ids.count,
                        "logicalPcmBytes": ids.count * 3200 * 4, "firstRejectedId": firstRejected,
                        "firstId": ids.first ?? -1, "lastId": ids.last ?? -1])
        let data = try JSONSerialization.data(withJSONObject: results, options: [.prettyPrinted, .sortedKeys])
        print(String(decoding: data, as: UTF8.self))
        precondition(ids == Array(0..<8) && firstRejected == 8)
    }
}
