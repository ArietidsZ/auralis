import Foundation
#if canImport(DialectInterpreter)
@testable import DialectInterpreter
#endif

@MainActor
private final class CaptureRecognition: RecognitionStage {
    var sampleCounts: [Int] = []
    func load() async throws {}
    func release() async {}
    func transcribe(audioData: [Float], languageHint: String?) async throws -> RecognitionOutput {
        sampleCounts.append(audioData.count)
        return RecognitionOutput(text: "captured", language: "English")
    }
}

@MainActor
private final class BoundedCapture: CaptureStagePort {
    let pipe = AudioChunkStream(capacity: 2)
    var chunks: AsyncStream<[Float]> { pipe.stream }
    var amplitude: Float { 0 }
    func start() throws {}
    func stop() { pipe.finish() }
}

@MainActor
enum CaptureBoundaryTests {
    static func continuousSpeechIsBounded(_ f: FailBox) throws {
        let segmenter = UtteranceSegmenter()
        let chunk = speechChunk()
        var completed: [[Float]] = []
        for _ in 0..<100 {
            try segmenter.process(chunk) { completed.append($0) }
            f.expectTrue(segmenter.bufferedSampleCount <= 160000, "PCM retention stays within 10 s")
        }
        f.expectEqual(completed.map(\.count), [160000, 160000], "20 s commits twice before silence")
        f.expectEqual(completed.flatMap { $0 }, [Float](repeating: 0.5, count: 320000), "every sample retained once")
    }

    static func oversizedInputStreamsBoundedOutputs(_ f: FailBox) throws {
        let segmenter = UtteranceSegmenter()
        let input = [Float](repeating: 0.5, count: 400000)
        var counts: [Int] = []
        try segmenter.process(input) { counts.append($0.count) }
        f.expectEqual(counts, [160000, 160000], "one large input emits bounded values directly")
        f.expectEqual(segmenter.bufferedSampleCount, 80000, "remaining PCM stays bounded")
    }

    static func partitioningPreservesSamples(_ f: FailBox) throws {
        let input = (0..<320).map { Float(2 + $0 % 5) / 10 }
        for step in [1, 7, 13, 32, 53, 320] {
            let segmenter = UtteranceSegmenter(maxSamples: 32, prerollSamples: 0)
            var result: [Float] = []
            for offset in stride(from: 0, to: input.count, by: step) {
                try segmenter.process(Array(input[offset..<min(offset + step, input.count)])) {
                    f.expectEqual($0.count, 32, "forced boundary is exact for partition \(step)")
                    result.append(contentsOf: $0)
                }
            }
            f.expectEqual(result, input, "partition \(step) has no lost or repeated sample")
        }
    }

    static func prerollFitsInsideBound(_ f: FailBox) throws {
        let segmenter = UtteranceSegmenter(maxSamples: 64, prerollSamples: 8)
        let silence = (0..<12).map { Float($0) * 0.00001 }
        var completed: [[Float]] = []
        try segmenter.process(silence) { completed.append($0) }
        try segmenter.process([Float](repeating: 0.5, count: 56)) { completed.append($0) }
        f.expectEqual(completed.count, 1, "pre-roll counts towards the same cap")
        if let audio = completed.first {
            f.expectEqual(audio, Array(silence.suffix(8)) + [Float](repeating: 0.5, count: 56), "latest pre-roll retained exactly once")
        }
    }

    static func resetDiscardsPartialAudio(_ f: FailBox) throws {
        let segmenter = UtteranceSegmenter(maxSamples: 64, prerollSamples: 8)
        var completed: [[Float]] = []
        try segmenter.process([Float](repeating: 0.5, count: 32)) { completed.append($0) }
        segmenter.reset()
        f.expectEqual(segmenter.bufferedSampleCount, 0, "playback/stop reset releases partial PCM")
        try segmenter.process([Float](repeating: 0.75, count: 64)) { completed.append($0) }
        f.expectEqual(completed, [[Float](repeating: 0.75, count: 64)], "new utterance excludes old session samples")
    }

    static func naturalEndpointRetainsShortPause(_ f: FailBox) throws {
        let segmenter = UtteranceSegmenter()
        var completed: [[Float]] = []
        try segmenter.process(speechChunk()) { completed.append($0) }
        try segmenter.process(silenceChunk()) { completed.append($0) }
        try segmenter.process(speechChunk()) { completed.append($0) }
        for _ in 0..<20 { try segmenter.process(silenceChunk()) { completed.append($0) } }
        f.expectEqual(completed.count, 1, "short pause does not split")
        if let audio = completed.first {
            f.expectEqual(Array(audio.prefix(9600)), speechChunk() + silenceChunk() + speechChunk(), "interior pause survives")
            f.expectTrue(audio.count <= 160000, "natural endpoint respects cap")
        }
    }

    static func vadClockUsesSampleCounts(_ f: FailBox) {
        let vad = VoiceActivityDetector()
        for _ in 0..<50 { _ = vad.process(audioChunk: [Float](repeating: 0.5, count: 320)) }
        var endedAt = 0
        for chunk in 1...100 {
            if vad.process(audioChunk: [Float](repeating: 0, count: 320)).utteranceComplete {
                endedAt = chunk * 320
                break
            }
        }
        f.expectTrue(endedAt >= 12800 && endedAt <= 19200,
            "20 ms inputs must retain at least 800 ms of endpoint silence, observed \(endedAt) samples")
    }

    static func pipelineCommitsBeforeSilence(_ f: FailBox) async {
        let asr = CaptureRecognition(), capture = FakeCapture()
        let pipeline = PipelineOrchestrator(asr: asr, translation: FakeTranslation(), tts: FakeSynthesis(),
            audioCapture: capture, audioPlayback: FakePlayback())
        pipeline.start()
        f.expectTrue(await waitUntil { pipeline.state == .listening }, "capture started")
        for _ in 0..<100 { capture.feed(speechChunk()) }
        f.expectTrue(await waitUntil { asr.sampleCounts.count == 2 }, "continuous speech reaches ASR before silence")
        f.expectEqual(asr.sampleCounts, [160000, 160000], "production path honors exact 10 s cap")
        await pipeline.release()
    }

    static func callbackOverflowEndsContiguousPrefix(_ f: FailBox) async {
        let pipe = AudioChunkStream(capacity: 2)
        f.expectTrue(pipe.yield([1]), "first chunk accepted")
        f.expectTrue(pipe.yield([2]), "second chunk accepted")
        f.expectTrue(!pipe.yield([3]), "overflow is reported")
        f.expectTrue(!pipe.yield([4]), "no chunk accepted after the gap")
        var received: [[Float]] = []
        for await chunk in pipe.stream { received.append(chunk) }
        f.expectEqual(received, [[1], [2]], "only the contiguous prefix drains, then stream ends")
    }

    static func callbackRejectsInvalidChunkLength(_ f: FailBox) async {
        for chunk in [[], [Float](repeating: 0.5, count: AudioChunkStream.maxChunkSamples + 1)] {
            let pipe = AudioChunkStream()
            f.expectTrue(!pipe.yield(chunk), "invalid chunk length terminates admission")
            var iterator = pipe.stream.makeAsyncIterator()
            f.expectTrue(await iterator.next() == nil, "invalid length never occupies the PCM budget")
        }
    }

    static func callbackFinishAndNewRecordingAreIndependent(_ f: FailBox) async {
        let old = AudioChunkStream(capacity: 2)
        let waiter = Task { () -> [Float]? in
            var iterator = old.stream.makeAsyncIterator()
            return await iterator.next()
        }
        old.finish()
        f.expectTrue(await waiter.value == nil, "finish wakes an empty consumer")
        let current = AudioChunkStream(capacity: 2)
        f.expectTrue(!old.yield([1]), "old recording stays closed")
        f.expectTrue(current.yield([2]), "new recording accepts independently")
        current.finish()
        var received: [[Float]] = []
        for await chunk in current.stream { received.append(chunk) }
        f.expectEqual(received, [[2]], "old callback cannot contaminate a new stream")
    }

    static func callbackOverflowFailsSession(_ f: FailBox) async {
        let asr = CaptureRecognition(), capture = BoundedCapture()
        let pipeline = PipelineOrchestrator(asr: asr, translation: FakeTranslation(), tts: FakeSynthesis(),
            audioCapture: capture, audioPlayback: FakePlayback())
        pipeline.start()
        _ = await waitUntil { pipeline.state == .listening }
        // One waiting iterator can receive a chunk directly in addition to
        // the two queued chunks. Four synchronous sends exceed both bounds.
        let accepted = (0..<4).map { _ in capture.pipe.yield(speechChunk()) }
        f.expectTrue(accepted[0] && accepted[1], "capacity accepts the first two callbacks")
        f.expectTrue(!accepted[3], "one in-flight plus two buffered chunks is the upper bound")
        f.expectTrue(await waitUntil { pipeline.state == .failed }, "overflow is surfaced as capture failure")
        f.expectTrue(asr.sampleCounts.isEmpty, "unfinished prefix is not fabricated into an utterance")
        await pipeline.release()
    }

    static func invalidCaptureFailsVisibly(_ f: FailBox) async {
        let asr = CaptureRecognition(), capture = FakeCapture()
        let pipeline = PipelineOrchestrator(asr: asr, translation: FakeTranslation(), tts: FakeSynthesis(),
            audioCapture: capture, audioPlayback: FakePlayback())
        pipeline.start()
        _ = await waitUntil { pipeline.state == .listening }
        capture.feed([0.5, .nan, 0.5])
        f.expectTrue(await waitUntil { pipeline.state == .failed }, "invalid capture terminates the session visibly")
        f.expectTrue(asr.sampleCounts.isEmpty, "invalid PCM never reaches ASR")
        await pipeline.release()
    }
}
