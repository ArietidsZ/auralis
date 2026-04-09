import AVFoundation
import Combine

/// Low-latency PCM audio recorder using AVAudioEngine.
/// Outputs 16kHz, mono Float32 PCM chunks for ASR processing.
@Observable
final class AudioRecorder {
    static let sampleRate: Double = 16000
    static let chunkSizeMs = 200
    static let chunkSizeSamples = Int(sampleRate) * chunkSizeMs / 1000 // 3200

    private var engine: AVAudioEngine?
    private let bus: AVAudioNodeBus = 0

    private(set) var isRecording = false
    private(set) var amplitude: Float = 0

    // Chunk streaming
    private var chunkContinuation: AsyncStream<[Float]>.Continuation?

    /// Async stream of Float PCM audio chunks.
    var audioChunks: AsyncStream<[Float]> {
        AsyncStream { continuation in
            self.chunkContinuation = continuation
            continuation.onTermination = { @Sendable _ in
                // cleanup handled by stopRecording
            }
        }
    }

    /// Check if recording permission is granted.
    var hasPermission: Bool {
        AVAudioApplication.shared.recordPermission == .granted
    }

    /// Request microphone permission.
    func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    /// Start recording audio.
    /// Emits Float chunks via the `audioChunks` AsyncStream.
    func startRecording() throws {
        guard !isRecording else { return }
        guard hasPermission else {
            print("[AudioRecorder] RECORD_AUDIO permission not granted")
            return
        }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setPreferredSampleRate(Self.sampleRate)
        try session.setActive(true)

        let engine = AVAudioEngine()
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: bus)

        // Target format: 16kHz mono Float32
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Self.sampleRate,
            channels: 1,
            interleaved: false
        ) else {
            print("[AudioRecorder] Failed to create target format")
            return
        }

        // Install converter if sample rates differ
        guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            print("[AudioRecorder] Failed to create audio converter")
            return
        }

        var accumulationBuffer: [Float] = []
        accumulationBuffer.reserveCapacity(Self.chunkSizeSamples * 2)

        inputNode.installTap(onBus: bus, bufferSize: AVAudioFrameCount(Self.chunkSizeSamples), format: inputFormat) { [weak self] buffer, _ in
            guard let self else { return }

            // Convert to target format
            let frameCount = AVAudioFrameCount(
                Double(buffer.frameLength) * Self.sampleRate / inputFormat.sampleRate
            )
            guard frameCount > 0,
                  let convertedBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: frameCount) else {
                return
            }

            var error: NSError?
            let status = converter.convert(to: convertedBuffer, error: &error) { _, outStatus in
                outStatus.pointee = .haveData
                return buffer
            }

            guard status != .error, error == nil,
                  let channelData = convertedBuffer.floatChannelData else {
                return
            }

            let samples = Array(UnsafeBufferPointer(
                start: channelData[0],
                count: Int(convertedBuffer.frameLength)
            ))

            accumulationBuffer.append(contentsOf: samples)

            // Emit chunks of chunkSizeSamples
            while accumulationBuffer.count >= Self.chunkSizeSamples {
                let chunk = Array(accumulationBuffer.prefix(Self.chunkSizeSamples))
                accumulationBuffer.removeFirst(Self.chunkSizeSamples)

                // Calculate RMS
                var energy: Double = 0
                for sample in chunk {
                    energy += Double(sample * sample)
                }
                let rms = Float(sqrt(energy / Double(chunk.count)))

                DispatchQueue.main.async {
                    self.amplitude = rms
                }

                self.chunkContinuation?.yield(chunk)
            }
        }

        try engine.start()
        self.engine = engine
        isRecording = true
        print("[AudioRecorder] Recording started: \(Self.sampleRate)Hz")
    }

    /// Stop recording.
    func stopRecording() {
        engine?.inputNode.removeTap(onBus: bus)
        engine?.stop()
        engine = nil
        isRecording = false
        amplitude = 0
        chunkContinuation?.finish()
        chunkContinuation = nil
        print("[AudioRecorder] Recording stopped")
    }

    /// Record a fixed duration of audio (for voice profile).
    func recordFixedDuration(durationMs: Int) async throws -> [Float] {
        guard hasPermission else {
            throw NSError(domain: "AudioRecorder", code: -1, userInfo: [NSLocalizedDescriptionKey: "RECORD_AUDIO permission required"])
        }

        let totalSamples = Int(Self.sampleRate) * durationMs / 1000
        var collected: [Float] = []
        collected.reserveCapacity(totalSamples)

        try startRecording()
        defer { stopRecording() }

        for await chunk in audioChunks {
            collected.append(contentsOf: chunk)
            amplitude = {
                var energy: Double = 0
                for s in chunk { energy += Double(s * s) }
                return Float(sqrt(energy / Double(chunk.count)))
            }()
            if collected.count >= totalSamples {
                break
            }
        }

        return Array(collected.prefix(totalSamples))
    }
}
