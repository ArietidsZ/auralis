import AVFoundation
import Combine

/// Low-latency PCM audio recorder using AVAudioEngine.
/// Outputs 16kHz, mono Float32 PCM chunks for ASR processing.
@MainActor
@Observable
final class AudioRecorder {
    nonisolated static let sampleRate: Double = 16000
    nonisolated static let chunkSizeMs = 200
    nonisolated static let chunkSizeSamples = Int(sampleRate) * chunkSizeMs / 1000 // 3200

    private var engine: AVAudioEngine?
    private let bus: AVAudioNodeBus = 0

    private(set) var isRecording = false
    private(set) var amplitude: Float = 0
    /// Set when the system interrupted the session (phone call, Siri, …).
    /// The pipeline observes this and stops/pauses honestly instead of
    /// pretending recording continues.
    private(set) var wasInterrupted = false
    /// Set when the input route changed mid-recording (headset unplug, …).
    private(set) var routeChanged = false

    private var interruptionObserver: (any NSObjectProtocol)?
    private var routeChangeObserver: (any NSObjectProtocol)?

    // Chunk streaming
    private var recordingID: UUID?
    private var chunkStream: AsyncStream<[Float]>?
    private var chunkContinuation: AsyncStream<[Float]>.Continuation?

    /// Async stream of Float PCM audio chunks.
    var audioChunks: AsyncStream<[Float]> {
        if let chunkStream { return chunkStream }
        let stream = AsyncStream<[Float]> { continuation in
            self.chunkContinuation = continuation
        }
        chunkStream = stream
        return stream
    }

    /// Check if recording permission is granted.
    var hasPermission: Bool {
        AVAudioApplication.shared.recordPermission == .granted
    }

    /// Request microphone permission.
    func requestPermission() async -> Bool {
        let request = PermissionRequest()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                guard request.register(continuation) else { return }
                AVAudioApplication.requestRecordPermission { granted in
                    request.resolve(granted)
                }
            }
        } onCancel: {
            request.resolve(false)
        }
    }

    // Permission callbacks and task cancellation can arrive on different
    // threads. Completion before registration must also resume exactly once.
    private final class PermissionRequest: @unchecked Sendable {
        private let lock = NSLock()
        private var result: Bool?
        private var continuation: CheckedContinuation<Bool, Never>?

        func register(_ continuation: CheckedContinuation<Bool, Never>) -> Bool {
            let completed: Bool? = lock.withLock {
                if let result { return result }
                self.continuation = continuation
                return nil
            }
            if let completed { continuation.resume(returning: completed); return false }
            return true
        }

        func resolve(_ granted: Bool) {
            let pending: CheckedContinuation<Bool, Never>? = lock.withLock {
                guard result == nil else { return nil }
                result = granted
                let pending = continuation
                continuation = nil
                return pending
            }
            pending?.resume(returning: granted)
        }
    }

    /// Start recording audio.
    /// Emits Float chunks via the `audioChunks` AsyncStream.
    func startRecording() throws {
        guard !isRecording else { return }
        guard hasPermission else {
            throw NSError(domain: "AudioRecorder", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "microphone permission not granted; request it first"])
        }

        wasInterrupted = false
        routeChanged = false
        let id = UUID()
        recordingID = id
        var started = false
        defer { if !started { stopRecording() } }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setPreferredSampleRate(Self.sampleRate)
        try session.setActive(true)
        installSessionObservers()

        let engine = AVAudioEngine()
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: bus)
        guard inputFormat.sampleRate.isFinite, inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw NSError(domain: "AudioRecorder", code: -4,
                          userInfo: [NSLocalizedDescriptionKey: "麦克风输入不可用"])
        }

        // Target format: 16kHz mono Float32
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Self.sampleRate,
            channels: 1,
            interleaved: false
        ) else {
            throw NSError(domain: "AudioRecorder", code: -4, userInfo: [NSLocalizedDescriptionKey: "无法创建录音格式"])
        }

        // Install converter if sample rates differ
        guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            throw NSError(domain: "AudioRecorder", code: -4, userInfo: [NSLocalizedDescriptionKey: "无法转换麦克风格式"])
        }

        _ = audioChunks
        // Each tap retains only its own stream; a late callback cannot feed
        // the next recording or race a mutable continuation on the main actor.
        let continuation = chunkContinuation!
        var accumulationBuffer: [Float] = []
        accumulationBuffer.reserveCapacity(Self.chunkSizeSamples * 2)

        inputNode.installTap(onBus: bus, bufferSize: AVAudioFrameCount(Self.chunkSizeSamples), format: inputFormat) { [weak self] buffer, _ in
            // Convert to target format
            let frameCount = AVAudioFrameCount(
                ceil(Double(buffer.frameLength) * Self.sampleRate / inputFormat.sampleRate)
            )
            guard frameCount > 0,
                  let convertedBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: frameCount) else {
                return
            }

            var error: NSError?
            var suppliedInput = false
            let status = converter.convert(to: convertedBuffer, error: &error) { _, outStatus in
                guard !suppliedInput else {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                suppliedInput = true
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
                    guard let self, self.recordingID == id else { return }
                    self.amplitude = rms
                }

                continuation.yield(chunk)
            }
        }

        self.engine = engine
        try engine.start()
        isRecording = true
        started = true
        print("[AudioRecorder] Recording started: \(Self.sampleRate)Hz")
    }

    /// Stop recording.
    func stopRecording() {
        recordingID = nil
        removeSessionObservers()
        engine?.inputNode.removeTap(onBus: bus)
        engine?.stop()
        engine = nil
        isRecording = false
        amplitude = 0
        chunkContinuation?.finish()
        chunkContinuation = nil
        chunkStream = nil
        print("[AudioRecorder] Recording stopped")
    }

    // MARK: - Session interruptions / route changes

    private func installSessionObservers() {
        let center = NotificationCenter.default
        let id = recordingID
        interruptionObserver = center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: session,
            queue: .main
        ) { [weak self] notification in
            guard let info = notification.userInfo,
                  let typeRaw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeRaw) else { return }
            MainActor.assumeIsolated {
                guard let self, self.recordingID == id else { return }
                switch type {
                case .began:
                    self.wasInterrupted = true
                    self.stopRecording()
                case .ended:
                    self.wasInterrupted = false
                @unknown default:
                    break
                }
            }
        }
        routeChangeObserver = center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: session,
            queue: .main
        ) { [weak self] notification in
            guard let info = notification.userInfo,
                  let reasonRaw = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonRaw) else { return }
            MainActor.assumeIsolated {
                guard let self, self.recordingID == id else { return }
                if reason == .oldDeviceUnavailable || reason == .newDeviceAvailable {
                    self.routeChanged = true
                }
            }
        }
    }

    private func removeSessionObservers() {
        let center = NotificationCenter.default
        if let observer = interruptionObserver { center.removeObserver(observer) }
        if let observer = routeChangeObserver { center.removeObserver(observer) }
        interruptionObserver = nil
        routeChangeObserver = nil
    }

    private var session: AVAudioSession { AVAudioSession.sharedInstance() }

    /// Record a fixed duration of audio (for voice profile).
    func recordFixedDuration(durationMs: Int) async throws -> [Float] {
        try Task.checkCancellation()
        guard durationMs > 0, durationMs <= 30000 else {
            throw NSError(domain: "AudioRecorder", code: -2, userInfo: [NSLocalizedDescriptionKey: "录音时长需要在 1–30000 毫秒内"])
        }
        guard hasPermission else {
            throw NSError(domain: "AudioRecorder", code: -1, userInfo: [NSLocalizedDescriptionKey: "RECORD_AUDIO permission required"])
        }

        let totalSamples = Int(Self.sampleRate) * durationMs / 1000
        var collected: [Float] = []
        collected.reserveCapacity(totalSamples)

        guard !isRecording else {
            throw NSError(domain: "AudioRecorder", code: -5, userInfo: [NSLocalizedDescriptionKey: "已有录音正在进行"])
        }
        let chunks = audioChunks
        try Task.checkCancellation()
        try startRecording()
        let id = recordingID
        defer { if recordingID == id { stopRecording() } }

        for await chunk in chunks {
            try Task.checkCancellation()
            collected.append(contentsOf: chunk)
            if collected.count >= totalSamples {
                break
            }
        }

        try Task.checkCancellation()
        guard collected.count >= totalSamples else {
            throw NSError(domain: "AudioRecorder", code: -3, userInfo: [NSLocalizedDescriptionKey: "录音提前结束，请重新录制"])
        }
        return Array(collected.prefix(totalSamples))
    }
}
