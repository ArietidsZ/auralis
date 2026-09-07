package com.dialect.interpreter.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioPlaybackFailure(message: String) : IllegalStateException(message)

/** One serial AudioTrack output path for complete buffers and progressive PCM.
 * Nonblocking writes keep stop/release outside a long native write lock.
 * Focus loss and hardware failures never become a completed playback.
 */
class AudioPlayer(private val context: Context) : AudioPlayback {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 24000
        private const val WRITE_POLL_MS = 4L
        private const val DRAIN_POLL_MS = 10L
        private const val STALL_TIMEOUT_MS = 2000L
    }


    private class Run(val rate: Int, val epoch: Long) {
        /** Allocate audio resources only when a producer supplies real PCM. */
        var track: AudioTrack? = null
        val stopped = AtomicBoolean(false)
        @Volatile var interruption: String? = null
        @Volatile var focusRequest: AudioFocusRequest? = null
        @Volatile var producerJob: Job? = null
        var writtenFrames = 0L // serial producer only
        var started = false
        var hasSignal = false
        var initialUnderruns = 0
    }

    private val playbackMutex = Mutex()
    private val trackLock = Any()
    private val stopEpoch = AtomicLong(0)
    @Volatile private var activeRun: Run? = null
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var volume = 1f
    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val playing = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = playing

    override suspend fun play(audio: FloatArray, sampleRate: Int) {
        require(audio.isNotEmpty() && audio.all { it.isFinite() } && audio.any { it != 0f }) {
            "Playback requires nonempty, finite, non-silent PCM"
        }
        playStream(sampleRate) { emit -> emit(audio) }
    }

    /** The producer suspends only when the native output buffer has no space.
     * A failed/cancelled producer stops the output; success waits for the tail.
     * This method does not promise an underrun-free stream when synthesis is slow.
     */
    override suspend fun playStream(
        sampleRate: Int,
        producer: suspend (emit: suspend (FloatArray) -> Unit) -> Unit,
    ): StreamPlaybackResult {
        require(sampleRate in 8000..192000) { "Unsupported playback sample rate" }
        val epoch = stopEpoch.get()
        return playbackMutex.withLock {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val run = synchronized(trackLock) {
                    if (epoch != stopEpoch.get()) throw CancellationException("Playback stopped before admission")
                    Run(sampleRate, epoch).also { activeRun = it }
                }
                // Prime real PCM before starting the output and claiming focus.
                var completed = false
                try {
                    checkActive(run)
                    coroutineScope {
                        val source = async(start = CoroutineStart.LAZY) {
                            checkActive(run)
                            producer { pcm -> writeChunk(run, pcm) }
                        }
                        run.producerJob = source
                        checkActive(run)
                        source.start()
                        try {
                            source.await()
                        } catch (cancelled: CancellationException) {
                            // Focus loss cancels production, but is a visible
                            // playback failure rather than a cancelled session.
                            currentCoroutineContext().ensureActive()
                            run.interruption?.let { throw AudioPlaybackFailure(it) }
                            throw cancelled
                        }
                    }
                    checkActive(run)
                    if (run.writtenFrames == 0L || !run.hasSignal) {
                        throw AudioPlaybackFailure("Audio stream is empty or entirely silent")
                    }
                    awaitTail(run)
                    checkActive(run)
                    val underruns = synchronized(trackLock) {
                        val track = run.track
                        if (track == null) 0
                        else (track.underrunCount - run.initialUnderruns).coerceAtLeast(0)
                    }
                    completed = true
                    StreamPlaybackResult(run.writtenFrames, underruns)
                } finally {
                    synchronized(trackLock) {
                        if (activeRun === run) {
                            // Pause + flush discard all unrendered PCM, so no
                            // stale audio can ever play; the track itself is
                            // kept for the serial reuse path (openTrackLocked).
                            run.track?.let { track ->
                                if (!completed || !run.started) {
                                    runCatching { track.release() }
                                    if (audioTrack === track) {
                                        audioTrack = null
                                        trackSampleRate = 0
                                    }
                                } else {
                                    runCatching { track.pause() }
                                    runCatching { track.flush() }
                                }
                            }
                            activeRun = null
                            playing.value = false
                        }
                    }
                    run.focusRequest?.let { request ->
                        runCatching { audioManager?.abandonAudioFocusRequest(request) }
                    }
                    run.focusRequest = null
                    run.producerJob = null
                }
            }
        }
    }

    private suspend fun checkActive(run: Run) {
        currentCoroutineContext().ensureActive()
        run.interruption?.let { throw AudioPlaybackFailure(it) }
        if (run.stopped.get() || run.epoch != stopEpoch.get() || activeRun !== run) {
            throw CancellationException("Audio playback stopped")
        }
    }

    private suspend fun writeChunk(run: Run, pcm: FloatArray) {
        require(pcm.isNotEmpty() && pcm.all { it.isFinite() }) { "Invalid PCM chunk" }
        checkActive(run)
        run.hasSignal = run.hasSignal || pcm.any { it != 0f }
        var offset = 0
        var progressAt = SystemClock.elapsedRealtime()
        while (offset < pcm.size) {
            checkActive(run)
            val written = synchronized(trackLock) {
                if (run.stopped.get() || activeRun !== run) 0
                else {
                    val track = run.track ?: openTrackLocked(run.rate).also { newTrack ->
                        run.track = newTrack
                        run.initialUnderruns = newTrack.underrunCount
                    }
                    if (Build.VERSION.SDK_INT < 31) {
                        // Older Android ties startup to the effective buffer
                        // size. Use real remaining PCM, not a partial write's
                        // free-space count; restore space for larger chunks.
                        val wanted = minOf(pcm.size - offset, track.bufferCapacityInFrames)
                        if (wanted != track.bufferSizeInFrames) {
                            val size = track.setBufferSizeInFrames(wanted)
                            if (size <= 0) throw AudioPlaybackFailure("AudioTrack buffer resize failed: $size")
                        }
                    }
                    val n = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    if (n > 0 && !run.started) {
                        // Claim audio focus only when real PCM is primed and
                        // about to play. Preparing/cancelling a silent producer
                        // must not interrupt other audio or acquire focus.
                        requestFocus(run)
                        // Establish the half-duplex gate before hardware can render.
                        playing.value = true
                        track.play()
                        run.started = true
                    }
                    n
                }
            }
            when {
                written > 0 -> {
                    offset += written
                    run.writtenFrames += written.toLong()
                    progressAt = SystemClock.elapsedRealtime()
                }
                written < 0 -> {
                    checkActive(run)
                    throw AudioPlaybackFailure("AudioTrack write failed: $written")
                }
                else -> {
                    checkActive(run)
                    if (SystemClock.elapsedRealtime() - progressAt >= STALL_TIMEOUT_MS) {
                        throw AudioPlaybackFailure("Audio output made no progress")
                    }
                    delay(WRITE_POLL_MS)
                }
            }
        }
    }

    private suspend fun awaitTail(run: Run) {
        val deadline = SystemClock.elapsedRealtime() + run.writtenFrames * 1000 / run.rate + STALL_TIMEOUT_MS
        while (true) {
            checkActive(run)
            val played = synchronized(trackLock) {
                val track = run.track
                if (activeRun !== run || track == null) -1L
                else Integer.toUnsignedLong(track.playbackHeadPosition)
            }
            if (played >= run.writtenFrames) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                val state = synchronized(trackLock) {
                    "head=$played written=${run.writtenFrames} " +
                        "playState=${run.track?.playState}"
                }
                throw AudioPlaybackFailure("Audio output tail did not finish: $state")
            }
            delay(DRAIN_POLL_MS)
        }
    }

    private fun requestFocus(run: Run) {
        val manager = audioManager ?: throw AudioPlaybackFailure("Audio service unavailable")
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (activeRun === run) {
                        run.interruption = "Audio playback interrupted by audio focus loss"
                        run.stopped.set(true)
                        run.producerJob?.cancel()
                        pauseAndFlush(run)
                    }
                }
            }.build()
        run.focusRequest = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw AudioPlaybackFailure("Audio focus was not granted")
        }
    }

    private fun pauseAndFlush(run: Run) {
        // Inactive tracks are cleaned up by the owning playback coroutine.
        if (!run.started) return
        synchronized(trackLock) {
            if (activeRun === run) {
                run.track?.let { track ->
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                }
            }
        }
    }

    override fun stop() {
        stopEpoch.incrementAndGet()
        activeRun?.let { run ->
            run.stopped.set(true)
            run.producerJob?.cancel()
            pauseAndFlush(run)
        }
    }

    override fun release() {
        stop()
        val previous = synchronized(trackLock) {
            val old = activeRun
            activeRun = null
            runCatching { audioTrack?.release() }
            audioTrack = null
            trackSampleRate = 0
            playing.value = false
            old
        }
        previous?.focusRequest?.let { request ->
            runCatching { audioManager?.abandonAudioFocusRequest(request) }
        }
    }

    fun setVolume(value: Float) {
        require(value.isFinite()) { "Volume must be finite" }
        synchronized(trackLock) {
            volume = value.coerceIn(0f, 1f)
            audioTrack?.setVolume(volume)
        }
    }

    private fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun openTrackLocked(sampleRate: Int): AudioTrack {
        audioTrack?.let { if (trackSampleRate == sampleRate) return it }
        runCatching { audioTrack?.release() }
        audioTrack = null
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT)
        if (minimum <= 0) throw AudioPlaybackFailure("AudioTrack buffer configuration failed: $minimum")
        val track = AudioTrack.Builder()
            .setAudioAttributes(speechAttributes())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minimum * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw AudioPlaybackFailure("AudioTrack failed to initialize")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            // Capacity is buffering space, not a minimum utterance length.
            // A short first chunk or a tail after underrun must start without
            // filling the entire buffer. PCM is never padded to satisfy it.
            track.setStartThresholdInFrames(1)
        }
        track.setVolume(volume)
        audioTrack = track
        trackSampleRate = sampleRate
        return track
    }
}
