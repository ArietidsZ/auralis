package com.dialect.interpreter.inference

import android.util.Log
import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.AudioRecorder
import com.dialect.interpreter.audio.VoiceActivityDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Orchestrates the real-time ASR → TTS pipeline with maximum concurrency.
 *
 * Architecture (3-stage pipeline running in parallel):
 *
 *   Stage 1 (IO):     AudioRecorder → PCM chunks → VAD → utterance buffer
 *   Stage 2 (Default): ASR encoder+decoder → text ──────┐
 *   Stage 3 (Default): TTS synthesize → PCM audio ──────┤
 *   Stage 4 (IO):     AudioTrack playback ◄─────────────┘
 *
 * Key optimizations:
 *  - Channel-based producer/consumer: ASR output feeds TTS without blocking
 *  - Separate coroutine dispatchers: I/O (audio) vs Default (NPU inference)
 *  - Double buffering: next utterance is ASR'd while current one is TTS'd
 *  - Streaming vocoder: TTS starts outputting audio before full sentence is generated
 */
class PipelineOrchestrator(
    private val asrEngine: AsrEngine,
    private val ttsEngine: TtsEngine,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer
) {
    companion object {
        private const val TAG = "Pipeline"
    }

    // Pipeline events
    sealed class PipelineEvent {
        data class AsrResult(val text: String, val language: String, val latencyMs: Long) : PipelineEvent()
        data class TtsStarted(val text: String) : PipelineEvent()
        data class TtsComplete(
            val durationMs: Long,
            val inferenceMs: Long,
            val playbackMs: Long,
            val rtf: Float
        ) : PipelineEvent()
        data class StateChange(val state: PipelineState) : PipelineEvent()
        data class Error(val message: String) : PipelineEvent()
    }

    enum class PipelineState { IDLE, LOADING, LISTENING, RECOGNIZING, SYNTHESIZING, PLAYING }

    data class PipelineTelemetry(
        val utteranceCount: Long = 0,
        val droppedUtterances: Long = 0,
        val droppedSynthesisJobs: Long = 0,
        val asrCount: Long = 0,
        val ttsCount: Long = 0,
        val lastUtteranceMs: Long = 0,
        val avgUtteranceMs: Long = 0,
        val lastAsrMs: Long = 0,
        val avgAsrMs: Long = 0,
        val lastTtsMs: Long = 0,
        val avgTtsMs: Long = 0,
        val lastPlaybackMs: Long = 0,
        val avgPlaybackMs: Long = 0,
        val lastRtf: Float = 0f,
        val avgRtf: Float = 0f
    )

    // Internal channels for pipeline stages
    private val utteranceChannel = Channel<FloatArray>(
        capacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val synthesisChannel = Channel<SynthesisJob>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private data class SynthesisJob(val text: String, val language: String)

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PipelineEvent> = _events

    private val _state = MutableStateFlow(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    private val _telemetry = MutableStateFlow(PipelineTelemetry())
    val telemetry: StateFlow<PipelineTelemetry> = _telemetry

    private var pipelineJob: Job? = null
    private var speakerEmbedding: FloatArray? = null

    /**
     * Set the voice profile speaker embedding for TTS.
     */
    fun setSpeakerEmbedding(embedding: FloatArray) {
        speakerEmbedding = embedding
    }

    /**
     * Start the full 4-stage pipeline.
     */
    fun start(
        scope: CoroutineScope,
        targetLanguage: String = "Chinese"
    ) {
        if (pipelineJob?.isActive == true) return

        pipelineJob = scope.launch {
            try {
                updateState(PipelineState.LOADING)

                // Load models concurrently
                coroutineScope {
                    launch { asrEngine.load() }
                    launch { ttsEngine.load() }
                }

                // Start all pipeline stages concurrently
                coroutineScope {
                    // Stage 1: Audio capture + VAD
                    launch(Dispatchers.IO) { captureStage() }

                    // Stage 2: ASR (runs on Default dispatcher → NPU)
                    launch(Dispatchers.Default) { asrStage() }

                    // Stage 3: TTS synthesis (runs on Default dispatcher → NPU)
                    launch(Dispatchers.Default) { ttsStage(targetLanguage) }
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Pipeline cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                _events.emit(PipelineEvent.Error(e.message ?: "Unknown error"))
            } finally {
                updateState(PipelineState.IDLE)
            }
        }
    }

    /**
     * Stage 1: Capture audio, run VAD, send complete utterances to ASR.
     */
    private suspend fun captureStage() {
        val vad = VoiceActivityDetector()
        val utteranceBuffer = mutableListOf<FloatArray>()

        updateState(PipelineState.LISTENING)

        // Collect audio chunks and amplitude in parallel
        coroutineScope {
            val amplitudeJob = launch {
                audioRecorder.amplitude.collect { _amplitude.value = it }
            }

            val chunkJob = launch {
                audioRecorder.audioChunks.collect { chunk ->
                    val vadResult = vad.process(
                        audioChunk = chunk,
                        chunkDurationMs = AudioRecorder.CHUNK_SIZE_MS.toLong()
                    )

                    if (vadResult.isSpeech) {
                        utteranceBuffer.add(chunk)
                    }

                    if (vadResult.utteranceComplete && utteranceBuffer.isNotEmpty()) {
                        // Concatenate all chunks into one utterance
                        val totalSize = utteranceBuffer.sumOf { it.size }
                        val utterance = FloatArray(totalSize)
                        var offset = 0
                        for (c in utteranceBuffer) {
                            c.copyInto(utterance, offset)
                            offset += c.size
                        }
                        utteranceBuffer.clear()
                        val utteranceDurationMs =
                            utterance.size.toLong() * 1000L / AsrEngine.SAMPLE_RATE

                        // Non-blocking send to ASR stage
                        val sendResult = utteranceChannel.trySend(utterance)
                        if (sendResult.isSuccess) {
                            recordUtteranceMetrics(utteranceDurationMs)
                            Log.d(TAG, "Utterance sent: ${utterance.size} samples (${utterance.size * 1000 / AsrEngine.SAMPLE_RATE}ms)")
                        } else {
                            _telemetry.update { current ->
                                current.copy(droppedUtterances = current.droppedUtterances + 1)
                            }
                            Log.w(TAG, "Dropped utterance due to backpressure")
                        }
                    }
                }
            }

            try {
                audioRecorder.startRecording()
            } finally {
                amplitudeJob.cancel()
                chunkJob.cancel()
                vad.reset()
            }
        }
    }

    /**
     * Stage 2: Receive utterances, run ASR, send text to TTS.
     * This runs on Dispatchers.Default which gives the NPU inference thread.
     */
    private suspend fun asrStage() {
        for (utterance in utteranceChannel) {
            updateState(PipelineState.RECOGNIZING)

            val t0 = System.nanoTime()
            val result = asrEngine.transcribe(utterance)
            val latencyMs = (System.nanoTime() - t0) / 1_000_000

            recordAsrMetrics(latencyMs)

            _events.emit(PipelineEvent.AsrResult(result.text, result.language, latencyMs))

            if (result.text.isNotBlank()) {
                // Non-blocking send to TTS stage — ASR can start on next utterance immediately
                val sendResult = synthesisChannel.trySend(SynthesisJob(result.text, result.language))
                if (sendResult.isFailure) {
                    _telemetry.update { current ->
                        current.copy(droppedSynthesisJobs = current.droppedSynthesisJobs + 1)
                    }
                    Log.w(TAG, "Dropped synthesis job due to backpressure")
                }
            }

            // Immediately go back to listening
            updateState(PipelineState.LISTENING)
        }
    }

    /**
     * Stage 3: Receive text, run TTS with voice cloning, play audio.
     * This runs concurrently with ASR — while TTS synthesizes,
     * ASR can already be processing the next utterance (double buffering).
     */
    private suspend fun ttsStage(targetLanguage: String) {
        for (job in synthesisChannel) {
            updateState(PipelineState.SYNTHESIZING)
            _events.emit(PipelineEvent.TtsStarted(job.text))

            val embedding = speakerEmbedding ?: FloatArray(TtsEngine.SPEAKER_EMBEDDING_DIM) { 0f }

            val t0 = System.nanoTime()
            val result = ttsEngine.synthesize(
                text = job.text,
                language = targetLanguage,
                speakerEmbedding = embedding
            )
            val inferenceMs = (System.nanoTime() - t0) / 1_000_000

            // Stage 4: Play audio (on IO dispatcher)
            updateState(PipelineState.PLAYING)
            val playbackStart = System.nanoTime()
            withContext(Dispatchers.IO) {
                audioPlayer.play(result.audioData, result.sampleRate)
            }
            val playbackMs = (System.nanoTime() - playbackStart) / 1_000_000

            val rtf = if (result.durationMs > 0) inferenceMs.toFloat() / result.durationMs else 0f
            recordTtsMetrics(inferenceMs, playbackMs, rtf)
            _events.emit(PipelineEvent.TtsComplete(result.durationMs, inferenceMs, playbackMs, rtf))

            Log.i(
                TAG,
                "TTS: ${result.durationMs}ms audio in ${inferenceMs}ms, playback=${playbackMs}ms (RTF=${"%.2f".format(rtf)})"
            )
        }
    }

    private fun recordUtteranceMetrics(utteranceDurationMs: Long) {
        _telemetry.update { current ->
            val nextCount = current.utteranceCount + 1
            current.copy(
                utteranceCount = nextCount,
                lastUtteranceMs = utteranceDurationMs,
                avgUtteranceMs = nextAverage(current.avgUtteranceMs, current.utteranceCount, utteranceDurationMs)
            )
        }
    }

    private fun recordAsrMetrics(latencyMs: Long) {
        _telemetry.update { current ->
            val nextCount = current.asrCount + 1
            current.copy(
                asrCount = nextCount,
                lastAsrMs = latencyMs,
                avgAsrMs = nextAverage(current.avgAsrMs, current.asrCount, latencyMs)
            )
        }
    }

    private fun recordTtsMetrics(inferenceMs: Long, playbackMs: Long, rtf: Float) {
        _telemetry.update { current ->
            val nextCount = current.ttsCount + 1
            current.copy(
                ttsCount = nextCount,
                lastTtsMs = inferenceMs,
                avgTtsMs = nextAverage(current.avgTtsMs, current.ttsCount, inferenceMs),
                lastPlaybackMs = playbackMs,
                avgPlaybackMs = nextAverage(current.avgPlaybackMs, current.ttsCount, playbackMs),
                lastRtf = rtf,
                avgRtf = nextAverage(current.avgRtf, current.ttsCount, rtf)
            )
        }
    }

    private fun nextAverage(currentAverage: Long, currentCount: Long, nextValue: Long): Long {
        val total = currentAverage * currentCount + nextValue
        return total / (currentCount + 1)
    }

    private fun nextAverage(currentAverage: Float, currentCount: Long, nextValue: Float): Float {
        val total = currentAverage * currentCount + nextValue
        return total / (currentCount + 1)
    }

    private suspend fun updateState(newState: PipelineState) {
        _state.value = newState
        _events.emit(PipelineEvent.StateChange(newState))
    }

    /**
     * Stop the pipeline.
     */
    fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
        audioRecorder.stopRecording()
        audioPlayer.release()
        _state.value = PipelineState.IDLE
        _amplitude.value = 0f
        _telemetry.value = PipelineTelemetry()
        Log.i(TAG, "Pipeline stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        utteranceChannel.close()
        synthesisChannel.close()
        asrEngine.release()
        ttsEngine.release()
    }
}
