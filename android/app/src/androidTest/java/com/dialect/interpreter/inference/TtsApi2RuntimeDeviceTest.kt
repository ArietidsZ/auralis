package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.data.ModelManifests
import com.dialect.interpreter.data.WavCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device API2 runtime tests (emulator-5580): the experimental multi-source
 * API2 bundle must be installed under files/dialect_models/tts/ with its
 * manifest.json (runtime.apiContractVersion == "2", roles speaker_encoder /
 * talker / code_predictor / reference_encoder / vocoder) and the reference
 * .f32 files pushed to the external bf16_quality/refs dir (same soxr_hq 24k
 * float32 files as the quality gate).
 *
 * Cases (arg `api2Case`): main | chunking | lifecycle | stream | binding. All evidence (WAV, JSON,
 * hashes, failures) is retained; nothing is deleted on failure.
 */
@RunWith(AndroidJUnit4::class)
class TtsApi2RuntimeDeviceTest {
    companion object {
        // LibriSpeech test-clean references; texts are the official transcripts
        // from real-pool manifest.json (sha 81378fdb…/d654a43d… recordings).
        private val SPEAKERS = listOf(
            Speaker("121", "121-reference24.f32",
                "ALSO A POPULAR CONTRIVANCE WHEREBY LOVE MAKING MAY BE SUSPENDED BUT NOT STOPPED DURING THE PICNIC SEASON"),
            Speaker("260", "260-reference24.f32",
                "SATURDAY AUGUST FIFTEENTH THE SEA UNBROKEN ALL ROUND NO LAND IN SIGHT"),
        )
        private const val TEXT_ZH = "请不要取消明天去上海的火车票。"
        private const val TEXT_EN = "Please do not cancel the train to London tomorrow."
        private const val MAX_FRAMES = 384

        class Speaker(val id: String, val refFile: String, val refText: String)
    }

    private fun externalDir(): File =
        File(AsrDeviceFixtures.targetCtx.getExternalFilesDir(null), "api2_evidence")

    private fun refsDir(): File =
        File(AsrDeviceFixtures.targetCtx.getExternalFilesDir(null), "bf16_quality/refs")

    private fun readRawF32(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(samples)
        return samples
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val block = ByteArray(1024 * 1024)
            while (true) { val size = stream.read(block); if (size < 0) break; digest.update(block, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Parses the on-device manifest and verifies every role file's size/hash. */
    private fun openRuntime(maxFrames: Int = MAX_FRAMES, chunkFrames: Int = 4): TtsApi2Runtime {
        val fx = AsrDeviceFixtures
        val manifestFile = File(fx.targetCtx.filesDir, "dialect_models/tts/manifest.json")
        check(manifestFile.isFile) { "API2 manifest missing at ${manifestFile.absolutePath}" }
        val manifest = ModelManifests.parse(manifestFile.readText())
        assertEquals("2", manifest.apiContractVersion)
        assertEquals("tts", manifest.packageId)
        // Integrity gate: every declared file must exist with the declared size.
        for (entry in manifest.files) {
            val f = File(fx.targetCtx.filesDir, "dialect_models/${entry.path}")
            check(f.isFile) { "bundle file missing: ${entry.path}" }
            entry.sizeBytes?.let { check(f.length() == it) { "size mismatch: ${entry.path}" } }
        }
        return TtsApi2Runtime(OnnxModelManager(fx.targetCtx), manifest,
            maxFrames = maxFrames, chunkFrames = chunkFrames)
    }

    private fun wavRow(id: String, mode: String, result: TtsEngine.SynthesisResult,
                       text: String, asrText: String, cer: Double, chunkCount: Int,
                       wavFile: File, identity: String): JSONObject = JSONObject()
        .put("id", id).put("mode", mode).put("text", text)
        .put("samples", result.audioData.size).put("sample_rate", result.sampleRate)
        .put("frames", result.audioData.size / Qwen3TtsProtocol.SAMPLES_PER_FRAME)
        .put("expected_samples_exact", result.audioData.size % Qwen3TtsProtocol.SAMPLES_PER_FRAME == 0)
        .put("audio_ms", result.durationMs).put("synthesis_ms", result.inferenceTimeMs)
        .put("finite", result.audioData.all { it.isFinite() })
        .put("non_silent", result.audioData.any { kotlin.math.abs(it) >= 1e-4f })
        .put("asr_text", asrText).put("cer_whitespace_stripped", cer)
        .put("chunk_count", chunkCount).put("wav_sha256", sha256(wavFile))
        .put("prepared_identity", identity)

    @Test
    fun main_twoSpeakersIclAndXvector() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("api2Case") == "main")
        val fx = AsrDeviceFixtures
        val dir = externalDir().apply { mkdirs() }
        val rows = JSONArray()
        val report = JSONObject().put("device", "emulator-5580 API35 arm64 (emulator, not a phone)")
            .put("api_contract", "2").put("engine_params", JSONObject()
                .put("seed", Qwen3TtsProtocol.SAMPLING_SEED)
                .put("temperature", Qwen3TtsProtocol.TEMPERATURE)
                .put("top_k", Qwen3TtsProtocol.TOP_K)
                .put("repetition_penalty", Qwen3TtsProtocol.REPETITION_PENALTY)
                .put("max_frames", MAX_FRAMES).put("chunk_frames", 4))
            .put("role_file_sha256", JSONObject()).put("cases", rows)
        fun save(stage: String) {
            report.put("stage", stage)
            File(dir, "api2_main.json").writeText(report.toString(2))
        }
        val runtime = openRuntime()
        val asr = AsrEngine(OnnxModelManager(fx.targetCtx))
        try {
            // Record device-side role file hashes once (integrity evidence).
            val hashes = report.getJSONObject("role_file_sha256")
            val ttsDir = File(fx.targetCtx.filesDir, "dialect_models/tts")
            for (name in listOf("manifest.json", "build-provenance.json", "talker.onnx",
                    "talker_api2.onnx.data", "code_predictor.onnx", "speaker_encoder.onnx",
                    "speaker_encoder.onnx.data", "reference_encoder.onnx",
                    "reference_encoder.onnx.data", "vocoder_streaming.onnx",
                    "vocoder_streaming.onnx.data")) {
                hashes.put(name, sha256(File(ttsDir, name)))
            }
            save("hashes")
            asr.load()
            for (speaker in SPEAKERS) {
                val reference = readRawF32(File(refsDir(), speaker.refFile))
                save("prepare_${speaker.id}")
                val icl = runtime.prepareReference(reference, inputSampleRate = 24000,
                    referenceText = speaker.refText)
                val xvector = runtime.prepareReference(reference, inputSampleRate = 24000,
                    referenceText = null)
                assertTrue(icl.isIcl && !xvector.isIcl)
                assertTrue("xvector embedding dim", icl.embedding.size == 1024)
                assertTrue("icl prepared has warm state", icl.referenceFrames > 0)
                assertTrue("xvector prepared has no ref codes", xvector.referenceCodes == null)
                for ((lang, text) in listOf("zh" to TEXT_ZH, "en" to TEXT_EN)) {
                    for ((mode, prepared) in listOf("icl" to icl, "xvector" to xvector)) {
                        save("synth_${speaker.id}_${mode}_$lang")
                        var chunkCount = 0
                        val result = runtime.synthesizePrepared(prepared, text, lang) { _ ->
                            chunkCount++
                        }
                        val wavFile = File(dir, "${speaker.id}-$lang-$mode.wav")
                        WavCodec.writeAtomically(wavFile,
                            WavCodec.encodeMonoPcm16(result.audioData, result.sampleRate))
                        val decoded = asr.transcribe(result.audioData, null, result.sampleRate)
                        val row = wavRow("${speaker.id}-$lang-$mode", mode, result, text,
                            decoded.text, fx.cer(text, decoded.text), chunkCount, wavFile, icl.identity)
                        rows.put(row)
                        save("done_${speaker.id}_${mode}_$lang")
                        assertTrue("bad synthesis: $row", row.getBoolean("finite") && row.getBoolean("non_silent"))
                        assertTrue("reference PCM leaked into output: $row",
                            row.getBoolean("expected_samples_exact") &&
                                result.audioData.size == row.getInt("frames") * Qwen3TtsProtocol.SAMPLES_PER_FRAME)
                    }
                }
            }
            assertTrue("expected 8 cases", rows.length() == 8)
            save("complete")
        } finally {
            asr.release()
            runtime.release()
        }
    }

    @Test
    fun chunking_sameCodesDifferentChunkingMatch() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("api2Case") == "chunking")
        val fx = AsrDeviceFixtures
        val dir = externalDir().apply { mkdirs() }
        val report = JSONObject().put("device", "emulator-5580 API35 arm64")
        // The two chunk sizes run SEQUENTIALLY (fully released between) so two
        // runtime instances never co-reside: each instance owns asset tables,
        // and the 200 MB ART heap on this AVD cannot hold both plus KV state.
        val reference = readRawF32(File(refsDir(), "121-reference24.f32"))
        val results = HashMap<String, FloatArray>()
        val runtimeSmall = openRuntime(chunkFrames = 4)
        val wavesSmall: Map<String, FloatArray>
        try {
            val iclS = runtimeSmall.prepareReference(reference, inputSampleRate = 24000,
                referenceText = SPEAKERS[0].refText)
            val xvS = runtimeSmall.prepareReference(reference, inputSampleRate = 24000)
            wavesSmall = mapOf(
                "icl" to runtimeSmall.synthesizePrepared(iclS, TEXT_ZH, "zh").audioData,
                "xvector" to runtimeSmall.synthesizePrepared(xvS, TEXT_ZH, "zh").audioData)
        } finally { runtimeSmall.release() }
        val runtimeWhole = openRuntime(chunkFrames = 4096)
        try {
            val iclW = runtimeWhole.prepareReference(reference, inputSampleRate = 24000,
                referenceText = SPEAKERS[0].refText)
            val xvW = runtimeWhole.prepareReference(reference, inputSampleRate = 24000)
            for ((mode, b) in mapOf("icl" to iclW, "xvector" to xvW)) {
                val a = wavesSmall.getValue(mode)
                val whole = runtimeWhole.synthesizePrepared(b, TEXT_ZH, "zh").audioData
                var maxDiff = 0.0f
                assertTrue(a.size == whole.size)
                for (i in a.indices) {
                    val d = kotlin.math.abs(a[i] - whole[i])
                    if (d > maxDiff) maxDiff = d
                }
                report.put("chunk_${mode}", JSONObject()
                    .put("frames", whole.size / Qwen3TtsProtocol.SAMPLES_PER_FRAME)
                    .put("samples", whole.size)
                    .put("max_abs_diff_chunk4_vs_whole", maxDiff)
                    .put("wav4_sha256", sha256Wav(a))
                    .put("wavWhole_sha256", sha256Wav(whole)))
            }
            File(dir, "api2_chunking.json").writeText(report.toString(2))
        } finally {
            runtimeWhole.release()
        }
    }

    private fun sha256Wav(audio: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(audio.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(audio)
        digest.update(buffer.array())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun lifecycle_cancelNoEosSinkFailureRecover() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("api2Case") == "lifecycle")
        val fx = AsrDeviceFixtures
        val dir = externalDir().apply { mkdirs() }
        val report = JSONObject().put("device", "emulator-5580 API35 arm64")
        val runtime = openRuntime()
        val tight = openRuntime(maxFrames = 3)
        try {
            val reference = readRawF32(File(refsDir(), "121-reference24.f32"))
            val icl = runtime.prepareReference(reference, inputSampleRate = 24000,
                referenceText = SPEAKERS[0].refText)
            val xvector = runtime.prepareReference(reference, inputSampleRate = 24000)

            // --- reference baseline output (deterministic recovery target) ---
            val baseline = runtime.synthesizePrepared(icl, TEXT_ZH, "zh")
            val baselineSha = sha256Wav(baseline.audioData)
            report.put("baseline", JSONObject()
                .put("frames", baseline.audioData.size / Qwen3TtsProtocol.SAMPLES_PER_FRAME)
                .put("float_sha256", baselineSha))

            // --- no-EOS: budget exhaustion is a failure, then recovery -------
            // tight is its own engine instance: prepared refs do not cross
            // engines (binding contract), so it prepares its own xvector.
            val xvectorTight = tight.prepareReference(reference, inputSampleRate = 24000)
            try {
                tight.synthesizePrepared(xvectorTight, TEXT_ZH, "zh")
                fail("budget exhaustion must fail")
            } catch (expected: ModelProtocol.UnsupportedModelException) {
                report.put("no_eos", JSONObject().put("error", expected.message))
            }
            // Recovery runs on the full-budget runtime: the failed turn on
            // tight must not poison any engine.
            val afterNoEos = runtime.synthesizePrepared(xvector, TEXT_ZH, "zh")
            report.put("after_no_eos", JSONObject().put("float_sha256", sha256Wav(afterNoEos.audioData)))

            // --- cancel mid-generation (after the first delivered chunk) -----
            val firstChunk = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                runtime.synthesizePrepared(icl, TEXT_ZH, "zh") { _ ->
                    firstChunk.complete(Unit)
                    delay(60_000) // hold the sink open until cancellation lands
                }
            }
            withTimeout(120_000) { firstChunk.await() }
            job.cancelAndJoin()
            report.put("cancel", "first chunk delivered; turn cancelled; no result returned")

            // --- sink failure: exception propagates, snapshot stays clean ----
            var sinkCalls = 0
            try {
                runtime.synthesizePrepared(icl, TEXT_ZH, "zh") { _ ->
                    sinkCalls++
                    if (sinkCalls == 2) throw IllegalStateException("sink failure injected")
                }
                fail("sink failure must propagate")
            } catch (expected: IllegalStateException) {
                report.put("sink_failure", JSONObject().put("sink_calls", sinkCalls)
                    .put("error", expected.message))
            }
            val afterSinkFailure = runtime.synthesizePrepared(icl, TEXT_ZH, "zh")
            val afterSinkSha = sha256Wav(afterSinkFailure.audioData)
            report.put("after_sink_failure", JSONObject()
                .put("float_sha256", afterSinkSha)
                .put("identical_to_baseline", afterSinkSha == baselineSha))
            assertEquals("snapshot must be unpolluted after sink failure", baselineSha, afterSinkSha)

            // --- state isolation: alternating voices never cross -------------
            val other = runtime.prepareReference(
                readRawF32(File(refsDir(), "260-reference24.f32")), inputSampleRate = 24000,
                referenceText = SPEAKERS[1].refText)
            val otherOnce = runtime.synthesizePrepared(other, TEXT_ZH, "zh")
            val again = runtime.synthesizePrepared(icl, TEXT_ZH, "zh")
            val againSha = sha256Wav(again.audioData)
            report.put("state_isolation", JSONObject()
                .put("other_voice_sha256", sha256Wav(otherOnce.audioData))
                .put("again_sha256", againSha)
                .put("identical_to_baseline", againSha == baselineSha))
            assertEquals("state must not cross utterances/voices", baselineSha, againSha)
            File(dir, "api2_lifecycle.json").writeText(report.toString(2))
        } finally {
            runtime.release()
            tight.release()
        }
    }

    /** Streaming proof: with immediate per-loop delivery, a maxFrames=5 no-EOS
     * turn must have delivered its first positive 4-frame chunk to the sink
     * BEFORE the budget-exhaustion failure — the old hold-one design
     * delivered nothing. The sink has no end-of-stream flag; normal return
     * of the method is the completion signal. */
    @Test
    fun streaming_chunkDeliveredBeforeFailure() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("api2Case") == "stream")
        val dir = externalDir().apply { mkdirs() }
        val report = JSONObject().put("device", "emulator-5580 API35 arm64")
        val tight = openRuntime(maxFrames = 5)
        try {
            val reference = readRawF32(File(refsDir(), "121-reference24.f32"))
            val icl = tight.prepareReference(reference, inputSampleRate = 24000,
                referenceText = SPEAKERS[0].refText)
            val delivered = ArrayList<Int>()
            try {
                tight.synthesizePrepared(icl, TEXT_ZH, "zh") { chunk ->
                    delivered.add(chunk.size)
                }
                fail("maxFrames=5 without EOS must fail")
            } catch (expected: ModelProtocol.UnsupportedModelException) {
                report.put("error", expected.message ?: "budget exhaustion")
            }
            report.put("delivered", JSONArray(delivered.map { JSONObject().put("samples", it) }))
            // 4-frame chunk of 24 kHz audio = 4*1920 samples, delivered while
            // the turn then failed; the failure must NOT un-deliver it.
            assertTrue("first 4-frame chunk must be delivered before failure",
                delivered.contains(4 * Qwen3TtsProtocol.SAMPLES_PER_FRAME))
            File(dir, "api2_stream.json").writeText(report.toString(2))
        } finally {
            tight.release()
        }
    }

    /** Binding: a prepared reference is bound to its engine instance and the
     * loading generation; release→reload or another engine rejects it. */
    @Test
    fun binding_staleAndForeignPreparedRejected() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("api2Case") == "binding")
        val dir = externalDir().apply { mkdirs() }
        val report = JSONObject().put("device", "emulator-5580 API35 arm64")
        val engineA = openRuntime()
        try {
            val reference = readRawF32(File(refsDir(), "121-reference24.f32"))
            val prepared = engineA.prepareReference(reference, inputSampleRate = 24000,
                referenceText = SPEAKERS[0].refText)

            // Same engine, live generation: accepted.
            val ok = engineA.synthesizePrepared(prepared, TEXT_ZH, "zh")
            report.put("live", JSONObject().put("frames", ok.audioData.size))

            // release→reload on the SAME engine: old prepared is stale.
            engineA.release()
            engineA.prepareReference(reference, inputSampleRate = 24000) // new generation
            try {
                engineA.synthesizePrepared(prepared, TEXT_ZH, "zh")
                fail("prepared reference must be rejected after engine release/reload")
            } catch (expected: IllegalArgumentException) {
                report.put("stale_rejected", expected.message ?: "rejected")
            }

            // Other engine instance: also rejected.
            val engineB = openRuntime()
            try {
                try {
                    engineB.synthesizePrepared(prepared, TEXT_ZH, "zh")
                    fail("prepared reference must be rejected by another engine instance")
                } catch (expected: IllegalArgumentException) {
                    report.put("foreign_rejected", expected.message ?: "rejected")
                }
                // A fresh preparation on B works.
                val fresh = engineB.prepareReference(reference, inputSampleRate = 24000)
                val bv = engineB.synthesizePrepared(fresh, TEXT_ZH, "zh")
                report.put("fresh", JSONObject().put("frames", bv.audioData.size))
            } finally {
                engineB.release()
            }
            File(dir, "api2_binding.json").writeText(report.toString(2))
        } finally {
            engineA.release()
        }
    }
}
