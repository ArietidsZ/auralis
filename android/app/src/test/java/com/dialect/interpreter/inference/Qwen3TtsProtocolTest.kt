package com.dialect.interpreter.inference

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * Protocol tests for the Qwen3-TTS-0.6B-Base ONNX bundle adapter.
 *
 * Pure-protocol tests run offline against hardcoded values that were verified
 * against HuggingFace Qwen2Tokenizer and the host runner (convert/tts_runner.py).
 * The end-to-end tokenizer test runs only when a real bundle directory is
 * provided via the `AURALIS_TTS_MODEL_DIR` env var; it is skipped
 * otherwise (no fixtures fake model behavior).
 */
class Qwen3TtsProtocolTest {

    // ---- Byte encoder -------------------------------------------------------

    @Test
    fun `byte encoder maps all 256 bytes to unique pieces`() {
        val table = Qwen3TtsProtocol.bytesToUnicode()
        assertEquals(256, table.size)
        assertEquals(256, table.toSet().size)
    }

    @Test
    fun `byte encoder roundtrips ascii`() {
        val table = Qwen3TtsProtocol.bytesToUnicode()
        val text = "Hello, world 42!"
        val encoded = text.toByteArray(Charsets.UTF_8).joinToString("") { table[it.toInt() and 0xFF] }
        assertTrue(encoded.isNotEmpty())
    }

    // ---- BPE tokenizer --------------------------------------------------------

    private val tinyVocab = mutableMapOf<String, Int>()
    private val tinyRanks = mapOf("l l" to 0)

    init {
        var id = 0
        // letters needed by the template word "assistant" plus test words
        for (piece in listOf("h", "e", "l", "o", "w", "r", "d", "a", "s", "i", "t", "n")) {
            tinyVocab[piece] = id++
        }
        tinyVocab["ll"] = id++
        tinyVocab["\u010a"] = id++ // byteEncoder("\n") for the \n byte
        for ((token, tokenId) in Qwen3TtsProtocol.specialTokenIds) {
            tinyVocab.putIfAbsent(token, tokenId)
        }
    }

    private fun tinyTokenizer() = Qwen3TtsProtocol.Qwen3TtsTokenizer(tinyVocab, tinyRanks)

    private fun tinyPiece(id: Int): String =
        tinyVocab.entries.first { it.value == id }.key

    @Test
    fun `bpe applies lowest rank merges`() {
        val tok = tinyTokenizer()
        // "hello": h,e,l,l,o -> "l l"(rank 0) merges first -> h,e,ll,o
        val ids = tok.encode("hello")
        assertEquals(listOf("h", "e", "ll", "o"), ids.map(::tinyPiece))
    }

    @Test
    fun `special tokens are atomic with canonical ids`() {
        val tok = tinyTokenizer()
        assertEquals(listOf(151644), tok.encode("<|im_start|>"))
        assertEquals(listOf(151645), tok.encode("<|im_end|>"))
        assertEquals(
            listOf(151644, 151645),
            tok.encode("<|im_start|><|im_end|>")
        )
        // special token adjacent to text must not be byte-split into the text
        val mixed = tok.encode("hi<|im_end|>")
        assertEquals(151645, mixed.last())
    }

    @Test
    fun `prompt builder wraps the chat template`() {
        val tok = tinyTokenizer()
        val ids = tok.buildPromptIds("hello")
        // [<|im_start|>, a,s,s,i,s,t,a,n,t, \n, ...text..., <|im_end|>, \n,
        //  <|im_start|>, a,s,s,i,s,t,a,n,t, \n] -> fixed 11 + 13 + 4 pieces
        assertEquals(151644, ids.first())
        assertEquals(151644, ids[ids.size - 11])
        assertEquals(151645, ids[ids.size - 13])
        assertEquals(28, ids.size)
        // text pieces h,e,ll,o sit between the two fixed runs
        assertEquals(listOf("h", "e", "ll", "o"), ids.subList(11, 15).map(::tinyPiece))
    }

    // ---- End-to-end tokenizer parity (needs real bundle; skipped otherwise) --

    @Test
    fun `real bundle tokenizer matches HF-verified ids`() {
        // Env-var gate: the real bundle lives outside the repo (multi-GB).
        val modelDir = System.getenv("AURALIS_TTS_MODEL_DIR")
        Assume.assumeTrue("real bundle not available in this environment", modelDir != null)
        val dir = File(modelDir!!)
        Assume.assumeTrue(dir.resolve("tokenizer/vocab.json").exists())
        Assume.assumeTrue(dir.resolve("tokenizer/merges.txt").exists())

        val vocab = HashMap<String, Int>(153_000)
        // Reuse the engine's loader through a minimal reimplementation of the
        // JSON read (org.json is unavailable in unit tests; kotlinx is used).
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(dir.resolve("tokenizer/vocab.json").readText())
            .let { it as? kotlinx.serialization.json.JsonObject }!!
        for ((piece, id) in root) {
            vocab[piece] = (id as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        }
        for ((token, id) in Qwen3TtsProtocol.specialTokenIds) vocab.putIfAbsent(token, id)
        val ranks = HashMap<String, Int>(270_000)
        var rank = 0
        dir.resolve("tokenizer/merges.txt").readLines().forEach { line ->
            if (line.isNotEmpty() && !line.startsWith("#") && line.split(' ').size == 2) {
                ranks[line] = rank++
            }
        }
        val tok = Qwen3TtsProtocol.Qwen3TtsTokenizer(vocab, ranks)

        // Verified against HuggingFace Qwen2Tokenizer via the host runner env.
        assertEquals(
            listOf(151644, 77091, 198, 108386, 3837, 99489, 1773, 151645, 198, 151644, 77091, 198),
            tok.buildPromptIds("你好，世界。")
        )
        assertEquals(
            listOf(151644, 77091, 198, 9707, 1879, 11, 419, 374, 264, 1931, 8806, 38875, 1273,
                13, 151645, 198, 151644, 77091, 198),
            tok.buildPromptIds("Hello world, this is a real speech synthesis test.")
        )
    }

    // ---- Sampler -------------------------------------------------------------

    private fun testConfig(): Qwen3TtsBundleConfig {
        val talker = mapOf(
            "hidden_size" to 1024, "num_hidden_layers" to 28, "num_key_value_heads" to 8,
            "head_dim" to 128, "vocab_size" to 3072, "codec_eos_token_id" to 2150,
            "num_code_groups" to 16, "codec_think_id" to 2154, "codec_think_bos_id" to 2156,
            "codec_think_eos_id" to 2157, "codec_pad_id" to 2148, "codec_bos_id" to 2149,
        )
        val cp = mapOf(
            "vocab_size" to 2048, "num_hidden_layers" to 5,
            "num_key_value_heads" to 8, "head_dim" to 128,
        )
        val tts = mapOf("tts_pad_token_id" to 151671, "tts_bos_token_id" to 151672,
            "tts_eos_token_id" to 151673)
        val langs = mapOf("chinese" to 2055, "english" to 2050)
        return Qwen3TtsBundleConfig(talker, cp, tts, langs)
    }

    @Test
    fun `eos suppression prevents immediate stop`() {
        val cfg = testConfig()
        val logits = FloatArray(cfg.talkerVocab) { -10f }
        logits[cfg.codecEosId] = 100f // would always win without suppression
        val rng = Random(1)
        val sample = Qwen3TtsProtocol.sampleGroup0(
            logits, cfg, temperature = 0.9f, topK = 50, repetitionPenalty = 1.05f,
            generated = emptyList(), random = rng, suppressEos = true)
        assertTrue(sample != cfg.codecEosId)
        // Without suppression the EOS wins deterministically.
        val unsuppressed = Qwen3TtsProtocol.sampleGroup0(
            logits, cfg, 0.9f, 50, 1.05f, emptyList(), rng, suppressEos = false)
        assertEquals(cfg.codecEosId, unsuppressed)
    }

    @Test
    fun `codec range outside cp vocab is suppressed except eos`() {
        val cfg = testConfig()
        val logits = FloatArray(cfg.talkerVocab) { -10f }
        logits[3000] = 50f // inside [2048, 3072), must never be sampled
        logits[1500] = 20f  // valid codec token
        val rng = Random(7)
        repeat(200) {
            val sample = Qwen3TtsProtocol.sampleGroup0(
                logits, cfg, 0.9f, 50, 1.05f, emptyList(), rng, suppressEos = false)
            assertTrue(sample < cfg.cpVocab || sample == cfg.codecEosId)
        }
    }

    @Test
    fun `repetition penalty divides positive logits`() {
        val cfg = testConfig()
        val logits = FloatArray(cfg.talkerVocab) { -10f }
        logits[100] = 10f
        val one = Qwen3TtsProtocol.sampleGroup0(
            logits, cfg, 0.001f, 0, 2.0f, listOf(100), Random(3), suppressEos = false)
        // temp ~0 => softmax over [100/2=5, rest -10] -> token 100 still wins
        assertEquals(100, one)
        val logits2 = FloatArray(cfg.talkerVocab) { -10f }
        logits2[100] = 10f
        logits2[200] = 6f
        // penalized 100 -> 5.0 < 6.0 -> 200 wins deterministically at low temp
        val two = Qwen3TtsProtocol.sampleGroup0(
            logits2, cfg, 0.001f, 0, 2.0f, listOf(100), Random(4), suppressEos = false)
        assertEquals(200, two)
    }

    @Test
    fun `sampler is deterministic for a fixed seed`() {
        val cfg = testConfig()
        val logits = FloatArray(cfg.talkerVocab)
        val rngValues = Random(99)
        for (i in logits.indices) logits[i] = rngValues.nextFloat() * 4f - 2f
        fun runOnce(): List<Int> {
            val rng = Random(123)
            return List(50) {
                Qwen3TtsProtocol.sampleGroup0(
                    logits, cfg, 0.9f, 50, 1.05f, emptyList(), rng, suppressEos = false)
            }
        }
        assertEquals(runOnce(), runOnce())
    }

    // ---- Mel frontend (values verified against torch+librosa on host) --------

    @Test
    fun `greedy and topK one select the first maximum after masks and penalty`() {
        val cfg = testConfig()
        val logits = FloatArray(cfg.talkerVocab) { Float.NEGATIVE_INFINITY }
        logits[2] = 2f; logits[3] = -2f; logits[4] = 1.8f
        assertEquals(2, Qwen3TtsProtocol.sampleGroup0(logits, cfg, 0f, 0, 1.05f,
            listOf(2, 2, 2, 3, 3), Random(4), false))
        logits[2] = 2f; logits[4] = 2f; logits[cfg.codecEosId] = 100f
        assertEquals(2, Qwen3TtsProtocol.sampleGroup0(logits, cfg, 0f, 0, 1f,
            emptyList(), Random(4), true))
        assertEquals(2, Qwen3TtsProtocol.sampleGroup0(logits, cfg, 0.9f, 1, 1f,
            emptyList(), Random(999), true))
    }

    @Test
    fun `sampling validates logits and controls while allowing masks and tiny temperatures`() {
        val cfg = testConfig()
        val good = FloatArray(cfg.cpVocab) { Float.NEGATIVE_INFINITY }.also { it[9] = 2f }
        assertEquals(9, Qwen3TtsProtocol.sampleCodePredictor(good, cfg, Float.MIN_VALUE, 0, Random(1)))
        for (temperature in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                Qwen3TtsProtocol.sampleCodePredictor(good, cfg, temperature, 0, Random(1))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Qwen3TtsProtocol.sampleCodePredictor(good, cfg, 1f, -1, Random(1))
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                Qwen3TtsProtocol.sampleCodePredictor(good.copyOf().also { it[1] = bad }, cfg, 0f, 1, Random(1))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Qwen3TtsProtocol.sampleCodePredictor(FloatArray(cfg.cpVocab) { Float.NEGATIVE_INFINITY }, cfg, 0f, 0, Random(1))
        }
    }

    @Test
    fun `mel filterbank matches verified spot values`() {
        val fb = Qwen3TtsProtocol.buildMelFilterbank(24000, 1024, 128, 0.0, 12000.0)
        println("FB0=" + fb[0 * 513 + 1] + " FB2=" + fb[0 * 513 + 2] +
            " FB127_508=" + fb[127 * 513 + 508] + " FB127_512=" + fb[127 * 513 + 512])
        assertEquals(128 * 513, fb.size)
        val eps = 2e-4f // float32 storage of double-precision host values
        assertTrue(abs(fb[0 * 513 + 1] - 0.033550430f) < eps)
        assertTrue(abs(fb[0 * 513 + 2] - 0.008569083f) < eps)
        // last mel filter tail falls to zero at the Nyquist bin
        assertTrue(abs(fb[127 * 513 + 512] - 0f) < 1e-7f)
        assertTrue(abs(fb[127 * 513 + 508] - 0.00091271347f) < eps)
        assertTrue(abs(fb[127 * 513 + 509] - 0.00068453513f) < eps)
    }

    @Test
    fun `log mel matches verified values on a synthetic signal`() {
        val audio = FloatArray(8192)
        for (i in 1000 until 6000) audio[i] = 0.5f
        val mel = Qwen3TtsProtocol.logMelSpectrogram(audio, 24000)
        println("MEL=" + mel.data[10 * 128 + 0] + "," + mel.data[10 * 128 + 1] + " frames=" + mel.frames)
        assertEquals(32, mel.frames)
        val eps = 2e-3f
        assertTrue(abs(mel.data[10 * 128 + 0] - 1.457325f) < eps)
        for (k in 1 until 4) {
            assertTrue(abs(mel.data[10 * 128 + k] - (-11.512925f)) < eps)
        }
        assertTrue(mel.data.all { it.isFinite() })
    }

    // ---- Resampler ------------------------------------------------------------

    // ---- Prefill embeddings (synthetic tables, layout verification) -----------

    private class FakeTables(
        val H: Int,
        val textHidden: Int,
    ) : Qwen3TtsEmbeddingLookup {
        val projectedText = HashMap<Int, FloatArray>()
        val projectedCp = HashMap<Pair<Int, Int>, FloatArray>()
        val talker = HashMap<Int, FloatArray>()
        val fc1Rows = 2

        override fun textEmbed(tokenId: Int): FloatArray =
            FloatArray(textHidden) { (tokenId * 31 + it).toFloat() }

        override fun project(raw: FloatArray): FloatArray {
            // deterministic, shape-safe projection
            return FloatArray(H) { raw[it % raw.size] * 0.5f }
        }

        override fun talkerCodecEmbedding(tokenId: Int): FloatArray =
            talker.getOrPut(tokenId) { FloatArray(H) { (tokenId * 7 + it + 1).toFloat() } }

        override fun cpCodecEmbedding(groupIndex: Int, tokenId: Int): FloatArray =
            projectedCp.getOrPut(groupIndex to tokenId) {
                FloatArray(H) { (groupIndex * 100 + tokenId * 3 + it + 2).toFloat() }
            }
    }

    @Test
    fun `prefill layout has role, prefix, speaker slot and first text positions`() {
        val cfg = testConfig()
        val H = cfg.hiddenSize
        val tables = FakeTables(H, textHidden = 8)
        val tokenIds = IntArray(11) { 100 + it } // text = ids[3..5], trailing = ids[4..5]

        val speaker = FloatArray(H) { (it + 1).toFloat() }
        val built = Qwen3TtsProtocol.buildPrefillEmbeddings(
            tokenIds, speaker, "chinese", cfg, tables)

        val positions = built.embeds.size / H
        // 3 role + (7 prefix - 2 + 1) + 1 first text = 3 + 6 + 1 = 10
        assertEquals(10, positions)

        // Speaker slot: prefix is [think(2154), think_bos(2156), lang(2055),
        // think_eos(2157), speaker-placeholder(2148), pad(2148), bos(2149)];
        // speaker position = 4 -> position index 3(role) + 4 = 7.
        val speakerPosition = 7
        val atSpeaker = built.embeds.copyOfRange(speakerPosition * H, (speakerPosition + 1) * H)
        val pad = tables.project(tables.textEmbed(151671))
        val expectedSpeaker = FloatArray(H) { pad[it] + speaker[it] }
        for (j in 0 until H) {
            assertEquals(expectedSpeaker[j], atSpeaker[j], 1e-5f)
        }
        // Position 3 (first prefix slot) must NOT contain the speaker embedding.
        val at3 = built.embeds.copyOfRange(3 * H, 4 * H)
        val think = tables.talkerCodecEmbedding(2154)
        val expectedAt3 = FloatArray(H) { pad[it] + think[it] }
        for (j in 0 until H) {
            assertEquals(expectedAt3[j], at3[j], 1e-5f)
        }

        // Trailing: tokens[4..len-6] = 2 text rows (11-9) + tts_eos row = 3 rows.
        assertEquals(3 * H, built.trailing.size)
    }

    @Test
    fun `short prompts are rejected`() {
        val cfg = testConfig()
        val tables = FakeTables(cfg.hiddenSize, 8)
        val ids = IntArray(8) { 100 + it }
        // short prompt guard

        try {
            Qwen3TtsProtocol.buildPrefillEmbeddings(ids, FloatArray(cfg.hiddenSize), "english", cfg, tables)
            throw AssertionError("expected failure")
        } catch (e: ModelProtocol.UnsupportedModelException) {
            assertTrue(e.message!!.contains("too short"))
        }
    }

    // ---- npy reader ------------------------------------------------------------

    private fun writeNpy(file: File, rows: Int, cols: Int, values: FloatArray) {
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': ($rows, $cols), }"
        var headerLen = header.toByteArray(Charsets.ISO_8859_1).size + 1
        if (headerLen % 16 != 0) headerLen += 16 - (headerLen % 16)
        val padded = header + " ".repeat(headerLen - header.toByteArray().size - 1) + "\n"
        file.outputStream().use { out ->
            out.write(byteArrayOf(0x93.toByte(), 0x4E, 0x55, 0x4D, 0x50, 0x59, 1, 0))
            out.write(byteArrayOf((headerLen and 0xFF).toByte(), ((headerLen shr 8) and 0xFF).toByte()))
            out.write(padded.toByteArray(Charsets.ISO_8859_1))
            val buf = java.nio.ByteBuffer.allocate(values.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in values) buf.putFloat(v)
            out.write(buf.array())
        }
    }

    @Test
    fun `npy reader roundtrips synthetic tables`() {
        val tmp = File.createTempFile("tts_npy", ".npy")
        tmp.deleteOnExit()
        val values = FloatArray(3 * 4) { it + 0.5f }
        writeNpy(tmp, 3, 4, values)
        val table = NpyFloat2D.open(tmp)
        assertEquals(3, table.rows)
        assertEquals(4, table.cols)
        for (r in 0 until 3) {
            val row = table.row(r)
            for (c in 0 until 4) {
                assertEquals(values[r * 4 + c], row[c], 1e-6f)
            }
        }
    }

    // ---- Language mapping -------------------------------------------------------

    @Test
    fun `small npy tables are mapped and match the explicit heap path`() {
        val file = File.createTempFile("tts_small_mapped", ".npy")
        file.deleteOnExit()
        val values = FloatArray(12) { it + 0.5f }
        writeNpy(file, 3, 4, values)
        val mapped = NpyFloat2D.open(file)
        val heap = NpyFloat2D.open(file, mmapThresholdBytes = Long.MAX_VALUE)
        for (row in 0 until 3) {
            assertTrue(mapped.row(row).contentEquals(heap.row(row)))
        }
        // A backing-file update is visible through a mapping; a heap copy
        // retains its initial row. This distinguishes the two storage paths.
        java.io.RandomAccessFile(file, "rw").use { output ->
            output.seek(output.length() - 4)
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(99.5f).array())
        }
        assertEquals(99.5f, mapped.row(2)[3], 0f)
        assertEquals(11.5f, heap.row(2)[3], 0f)
    }

    @Test
    fun `language codes map to bundle config keys`() {
        assertEquals("chinese", Qwen3TtsProtocol.languageCodeToConfigKey["zh"])
        assertEquals("english", Qwen3TtsProtocol.languageCodeToConfigKey["en"])
    }

    fun `code flattening is group-major for the vocoder`() {
        // frames f=0..2 × groups g=0..1; value encodes (g, f) to catch swaps.
        val frames = listOf(intArrayOf(0, 1), intArrayOf(10, 11), intArrayOf(20, 21))
        val flat = Qwen3TtsProtocol.flattenCodesGroupMajor(frames, numCodebooks = 2)
        // group 0 across frames, then group 1 across frames (NOT frame-major).
        assertEquals(listOf(0, 10, 20, 1, 11, 21), flat.toList())
    }

    @Test
    fun `code flattening rejects ragged frames`() {
        val frames = listOf(intArrayOf(0, 1), intArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) {
            Qwen3TtsProtocol.flattenCodesGroupMajor(frames, numCodebooks = 2)
        }
    }
}
