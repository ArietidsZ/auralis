import Foundation

/// Sample-bounded PCM assembly. At 16 kHz, the defaults retain 200 ms of
/// pre-roll and emit at most 10 s per utterance, including that pre-roll.
/// The callback transfers each completed value without collecting another
/// unbounded list when one input chunk crosses several utterance boundaries.
final class UtteranceSegmenter {
    enum InputError: Error { case nonFiniteAudio }

    private let vad = VoiceActivityDetector()
    private let sampleRate: Int
    private let maxSamples: Int
    private let prerollSamples: Int
    private var preroll: [Float] = []
    private var speech: [Float] = []
    private var active = false

    init(sampleRate: Int = 16000, maxSamples: Int = 160000, prerollSamples: Int = 3200) {
        precondition(sampleRate > 0 && maxSamples > 0)
        precondition(prerollSamples >= 0 && prerollSamples < maxSamples)
        self.sampleRate = sampleRate
        self.maxSamples = maxSamples
        self.prerollSamples = prerollSamples
    }

    var bufferedSampleCount: Int { speech.count + preroll.count }

    func process(_ chunk: [Float], emit: ([Float]) -> Void) throws {
        guard !chunk.isEmpty else { return }
        guard chunk.allSatisfy(\.isFinite) else { throw InputError.nonFiniteAudio }
        let result = vad.process(audioChunk: chunk, sampleRate: sampleRate)
        if result.isSpeech {
            if !active {
                active = true
                append(preroll, emit: emit)
                preroll.removeAll(keepingCapacity: true)
            }
            append(chunk, emit: emit)
        } else {
            if active {
                if result.utteranceComplete && !speech.isEmpty { emitSpeech(emit) }
                else { speech.removeAll(keepingCapacity: false) }
                active = false
            }
            retainPreroll(chunk)
        }
    }

    func reset() {
        vad.reset()
        preroll.removeAll(keepingCapacity: false)
        speech.removeAll(keepingCapacity: false)
        active = false
    }

    private func append(_ samples: [Float], emit: ([Float]) -> Void) {
        var offset = 0
        while offset < samples.count {
            let count = min(maxSamples - speech.count, samples.count - offset)
            speech.append(contentsOf: samples[offset..<(offset + count)])
            offset += count
            if speech.count == maxSamples { emitSpeech(emit) }
        }
    }

    private func emitSpeech(_ emit: ([Float]) -> Void) {
        let completed = speech
        speech = []
        emit(completed)
    }

    private func retainPreroll(_ samples: [Float]) {
        guard prerollSamples > 0 else { return }
        if samples.count >= prerollSamples {
            preroll = Array(samples.suffix(prerollSamples))
        } else {
            let excess = preroll.count + samples.count - prerollSamples
            if excess > 0 { preroll.removeFirst(excess) }
            preroll.append(contentsOf: samples)
        }
    }
}
