package com.dialect.interpreter.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.data.WavCodec
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only the independently created CP session gets the experimental setting. */
@RunWith(AndroidJUnit4::class)
class Bf16TtsProbeTest {
    @Test
    fun compareCpOnlyFastmathInRealChineseAndEnglishSynthesis() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual BF16 experiment only", arguments.getString("bf16Probe") == "true")
        val mode = arguments.getString("bf16Mode", "0")!!
        require(mode == "0" || mode == "1")
        val fx = AsrDeviceFixtures
        val manager = OnnxModelManager(fx.targetCtx)
        val tts = TtsEngine(manager)
        val asr = AsrEngine(OnnxModelManager(fx.targetCtx))
        val field = OnnxModelManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(manager) as ConcurrentHashMap<String, OrtSession>
        val directory = File(fx.targetCtx.getExternalFilesDir(null), "bf16_probe").apply { mkdirs() }
        val rows = JSONArray()
        val report = JSONObject().put("mode", mode).put("scope", "code_predictor.onnx only")
            .put("quality_acceptance", "not evaluated; two synthetic reference voices only")
            .put("cases", rows)
        fun save(stage: String) {
            report.put("stage", stage).put("rss_kb", fx.vmRssKb())
                .put("java_heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                .put("native_heap_allocated_bytes", android.os.Debug.getNativeHeapAllocatedSize())
            File(directory, "tts_cp_$mode.json").writeText(report.toString(2))
        }
        try {
            save("begin")
            manager.createSessionOptions().use { options ->
                options.addConfigEntry(Bf16MatMulProbeTest.FASTMATH_KEY, mode)
                val start = System.nanoTime()
                val cp = OrtEnvironment.getEnvironment().createSession(
                    File(manager.getModelsDir(), "tts/code_predictor.onnx").absolutePath, options)
                sessions["tts/code_predictor.onnx"] = cp // manager owns cleanup from here
                report.put("cp_preload_ms", (System.nanoTime() - start) / 1e6)
            }
            asr.load()
            for ((language, text, referenceName) in listOf(
                Triple("zh", "今天天气不错，我们去公园散步吧。", "ref_zh.wav"),
                Triple("en", "The quick brown fox jumps over the lazy dog.", "ref_en.wav"))) {
                save("starting_$language")
                val (reference, rate) = fx.wavAsset(referenceName)
                val embedding = tts.extractSpeakerEmbedding(reference, inputSampleRate = rate)
                val result = tts.synthesize(text, language, embedding)
                WavCodec.writeAtomically(File(directory, "tts_cp_${mode}_$language.wav"),
                    WavCodec.encodeMonoPcm16(result.audioData, result.sampleRate))
                val decoded = asr.transcribe(result.audioData, null, result.sampleRate)
                rows.put(JSONObject().put("language", language).put("text", text).put("reference", referenceName)
                    .put("samples", result.audioData.size).put("sample_rate", result.sampleRate)
                    .put("audio_ms", result.durationMs).put("synthesis_ms_excluding_cp_preload", result.inferenceTimeMs)
                    .put("asr_text", decoded.text).put("cer_whitespace_stripped", fx.cer(text, decoded.text)))
                save("completed_$language")
            }
            assertTrue(rows.length() == 2)
            save("complete")
        } finally {
            tts.release()
            asr.release()
            manager.releaseAll()
        }
    }
}
