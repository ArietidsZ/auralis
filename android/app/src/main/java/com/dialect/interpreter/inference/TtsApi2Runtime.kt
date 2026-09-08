package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import com.dialect.interpreter.data.ModelManifests
import com.k2fsa.sherpa.onnx.SherpaJni
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.random.Random

/**
 * Qwen3-TTS API2 runtime (manifest runtime.apiContractVersion == "2").
 *
 * Roles (manifest `roles`): `speaker_encoder`, `talker`, `code_predictor`,
 * `reference_encoder`, `vocoder`. ONE `talker` session serves dynamic prefill
 * AND decode; sessions stay cached while the engine is active and are
 * released by [release]. Two roles that point at the same manifest file share
 * one session (canonical-path keying in [OnnxModelManager.loadRoleSession]).
 *
 * The `vocoder` is the stateful streaming graph. Per-utterance state starts
 * from the prepared reference's warm snapshot (ICL) or from all zeros
 * (xvector-only), never crosses utterances or voices, and a failed or
 * cancelled turn discards its working state without touching the snapshot.
 * Reference PCM is consumed for context and never played; EOF adds no
 * padding, no zero codes and no repeated history.
 *
 * [prepareReference] runs the real graphs once per reference: speaker
 * embedding (xvector port value) and — only with a caller-provided reference
 * text — the reference-codec encoder plus the vocoder warm state after
 * consuming the reference codes. The warm snapshot (~5.2 MB of immutable
 * state) is computed once, owned by the returned [PreparedReference], bound
 * to the reference PCM/text/model identities, and handed to every turn as an
 * independent copy. It is never a global profile cache; no transcript is ever
 * fabricated — without a reference text the engine stays explicitly
 * xvector-only.
 *
 * Sampling, NPY tables, DSP and input guards are shared with the API1 engine
 * (same [Qwen3TtsProtocol] / [TtsBundleAssets] / sherpa resampler): default
 * temperature 0.9, topK 50, repetition penalty applied once per distinct
 * token, seed [Qwen3TtsProtocol.SAMPLING_SEED] reset per synthesis, EOS
 * failure on budget exhaustion, cancellation checked per frame/codebook.
 * BF16/quantization are never enabled here.
 */
class TtsApi2Runtime(
    private val modelManager: OnnxModelManager,
    private val manifest: ModelManifests.PackageManifest,
    private val maxFrames: Int = Qwen3TtsProtocol.DEFAULT_FRAME_BUDGET,
    /** Streaming vocoder frames per step; 4 in production, test knob for chunk invariance. */
    private val chunkFrames: Int = CHUNK_FRAMES,
) {
    companion object {
        private const val TAG = "TtsApi2Runtime"
        private const val SUB_DIR = "tts"
        private const val INPUT_SAMPLE_RATE_DEFAULT = 16000

        /** Fixed streaming-vocoder state sizes from the frozen graph interface. */
        private const val CONV_STATE_FLOATS = 135232
        private const val VOC_KV_FLOATS = 8 * 1 * 16 * 71 * 64

        /** Target PCM frames per streaming vocoder step (4-frame chunks). */
        private const val CHUNK_FRAMES = 4
        private const val MAX_CHUNK_FRAMES = 4096

        /** 30 s reference cap: 24000 * 30 / 1920. */
        private const val MAX_REFERENCE_FRAMES = 375

        private val REQUIRED_ROLES = listOf(
            "speaker_encoder", "talker", "code_predictor", "reference_encoder", "vocoder")

        private val VOCODER_INPUTS = sortedSetOf(
            "codes", "conv_state", "past_keys", "past_values", "position")
        private val VOCODER_OUTPUTS = sortedSetOf(
            "waveform", "conv_state_out", "present_keys", "present_values", "position_out")
    }

    init {
        require(manifest.packageId == "tts") { "API2 runtime requires the tts package" }
        require(manifest.runtimeBackend == "onnx") {
            "API2 runtime supports only the onnx backend, got ${manifest.runtimeBackend}"
        }
        require(manifest.apiContractVersion == "2") {
            "API2 runtime requires runtime.apiContractVersion == \"2\", got ${manifest.apiContractVersion}"
        }
        val missing = REQUIRED_ROLES.filterNot { manifest.roles.containsKey(it) }
        require(missing.isEmpty()) { "manifest roles missing: $missing" }
        require(maxFrames in 1..Qwen3TtsProtocol.DEFAULT_FRAME_BUDGET) {
            "TTS frame budget must be in 1..${Qwen3TtsProtocol.DEFAULT_FRAME_BUDGET}"
        }
        require(chunkFrames in 1..MAX_CHUNK_FRAMES) {
            "vocoder chunk must be in 1..$MAX_CHUNK_FRAMES frames"
        }
    }

    private val mutex = Mutex()
    private val modelsDir: File get() = modelManager.getModelsDir()

    /** Identity of this runtime instance for prepared-reference binding. */
    private val engineToken = Any()

    /** Bumped by [release]; prepared references from older generations are stale. */
    private val generation = AtomicInteger(0)

    @Volatile private var config: Qwen3TtsBundleConfig? = null
    @Volatile private var embeddings: Qwen3TtsEmbeddings? = null
    @Volatile private var tokenizer: Qwen3TtsProtocol.Qwen3TtsTokenizer? = null

    /** Immutable vocoder state after consuming the reference codes (ICL only). */
    internal class VocoderWarmState(
        val convState: FloatArray,   // [135232]
        val pastKeys: FloatArray,    // [8,1,16,71,64]
        val pastValues: FloatArray,  // [8,1,16,71,64]
        val position: Long,          // == referenceFrames
    ) {
        fun copy() = VocoderWarmState(convState.copyOf(), pastKeys.copyOf(), pastValues.copyOf(), position)
    }

    /**
     * Result of [prepareReference]. [embedding] is the public xvector value so
     * legacy ports can keep calling `synthesize(text, language, embedding)`;
     * the ICL fields are present only when a reference text was supplied.
     * Conditioning is explicit in these fields — never inferred from
     * embedding values.
     *
     * The conditioning package is immutable: the array-typed properties are
     * defensively copied at construction AND on every read, so neither the
     * producer nor any consumer can reach the stored conditioning through a
     * shared array reference. [embedding], [referenceTokenIds] and
     * [referenceCodes] each hand out an independent copy; mutating a
     * handed-out array, or the arrays passed to the constructor, can never
     * change what [synthesizePrepared] later consumes. [referenceText] is an
     * immutable String. The internal [vocoderWarmState] is intentionally kept
     * by reference (it is never exposed through the public surface) and is
     * consumed only through the engine's per-turn state builder, which copies
     * every array, so no warm-state alias escapes the engine either.
     *
     * A prepared reference is bound to the producing [TtsApi2Runtime] instance
     * and its loading generation: after the engine was released (and reloaded)
     * it is stale and [synthesizePrepared] rejects it; passing it to another
     * engine instance is rejected for the same reason.
     */
    class PreparedReference internal constructor(
        embedding: FloatArray,
        referenceText: String?,
        referenceTokenIds: IntArray?,
        /** Group-major [16 * frames] codec ids for the reference audio (ICL only). */
        referenceCodes: IntArray?,
        val referenceFrames: Int,
        /** sha256 over PCM bytes + reference text + role file identities. */
        val identity: String,
        internal val vocoderWarmState: VocoderWarmState?,
        internal val engineToken: Any,
        internal val generation: Int,
    ) {
        // Private snapshots: the constructor owns its own copy of the caller's
        // arrays and every read hands out a fresh copy over that snapshot.
        val embedding: FloatArray = embedding.copyOf()
            get() = field.copyOf()
        val referenceText: String? = referenceText
        val referenceTokenIds: IntArray? = referenceTokenIds?.copyOf()
            get() = field?.copyOf()
        val referenceCodes: IntArray? = referenceCodes?.copyOf()
            get() = field?.copyOf()

        /** ICL conditioning is present exactly when reference codes were supplied. */
        val isIcl: Boolean = referenceCodes != null
    }

    // ---- Reference preparation -------------------------------------------------

    /**
     * Runs the real speaker/reference graphs once per reference. The vocoder
     * warm state (ICL) consumes the reference codes and discards the
     * corresponding PCM; it is computed exactly here once and handed to every
     * turn as an independent copy.
     */
    suspend fun prepareReference(
        referenceAudio: FloatArray,
        inputSampleRate: Int = INPUT_SAMPLE_RATE_DEFAULT,
        referenceText: String? = null,
    ): PreparedReference = mutex.withLock {
        require(referenceAudio.isNotEmpty()) { "Reference audio is empty" }
        require(inputSampleRate in 8000..192000) { "Unsupported reference sample rate" }
        require(referenceAudio.size.toLong() <= inputSampleRate.toLong() * 30) {
            "Reference audio exceeds 30 seconds"
        }
        var peak = 0f
        for (sample in referenceAudio) {
            require(sample.isFinite()) { "Reference audio must be finite" }
            peak = max(peak, kotlin.math.abs(sample))
        }
        require(peak >= 1e-4f) { "Reference audio is silent or too quiet" }
        if (referenceText != null) {
            require(referenceText.isNotBlank()) {
                "blank reference text is rejected; pass null for explicit xvector-only preparation"
            }
        }

        loadAssetsLocked()
        val cfg = config ?: throw ModelProtocol.UnsupportedModelException("TTS bundle config missing")
        val roles = manifest.roles

        try {
            withContext(Dispatchers.Default) {
                SherpaJni.load()
                val pcm24k = SherpaJni.resample(referenceAudio, inputSampleRate, Qwen3TtsProtocol.SAMPLE_RATE)

                // Speaker embedding: same raw, un-normalized value the API1 port consumes.
                val embedding = runSpeakerEncoder(pcm24k)

                var refTokenIds: IntArray? = null
                var refCodes: IntArray? = null
                var refFrames = 0
                var warmState: VocoderWarmState? = null
                if (referenceText != null) {
                    val tok = tokenizer ?: throw ModelProtocol.UnsupportedModelException("TTS tokenizer missing")
                    // Official reference wrapper; the prompt consumes ids[3:-2].
                    refTokenIds = tok.encode(
                        "<|im_start|>assistant\n$referenceText<|im_end|>\n").toIntArray()
                    if (refTokenIds.size < 6) {
                        throw ModelProtocol.UnsupportedModelException(
                            "reference text wraps to only ${refTokenIds.size} tokens")
                    }
                    val encodeStart = System.nanoTime()
                    refCodes = runReferenceEncoder(pcm24k, cfg)
                    refFrames = refCodes.size / Qwen3TtsProtocol.NUM_CODEBOOKS
                    warmState = computeVocoderWarmState(refCodes, refFrames)
                    Log.i(TAG, "reference prepared: frames=$refFrames " +
                        "encodeMs=${(System.nanoTime() - encodeStart) / 1e6}")
                }

                // Identity binds the snapshot to its PCM, text and model files.
                val digest = MessageDigest.getInstance("SHA-256")
                fun feed(text: String) = digest.update(text.toByteArray(Charsets.UTF_8))
                feed("pcm24k:")
                val pcmBytes = ByteBuffer.allocate(pcm24k.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                pcmBytes.asFloatBuffer().put(pcm24k)
                digest.update(pcmBytes.array())
                feed("\ntext:${referenceText ?: ""}\n")
                for (role in REQUIRED_ROLES) feed("$role:${roleFileIdentity(roles, role)}\n")
                val identity = digest.digest().joinToString("") { "%02x".format(it) }

                PreparedReference(
                    embedding, referenceText, refTokenIds, refCodes, refFrames, identity, warmState,
                    this@TtsApi2Runtime.engineToken, generation.get())
            }
        } finally {
            // The reference encoder is one-shot per preparation; release it
            // eagerly so an idle engine does not hold ~190 MB of weights.
            // finally — not success-only — so a failed ICL preparation
            // (tokenizer, reference-encoder or warm-state validation) or a
            // cancellation inside the block also releases the ~190 MB session
            // instead of leaking it until release(). For xvector-only
            // preparations the role was never loaded and releaseRole is a
            // harmless no-op (sessions.remove(key)?.close()).
            modelManager.releaseRole(SUB_DIR, roles, "reference_encoder")
        }
    }

    /** Manifest file identities (graph sha / external-data sha) for the snapshot binding. */
    private fun roleFileIdentity(roles: Map<String, String>, role: String): String {
        val relative = roles[role] ?: return "missing"
        val graphSha = manifest.files.firstOrNull { it.path == relative }?.sha256
        val dataSha = manifest.files.firstOrNull { it.path == "$relative.data" }?.sha256
        return "${graphSha ?: "unhashed"}/${dataSha ?: "unhashed"}"
    }

    private suspend fun runSpeakerEncoder(pcm24k: FloatArray): FloatArray {
        val encoder = modelManager.loadRoleSession(SUB_DIR, manifest.roles, "speaker_encoder")
        if (encoder.inputNames.toList() != listOf("mel_spectrogram") ||
            encoder.outputNames.toList() != listOf("speaker_embedding")) {
            throw ModelProtocol.UnsupportedModelException(
                "speaker encoder graph I/O mismatch: ${encoder.inputNames}/${encoder.outputNames}")
        }
        val mel = Qwen3TtsProtocol.logMelSpectrogram(pcm24k, Qwen3TtsProtocol.SAMPLE_RATE)
        val input = modelManager.createTensor(mel.data, longArrayOf(1, mel.frames.toLong(), 128))
        try {
            encoder.run(mapOf("mel_spectrogram" to input)).use { result ->
                val tensor = outputTensor(result, "speaker_embedding")
                val shape = tensor.info.shape
                if (shape.size != 2 || shape[1] != Qwen3TtsProtocol.SPEAKER_EMBEDDING_DIM.toLong()) {
                    throw ModelProtocol.UnsupportedModelException(
                        "speaker encoder output shape ${shape.toList()} unexpected")
                }
                val values = FloatArray(shape[1].toInt())
                tensor.floatBuffer.get(values)
                if (values.any { !it.isFinite() }) {
                    throw ModelProtocol.UnsupportedModelException(
                        "speaker encoder produced non-finite values")
                }
                return values
            }
        } finally {
            input.close()
        }
    }

    /** Real reference-codec encoder: pcm float32[1,1,N] @24k -> codes int64[1,16,R]. */
    private suspend fun runReferenceEncoder(pcm24k: FloatArray, cfg: Qwen3TtsBundleConfig): IntArray {
        val encoder = modelManager.loadRoleSession(SUB_DIR, manifest.roles, "reference_encoder")
        if (encoder.inputNames.toList() != listOf("pcm") ||
            encoder.outputNames.toList() != listOf("codes")) {
            throw ModelProtocol.UnsupportedModelException(
                "reference encoder graph I/O mismatch: ${encoder.inputNames}/${encoder.outputNames}; " +
                    "expected [pcm]/[codes]")
        }
        val expectedR = (pcm24k.size + Qwen3TtsProtocol.SAMPLES_PER_FRAME - 1) /
            Qwen3TtsProtocol.SAMPLES_PER_FRAME
        val input = modelManager.createTensor(pcm24k, longArrayOf(1, 1, pcm24k.size.toLong()))
        try {
            encoder.run(mapOf("pcm" to input)).use { result ->
                val tensor = outputTensor(result, "codes")
                val shape = tensor.info.shape
                if (shape.size != 3 || shape[0] != 1L ||
                    shape[1] != Qwen3TtsProtocol.NUM_CODEBOOKS.toLong() ||
                    shape[2] != expectedR.toLong()) {
                    throw ModelProtocol.UnsupportedModelException(
                        "reference encoder codes shape ${shape.toList()} unexpected; expected [1,16,$expectedR]")
                }
                val flat = LongArray(tensor.longBuffer.remaining())
                tensor.longBuffer.get(flat)
                for (value in flat) {
                    if (value < 0 || value >= cfg.cpVocab) {
                        throw ModelProtocol.UnsupportedModelException(
                            "reference encoder returned reserved or out-of-range codec id $value")
                    }
                }
                return IntArray(flat.size) { flat[it].toInt() } // group-major [16*R]
            }
        } finally {
            input.close()
        }
    }

    /**
     * Feeds the reference codes to the stateful vocoder once (PCM discarded)
     * and snapshots the resulting state. Inputs/outputs are separate tensors;
     * the snapshot is never aliased to a live turn.
     */
    private suspend fun computeVocoderWarmState(refCodes: IntArray, refFrames: Int): VocoderWarmState {
        require(refFrames in 1..MAX_REFERENCE_FRAMES) {
            "reference codes must span 1..$MAX_REFERENCE_FRAMES frames, got $refFrames"
        }
        val vocoder = modelManager.loadRoleSession(SUB_DIR, manifest.roles, "vocoder")
        validateVocoderIo(vocoder)
        val codeTensor = modelManager.createLongTensor(
            refCodes.map { it.toLong() }.toLongArray(),
            longArrayOf(1, Qwen3TtsProtocol.NUM_CODEBOOKS.toLong(), refFrames.toLong()))
        val convTensor = modelManager.createTensor(
            FloatArray(CONV_STATE_FLOATS), longArrayOf(CONV_STATE_FLOATS.toLong()))
        val keysTensor = modelManager.createTensor(
            FloatArray(VOC_KV_FLOATS), longArrayOf(8, 1, 16, 71, 64))
        val valuesTensor = modelManager.createTensor(
            FloatArray(VOC_KV_FLOATS), longArrayOf(8, 1, 16, 71, 64))
        val positionTensor = modelManager.createLongTensor(longArrayOf(0), longArrayOf(1))
        try {
            vocoder.run(mapOf(
                "codes" to codeTensor,
                "conv_state" to convTensor,
                "past_keys" to keysTensor,
                "past_values" to valuesTensor,
                "position" to positionTensor,
            )).use { result ->
                // The waveform for the reference context is intentionally discarded.
                return readVocoderState(result, expectedPosition = refFrames.toLong())
            }
        } finally {
            codeTensor.close(); convTensor.close(); keysTensor.close()
            valuesTensor.close(); positionTensor.close()
        }
    }

    private fun validateVocoderIo(vocoder: OrtSession) {
        if (sortedSetOf(*vocoder.inputNames.toTypedArray()) != VOCODER_INPUTS ||
            sortedSetOf(*vocoder.outputNames.toTypedArray()) != VOCODER_OUTPUTS) {
            throw ModelProtocol.UnsupportedModelException(
                "state vocoder I/O mismatch: in=${vocoder.inputNames} out=${vocoder.outputNames}")
        }
    }

    /** Per-turn mutable vocoder state; starts as a snapshot copy or all zeros. */
    private class VocoderTurnState(
        val convState: FloatArray,
        val pastKeys: FloatArray,
        val pastValues: FloatArray,
        var position: Long,
    ) {
        companion object {
            fun zero() = VocoderTurnState(
                FloatArray(CONV_STATE_FLOATS), FloatArray(VOC_KV_FLOATS),
                FloatArray(VOC_KV_FLOATS), 0L)

            fun fromSnapshot(snapshot: VocoderWarmState) = VocoderTurnState(
                snapshot.convState.copyOf(), snapshot.pastKeys.copyOf(),
                snapshot.pastValues.copyOf(), snapshot.position)
        }
    }

    /**
     * Reads the next state from a successful step and validates the position
     * bookkeeping ([expectedPosition] = input position + frames consumed).
     */
    private fun readVocoderState(result: OrtSession.Result, expectedPosition: Long): VocoderWarmState {
        val conv = readFloatOutput(result, "conv_state_out")
        val keys = readFloatOutput(result, "present_keys")
        val values = readFloatOutput(result, "present_values")
        val positionTensor = outputTensor(result, "position_out")
        val position = LongArray(positionTensor.longBuffer.remaining())
        positionTensor.longBuffer.get(position)
        if (conv.size != CONV_STATE_FLOATS || keys.size != VOC_KV_FLOATS ||
            values.size != VOC_KV_FLOATS || position.size != 1) {
            throw ModelProtocol.UnsupportedModelException(
                "state vocoder output sizes unexpected: conv=${conv.size} " +
                    "kv=${keys.size}/${values.size} pos=${position.toList()}")
        }
        if (position[0] != expectedPosition) {
            throw ModelProtocol.UnsupportedModelException(
                "state vocoder position_out ${position[0]} != expected $expectedPosition")
        }
        return VocoderWarmState(conv, keys, values, position[0])
    }

    // ---- Synthesis ----------------------------------------------------------------

    /**
     * Synthesizes against a prepared reference. The result always carries the
     * complete waveform (concatenation of the produced chunks), so the legacy
     * pipeline keeps compiling; with a non-null [onAudioChunk] each full
     * [chunkFrames]-frame PCM chunk (owned copy, clipped to [-1,1]) is
     * delivered IMMEDIATELY as produced from the per-frame generation loop —
     * the sink receives only positive-length PCM and may suspend for
     * backpressure; its failure or cancellation aborts the turn without
     * committing state. There is no end-of-stream protocol on the sink:
     * the method returning normally IS generation completion (EOS), after
     * which the caller waits for the playback tail. A turn that fails (no EOS
     * within budget, cancellation, sink failure) may have delivered audio
     * already; delivered chunks are never un-delivered.
     */
    suspend fun synthesizePrepared(
        prepared: PreparedReference,
        text: String,
        language: String,
        onAudioChunk: (suspend (chunk: FloatArray) -> Unit)? = null,
    ): TtsEngine.SynthesisResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (prepared.engineToken !== engineToken || prepared.generation != generation.get()) {
                throw IllegalArgumentException(
                    "prepared reference is stale: it belongs to a different engine instance " +
                        "or to a generation before the last release(); prepare it again")
            }
            val cfg = loadAssetsLocked()
            val tables = embeddings ?: throw ModelProtocol.UnsupportedModelException("TTS tables missing")
            val tok = tokenizer ?: throw ModelProtocol.UnsupportedModelException("TTS tokenizer missing")

            if (text.isBlank()) {
                throw TtsEngine.SpeakerVoiceRequiredException("empty text cannot be synthesized")
            }
            if (prepared.embedding.size != cfg.hiddenSize) {
                throw ModelProtocol.UnsupportedModelException(
                    "speaker embedding dim ${prepared.embedding.size} != talker hidden ${cfg.hiddenSize}")
            }
            ModelProtocol.requireValidSpeakerEmbedding(prepared.embedding)

            val t0 = System.currentTimeMillis()
            val languageKey = Qwen3TtsProtocol.languageConfigKey(language, cfg)
            val tokenIds = tok.buildPromptIds(text).toIntArray()

            // Prefill body + trailing: xvector reuses the verified API1 builder;
            // ICL inserts the reference context in place of the first-text row.
            val prefillRows: FloatArray
            val trailing: FloatArray
            if (prepared.isIcl) {
                val refTokenIds = prepared.referenceTokenIds
                val refCodes = prepared.referenceCodes
                if (refTokenIds == null || refCodes == null || prepared.referenceFrames <= 0) {
                    throw ModelProtocol.UnsupportedModelException("ICL prepared reference is incomplete")
                }
                val prefix = Qwen3TtsProtocol.buildPrefillEmbeddings(
                    tokenIds, prepared.embedding, languageKey, cfg, tables)
                if (prefix.embeds.size < 9 * cfg.hiddenSize) {
                    throw ModelProtocol.UnsupportedModelException("xvector prefix rows unexpectedly short")
                }
                val body = buildIclBody(tokenIds, refTokenIds, refCodes, prepared.referenceFrames, cfg, tables)
                prefillRows = prefix.embeds.copyOfRange(0, 9 * cfg.hiddenSize) + body.first
                trailing = body.second
            } else {
                val built = Qwen3TtsProtocol.buildPrefillEmbeddings(
                    tokenIds, prepared.embedding, languageKey, cfg, tables)
                prefillRows = built.embeds
                trailing = built.trailing
            }

            // ONE talker session for prefill + decode; sessions cached while active.
            val talker = modelManager.loadRoleSession(SUB_DIR, manifest.roles, "talker")
            val cp = modelManager.loadRoleSession(SUB_DIR, manifest.roles, "code_predictor")
            val vocoder = modelManager.loadRoleSession(SUB_DIR, manifest.roles, "vocoder")
            validateVocoderIo(vocoder)

            var state = runPrefill(talker, prefillRows, cfg)
            try {
                val vocState = if (prepared.isIcl) {
                    VocoderTurnState.fromSnapshot(
                        prepared.vocoderWarmState ?: throw ModelProtocol.UnsupportedModelException(
                            "ICL prepared reference has no vocoder warm state"))
                } else {
                    VocoderTurnState.zero()
                }

                val H = cfg.hiddenSize
                val random = Random(seed = Qwen3TtsProtocol.SAMPLING_SEED)

                val ttsPad = tables.project(tables.textEmbed(cfg.tts.getValue("tts_pad_token_id")))
                val trailingRows = trailing.size / H
                val generated = ArrayList<Int>()
                val allCodes = ArrayList<IntArray>()
                val accumulated = ArrayList<FloatArray>()

                var pendingCodes = ArrayList<IntArray>()

                suspend fun vocodeChunk(frames: List<IntArray>) {
                    if (frames.isEmpty()) return
                    val flat = Qwen3TtsProtocol.flattenCodesGroupMajor(frames, Qwen3TtsProtocol.NUM_CODEBOOKS)
                    val waveform = runVocoderChunk(vocoder, vocState, flat, frames.size)
                    accumulated.add(waveform)
                    // Immediate delivery from the generation loop: the sink starts
                    // consuming while later frames still decode. Positive-length
                    // PCM only — method return is the completion signal.
                    onAudioChunk?.invoke(waveform.copyOf())
                }

                for (step in 0 until maxFrames) {
                    currentCoroutineContext().ensureActive()
                    val g0 = Qwen3TtsProtocol.sampleGroup0(
                        state.logits, cfg, Qwen3TtsProtocol.TEMPERATURE, Qwen3TtsProtocol.TOP_K,
                        Qwen3TtsProtocol.REPETITION_PENALTY,
                        generated, random, suppressEos = step < 2)
                    if (g0 == cfg.codecEosId) break
                    generated.add(g0)

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
                            // CP prefill: [talker last hidden, group-0 embedding]
                            cpInput = FloatArray(2 * H)
                            System.arraycopy(state.lastHidden, 0, cpInput, 0, H)
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
                                // sampleCodePredictor consumes only the last
                                // cfg.cpVocab logits itself; pass the full
                                // array instead of pre-slicing it (the slice
                                // was a redundant copy on every codebook step).
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
                    pendingCodes.add(frame)
                    if (pendingCodes.size == chunkFrames) {
                        val chunk = pendingCodes
                        pendingCodes = ArrayList()
                        vocodeChunk(chunk)
                    }

                    // Next talker input: sum of the 16 group embeds + text/pad.
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

                    val totalLen = state.seqLen + 1
                    val nextTensor = modelManager.createTensor(next, longArrayOf(1, 1, H.toLong()))
                    val maskTensor = modelManager.createLongTensor(
                        LongArray(totalLen) { 1 }, longArrayOf(1, totalLen.toLong()))
                    val position = state.seqLen.toLong()
                    val posTensor = modelManager.createLongTensor(
                        longArrayOf(position, position, position), longArrayOf(3, 1, 1))
                    try {
                        val nextState = readTalkerState(talker.run(mapOf(
                            "inputs_embeds" to nextTensor,
                            "attention_mask" to maskTensor,
                            "position_ids" to posTensor,
                            "past_keys" to state.pastKeys,
                            "past_values" to state.pastValues,
                        )), totalLen, cfg)
                        val previous = state
                        state = nextState
                        previous.close()
                    } finally {
                        nextTensor.close(); maskTensor.close(); posTensor.close()
                    }
                }

                if (allCodes.size >= maxFrames) {
                    throw ModelProtocol.UnsupportedModelException(
                        "TTS frame budget exhausted before codec EOS ($maxFrames)")
                }
                if (allCodes.isEmpty()) {
                    throw ModelProtocol.UnsupportedModelException(
                        "talker produced no audio frames (immediate codec EOS); " +
                            "synthesis failed rather than emitting silence")
                }

                // Positive tail chunk at EOS; a boundary EOS has an empty tail and
                // delivers nothing — completion is signaled by normal return.
                val tail = pendingCodes
                pendingCodes = ArrayList()
                vocodeChunk(tail)

                var total = 0
                for (chunk in accumulated) total += chunk.size
                val waveform = FloatArray(total)
                var offset = 0
                for (chunk in accumulated) {
                    System.arraycopy(chunk, 0, waveform, offset, chunk.size)
                    offset += chunk.size
                }
                val expectedSamples = allCodes.size * Qwen3TtsProtocol.SAMPLES_PER_FRAME
                if (waveform.size != expectedSamples) {
                    throw ModelProtocol.UnsupportedModelException(
                        "vocoder produced ${waveform.size} samples, expected $expectedSamples")
                }
                if (waveform.isEmpty()) {
                    throw ModelProtocol.UnsupportedModelException("vocoder produced zero samples")
                }
                val peak = waveform.maxOf { kotlin.math.abs(it) }
                if (peak < 1e-4f) {
                    throw ModelProtocol.UnsupportedModelException(
                        "synthesis is silent (peak $peak); refusing to report success")
                }

                TtsEngine.SynthesisResult(
                    audioData = waveform,
                    sampleRate = TtsEngine.OUTPUT_SAMPLE_RATE,
                    durationMs = waveform.size.toLong() * 1000 / TtsEngine.OUTPUT_SAMPLE_RATE,
                    inferenceTimeMs = System.currentTimeMillis() - t0
                )
            } finally {
                state.close()
            }
        }
    }

    /** One streaming vocoder step (clipped); advances [state] only on success. */
    private fun runVocoderChunk(
        vocoder: OrtSession,
        state: VocoderTurnState,
        codesFlat: IntArray,
        frames: Int,
    ): FloatArray {
        require(frames > 0) { "vocoder chunk must contain a positive number of frames" }
        val codeTensor = modelManager.createLongTensor(
            codesFlat.map { it.toLong() }.toLongArray(),
            longArrayOf(1, Qwen3TtsProtocol.NUM_CODEBOOKS.toLong(), frames.toLong()))
        val convTensor = modelManager.createTensor(state.convState, longArrayOf(CONV_STATE_FLOATS.toLong()))
        val keysTensor = modelManager.createTensor(state.pastKeys, longArrayOf(8, 1, 16, 71, 64))
        val valuesTensor = modelManager.createTensor(state.pastValues, longArrayOf(8, 1, 16, 71, 64))
        val positionTensor = modelManager.createLongTensor(longArrayOf(state.position), longArrayOf(1))
        try {
            vocoder.run(mapOf(
                "codes" to codeTensor,
                "conv_state" to convTensor,
                "past_keys" to keysTensor,
                "past_values" to valuesTensor,
                "position" to positionTensor,
            )).use { result ->
                val waveTensor = outputTensor(result, "waveform")
                val expected = frames * Qwen3TtsProtocol.SAMPLES_PER_FRAME
                if (waveTensor.info.shape.toList() != listOf(1L, 1L, expected.toLong())) {
                    throw ModelProtocol.UnsupportedModelException(
                        "vocoder waveform shape ${waveTensor.info.shape.toList()} != [1,1,$expected]")
                }
                val waveform = FloatArray(waveTensor.floatBuffer.remaining())
                waveTensor.floatBuffer.get(waveform)
                // Commit the next state only after the step succeeded and the
                // waveform has been fully read (no input/output aliasing).
                val next = readVocoderState(result, expectedPosition = state.position + frames)
                System.arraycopy(next.convState, 0, state.convState, 0, CONV_STATE_FLOATS)
                System.arraycopy(next.pastKeys, 0, state.pastKeys, 0, VOC_KV_FLOATS)
                System.arraycopy(next.pastValues, 0, state.pastValues, 0, VOC_KV_FLOATS)
                state.position = next.position
                for (i in waveform.indices) waveform[i] = waveform[i].coerceIn(-1f, 1f)
                return waveform
            }
        } finally {
            codeTensor.close(); convTensor.close(); keysTensor.close()
            valuesTensor.close(); positionTensor.close()
        }
    }

    /**
     * Official ICL body: ref ids[3:-2] + target ids[3:-5] text rows over
     * codec rows (codec_bos + the R reference-codec frames). Body is always
     * refFrames+1 rows; the text remainder (or one pad row) is the trailing.
     */
    private fun buildIclBody(
        tokenIds: IntArray,
        refTokenIds: IntArray,
        refCodes: IntArray,
        refFrames: Int,
        cfg: Qwen3TtsBundleConfig,
        tables: Qwen3TtsEmbeddingLookup,
    ): Pair<FloatArray, FloatArray> {
        if (tokenIds.size < 9 || refTokenIds.size < 6) {
            throw ModelProtocol.UnsupportedModelException(
                "ICL requires nonempty wrapped target and reference token sequences")
        }
        if (refFrames !in 1..MAX_REFERENCE_FRAMES) {
            throw ModelProtocol.UnsupportedModelException(
                "reference codes must span 1..$MAX_REFERENCE_FRAMES frames, got $refFrames")
        }
        val H = cfg.hiddenSize
        for (value in refCodes) {
            if (value < 0 || value >= cfg.cpVocab) {
                throw ModelProtocol.UnsupportedModelException(
                    "reference codes contain reserved or out-of-range id $value")
            }
        }

        val ttsPad = tables.project(tables.textEmbed(cfg.tts.getValue("tts_pad_token_id")))
        val ttsEos = tables.project(tables.textEmbed(cfg.tts.getValue("tts_eos_token_id")))

        val textIds = ArrayList<Int>(refTokenIds.size - 5 + tokenIds.size - 8)
        for (i in 3 until refTokenIds.size - 2) textIds.add(refTokenIds[i])
        for (i in 3 until tokenIds.size - 5) textIds.add(tokenIds[i])

        val textRows = ArrayList<FloatArray>(textIds.size + 1)
        for (id in textIds) textRows.add(tables.project(tables.textEmbed(id)))
        textRows.add(ttsEos)

        val codecRows = ArrayList<FloatArray>(refFrames + 1)
        codecRows.add(tables.talkerCodecEmbedding(cfg.talker.getValue("codec_bos_id")))
        for (f in 0 until refFrames) {
            val row = FloatArray(H)
            System.arraycopy(tables.talkerCodecEmbedding(refCodes[f]), 0, row, 0, H)
            for (g in 1 until Qwen3TtsProtocol.NUM_CODEBOOKS) {
                val emb = tables.cpCodecEmbedding(g - 1, refCodes[g * refFrames + f])
                for (j in 0 until H) row[j] += emb[j]
            }
            codecRows.add(row)
        }

        val length = codecRows.size // == refFrames + 1
        val body = FloatArray(length * H)
        val trailing: FloatArray
        if (textRows.size > length) {
            for (r in 0 until length) {
                val text = textRows[r]
                val codec = codecRows[r]
                for (j in 0 until H) body[r * H + j] = text[j] + codec[j]
            }
            trailing = FloatArray((textRows.size - length) * H)
            for (r in length until textRows.size) {
                System.arraycopy(textRows[r], 0, trailing, (r - length) * H, H)
            }
        } else {
            for (r in 0 until length) {
                val text = if (r < textRows.size) textRows[r] else ttsPad
                val codec = codecRows[r]
                for (j in 0 until H) body[r * H + j] = text[j] + codec[j]
            }
            trailing = ttsPad.copyOf()
        }
        return body to trailing
    }

    // ---- Shared graph plumbing ------------------------------------------------

    /**
     * Owns the talker outputs until the next run has consumed them. KV stays in
     * ORT memory: getFloatBuffer() would copy each cache onto the ART heap.
     */
    private class TalkerState(
        val logits: FloatArray,
        val lastHidden: FloatArray,
        val pastKeys: OnnxTensor,
        val pastValues: OnnxTensor,
        val seqLen: Int,
        private val result: OrtSession.Result,
    ) : AutoCloseable {
        override fun close() = result.close()
    }

    /** Takes ownership of [result], including when output validation fails. */
    private fun readTalkerState(
        result: OrtSession.Result, seqLen: Int, cfg: Qwen3TtsBundleConfig,
    ): TalkerState {
        try {
            val logits = readFloatOutput(result, "logits")
            if (logits.size != cfg.talkerVocab) {
                throw ModelProtocol.UnsupportedModelException(
                    "talker logits size ${logits.size} != vocab ${cfg.talkerVocab}")
            }
            val lastHidden = readFloatOutput(result, "last_hidden_state")
            if (lastHidden.size != cfg.hiddenSize) {
                throw ModelProtocol.UnsupportedModelException(
                    "talker last_hidden_state size ${lastHidden.size} != ${cfg.hiddenSize}")
            }
            val shape = intArrayOf(cfg.numLayers, 1, cfg.numKvHeads, seqLen, cfg.headDim)
            val keys = kvOutputTensor(result, "present_keys", shape)
            val values = kvOutputTensor(result, "present_values", shape)
            return TalkerState(logits, lastHidden, keys, values, seqLen, result)
        } catch (error: Throwable) {
            result.close()
            throw error
        }
    }

    private fun makeKvTensor(
        data: FloatArray, layers: Int, kvHeads: Int, seqLen: Int, headDim: Int,
    ): OnnxTensor = modelManager.createTensor(
        data, longArrayOf(layers.toLong(), 1, kvHeads.toLong(), seqLen.toLong(), headDim.toLong()))

    /** Dynamic prefill on the unified talker: P=0 empty past, stacked KV output. */
    private fun runPrefill(talker: OrtSession, embeds: FloatArray, cfg: Qwen3TtsBundleConfig): TalkerState {
        val seqLen = embeds.size / cfg.hiddenSize
        if (seqLen <= 0) {
            throw ModelProtocol.UnsupportedModelException("prefill embeds are empty")
        }
        val embedTensor = modelManager.createTensor(
            embeds, longArrayOf(1, seqLen.toLong(), cfg.hiddenSize.toLong()))
        val maskTensor = modelManager.createLongTensor(
            LongArray(seqLen) { 1 }, longArrayOf(1, seqLen.toLong()))
        val posTensor = modelManager.createLongTensor(
            LongArray(3 * seqLen) { (it % seqLen).toLong() },
            longArrayOf(3, 1, seqLen.toLong()))
        val emptyKeys = makeKvTensor(FloatArray(0), cfg.numLayers, cfg.numKvHeads, 0, cfg.headDim)
        val emptyValues = makeKvTensor(FloatArray(0), cfg.numLayers, cfg.numKvHeads, 0, cfg.headDim)
        try {
            return readTalkerState(talker.run(mapOf(
                "inputs_embeds" to embedTensor,
                "attention_mask" to maskTensor,
                "position_ids" to posTensor,
                "past_keys" to emptyKeys,
                "past_values" to emptyValues,
            )), seqLen, cfg)
        } finally {
            embedTensor.close(); maskTensor.close(); posTensor.close()
            emptyKeys.close(); emptyValues.close()
        }
    }

    /** Named output access: ORT wraps results in Optional; unwrap + type check. */
    private fun outputTensor(result: OrtSession.Result, name: String): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: throw ModelProtocol.UnsupportedModelException("graph produced no '$name' output")
        return value as? OnnxTensor
            ?: throw ModelProtocol.UnsupportedModelException(
                "output '$name' is ${value.javaClass.simpleName}, expected a tensor")
    }

    private fun readFloatOutput(result: OrtSession.Result, name: String): FloatArray {
        val buf = outputTensor(result, name).floatBuffer
        val values = FloatArray(buf.remaining())
        buf.get(values)
        return values
    }

    private fun kvOutputTensor(result: OrtSession.Result, name: String, expected: IntArray): OnnxTensor {
        val tensor = outputTensor(result, name)
        val shape = tensor.info.shape.map { it.toInt() }.toIntArray()
        if (!shape.contentEquals(expected)) {
            throw ModelProtocol.UnsupportedModelException(
                "KV tensor '$name' shape ${shape.toList()} != expected ${expected.toList()}")
        }
        return tensor
    }

    private fun readKvOutput(result: OrtSession.Result, name: String, expected: IntArray): FloatArray {
        val buf = kvOutputTensor(result, name, expected).floatBuffer
        val values = FloatArray(buf.remaining())
        buf.get(values)
        return values
    }

    internal suspend fun loadAssets() = mutex.withLock {
        withContext(Dispatchers.Default) { loadAssetsLocked() }
        Unit
    }

    private fun loadAssetsLocked(): Qwen3TtsBundleConfig {
        config?.let { return it }
        val cfg = TtsBundleAssets.loadConfig(modelsDir)
        embeddings = Qwen3TtsEmbeddings(File(modelsDir, SUB_DIR))
        tokenizer = TtsBundleAssets.loadTokenizer(modelsDir)
        config = cfg
        return cfg
    }

    /** Releases cached sessions and asset tables; prepared references are pure data. */
    fun release() {
        generation.incrementAndGet() // invalidates all prepared references
        config = null
        embeddings = null
        tokenizer = null
        for (role in REQUIRED_ROLES) {
            modelManager.releaseRole(SUB_DIR, manifest.roles, role)
        }
        Log.i(TAG, "API2 runtime released")
    }
}
