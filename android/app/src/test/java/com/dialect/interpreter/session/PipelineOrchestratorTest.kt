package com.dialect.interpreter.session

import com.dialect.interpreter.audio.AudioCapture
import com.dialect.interpreter.audio.AudioPlayback
import com.dialect.interpreter.audio.StreamPlaybackResult
import com.dialect.interpreter.inference.AsrEngine
import com.dialect.interpreter.inference.PipelineOrchestrator
import com.dialect.interpreter.inference.RuntimeLimits
import com.dialect.interpreter.inference.SpeechRecognizer
import com.dialect.interpreter.inference.SpeechSynthesizer
import com.dialect.interpreter.inference.TtsEngine
import com.dialect.interpreter.inference.TranslationEngine
import com.dialect.interpreter.inference.TranslationRequest
import com.dialect.interpreter.inference.TranslationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle / backpressure / failure tests with fake engine ports (spec V03,
 * V04, V05, V06, V07-half-duplex). No fake inference is used in production.
 */
class PipelineOrchestratorTest {

    private class ProgressiveSynth : SpeechSynthesizer {
        val finish = CompletableDeferred<Unit>()
        val producing = AtomicBoolean(false)
        var failAfterFirst = false
        var failureBeforeOutput: Error? = null
        var releasedDuringProduction = false
        override suspend fun load() = Unit
        override suspend fun synthesize(text: String, language: String, speakerEmbedding: FloatArray?): TtsEngine.SynthesisResult =
            error("stream test must use the incremental port")
        override suspend fun synthesizeStream(text: String, language: String, speakerEmbedding: FloatArray?,
                                              onAudioChunk: suspend (FloatArray) -> Unit): TtsEngine.SynthesisResult {
            producing.set(true)
            try {
                failureBeforeOutput?.let { throw it }
                onAudioChunk(floatArrayOf(0.1f))
                finish.await()
                if (failAfterFirst) error("controlled no EOS after partial PCM")
                onAudioChunk(floatArrayOf(0.2f))
                return TtsEngine.SynthesisResult(floatArrayOf(0.1f, 0.2f), 24000, 1, 1)
            } finally { producing.set(false) }
        }
        override fun release() { releasedDuringProduction = producing.get() }
    }

    private class StreamPlayback : AudioPlayback {
        private val playing = MutableStateFlow(false)
        override val isPlaying: StateFlow<Boolean> = playing
        val firstChunk = CompletableDeferred<Unit>()
        val sourceReturned = CompletableDeferred<Unit>()
        val tail = CompletableDeferred<Unit>()
        var blockSink = false
        var samples = 0L
        override suspend fun play(audio: FloatArray, sampleRate: Int) { error("must not replay the complete result") }
        override suspend fun playStream(sampleRate: Int, producer: suspend (suspend (FloatArray) -> Unit) -> Unit): StreamPlaybackResult {
            try {
                producer { chunk ->
                    playing.value = true
                    samples += chunk.size
                    firstChunk.complete(Unit)
                    if (blockSink) awaitCancellation()
                }
                sourceReturned.complete(Unit)
                tail.await()
                return StreamPlaybackResult(samples, 0)
            } finally { playing.value = false }
        }
        override fun stop() = Unit
        override fun release() = Unit
    }

    @Test fun `stream delivers before synthesis ends and completes only after tail`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val synth = ProgressiveSynth()
        val playback = StreamPlayback()
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), synth, capture, playback)
        try {
            controller.start(SessionConfig("Chinese", "English", "p1"))
            commitUtterance(capture)
            withTimeout(8000) { playback.firstChunk.await() }
            assertTrue(synth.producing.get())
            assertTrue(controller.snapshot.value.activeStages.containsAll(setOf(WorkStage.TTS, WorkStage.PLAYBACK)))
            synth.finish.complete(Unit)
            withTimeout(8000) { playback.sourceReturned.await() }
            assertTrue(controller.snapshot.value.turns.none { it.status == TurnStatus.COMPLETE })
            playback.tail.complete(Unit)
            awaitSnapshot(controller) { it.turns.any { turn -> turn.status == TurnStatus.COMPLETE } }
            assertEquals(2L, playback.samples)
        } finally { controller.stop() }
    }

    @Test fun `partial synthesis failure keeps translation and never completes`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val synth = ProgressiveSynth().apply { failAfterFirst = true; finish.complete(Unit) }
        val playback = StreamPlayback()
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), synth, capture, playback)
        try {
            controller.start(SessionConfig("Chinese", "English", "p1"))
            commitUtterance(capture)
            val snapshot = awaitSnapshot(controller) { it.turns.any { turn -> turn.status == TurnStatus.FAILED } }
            val turn = snapshot.turns.last()
            assertEquals(WorkStage.TTS, turn.problem?.stage)
            assertEquals("Hello world", turn.translatedText)
            assertEquals(1L, playback.samples)
            assertTrue(!playback.isPlaying.value)
        } finally { controller.stop() }
    }

    @Test fun `unexpected synthesis allocation error retains its actual stage`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val synth = ProgressiveSynth().apply { failureBeforeOutput = OutOfMemoryError("controlled allocation failure") }
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), synth, capture, StreamPlayback())
        try {
            controller.start(SessionConfig("Chinese", "English", "p1"))
            commitUtterance(capture)
            val snapshot = awaitSnapshot(controller) { it.turns.any { turn -> turn.status == TurnStatus.FAILED } }
            val turn = snapshot.turns.last()
            assertEquals("worker_unexpected", turn.problem?.code)
            assertEquals(WorkStage.TTS, turn.problem?.stage)
            assertEquals("Hello world", turn.translatedText)
        } finally { controller.stop() }
    }

    @Test fun `stop joins synthesis suspended in the audio sink`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val synth = ProgressiveSynth()
        val playback = StreamPlayback().apply { blockSink = true }
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), synth, capture, playback)
        controller.start(SessionConfig("Chinese", "English", "p1"))
        commitUtterance(capture)
        withTimeout(8000) { playback.firstChunk.await() }
        withTimeout(2000) { controller.stop() }
        assertTrue(!synth.producing.get() && !synth.releasedDuringProduction)
        assertTrue(!playback.isPlaying.value)
        assertTrue(controller.snapshot.value.turns.none { it.status == TurnStatus.COMPLETE })
    }

    // ---- Fakes ----

    /** Restartable capture: a fresh channel per start, stop unblocks the loop. */
    private class FakeCapture : AudioCapture {
        private var current = Channel<FloatArray>(Channel.UNLIMITED)
        var crashInLoop = false
        var crashMessage: String? = null
        private val _amplitude = MutableStateFlow(0f)
        override val amplitude: StateFlow<Float> = _amplitude
        override fun hasPermission(): Boolean = true

        @Volatile
        var started = false
            private set

        var startCalls = 0
            private set

        override suspend fun start(onChunk: (FloatArray) -> Unit, onReady: () -> Unit) {
            startCalls += 1
            val channel = Channel<FloatArray>(Channel.UNLIMITED)
            current = channel
            started = true
            onReady()
            try {
                for (chunk in channel) {
                    if (crashInLoop) {
                        throw IllegalStateException(crashMessage ?: "AudioRecord read error")
                    }
                    onChunk(chunk)
                }
            } finally {
                started = false
            }
        }

        override fun stop() {
            // Closing the channel unblocks the read loop (the AudioRecord.stop()
            // equivalent in the real implementation).
            current.close()
        }

        fun speak(samples: Int = 1600, value: Float = 0.5f) {
            current.trySend(FloatArray(samples) { value })
        }

        fun speakSilence(samples: Int = 1600) {
            current.trySend(FloatArray(samples))
        }
    }

    private class FakePlayback : AudioPlayback {
        private val _isPlaying = MutableStateFlow(false)
        override val isPlaying: StateFlow<Boolean> = _isPlaying

        val played = mutableListOf<FloatArray>()
        var blockInPlay = false
        private val resume = CompletableDeferred<Unit>()

        fun resumePlayback() {
            resume.complete(Unit)
        }

        override suspend fun play(audio: FloatArray, sampleRate: Int) {
            _isPlaying.value = true
            try {
                played.add(audio)
                if (blockInPlay) resume.await()
            } finally {
                _isPlaying.value = false
            }
        }

        override fun stop() {
            resume.complete(Unit)
        }

        override fun release() = Unit
    }

    private class FakeRecognizer : SpeechRecognizer {
        var loadCalls = 0
            private set
        var releaseCalls = 0
            private set

        /** Language hint passed to every transcribe call, in order. */
        val capturedLanguages = mutableListOf<String?>()

        /** When set, load() suspends on this gate (simulating a slow load). */
        var slowLoadGate: CompletableDeferred<Unit>? = null
        var disposalGate: CompletableDeferred<Unit>? = null
        val disposalStarted = CompletableDeferred<Unit>()

        @Volatile
        var insideLoad = false
            private set

        var failLoad = false
        var transcribeText = "你好世界"
        var failTranscribe = false

        override suspend fun load() {
            insideLoad = true
            try {
                loadCalls += 1
                slowLoadGate?.await()
                if (failLoad) throw IllegalStateException("asr model missing")
            } finally {
                insideLoad = false
            }
        }

        override suspend fun transcribe(
            audioData: FloatArray,
            language: String?,
            sampleRate: Int,
        ): AsrEngine.TranscriptionResult {
            capturedLanguages.add(language)
            return if (failTranscribe) {
                AsrEngine.TranscriptionResult(text = "", language = language ?: "unknown")
            } else {
                AsrEngine.TranscriptionResult(text = transcribeText, language = "unknown")
            }
        }

        override suspend fun release() {
            disposalStarted.complete(Unit)
            disposalGate?.await()
            releaseCalls += 1
        }
    }

    private class FakeTranslator : TranslationEngine {
        var loadCalls = 0
            private set
        var releaseCalls = 0
            private set
        var cancelCalls = 0
            private set
        var failLoad = false

        /** 1-based translate call number to block on a manual gate. */
        var blockOnNthTranslate = 0

        /** When true, every translate suspends until cancel() is called. */
        var blockUntilCancel = false
        var failTranslateOnce = false
        private var failedOnce = false
        var translatedText = "Hello world"
        var translateCalls = 0
            private set
        var onTranslateReturn: (() -> Unit)? = null

        /** sourceLanguage received by every translate call, in order. */
        val capturedSources = mutableListOf<String>()

        private var cancelSignal: CompletableDeferred<Unit>? = null
        private var gate: CompletableDeferred<Unit>? = null

        fun releaseGate() {
            gate?.complete(Unit)
        }

        override suspend fun load() {
            loadCalls += 1
            if (failLoad) throw IllegalStateException("mt runtime unavailable")
        }

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            translateCalls += 1
            capturedSources.add(request.sourceLanguage)
            try {
                return translateInner(request)
            } finally {
                onTranslateReturn?.invoke()
            }
        }

        private suspend fun translateInner(request: TranslationRequest): TranslationResult {
            if (blockOnNthTranslate == translateCalls) {
                val awaited = CompletableDeferred<Unit>()
                gate = awaited
                awaited.await()
            }
            if (blockUntilCancel) {
                val awaited = CompletableDeferred<Unit>()
                cancelSignal = awaited
                awaited.await()
                throw CancellationException("generation aborted")
            }
            if (failTranslateOnce && !failedOnce) {
                failedOnce = true
                throw IllegalStateException("mt failed")
            }
            return TranslationResult(
                sourceText = request.text,
                translatedText = translatedText,
                sourceLanguage = request.sourceLanguage,
                targetLanguage = request.targetLanguage,
                latencyMs = 1,
                runtime = "fake"
            )
        }

        override fun cancel() {
            cancelCalls += 1
            cancelSignal?.complete(Unit)
            gate?.complete(Unit)
        }

        override fun release() {
            releaseCalls += 1
        }
    }

    private class FakeSynthesizer : SpeechSynthesizer {
        var loadCalls = 0
            private set
        var releaseCalls = 0
            private set

        override suspend fun load() {
            loadCalls += 1
        }

        override suspend fun synthesize(
            text: String,
            language: String,
            speakerEmbedding: FloatArray?
        ): TtsEngine.SynthesisResult {
            assertNotNull("pipeline must supply a resolved embedding", speakerEmbedding)
            return TtsEngine.SynthesisResult(
                audioData = FloatArray(24000) { 0.1f }, // 1s of non-silent test audio
                sampleRate = 24000,
                durationMs = 1000,
                inferenceTimeMs = 1
            )
        }

        override fun release() {
            releaseCalls += 1
        }
    }

    // speech chunks commit after ~1s speech + decay + 800ms trailing silence
    private fun commitUtterance(capture: FakeCapture) {
        repeat(8) { capture.speak() }         // 800ms speech
        repeat(30) { capture.speakSilence() } // 3s silence → commit
    }

    private fun orchestrator(
        recognizer: SpeechRecognizer,
        translator: TranslationEngine,
        synthesizer: SpeechSynthesizer,
        capture: FakeCapture,
        playback: AudioPlayback,
        voiceProfile: FloatArray? = FloatArray(192) { 0.1f },
        limits: RuntimeLimits = RuntimeLimits(),
        leaseLog: MutableList<String>? = null
    ): PipelineOrchestrator = PipelineOrchestrator(
        recognizer = recognizer,
        translator = translator,
        synthesizer = synthesizer,
        capture = capture,
        playback = playback,
        voiceProfiles = { voiceProfile },
        limits = limits,
        acquireModelLease = {
            leaseLog?.add("acquire")
            AutoCloseable { leaseLog?.add("release") }
        }
    )

    private suspend fun awaitSnapshot(
        controller: SessionController,
        timeoutMs: Long = 8000,
        condition: (SessionSnapshot) -> Boolean
    ): SessionSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = controller.snapshot.value
        while (System.currentTimeMillis() < deadline) {
            latest = controller.snapshot.value
            if (condition(latest)) return latest
            delay(10)
        }
        fail("Condition not met within ${timeoutMs}ms; last=$latest")
        throw IllegalStateException()
    }

    private suspend fun awaitCondition(timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        fail("Condition not met within ${timeoutMs}ms")
    }

    private fun TurnStatus.isTerminal(): Boolean =
        this == TurnStatus.COMPLETE || this == TurnStatus.FAILED ||
            this == TurnStatus.DROPPED || this == TurnStatus.CANCELLED

    // ---- Tests: lifecycle (V03) ----

    @Test
    fun `stop holds lease and restart until asynchronous disposal completes`() = runBlocking<Unit> {
        val recognizer = FakeRecognizer()
        val gate = CompletableDeferred<Unit>()
        recognizer.disposalGate = gate
        val leases = mutableListOf<String>()
        val controller = orchestrator(recognizer, FakeTranslator(), FakeSynthesizer(),
            FakeCapture(), FakePlayback(), leaseLog = leases)
        val config = SessionConfig("Chinese", "English")
        controller.start(config)
        val stopped = CompletableDeferred<Unit>()
        val stopJob = launch { controller.stop(); stopped.complete(Unit) }
        withTimeout(8000) { recognizer.disposalStarted.await() }
        assertEquals(SessionPhase.STOPPING, controller.snapshot.value.phase)
        assertEquals(listOf("acquire"), leases)
        assertTrue(!stopped.isCompleted)
        val restart = launch { controller.start(config) }
        delay(100)
        assertEquals("new load must not cross the disposal barrier", 1, recognizer.loadCalls)
        assertEquals(listOf("acquire"), leases)
        gate.complete(Unit)
        withTimeout(8000) { stopJob.join(); restart.join() }
        assertEquals(2, recognizer.loadCalls)
        assertEquals(listOf("acquire", "release", "acquire"), leases)
        assertEquals(SessionPhase.ACTIVE, controller.snapshot.value.phase)
        controller.stop()
        assertEquals(SessionPhase.IDLE, controller.snapshot.value.phase)
    }

    @Test
    fun `start activates the session and loads engines with a model lease`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val recognizer = FakeRecognizer()
        val translator = FakeTranslator()
        val synthesizer = FakeSynthesizer()
        val leaseLog = mutableListOf<String>()
        val controller = orchestrator(recognizer, translator, synthesizer, capture, FakePlayback(), leaseLog = leaseLog)

        controller.start(SessionConfig("Chinese", "English", voiceProfileId = "p1"))

        assertEquals(1, recognizer.loadCalls)
        assertEquals(1, translator.loadCalls)
        assertEquals(1, synthesizer.loadCalls)
        val snapshot = awaitSnapshot(controller) {
            it.phase == SessionPhase.ACTIVE && it.captureActive
        }
        assertEquals(1L, snapshot.sessionId)
        assertTrue(capture.started)
        assertEquals("lease acquired before any load", listOf("acquire"), leaseLog)

        controller.stop()
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
        assertEquals("lease released after engines were released", listOf("acquire", "release"), leaseLog)
    }

    @Test
    fun `model lease is held until the in-flight native call has returned`() = runBlocking<Unit> {
        val leaseLog = mutableListOf<String>()
        val capture = FakeCapture()
        val translator = FakeTranslator().apply {
            blockUntilCancel = true
            onTranslateReturn = { leaseLog.add("translateReturned") }
        }
        val controller = orchestrator(
            FakeRecognizer(), translator, FakeSynthesizer(), capture, FakePlayback(),
            leaseLog = leaseLog
        )
        controller.start(SessionConfig("Chinese", "English"))
        commitUtterance(capture)
        awaitSnapshot(controller) { s -> s.turns.any { it.status == TurnStatus.TRANSLATING } }
        assertEquals(listOf("acquire"), leaseLog)

        withTimeout(8000) { controller.stop() }
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
        assertEquals(
            "lease must outlive the in-flight native generate",
            listOf("acquire", "translateReturned", "release"),
            leaseLog
        )
    }

    @Test
    fun `failed start returns the model lease`() = runBlocking<Unit> {
        val leaseLog = mutableListOf<String>()
        val controller = orchestrator(
            FakeRecognizer().apply { failLoad = true },
            FakeTranslator(), FakeSynthesizer(), FakeCapture(), FakePlayback(),
            leaseLog = leaseLog
        )

        withTimeout(8000) {
            try {
                controller.start(SessionConfig("Chinese", "English"))
                fail("expected SessionStartException")
            } catch (expected: SessionStartException) {
                // expected
            }
        }
        assertEquals(listOf("acquire", "release"), leaseLog)
    }

    @Test
    fun `second start while running fails without corrupting state`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val recognizer = FakeRecognizer()
        val controller = orchestrator(recognizer, FakeTranslator(), FakeSynthesizer(), capture, FakePlayback())

        controller.start(SessionConfig("Chinese", "English"))
        val sessionIdBefore = controller.snapshot.value.sessionId

        try {
            controller.start(SessionConfig("Chinese", "English"))
            fail("expected SessionStartException")
        } catch (expected: SessionStartException) {
            assertEquals("already_started", expected.problem.code)
        }

        awaitSnapshot(controller) {
            it.phase == SessionPhase.ACTIVE && it.sessionId == sessionIdBefore
        }
        assertEquals(1, recognizer.loadCalls)
        controller.stop()
    }

    @Test
    fun `stop is idempotent and resets phase to idle`() = runBlocking<Unit> {
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), FakeCapture(), FakePlayback())
        controller.start(SessionConfig("Chinese", "English"))

        controller.stop()
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
        controller.stop()
        assertEquals(SessionPhase.IDLE, controller.snapshot.value.phase)
    }

    @Test
    fun `stop unblocks capture before joining the capture loop`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), capture, FakePlayback())
        controller.start(SessionConfig("Chinese", "English"))

        assertTrue(capture.started)

        // If stop() joined the capture loop before unblocking it, this would
        // hang and the withTimeout would fail the test (spec R01 order).
        withTimeout(8000) { controller.stop() }
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
    }

    @Test
    fun `stop requests native cancellation before waiting for in-flight generate`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val translator = FakeTranslator().apply { blockUntilCancel = true }
        val controller = orchestrator(FakeRecognizer(), translator, FakeSynthesizer(), capture, FakePlayback())
        controller.start(SessionConfig("Chinese", "English"))

        commitUtterance(capture)

        awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.TRANSLATING }
        }

        // translate() only unblocks via cancel(): if stop() waited for the
        // in-flight call before requesting cancellation, this times out.
        withTimeout(8000) { controller.stop() }
        assertEquals(1, translator.cancelCalls)
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
    }

    @Test
    fun `stop during a slow start aborts promptly before loading later engines`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val capture = FakeCapture()
        val recognizer = FakeRecognizer().apply { slowLoadGate = gate }
        val translator = FakeTranslator()
        val synthesizer = FakeSynthesizer()
        val controller = orchestrator(recognizer, translator, synthesizer, capture, FakePlayback())

        val startAborted = AtomicBoolean(false)
        val startJob = launch {
            try {
                controller.start(SessionConfig("Chinese", "English"))
            } catch (e: CancellationException) {
                startAborted.set(true)
            }
        }

        // Wait until start() is inside the (blocked) ASR load.
        awaitCondition { recognizer.insideLoad }

        // Request stop while start() holds the lifecycle mutex. stop() flags
        // the request synchronously, then blocks on the mutex — the flag is
        // what lets the start abort at its next checkpoint.
        val stopDone = CompletableDeferred<Unit>()
        launch {
            withTimeout(8000) { controller.stop() }
            stopDone.complete(Unit)
        }
        delay(100) // let stop() set the flag and block on the mutex

        // Finish the slow load: the start must observe the stop request at its
        // next checkpoint instead of running to full completion.
        gate.complete(Unit)
        stopDone.await()
        startJob.join()
        assertTrue("start should abort via cancellation", startAborted.get())
        assertEquals("MT must never load after abort", 0, translator.loadCalls)
        assertEquals(0, synthesizer.loadCalls)
        assertEquals("aborted start released the loaded engine", 1, recognizer.releaseCalls)
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
    }

    @Test
    fun `close during a slow start aborts and leaves a closed controller`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val capture = FakeCapture()
        val recognizer = FakeRecognizer().apply { slowLoadGate = gate }
        val translator = FakeTranslator()
        val controller = orchestrator(recognizer, translator, FakeSynthesizer(), capture, FakePlayback())

        val startAborted = AtomicBoolean(false)
        val startJob = launch {
            try {
                controller.start(SessionConfig("Chinese", "English"))
            } catch (e: CancellationException) {
                startAborted.set(true)
            }
        }
        awaitCondition { recognizer.insideLoad }

        controller.close() // non-suspending; must not hang on the held mutex

        gate.complete(Unit)
        startJob.join()
        assertTrue(startAborted.get())
        assertEquals(0, translator.loadCalls)

        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
        try {
            controller.start(SessionConfig("Chinese", "English"))
            fail("expected SessionStartException")
        } catch (expected: SessionStartException) {
            assertEquals("controller_closed", expected.problem.code)
        }
    }

    @Test
    fun `asr load failure fails the start and never hangs or loads other engines`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val recognizer = FakeRecognizer().apply { failLoad = true }
        val translator = FakeTranslator()
        val synthesizer = FakeSynthesizer()
        val controller = orchestrator(recognizer, translator, synthesizer, capture, FakePlayback())

        withTimeout(8000) {
            try {
                controller.start(SessionConfig("Chinese", "English"))
                fail("expected SessionStartException")
            } catch (expected: SessionStartException) {
                assertEquals(SessionPhase.FAILED, controller.snapshot.value.phase)
                assertNotNull(controller.snapshot.value.problem)
            }
        }

        assertEquals(1, recognizer.loadCalls)
        assertEquals("mandatory ASR failed → MT/TTS never loaded", 0, translator.loadCalls)
        assertEquals(0, synthesizer.loadCalls)
        assertEquals("failed start released partial resources", 1, recognizer.releaseCalls)

        // A stop after failure still reaches IDLE (no join hang on fake jobs).
        controller.stop()
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
    }

    @Test
    fun `capture hardware failure mid-session fails the session and cleans up`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val recognizer = FakeRecognizer()
        val disposal = CompletableDeferred<Unit>()
        recognizer.disposalGate = disposal
        val translator = FakeTranslator()
        val leases = mutableListOf<String>()
        val controller = orchestrator(recognizer, translator, FakeSynthesizer(), capture, FakePlayback(), leaseLog = leases)
        controller.start(SessionConfig("Chinese", "English"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        // Hardware dies while the session is live.
        capture.crashInLoop = true
        capture.speakSilence()

        withTimeout(8000) { recognizer.disposalStarted.await() }
        assertEquals(SessionPhase.STOPPING, controller.snapshot.value.phase)
        assertEquals(listOf("acquire"), leases)
        disposal.complete(Unit)

        val snapshot = awaitSnapshot(controller) { it.phase == SessionPhase.FAILED }
        assertEquals("capture_failed", snapshot.problem?.code)
        assertTrue(snapshot.problem?.recoverable == true)
        assertEquals("worker cancelled, queue drained", 0, snapshot.turns.count { !it.status.isTerminal() })
        assertEquals("engines released on capture failure", 1, recognizer.releaseCalls)
        assertEquals(1, translator.releaseCalls)
        assertTrue(!capture.started)

        // The controller is restartable after a recoverable capture failure.
        capture.crashInLoop = false
        controller.start(SessionConfig("Chinese", "English"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }
        controller.stop()
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
    }

    @Test
    fun `close is idempotent and start fails after close`() = runBlocking<Unit> {
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), FakeCapture(), FakePlayback())
        controller.start(SessionConfig("Chinese", "English"))
        controller.close()
        awaitSnapshot(controller) { it.phase == SessionPhase.IDLE }
        controller.close()

        try {
            controller.start(SessionConfig("Chinese", "English"))
            fail("expected SessionStartException")
        } catch (expected: SessionStartException) {
            assertEquals("controller_closed", expected.problem.code)
        }
    }

    // ---- Tests: degradation & honest failure (V06) ----

    @Test
    fun `missing mt keeps source text and never fakes a translation`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val translator = FakeTranslator().apply { failLoad = true }
        val synthesizer = FakeSynthesizer()
        val controller = orchestrator(FakeRecognizer(), translator, synthesizer, capture, FakePlayback())

        controller.start(SessionConfig("Chinese", "English"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        val snapshot = awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.COMPLETE }
        }
        val turn = snapshot.turns.first()

        assertEquals("你好世界", turn.sourceText)
        assertNull("no translation may exist without MT", turn.translatedText)
        assertEquals("mt_unavailable", turn.problem?.code)
        // No synthesis of source text as if it were the target language.
        assertEquals(0, synthesizer.loadCalls)

        controller.stop()
    }

    @Test
    fun `missing voice profile keeps the real translation without audio`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val playback = FakePlayback()
        val controller = orchestrator(
            FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), capture, playback,
            voiceProfile = null // resolver returns null: no valid reference
        )

        controller.start(SessionConfig("Chinese", "English", voiceProfileId = "profile-1"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        val snapshot = awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.COMPLETE }
        }
        val turn = snapshot.turns.first()

        assertEquals("你好世界", turn.sourceText)
        assertEquals("Hello world", turn.translatedText)
        assertEquals("voice_profile_missing", turn.problem?.code)
        assertTrue("nothing was played", playback.played.isEmpty())

        controller.stop()
    }

    @Test
    fun `missing tts model keeps the real translation without audio`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val playback = FakePlayback()
        val failingSynth = object : SpeechSynthesizer {
            override suspend fun load() {
                throw IllegalStateException("tts model missing")
            }

            override suspend fun synthesize(
                text: String,
                language: String,
                speakerEmbedding: FloatArray?
            ): TtsEngine.SynthesisResult = throw IllegalStateException("unreachable")

            override fun release() = Unit
        }
        val controller = orchestrator(
            FakeRecognizer(), FakeTranslator(), failingSynth, capture, playback
        )

        controller.start(SessionConfig("Chinese", "English", voiceProfileId = "p1"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        val snapshot = awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.COMPLETE }
        }
        val turn = snapshot.turns.first()

        assertEquals("Hello world", turn.translatedText)
        assertEquals("tts_unavailable", turn.problem?.code)
        assertTrue(playback.played.isEmpty())
        controller.stop()
    }

    @Test
    fun `per-turn mt failure marks that turn failed and others continue`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val translator = FakeTranslator().apply { failTranslateOnce = true }
        val controller = orchestrator(FakeRecognizer(), translator, FakeSynthesizer(), capture, FakePlayback())

        controller.start(SessionConfig("Chinese", "English"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        val failed = awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.FAILED }
        }
        val failedTurn = failed.turns.first { it.status == TurnStatus.FAILED }
        assertEquals("mt_failed", failedTurn.problem?.code)

        commitUtterance(capture)
        awaitSnapshot(controller) { s ->
            s.turns.any { it.id != failedTurn.id && it.status == TurnStatus.COMPLETE }
        }

        controller.stop()
    }

    @Test
    fun `empty transcription is a failed turn with a reason`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val recognizer = FakeRecognizer().apply { failTranscribe = true }
        val controller = orchestrator(recognizer, FakeTranslator(), FakeSynthesizer(), capture, FakePlayback())

        controller.start(SessionConfig("Chinese", "English"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        val snapshot = awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.FAILED }
        }
        val turn = snapshot.turns.first { it.status == TurnStatus.FAILED }
        assertEquals("asr_empty", turn.problem?.code)
        controller.stop()
    }

    // ---- Tests: backpressure (V05) ----

    @Test
    fun `full queue drops the newest utterance visibly and counts it`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val translator = FakeTranslator().apply { blockUntilCancel = true }
        val controller = orchestrator(FakeRecognizer(), translator, FakeSynthesizer(), capture, FakePlayback())
        controller.start(SessionConfig("Chinese", "English"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        // Turn 1 blocks the single worker inside MT; turns 2 and 3 fill the
        // queue (capacity 2); turn 4 must be dropped explicitly.
        commitUtterance(capture) // turn 1
        awaitSnapshot(controller) { s -> s.turns.any { it.status == TurnStatus.TRANSLATING } }
        commitUtterance(capture) // turn 2 → queue
        repeat(30) { capture.speakSilence() } // settle turn 2 commit
        commitUtterance(capture) // turn 3 → queue
        repeat(30) { capture.speakSilence() }
        commitUtterance(capture) // turn 4 → dropped

        val snapshot = awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.DROPPED }
        }
        assertEquals(1, snapshot.droppedTurns)
        val dropped = snapshot.turns.first { it.status == TurnStatus.DROPPED }
        assertEquals("capture_queue_full", dropped.problem?.code)
        assertEquals("dropped turn carries no fabricated text", "", dropped.sourceText)

        controller.stop()
    }

    @Test
    fun `every minted turn reaches exactly one terminal state`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), capture, FakePlayback())
        controller.start(SessionConfig("Chinese", "English"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        commitUtterance(capture)
        awaitSnapshot(controller) { s -> s.turns.count { it.status == TurnStatus.COMPLETE } == 2 }

        controller.stop()
        val final = controller.snapshot.value
        assertTrue(final.turns.isNotEmpty())
        assertTrue(
            "all turns terminal after stop",
            final.turns.all { it.status.isTerminal() }
        )
    }

    // ---- Tests: session isolation (V04) ----

    @Test
    fun `a new session starts fresh and old turns do not leak`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), capture, FakePlayback())
        controller.start(SessionConfig("Chinese", "English"))
        commitUtterance(capture)
        awaitSnapshot(controller) { s -> s.turns.isNotEmpty() }
        controller.stop()

        // Restart with the same capture hardware: a fresh session, fresh state.
        controller.start(SessionConfig("Chinese", "English"))

        val snapshot = awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }
        assertEquals(2L, snapshot.sessionId)
        assertTrue("turns do not carry over into the new session", snapshot.turns.isEmpty())
        assertEquals(0L, snapshot.droppedTurns)
        assertNull(snapshot.problem)

        // The restarted session actually processes turns.
        commitUtterance(capture)
        awaitSnapshot(controller) { s -> s.turns.any { it.status == TurnStatus.COMPLETE } }
        controller.stop()
    }

    // ---- Tests: full happy path + half-duplex (V07) ----

    @Test
    fun `asr hint stays auto while mt keeps configured source language`() = runBlocking<Unit> {
        // Root's real ablation (runtime-cache.json asr.hintAblation): forcing
        // canonical hints degraded zh/yue — the pipeline must leave ASR on
        // auto while MT still receives the user's configured source language.
        val recognizer = FakeRecognizer()
        val translator = FakeTranslator()
        val capture = FakeCapture()
        val controller = orchestrator(recognizer, translator, FakeSynthesizer(), capture, FakePlayback())
        controller.start(SessionConfig("Chinese", "English", voiceProfileId = "p1"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        awaitSnapshot(controller) { s -> s.turns.any { it.status == TurnStatus.COMPLETE } }
        controller.stop()

        assertEquals(listOf("auto"), recognizer.capturedLanguages)
        assertEquals(listOf("Chinese"), translator.capturedSources)
    }

    @Test
    fun `full turn flows capture asr mt tts playback complete`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val playback = FakePlayback()
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), capture, playback)
        controller.start(SessionConfig("Chinese", "English", voiceProfileId = "p1"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        val snapshot = awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.COMPLETE }
        }
        val turn = snapshot.turns.first()
        assertEquals("你好世界", turn.sourceText)
        assertEquals("Hello world", turn.translatedText)
        assertNull(turn.problem)
        assertEquals(1, playback.played.size)
        assertEquals(24000, playback.played[0].size)

        controller.stop()
    }

    @Test
    fun `mic input during playback is never recognized half-duplex`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val playback = FakePlayback().apply { blockInPlay = true }
        val controller = orchestrator(FakeRecognizer(), FakeTranslator(), FakeSynthesizer(), capture, playback)
        controller.start(SessionConfig("Chinese", "English", voiceProfileId = "p1"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        commitUtterance(capture)
        awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.PLAYING }
        }
        val turnCountDuringPlayback = controller.snapshot.value.turns.size

        // Loud speech while the speaker plays: the segmenter is held in reset —
        // this must never become a new turn (no echo re-recognition).
        repeat(8) { capture.speak() }
        repeat(30) { capture.speakSilence() }
        delay(200)
        assertEquals(
            "no new turns while playback holds the gate",
            turnCountDuringPlayback,
            controller.snapshot.value.turns.size
        )

        playback.resumePlayback()
        awaitSnapshot(controller) { s ->
            s.turns.any { it.status == TurnStatus.COMPLETE }
        }

        // After the gate opens, real speech is recognized again.
        commitUtterance(capture)
        awaitSnapshot(controller) { s ->
            s.turns.count { it.status == TurnStatus.COMPLETE } == 2
        }

        controller.stop()
    }

    // ---- Tests: serial worker (spec R03) ----

    @Test
    fun `turns are processed serially by a single worker`() = runBlocking<Unit> {
        val capture = FakeCapture()
        val translator = FakeTranslator().apply { blockOnNthTranslate = 1 }
        val controller = orchestrator(FakeRecognizer(), translator, FakeSynthesizer(), capture, FakePlayback())
        controller.start(SessionConfig("Chinese", "English", voiceProfileId = "p1"))
        awaitSnapshot(controller) { it.phase == SessionPhase.ACTIVE }

        // Turn 1 blocks the worker inside MT.
        commitUtterance(capture)
        awaitSnapshot(controller) { s -> s.turns.any { it.status == TurnStatus.TRANSLATING } }

        // Turn 2 is committed while the worker is busy: it must wait in the
        // queue, not start ASR in parallel.
        commitUtterance(capture)
        val snapshot = awaitSnapshot(controller) { s -> s.turns.size == 2 }
        val second = snapshot.turns.last()
        assertEquals(
            "second turn waits while the worker is busy",
            TurnStatus.CAPTURED,
            second.status
        )

        translator.releaseGate()
        awaitSnapshot(controller) { s -> s.turns.count { it.status == TurnStatus.COMPLETE } == 2 }
        controller.stop()
    }
}
