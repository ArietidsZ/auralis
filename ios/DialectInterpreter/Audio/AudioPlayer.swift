import AVFoundation

/// Low-latency PCM audio player using AVAudioEngine.
/// Designed for synthesized speech playback.
@Observable
final class AudioPlayer {
    static let defaultSampleRate: Double = 24000 // Qwen3-TTS output rate

    private var engine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var currentFormat: AVAudioFormat?

    private(set) var isPlaying = false

    /// Play a complete audio buffer.
    func play(audioData: [Float], sampleRate: Double = defaultSampleRate) async {
        guard !audioData.isEmpty else { return }

        await MainActor.run { isPlaying = true }
        defer { Task { @MainActor in self.isPlaying = false } }

        do {
            try ensureInitialized(sampleRate: sampleRate)
            guard let playerNode, let format = currentFormat else { return }

            // Stop any current playback
            if playerNode.isPlaying {
                playerNode.stop()
            }

            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(audioData.count)) else {
                print("[AudioPlayer] Failed to create buffer")
                return
            }

            buffer.frameLength = AVAudioFrameCount(audioData.count)
            if let channelData = buffer.floatChannelData {
                audioData.withUnsafeBufferPointer { ptr in
                    channelData[0].update(from: ptr.baseAddress!, count: audioData.count)
                }
            }

            // Schedule and play
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                playerNode.scheduleBuffer(buffer) {
                    continuation.resume()
                }
                if !playerNode.isPlaying {
                    playerNode.play()
                }
            }

            let durationMs = Int(Double(audioData.count) * 1000.0 / sampleRate)
            print("[AudioPlayer] Played \(audioData.count) samples (\(durationMs)ms)")

        } catch {
            print("[AudioPlayer] Playback error: \(error)")
        }
    }

    /// Set playback volume (0.0 to 1.0).
    func setVolume(_ volume: Float) {
        playerNode?.volume = min(max(volume, 0), 1)
    }

    /// Release all audio resources.
    func release() {
        playerNode?.stop()
        engine?.stop()
        playerNode = nil
        engine = nil
        currentFormat = nil
        isPlaying = false
    }

    // MARK: - Private

    private func ensureInitialized(sampleRate: Double) throws {
        if currentFormat?.sampleRate == sampleRate, engine != nil {
            return
        }

        release()

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: false
        ) else {
            print("[AudioPlayer] Failed to create format")
            return
        }

        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()

        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)

        try engine.start()

        self.engine = engine
        self.playerNode = player
        self.currentFormat = format

        print("[AudioPlayer] Initialized: \(sampleRate)Hz")
    }
}
