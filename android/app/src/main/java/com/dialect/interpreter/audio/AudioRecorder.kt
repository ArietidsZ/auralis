package com.dialect.interpreter.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Real AudioRecord-backed implementation of [AudioCapture].
 *
 * Design rules (spec R03/R04):
 *  - The read loop never blocks on consumers: chunks are handed to the
 *    callback synchronously; downstream buffering/queueing with explicit
 *    dropping belongs to the session layer.
 *  - No model inference or disk I/O happens here.
 *  - [stop] first flags the loop and stops the AudioRecord so a blocking
 *    `read()` returns promptly; the loop thread then releases the hardware.
 *    Callers join their own coroutine afterwards (stop-before-join order).
 */
class AudioRecorder(private val context: Context) : AudioCapture {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_MS = 200
        const val CHUNK_SIZE_SAMPLES = SAMPLE_RATE * CHUNK_SIZE_MS / 1000
    }

    private val stopRequested = AtomicBoolean(false)
    private val recordLock = Any()
    private var audioRecord: AudioRecord? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _amplitude = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amplitude

    override fun hasPermission(): Boolean = context.checkSelfPermission(
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Lint-visible permission guard on the real construction path. Also catches
     * permission revoked between check and hardware open: the SecurityException
     * propagates to the session layer as a recoverable capture failure.
     */
    private fun requireMicPermission() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
    }

    override suspend fun start(onChunk: (FloatArray) -> Unit, onReady: () -> Unit) = withContext(Dispatchers.IO) {
        check(!_isCapturing.value) { "AudioRecorder is already capturing" }
        requireMicPermission()

        val bufferSizeInBytes = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            CHUNK_SIZE_SAMPLES * 4
        )

        val recorder = synchronized(recordLock) {
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSizeInBytes
                )
            } catch (e: SecurityException) {
                // Permission revoked between the guard and hardware open.
                throw SecurityException("RECORD_AUDIO permission revoked: ${e.message}", e)
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("AudioRecord failed to initialize")
            }
            audioRecord = record
            record
        }

        stopRequested.set(false)
        _isCapturing.value = true
        Log.i(TAG, "Recording started: ${SAMPLE_RATE}Hz, buffer=$bufferSizeInBytes bytes")

        try {
            // These can fail after construction (including permission
            // revocation), so they share the recorder's release path.
            recorder.startRecording()
            onReady()
            val buffer = ShortArray(CHUNK_SIZE_SAMPLES)
            while (!stopRequested.get() && coroutineContext.isActive) {
                // Never hold recordLock through a blocking read: stop() needs
                // that lock to call AudioRecord.stop() and unblock this thread.
                // Only this capture thread releases the recorder in finally.
                val readCount = if (stopRequested.get()) -1
                    else recorder.read(buffer, 0, CHUNK_SIZE_SAMPLES)

                when {
                    readCount > 0 -> {
                        val floatChunk = FloatArray(readCount)
                        var energy = 0.0
                        for (i in 0 until readCount) {
                            val sample = buffer[i] / 32768f
                            floatChunk[i] = sample
                            energy += (sample * sample).toDouble()
                        }
                        _amplitude.value = sqrt(energy / readCount).toFloat()
                        onChunk(floatChunk)
                    }
                    stopRequested.get() -> break
                    readCount == 0 -> Unit // transient; keep polling
                    else -> throw IllegalStateException(
                        "AudioRecord read error: $readCount"
                    )
                }
            }
        } finally {
            synchronized(recordLock) {
                stopAndReleaseRecorder(audioRecord)
                audioRecord = null
            }
            _isCapturing.value = false
            _amplitude.value = 0f
            Log.i(TAG, "Recording loop exited")
        }
    }

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        synchronized(recordLock) {
            val record = audioRecord ?: return
            // Stopping the recorder from here unblocks a pending read() on the
            // capture thread; the loop thread performs the release itself.
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }.onFailure { Log.w(TAG, "Error stopping recorder: ${it.message}") }
        }
    }

    /**
     * Record a fixed duration of audio (voice profile capture; UI-owned API).
     *
     * @return Complete PCM float audio at 16 kHz
     */
    suspend fun recordFixedDuration(durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val collected = FloatArray(totalSamples)
        var writeIndex = 0

        requireMicPermission()

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            CHUNK_SIZE_SAMPLES * 4
        )

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            throw SecurityException("RECORD_AUDIO permission revoked: ${e.message}", e)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        try {
            recorder.startRecording()
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
                    _amplitude.value = sqrt(energy / readCount).toFloat()
                } else if (readCount < 0) {
                    throw IllegalStateException("Fixed-duration read error: $readCount")
                }
            }
        } finally {
            stopAndReleaseRecorder(recorder)
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
        }.onFailure { Log.w(TAG, "Error stopping recorder: ${it.message}") }
        runCatching {
            recorder.release()
        }.onFailure { Log.w(TAG, "Error releasing recorder: ${it.message}") }
    }
}
