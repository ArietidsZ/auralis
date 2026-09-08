package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.dialect.interpreter.data.ModelManifests
import com.k2fsa.sherpa.onnx.SherpaJni
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * TTS engine for the Qwen3-TTS-12Hz-0.6B-Base ONNX bundle
 * (elbruno/Qwen3-TTS-12Hz-0.6B-Base-ONNX; see shared/model-manifests/tts.json).
 *
 * The protocol is a direct port of the bundle author's reference inference
 * (github.com/elbruno/ElBruno.QwenTTS, LanguageModel.cs / VoiceClonePipeline.cs)
 * and was verified end-to-end by the host runner convert/tts_runner.py: real
 * zh/en synthesis whose audio round-trips through the real ASR runner.
 *
 * Pipeline:
 *   reference 16 kHz PCM -> resample 24 kHz -> log-mel -> speaker_encoder.onnx
 *   text -> byte-level BPE -> embeddings tables (.npy) + text projection
 *        -> talker_prefill.onnx (logits/hidden/KV)
 *        -> per frame: talker_decode.onnx + code_predictor.onnx (groups 1..15)
 *        -> vocoder.onnx (codes [1,16,T] -> 24 kHz waveform)
 *
 * Honesty rules (spec 01 C04, 02):
 *  - Graph I/O names/shapes/dims are validated at use; mismatch throws
 *    [ModelProtocol.UnsupportedModelException], never a guess.
 *  - Missing tokenizer, unknown language, wrong embedding dim: hard failure.
 *  - A speaker embedding is required; there is no zero-vector clone path.
 *  - Empty or all-silent synthesis output is an error, never a "success".
 *  - Memory: talker_prefill/decode graphs each reference ~1.7 GB of weights.
 *    Sessions load serially; the prefill session is released right after the
 *    prefill pass; embedding tables are memory-mapped, not heap-copied.
 */
interface SpeechSynthesizer {
    suspend fun load()
    val outputSampleRate: Int get() = 24000

    /**
     * @param speakerEmbedding real speaker conditioning; null is rejected —
     *   the pipeline degrades to text-only instead of fabricating a voice.
     */
    suspend fun synthesize(
        text: String,
        language: String,
        speakerEmbedding: FloatArray?
    ): TtsEngine.SynthesisResult

    /** API1 compatibility producer; implementations with incremental PCM override it. */
    suspend fun synthesizeStream(
        text: String,
        language: String,
        speakerEmbedding: FloatArray?,
        onAudioChunk: suspend (FloatArray) -> Unit,
    ): TtsEngine.SynthesisResult {
        val result = synthesize(text, language, speakerEmbedding)
        require(result.sampleRate == outputSampleRate && result.audioData.isNotEmpty() &&
            result.audioData.all { it.isFinite() } && result.audioData.any { it != 0f }) {
            "Synthesis returned invalid PCM"
        }
        onAudioChunk(result.audioData)
        return result
    }

    fun release()
}

// ---------------------------------------------------------------------------
// Bundle config

class Qwen3TtsBundleConfig(
    val talker: Map<String, Int>,
    val codePredictor: Map<String, Int>,
    val tts: Map<String, Int>,
    val languageIds: Map<String, Int>,
) {
    val hiddenSize: Int get() = talker.getValue("hidden_size")          // 1024
    val numLayers: Int get() = talker.getValue("num_hidden_layers")     // 28
    val numKvHeads: Int get() = talker.getValue("num_key_value_heads")  // 8
    val headDim: Int get() = talker.getValue("head_dim")                // 128
    val talkerVocab: Int get() = talker.getValue("vocab_size")          // 3072
    val codecEosId: Int get() = talker.getValue("codec_eos_token_id")   // 2150
    val cpVocab: Int get() = codePredictor.getValue("vocab_size")       // 2048
    val cpLayers: Int get() = codePredictor.getValue("num_hidden_layers")
    val cpKvHeads: Int get() = codePredictor.getValue("num_key_value_heads")
    val cpHeadDim: Int get() = codePredictor.getValue("head_dim")
    val numCodeGroups: Int get() = talker.getValue("num_code_groups")   // 16
}

// ---------------------------------------------------------------------------
// Pure protocol implementation (unit-testable without ONNX Runtime)

object Qwen3TtsProtocol {

    const val SAMPLE_RATE = 24000
    const val SAMPLES_PER_FRAME = 1920
    const val NUM_CODEBOOKS = 16
    const val SPEAKER_EMBEDDING_DIM = 1024

    /** Per-synthesis RNG seed; each generate call resets it (paired protocol). */
    const val SAMPLING_SEED = 20260906

    /** Engine default frame budget (constructor may tighten it). */
    const val DEFAULT_FRAME_BUDGET = 2048

    /** Author's reference generation config (single source for both engines). */
    const val TEMPERATURE = 0.9f
    const val TOP_K = 50
    const val REPETITION_PENALTY = 1.05f

    /** Language code (LanguageCodes) -> config.json language_ids key. */
    val languageCodeToConfigKey = mapOf(
        "zh" to "chinese", "en" to "english", "de" to "german", "it" to "italian",
        "pt" to "portuguese", "es" to "spanish", "ja" to "japanese", "ko" to "korean",
        "fr" to "french", "ru" to "russian",
    )

    /** Resolves a pipeline language code to the bundle's language_ids key. */
    fun languageConfigKey(language: String, cfg: Qwen3TtsBundleConfig): String {
        val code = LanguageCodes.normalize(language)
            ?: throw ModelProtocol.UnsupportedModelException("unsupported TTS language: $language")
        val key = languageCodeToConfigKey[code]
            ?: throw ModelProtocol.UnsupportedModelException(
                "no bundle language id for '$language' (code $code)")
        if (key !in cfg.languageIds) {
            throw ModelProtocol.UnsupportedModelException(
                "bundle config has no language id for '$key'; known: ${cfg.languageIds.keys}")
        }
        return key
    }

    /**
     * Flatten per-frame code groups to the vocoder's GROUP-major layout:
     * flat[g * frames + f] for codes[g][f]. The vocoder consumes
     * codes[1, 16, T]; feeding frame-major data under that shape transposes
     * the matrix — sample count still matches, so only the audio is garbage
     * (host-verified failure mode, 2026-09-07).
     */
    fun flattenCodesGroupMajor(allCodes: List<IntArray>, numCodebooks: Int): IntArray {
        val frames = allCodes.size
        val flat = IntArray(frames * numCodebooks)
        for ((f, frame) in allCodes.withIndex()) {
            require(frame.size == numCodebooks) {
                "code frame $f has ${frame.size} groups, expected $numCodebooks"
            }
            for (g in 0 until numCodebooks) {
                flat[g * frames + f] = frame[g]
            }
        }
        return flat
    }

    // ---- Byte-level BPE (GPT-2 / Qwen2) ------------------------------------

    /** GPT-2 bytes_to_unicode reversible table. */
    fun bytesToUnicode(): Array<String> {
        val printable = HashSet<Int>()
        for (b in '!'..'~') printable.add(b.code)
        for (b in '¡'..'¬') printable.add(b.code)
        for (b in '®'..'ÿ') printable.add(b.code)
        var n = 0
        return Array(256) { byte ->
            val codePoint = if (byte in printable) byte else 256 + (n++)
            if (codePoint < 0x10000) String(charArrayOf(codePoint.toChar()))
            else String(charArrayOf(
                ((codePoint - 0x10000) shr 10 or 0xD800).toChar(),
                ((codePoint and 0x3FF) or 0xDC00).toChar()
            ))
        }
    }

    private val byteEncoder: Array<String> by lazy { bytesToUnicode() }

    /** Canonical special token ids; NOT present in the bundle vocab.json. */
    val specialTokenIds: Map<String, Int> = linkedMapOf(
        "<|endoftext|>" to 151643,
        "<|im_start|>" to 151644,
        "<|im_end|>" to 151645,
        "<|audio_start|>" to 151669,
        "<|audio_end|>" to 151670,
        "<tts_pad>" to 151671,
        "<tts_text_bos>" to 151672,
        "<tts_text_eod>" to 151673,
        "<tts_text_bos_single>" to 151674,
        "<|audio_pad|>" to 151675,
    )

    /**
     * GPT-2 pre-tokenization regex (matches the author's C# TextTokenizer and
     * HuggingFace Qwen2Tokenizer).
     */
    private val gpt2Regex = Regex(
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}|" +
            " ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"
    )

    class Qwen3TtsTokenizer(
        private val vocab: Map<String, Int>,
        private val mergeRanks: Map<String, Int>,
    ) {
        init {
            for ((token, id) in specialTokenIds) {
                require(vocab[token] == id) {
                    "special token '$token' must map to $id, vocab says ${vocab[token]}"
                }
            }
        }

        fun idFor(piece: String): Int? = vocab[piece]

        fun encode(text: String): List<Int> {
            val ids = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                // Special tokens are atomic and must not be byte-split.
                var specialId = -1
                var specialEnd = -1
                for ((token, id) in specialTokenIds) {
                    if (text.startsWith(token, i)) { specialId = id; specialEnd = i + token.length; break }
                }
                if (specialId >= 0) {
                    ids.add(specialId)
                    i = specialEnd
                    continue
                }
                // Find the run up to the next special token occurrence.
                var runEnd = text.length
                for ((token, _) in specialTokenIds) {
                    val idx = text.indexOf(token, i + 1)
                    if (idx in (i + 1) until runEnd) runEnd = idx
                }
                val chunk = text.substring(i, runEnd)
                for (preToken in gpt2Regex.findAll(chunk).map { it.value }) {
                    for (piece in bpe(preToken)) {
                        val id = vocab[piece] ?: throw IllegalStateException(
                            "BPE piece '$piece' not in vocab; tokenizer/decoder mismatch")
                        ids.add(id)
                    }
                }
                i = runEnd
            }
            return ids
        }

        /** Qwen3-TTS chat prompt: <|im_start|>assistant\n{text}<|im_end|>\n<|im_start|>assistant\n */
        fun buildPromptIds(text: String): List<Int> =
            encode("<|im_start|>assistant\n$text<|im_end|>\n<|im_start|>assistant\n")

        /** Classic lowest-rank-pair BPE over one pre-token. */
        private fun bpe(preToken: String): List<String> {
            if (preToken.isEmpty()) return emptyList()
            var symbols = preToken.toByteArray(Charsets.UTF_8).map { byteEncoder[it.toInt() and 0xFF] }
            while (symbols.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1
                for (j in 0 until symbols.size - 1) {
                    val rank = mergeRanks[symbols[j] + " " + symbols[j + 1]]
                    if (rank != null && rank < bestRank) { bestRank = rank; bestIdx = j }
                }
                if (bestIdx < 0) break
                val merged = symbols[bestIdx] + symbols[bestIdx + 1]
                val next = ArrayList<String>(symbols.size - 1)
                var j = 0
                while (j < symbols.size) {
                    if (j == bestIdx) { next.add(merged); j += 2 }
                    else { next.add(symbols[j]); j++ }
                }
                symbols = next
            }
            return symbols
        }
    }

    // ---- Prefill embeddings (port of BuildPrefillEmbedding) -----------------

    class PrefillEmbeddings(
        val embeds: FloatArray,   // [seqLen * H]
        val trailing: FloatArray, // [(trailingCount) * H]; index < count -> text, else tts_pad
    )

    /**
     * Prefill embedding sequence (author's LanguageModel.BuildPrefillEmbedding):
     *   role embeds (tokens 0..2)
     *   + codec prefix with speaker embedding injected at its slot, each combined
     *     with tts_pad; the second-to-last prefix slot combined with tts_bos
     *   + first text token projected + codec_bos
     * Trailing: projected tokens[4 .. len-6], then tts_eos; decode steps past the
     * trailing end fall back to tts_pad (handled by the caller).
     */
    fun buildPrefillEmbeddings(
        tokenIds: IntArray,
        speakerEmbedding: FloatArray,
        languageKey: String,
        cfg: Qwen3TtsBundleConfig,
        tables: Qwen3TtsEmbeddingLookup,
    ): PrefillEmbeddings {
        if (tokenIds.size < 9) {
            throw ModelProtocol.UnsupportedModelException(
                "prompt too short (${tokenIds.size} tokens); text is unusable")
        }
        val H = cfg.hiddenSize

        val codecPrefix = ArrayList<Int>()
        codecPrefix.add(cfg.talker.getValue("codec_think_id"))
        codecPrefix.add(cfg.talker.getValue("codec_think_bos_id"))
        val languageId = cfg.languageIds[languageKey]
            ?: throw ModelProtocol.UnsupportedModelException(
                "bundle config has no language id for '$languageKey'")
        codecPrefix.add(languageId)
        codecPrefix.add(cfg.talker.getValue("codec_think_eos_id"))
        val speakerPos = codecPrefix.size
        codecPrefix.add(cfg.talker.getValue("codec_pad_id")) // speaker placeholder
        codecPrefix.add(cfg.talker.getValue("codec_pad_id"))
        codecPrefix.add(cfg.talker.getValue("codec_bos_id"))

        val ttsPad = tables.project(tables.textEmbed(cfg.tts.getValue("tts_pad_token_id")))
        val ttsBos = tables.project(tables.textEmbed(cfg.tts.getValue("tts_bos_token_id")))
        val ttsEos = tables.project(tables.textEmbed(cfg.tts.getValue("tts_eos_token_id")))

        val positions = ArrayList<FloatArray>()

        for (i in 0 until 3) {
            positions.add(tables.project(tables.textEmbed(tokenIds[i])))
        }
        for (i in 0 until codecPrefix.size - 2) {
            val combined = ttsPad.copyOf()
            if (i == speakerPos) {
                for (j in 0 until H) combined[j] += speakerEmbedding[j]
            } else {
                val codec = tables.talkerCodecEmbedding(codecPrefix[i])
                for (j in 0 until H) combined[j] += codec[j]
            }
            positions.add(combined)
        }
        val last = ttsBos.copyOf()
        val lastCodec = tables.talkerCodecEmbedding(codecPrefix[codecPrefix.size - 2])
        for (j in 0 until H) last[j] += lastCodec[j]
        positions.add(last)
        val firstText = tables.project(tables.textEmbed(tokenIds[3]))
        val codecBos = tables.talkerCodecEmbedding(cfg.talker.getValue("codec_bos_id"))
        for (j in 0 until H) firstText[j] += codecBos[j]
        positions.add(firstText)

        val prefill = FloatArray(positions.size * H)
        for ((p, vec) in positions.withIndex()) System.arraycopy(vec, 0, prefill, p * H, H)

        val trailingCount = max(0, tokenIds.size - 9)
        val trailing = FloatArray(trailingCount * H)
        for (t in 0 until trailingCount) {
            val projected = tables.project(tables.textEmbed(tokenIds[4 + t]))
            System.arraycopy(projected, 0, trailing, t * H, H)
        }
        // tts_eos appended conceptually after the text tokens: store it as one
        // extra row so the caller can distinguish text rows from the final EOS.
        val trailingWithEos = FloatArray(trailing.size + H)
        System.arraycopy(trailing, 0, trailingWithEos, 0, trailing.size)
        System.arraycopy(ttsEos, 0, trailingWithEos, trailing.size, H)

        return PrefillEmbeddings(prefill, trailingWithEos)
    }

    // ---- Sampling (port of SampleToken / SampleTokenSimple) -----------------

    /**
     * Group-0 sampling: repetition penalty over generated group-0 tokens,
     * suppression of the codec range outside [0, cpVocab) except codec EOS,
     * temperature, top-k, softmax, multinomial. `suppressEos` implements
     * min_new_tokens=2 (matching the Python/C# reference).
     */
    fun sampleGroup0(
        logitsLast: FloatArray,
        cfg: Qwen3TtsBundleConfig,
        temperature: Float,
        topK: Int,
        repetitionPenalty: Float,
        generated: List<Int>,
        random: Random,
        suppressEos: Boolean,
    ): Int {
        val vocab = cfg.talkerVocab
        require(vocab > 0 && logitsLast.size >= vocab) { "Invalid talker logits shape" }
        require(repetitionPenalty.isFinite() && repetitionPenalty > 0f) { "Invalid repetition penalty" }
        val probs = FloatArray(vocab)
        System.arraycopy(logitsLast, logitsLast.size - vocab, probs, 0, vocab)
        require(probs.all { it.isFinite() || it == Float.NEGATIVE_INFINITY }) { "Invalid talker logits" }
        if (suppressEos) probs[cfg.codecEosId] = Float.NEGATIVE_INFINITY
        // Each distinct generated token is penalized exactly once; the seen
        // mask replaces the per-call HashSet without changing the outcome
        // (penalty application is per-index, so order is irrelevant).
        val penalized = BooleanArray(vocab)
        for (token in generated) {
            require(token in probs.indices) { "Invalid generated codec token" }
            if (!penalized[token]) {
                penalized[token] = true
                if (probs[token] > 0f) probs[token] /= repetitionPenalty else probs[token] *= repetitionPenalty
            }
        }
        for (i in cfg.cpVocab until vocab) {
            if (i != cfg.codecEosId) probs[i] = Float.NEGATIVE_INFINITY
        }
        return sampleFromLogits(probs, temperature, topK, random)
    }

    fun sampleCodePredictor(
        logitsLast: FloatArray,
        cfg: Qwen3TtsBundleConfig,
        temperature: Float,
        topK: Int,
        random: Random,
    ): Int {
        val vocab = cfg.cpVocab
        require(vocab > 0 && logitsLast.size >= vocab) { "Invalid code-predictor logits shape" }
        val probs = FloatArray(vocab)
        System.arraycopy(logitsLast, logitsLast.size - vocab, probs, 0, vocab)
        return sampleFromLogits(probs, temperature, topK, random)
    }

    private fun sampleFromLogits(
        probs: FloatArray,
        temperature: Float,
        topK: Int,
        random: Random,
    ): Int {
        require(temperature.isFinite() && temperature >= 0f) { "Invalid sampling temperature" }
        require(topK >= 0) { "topK must be nonnegative (0 disables filtering)" }
        require(probs.isNotEmpty() && probs.all { it.isFinite() || it == Float.NEGATIVE_INFINITY }) {
            "Sampling logits contain NaN or positive infinity"
        }
        var best = 0
        for (i in probs.indices) if (probs[i] > probs[best]) best = i
        val maxLogit = probs[best]
        require(maxLogit.isFinite()) { "No finite sampling candidate remains" }
        if (temperature == 0f || topK == 1) return best
        if (topK in 1 until probs.size) {
            val threshold = kthLargestFloat(probs, topK)
            for (i in probs.indices) if (probs[i] < threshold) probs[i] = Float.NEGATIVE_INFINITY
        }
        // Subtract the finite maximum before temperature scaling. Double
        // intermediates also avoid overflow for extreme finite float inputs.
        val weights = DoubleArray(probs.size) {
            exp((probs[it].toDouble() - maxLogit.toDouble()) / temperature.toDouble())
        }
        val sum = weights.sum()
        check(sum.isFinite() && sum > 0.0) { "Invalid sampling probability mass" }
        val r = random.nextDouble() * sum
        var cum = 0.0
        for (i in weights.indices) {
            cum += weights[i]
            if (r < cum) return i
        }
        return weights.indices.last { weights[it] > 0.0 }
    }

    /**
     * Exact k-th largest value of a multiset — the value
     * `values.copyOf().sortedDescending()[k - 1]` selects — without copying
     * or sorting [values]. Selecting the k-th largest equals selecting the
     * (n-k+1)-th smallest, so a bounded heap over whichever side is smaller
     * does the job in O(n log min(k, n-k+1)). Comparisons only, no arithmetic
     * on the values, so the result equals the sorted reference for every
     * value — except possibly the sign of a zero when the input mixes -0.0f
     * and 0.0f (the heap compares IEEE; a boxed sort may total-order them).
     * That sign cannot change sampling behavior: the mask test
     * `x < threshold` treats both zero signs alike and exp(±0.0) are equal.
     * Inputs must not contain NaN (the sampler rejects NaN logits first).
     */
    internal fun kthLargestFloat(values: FloatArray, k: Int): Float {
        require(values.isNotEmpty()) { "kth largest requires a nonempty array" }
        require(k in 1..values.size) { "kth largest order $k outside 1..${values.size}" }
        val keepLargest = k <= values.size - k + 1
        val capacity = if (keepLargest) k else values.size - k + 1
        val heap = FloatArray(capacity)
        var size = 0
        for (x in values) {
            if (size < capacity) {
                // Sift-up insert.
                var i = size++
                heap[i] = x
                while (i > 0) {
                    val parent = (i - 1) / 2
                    val ordered = if (keepLargest) heap[parent] <= heap[i] else heap[parent] >= heap[i]
                    if (ordered) break
                    val tmp = heap[parent]; heap[parent] = heap[i]; heap[i] = tmp
                    i = parent
                }
            } else if (if (keepLargest) x > heap[0] else x < heap[0]) {
                // Replace the boundary entry and restore the heap invariant.
                heap[0] = x
                var i = 0
                while (true) {
                    val left = 2 * i + 1
                    val right = left + 1
                    var best = i
                    if (left < size) {
                        val betterLeft = if (keepLargest) heap[left] < heap[best] else heap[left] > heap[best]
                        if (betterLeft) best = left
                    }
                    if (right < size) {
                        val betterRight = if (keepLargest) heap[right] < heap[best] else heap[right] > heap[best]
                        if (betterRight) best = right
                    }
                    if (best == i) break
                    val tmp = heap[best]; heap[best] = heap[i]; heap[i] = tmp
                    i = best
                }
            }
        }
        return heap[0]
    }

    // ---- Audio frontend (exact PyTorch-style mel, verified vs librosa) ------

    fun buildMelFilterbank(sr: Int, nFft: Int, nMels: Int, fmin: Double, fmax: Double): FloatArray {
        val nFreqs = nFft / 2 + 1

        fun hzToMel(f: Double): Double {
            val fSp = 200.0 / 3.0
            val mels = f / fSp
            val minLogHz = 1000.0
            val minLogMel = minLogHz / fSp
            val logstep = ln(6.4) / 27.0
            return if (f >= minLogHz) minLogMel + ln(max(f, 1e-30) / minLogHz) / logstep else mels
        }

        fun melToHz(m: Double): Double {
            val fSp = 200.0 / 3.0
            val minLogMel = 1000.0 / fSp
            val logstep = ln(6.4) / 27.0
            return if (m >= minLogMel) 1000.0 * exp(logstep * (m - minLogMel)) else fSp * m
        }

        val melMin = hzToMel(fmin)
        val melMax = hzToMel(fmax)
        // Filter band edges in Hz (librosa slaney scale: mel -> Hz).
        val melF = DoubleArray(nMels + 2) {
            melToHz(melMin + (melMax - melMin) * it / (nMels + 1))
        }
        val fftFreqs = DoubleArray(nFreqs) { it.toDouble() * sr / nFft }

        val weights = FloatArray(nMels * nFreqs)
        for (m in 0 until nMels) {
            val fdiffLow = melF[m + 1] - melF[m]
            val fdiffHigh = melF[m + 2] - melF[m + 1]
            val enorm = 2.0 / (melF[m + 2] - melF[m])
            for (k in 0 until nFreqs) {
                val lower = -(melF[m] - fftFreqs[k]) / fdiffLow
                val upper = (melF[m + 2] - fftFreqs[k]) / fdiffHigh
                weights[m * nFreqs + k] = (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
        return weights
    }

    /** Reflect-pad index mapping matching torch F.pad(mode="reflect"). */
    internal fun reflectIndex(idx: Int, length: Int): Int {
        var i = idx
        if (i < 0) i = -i
        val period = 2 * (length - 1)
        if (period == 0) return 0
        i %= period
        return if (i >= length) period - i else i
    }

    class LogMelResult(val data: FloatArray, val frames: Int)

    /** [1, T, 128] log-mel frontend (n_fft 1024, hop 256, slaney norm). */
    fun logMelSpectrogram(audio: FloatArray, sr: Int): LogMelResult {
        val nFft = 1024
        val hop = 256
        val nMels = 128
        // The reflect-padded signal must cover one full FFT window, otherwise
        // the frame loop reads past the padded array (AIOOBE). Minimum:
        // audio.size >= nFft - 2*pad == hop. Input shorter than one hop is
        // rejected cleanly instead of crashing.
        if (audio.size < hop) {
            throw IllegalArgumentException("reference audio too short for mel frontend")
        }
        val pad = (nFft - hop) / 2
        val padded = FloatArray(audio.size + 2 * pad)
        for (i in padded.indices) {
            padded[i] = audio[reflectIndex(i - pad, audio.size)]
        }
        val window = FloatArray(nFft) { (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * it / nFft)).toFloat() }
        val frames = 1 + (padded.size - nFft) / hop
        val basis = buildMelFilterbank(sr, nFft, nMels, 0.0, sr / 2.0)
        val out = FloatArray(frames * nMels)
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val nFreqs = nFft / 2 + 1
        val mag = DoubleArray(nFreqs)
        for (f in 0 until frames) {
            val start = f * hop
            for (i in 0 until nFft) {
                re[i] = (padded[start + i] * window[i]).toDouble()
                im[i] = 0.0
            }
            fftRadix2(re, im)
            // Magnitude once per bin; every mel band reuses it. The expression
            // and the band-accumulation order are unchanged, so the output is
            // bit-identical to the per-band recomputation.
            for (k in 0 until nFreqs) {
                mag[k] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k] + 1e-9)
            }
            for (m in 0 until nMels) {
                var energy = 0.0
                for (k in 0 until nFreqs) {
                    energy += basis[m * nFreqs + k] * mag[k]
                }
                out[f * nMels + m] = max(energy, 1e-5).let(::ln).toFloat()
            }
        }
        return LogMelResult(out, frames)
    }

    /** In-place iterative radix-2 FFT (port of the author's MelSpectrogram.Fft). */
    fun fftRadix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        val bits = Integer.numberOfTrailingZeros(n)
        require(1 shl bits == n) { "FFT size must be a power of two" }
        for (i in 0 until n) {
            var x = i
            var j = 0
            repeat(bits) { j = (j shl 1) or (x and 1); x = x shr 1 }
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val twiddles = twiddlesFor(n)
        val cosT = twiddles.cos
        val sinT = twiddles.sin
        var size = 2
        var twOff = 0
        while (size <= n) {
            val half = size / 2
            for (i in 0 until n step size) {
                for (k in 0 until half) {
                    val c = cosT[twOff + k]
                    val s = sinT[twOff + k]
                    val tReal = c * re[i + k + half] - s * im[i + k + half]
                    val tImag = s * re[i + k + half] + c * im[i + k + half]
                    re[i + k + half] = re[i + k] - tReal
                    im[i + k + half] = im[i + k] - tImag
                    re[i + k] += tReal
                    im[i + k] += tImag
                }
            }
            twOff += half
            size *= 2
        }
    }

    // ---- FFT twiddle cache -----------------------------------------------------
    // cos(-2*pi*k/size) / sin(...) were recomputed for every butterfly block of
    // every frame; the values depend only on (n, size, k), so they are built
    // once per FFT size with the exact same expressions (bit-identical to the
    // inline computation) and reused. One immutable @Volatile holder keeps the
    // (n, cos, sin) triple consistent for concurrent readers.

    private class TwiddleTable(val n: Int, val cos: DoubleArray, val sin: DoubleArray)

    private val twiddleLock = Any()
    @Volatile private var twiddleTable = TwiddleTable(0, DoubleArray(0), DoubleArray(0))

    private fun twiddlesFor(n: Int): TwiddleTable {
        twiddleTable.let { if (it.n == n) return it }
        synchronized(twiddleLock) {
            if (twiddleTable.n != n) {
                val cosT = DoubleArray(n - 1)
                val sinT = DoubleArray(n - 1)
                var off = 0
                var size = 2
                while (size <= n) {
                    val half = size / 2
                    val angle = -2.0 * PI / size
                    for (k in 0 until half) {
                        cosT[off + k] = kotlin.math.cos(angle * k)
                        sinT[off + k] = kotlin.math.sin(angle * k)
                    }
                    off += half
                    size *= 2
                }
                twiddleTable = TwiddleTable(n, cosT, sinT)
            }
            return twiddleTable
        }
    }


}

// ---------------------------------------------------------------------------
// .npy assets

/** Embedding/projection lookups needed by the protocol (injectable for tests). */
interface Qwen3TtsEmbeddingLookup {
    fun textEmbed(tokenId: Int): FloatArray
    fun project(raw: FloatArray): FloatArray
    fun talkerCodecEmbedding(tokenId: Int): FloatArray
    fun cpCodecEmbedding(groupIndex: Int, tokenId: Int): FloatArray
}

/**
 * Reads the bundle's float32 .npy tables. text_embedding.npy is 1.2 GB and is
 * memory-mapped: only touched rows are paged in. Other tables load to the heap.
 */
class Qwen3TtsEmbeddings(bundleDir: File) : Qwen3TtsEmbeddingLookup {
    private val embeddingsDir = File(bundleDir, "embeddings")

    private val textEmbedding: NpyFloat2D = NpyFloat2D.open(File(embeddingsDir, "text_embedding.npy"))
    private val fc1Weight: NpyFloat2D = NpyFloat2D.open(File(embeddingsDir, "text_projection_fc1_weight.npy"))
    private val fc1Bias: NpyFloat1D = NpyFloat1D.open(File(embeddingsDir, "text_projection_fc1_bias.npy"))
    private val fc2Weight: NpyFloat2D = NpyFloat2D.open(File(embeddingsDir, "text_projection_fc2_weight.npy"))
    private val fc2Bias: NpyFloat1D = NpyFloat1D.open(File(embeddingsDir, "text_projection_fc2_bias.npy"))
    private val talkerCodec: NpyFloat2D = NpyFloat2D.open(File(embeddingsDir, "talker_codec_embedding.npy"))
    private val cpCodecs: List<NpyFloat2D> = (0 until 15).map {
        NpyFloat2D.open(File(embeddingsDir, "cp_codec_embedding_$it.npy"))
    }

    val hiddenSize: Int get() = fc2Weight.rows
    val textHiddenSize: Int get() = textEmbedding.cols

    init {
        require(fc1Weight.cols == textHiddenSize) { "text projection fc1 shape mismatch" }
        require(fc2Weight.cols == fc1Weight.rows) { "text projection fc2 shape mismatch" }
        require(talkerCodec.cols == hiddenSize) { "talker codec embedding dim mismatch" }
        for (cp in cpCodecs) require(cp.cols == hiddenSize) { "cp codec embedding dim mismatch" }
    }

    override fun textEmbed(tokenId: Int): FloatArray = textEmbedding.row(tokenId)

    /** fc2(silu(fc1(x))) with biases; maps 2048-d text space -> talker space. */
    override fun project(raw: FloatArray): FloatArray {
        val fc1Out = fc1Weight.rows
        val hidden = FloatArray(fc1Out)
        for (i in 0 until fc1Out) {
            var sum = 0f
            val row = fc1Weight.row(i)
            for (j in raw.indices) sum += row[j] * raw[j]
            val h = sum + fc1Bias.data[i]
            hidden[i] = h / (1f + exp(-h)) // SiLU
        }
        val out = FloatArray(fc2Weight.rows)
        for (i in out.indices) {
            var sum = 0f
            val row = fc2Weight.row(i)
            for (j in hidden.indices) sum += row[j] * hidden[j]
            out[i] = sum + fc2Bias.data[i]
        }
        return out
    }

    override fun talkerCodecEmbedding(tokenId: Int): FloatArray = talkerCodec.row(tokenId)

    /** CP groups 1..15 use embedding tables indexed 0..14. */
    override fun cpCodecEmbedding(groupIndex: Int, tokenId: Int): FloatArray {
        require(groupIndex in 0 until 15) { "cp group index must be 0..14, got $groupIndex" }
        return cpCodecs[groupIndex].row(tokenId)
    }
}

/** C-order float32 .npy with row access; large files are memory-mapped. */
class NpyFloat2D private constructor(
    private val buffer: ByteBuffer,
    val rows: Int,
    val cols: Int,
) {
    fun row(index: Int): FloatArray {
        check(index in 0 until rows) { "row $index out of range ($rows)" }
        val out = FloatArray(cols)
        buffer.position(index * cols * 4)
        buffer.asFloatBuffer().get(out)
        return out
    }

    companion object {
        fun open(file: File): NpyFloat2D = open(file, mmapThresholdBytes = 0)

        internal fun open(file: File, mmapThresholdBytes: Long): NpyFloat2D {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val headerLen = readHeaderLength(channel)
                val header = ByteArray(headerLen)
                channel.position(10)
                check(channel.read(ByteBuffer.wrap(header)) == headerLen) {
                    "truncated npy header: $file"
                }
                val headerText = String(header, Charsets.ISO_8859_1)
                val descr = extractHeaderField(headerText, "descr")
                    ?: throw IllegalArgumentException("npy header missing descr: $file")
                check(descr.contains("f4") || descr.contains("float32")) {
                    "expected float32 npy, got $descr: $file"
                }
                // fortran_order is a bare boolean in npy headers (unquoted).
                val fortranMatch = Regex("'fortran_order'\\s*:\\s*(True|False|true|false)")
                    .find(headerText)
                    ?: throw IllegalArgumentException("npy header missing fortran_order: $file")
                check(fortranMatch.groupValues[1].lowercase() == "false") {
                    "fortran-order npy not supported: $file"
                }
                val shapeMatch = Regex("'shape'\\s*:\\s*\\(([^)]*)\\)").find(headerText)
                    ?: throw IllegalArgumentException("npy header missing shape: $file")
                val dims = shapeMatch.groupValues[1].split(',').mapNotNull {
                    it.trim().takeIf(String::isNotEmpty)?.toInt()
                }
                require(dims.size == 2) { "expected 2-D npy, got $dims: $file" }
                val dataOffset = 10L + headerLen
                val byteCount = dims[0].toLong() * dims[1] * 4L
                return if (byteCount >= mmapThresholdBytes) {
                    val map = channel.map(FileChannel.MapMode.READ_ONLY, dataOffset, byteCount)
                    map.order(ByteOrder.LITTLE_ENDIAN)
                    NpyFloat2D(map, dims[0], dims[1])
                } else {
                    val buf = ByteBuffer.allocate(byteCount.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    channel.position(dataOffset)
                    while (buf.hasRemaining()) {
                        check(channel.read(buf) >= 0) { "truncated npy data: $file" }
                    }
                    buf.flip()
                    NpyFloat2D(buf, dims[0], dims[1])
                }
            }
        }

        private fun readHeaderLength(channel: FileChannel): Int {
            val prefix = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(0)
            check(channel.read(prefix) == 10) { "truncated npy prefix" }
            prefix.flip()
            val magic = ByteArray(6)
            prefix.get(magic)
            check(String(magic, Charsets.ISO_8859_1) == "\u0093NUMPY") { "not an npy file" }
            val major = prefix.get().toInt()
            prefix.get() // minor version byte
            check(major == 1 || major == 2) { "unsupported npy major version $major" }
            return if (major == 1) prefix.short.toInt() and 0xFFFF else prefix.int
        }

        private fun extractHeaderField(header: String, field: String): String? {
            val match = Regex("'$field'\\s*:\\s*'([^']*)'").find(header)
            return match?.groupValues?.get(1)
        }
    }
}

/** 1-D float32 .npy (biases). */
class NpyFloat1D private constructor(val data: FloatArray) {
    companion object {
        fun open(file: File): NpyFloat1D {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val headerLen = readHeaderLength(channel)
                val header = ByteArray(headerLen)
                channel.position(10)
                check(channel.read(ByteBuffer.wrap(header)) == headerLen) {
                    "truncated npy header: $file"
                }
                val headerText = String(header, Charsets.ISO_8859_1)
                val shapeMatch = Regex("'shape'\\s*:\\s*\\(([^)]*)\\)").find(headerText)
                    ?: throw IllegalArgumentException("npy header missing shape: $file")
                val dims = shapeMatch.groupValues[1].split(',').mapNotNull {
                    it.trim().takeIf(String::isNotEmpty)?.toInt()
                }
                require(dims.size == 1) { "expected 1-D npy, got $dims: $file" }
                val data = FloatArray(dims[0])
                val buf = ByteBuffer.allocate(dims[0] * 4).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(10L + headerLen)
                while (buf.hasRemaining()) {
                    check(channel.read(buf) >= 0) { "truncated npy data: $file" }
                }
                buf.flip()
                buf.asFloatBuffer().get(data)
                return NpyFloat1D(data)
            }
        }

        private fun readHeaderLength(channel: FileChannel): Int {
            val prefix = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(0)
            check(channel.read(prefix) == 10) { "truncated npy prefix" }
            prefix.flip()
            val magic = ByteArray(6)
            prefix.get(magic)
            check(String(magic, Charsets.ISO_8859_1) == "\u0093NUMPY") { "not an npy file" }
            val major = prefix.get().toInt()
            prefix.get() // minor version byte
            check(major == 1 || major == 2) { "unsupported npy major version $major" }
            return if (major == 1) prefix.short.toInt() and 0xFFFF else prefix.int
        }
    }
}

// ---------------------------------------------------------------------------
// Bundle assets (shared by the API1 engine and the API2 runtime)

/** Loads the bundle's config.json + tokenizer; one source of truth for both engines. */
internal object TtsBundleAssets {
    private const val CONFIG_RELATIVE = "tts/embeddings/config.json"
    private const val VOCAB_RELATIVE = "tts/tokenizer/vocab.json"
    private const val MERGES_RELATIVE = "tts/tokenizer/merges.txt"

    private val json = Json { ignoreUnknownKeys = true }

    fun loadConfig(modelsDir: File): Qwen3TtsBundleConfig {
        val file = File(modelsDir, CONFIG_RELATIVE)
        require(file.exists()) {
            "TTS bundle config missing at ${file.absolutePath} — model protocol unsupported"
        }
        val root = json.parseToJsonElement(file.readText()).jsonObject
        fun section(key: String): Map<String, Int> =
            root[key]?.jsonObject?.entries?.associate { (k, v) -> k to v.jsonPrimitive.int }
                ?: throw ModelProtocol.UnsupportedModelException("TTS config missing '$key' section")
        val talker = section("talker")
        val cp = section("code_predictor")
        val tts = section("tts")
        val langs = section("language_ids")
        for (required in listOf("hidden_size", "num_hidden_layers", "num_key_value_heads",
                "head_dim", "vocab_size", "codec_eos_token_id", "num_code_groups")) {
            require(talker.containsKey(required)) { "TTS talker config missing '$required'" }
        }
        return Qwen3TtsBundleConfig(talker, cp, tts, langs)
    }

    fun loadTokenizer(modelsDir: File): Qwen3TtsProtocol.Qwen3TtsTokenizer {
        val vocabFile = File(modelsDir, VOCAB_RELATIVE)
        val mergesFile = File(modelsDir, MERGES_RELATIVE)
        require(vocabFile.exists() && mergesFile.exists()) {
            "TTS tokenizer missing at ${vocabFile.parentFile.absolutePath} — " +
                "model protocol unsupported"
        }
        val vocab = HashMap<String, Int>(153_000)
        val root = json.parseToJsonElement(vocabFile.readText()).jsonObject
        for ((piece, id) in root) {
            val idValue = id.jsonPrimitive.content.toIntOrNull()
                ?: throw ModelProtocol.UnsupportedModelException(
                    "TTS vocab.json has non-integer id for '$piece'")
            vocab[piece] = idValue
        }
        for ((token, id) in Qwen3TtsProtocol.specialTokenIds) {
            vocab.putIfAbsent(token, id)
        }
        val mergeRanks = HashMap<String, Int>(270_000)
        mergesFile.useLines { lines ->
            var rank = 0
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.split(' ').size != 2) continue
                mergeRanks[line] = rank++
            }
        }
        return Qwen3TtsProtocol.Qwen3TtsTokenizer(vocab, mergeRanks)
    }
}

// ---------------------------------------------------------------------------
// Engine

class TtsEngine(
    private val modelManager: OnnxModelManager,
    private val maxFrames: Int = MAX_FRAMES,
) : SpeechSynthesizer {

    init {
        require(maxFrames in 1..MAX_FRAMES) { "TTS frame budget must be in 1..$MAX_FRAMES" }
    }

    companion object {
        private const val TAG = "TtsEngine"
        const val OUTPUT_SAMPLE_RATE = Qwen3TtsProtocol.SAMPLE_RATE
        private const val PACKAGE_DIR = "tts"
        private const val SPEAKER_ENCODER_FILE = "speaker_encoder.onnx"
        private const val TALKER_PREFILL_FILE = "talker_prefill.onnx"
        private const val TALKER_DECODE_FILE = "talker_decode.onnx"
        private const val CODE_PREDICTOR_FILE = "code_predictor.onnx"
        private const val VOCODER_FILE = "vocoder.onnx"

        /** Reference audio recorded by [AudioRecorder]; resampled to 24 kHz. */
        const val REFERENCE_INPUT_SAMPLE_RATE = 16000

        private const val MAX_FRAMES = 2048
    }

    /** Synthesis refused because no valid speaker reference was supplied. */
    class SpeakerVoiceRequiredException(message: String) :
        IllegalArgumentException(message)

    data class SynthesisResult(
        val audioData: FloatArray,
        val sampleRate: Int,
        val durationMs: Long,
        val inferenceTimeMs: Long
    )

    private val json = Json { ignoreUnknownKeys = true }

    private var tokenizer: Qwen3TtsProtocol.Qwen3TtsTokenizer? = null
    private var embeddings: Qwen3TtsEmbeddings? = null
    private var config: Qwen3TtsBundleConfig? = null
    private var speakerEncoderLoaded = false
    private var synthesisLoaded = false


    // ---- Lifecycle ----------------------------------------------------------

    /** Load everything the synthesis path needs (config + tables + tokenizer). */
    override suspend fun load() = withContext(Dispatchers.IO) {
        if (supportsPreparedReference()) {
            api2().loadAssets()
            return@withContext
        }
        if (synthesisLoaded) return@withContext
        val t0 = System.currentTimeMillis()
        config = TtsBundleAssets.loadConfig(modelManager.getModelsDir())
        embeddings = Qwen3TtsEmbeddings(File(modelManager.getModelsDir(), PACKAGE_DIR))
        tokenizer = TtsBundleAssets.loadTokenizer(modelManager.getModelsDir())
        synthesisLoaded = true
        Log.i(TAG, "TTS tables/tokenizer/config ready in ${System.currentTimeMillis() - t0}ms")
    }

    /** Lightweight path used by voice-profile creation: encoder module only. */
    suspend fun loadSpeakerEncoder() = withContext(Dispatchers.IO) {
        if (speakerEncoderLoaded) return@withContext
        modelManager.loadSession(PACKAGE_DIR, SPEAKER_ENCODER_FILE)
        speakerEncoderLoaded = true
    }

    // ---- Speaker embedding ---------------------------------------------------

    /**
     * Extract the raw ECAPA-TDNN speaker embedding via the real graph.
     *
     * The bundle speaker encoder consumes a 24 kHz log-mel frontend, so 16 kHz
     * capture is resampled first. The returned vector is NOT L2-normalized:
     * the verified host protocol injects the raw embedding (normalizing would
     * rescale the conditioning relative to the proven path).
     */
    @Suppress("UNUSED_PARAMETER") // profileId remains API-compatible; references are never ID-cached.
    suspend fun extractSpeakerEmbedding(
        referenceAudio: FloatArray,
        profileId: String? = null,
        inputSampleRate: Int = REFERENCE_INPUT_SAMPLE_RATE
    ): FloatArray = withContext(Dispatchers.Default) {
        require(referenceAudio.isNotEmpty()) { "Reference audio is empty" }
        require(inputSampleRate in 8000..192000) { "Unsupported reference sample rate" }
        require(referenceAudio.size.toLong() <= inputSampleRate.toLong() * 30) { "Reference audio exceeds 30 seconds" }
        var peak = 0f
        for (sample in referenceAudio) {
            require(sample.isFinite()) { "Reference audio must be finite" }
            peak = max(peak, kotlin.math.abs(sample))
        }
        require(peak >= 1e-4f) { "Reference audio is silent or too quiet" }

        SherpaJni.load()
        val audio24k = SherpaJni.resample(referenceAudio, inputSampleRate, OUTPUT_SAMPLE_RATE)
        if (!speakerEncoderLoaded) loadSpeakerEncoder()
        val mel = Qwen3TtsProtocol.logMelSpectrogram(audio24k, OUTPUT_SAMPLE_RATE)
        val encoder = modelManager.loadSession(PACKAGE_DIR, SPEAKER_ENCODER_FILE)
        validateSpeakerEncoderIo(encoder)

        val input = modelManager.createTensor(
            mel.data, longArrayOf(1, mel.frames.toLong(), 128))
        val embedding = try {
            encoder.run(mapOf("mel_spectrogram" to input)).use { result ->
                val tensor = outputTensor(result, "speaker_embedding")
                val shape = tensor.info.shape
                if (shape.size != 2 || shape[1] != Qwen3TtsProtocol.SPEAKER_EMBEDDING_DIM.toLong()) {
                    throw ModelProtocol.UnsupportedModelException(
                        "speaker encoder output shape ${shape.toList()} unexpected")
                }
                val values = FloatArray(shape[1].toInt())
                tensor.floatBuffer.get(values)
                values
            }
        } finally {
            input.close()
        }
        if (embedding.any { !it.isFinite() }) {
            throw ModelProtocol.UnsupportedModelException(
                "speaker encoder produced non-finite values")
        }

        embedding
    }

    private fun validateSpeakerEncoderIo(encoder: OrtSession) {
        val inputs = encoder.inputNames.toList()
        val outputs = encoder.outputNames.toList()
        if (inputs != listOf("mel_spectrogram") || outputs != listOf("speaker_embedding")) {
            throw ModelProtocol.UnsupportedModelException(
                "TTS speaker encoder graph I/O mismatch: inputs=$inputs outputs=$outputs; " +
                    "expected [mel_spectrogram]/[speaker_embedding]")
        }
    }

    // ---- Synthesis -----------------------------------------------------------

    override suspend fun synthesize(
        text: String,
        language: String,
        speakerEmbedding: FloatArray?
    ): SynthesisResult = withContext(Dispatchers.Default) {
        if (!synthesisLoaded) load()
        val cfg = config ?: throw ModelProtocol.UnsupportedModelException("TTS bundle config missing")

        if (speakerEmbedding == null) {
            throw SpeakerVoiceRequiredException(
                "No valid voice profile selected — speech synthesis requires speaker " +
                    "conditioning (no zero-reference fallback)")
        }
        if (speakerEmbedding.size != cfg.hiddenSize) {
            throw ModelProtocol.UnsupportedModelException(
                "speaker embedding dim ${speakerEmbedding.size} != talker hidden ${cfg.hiddenSize}")
        }
        ModelProtocol.requireValidSpeakerEmbedding(speakerEmbedding)
        if (text.isBlank()) {
            throw SpeakerVoiceRequiredException("empty text cannot be synthesized")
        }

        val t0 = System.currentTimeMillis()
        val tok = tokenizer ?: throw ModelProtocol.UnsupportedModelException("TTS tokenizer missing")
        val tables = embeddings ?: throw ModelProtocol.UnsupportedModelException("TTS tables missing")

        val tokenIds = tok.buildPromptIds(text)
        val languageKey = languageKeyFor(language, cfg)
        val built = Qwen3TtsProtocol.buildPrefillEmbeddings(
            tokenIds.toIntArray(), speakerEmbedding, languageKey, cfg, tables)

        // 1. Prefill (serial: release the ~1.7 GB prefill session afterwards).
        val state = try {
            val prefill = modelManager.loadSession(PACKAGE_DIR, TALKER_PREFILL_FILE)
            runPrefill(prefill, built.embeds, cfg)
        } finally {
            modelManager.release("${PACKAGE_DIR}/$TALKER_PREFILL_FILE")
        }

        // 2. Autoregressive loop.
        val codes = try {
            val decode = modelManager.loadSession(PACKAGE_DIR, TALKER_DECODE_FILE)
            val cp = modelManager.loadSession(PACKAGE_DIR, CODE_PREDICTOR_FILE)
            generateFrames(decode, cp, state, built.trailing, cfg, tables)
        } finally {
            modelManager.release("${PACKAGE_DIR}/$TALKER_DECODE_FILE")
        }
        if (codes.frames == 0) {
            throw ModelProtocol.UnsupportedModelException(
                "talker produced no audio frames (immediate codec EOS); " +
                    "synthesis failed rather than emitting silence")
        }

        // 3. Vocoder.
        val waveform = try {
            val vocoder = modelManager.loadSession(PACKAGE_DIR, VOCODER_FILE)
            runVocoder(vocoder, codes)
        } finally {
            modelManager.release("${PACKAGE_DIR}/$VOCODER_FILE")
        }

        if (waveform.isEmpty()) {
            throw ModelProtocol.UnsupportedModelException("vocoder produced zero samples")
        }
        val peak = waveform.maxOf { kotlin.math.abs(it) }
        if (peak < 1e-4f) {
            throw ModelProtocol.UnsupportedModelException(
                "synthesis is silent (peak $peak); refusing to report success")
        }

        SynthesisResult(
            audioData = waveform,
            sampleRate = OUTPUT_SAMPLE_RATE,
            durationMs = waveform.size.toLong() * 1000 / OUTPUT_SAMPLE_RATE,
            inferenceTimeMs = System.currentTimeMillis() - t0
        )
    }

    internal fun languageKeyFor(language: String, cfg: Qwen3TtsBundleConfig): String {
        val code = LanguageCodes.normalize(language)
            ?: throw ModelProtocol.UnsupportedModelException("unsupported TTS language: $language")
        val key = Qwen3TtsProtocol.languageCodeToConfigKey[code]
            ?: throw ModelProtocol.UnsupportedModelException(
                "no bundle language id for '$language' (code $code)")
        if (key !in cfg.languageIds) {
            throw ModelProtocol.UnsupportedModelException(
                "bundle config has no language id for '$key'; known: ${cfg.languageIds.keys}")
        }
        return key
    }

    // ---- Graph passes ----------------------------------------------------------

    private class PrefillState(
        val logits: FloatArray,             // [talkerVocab] (last position)
        val hidden: FloatArray,             // [seqLen * H]
        val pastKeys: FloatArray,           // stacked [layers, 1, kvHeads, seqLen, headDim]
        val pastValues: FloatArray,
        val seqLen: Int,
    )

    private fun runPrefill(
        prefill: OrtSession,
        embeds: FloatArray,
        cfg: Qwen3TtsBundleConfig,
    ): PrefillState {
        val seqLen = embeds.size / cfg.hiddenSize

        val embedTensor = modelManager.createTensor(
            embeds, longArrayOf(1, seqLen.toLong(), cfg.hiddenSize.toLong()))
        val maskTensor = modelManager.createLongTensor(
            LongArray(seqLen) { 1 }, longArrayOf(1, seqLen.toLong()))
        val posTensor = modelManager.createLongTensor(
            LongArray(3 * seqLen) { (it % seqLen).toLong() },
            longArrayOf(3, 1, seqLen.toLong()))
        try {
            prefill.run(mapOf(
                "inputs_embeds" to embedTensor,
                "attention_mask" to maskTensor,
                "position_ids" to posTensor,
            )).use { result ->
                val logits = readFloatOutput(result, "logits")
                if (logits.size != cfg.talkerVocab) {
                    throw ModelProtocol.UnsupportedModelException(
                        "prefill logits size ${logits.size} != vocab ${cfg.talkerVocab}")
                }
                val hidden = readFloatOutput(result, "hidden_states")
                if (hidden.size != seqLen * cfg.hiddenSize) {
                    throw ModelProtocol.UnsupportedModelException(
                        "prefill hidden size ${hidden.size} != ${seqLen * cfg.hiddenSize}")
                }
                val kvShape = intArrayOf(1, cfg.numKvHeads, seqLen, cfg.headDim)
                val keys = Array(cfg.numLayers) { l ->
                    readKvOutput(result, "present_key_$l", kvShape)
                }
                val values = Array(cfg.numLayers) { l ->
                    readKvOutput(result, "present_value_$l", kvShape)
                }
                return PrefillState(
                    logits, hidden,
                    pastKeys = concatenate(keys),
                    pastValues = concatenate(values),
                    seqLen = seqLen,
                )
            }
        } finally {
            embedTensor.close(); maskTensor.close(); posTensor.close()
        }
    }

    /** Named output access: ORT wraps results in Optional; unwrap + type check. */
    private fun outputTensor(result: OrtSession.Result, name: String): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: throw ModelProtocol.UnsupportedModelException("graph produced no '$name' output")
        return value as? OnnxTensor
            ?: throw ModelProtocol.UnsupportedModelException(
                "output '$name' is ${value.javaClass.simpleName}, expected a float tensor")
    }

    private fun readFloatOutput(result: OrtSession.Result, name: String): FloatArray {
        val buf = outputTensor(result, name).floatBuffer
        val values = FloatArray(buf.remaining())
        buf.get(values)
        return values
    }

    /** Reads a KV tensor validating the exact expected shape. */
    private fun readKvOutput(
        result: OrtSession.Result,
        name: String,
        expected: IntArray,
    ): FloatArray {
        val tensor = outputTensor(result, name)
        val shape = tensor.info.shape.map { it.toInt() }.toIntArray()
        if (!shape.contentEquals(expected)) {
            throw ModelProtocol.UnsupportedModelException(
                "KV tensor '$name' shape ${shape.toList()} != expected ${expected.toList()}")
        }
        val buf = tensor.floatBuffer
        val values = FloatArray(buf.remaining())
        buf.get(values)
        return values
    }

    private fun concatenate(perLayer: Array<FloatArray>): FloatArray {
        val total = perLayer.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (layer in perLayer) {
            System.arraycopy(layer, 0, out, offset, layer.size)
            offset += layer.size
        }
        return out
    }

    private fun makeKvTensor(
        data: FloatArray,
        layers: Int,
        kvHeads: Int,
        seqLen: Int,
        headDim: Int,
    ): OnnxTensor = modelManager.createTensor(
        data, longArrayOf(layers.toLong(), 1, kvHeads.toLong(), seqLen.toLong(), headDim.toLong()))

    private class GeneratedCodes(
        val codes: IntArray,   // [NUM_CODEBOOKS * frames]
        val frames: Int,
    )

    private suspend fun generateFrames(
        decode: OrtSession,
        cp: OrtSession,
        state: PrefillState,
        trailing: FloatArray,
        cfg: Qwen3TtsBundleConfig,
        tables: Qwen3TtsEmbeddingLookup,
    ): GeneratedCodes {
        val H = cfg.hiddenSize
        val random = Random(seed = Qwen3TtsProtocol.SAMPLING_SEED)

        var logits = state.logits
        var hidden = state.hidden
        var pastKeys = state.pastKeys
        var pastValues = state.pastValues
        var pastLen = state.seqLen

        val ttsPad = tables.project(tables.textEmbed(cfg.tts.getValue("tts_pad_token_id")))
        val trailingRows = trailing.size / H // text rows + final tts_eos row

        val generated = ArrayList<Int>()
        val allCodes = ArrayList<IntArray>()

        for (step in 0 until maxFrames) {
            currentCoroutineContext().ensureActive()
            // --- group 0 from the talker logits ------------------------------
            val g0 = Qwen3TtsProtocol.sampleGroup0(
                logits, cfg, Qwen3TtsProtocol.TEMPERATURE, Qwen3TtsProtocol.TOP_K,
                Qwen3TtsProtocol.REPETITION_PENALTY,
                generated, random, suppressEos = step < 2)
            if (g0 == cfg.codecEosId) break
            generated.add(g0)

            // --- code predictor: groups 1..15, fresh KV per frame ------------
            val frame = IntArray(Qwen3TtsProtocol.NUM_CODEBOOKS)
            frame[0] = g0
            var cpKeys = FloatArray(0)
            var cpValues = FloatArray(0)
            var cpPastLen = 0
            for (g in 1 until Qwen3TtsProtocol.NUM_CODEBOOKS) {
                currentCoroutineContext().ensureActive()
                val cpInput: FloatArray
                val cpInputSeq: Int
                if (g == 1) {
                    // CP prefill: [talker hidden last position, group-0 embedding]
                    cpInput = FloatArray(2 * H)
                    System.arraycopy(hidden, hidden.size - H, cpInput, 0, H)
                    System.arraycopy(tables.talkerCodecEmbedding(g0), 0, cpInput, H, H)
                    cpInputSeq = 2
                } else {
                    cpInput = tables.cpCodecEmbedding(g - 2, frame[g - 1])
                    cpInputSeq = 1
                }
                val embedTensor = modelManager.createTensor(
                    cpInput, longArrayOf(1, cpInputSeq.toLong(), H.toLong()))
                val stepsTensor = modelManager.createLongTensor(
                    longArrayOf((g - 1).toLong()), longArrayOf(1))
                val keysTensor = makeKvTensor(cpKeys, cfg.cpLayers, cfg.cpKvHeads, cpPastLen, cfg.cpHeadDim)
                val valuesTensor = makeKvTensor(cpValues, cfg.cpLayers, cfg.cpKvHeads, cpPastLen, cfg.cpHeadDim)
                try {
                    cp.run(mapOf(
                        "inputs_embeds" to embedTensor,
                        "generation_steps" to stepsTensor,
                        "past_keys" to keysTensor,
                        "past_values" to valuesTensor,
                    )).use { cpResult ->
                        val cpLogits = readFloatOutput(cpResult, "logits")
                        // sampleCodePredictor copies the trailing cpVocab logits
                        // itself; pass the full array (the pre-slice was a
                        // redundant per-step copy).
                        val token = Qwen3TtsProtocol.sampleCodePredictor(
                            cpLogits,
                            cfg, Qwen3TtsProtocol.TEMPERATURE, Qwen3TtsProtocol.TOP_K, random)
                        frame[g] = token
                        val shape = intArrayOf(
                            cfg.cpLayers, 1, cfg.cpKvHeads, cpPastLen + cpInputSeq, cfg.cpHeadDim)
                        cpKeys = readKvOutput(cpResult, "present_keys", shape)
                        cpValues = readKvOutput(cpResult, "present_values", shape)
                        cpPastLen += cpInputSeq
                    }
                } finally {
                    embedTensor.close(); stepsTensor.close()
                    keysTensor.close(); valuesTensor.close()
                }
            }
            allCodes.add(frame)

            // --- next talker input: sum of 16 group embeds + text/pad --------
            val next = FloatArray(H)
            System.arraycopy(tables.talkerCodecEmbedding(frame[0]), 0, next, 0, H)
            for (g in 1 until Qwen3TtsProtocol.NUM_CODEBOOKS) {
                val cpEmb = tables.cpCodecEmbedding(g - 1, frame[g])
                for (j in 0 until H) next[j] += cpEmb[j]
            }
            if (step < trailingRows) {
                for (j in 0 until H) next[j] += trailing[step * H + j]
            } else {
                for (j in 0 until H) next[j] += ttsPad[j]
            }

            // --- one decode step ----------------------------------------------
            val totalLen = pastLen + 1
            val nextTensor = modelManager.createTensor(next, longArrayOf(1, 1, H.toLong()))
            val maskTensor = modelManager.createLongTensor(
                LongArray(totalLen) { 1 }, longArrayOf(1, totalLen.toLong()))
            val position = (state.seqLen + step).toLong()
            val posTensor = modelManager.createLongTensor(
                longArrayOf(position, position, position), longArrayOf(3, 1, 1))
            val keysTensor = makeKvTensor(pastKeys, cfg.numLayers, cfg.numKvHeads, pastLen, cfg.headDim)
            val valuesTensor = makeKvTensor(pastValues, cfg.numLayers, cfg.numKvHeads, pastLen, cfg.headDim)
            try {
                decode.run(mapOf(
                    "inputs_embeds" to nextTensor,
                    "attention_mask" to maskTensor,
                    "position_ids" to posTensor,
                    "past_keys" to keysTensor,
                    "past_values" to valuesTensor,
                )).use { decResult ->
                    logits = readFloatOutput(decResult, "logits")
                    hidden = readFloatOutput(decResult, "hidden_states")
                    if (hidden.size != H) {
                        throw ModelProtocol.UnsupportedModelException(
                            "decode hidden size ${hidden.size} != $H")
                    }
                    val kvShape = intArrayOf(cfg.numLayers, 1, cfg.numKvHeads, totalLen, cfg.headDim)
                    pastKeys = readKvOutput(decResult, "present_keys", kvShape)
                    pastValues = readKvOutput(decResult, "present_values", kvShape)
                    pastLen = totalLen
                }
            } finally {
                nextTensor.close(); maskTensor.close(); posTensor.close()
                keysTensor.close(); valuesTensor.close()
            }
        }

        if (allCodes.size >= maxFrames) {
            throw ModelProtocol.UnsupportedModelException(
                "TTS frame budget exhausted before codec EOS ($maxFrames)")
        }
        // Vocoder input layout: codes[1, 16, T] is GROUP-major —
        // flat[g * frames + f] (see Qwen3TtsProtocol.flattenCodesGroupMajor).
        return GeneratedCodes(
            codes = Qwen3TtsProtocol.flattenCodesGroupMajor(
                allCodes, Qwen3TtsProtocol.NUM_CODEBOOKS),
            frames = allCodes.size)
    }

    private fun runVocoder(vocoder: OrtSession, codes: GeneratedCodes): FloatArray {
        val inputNames = vocoder.inputNames.toList()
        if (inputNames != listOf("codes")) {
            throw ModelProtocol.UnsupportedModelException(
                "vocoder graph inputs $inputNames unexpected; expected [codes]")
        }
        val codeTensor = modelManager.createLongTensor(
            codes.codes.map { it.toLong() }.toLongArray(),
            longArrayOf(1, Qwen3TtsProtocol.NUM_CODEBOOKS.toLong(), codes.frames.toLong()))
        try {
            vocoder.run(mapOf("codes" to codeTensor)).use { result ->
                val tensor = outputTensor(result, "waveform")
                val buf = tensor.floatBuffer
                val waveform = FloatArray(buf.remaining())
                buf.get(waveform)
                val expected = codes.frames * Qwen3TtsProtocol.SAMPLES_PER_FRAME
                if (waveform.size != expected) {
                    throw ModelProtocol.UnsupportedModelException(
                        "vocoder produced ${waveform.size} samples, expected $expected")
                }
                for (i in waveform.indices) {
                    waveform[i] = waveform[i].coerceIn(-1f, 1f)
                }
                return waveform
            }
        } finally {
            codeTensor.close()
        }
    }

    override fun release() {
        synchronized(api2Lock) { api2Runtime?.release(); api2Runtime = null; api2Manifest = null }
        speakerEncoderLoaded = false
        synthesisLoaded = false
        tokenizer = null
        embeddings = null
        config = null
        for (file in listOf(SPEAKER_ENCODER_FILE, TALKER_PREFILL_FILE, TALKER_DECODE_FILE,
                CODE_PREDICTOR_FILE, VOCODER_FILE)) {
            modelManager.release("${PACKAGE_DIR}/$file")
        }
        Log.i(TAG, "TTS engine released")
    }

    // ---- Prepared-reference facade (API2) -------------------------------------

    fun supportsPreparedReference(): Boolean = when (val version = resolveApi2Manifest()?.apiContractVersion) {
        null, "1" -> false
        "2" -> true
        else -> throw ModelProtocol.UnsupportedModelException("unsupported TTS API contract: $version")
    }

    private val api2Lock = Any()
    @Volatile private var api2Manifest: ModelManifests.PackageManifest? = null
    @Volatile private var api2Runtime: TtsApi2Runtime? = null

    /**
     * Resolves the tts package manifest from the device model directory
     * (`tts/manifest.json`); null when the installation has no manifest, in
     * which case only the API1 paths are available.
     */
    private fun resolveApi2Manifest(): ModelManifests.PackageManifest? {
        api2Manifest?.let { return it }
        val file = File(modelManager.getModelsDir(), "$PACKAGE_DIR/manifest.json")
        if (!file.isFile) return null
        val manifest = ModelManifests.parse(file.readText())
        if (manifest.packageId != "tts") {
            throw ModelProtocol.UnsupportedModelException(
                "tts/manifest.json declares package ${manifest.packageId}, expected tts")
        }
        api2Manifest = manifest
        return manifest
    }

    /** Lazily created API2 runtime for contract-version-2 packages. */
    private fun api2(): TtsApi2Runtime {
        api2Runtime?.let { return it }
        synchronized(api2Lock) {
            api2Runtime?.let { return it }
            val manifest = resolveApi2Manifest()
                ?: throw ModelProtocol.UnsupportedModelException(
                    "prepared-reference synthesis requires tts/manifest.json with " +
                        "runtime.apiContractVersion == \"2\"; this installation is API1-only")
            if (manifest.apiContractVersion != "2") {
                throw ModelProtocol.UnsupportedModelException(
                    "prepared-reference synthesis requires runtime.apiContractVersion == \"2\", " +
                        "got ${manifest.apiContractVersion}")
            }
            val runtime = TtsApi2Runtime(modelManager, manifest, maxFrames = maxFrames)
            api2Runtime = runtime
            return runtime
        }
    }

    /**
     * API2 prepared-reference entry: runs the real speaker/reference graphs
     * once and returns an immutable conditioning package (see
     * [TtsApi2Runtime.prepareReference]). With a reference text this is the ICL
     * path; without one it stays explicitly xvector-only — no transcript is
     * ever fabricated. Requires the manifest gate `apiContractVersion == "2"`.
     */
    suspend fun prepareReference(
        referenceAudio: FloatArray,
        inputSampleRate: Int = REFERENCE_INPUT_SAMPLE_RATE,
        referenceText: String? = null,
    ): TtsApi2Runtime.PreparedReference = api2().prepareReference(
        referenceAudio, inputSampleRate = inputSampleRate, referenceText = referenceText)

    /**
     * API2 synthesis against a prepared reference. The optional [onAudioChunk]
     * sink receives only positive-length PCM chunks as produced; there is no
     * end-of-stream protocol on the sink — normal method return IS generation
     * completion (EOS), after which the caller waits for the playback tail
     * (e.g. `AudioPlayer.playStream` drain). The result always carries the
     * complete waveform. Requires the manifest gate `apiContractVersion == "2"`.
     */
    suspend fun synthesizePrepared(
        prepared: TtsApi2Runtime.PreparedReference,
        text: String,
        language: String,
        onAudioChunk: (suspend (chunk: FloatArray) -> Unit)? = null,
    ): SynthesisResult = api2().synthesizePrepared(prepared, text, language, onAudioChunk)
}
