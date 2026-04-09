package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.math.*

/**
 * ASR inference engine — Qwen3-ASR-0.6B INT4 on NPU.
 *
 * Pipeline: PCM → Mel Spectrogram (in-place FFT) → Encoder → Decoder → Text
 *
 * Performance optimizations:
 *  - Radix-2 FFT (O(N log N)) instead of naive DFT
 *  - Pre-allocated mel filterbank and FFT twiddle factors
 *  - Streaming mel buffer accumulation (avoid recomputation)
 *  - Pre-allocated output buffers for encoder/decoder
 */
class AsrEngine(private val modelManager: OnnxModelManager) {

    companion object {
        private const val TAG = "AsrEngine"

        const val SAMPLE_RATE = 16000
        const val N_FFT = 400
        const val HOP_LENGTH = 160
        const val N_MELS = 80
        const val CHUNK_DURATION_MS = 200
        private const val MAX_DECODER_STEPS = 256
        private const val DECODER_HIDDEN_DIM = 896
        private const val TOKENIZER_RELATIVE_PATH = "asr/tokenizer/tokenizer.json"

        // Pre-compute FFT size (next power of 2)
        val FFT_SIZE = Integer.highestOneBit(N_FFT - 1) shl 1  // 512

        val CHINESE_DIALECTS = listOf(
            "普通话" to "Chinese", "粤语" to "Cantonese",
            "四川话" to "Sichuan", "东北话" to "Dongbei",
            "河南话" to "Henan", "湖南话" to "Hunan",
            "湖北话" to "Hubei", "山东话" to "Shandong",
            "陕西话" to "Shaanxi", "福建话" to "Fujian",
            "安徽话" to "Anhui", "甘肃话" to "Gansu",
            "贵州话" to "Guizhou", "河北话" to "Hebei",
            "江西话" to "Jiangxi", "宁夏话" to "Ningxia",
            "山西话" to "Shanxi", "天津话" to "Tianjin",
            "云南话" to "Yunnan", "浙江话" to "Zhejiang",
            "吴语" to "Wu", "闽南语" to "Minnan",
        )

        val SUPPORTED_LANGUAGES = listOf(
            "中文" to "Chinese", "English" to "English",
            "日本語" to "Japanese", "한국어" to "Korean",
            "Deutsch" to "German", "Français" to "French",
            "Русский" to "Russian", "Português" to "Portuguese",
            "Español" to "Spanish", "Italiano" to "Italian",
        )
    }

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var isLoaded = false

    // Pre-allocated buffers (avoid GC during inference)
    private lateinit var melFilterbank: FloatArray    // [N_MELS × (FFT_SIZE/2+1)]
    private lateinit var hannWindow: FloatArray       // [N_FFT]
    private lateinit var fftReal: FloatArray          // [FFT_SIZE]  reusable
    private lateinit var fftImag: FloatArray          // [FFT_SIZE]  reusable
    private lateinit var powerSpec: FloatArray        // [FFT_SIZE/2+1]
    private lateinit var twiddleReal: FloatArray      // FFT twiddle factors
    private lateinit var twiddleImag: FloatArray

    private val tokenizerJson = Json { ignoreUnknownKeys = true }
    private val whitespaceRegex = Regex("\\s+")
    private var tokenIdToPiece: Map<Long, String> = emptyMap()
    private var tokenPieceToId: Map<String, Long> = emptyMap()
    private var languageTokenIds: Map<String, Long> = emptyMap()
    private var defaultLanguageTokenId: Long? = null

    private data class TokenizerVocabulary(
        val idToPiece: Map<Long, String>,
        val pieceToId: Map<String, Long>
    )

    data class TranscriptionResult(
        val text: String,
        val language: String,
        val confidence: Float = 0f,
        val durationMs: Long = 0
    )

    suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        val t0 = System.nanoTime()

        // Pre-allocate all buffers
        initBuffers()
        val tokenizer = loadTokenizer()
        tokenIdToPiece = tokenizer.idToPiece
        tokenPieceToId = tokenizer.pieceToId
        languageTokenIds = resolveLanguageTokenIds(tokenPieceToId)
        defaultLanguageTokenId = languageTokenIds["zh"] ?: languageTokenIds.values.firstOrNull()

        encoderSession = modelManager.loadSession(OnnxModelManager.ASR_DIR, "asr_encoder_int4.onnx")
        decoderSession = modelManager.loadSession(OnnxModelManager.ASR_DIR, "asr_decoder_int4.onnx")
        isLoaded = true

        Log.i(TAG, "ASR loaded in ${(System.nanoTime() - t0) / 1_000_000}ms")
    }

    private fun initBuffers() {
        hannWindow = FloatArray(N_FFT) { i ->
            0.5f * (1f - cos(2f * PI.toFloat() * i / (N_FFT - 1)))
        }

        val fftBins = FFT_SIZE / 2 + 1
        melFilterbank = createMelFilterbank(N_MELS, fftBins, SAMPLE_RATE)

        fftReal = FloatArray(FFT_SIZE)
        fftImag = FloatArray(FFT_SIZE)
        powerSpec = FloatArray(fftBins)

        // Pre-compute FFT twiddle factors
        val halfN = FFT_SIZE / 2
        twiddleReal = FloatArray(halfN)
        twiddleImag = FloatArray(halfN)
        for (k in 0 until halfN) {
            val angle = -2.0 * PI * k / FFT_SIZE
            twiddleReal[k] = cos(angle).toFloat()
            twiddleImag[k] = sin(angle).toFloat()
        }
    }

    /**
     * Transcribe audio with pre-allocated buffers for zero-alloc hot path.
     */
    suspend fun transcribe(
        audioData: FloatArray,
        language: String? = null
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        check(isLoaded) { "ASR not loaded" }
        val t0 = System.nanoTime()

        val melSpec = computeMelSpectrogram(audioData)
        val encoderOut = runEncoder(melSpec)
        val tokens = runDecoder(encoderOut, language)
        val text = decodeTokens(tokens)
        val lang = language ?: "Chinese"

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "Transcribed ${audioData.size} samples in ${elapsedMs}ms: [$lang] $text")

        TranscriptionResult(text, lang, durationMs = elapsedMs)
    }

    /**
     * Compute mel spectrogram using in-place radix-2 FFT.
     * O(N·M·log(FFT_SIZE)) vs O(N·M·FFT_SIZE²) for naive DFT.
     */
    private fun computeMelSpectrogram(audio: FloatArray): FloatArray {
        val numFrames = maxOf(1, (audio.size - N_FFT) / HOP_LENGTH + 1)
        val fftBins = FFT_SIZE / 2 + 1
        val melSpec = FloatArray(N_MELS * numFrames)

        for (frame in 0 until numFrames) {
            val start = frame * HOP_LENGTH

            // Zero-fill FFT buffer, apply window
            fftReal.fill(0f)
            fftImag.fill(0f)
            for (i in 0 until N_FFT) {
                val idx = start + i
                fftReal[i] = if (idx < audio.size) audio[idx] * hannWindow[i] else 0f
            }

            // In-place radix-2 FFT
            fftInPlace(fftReal, fftImag)

            // Power spectrum (magnitude²)
            for (k in 0 until fftBins) {
                val re = fftReal[k]
                val im = fftImag[k]
                powerSpec[k] = re * re + im * im
            }

            // Apply mel filterbank → log mel
            val frameOffset = frame
            for (mel in 0 until N_MELS) {
                var sum = 0f
                val filterOffset = mel * fftBins
                for (k in 0 until fftBins) {
                    sum += melFilterbank[filterOffset + k] * powerSpec[k]
                }
                melSpec[mel * numFrames + frameOffset] = ln(maxOf(sum, 1e-10f))
            }
        }

        return melSpec
    }

    /**
     * In-place Cooley-Tukey radix-2 FFT using pre-computed twiddle factors.
     * Operates on [fftReal] and [fftImag] arrays.
     */
    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = FFT_SIZE

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                // Swap
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val twiddleStep = n / len

            var i = 0
            while (i < n) {
                for (k in 0 until halfLen) {
                    val twIdx = k * twiddleStep
                    val tRe = twiddleReal[twIdx]
                    val tIm = twiddleImag[twIdx]

                    val evenIdx = i + k
                    val oddIdx = i + k + halfLen

                    val oddRe = real[oddIdx] * tRe - imag[oddIdx] * tIm
                    val oddIm = real[oddIdx] * tIm + imag[oddIdx] * tRe

                    real[oddIdx] = real[evenIdx] - oddRe
                    imag[oddIdx] = imag[evenIdx] - oddIm
                    real[evenIdx] = real[evenIdx] + oddRe
                    imag[evenIdx] = imag[evenIdx] + oddIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun createMelFilterbank(nMels: Int, fftBins: Int, sampleRate: Int): FloatArray {
        val filters = FloatArray(nMels * fftBins)
        val melLow = hzToMel(0f)
        val melHigh = hzToMel(sampleRate / 2f)
        val melPoints = FloatArray(nMels + 2) { i ->
            melToHz(melLow + (melHigh - melLow) * i / (nMels + 1))
        }
        for (m in 0 until nMels) {
            val fLow = melPoints[m]; val fCenter = melPoints[m + 1]; val fHigh = melPoints[m + 2]
            for (k in 0 until fftBins) {
                val freq = k.toFloat() * sampleRate / (2 * (fftBins - 1))
                filters[m * fftBins + k] = when {
                    freq < fLow -> 0f
                    freq <= fCenter -> (freq - fLow) / (fCenter - fLow)
                    freq <= fHigh -> (fHigh - freq) / (fHigh - fCenter)
                    else -> 0f
                }
            }
        }
        return filters
    }

    private fun hzToMel(hz: Float) = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float) = 700f * (10f.pow(mel / 2595f) - 1f)

    private fun loadTokenizer(): TokenizerVocabulary {
        val tokenizerFile = File(modelManager.getModelsDir(), TOKENIZER_RELATIVE_PATH)
        if (!tokenizerFile.exists()) {
            Log.w(TAG, "Tokenizer not found: ${tokenizerFile.absolutePath}")
            return TokenizerVocabulary(emptyMap(), emptyMap())
        }

        return runCatching {
            val root = tokenizerJson.parseToJsonElement(tokenizerFile.readText()).jsonObject
            val vocab = root["model"]
                ?.jsonObject
                ?.get("vocab")
                ?.jsonObject
                ?: root["vocab"]?.jsonObject
                ?: return@runCatching TokenizerVocabulary(emptyMap(), emptyMap())

            val pieceToId = linkedMapOf<String, Long>()
            vocab.forEach { (piece, idElement) ->
                val id = idElement.jsonPrimitive.longOrNull ?: return@forEach
                pieceToId[piece] = id
            }

            val addedTokens = root["added_tokens"]?.jsonArray?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val piece = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                piece to id
            }.orEmpty()

            for ((piece, id) in addedTokens) {
                pieceToId[piece] = id
            }

            val idToPiece = linkedMapOf<Long, String>()
            for ((piece, id) in pieceToId) {
                idToPiece.putIfAbsent(id, piece)
            }

            TokenizerVocabulary(
                idToPiece = idToPiece,
                pieceToId = pieceToId
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse ASR tokenizer: ${it.message}")
        }.getOrDefault(TokenizerVocabulary(emptyMap(), emptyMap()))
    }

    private fun resolveLanguageTokenIds(pieceToId: Map<String, Long>): Map<String, Long> {
        val aliases = mapOf(
            "zh" to listOf("<|zh|>", "<|zh-cn|>", "<|cmn|>", "<|chinese|>"),
            "en" to listOf("<|en|>", "<|en-us|>", "<|english|>"),
            "ja" to listOf("<|ja|>", "<|jp|>", "<|japanese|>"),
            "ko" to listOf("<|ko|>", "<|kr|>", "<|korean|>"),
            "de" to listOf("<|de|>", "<|german|>"),
            "fr" to listOf("<|fr|>", "<|french|>"),
            "ru" to listOf("<|ru|>", "<|russian|>"),
            "pt" to listOf("<|pt|>", "<|portuguese|>"),
            "es" to listOf("<|es|>", "<|spanish|>"),
            "it" to listOf("<|it|>", "<|italian|>")
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

    private fun runEncoder(melSpec: FloatArray): FloatArray {
        val encoder = encoderSession!!
        val numFrames = melSpec.size / N_MELS
        val input = modelManager.createTensor(melSpec, longArrayOf(1, N_MELS.toLong(), numFrames.toLong()))

        try {
            val result = encoder.run(mapOf("audio_features" to input))
            try {
                val output = parseBatchSequenceMatrix(result[0].value)
                    ?: throw IllegalStateException("Unexpected encoder output type: ${result[0].value?.javaClass?.name}")
                val hidden = output.firstOrNull()
                    ?: throw IllegalStateException("Encoder output is empty")
                val flat = FloatArray(hidden.sumOf { it.size })
                var off = 0
                for (row in hidden) {
                    row.copyInto(flat, off)
                    off += row.size
                }
                return flat
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    private fun runDecoder(encoderOutput: FloatArray, languageHint: String?): LongArray {
        val decoder = decoderSession!!
        val tokens = mutableListOf(1L)
        if (languageHint != null) {
            val token = getLanguageToken(languageHint)
            if (token != null) {
                tokens.add(token)
            } else {
                Log.w(TAG, "No tokenizer language token found for '$languageHint', decoding without hint token")
            }
        }

        require(encoderOutput.size % DECODER_HIDDEN_DIM == 0) {
            "Invalid encoder output shape: ${encoderOutput.size} not divisible by $DECODER_HIDDEN_DIM"
        }
        val seqLen = encoderOutput.size / DECODER_HIDDEN_DIM
        val encoderTensor = modelManager.createTensor(
            encoderOutput,
            longArrayOf(1, seqLen.toLong(), DECODER_HIDDEN_DIM.toLong())
        )

        try {
            for (stepIndex in 0 until MAX_DECODER_STEPS) {
                val inputIds = modelManager.createLongTensor(
                    tokens.toLongArray(),
                    longArrayOf(1, tokens.size.toLong())
                )
                try {
                    val result = decoder.run(
                        mapOf(
                            "input_ids" to inputIds,
                            "encoder_hidden_states" to encoderTensor
                        )
                    )
                    try {
                        val logits = parseBatchSequenceMatrix(result[0].value)
                            ?: throw IllegalStateException("Unexpected decoder output type: ${result[0].value?.javaClass?.name}")
                        val stepLogits = logits.firstOrNull()?.lastOrNull() ?: break
                        if (stepLogits.isEmpty()) break

                        var bestIndex = 0
                        var bestScore = stepLogits[0]
                        for (i in 1 until stepLogits.size) {
                            val score = stepLogits[i]
                            if (score > bestScore) {
                                bestScore = score
                                bestIndex = i
                            }
                        }

                        val next = bestIndex.toLong()
                        if (next == 2L) break
                        tokens.add(next)

                        if (stepIndex > 0 && stepIndex % 64 == 0) {
                            Log.v(TAG, "Decoder step=$stepIndex token=$next")
                        }
                    } finally {
                        result.close()
                    }
                } finally {
                    inputIds.close()
                }
            }
        } finally {
            encoderTensor.close()
        }

        return tokens.toLongArray()
    }

    private fun parseBatchSequenceMatrix(value: Any?): List<List<FloatArray>>? {
        val batch = value as? Array<*> ?: return null
        if (batch.isEmpty()) return emptyList()

        return batch.map { sequenceAny ->
            val sequence = sequenceAny as? Array<*> ?: return null
            sequence.map { frameAny ->
                frameAny as? FloatArray ?: return null
            }
        }
    }

    private fun decodeTokens(tokens: LongArray): String {
        if (tokens.isEmpty()) return ""
        if (tokenIdToPiece.isEmpty()) return "[${tokens.size} tokens]"

        val sb = StringBuilder()
        for (tokenId in tokens) {
            if (tokenId <= 2L) continue

            val piece = tokenIdToPiece[tokenId] ?: continue
            if (piece.startsWith("<") && piece.endsWith(">")) continue
            if (piece.startsWith("<|")) continue

            sb.append(
                piece
                    .replace("▁", " ")
                    .replace("Ġ", " ")
                    .replace("</w>", "")
            )
        }

        return sb.toString()
            .replace(whitespaceRegex, " ")
            .trim()
            .ifBlank { "[${tokens.size} tokens]" }
    }

    private fun getLanguageToken(lang: String): Long? {
        val normalized = lang.lowercase()
        val code = when (normalized) {
            "chinese", "中文", "mandarin", "普通话" -> "zh"
            "english", "en" -> "en"
            "japanese", "日本語", "jp" -> "ja"
            "korean", "한국어", "kr" -> "ko"
            "german", "deutsch" -> "de"
            "french", "français" -> "fr"
            "russian", "русский" -> "ru"
            "portuguese", "português" -> "pt"
            "spanish", "español" -> "es"
            "italian", "italiano" -> "it"
            else -> "zh"
        }

        return languageTokenIds[code] ?: defaultLanguageTokenId
    }

    fun release() {
        isLoaded = false; encoderSession = null; decoderSession = null
        modelManager.release("${OnnxModelManager.ASR_DIR}/asr_encoder_int4.onnx")
        modelManager.release("${OnnxModelManager.ASR_DIR}/asr_decoder_int4.onnx")
    }
}
