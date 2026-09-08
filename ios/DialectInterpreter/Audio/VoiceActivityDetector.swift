import Foundation

/// Simple energy-based Voice Activity Detector.
/// Detects when the user is speaking vs. silence.
final class VoiceActivityDetector {
    private static let energyThreshold: Float = 0.02
    private static let silenceTimeoutMs: Int64 = 800
    private static let minSpeechDurationMs: Int64 = 200

    enum State {
        case silence, speech, trailingSilence
    }

    struct VadResult {
        let isSpeech: Bool
        let utteranceComplete: Bool
        let energy: Float
    }

    private var state: State = .silence
    private var speechStartTime: Int64 = 0
    private var silenceStartTime: Int64 = 0
    private var audioClockSamples: Int64 = 0
    private var energySmoothed: Float = 0

    /// Process an audio chunk and return VAD result.
    func process(audioChunk: [Float], sampleRate: Int = 16000) -> VadResult {
        precondition(sampleRate > 0)
        guard !audioChunk.isEmpty else {
            return VadResult(
                isSpeech: state == .speech || state == .trailingSilence,
                utteranceComplete: false,
                energy: energySmoothed
            )
        }

        // Calculate RMS energy
        var energy: Double = 0
        for sample in audioChunk {
            energy += Double(sample * sample)
        }
        let rms = Float(sqrt(energy / Double(audioChunk.count)))

        // Exponential smoothing
        energySmoothed = 0.7 * energySmoothed + 0.3 * rms

        let isSpeech = energySmoothed > Self.energyThreshold
        let now = audioClockSamples * 1000 / Int64(sampleRate)
        var utteranceComplete = false

        switch state {
        case .silence:
            if isSpeech {
                state = .speech
                speechStartTime = now
            }
        case .speech:
            if !isSpeech {
                state = .trailingSilence
                silenceStartTime = now
            }
        case .trailingSilence:
            if isSpeech {
                state = .speech
            } else if now - silenceStartTime > Self.silenceTimeoutMs {
                let speechDuration = silenceStartTime - speechStartTime
                if speechDuration >= Self.minSpeechDurationMs {
                    utteranceComplete = true
                }
                state = .silence
            }
        }

        audioClockSamples += Int64(audioChunk.count)

        return VadResult(
            isSpeech: state == .speech || state == .trailingSilence,
            utteranceComplete: utteranceComplete,
            energy: energySmoothed
        )
    }

    /// Reset the VAD state.
    func reset() {
        state = .silence
        speechStartTime = 0
        silenceStartTime = 0
        audioClockSamples = 0
        energySmoothed = 0
    }
}
