package com.dialect.interpreter.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.data.WavCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Predeclared six-real-speaker paired TTS quality data collection on the
 * dedicated emulator: 6 LibriSpeech speakers x {chinese, english} x
 * {mode 0 = native FP32 (production default), mode 1 = CP-only BF16}.
 *
 * Not a self-contained pass/fail gate: identity (ECAPA held-out) and paired
 * speaker-similarity deltas are computed host-side with the same scoring
 * tool as the earlier real-pool screening. This test records all data,
 * including failures — it never deletes or masks a bad case.
 *
 * Fixed protocol (pushed bf16_quality_manifest.json, criteria SHA-256 is
 * echoed into every report): seed 20260906 (per-synthesis RNG reset inside
 * TtsEngine), temperature 0.9, topK 50, repetition penalty 1.05,
 * maxFrames 384, ORT 1.24.2, production FP32 graphs only. Mode 1 attaches
 * ONE session option (mlas.enable_gemm_fastmath_arm64_bfloat16=1) to the
 * independently created tts/code_predictor.onnx session; ModelManager
 * defaults and the manifest are untouched.
 *
 * References: official FLAC -> soxr_hq 16k->24k float32, pushed as raw
 * little-endian .f32 (byte-exact; no WAV codec round-trip on device).
 */
@RunWith(AndroidJUnit4::class)
class Bf16TtsQualityGateTest {
    @Test
    fun collectSixSpeakerPairedSynthesis() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual BF16 quality collection only", arguments.getString("bf16Quality") == "true")
        val mode = arguments.getString("bf16Mode", "0")!!
        require(mode == "0" || mode == "1")
        val modeName = if (mode == "0") "fp32" else "bf16cp"
        val fx = AsrDeviceFixtures

        val external = File(fx.targetCtx.getExternalFilesDir(null), "bf16_quality")
        val refsDir = File(external, "refs")
        val manifestBytes = File(refsDir, "bf16_quality_manifest.json")
        check(manifestBytes.isFile) {
            "Push bf16_quality_manifest.json + *-reference24.f32 to $refsDir first"
        }
        val manifest = JSONObject(manifestBytes.readText())
        val outDir = File(external, "mode_$mode").apply { mkdirs() }

        val manager = OnnxModelManager(fx.targetCtx)
        val tts = TtsEngine(manager, maxFrames = manifest.getJSONObject("engine").getInt("max_frames"))
        val asr = AsrEngine(OnnxModelManager(fx.targetCtx))
        val field = OnnxModelManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(manager) as ConcurrentHashMap<String, OrtSession>

        val cpSha = sha256(File(manager.getModelsDir(), "tts/code_predictor.onnx"))
        val report = JSONObject()
            .put("mode", mode).put("mode_name", modeName)
            .put("scope", if (mode == "1") "tts/code_predictor.onnx session only" else "no session option (native FP32)")
            .put("ort_version", OrtEnvironment.getEnvironment().version)
            .put("device", "emulator-5580 API35 arm64 (emulator, not a phone baseline)")
            .put("code_predictor_sha256", cpSha)
            .put("pushed_manifest_sha256", sha256(manifestBytes))
            .put("pushed_criteria_sha256", manifest.getJSONObject("provenance").getString("criteria_sha256"))
            .put("engine", manifest.getJSONObject("engine"))
            .put("cases", JSONArray())
        fun save(stage: String) {
            report.put("stage", stage).put("rss_kb", fx.vmRssKb())
                .put("native_heap_allocated_bytes", android.os.Debug.getNativeHeapAllocatedSize())
            File(outDir, "quality_$modeName.json").writeText(report.toString(2))
        }
        try {
            if (mode == "1") {
                manager.createSessionOptions().use { options ->
                    options.addConfigEntry(Bf16MatMulProbeTest.FASTMATH_KEY, "1")
                    val start = System.nanoTime()
                    val cp = OrtEnvironment.getEnvironment().createSession(
                        File(manager.getModelsDir(), "tts/code_predictor.onnx").absolutePath, options)
                    sessions["tts/code_predictor.onnx"] = cp // manager owns cleanup from here
                    report.put("bf16_cp_session_create_ms", (System.nanoTime() - start) / 1e6)
                }
            }
            save("begin")
            asr.load()
            val refs = manifest.getJSONArray("references")
            for (i in 0 until refs.length()) {
                val sp = refs.getJSONObject(i)
                val (reference, rate) = readRawF32(File(refsDir, sp.getString("file")))
                check(reference.size == sp.getInt("samples") && rate == sp.getInt("sample_rate"))
                save("embedding_${sp.getString("id")}")
                val embedding = tts.extractSpeakerEmbedding(reference, inputSampleRate = rate)
                val texts = manifest.getJSONObject("criteria").getJSONObject("synthesis_texts")
                for (language in listOf("chinese", "english")) {
                    val text = texts.getString(language)
                    val langCode = if (language == "chinese") "zh" else "en"
                    save("synthesis_${sp.getString("id")}_$language")
                    val result = tts.synthesize(text, langCode, embedding)
                    val wavFile = File(outDir, "${sp.getString("id")}-$language.wav")
                    WavCodec.writeAtomically(wavFile,
                        WavCodec.encodeMonoPcm16(result.audioData, result.sampleRate))
                    val decoded = asr.transcribe(result.audioData, null, result.sampleRate)
                    val row = JSONObject()
                        .put("id", "${sp.getString("id")}-$language")
                        .put("voice", sp.getString("id")).put("sex", sp.getString("sex"))
                        .put("language", language).put("text", text)
                        .put("samples", result.audioData.size).put("sample_rate", result.sampleRate)
                        .put("audio_ms", result.durationMs)
                        .put("synthesis_ms_excluding_cp_preload", result.inferenceTimeMs)
                        .put("frames", result.audioData.size / Qwen3TtsProtocol.SAMPLES_PER_FRAME)
                        .put("eos_within_budget", result.audioData.size < 384 * Qwen3TtsProtocol.SAMPLES_PER_FRAME)
                        .put("finite", result.audioData.all { it.isFinite() })
                        .put("non_silent", result.audioData.any { kotlin.math.abs(it) >= 1e-4f })
                        .put("wav_sha256", sha256(wavFile))
                        .put("asr_text", decoded.text)
                        .put("cer_whitespace_stripped", fx.cer(text, decoded.text))
                    report.getJSONArray("cases").put(row)
                    save("completed_${sp.getString("id")}_$language")
                    assertTrue("non-finite or silent synthesis: $row", row.getBoolean("finite") && row.getBoolean("non_silent"))
                }
            }
            report.put("vm_hwm_kb", readVmHwmKb())
            assertTrue("expected 12 cases", report.getJSONArray("cases").length() == 12)
            save("complete")
        } finally {
            asr.release()
            tts.release()
            manager.releaseAll()
        }
    }

    /** Raw little-endian float32 pushed by the host harness (no WAV header). */
    private fun readRawF32(file: File): Pair<FloatArray, Int> {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(samples)
        return samples to 24000
    }

    private fun readVmHwmKb(): Long {
        File("/proc/self/status").useLines { lines ->
            for (line in lines) {
                if (line.startsWith("VmHWM:")) return line.split(Regex("\\s+"))[1].toLong()
            }
        }
        return -1
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val block = ByteArray(1024 * 1024)
            while (true) { val size = stream.read(block); if (size < 0) break; digest.update(block, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
