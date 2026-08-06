package com.dialect.interpreter.audio

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Low-latency PCM audio recorder using AudioRecord API.
 * Outputs 16kHz, 16-bit, mono PCM chunks for ASR processing.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_MS = 200  // 200ms chunks for ASR mel accumulation
        val CHUNK_SIZE_SAMPLES = SAMPLE_RATE * CHUNK_SIZE_MS / 1000
    }

    private var audioRecord: AudioRecord? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    // DROP_OLDEST: the hardware read loop must never block on a slow consumer,
    // or the OS audio buffer overflows and we lose captures unpredictably. Under
    // extreme backpressure we'd rather evict the oldest raw chunk than stall the
    // recorder; committed utterances are protected upstream at the pipeline layer.
    private val _audioChunks = MutableSharedFlow<FloatArray>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioChunks: SharedFlow<FloatArray> = _audioChunks

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    /**
     * Check if recording permission is granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording audio in a coroutine.
     * Emits FloatArray chunks via audioChunks flow.
     *
     * @throws IllegalStateException if RECORD_AUDIO permission is missing or the
     *   input device fails to initialize — the caller surfaces this as a
     *   recoverable error rather than leaving the session silently un-mic'd.
     */
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return@withContext
        }

        if (!hasPermission()) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            CHUNK_SIZE_SAMPLES * 2  // 16-bit = 2 bytes per sample
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            audioRecord?.startRecording()
            _isRecording.value = true
            Log.i(TAG, "Recording started: ${SAMPLE_RATE}Hz, buffer=${bufferSize}")

            val buffer = ShortArray(CHUNK_SIZE_SAMPLES)

            while (isActive && _isRecording.value) {
                val readCount = audioRecord?.read(buffer, 0, CHUNK_SIZE_SAMPLES) ?: -1

                if (readCount > 0) {
                    // Convert 16-bit PCM to float [-1, 1] and calculate RMS in one pass
                    val floatChunk = FloatArray(readCount)
                    var energy = 0.0
                    for (i in 0 until readCount) {
                        val sample = buffer[i] / 32768f
                        floatChunk[i] = sample
                        energy += (sample * sample).toDouble()
                    }

                    val rms = sqrt(energy / readCount).toFloat()
                    _amplitude.value = rms

                    // Emit chunk
                    _audioChunks.emit(floatChunk)
                } else if (readCount == AudioRecord.ERROR_BAD_VALUE || readCount == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.w(TAG, "AudioRecord read error: $readCount")
                    break
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recording start error: ${e.message}", e)
            throw e
        } finally {
            stopAndReleaseRecorder(audioRecord)
            audioRecord = null
            _isRecording.value = false
            _amplitude.value = 0f
        }
    }

    /**
     * Stop recording.
     */
    fun stopRecording() {
        _isRecording.value = false
        stopAndReleaseRecorder(audioRecord)
        audioRecord = null
        _amplitude.value = 0f
        Log.i(TAG, "Recording stopped")
    }

    /**
     * Record a fixed duration of audio (for voice profile).
     *
     * @param durationMs Duration to record in milliseconds
     * @return Complete PCM float audio
     */
    @SuppressLint("MissingPermission")
    suspend fun recordFixedDuration(durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val collected = FloatArray(totalSamples)
        var writeIndex = 0

        if (!hasPermission()) {
            throw SecurityException("RECORD_AUDIO permission required")
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            CHUNK_SIZE_SAMPLES * 2
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        try {
            recorder.startRecording()
            _isRecording.value = true

            val buffer = ShortArray(CHUNK_SIZE_SAMPLES)

            while (writeIndex < totalSamples && isActive) {
                val readTarget = minOf(CHUNK_SIZE_SAMPLES, totalSamples - writeIndex)
                val readCount = recorder.read(buffer, 0, readTarget)
                if (readCount > 0) {
                    var energy = 0.0
                    for (i in 0 until readCount) {
                        val sample = buffer[i] / 32768f
                        collected[writeIndex++] = sample
                        energy += (sample * sample).toDouble()
                    }
                    val rms = sqrt(energy / readCount).toFloat()
                    _amplitude.value = rms
                } else if (readCount == AudioRecord.ERROR_BAD_VALUE || readCount == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.w(TAG, "Fixed-duration read error: $readCount")
                    break
                }
            }
        } finally {
            stopAndReleaseRecorder(recorder)
            _isRecording.value = false
            _amplitude.value = 0f
        }

        if (writeIndex == collected.size) collected else collected.copyOf(writeIndex)
    }

    private fun stopAndReleaseRecorder(recorder: AudioRecord?) {
        recorder ?: return

        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }.onFailure {
            Log.w(TAG, "Error stopping recorder: ${it.message}")
        }

        runCatching {
            recorder.release()
        }.onFailure {
            Log.w(TAG, "Error releasing recorder: ${it.message}")
        }
    }
}
