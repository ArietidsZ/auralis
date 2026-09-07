package com.dialect.interpreter.inference

import com.dialect.interpreter.audio.AudioCapture
import com.dialect.interpreter.audio.AudioPlayback
import com.dialect.interpreter.audio.UtteranceSegmenter
import com.dialect.interpreter.session.SessionConfig
import com.dialect.interpreter.session.SessionController
import com.dialect.interpreter.session.SessionPhase
import com.dialect.interpreter.session.SessionProblem
import com.dialect.interpreter.session.SessionSnapshot
import com.dialect.interpreter.session.SessionStartException
import com.dialect.interpreter.session.TranscriptTurn
import com.dialect.interpreter.session.TurnStatus
import com.dialect.interpreter.session.VoiceProfileResolver
import com.dialect.interpreter.session.WorkStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tunable runtime bounds. Defaults follow spec R03 (two queued utterances,
 * snapshot history cap) and are overridable for tests.
 */
data class RuntimeLimits(
    val queueCapacity: Int = 2,
    val maxSnapshotTurns: Int = 200,
    val captureStartTimeoutMs: Long = 10_000
)

/**
 * One start→stop cycle is one session: it owns engine handles and audio
 * resources (spec R01). Implements [SessionController] directly — no delegate
 * layer (spec R02).
 *
 * Guarantees:
 *  - Session id increments per start; turn ids are minted at capture and
 *    carried through ASR/MT/TTS/playback. All state funnels through one atomic
 *    reducer ([snapshot]); a turn reaches exactly one terminal state
 *    (COMPLETE / FAILED / DROPPED / CANCELLED).
 *  - Bounded, drop-new utterance queue: when the queue is full the newest
 *    utterance gets a visible DROPPED turn and the counter increments — never
 *    silent DROP_OLDEST.
 *  - Default one serial compute worker per turn (ASR→MT→TTS→playback);
 *    capture runs independently. Speaker playback is half-duplex: while audio
 *    is playing the capture segmenter is held in reset, so synthesized sound is
 *    never re-recognized.
 *  - Stop order (spec R01): STOPPING → unblock capture/playback → request
 *    native cancel + cancel subtasks → join in-flight calls → drain queue and
 *    release engines → IDLE. Close is idempotent and runs on the owned scope.
 *  - No fake success: missing MT keeps the source text ("翻译不可用"); missing
 *    TTS keeps the real translation ("未播放"); empty synthesis or playback
 *    shortfalls are failures, not completions.
 */
class PipelineOrchestrator(
    private val recognizer: SpeechRecognizer,
    private val translator: TranslationEngine,
    private val synthesizer: SpeechSynthesizer,
    private val capture: AudioCapture,
    private val playback: AudioPlayback,
    private val voiceProfiles: VoiceProfileResolver,
    private val limits: RuntimeLimits = RuntimeLimits(),
    private val acquireModelLease: () -> AutoCloseable = { AutoCloseable {} }
) : SessionController {

    private data class UtteranceJob(
        val sessionId: Long,
        val turnId: Long,
        val audio: FloatArray
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    private val sessionCounter = AtomicLong(0)
    private val turnCounter = AtomicLong(0)

    private val snapshotFlow = MutableStateFlow(SessionSnapshot())
    override val snapshot: StateFlow<SessionSnapshot> = snapshotFlow
    override val amplitude: StateFlow<Float> get() = capture.amplitude

    private var utteranceQueue = newQueue()
    private val segmenter = UtteranceSegmenter()

    @Volatile
    private var currentSessionId: Long = 0

    @Volatile
    private var activeConfig: SessionConfig? = null

    @Volatile
    private var activeSpeakerEmbedding: FloatArray? = null

    @Volatile
    private var mtAvailable = false

    @Volatile
    private var ttsAvailable = false

    private var captureJob: Job? = null
    private var workerJob: Job? = null

    /**
     * Shared-model access lease held from before the first engine load until
     * every engine has been released (all teardown paths). While held, the
     * repository refuses model package replacement; closed and cleared exactly
     * once per completed session.
     */
    @Volatile
    private var modelLease: AutoCloseable? = null

    // ---- SessionController ----

    override suspend fun start(config: SessionConfig) {
        lifecycleMutex.withLock {
            if (closed.get()) {
                throw SessionStartException(
                    SessionProblem(
                        code = "controller_closed", stage = null,
                        message = "会话控制器已关闭，不能再次开始", recoverable = false
                    )
                )
            }
            val phase = snapshotFlow.value.phase
            if (phase == SessionPhase.STARTING || phase == SessionPhase.ACTIVE ||
                phase == SessionPhase.STOPPING
            ) {
                // Precondition failure: the session may actually be running —
                // never corrupt its state with a FAILED phase.
                throw SessionStartException(
                    SessionProblem(
                        code = "already_started", stage = null,
                        message = "会话已在运行（phase=$phase）", recoverable = false
                    )
                )
            }

            val sessionId = sessionCounter.incrementAndGet()
            currentSessionId = sessionId
            activeConfig = config
            activeSpeakerEmbedding = null
            mtAvailable = false
            ttsAvailable = false
            stopRequested.set(false)

            segmenter.reset()
            recycleQueue()
            snapshotFlow.value = SessionSnapshot(
                sessionId = sessionId,
                phase = SessionPhase.STARTING
            )

            val ready = CompletableDeferred<Unit>()
            try {
                // Stop/close requested while loading must abort the start at
                // the next checkpoint instead of running to full completion
                // behind the mutex (a single native load itself is not
                // interruptible; the gap is bounded to one engine load).
                fun ensureNotStopped() {
                    if (stopRequested.get()) {
                        throw CancellationException("session start aborted by stop request")
                    }
                }

                // Hold the model lease before ANY engine load so native model
                // packages cannot be swapped under a live session.
                modelLease?.close()
                modelLease = acquireModelLease()

                // 1. Mandatory capability: ASR. Failure blocks the session.
                recognizer.load()
                ensureNotStopped()

                // 2. Degradable capabilities. MT/TTS loss never fabricates
                //    output; the session degrades to transcript/text-only.
                mtAvailable = try {
                    translator.load()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                ensureNotStopped()

                if (config.voiceProfileId != null) {
                    activeSpeakerEmbedding = voiceProfiles.resolve(config.voiceProfileId)
                }
                ttsAvailable = if (activeSpeakerEmbedding == null) {
                    false // No valid voice reference → no synthesis this session.
                } else {
                    try {
                        synthesizer.load()
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
                ensureNotStopped()

                announceDegradations(config)

                // 3. Capture + compute worker. Capture readiness is awaited so
                //    "ACTIVE" means the microphone is really live.
                captureJob = scope.launch {
                    runCaptureLoop(sessionId, ready)
                }
                workerJob = scope.launch { runWorker(sessionId) }

                try {
                    withTimeout(limits.captureStartTimeoutMs) { ready.await() }
                } catch (e: TimeoutCancellationException) {
                    throw IllegalStateException("麦克风启动超时", e)
                }
                if (captureJob?.isActive != true) {
                    // The capture loop died between readiness and ACTIVE.
                    throw IllegalStateException("录音输入意外结束")
                }
            } catch (e: CancellationException) {
                // Aborted start (stop/close/caller cancellation) is not a failed
                // session: tear down and leave a clean, restartable state.
                teardownQuietly()
                reduce { it.copy(phase = SessionPhase.IDLE, problem = null) }
                throw e
            } catch (e: Exception) {
                markFailed(
                    SessionProblem(
                        code = "start_failed", stage = null,
                        message = e.message ?: "会话开始失败", recoverable = true
                    )
                )
                teardownQuietly()
                throw SessionStartException(
                    SessionProblem(
                        code = "start_failed", stage = null,
                        message = e.message ?: "会话开始失败", recoverable = true
                    )
                )
            }

            setCaptureActive(true)
            reduce { it.copy(phase = SessionPhase.ACTIVE) }
        }
    }

    override suspend fun stop() {
        // Flag first: an in-flight start() observes it at its next checkpoint
        // instead of completing behind the mutex (stop-during-start must be
        // bounded, not "wait for the whole start").
        stopRequested.set(true)
        withContext(NonCancellable) {
            lifecycleMutex.withLock { stopInternal() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // An in-flight start() must observe the close request at its next
        // checkpoint instead of running to completion behind the mutex.
        stopRequested.set(true)
        scope.launch {
            try {
                lifecycleMutex.withLock { stopInternal() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // close() must never throw; the scope is torn down in finally.
            } finally {
                scope.cancel()
            }
        }
    }

    // ---- Lifecycle internals ----

    /**
     * Join only the live subtasks and clear the handles. Never substitutes a
     * fresh active [Job] for a null one — that would block forever (and
     * NonCancellable teardown would make the hang unrescuable).
     */
    private suspend fun joinLiveJobs() {
        val worker = workerJob
        val capture = captureJob
        workerJob = null
        captureJob = null
        if (worker != null && capture != null && worker !== capture) {
            joinAll(worker, capture)
        } else {
            worker?.join()
            capture?.join()
        }
    }

    /**
     * Full stop sequence. Callers must hold [lifecycleMutex]. Idempotent: a
     * no-op for IDLE; FAILED start leftovers are released and phase reset.
     */
    private suspend fun stopInternal() {
        val phase = snapshotFlow.value.phase
        if (phase == SessionPhase.IDLE) return

        stopRequested.set(true)
        reduce { it.copy(phase = SessionPhase.STOPPING) }

        // 1. Unblock capture and playback first (stop-before-join order).
        capture.stop()
        playback.stop()

        // 2. Request native cancellation and cancel subtasks.
        translator.cancel()
        workerJob?.cancel()
        captureJob?.cancel()

        // 3. Wait for in-flight native calls to return.
        joinLiveJobs()

        // 4. Drain queues, close engine handles, release the model lease last.
        drainQueueAsCancelled()
        releaseEnginesQuietly()
        closeModelLease()

        segmenter.reset()
        setCaptureActive(false)

        // 5. Stopped.
        reduce { it.copy(phase = SessionPhase.IDLE) }
    }

    private fun closeModelLease() {
        val lease = modelLease
        modelLease = null
        runCatching { lease?.close() }
    }

    private suspend fun teardownQuietly() {
        try {
            withContext(NonCancellable) {
                capture.stop()
                playback.stop()
                translator.cancel()
                workerJob?.cancel()
                captureJob?.cancel()
                joinLiveJobs()
                drainQueueAsCancelled()
                releaseEnginesQuietly()
                closeModelLease()

                setCaptureActive(false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Teardown is best-effort during a failed start; the FAILED phase
            // plus problem already surfaces the primary failure.
        }
    }

    private suspend fun releaseEnginesQuietly() = withContext(Dispatchers.IO) {
        runCatching { recognizer.release() }
        runCatching { translator.release() }
        runCatching { synthesizer.release() }
    }

    private fun recycleQueue() {
        utteranceQueue.close()
        utteranceQueue = newQueue()
    }

    private fun newQueue(): Channel<UtteranceJob> =
        Channel(capacity = limits.queueCapacity, onBufferOverflow = BufferOverflow.SUSPEND)

    // ---- Capture ----

    private suspend fun runCaptureLoop(sessionId: Long, ready: CompletableDeferred<Unit>) {
        try {
            capture.start(
                onChunk = { chunk -> onChunk(sessionId, chunk) },
                onReady = { ready.complete(Unit) }
            )
        } catch (e: CancellationException) {
            if (!ready.isCompleted) ready.completeExceptionally(e)
            throw e
        } catch (e: Throwable) {
            // Expected capture failure (permission, init, read error, route
            // loss): never rethrow into the supervisor scope — an unhandled
            // exception there would crash the app. Convert to session state.
            if (!ready.isCompleted) {
                // Start phase: start() observes this and runs the failure path.
                ready.completeExceptionally(e)
            } else {
                // Active phase: capture died mid-session → recoverable FAILED
                // state plus safe teardown (engines, worker, queues).
                teardownFromCaptureFailure(e)
            }
            return
        } finally {
            if (sessionId == currentSessionId) {
                setCaptureActive(false)
            }
        }
    }

    /**
     * Capture died while the session was ACTIVE: mark FAILED with a recoverable
     * problem, cancel the worker, drain and release engines. The capture
     * coroutine itself is the caller — it must not join itself.
     */
    private suspend fun teardownFromCaptureFailure(e: Throwable) {
        lifecycleMutex.withLock {
            val phase = snapshotFlow.value.phase
            if (phase == SessionPhase.IDLE || phase == SessionPhase.STOPPING ||
                phase == SessionPhase.FAILED
            ) {
                return@withLock
            }
            val problem = SessionProblem(
                code = "capture_failed",
                stage = WorkStage.CAPTURE,
                message = e.message ?: "录音输入中断，请检查权限与音频设备",
                recoverable = true
            )
            reduce { it.copy(phase = SessionPhase.STOPPING, problem = problem) }
            stopRequested.set(true)
            playback.stop()
            translator.cancel()
            val worker = workerJob
            workerJob = null
            worker?.cancel()
            worker?.join()
            drainQueueAsCancelled()
            releaseEnginesQuietly()
            closeModelLease()

            segmenter.reset()
            setCaptureActive(false)
            markFailed(problem)
        }
    }

    /**
     * Capture callback: VAD segmentation only. Never blocks on model work;
     * committed utterances are offered to the bounded queue with explicit
     * drop-new semantics.
     */
    private fun onChunk(sessionId: Long, chunk: FloatArray) {
        if (playback.isPlaying.value) {
            // Half-duplex: synthesized audio is being played — drop mic input
            // and keep the segmenter clean so echo never merges into a turn.
            segmenter.reset()
            return
        }
        if (sessionId != currentSessionId) return
        if (stopRequested.get()) return

        val utterance = segmenter.process(chunk) ?: return

        val turnId = turnCounter.incrementAndGet()
        mintTurn(TranscriptTurn(sessionId = sessionId, id = turnId))

        val sent = utteranceQueue.trySend(
            UtteranceJob(sessionId = sessionId, turnId = turnId, audio = utterance.audio)
        )
        if (!sent.isSuccess) {
            updateTurn(sessionId, turnId) {
                it.copy(
                    status = TurnStatus.DROPPED,
                    problem = SessionProblem(
                        code = "capture_queue_full",
                        stage = WorkStage.CAPTURE,
                        message = "处理饱和，本条语句被丢弃",
                        recoverable = true
                    )
                )
            }
            reduce { it.copy(droppedTurns = it.droppedTurns + 1) }
        }
    }

    // ---- Compute worker (one serial ASR → MT → TTS → PLAYBACK per turn) ----

    private suspend fun runWorker(sessionId: Long) {
        for (job in utteranceQueue) {
            if (job.sessionId != sessionId) continue
            try {
                processTurn(job)
            } catch (e: CancellationException) {
                markCancelled(job)
                throw e
            } catch (e: Throwable) {
                // Safety net: an unexpected engine crash must neither kill the
                // app nor cancel other turns (spec R03). The turn fails with a
                // reason; the worker keeps serving the queue.
                markFailedTurn(job, "worker_unexpected", activeStageOrAsr(), e.message ?: "推理管线异常")
            }
        }
    }

    private fun activeStageOrAsr(): WorkStage =
        snapshotFlow.value.activeStages
            .firstOrNull { it in modelStages }
            ?: WorkStage.ASR

    private suspend fun processTurn(job: UtteranceJob) {
        val config = activeConfig ?: return

        setWorkerStage(WorkStage.ASR)
        try {
            updateTurn(job.sessionId, job.turnId) { it.copy(status = TurnStatus.RECOGNIZING) }

            val asrResult = try {
                // Paired Qwen tests show forced language prefixes hurt Chinese
                // and Cantonese here. Source selection still informs MT below.
                recognizer.transcribe(job.audio, language = "auto")
            } catch (e: CancellationException) {
                markCancelled(job); throw e
            } catch (e: Exception) {
                markFailedTurn(job, "asr_failed", WorkStage.ASR, e.message ?: "识别失败")
                return
            }

            if (asrResult.text.isBlank()) {
                markFailedTurn(job, "asr_empty", WorkStage.ASR, "未能识别到语音内容")
                return
            }
            updateTurn(job.sessionId, job.turnId) { it.copy(sourceText = asrResult.text) }

            if (!mtAvailable) {
                // Missing MT: keep the real transcript, mark translation
                // unavailable, never emit a translation, never synthesize the
                // source text as if it were the target language.
                markCompleteTurn(
                    job,
                    SessionProblem(
                        code = "mt_unavailable", stage = WorkStage.MT,
                        message = "机器翻译不可用，仅保留原文", recoverable = true
                    )
                )
                return
            }

            setWorkerStage(WorkStage.MT)
            updateTurn(job.sessionId, job.turnId) { it.copy(status = TurnStatus.TRANSLATING) }

            val mtResult = try {
                translator.translate(
                    TranslationRequest(
                        text = asrResult.text,
                        sourceLanguage = config.sourceLanguage,
                        targetLanguage = config.targetLanguage
                    )
                )
            } catch (e: CancellationException) {
                markCancelled(job); throw e
            } catch (e: Exception) {
                markFailedTurn(job, "mt_failed", WorkStage.MT, e.message ?: "翻译失败")
                return
            }

            if (mtResult.translatedText.isBlank()) {
                markFailedTurn(job, "mt_failed", WorkStage.MT, "翻译结果为空")
                return
            }
            updateTurn(job.sessionId, job.turnId) {
                it.copy(translatedText = mtResult.translatedText)
            }

            val embedding = activeSpeakerEmbedding
            if (!ttsAvailable || embedding == null) {
                if (config.voiceProfileId == null) {
                    // Normal text-only mode: no voice profile selected, so no
                    // synthesis was expected — not a problem, not an error.
                    markCompleteTurn(job, problem = null)
                    return
                }
                // A profile was requested but is unusable or the model failed:
                // keep the real translation, state clearly nothing was played.
                markCompleteTurn(
                    job,
                    SessionProblem(
                        code = if (embedding == null) "voice_profile_missing" else "tts_unavailable",
                        stage = WorkStage.TTS,
                        message = if (embedding == null) "声音档案无效，语音输出不可用"
                        else "语音合成不可用，仅显示译文",
                        recoverable = true
                    )
                )
                return
            }

            setWorkerStage(WorkStage.TTS)
            updateTurn(job.sessionId, job.turnId) { it.copy(status = TurnStatus.SYNTHESIZING) }

            var generated: TtsEngine.SynthesisResult? = null
            var emittedSamples = 0L
            var hasSignal = false
            try {
                if (synthesizer.outputSampleRate !in 8000..192000) {
                    throw SynthesisDeliveryFailure(IllegalArgumentException("Invalid synthesis sample rate"))
                }
                playback.playStream(synthesizer.outputSampleRate) { emit ->
                    try {
                        val result = synthesizer.synthesizeStream(
                            mtResult.translatedText, config.targetLanguage, embedding) { chunk ->
                            currentCoroutineContext().ensureActive()
                            require(chunk.isNotEmpty() && chunk.all { it.isFinite() }) { "Invalid synthesis PCM chunk" }
                            setWorkerStages(setOf(WorkStage.TTS, WorkStage.PLAYBACK))
                            updateTurn(job.sessionId, job.turnId) { it.copy(status = TurnStatus.PLAYING) }
                            try { emit(chunk) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { throw PlaybackDeliveryFailure(error) }
                            emittedSamples += chunk.size
                            hasSignal = hasSignal || chunk.any { it != 0f }
                        }
                        require(emittedSamples > 0 && hasSignal && result.sampleRate == synthesizer.outputSampleRate &&
                            emittedSamples == result.audioData.size.toLong()) { "Synthesis returned incomplete PCM" }
                        currentCoroutineContext().ensureActive()
                        generated = result
                        setWorkerStage(WorkStage.PLAYBACK)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: PlaybackDeliveryFailure) { throw error }
                    catch (error: Exception) { throw SynthesisDeliveryFailure(error) }
                }
                currentCoroutineContext().ensureActive()
                if (stopRequested.get()) { markCancelled(job); return }
                check(generated != null) { "Playback returned before synthesis completed" }
                markCompleteTurn(job, problem = null) // generator EOS and native tail both finished
            } catch (cancelled: CancellationException) {
                markCancelled(job); throw cancelled
            } catch (error: SynthesisDeliveryFailure) {
                markFailedTurn(job, "tts_failed", WorkStage.TTS, error.cause?.message ?: "语音合成失败")
            } catch (error: Exception) {
                markFailedTurn(job, "playback_failed", WorkStage.PLAYBACK, error.cause?.message ?: error.message ?: "播放失败")
            }
        } finally {
            setWorkerStage(null)
        }
    }

    // ---- Snapshot reducer (single atomic writer) ----

    private val terminalStatuses =
        setOf(TurnStatus.COMPLETE, TurnStatus.FAILED, TurnStatus.DROPPED, TurnStatus.CANCELLED)

    private val modelStages =
        setOf(WorkStage.ASR, WorkStage.MT, WorkStage.TTS, WorkStage.PLAYBACK)

    private fun reduce(transform: (SessionSnapshot) -> SessionSnapshot) {
        snapshotFlow.update(transform)
    }

    private fun mintTurn(turn: TranscriptTurn) {
        reduce { s ->
            s.copy(turns = (s.turns + turn).takeLast(limits.maxSnapshotTurns))
        }
    }

    private fun updateTurn(
        sessionId: Long,
        turnId: Long,
        transform: (TranscriptTurn) -> TranscriptTurn
    ) {
        reduce { s ->
            s.copy(
                turns = s.turns.map { turn ->
                    if (turn.sessionId == sessionId && turn.id == turnId &&
                        turn.status !in terminalStatuses
                    ) {
                        transform(turn)
                    } else {
                        turn
                    }
                }
            )
        }
    }

    private class SynthesisDeliveryFailure(cause: Exception) : RuntimeException(cause)
    private class PlaybackDeliveryFailure(cause: Exception) : RuntimeException(cause)

    private fun setWorkerStage(stage: WorkStage?) = setWorkerStages(stage?.let { setOf(it) } ?: emptySet())

    private fun setWorkerStages(stages: Set<WorkStage>) {
        reduce { s -> s.copy(activeStages = (s.activeStages - modelStages) + stages) }
    }

    private fun setCaptureActive(active: Boolean) {
        reduce { s ->
            val stages = if (active) s.activeStages + WorkStage.CAPTURE
            else s.activeStages - WorkStage.CAPTURE
            s.copy(captureActive = active, activeStages = stages)
        }
    }

    private fun markFailed(problem: SessionProblem) {
        reduce {
            it.copy(phase = SessionPhase.FAILED, problem = problem)
        }
    }

    private fun markFailedTurn(job: UtteranceJob, code: String, stage: WorkStage, message: String) {
        updateTurn(job.sessionId, job.turnId) {
            it.copy(
                status = TurnStatus.FAILED,
                problem = SessionProblem(code, stage, message, recoverable = true)
            )
        }
    }

    private fun markCancelled(job: UtteranceJob) {
        updateTurn(job.sessionId, job.turnId) {
            it.copy(
                status = TurnStatus.CANCELLED,
                problem = SessionProblem(
                    "turn_cancelled", null, "语句处理已取消", recoverable = true
                )
            )
        }
    }

    private fun markCompleteTurn(job: UtteranceJob, problem: SessionProblem?) {
        updateTurn(job.sessionId, job.turnId) {
            it.copy(status = TurnStatus.COMPLETE, problem = problem)
        }
    }

    private fun drainQueueAsCancelled() {
        while (true) {
            val job = utteranceQueue.tryReceive().getOrNull() ?: break
            if (job.sessionId != currentSessionId) continue
            updateTurn(job.sessionId, job.turnId) {
                it.copy(
                    status = TurnStatus.CANCELLED,
                    problem = SessionProblem(
                        "turn_cancelled", null, "语句处理已取消", recoverable = true
                    )
                )
            }
        }
    }

    private fun announceDegradations(config: SessionConfig) {
        val messages = mutableListOf<String>()
        if (!mtAvailable) messages.add("机器翻译不可用，仅显示原文")
        if (config.voiceProfileId != null) {
            if (activeSpeakerEmbedding == null) {
                messages.add("声音档案无效，语音输出不可用")
            } else if (!ttsAvailable) {
                messages.add("语音合成不可用，仅显示译文")
            }
        }
        // No voice profile selected: text-only is the normal mode, no problem.
        if (messages.isEmpty()) return
        val stage = if (!mtAvailable) WorkStage.MT else WorkStage.TTS
        reduce { s ->
            s.copy(
                problem = SessionProblem(
                    code = if (!mtAvailable) "mt_unavailable" else "tts_unavailable",
                    stage = stage,
                    message = messages.joinToString("；"),
                    recoverable = true
                )
            )
        }
    }
}
