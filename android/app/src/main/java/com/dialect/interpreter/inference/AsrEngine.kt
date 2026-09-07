package com.dialect.interpreter.inference

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Display-language-name → code mapping shared by ASR and TTS token resolution.
 */
object LanguageCodes {
    fun normalize(lang: String): String? {
        val normalized = lang.trim().lowercase()
        return when (normalized) {
            "chinese", "中文", "mandarin", "普通话" -> "zh"
            "english", "en" -> "en"
            "japanese", "日本語", "jp" -> "ja"
            "korean", "한국어", "kr" -> "ko"
            "german", "deutsch", "de" -> "de"
            "french", "français", "fr" -> "fr"
            "russian", "русский", "ru" -> "ru"
            "portuguese", "português", "pt" -> "pt"
            "spanish", "español", "es" -> "es"
            "italian", "italiano", "it" -> "it"
            else -> if (normalized.length == 2) normalized else null
        }
    }

    /**
     * Qwen3-ASR canonical language names (ASR lane only — TTS keeps [normalize]).
     *
     * Upstream concatenates the hint verbatim into the prompt:
     * `Encode("language " + language)` → `... language <NAME><asr_text>`
     * (sherpa-onnx @917bed95 offline-recognizer-qwen3-asr-impl.cc:476-487).
     * The official sherpa example and the Qwen3-ASR model card use full English
     * names ("Korean, Chinese, English"), NOT ISO codes; "language zh" would
     * inject an out-of-distribution prompt. Unmappable hints are dropped
     * (auto language id) instead of polluting the prompt.
     */
    fun canonicalAsrLanguage(lang: String?): String? {
        if (lang.isNullOrBlank()) return null
        return when (lang.trim().lowercase()) {
            "chinese", "中文", "mandarin", "普通话", "zh" -> "Chinese"
            "english", "en" -> "English"
            "cantonese", "粤语", "yue" -> "Cantonese"
            "arabic", "ar" -> "Arabic"
            "german", "deutsch", "de" -> "German"
            "french", "français", "fr" -> "French"
            "spanish", "español", "es" -> "Spanish"
            "portuguese", "português", "pt" -> "Portuguese"
            "indonesian", "id" -> "Indonesian"
            "italian", "italiano", "it" -> "Italian"
            "korean", "한국어", "kr", "ko" -> "Korean"
            "russian", "русский", "ru" -> "Russian"
            "thai", "th" -> "Thai"
            "vietnamese", "vi" -> "Vietnamese"
            "japanese", "日本語", "jp", "ja" -> "Japanese"
            "turkish", "tr" -> "Turkish"
            "hindi", "hi" -> "Hindi"
            "malay", "ms" -> "Malay"
            "dutch", "nl" -> "Dutch"
            "swedish", "sv" -> "Swedish"
            "danish", "da" -> "Danish"
            "finnish", "fi" -> "Finnish"
            "polish", "pl" -> "Polish"
            "czech", "cs" -> "Czech"
            "filipino", "fil" -> "Filipino"
            "persian", "fa" -> "Persian"
            "greek", "el" -> "Greek"
            "hungarian", "hu" -> "Hungarian"
            "macedonian", "mk" -> "Macedonian"
            "romanian", "ro" -> "Romanian"
            else -> null
        }
    }
}

interface SpeechRecognizer {
    suspend fun load()

    /**
     * [sampleRate] is the TRUE rate of [audioData] — sherpa owns resampling
     * (anti-aliased LinearResample, offline-stream.cc:139-156). Mislabeling a
     * 44.1 kHz clip as 16 kHz silently produces garbage transcripts, so the
     * rate is explicit at every call site.
     */
    suspend fun transcribe(
        audioData: FloatArray,
        language: String?,
        sampleRate: Int = SAMPLE_RATE,
    ): AsrEngine.TranscriptionResult
    /** Suspends until in-flight work and native disposal finish, without blocking Main. */
    suspend fun release()

    companion object {
        /** Capture rate of the production pipeline (AudioRecorder). */
        const val SAMPLE_RATE = 16000
    }
}

/**
 * ASR engine: sherpa-onnx Qwen3-ASR-0.6B INT8 (official OfflineRecognizer).
 *
 * Replaces the handwritten 80-mel / INT4 / greedy-piece path. Audio is PCM
 * float; sherpa owns feature extraction (128-dim) and the KV-cache decoder.
 * Clips longer than [SEGMENT_TRIGGER_S] are split so each piece stays under
 * the default KV 512 budget (~13 audio tokens/s).
 */
class AsrEngine(private val modelManager: OnnxModelManager) : SpeechRecognizer {

    companion object {
        private const val TAG = "AsrEngine"
        const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 128
        private const val MAX_TOTAL_LEN = 512
        private const val MAX_NEW_TOKENS = 192
        private const val SEGMENT_TRIGGER_S = 36.0
        private const val MAX_SEGMENT_S = 20.0
        private const val CONV_FRONTEND = "conv_frontend.onnx"
        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.int8.onnx"
    }

    // A suspending mutex serializes load, decode and disposal. Waiting callers
    // yield their thread; native calls run only on a worker dispatcher.
    private val nativeMutex = Mutex()
    private val releasedGate = AtomicBoolean(false)
    private var recognizer: OfflineRecognizer? = null
    private var isLoaded = false

    data class TranscriptionResult(
        val text: String,
        val language: String,
        val confidence: Float = 0f,
        val durationMs: Long = 0,
        /** Pieces actually decoded (1 = whole clip, >1 = KV-budget split). */
        val segmentCount: Int = 1,
    )

    override suspend fun load() {
        withContext(Dispatchers.IO) {
            nativeMutex.withLock {
                // A load queued after release can enter only after disposal.
                if (isLoaded) return@withLock
                // An idempotent load must not reopen a concurrently published
                // release gate while the existing recognizer is draining.
                releasedGate.set(false)
                val t0 = System.nanoTime()
                val root = File(modelManager.getModelsDir(), OnnxModelManager.ASR_DIR)
                val conv = File(root, CONV_FRONTEND)
                val encoder = File(root, ENCODER)
                val decoder = File(root, DECODER)
                val tokenizer = File(root, "tokenizer")
                val missing = buildList {
                    if (!conv.isFile) add(CONV_FRONTEND)
                    if (!encoder.isFile) add(ENCODER)
                    if (!decoder.isFile) add(DECODER)
                    if (!tokenizer.isDirectory) add("tokenizer/")
                }
                require(missing.isEmpty()) {
                    "ASR bundle missing $missing under ${root.absolutePath}"
                }

                val threads = min(Runtime.getRuntime().availableProcessors(), 4)
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = FEATURE_DIM,
                    ),
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = conv.absolutePath,
                            encoder = encoder.absolutePath,
                            decoder = decoder.absolutePath,
                            tokenizer = tokenizer.absolutePath,
                            maxTotalLen = MAX_TOTAL_LEN,
                            maxNewTokens = MAX_NEW_TOKENS,
                        ),
                        tokens = "",
                        numThreads = threads,
                        provider = "cpu",
                        debug = false,
                    ),
                    decodingMethod = "greedy_search",
                )
                recognizer = OfflineRecognizer(assetManager = null, config = config)
                isLoaded = true
                Log.i(
                    TAG,
                    "ASR (sherpa-onnx qwen3) loaded in ${(System.nanoTime() - t0) / 1_000_000}ms"
                )
            }
        }
    }

    override suspend fun transcribe(
        audioData: FloatArray,
        language: String?,
        sampleRate: Int,
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        check(!releasedGate.get()) { "ASR released" }
        nativeMutex.withLock {
            ensureActive()
            check(!releasedGate.get()) { "ASR released" }
            check(isLoaded) { "ASR not loaded" }
            val rec = recognizer ?: error("ASR not loaded")
            val t0 = System.nanoTime()
            // Canonical full-name hint per upstream protocol; unmappable hints
            // are dropped (auto language id) instead of corrupting the prompt.
            val langHint = LanguageCodes.canonicalAsrLanguage(language)
            val pieces = boundSegments(audioData, sampleRate)
            val texts = ArrayList<String>(pieces.size)
            // Upstream Qwen3 impl fills only text/tokens — result.lang is
            // always empty (impl.cc:1097-1135). Report the model's actual
            // lang when a future patch provides it; otherwise honest unknown.
            // Never backfill from the user hint or a default like "Chinese".
            var detected = ""
            for (piece in pieces) {
                ensureActive()
                check(!releasedGate.get()) { "ASR released" }
                if (piece.isEmpty()) continue
                val stream = rec.createStream()
                try {
                    if (!langHint.isNullOrBlank()) {
                        stream.setOption("language", langHint)
                    }
                    stream.acceptWaveform(piece, sampleRate)
                    rec.decode(stream)
                    ensureActive()
                    check(!releasedGate.get()) { "ASR released" }
                    val result = rec.getResult(stream)
                    if (result.text.isNotBlank()) texts.add(result.text.trim())
                    if (detected.isBlank() && result.lang.isNotBlank()) {
                        detected = result.lang
                    }
                } finally {
                    stream.release()
                }
            }
            val text = texts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val lang = detected.ifBlank { "unknown" }
            Log.i(
                TAG,
                "Transcribed ${audioData.size} samples in ${elapsedMs}ms " +
                    "(${pieces.size} segment(s))"
            )
            TranscriptionResult(
                text = text,
                language = lang,
                durationMs = elapsedMs,
                segmentCount = maxOf(1, pieces.size),
            )
        }
    }

    override suspend fun release() = withContext(NonCancellable) {
        // Publish before waiting. Every release caller awaits the same lock;
        // idempotence never means returning before another disposal finishes.
        releasedGate.set(true)
        nativeMutex.withLock {
            withContext(Dispatchers.IO) {
                recognizer?.release()
                recognizer = null
                isLoaded = false
            }
        }
    }

    /** Split so each piece is ≤ MAX_SEGMENT_S when the clip exceeds the KV budget. */
    internal fun boundSegments(samples: FloatArray, sampleRate: Int): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val duration = samples.size.toDouble() / sampleRate
        if (duration <= SEGMENT_TRIGGER_S) return listOf(samples)
        val maxN = (MAX_SEGMENT_S * sampleRate).toInt().coerceAtLeast(1)
        val hop = (0.02 * sampleRate).toInt().coerceAtLeast(1)
        val out = ArrayList<FloatArray>()
        var pos = 0
        while (pos < samples.size) {
            val remain = samples.size - pos
            if (remain <= maxN) {
                out.add(samples.copyOfRange(pos, samples.size))
                break
            }
            val windowEnd = pos + maxN
            var bestI = windowEnd
            var bestE = Float.POSITIVE_INFINITY
            var i = pos + (maxN * 0.55).toInt()
            while (i < windowEnd) {
                val e = frameEnergy(samples, i, hop)
                if (e < bestE) {
                    bestE = e
                    bestI = i
                }
                i += hop
            }
            val cut = bestI.coerceIn(pos + hop, samples.size)
            out.add(samples.copyOfRange(pos, cut))
            pos = cut
        }
        return out
    }

    private fun frameEnergy(samples: FloatArray, start: Int, hop: Int): Float {
        val end = min(samples.size, start + hop)
        if (end <= start) return 0f
        var acc = 0.0
        for (i in start until end) {
            val v = samples[i].toDouble()
            acc += v * v
        }
        return (acc / (end - start)).toFloat()
    }
}
