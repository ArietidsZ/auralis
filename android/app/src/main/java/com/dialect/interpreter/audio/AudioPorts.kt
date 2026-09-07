package com.dialect.interpreter.audio

import kotlinx.coroutines.flow.StateFlow

data class StreamPlaybackResult(val renderedFrames: Long, val underruns: Int)

/**
 * Minimal capture port consumed by the session orchestrator. The real
 * implementation wraps AudioRecord; tests inject fakes that pump chunks
 * deterministically. The chunk callback runs on the capture loop's thread and
 * must not block on model work (spec R04: no inference/disk I/O in the capture
 * callback).
 */
interface AudioCapture {
    /** True when RECORD_AUDIO is granted. */
    fun hasPermission(): Boolean

    /** RMS of the most recent chunk, reset to 0 when capture stops. */
    val amplitude: StateFlow<Float>

    /**
     * Open the microphone and invoke [onChunk] for every PCM float chunk (16 kHz
     * mono) until [stop] is requested. Throws before any chunk is delivered when
     * permission is missing or hardware init fails. [onReady] is invoked exactly
     * once once the hardware is capturing. Safe to call from any coroutine; the
     * blocking read loop runs on Dispatchers.IO.
     */
    suspend fun start(onChunk: (FloatArray) -> Unit, onReady: () -> Unit = {})

    /**
     * Request [start] to return: unblocks the hardware read (recorder stop) and
     * releases the AudioRecord. Idempotent, safe from any thread, no-op when not
     * capturing.
     */
    fun stop()
}

/**
 * Minimal playback port consumed by the session orchestrator. Playback is
 * serial (spec R04); [play] suspends until the tail has been drained or [stop]
 * was requested, so "played" always means fully audible.
 */
interface AudioPlayback {
    /** True while a [play] call is writing/draining audio. */
    val isPlaying: StateFlow<Boolean>

    /**
     * Play a complete PCM float buffer. Short writes and track errors fail with
     * an exception; a [stop] request cancels the play operation. Audio-focus
     * loss is a playback failure. Normal return means the tail was rendered.
     */
    suspend fun play(audio: FloatArray, sampleRate: Int)

    /** One producer, positive PCM chunks, backpressure, and a fully drained tail. */
    suspend fun playStream(
        sampleRate: Int = 24000,
        producer: suspend (suspend (FloatArray) -> Unit) -> Unit,
    ): StreamPlaybackResult {
        val chunks = mutableListOf<FloatArray>()
        producer { chunk ->
            require(chunk.isNotEmpty() && chunk.all { it.isFinite() }) { "Invalid PCM chunk" }
            chunks += chunk.copyOf()
        }
        val count = chunks.sumOf { it.size }
        val audio = FloatArray(count)
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(audio, offset); offset += chunk.size }
        play(audio, sampleRate)
        return StreamPlaybackResult(count.toLong(), 0)
    }

    /** Immediately stop current playback; idempotent, no-op when idle. */
    fun stop()

    /** Release hardware resources; idempotent. */
    fun release()
}
