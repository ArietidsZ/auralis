package com.dialect.interpreter

import android.content.Context
import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.AudioRecorder
import com.dialect.interpreter.data.DialectCatalogLoader
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.data.SettingsRepository
import com.dialect.interpreter.data.VoiceProfileRepository
import com.dialect.interpreter.inference.AsrEngine
import com.dialect.interpreter.inference.HyMtTranslationEngine
import com.dialect.interpreter.inference.OnnxModelManager
import com.dialect.interpreter.inference.PipelineOrchestrator
import com.dialect.interpreter.inference.TtsApi2Runtime
import com.dialect.interpreter.inference.SpeechSynthesizer
import com.dialect.interpreter.inference.TtsEngine
import com.dialect.interpreter.session.SessionController
import com.dialect.interpreter.session.VoiceProfileResolver
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped dependency container (manual DI, spec 02 R01).
 *
 * Ownership model (review items 1–2):
 *  - No app-level ONNX session cache. Every session controller and every
 *    runtime probe gets a dedicated [OnnxModelManager]; closing a controller
 *    can therefore never touch sessions of another controller.
 *  - Cloning resolves through the voice profile repository's speaker-encoder
 *    port, wired to A's [TtsEngine.extractSpeakerEmbedding] on a dedicated,
 *    mutex-serialized manager. Extraction failures return null — cloning is
 *    disabled, never a fabricated embedding.
 *  - Model readiness uses [ModelRuntimeProbe] (isolated load + release);
 *    verified files without a working runtime surface as RuntimeUnavailable.
 */
class AppContainer(private val context: Context) {

    val modelRepository: ModelRepository by lazy {
        ModelRepository(context, runtimeProbe = ModelRuntimeProbe(context))
    }

    val voiceProfileRepository: VoiceProfileRepository by lazy {
        VoiceProfileRepository(
            context,
            embeddingExtractor = { pcm, profileId -> extractEmbedding(pcm, profileId) },
        )
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    val dialectCatalogLoader: DialectCatalogLoader by lazy { DialectCatalogLoader(context) }

    /**
     * Dedicated manager + engine for speaker-embedding extraction. Isolated
     * from session controllers (own session cache), serialized by [embeddingMutex]
     * so the engine's internal cache/decoder is never used concurrently.
     */
    private val embeddingMutex = Mutex()
    private suspend fun extractEmbedding(referencePcm: FloatArray, profileId: String): FloatArray? =
        embeddingMutex.withLock {
            modelRepository.acquireForSession().use {
                val manager = OnnxModelManager(context)
                val embeddingEngine = TtsEngine(manager)
                try {
                    // Validate the reference, then load only the encoder; close before allowing
                    // model files to change again.
                    embeddingEngine.extractSpeakerEmbedding(referencePcm, profileId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Missing models / native runtime → cloning stays disabled.
                    null
                } finally {
                    try {
                        embeddingEngine.release()
                    } finally {
                        manager.releaseAll()
                    }
                }
            }
        }

    /**
     * Session factory (interface-A §2): a fresh controller per screen entry.
     * Each controller owns a dedicated [OnnxModelManager]; the orchestrator
     * loads models in start() and releases them in stop()/close().
     */
    fun createSessionController(): SessionController {
        val manager = OnnxModelManager(context) // per-controller: isolated sessions
        val recognizer = AsrEngine(manager)
        val voice = SessionVoiceSynthesis(TtsEngine(manager), recognizer, voiceProfileRepository)
        return PipelineOrchestrator(
            recognizer = recognizer,
            translator = HyMtTranslationEngine(
                modelFile = File(
                    manager.getModelsDir(),
                    "${OnnxModelManager.MT_DIR}/${OnnxModelManager.HY_MT_GGUF}"
                )
            ),
            synthesizer = voice,
            capture = AudioRecorder(context = context),
            playback = AudioPlayer(context),
            voiceProfiles = voice,
            acquireModelLease = modelRepository::acquireForSession,
        )
    }
}

/** Session-owned reference preparation. ASR is loaded before resolve() and
 * capture starts afterwards. Reference text never enters conversation state.
 */
internal class SessionVoiceSynthesis(
    private val engine: TtsEngine,
    private val recognizer: AsrEngine,
    private val profiles: VoiceProfileRepository,
) : SpeechSynthesizer, VoiceProfileResolver {
    private var prepared: TtsApi2Runtime.PreparedReference? = null

    override suspend fun resolve(profileId: String): FloatArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        prepared = null
        try {
            val reference = profiles.loadReference(profileId) ?: return@withContext null
            if (engine.supportsPreparedReference()) {
                val result = recognizer.transcribe(reference.pcm, language = null, sampleRate = reference.sampleRate)
                val text = result.text.trim()
                require(text.isNotEmpty()) { "Reference speech could not be transcribed" }
                engine.prepareReference(reference.pcm, reference.sampleRate, text).also { prepared = it }.embedding
            } else {
                engine.extractSpeakerEmbedding(reference.pcm, inputSampleRate = reference.sampleRate)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unusable reference disables speech while preserving real translation.
            prepared = null
            engine.release()
            null
        }
    }

    override suspend fun load() { engine.load() }

    override suspend fun synthesize(text: String, language: String, speakerEmbedding: FloatArray?): TtsEngine.SynthesisResult =
        prepared?.let { engine.synthesizePrepared(it, text, language) }
            ?: engine.synthesize(text, language, speakerEmbedding)

    override suspend fun synthesizeStream(text: String, language: String, speakerEmbedding: FloatArray?,
                                          onAudioChunk: suspend (FloatArray) -> Unit): TtsEngine.SynthesisResult {
        val reference = prepared
        if (reference != null) return engine.synthesizePrepared(reference, text, language, onAudioChunk)
        val result = engine.synthesize(text, language, speakerEmbedding)
        onAudioChunk(result.audioData)
        return result
    }

    override fun release() { prepared = null; engine.release() }
}
