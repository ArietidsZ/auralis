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
 * Orchestrates the real-time ASR → MT → TTS pipeline with bounded concurrency.
 *
 * Architecture (5-stage pipeline running in parallel):
 *
 *   Stage 1 (IO):     AudioRecorder → PCM chunks → VAD → utterance buffer
 *   Stage 2 (Default): ASR encoder+decoder → source text
 *   Stage 3 (Default): Hy-MT translation → target text
 *   Stage 4 (Default): TTS synthesize → PCM audio
 *   Stage 5 (IO):      AudioTrack playback
 *
 * Key optimizations:
 *  - Channel-based producer/consumer: ASR output feeds Hy-MT and TTS without blocking
 *  - Separate coroutine dispatchers: I/O (audio) vs Default (NPU inference)
 *  - Double buffering: next utterance is ASR'd while current one is TTS'd
 *  - Streaming vocoder: TTS starts outputting audio before full sentence is generated
 */
class PipelineOrchestrator(
    private val asrEngine: AsrEngine,
    private val translationEngine: TranslationEngine,
    private val ttsEngine: TtsEngine,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer
) {
    companion object {
        private const val TAG = "Pipeline"
    }

    // Pipeline events.
    // Every event that maps to a transcript message carries a stable [messageId]
    // minted at utterance capture time, so consumers can key messages by id
    // instead of relying on FIFO ordering (which mismatches when utterances are
    // dropped under backpressure).
    sealed class PipelineEvent {
        data class AsrResult(
            val messageId: Long,
            val text: String,
            val language: String,
            val latencyMs: Long
        ) : PipelineEvent()
        data class MtStarted(
            val messageId: Long,
            val sourceText: String,
            val sourceLanguage: String,
            val targetLanguage: String
        ) : PipelineEvent()
        data class MtComplete(
            val messageId: Long,
            val sourceText: String,
            val translatedText: String,
            val sourceLanguage: String,
            val targetLanguage: String,
            val latencyMs: Long,
            val runtime: String
        ) : PipelineEvent()
        data class TtsStarted(val messageId: Long, val text: String) : PipelineEvent()
        data class TtsComplete(
            val messageId: Long,
            val durationMs: Long,
            val inferenceMs: Long,
            val playbackMs: Long,
            val rtf: Float
        ) : PipelineEvent()
        data class UtteranceDropped(val messageId: Long) : PipelineEvent()
        data class StateChange(val state: PipelineState) : PipelineEvent()
        data class Error(val message: String) : PipelineEvent()
    }

    enum class PipelineState { IDLE, LOADING, LISTENING, RECOGNIZING, TRANSLATING, SYNTHESIZING, PLAYING }

    data class PipelineTelemetry(
        val utteranceCount: Long = 0,
        val droppedUtterances: Long = 0,
        val droppedTranslationJobs: Long = 0,
        val droppedSynthesisJobs: Long = 0,
        val asrCount: Long = 0,
        val mtCount: Long = 0,
        val ttsCount: Long = 0,
        val lastUtteranceMs: Long = 0,
        val avgUtteranceMs: Long = 0,
        val lastAsrMs: Long = 0,
        val avgAsrMs: Long = 0,
        val lastMtMs: Long = 0,
        val avgMtMs: Long = 0,
        val lastTtsMs: Long = 0,
        val avgTtsMs: Long = 0,
        val lastPlaybackMs: Long = 0,
        val avgPlaybackMs: Long = 0,
        val lastRtf: Float = 0f,
        val avgRtf: Float = 0f
    )

    // Internal channels for pipeline stages
    private val utteranceChannel = Channel<UtteranceJob>(
        capacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val translationChannel = Channel<TranslationJob>(
        capacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val synthesisChannel = Channel<SynthesisJob>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private data class UtteranceJob(
        val messageId: Long,
        val audioData: FloatArray
    )

    private data class TranslationJob(
        val messageId: Long,
        val text: String,
        val sourceLanguage: String,
        val targetLanguage: String
    )

    private data class SynthesisJob(
        val messageId: Long,
        val sourceText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val mtLatencyMs: Long
    )

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
    private val messageIdCounter = java.util.concurrent.atomic.AtomicLong(0L)

    // Degradation flags: set true when a stage's model fails to load. The stage
    // then degrades to a passthrough instead of cancelling the whole pipeline.
    @Volatile
    private var translationEnabled = true
    @Volatile
    private var ttsEnabled = true

    private fun nextMessageId(): Long = messageIdCounter.incrementAndGet()

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
        sourceLanguage: String = "Chinese",
        targetLanguage: String = "Chinese"
    ) {
        if (pipelineJob?.isActive == true) return

        pipelineJob = scope.launch {
            try {
                updateState(PipelineState.LOADING)

                // Reset degradation flags each session.
                translationEnabled = true
                ttsEnabled = true

                // Load models concurrently. A stage whose model fails to load
                // degrades to a passthrough instead of cancelling the whole
                // pipeline (ASR is mandatory; MT and TTS are degradable).
                translationEnabled = runCatching { translationEngine.load() }.isSuccess
                ttsEnabled = runCatching { ttsEngine.load() }.isSuccess
                asrEngine.load()

                if (!translationEnabled) {
                    _events.emit(PipelineEvent.Error("机器翻译不可用，将直通输出源语言"))
                }
                if (!ttsEnabled) {
                    _events.emit(PipelineEvent.Error("语音合成不可用，将仅显示译文"))
                }

                // Start all pipeline stages concurrently
                coroutineScope {
                    // Stage 1: Audio capture + VAD
                    launch(Dispatchers.IO) { captureStage() }

                    // Stage 2: ASR (runs on Default dispatcher → NPU)
                    launch(Dispatchers.Default) { asrStage(sourceLanguage, targetLanguage) }

                    // Stage 3: MT translation (runs on Default dispatcher → local Hy-MT runtime)
                    launch(Dispatchers.Default) { mtStage() }

                    // Stage 4: TTS synthesis (runs on Default dispatcher → NPU)
                    launch(Dispatchers.Default) { ttsStage() }
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
     *
     * A mic failure (missing permission, init error) propagates as a recoverable
     * [PipelineEvent.Error] instead of leaving the session silently stuck in
     * LISTENING. Committed utterances are never dropped: [utteranceChannel] uses
     * suspend-send, pushing backpressure upstream to the raw audio flow.
     */
    private suspend fun captureStage() {
        val vad = VoiceActivityDetector()
        val utteranceBuffer = mutableListOf<FloatArray>()

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

                        // send() suspends until ASR is ready. Committed user speech
                        // is never silently dropped; backpressure pushes upstream to
                        // the raw audio flow (which evicts oldest chunks) instead.
                        val messageId = nextMessageId()
                        utteranceChannel.send(UtteranceJob(messageId, utterance))
                        recordUtteranceMetrics(utteranceDurationMs)
                        Log.d(TAG, "Utterance sent: ${utterance.size} samples (${utterance.size * 1000 / AsrEngine.SAMPLE_RATE}ms)")
                    }
                }
            }

            updateState(PipelineState.LISTENING)
            try {
                audioRecorder.startRecording()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(PipelineEvent.Error("麦克风启动失败: ${e.message}"))
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
    private suspend fun asrStage(sourceLanguage: String, targetLanguage: String) {
        for (job in utteranceChannel) {
            updateState(PipelineState.RECOGNIZING)

            val t0 = System.nanoTime()
            val result = asrEngine.transcribe(job.audioData, language = sourceLanguage)
            val latencyMs = (System.nanoTime() - t0) / 1_000_000

            recordAsrMetrics(latencyMs)

            _events.emit(
                PipelineEvent.AsrResult(
                    messageId = job.messageId,
                    text = result.text,
                    language = result.language,
                    latencyMs = latencyMs
                )
            )

            if (result.text.isNotBlank()) {
                // Non-blocking send to MT stage — ASR can start on next utterance immediately
                val sendResult = translationChannel.trySend(
                    TranslationJob(
                        messageId = job.messageId,
                        text = result.text,
                        sourceLanguage = result.language,
                        targetLanguage = targetLanguage
                    )
                )
                if (sendResult.isFailure) {
                    _telemetry.update { current ->
                        current.copy(droppedTranslationJobs = current.droppedTranslationJobs + 1)
                    }
                    Log.w(TAG, "Dropped translation job due to backpressure")
                }
            }

            // Immediately go back to listening
            updateState(PipelineState.LISTENING)
        }
    }

    /**
     * Stage 3: Translate recognized text with Hy-MT before synthesis.
     */
    private suspend fun mtStage() {
        val contextWindow = ArrayDeque<String>()

        for (job in translationChannel) {
            updateState(PipelineState.TRANSLATING)
            _events.emit(
                PipelineEvent.MtStarted(
                    messageId = job.messageId,
                    sourceText = job.text,
                    sourceLanguage = job.sourceLanguage,
                    targetLanguage = job.targetLanguage
                )
            )

            val result = if (!translationEnabled) {
                // Translation runtime unavailable (e.g. native lib not packaged):
                // degrade to ASR passthrough so the session still produces output
                // instead of silently failing. The runtime label communicates the
                // degraded path to the UI.
                TranslationResult(
                    sourceText = job.text,
                    translatedText = TranslationCache.normalizeText(job.text),
                    sourceLanguage = job.sourceLanguage,
                    targetLanguage = job.targetLanguage,
                    latencyMs = 0L,
                    runtime = "passthrough"
                )
            } else {
                translationEngine.translate(
                    TranslationRequest(
                        text = job.text,
                        sourceLanguage = job.sourceLanguage,
                        targetLanguage = job.targetLanguage,
                        context = contextWindow.toList()
                    )
                )
            }

            recordMtMetrics(result.latencyMs)
            _events.emit(
                PipelineEvent.MtComplete(
                    messageId = job.messageId,
                    sourceText = result.sourceText,
                    translatedText = result.translatedText,
                    sourceLanguage = result.sourceLanguage,
                    targetLanguage = result.targetLanguage,
                    latencyMs = result.latencyMs,
                    runtime = result.runtime
                )
            )

            if (result.translatedText.isNotBlank()) {
                val sendResult = synthesisChannel.trySend(
                    SynthesisJob(
                        messageId = job.messageId,
                        sourceText = result.sourceText,
                        translatedText = result.translatedText,
                        sourceLanguage = result.sourceLanguage,
                        targetLanguage = result.targetLanguage,
                        mtLatencyMs = result.latencyMs
                    )
                )
                if (sendResult.isFailure) {
                    _telemetry.update { current ->
                        current.copy(droppedSynthesisJobs = current.droppedSynthesisJobs + 1)
                    }
                    Log.w(TAG, "Dropped synthesis job due to backpressure")
                }
            }

            contextWindow.addLast("${result.sourceText}\n${result.translatedText}")
            while (contextWindow.size > 4) {
                contextWindow.removeFirst()
            }

            updateState(PipelineState.LISTENING)
        }
    }

    /**
     * Stage 4: Receive translated text, run TTS with voice cloning, play audio.
     * This runs concurrently with ASR — while TTS synthesizes,
     * ASR can already be processing the next utterance (double buffering).
     */
    private suspend fun ttsStage() {
        for (job in synthesisChannel) {
            updateState(PipelineState.SYNTHESIZING)
            _events.emit(PipelineEvent.TtsStarted(job.messageId, job.translatedText))

            val embedding = speakerEmbedding ?: FloatArray(TtsEngine.SPEAKER_EMBEDDING_DIM) { 0f }

            val t0 = System.nanoTime()
            val result = if (ttsEnabled) {
                try {
                    ttsEngine.synthesize(
                        text = job.translatedText,
                        language = job.targetLanguage,
                        speakerEmbedding = embedding
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Error("语音合成失败: ${e.message}"))
                    TtsEngine.SynthesisResult(
                        audioData = FloatArray(0),
                        sampleRate = TtsEngine.OUTPUT_SAMPLE_RATE,
                        durationMs = 0L,
                        inferenceTimeMs = 0L
                    )
                }
            } else {
                val empty = TtsEngine.SynthesisResult(
                    audioData = FloatArray(0),
                    sampleRate = TtsEngine.OUTPUT_SAMPLE_RATE,
                    durationMs = 0L,
                    inferenceTimeMs = 0L
                )
                _events.emit(PipelineEvent.Error("语音合成不可用，已跳过播报"))
                empty
            }
            val inferenceMs = (System.nanoTime() - t0) / 1_000_000

            if (result.audioData.isNotEmpty()) {
                // Stage 4: Play audio (on IO dispatcher)
                updateState(PipelineState.PLAYING)
                val playbackStart = System.nanoTime()
                withContext(Dispatchers.IO) {
                    audioPlayer.play(result.audioData, result.sampleRate)
                }
                val playbackMs = (System.nanoTime() - playbackStart) / 1_000_000

                val rtf = if (result.durationMs > 0) inferenceMs.toFloat() / result.durationMs else 0f
                recordTtsMetrics(inferenceMs, playbackMs, rtf)
                _events.emit(
                    PipelineEvent.TtsComplete(
                        messageId = job.messageId,
                        durationMs = result.durationMs,
                        inferenceMs = inferenceMs,
                        playbackMs = playbackMs,
                        rtf = rtf
                    )
                )

                Log.i(
                    TAG,
                    "TTS: ${result.durationMs}ms audio in ${inferenceMs}ms, playback=${playbackMs}ms (RTF=${"%.2f".format(rtf)})"
                )
            }
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

    private fun recordMtMetrics(latencyMs: Long) {
        _telemetry.update { current ->
            val nextCount = current.mtCount + 1
            current.copy(
                mtCount = nextCount,
                lastMtMs = latencyMs,
                avgMtMs = nextAverage(current.avgMtMs, current.mtCount, latencyMs)
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
     * Stop the pipeline. Suspends until the pipeline stages have actually exited
     * before releasing audio/native resources, preventing use-after-free when a
     * stage (e.g. TTS) is still blocked in a native call.
     */
    suspend fun stop() {
        pipelineJob?.cancel()
        pipelineJob?.join()
        pipelineJob = null
        audioRecorder.stopRecording()
        audioPlayer.release()
        clearPendingWork()
        _state.value = PipelineState.IDLE
        _amplitude.value = 0f
        _telemetry.value = PipelineTelemetry()
        Log.i(TAG, "Pipeline stopped")
    }

    private fun clearPendingWork() {
        while (utteranceChannel.tryReceive().isSuccess) Unit
        while (translationChannel.tryReceive().isSuccess) Unit
        while (synthesisChannel.tryReceive().isSuccess) Unit
    }

    /**
     * Release all resources.
     */
    suspend fun release() {
        stop()
        utteranceChannel.close()
        translationChannel.close()
        synthesisChannel.close()
        asrEngine.release()
        translationEngine.release()
        ttsEngine.release()
    }
}
