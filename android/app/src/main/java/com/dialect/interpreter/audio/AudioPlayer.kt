package com.dialect.interpreter.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Low-latency PCM audio player using AudioTrack API.
 * Designed for streaming synthesized speech playback.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000  // Qwen3-TTS output rate
    }

    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /**
     * Initialize the audio player for a specific sample rate.
     */
    fun initialize(sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        Log.i(TAG, "AudioPlayer initialized: ${sampleRate}Hz, buffer=${bufferSize}")
    }

    /**
     * Play a complete audio buffer.
     */
    suspend fun play(audioData: FloatArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) =
        withContext(Dispatchers.IO) {
            if (audioData.isEmpty()) return@withContext

            ensureInitialized(sampleRate)
            val track = audioTrack ?: return@withContext

            try {
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                }

                track.play()
                _isPlaying.value = true

                var offset = 0
                while (offset < audioData.size) {
                    val written = track.write(
                        audioData,
                        offset,
                        audioData.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )

                    if (written <= 0) {
                        throw IllegalStateException("AudioTrack write failed: $written")
                    }
                    offset += written
                }

                awaitPlaybackComplete(track, audioData.size, sampleRate)
                Log.i(TAG, "Played ${audioData.size} samples")

            } finally {
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                }.onFailure {
                    Log.w(TAG, "Error stopping playback: ${it.message}")
                }
                _isPlaying.value = false
            }
        }

    /**
     * Write a chunk of audio for streaming playback.
     * Call play() first to start the AudioTrack.
     */
    fun writeChunk(chunk: FloatArray): Int {
        val track = audioTrack ?: return -1
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
            _isPlaying.value = true
        }
        return track.write(chunk, 0, chunk.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    /**
     * Start streaming mode.
     */
    fun startStreaming(sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        ensureInitialized(sampleRate)
        audioTrack?.play()
        _isPlaying.value = true
    }

    /**
     * Stop streaming.
     */
    fun stopStreaming() {
        runCatching {
            audioTrack?.stop()
        }.onFailure {
            Log.w(TAG, "Error stopping stream: ${it.message}")
        }
        _isPlaying.value = false
    }

    /**
     * Set playback volume (0.0 to 1.0).
     */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    /**
     * Release all audio resources.
     */
    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null
        _isPlaying.value = false
    }

    private fun ensureInitialized(sampleRate: Int) {
        if (audioTrack == null || audioTrack?.sampleRate != sampleRate) {
            release()
            initialize(sampleRate)
        }
    }

    private suspend fun awaitPlaybackComplete(
        track: AudioTrack,
        frameCount: Int,
        sampleRate: Int
    ) {
        val expectedDurationMs = frameCount * 1000L / sampleRate
        val timeoutMs = expectedDurationMs + 2_000L
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (track.playbackHeadPosition >= frameCount) return
            delay(10)
        }

        Log.w(TAG, "Playback completion timeout after ${timeoutMs}ms")
    }
}
