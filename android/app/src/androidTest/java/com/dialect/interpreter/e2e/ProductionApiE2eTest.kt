package com.dialect.interpreter.e2e

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.MainActivity
import com.dialect.interpreter.SessionVoiceSynthesis
import com.dialect.interpreter.audio.AudioCapture
import com.dialect.interpreter.audio.AudioPlayback
import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.StreamPlaybackResult
import com.dialect.interpreter.data.VoiceProfileRepository
import com.dialect.interpreter.inference.AsrEngine
import com.dialect.interpreter.inference.HyMtTranslationEngine
import com.dialect.interpreter.inference.OnnxModelManager
import com.dialect.interpreter.inference.PipelineOrchestrator
import com.dialect.interpreter.inference.SpeechSynthesizer
import com.dialect.interpreter.inference.TtsEngine
import com.dialect.interpreter.session.SessionConfig
import com.dialect.interpreter.session.SessionPhase
import com.dialect.interpreter.session.TranscriptTurn
import com.dialect.interpreter.session.TurnStatus
import com.dialect.interpreter.session.VoiceProfileResolver
import com.k2fsa.sherpa.onnx.SherpaJni
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-adapter end-to-end acceptance (root mandate 2026-09-07):
 * real ASR + real MT + real TTS through the real internal
 * [SessionVoiceSynthesis] wiring and the real [VoiceProfileRepository] with a
 * REAL 16 kHz reference. Only capture is injected (file PCM instead of the
 * microphone) and playback runs on the real AudioPlayer muted, recorded so the
 * synthesized WAV can be handed back for transcription/scoring. No hardcoded
 * embeddings, no fabricated transcripts, no fake translations: the ICL
 * reference text comes from the session's own ASR of the real reference, and
 * the translation comes from the real Hy-MT model.
 *
 * device arg: api2E2eCase = full | restart | cancel
 */
private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun wavBytes(pcm: FloatArray, sampleRate: Int): ByteArray {
    val data = ByteArray(pcm.size * 2)
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    for (v in pcm) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    val header = ByteArray(44)
    fun le32(a: ByteArray, o: Int, v: Int) {
        a[o] = (v and 0xff).toByte(); a[o + 1] = ((v shr 8) and 0xff).toByte()
        a[o + 2] = ((v shr 16) and 0xff).toByte(); a[o + 3] = ((v shr 24) and 0xff).toByte()
    }
    fun le16(a: ByteArray, o: Int, v: Int) {
        a[o] = (v and 0xff).toByte(); a[o + 1] = ((v shr 8) and 0xff).toByte()
    }
    "RIFF".toByteArray().copyInto(header, 0); le32(header, 4, data.size + 36)
    "WAVE".toByteArray().copyInto(header, 8); "fmt ".toByteArray().copyInto(header, 12)
    le32(header, 16, 16); le16(header, 20, 1); le16(header, 22, 1)
    le32(header, 24, sampleRate); le32(header, 28, sampleRate * 2)
    le16(header, 32, 2); le16(header, 34, 16)
    "data".toByteArray().copyInto(header, 36); le32(header, 40, data.size)
    return header + data
}

@RunWith(AndroidJUnit4::class)
class ProductionApiE2eTest {
    private val case get() = InstrumentationRegistry.getArguments()
        .getString("api2E2eCase") ?: "full"
    private val speakSeconds get() = (InstrumentationRegistry.getArguments()
        .getString("speakSeconds")?.toFloat() ?: 10f).also {
            require(it in 0.1f..10f && it.isFinite())
        }
    private val runId = java.util.UUID.randomUUID().toString()
    private val sourceLanguage get() = InstrumentationRegistry.getArguments().getString("sourceLanguage") ?: "en"
    private val targetLanguage get() = InstrumentationRegistry.getArguments().getString("targetLanguage") ?: "zh"
    private val repeatInput get() = InstrumentationRegistry.getArguments().getString("repeatInput") == "true"

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun outDir(): File = File(
        "/storage/emulated/0/Android/data/com.dialect.interpreter/files/api2_e2e/$case-$runId")
        .apply { mkdirs() }

    /** Real 16 kHz reference PCM from the pushed fixture (speaker 121). */
    private fun reference16k(): FloatArray {
        val f24 = File(context().getExternalFilesDir(null), "e2e/reference24.f32")
        val raw = f24.readBytes()
        val pcm24 = FloatArray(raw.size / 4)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm24)
        SherpaJni.load()
        return SherpaJni.resample(pcm24, 24000, 16000)
    }

    /**
     * Production capture port backed by file PCM (the only injected piece).
     * Pumps the first [speakSeconds] of speech at natural length so the
     * segmenter commits a production-sized utterance, then trailing silence.
     */
    private class FilePcmCapture(private val pcm: FloatArray, private val speakSeconds: Float,
                                 private val repeatInput: Boolean) : AudioCapture {
        private val speakSamples = (speakSeconds * 16000).toInt()
        private val stopRequested = AtomicBoolean(false)
        private val _amplitude = kotlinx.coroutines.flow.MutableStateFlow(0f)
        override val amplitude = _amplitude
        override fun hasPermission(): Boolean = true
        override suspend fun start(onChunk: (FloatArray) -> Unit, onReady: () -> Unit) {
            stopRequested.set(false)
            onReady()
            withContext(Dispatchers.IO) {
                val chunk = FloatArray(3200) // 200 ms at 16 kHz, production chunking
                var offset = 0
                // Explicit boundary stress can repeat real fixture PCM to
                // reach the segment limit; normal runs retain natural length.
                while (offset < speakSamples && (repeatInput || offset < pcm.size) && !stopRequested.get()) {
                    val n = minOf(chunk.size, speakSamples - offset,
                        if (repeatInput) chunk.size else pcm.size - offset)
                    for (i in 0 until n) chunk[i] = pcm[(offset + i) % pcm.size]
                    onChunk(chunk.copyOf(n))
                    offset += n
                    delay(60) // faster than real time; segmenter/VAD absorb pacing
                }
                // Trailing silence commits the last utterance, then idle silence
                // (capture keeps running exactly like the real microphone would).
                val silence = FloatArray(chunk.size)
                while (!stopRequested.get()) {
                    onChunk(silence.copyOf())
                    delay(200)
                }
            }
        }
        override fun stop() { stopRequested.set(true) }
    }

    /** Real muted AudioPlayer that additionally records every rendered chunk. */
    private class RecordingMutedPlayer(context: Context) : AudioPlayback {
        private val real = AudioPlayer(context)
        private val recorded = mutableListOf<FloatArray>()
        override val isPlaying get() = real.isPlaying
        fun setVolume(v: Float) = real.setVolume(v)
        override suspend fun play(audio: FloatArray, sampleRate: Int) {
            synchronized(recorded) { recorded.add(audio.copyOf()) }
            real.play(audio, sampleRate)
        }
        override suspend fun playStream(
            sampleRate: Int,
            producer: suspend (suspend (FloatArray) -> Unit) -> Unit,
        ): StreamPlaybackResult = real.playStream(sampleRate) { emit ->
            producer { chunk ->
                synchronized(recorded) { recorded.add(chunk.copyOf()) }
                emit(chunk)
            }
        }
        override fun stop() = real.stop()
        override fun release() = real.release()
        fun resetRecording() = synchronized(recorded) { recorded.clear() }
        fun writeWav(out: File): JSONObject {
            val pcm = synchronized(recorded) { recorded.toList() }
                .fold(FloatArray(0)) { acc, c -> acc + c }
            val bytes = wavBytes(pcm, 24000)
            out.writeBytes(bytes)
            return JSONObject()
                .put("file", out.name).put("samples", pcm.size).put("sample_rate", 24000)
                .put("sha256", sha256(bytes))
        }
    }

    /** Builds the exact production wiring with the two mandated substitutions. */
    private class ProductionStack(context: Context, val reference: FloatArray, speakSeconds: Float,
                                  val sourceLanguage: String, val targetLanguage: String, repeatInput: Boolean) {
        val manager = OnnxModelManager(context)
        val recognizer = AsrEngine(manager)
        val ttsEngine = TtsEngine(manager)
        // Production extraction semantics (AppContainer): a dedicated manager
        // + engine per extraction, released when the extraction is done.
        val voiceRepo = VoiceProfileRepository(
            context,
            embeddingExtractor = { pcm, profileId ->
                val extractionManager = OnnxModelManager(context)
                val extractionEngine = TtsEngine(extractionManager)
                try {
                    extractionEngine.extractSpeakerEmbedding(pcm, profileId)
                } catch (e: Exception) {
                    null
                } finally {
                    runCatching { extractionEngine.release() }
                    runCatching { extractionManager.releaseAll() }
                }
            },
        )
        val voice = SessionVoiceSynthesis(ttsEngine, recognizer, voiceRepo)
        val playback = RecordingMutedPlayer(context)
        val controller = PipelineOrchestrator(
            recognizer = recognizer,
            translator = HyMtTranslationEngine(
                modelFile = File(
                    manager.getModelsDir(),
                    "${OnnxModelManager.MT_DIR}/${OnnxModelManager.HY_MT_GGUF}")),
            synthesizer = voice,
            capture = FilePcmCapture(reference, speakSeconds, repeatInput),
            playback = playback,
            voiceProfiles = voice,
            acquireModelLease = { AutoCloseable {} },
        )
        var sessionId: Long = 0
        suspend fun start(profileId: String) {
            controller.start(SessionConfig(
                sourceLanguage = sourceLanguage, targetLanguage = targetLanguage, voiceProfileId = profileId))
            sessionId = controller.snapshot.value.sessionId
        }
    }

    private suspend fun awaitTurn(
        stack: ProductionStack,
        timeoutMs: Long,
        predicate: (TranscriptTurn) -> Boolean,
    ): TranscriptTurn = withTimeout(timeoutMs) {
        stack.controller.snapshot.filter { snap ->
            snap.turns.any { it.sessionId == stack.sessionId && predicate(it) }
        }.first().turns.last { it.sessionId == stack.sessionId && predicate(it) }
    }

    private suspend fun awaitIdle(stack: ProductionStack) = withTimeout(90_000) {
        stack.controller.snapshot.filter { it.phase == SessionPhase.IDLE }.first()
    }

    private fun turnJson(turn: TranscriptTurn, playback: RecordingMutedPlayer): JSONObject {
        val json = JSONObject()
            .put("session_id", turn.sessionId).put("turn_id", turn.id)
            .put("status", turn.status.name)
            .put("asr_text", turn.sourceText)
            .put("mt_text", turn.translatedText ?: "")
            .put("problem", turn.problem?.let { JSONObject()
                .put("code", it.code).put("stage", it.stage?.name)
                .put("message", it.message).put("recoverable", it.recoverable) })
        if (turn.status == TurnStatus.COMPLETE) {
            json.put("playback", playback.writeWav(
                File(outDir(), "e2e-s${turn.sessionId}-t${turn.id}.wav")))
        }
        return json
    }

    private suspend fun runOneTurn(
        stack: ProductionStack, profileId: String, report: JSONObject, key: String) {
        stack.playback.resetRecording()
        stack.start(profileId)
        val turn = awaitTurn(stack, 420_000L) {
            it.status in setOf(TurnStatus.COMPLETE, TurnStatus.FAILED, TurnStatus.DROPPED)
        }
        assertTrue("$key must COMPLETE, was ${turn.status} (${turn.problem})",
            turn.status == TurnStatus.COMPLETE)
        assertTrue("$key real ASR text missing", turn.sourceText.isNotBlank())
        assertTrue("$key real MT text missing", !turn.translatedText.isNullOrBlank())
        assertTrue("$key MT must differ from source",
            turn.translatedText != turn.sourceText)
        if (stack.targetLanguage == "zh") {
            assertTrue("$key MT must contain target Chinese script",
                turn.translatedText!!.any { it in '\u3400'..'\u9fff' })
        }
        report.put(key, turnJson(turn, stack.playback))
        stack.controller.stop()
        awaitIdle(stack)
    }

    @Test
    fun productionE2e_realAsrMtStreamingTtsWithPlaybackTail() = runBlocking {
        val context = context()
        val report = JSONObject().put("device", "emulator-5580 API35 arm64")
            .put("case", case).put("requested_speech_seconds", speakSeconds)
            .put("source_language", sourceLanguage).put("target_language", targetLanguage)
            .put("repeat_input_for_boundary_stress", repeatInput)
        // The session runs with the app foregrounded (production semantics):
        // Android 15 audio-focus hardening denies playback focus from a
        // background process state.
        ActivityScenario.launch(MainActivity::class.java).use {
        val stack = ProductionStack(context, reference16k(), speakSeconds, sourceLanguage, targetLanguage, repeatInput)
        report.put("reference_samples_16k", stack.reference.size)
        var createdProfileId: String? = null
        try {
            // Real 16 kHz voice profile through the real repository + real
            // speaker-encoder extraction (saveProfile validates via extractor).
            val saveResult = stack.voiceRepo.saveProfile("api2-e2e-121", stack.reference)
            val profile = when (saveResult) {
                is VoiceProfileRepository.SaveResult.Ok -> saveResult.profile
                is VoiceProfileRepository.SaveResult.Rejected ->
                    throw AssertionError("real reference rejected: ${saveResult.reason}")
                is VoiceProfileRepository.SaveResult.Failed ->
                    throw AssertionError("profile save failed: ${saveResult.error}")
            }
            report.put("voice_profile", JSONObject()
                .put("id", profile.id).put("format", profile.format))
            createdProfileId = profile.id

            runOneTurn(stack, profile.id, report, "turn1")

            if (case == "restart" || case == "cancel") {
                // stop -> restart on the same production stack (new session id)
                stack.start(profile.id)
                assertTrue("restart must mint a new session id",
                    stack.sessionId != report.getJSONObject("turn1").getLong("session_id"))
                if (case == "restart") {
                    stack.playback.resetRecording()
                    val turn = awaitTurn(stack, 420_000L) {
                        it.status in setOf(TurnStatus.COMPLETE, TurnStatus.FAILED,
                            TurnStatus.DROPPED)
                    }
                    assertTrue("restarted turn must COMPLETE, was ${turn.status} (${turn.problem})",
                        turn.status == TurnStatus.COMPLETE)
                    report.put("turn2_after_restart", turnJson(turn, stack.playback))
                    stack.controller.stop()
                    awaitIdle(stack)
                } else {
                    // cancel: stop while a turn is synthesizing or playing
                    withTimeout(420_000) {
                        stack.controller.snapshot.filter { snap ->
                            snap.turns.any { it.sessionId == stack.sessionId &&
                                (it.status == TurnStatus.SYNTHESIZING ||
                                    it.status == TurnStatus.PLAYING) }
                        }.first()
                    }
                    stack.controller.stop()
                    val cancelled = awaitTurn(stack, 60_000L) {
                        it.status in setOf(TurnStatus.CANCELLED, TurnStatus.FAILED,
                            TurnStatus.COMPLETE, TurnStatus.DROPPED)
                    }
                    report.put("cancelled_turn", turnJson(cancelled, stack.playback))
                    assertTrue("turn must not COMPLETE after stop",
                        cancelled.status != TurnStatus.COMPLETE)
                    awaitIdle(stack)
                }
            }
            File(outDir(), "api2_e2e_$case.json").writeText(report.toString(2))
        } finally {
            stack.controller.stop()
            stack.controller.close()
            stack.playback.release()
            stack.manager.releaseAll()
            createdProfileId?.let { stack.voiceRepo.deleteProfile(it).getOrThrow() }
        }
        }
    }
}
