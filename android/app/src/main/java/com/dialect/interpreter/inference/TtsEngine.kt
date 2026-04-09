package com.dialect.interpreter.inference

import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * TTS (Text-to-Speech) + Voice Cloning engine using Qwen3-TTS-12Hz-0.6B-Base ONNX model.
 *
 * Pipeline: Text + Speaker Embedding → Talker LM → Code Predictor → Vocoder → PCM Audio
 *
 * Sub-modules:
 * 1. Speaker Encoder (ECAPA-TDNN): Reference audio → speaker embedding vector
 * 2. Talker LM: Text tokens + speaker embedding → speech tokens (autoregressive)
 * 3. Code Predictor: Predict multi-codebook codes
 * 4. Vocoder: Speech codes → raw PCM waveform
 */
class TtsEngine(private val modelManager: OnnxModelManager) {

    companion object {
        private const val TAG = "TtsEngine"

        const val OUTPUT_SAMPLE_RATE = 24000  // Qwen3-TTS output sample rate
        const val SPEAKER_EMBEDDING_DIM = 192  // ECAPA-TDNN embedding dimension
        const val NUM_CODEBOOKS = 8
        private const val MIN_CODE_STEPS = 40
        private const val MAX_CODE_STEPS = 320
        private const val ESTIMATED_STEPS_PER_TOKEN = 2
        private const val TOKENIZER_RELATIVE_PATH = "tts/tokenizer/tokenizer.json"
    }

    private var speakerEncoderSession: OrtSession? = null
    private var talkerLmSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null
    private var isLoaded = false
    private val tokenizerJson = Json { ignoreUnknownKeys = true }
    private var tokenToId: Map<String, Long> = emptyMap()
    private var languageTokenIds: Map<String, Long> = emptyMap()
    private var defaultLanguageTokenId: Long? = null
    private var warnedMissingLanguageToken = false

    // Cached speaker embeddings for voice profiles
    private val speakerEmbeddingCache = mutableMapOf<String, FloatArray>()

    data class SynthesisResult(
        val audioData: FloatArray,       // PCM float audio
        val sampleRate: Int,
        val durationMs: Long,            // Audio duration
        val inferenceTimeMs: Long        // Processing time
    )

    /**
     * Load all TTS sub-modules.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        Log.i(TAG, "Loading TTS models...")
        val t0 = System.currentTimeMillis()

        try {
            tokenToId = loadTokenizerVocabulary()
            languageTokenIds = resolveLanguageTokenIds(tokenToId)
            defaultLanguageTokenId = languageTokenIds["zh"] ?: languageTokenIds.values.firstOrNull()
            speakerEncoderSession = modelManager.loadSession(
                OnnxModelManager.TTS_DIR,
                "speaker_encoder_int4.onnx"
            )
            talkerLmSession = modelManager.loadSession(
                OnnxModelManager.TTS_DIR,
                "talker_lm_int4.onnx"
            )
            vocoderSession = modelManager.loadSession(
                OnnxModelManager.TTS_DIR,
                "vocoder_int4.onnx"
            )
            isLoaded = true
            Log.i(TAG, "TTS loaded in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TTS: ${e.message}", e)
            throw e
        }
    }

    private fun loadTokenizerVocabulary(): Map<String, Long> {
        val tokenizerFile = File(modelManager.getModelsDir(), TOKENIZER_RELATIVE_PATH)
        if (!tokenizerFile.exists()) {
            Log.w(TAG, "Tokenizer not found: ${tokenizerFile.absolutePath}")
            return emptyMap()
        }

        return runCatching {
            val root = tokenizerJson.parseToJsonElement(tokenizerFile.readText()).jsonObject
            val vocab = root["model"]
                ?.jsonObject
                ?.get("vocab")
                ?.jsonObject
                ?: root["vocab"]?.jsonObject
                ?: return@runCatching emptyMap<String, Long>()

            val merged = linkedMapOf<String, Long>()

            vocab.forEach { (token, idElement) ->
                val id = idElement.jsonPrimitive.longOrNull ?: return@forEach
                merged[token] = id
            }

            val addedTokens = root["added_tokens"]?.jsonArray?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val token = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                token to id
            }.orEmpty()

            for ((token, id) in addedTokens) {
                merged[token] = id
            }

            merged
        }.onFailure {
            Log.w(TAG, "Failed to parse TTS tokenizer: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun resolveLanguageTokenIds(pieceToId: Map<String, Long>): Map<String, Long> {
        val aliases = mapOf(
            "zh" to listOf("<|zh|>", "<|zh-cn|>", "<|cmn|>", "<|chinese|>"),
            "en" to listOf("<|en|>", "<|en-us|>", "<|english|>"),
            "ja" to listOf("<|ja|>", "<|jp|>", "<|japanese|>"),
            "ko" to listOf("<|ko|>", "<|kr|>", "<|korean|>"),
            "de" to listOf("<|de|>", "<|german|>"),
            "fr" to listOf("<|fr|>", "<|french|>")
        )

        return aliases.mapNotNull { (code, candidates) ->
            findLanguageTokenId(code, candidates, pieceToId)?.let { code to it }
        }.toMap()
    }

    private fun findLanguageTokenId(
        languageCode: String,
        candidates: List<String>,
        pieceToId: Map<String, Long>
    ): Long? {
        for (candidate in candidates) {
            pieceToId[candidate]?.let { return it }
        }

        return pieceToId.entries.firstOrNull { (piece, _) ->
            normalizeLanguageTag(piece) == languageCode
        }?.value
    }

    private fun normalizeLanguageTag(piece: String): String? {
        val token = piece.lowercase()
        if ("|" !in token && "lang" !in token) return null

        val regex = Regex("([a-z]{2})(?:[-_]([a-z]{2}))?")
        val match = regex.find(token) ?: return null
        return match.groupValues[1]
    }

    /**
     * Extract speaker embedding from reference audio.
     * This embedding captures the speaker's voice characteristics.
     *
     * @param referenceAudio PCM float audio at 16kHz
     * @param profileId Optional cache key for the voice profile
     * @return Speaker embedding vector
     */
    suspend fun extractSpeakerEmbedding(
        referenceAudio: FloatArray,
        profileId: String? = null
    ): FloatArray = withContext(Dispatchers.Default) {
        // Check cache first
        profileId?.let { id ->
            speakerEmbeddingCache[id]?.let { return@withContext it }
        }

        check(isLoaded) { "TTS model not loaded" }
        val encoder = speakerEncoderSession ?: throw IllegalStateException("Speaker encoder not loaded")

        Log.i(TAG, "Extracting speaker embedding from ${referenceAudio.size} samples")

        // Normalize audio
        val normalizedAudio = normalizeAudio(referenceAudio)

        val inputTensor = modelManager.createTensor(
            normalizedAudio,
            longArrayOf(1, normalizedAudio.size.toLong())
        )

        val normalizedEmbedding = try {
            val result = encoder.run(mapOf("waveform" to inputTensor))
            try {
                val embedding = parseFirstFloatArray(result[0].value)
                    ?: throw IllegalStateException("Unexpected speaker embedding output type: ${result[0].value?.javaClass?.name}")

                // L2 normalize the embedding (guard against near-zero norm)
                val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
                if (norm > 1e-6f) {
                    FloatArray(embedding.size) { embedding[it] / norm }
                } else {
                    embedding.copyOf()
                }
            } finally {
                result.close()
            }
        } finally {
            inputTensor.close()
        }

        // Cache
        profileId?.let { speakerEmbeddingCache[it] = normalizedEmbedding }

        Log.i(TAG, "Speaker embedding extracted: dim=${normalizedEmbedding.size}")
        normalizedEmbedding
    }

    /**
     * Synthesize speech with voice cloning.
     *
     * @param text Text to speak
     * @param language Target language (e.g., "Chinese", "English")
     * @param speakerEmbedding Pre-extracted speaker embedding
     * @return Synthesized audio
     */
    suspend fun synthesize(
        text: String,
        language: String,
        speakerEmbedding: FloatArray
    ): SynthesisResult = withContext(Dispatchers.Default) {
        check(isLoaded) { "TTS model not loaded" }

        val t0 = System.currentTimeMillis()
        Log.i(TAG, "Synthesizing: [$language] $text")

        // 1. Tokenize text
        val textTokens = tokenizeText(text, language)

        // 2. Run Talker LM: generate speech tokens
        val speechCodes = runTalkerLm(textTokens, speakerEmbedding)

        // 3. Run Vocoder: convert codes to waveform
        val waveform = runVocoder(speechCodes)

        val inferenceTime = System.currentTimeMillis() - t0
        val audioDuration = (waveform.size.toLong() * 1000) / OUTPUT_SAMPLE_RATE
        val rtf = if (audioDuration > 0) inferenceTime.toFloat() / audioDuration else 0f

        Log.i(TAG, "Synthesis complete: ${audioDuration}ms audio in ${inferenceTime}ms " +
                "(RTF=$rtf)")

        SynthesisResult(
            audioData = waveform,
            sampleRate = OUTPUT_SAMPLE_RATE,
            durationMs = audioDuration,
            inferenceTimeMs = inferenceTime
        )
    }

    /**
     * Synthesize with voice cloning from reference audio (convenience method).
     */
    suspend fun synthesizeWithClone(
        text: String,
        language: String,
        referenceAudio: FloatArray,
        profileId: String? = null
    ): SynthesisResult {
        val embedding = extractSpeakerEmbedding(referenceAudio, profileId)
        return synthesize(text, language, embedding)
    }

    /**
     * Tokenize text for the TTS model.
     */
    private fun tokenizeText(text: String, language: String): LongArray {
        val tokens = mutableListOf<Long>()

        // BOS + language token
        tokens.add(1L) // BOS
        val languageToken = languageTokenId(language)
        if (languageToken != null) {
            tokens.add(languageToken)
        } else if (!warnedMissingLanguageToken) {
            warnedMissingLanguageToken = true
            Log.w(TAG, "No tokenizer language token found, synthesizing without explicit language token")
        }

        for (char in text.trim()) {
            if (char.isWhitespace()) {
                tokenToId["▁"]?.let { tokens.add(it) }
                continue
            }

            val asString = char.toString()
            val tokenId = tokenToId[asString]
                ?: tokenToId["▁$asString"]
                ?: tokenToId[asString.lowercase(Locale.getDefault())]

            tokens.add(tokenId ?: (char.code.toLong() + 200L))
        }

        tokens.add(2L) // EOS

        return tokens.toLongArray()
    }

    private fun languageTokenId(language: String): Long? {
        val normalized = language.lowercase(Locale.getDefault())

        val code = when (normalized) {
            "chinese", "中文", "mandarin", "普通话" -> "zh"
            "english", "en" -> "en"
            "japanese", "日本語", "jp" -> "ja"
            "korean", "한국어", "kr" -> "ko"
            "german", "deutsch" -> "de"
            "french", "français" -> "fr"
            else -> "zh"
        }

        return languageTokenIds[code] ?: defaultLanguageTokenId
    }

    /**
     * Run Talker LM to generate multi-codebook speech codes.
     */
    private fun runTalkerLm(textTokens: LongArray, speakerEmbedding: FloatArray): Array<LongArray> {
        val lm = talkerLmSession ?: throw IllegalStateException("Talker LM not loaded")

        val inputIds = modelManager.createLongTensor(textTokens, longArrayOf(1, textTokens.size.toLong()))
        val embeddingTensor = modelManager.createTensor(
            speakerEmbedding,
            longArrayOf(1, speakerEmbedding.size.toLong())
        )

        // Run the LM
        val result = try {
            lm.run(mapOf(
                "input_ids" to inputIds,
                "speaker_embedding" to embeddingTensor
            ))
        } finally {
            inputIds.close()
            embeddingTensor.close()
        }

        val codes = try {
            parseSpeechCodes(result[0].value, textTokens.size)
        } finally {
            result.close()
        }

        val numSteps = codes.firstOrNull()?.size ?: 0

        Log.i(TAG, "Generated ${numSteps} speech code steps across $NUM_CODEBOOKS codebooks")
        return codes
    }

    private fun parseSpeechCodes(outputValue: Any?, textTokenCount: Int): Array<LongArray> {
        val fallbackSteps = (textTokenCount * ESTIMATED_STEPS_PER_TOKEN)
            .coerceIn(MIN_CODE_STEPS, MAX_CODE_STEPS)
        val fallback = Array(NUM_CODEBOOKS) { LongArray(fallbackSteps) { 0L } }

        val root = outputValue as? Array<*> ?: return fallback
        val batch0 = root.firstOrNull() as? Array<*> ?: return fallback
        val first = batch0.firstOrNull() ?: return fallback

        return when (first) {
            is FloatArray -> decode3dLogits(batch0, fallbackSteps)
            is Array<*> -> decode4dLogits(batch0, fallbackSteps)
            else -> fallback
        }
    }

    private fun decode3dLogits(batch0: Array<*>, fallbackSteps: Int): Array<LongArray> {
        val logitsByStep = batch0.mapNotNull { it as? FloatArray }
        if (logitsByStep.isEmpty()) {
            return Array(NUM_CODEBOOKS) { LongArray(fallbackSteps) { 0L } }
        }

        val steps = logitsByStep.size.coerceIn(MIN_CODE_STEPS, MAX_CODE_STEPS)
        val outputSteps = if (steps > 0) steps else fallbackSteps
        val codes = Array(NUM_CODEBOOKS) { LongArray(outputSteps) { 0L } }

        for (step in 0 until outputSteps) {
            val logits = logitsByStep.getOrNull(step) ?: continue
            val token = argmax(logits)
            for (codebook in 0 until NUM_CODEBOOKS) {
                codes[codebook][step] = token
            }
        }

        return codes
    }

    private fun decode4dLogits(batch0: Array<*>, fallbackSteps: Int): Array<LongArray> {
        val logitsByCodebook = batch0.mapNotNull { codebookAny ->
            val codebookSteps = codebookAny as? Array<*> ?: return@mapNotNull null
            codebookSteps.mapNotNull { it as? FloatArray }
        }
        if (logitsByCodebook.isEmpty()) {
            return Array(NUM_CODEBOOKS) { LongArray(fallbackSteps) { 0L } }
        }

        val steps = logitsByCodebook.minOf { it.size }.coerceIn(MIN_CODE_STEPS, MAX_CODE_STEPS)
        val outputSteps = if (steps > 0) steps else fallbackSteps
        val codes = Array(NUM_CODEBOOKS) { LongArray(outputSteps) { 0L } }

        for (codebook in 0 until NUM_CODEBOOKS) {
            val stepLogits = logitsByCodebook.getOrNull(codebook)
                ?: logitsByCodebook.first()

            for (step in 0 until outputSteps) {
                val logits = stepLogits.getOrNull(step) ?: continue
                codes[codebook][step] = argmax(logits)
            }
        }

        return codes
    }

    private fun parseFirstFloatArray(value: Any?): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> value.firstOrNull() as? FloatArray
            else -> null
        }
    }

    private fun argmax(values: FloatArray): Long {
        if (values.isEmpty()) return 0L

        var bestIndex = 0
        var bestValue = values[0]
        for (index in 1 until values.size) {
            val value = values[index]
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex.toLong()
    }

    /**
     * Run vocoder to convert speech codes to waveform.
     */
    private fun runVocoder(codes: Array<LongArray>): FloatArray {
        val vocoder = vocoderSession ?: throw IllegalStateException("Vocoder not loaded")

        val numSteps = codes[0].size
        val flatCodes = LongArray(NUM_CODEBOOKS * numSteps)
        for (cb in codes.indices) {
            codes[cb].copyInto(flatCodes, cb * numSteps)
        }

        val codeTensor = modelManager.createLongTensor(
            flatCodes,
            longArrayOf(1, NUM_CODEBOOKS.toLong(), numSteps.toLong())
        )

        val waveform = try {
            val result = vocoder.run(mapOf("codes" to codeTensor))
            try {
                parseFirstFloatArray(result[0].value)
                    ?: throw IllegalStateException("Unexpected vocoder output type: ${result[0].value?.javaClass?.name}")
            } finally {
                result.close()
            }
        } finally {
            codeTensor.close()
        }

        Log.i(TAG, "Vocoder output: ${waveform.size} samples at ${OUTPUT_SAMPLE_RATE}Hz")
        return waveform
    }

    /**
     * Normalize audio to [-1, 1] range.
     */
    private fun normalizeAudio(audio: FloatArray): FloatArray {
        val maxAbs = audio.maxOfOrNull { kotlin.math.abs(it) } ?: 1f
        return if (maxAbs > 0f) FloatArray(audio.size) { audio[it] / maxAbs } else audio
    }

    /**
     * Clear the speaker embedding cache.
     */
    fun clearCache() {
        speakerEmbeddingCache.clear()
    }

    /**
     * Release all resources.
     */
    fun release() {
        isLoaded = false
        speakerEncoderSession = null
        talkerLmSession = null
        vocoderSession = null
        clearCache()
        modelManager.release("${OnnxModelManager.TTS_DIR}/speaker_encoder_int4.onnx")
        modelManager.release("${OnnxModelManager.TTS_DIR}/talker_lm_int4.onnx")
        modelManager.release("${OnnxModelManager.TTS_DIR}/vocoder_int4.onnx")
        Log.i(TAG, "TTS engine released")
    }
}
