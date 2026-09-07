import AVFoundation
import Observation

/// Playback failures are real failures: the session core surfaces them as a
/// terminal turn status with the translation kept — never silence-as-success.
private final class HeardFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var heard = false
    var value: Bool { lock.lock(); defer { lock.unlock() }; return heard }
    func mark() { lock.lock(); heard = true; lock.unlock() }
}

enum PlaybackError: Error, LocalizedError {
    case initializationFailed(String)
    case invalidAudio(String)

    var errorDescription: String? {
        switch self {
        case .initializationFailed(let m): return "audio playback initialization failed: \(m)"
        case .invalidAudio(let m): return "invalid audio for playback: \(m)"
        }
    }
}

/// Low-latency PCM audio player using AVAudioEngine.
///
/// Concurrency contract:
///   - @MainActor: `isPlaying` is only touched from the orchestrator main-actor
///     context so the half-duplex capture gate reads a coherent value.
///   - `isPlaying` becomes true immediately before the first buffer is
///     scheduled/played — not while the producer is still computing. It stays
///     true across chunk gaps until the producer returns and in-flight
///     buffers complete, then clears synchronously at scope exit.
///   - Device playback uses `.dataPlayedBack`. Offline/manual rendering uses
///     `.dataRendered` because Apple documents `.dataPlayedBack` as device-only
///     (AVAudioPlayerNode.h). `.dataConsumed` is never treated as complete.
///   - Completion callbacks never call `node.stop()` — Apple warns that can
///     deadlock on the render thread. Stop runs on cancel/release/error only.
@MainActor
@Observable
final class AudioPlayer {
    nonisolated static let defaultSampleRate: Double = 24000
    /// Two in-flight buffers; a streaming vocoder chunk is 4×1920 samples.
    nonisolated static let streamQueueDepth = 2
    nonisolated static let streamMaxBufferFrames = 4 * 1920

    typealias ChunkSink = @Sendable ([Float]) async throws -> Void
    typealias StreamProducer = @MainActor (@escaping ChunkSink) async throws -> Void

    private let manualRendering: Bool
    private var engine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var currentFormat: AVAudioFormat?
    private var activeStream: StreamPlaybackOperation?
    private var producerTask: Task<Void, Error>?
    private var generationSeed: UInt64 = 0

    private(set) var isPlaying = false

    init(manualRendering: Bool = false) {
        self.manualRendering = manualRendering
    }

    /// Play a complete audio buffer. Throws on initialization/PCM/hardware
    /// failure and on cancellation; empty, non-finite or non-positive-rate
    /// audio is an explicit failure, never a silent success.
    func play(audioData: [Float], sampleRate: Double = defaultSampleRate) async throws {
        try Task.checkCancellation()
        guard !audioData.isEmpty else {
            throw PlaybackError.invalidAudio("empty audio buffer")
        }
        guard audioData.allSatisfy({ $0.isFinite }) else {
            throw PlaybackError.invalidAudio("non-finite samples in audio buffer")
        }
        guard audioData.contains(where: { $0 != 0 }) else {
            throw PlaybackError.invalidAudio("audio buffer is entirely silent")
        }
        guard sampleRate.isFinite, sampleRate > 0 else {
            throw PlaybackError.invalidAudio("invalid sample rate \(sampleRate)")
        }
        try await playStream(sampleRate: sampleRate) { sink in
            try await sink(audioData)
        }
    }

    /// Continuously schedule PCM from `producer`. The sink only waits when the
    /// two-buffer queue is full, so generation can overlap playback. The same
    /// `AVAudioPlayerNode` is reused; chunks are not played by rebuilding the
    /// node or waiting for each buffer to finish rendering.
    func playStream(sampleRate: Double = defaultSampleRate,
                    producer: @escaping StreamProducer) async throws {
        try Task.checkCancellation()
        guard activeStream == nil else {
            throw PlaybackError.initializationFailed("another playback is still active")
        }
        guard sampleRate.isFinite, sampleRate > 0 else {
            throw PlaybackError.invalidAudio("invalid sample rate \(sampleRate)")
        }

        try ensureInitialized(sampleRate: sampleRate)
        guard let node = playerNode, let format = currentFormat else {
            throw PlaybackError.initializationFailed("engine or player node unavailable")
        }
        if node.isPlaying {
            node.stop()
        }

        generationSeed &+= 1
        let op = StreamPlaybackOperation(generation: generationSeed, maxScheduled: Self.streamQueueDepth)
        activeStream = op
        let heard = HeardFlag()
        // Isolated so release() can cancel a producer blocked on Task.sleep
        // or await CancellationError, not only queue waiters.
        let work = Task { [heard] in
            try await producer { chunk in
                try Task.checkCancellation()
                if try await self.enqueue(chunk, operation: op, node: node, format: format) {
                    heard.mark()
                }
            }
        }
        producerTask = work
        defer {
            if activeStream === op {
                producerTask = nil
                activeStream = nil
                isPlaying = false
            }
        }

        // Join the producer before returning on every path. Cancellation must
        // not skip that join, and node.stop() must not run on a later session:
        // onCancel only cancels the child; stop happens on this MainActor
        // after join, and only while `activeStream === op`.
        do {
            try await withTaskCancellationHandler {
                try await work.value
                try Task.checkCancellation()
                guard heard.value else {
                    throw PlaybackError.invalidAudio("empty or entirely silent stream")
                }
                try await op.waitUntilIdle()
                op.markCompleted()
            } onCancel: {
                work.cancel()
                _ = op.requestCancellation()
            }
        } catch {
            work.cancel()
            _ = await work.result
            if activeStream === op {
                node.stop()
                if error is CancellationError {
                    op.finishCancellation()
                } else {
                    op.fail(error)
                }
            }
            throw error
        }
    }

    /// Host-only: engine has entered offline manual rendering.
    var canPullManualRender: Bool {
        manualRendering && engine?.isInManualRenderingMode == true
    }

    /// Offline pull for host checks. Not a speaker path.
    func pullManualRender(frameCount: Int) throws -> [Float] {
        guard manualRendering else {
            throw PlaybackError.initializationFailed("manual rendering is not enabled")
        }
        guard let engine, engine.isInManualRenderingMode else {
            throw PlaybackError.initializationFailed("engine is not in manual rendering mode")
        }
        guard frameCount > 0 else {
            throw PlaybackError.invalidAudio("manual render frame count must be positive")
        }
        let format = engine.manualRenderingFormat
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)) else {
            throw PlaybackError.initializationFailed("failed to allocate manual render buffer")
        }
        try engine.renderOffline(AVAudioFrameCount(frameCount), to: buffer)
        let frames = Int(buffer.frameLength)
        guard frames > 0, let channel = buffer.floatChannelData else { return [] }
        return Array(UnsafeBufferPointer(start: channel[0], count: frames))
    }

    func setVolume(_ volume: Float) {
        playerNode?.volume = min(max(volume, 0), 1)
    }

    func release() {
        let task = producerTask
        producerTask = nil
        task?.cancel()
        let operation = activeStream
        _ = operation?.requestCancellation()
        playerNode?.stop()
        engine?.stop()
        playerNode = nil
        engine = nil
        currentFormat = nil
        activeStream = nil
        isPlaying = false
        operation?.finishCancellation()
    }

    // MARK: - Private

    private var completionType: AVAudioPlayerNodeCompletionCallbackType {
        // AVAudioPlayerNode.h: dataPlayedBack applies only when rendering to
        // a device. Manual/offline host checks use dataRendered.
        manualRendering ? .dataRendered : .dataPlayedBack
    }

    private func enqueue(_ audioData: [Float],
                         operation: StreamPlaybackOperation,
                         node: AVAudioPlayerNode,
                         format: AVAudioFormat) async throws -> Bool {
        try Task.checkCancellation()
        guard !audioData.isEmpty else {
            throw PlaybackError.invalidAudio("empty audio buffer")
        }
        guard audioData.allSatisfy({ $0.isFinite }) else {
            throw PlaybackError.invalidAudio("non-finite samples in audio buffer")
        }
        let heard = audioData.contains(where: { $0 != 0 })
        var offset = 0
        while offset < audioData.count {
            try Task.checkCancellation()
            let end = min(offset + Self.streamMaxBufferFrames, audioData.count)
            let slice = Array(audioData[offset..<end])
            offset = end
            try await operation.reserveSlot()
            do {
                try schedule(slice, operation: operation, node: node, format: format)
            } catch {
                operation.releaseReservedSlot()
                throw error
            }
        }
        return heard
    }

    private func schedule(_ audioData: [Float],
                          operation: StreamPlaybackOperation,
                          node: AVAudioPlayerNode,
                          format: AVAudioFormat) throws {
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: format, frameCapacity: AVAudioFrameCount(audioData.count)
        ) else {
            throw PlaybackError.invalidAudio("failed to create PCM buffer for \(audioData.count) frames")
        }
        buffer.frameLength = AVAudioFrameCount(audioData.count)
        guard let channelData = buffer.floatChannelData else {
            throw PlaybackError.invalidAudio("PCM buffer has no Float32 channel")
        }
        audioData.withUnsafeBufferPointer { ptr in
            channelData[0].update(from: ptr.baseAddress!, count: audioData.count)
        }
        if !isPlaying {
            isPlaying = true
        }
        let generation = operation.generation
        node.scheduleBuffer(buffer, at: nil, options: [], completionCallbackType: completionType) { _ in
            // Resume only. No node.stop() — AVAudioPlayerNode.h deadlock warning.
            operation.bufferCompleted(generation: generation)
        }
        if !node.isPlaying {
            node.play()
        }
    }

    private func ensureInitialized(sampleRate: Double) throws {
        if currentFormat?.sampleRate == sampleRate, engine != nil {
            return
        }

        release()

        #if os(iOS)
        if !manualRendering {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        }
        #endif

        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: false
        ) else {
            throw PlaybackError.initializationFailed("failed to create 1-channel Float32 format @ \(sampleRate)Hz")
        }

        let engine = AVAudioEngine()
        if manualRendering {
            // Enable before touching mainMixerNode so the engine does not
            // configure device hardware (AVAudioEngine.h).
            try engine.enableManualRenderingMode(
                .offline,
                format: format,
                maximumFrameCount: AVAudioFrameCount(max(4096, Self.streamMaxBufferFrames)))
        }
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        try engine.start()

        self.engine = engine
        self.playerNode = player
        self.currentFormat = format
    }
}
