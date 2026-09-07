package com.dialect.interpreter.inference

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.data.WavCodec
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Paired execution/measurements only; success does not certify voice quality. */
@RunWith(AndroidJUnit4::class)
class TtsCandidateBenchmarkTest {
    @Test
    fun executePairedChineseAndEnglishCases() = runBlocking {
        val fx = AsrDeviceFixtures
        val variant = InstrumentationRegistry.getArguments().getString("ttsVariant", "fp32")!!
        require(variant in setOf("fp32", "int8", "int4"))
        val context = if (variant == "fp32") fx.targetCtx else object : ContextWrapper(fx.targetCtx) {
            override fun getFilesDir(): File = File(baseContext.filesDir, "runtime_candidates/$variant")
        }
        val manager = OnnxModelManager(context)
        val identity = if (variant == "fp32") JSONObject().put("source", "original pinned FP32 bundle") else {
            val conversion = JSONObject(File(context.filesDir, "conversion.json").readText())
            val models = conversion.getJSONArray("models")
            for (i in 0 until models.length()) {
                val files = models.getJSONObject(i).getJSONArray("files")
                for (j in 0 until files.length()) {
                    val expected = files.getJSONObject(j)
                    val file = File(manager.getModelsDir(), "tts/" + File(expected.getString("path")).name)
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val block = ByteArray(1024 * 1024)
                        while (true) {
                            val size = input.read(block)
                            if (size < 0) break
                            digest.update(block, 0, size)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actual == expected.getString("sha256")) { "Candidate hash mismatch: $file" }
                }
            }
            conversion
        }
        val rows = JSONArray()
        val report = JSONObject().put("variant", variant).put("artifact_identity", identity)
            .put("quality_acceptance", "not evaluated").put("cases", rows)
        fun save(stage: String) {
            report.put("stage", stage).put("rss_kb", fx.vmRssKb())
            fx.writeReport("tts_candidate_$variant.json", report.toString(2))
        }
        val tts = TtsEngine(manager)
        val asr = AsrEngine(OnnxModelManager(fx.targetCtx))
        try {
            asr.load()
            val cases = listOf(
                Triple("zh", "今天天气不错，我们去公园散步吧。", "ref_zh.wav"),
                Triple("en", "The quick brown fox jumps over the lazy dog.", "ref_en.wav"))
            for ((language, text, referenceName) in cases) {
                save("starting_$language")
                val (reference, rate) = fx.wavAsset(referenceName)
                val speaker = tts.extractSpeakerEmbedding(reference, inputSampleRate = rate)
                val output = tts.synthesize(text, language, speaker)
                val target = File(fx.targetCtx.getExternalFilesDir(null), "asr_validation/tts_${variant}_$language.wav")
                WavCodec.writeAtomically(target, WavCodec.encodeMonoPcm16(output.audioData, output.sampleRate))
                val decoded = asr.transcribe(output.audioData, null, output.sampleRate)
                rows.put(JSONObject().put("language", language).put("text", text)
                    .put("reference", referenceName).put("samples", output.audioData.size)
                    .put("sample_rate", output.sampleRate).put("audio_ms", output.durationMs)
                    .put("synthesis_ms", output.inferenceTimeMs).put("roundtrip", decoded.text)
                    .put("cer_whitespace_stripped", fx.cer(text, decoded.text)))
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
