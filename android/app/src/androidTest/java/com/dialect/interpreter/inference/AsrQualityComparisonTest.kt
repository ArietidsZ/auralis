package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lane 2 — Android Kotlin ASR vs host official samples (same WAVs, same
 * references, same CER normalization as convert/asr_runner.py).
 *
 * Samples: de (German, host CER 0.0), fast1 (short Chinese, host 0.1972),
 * noise2 (Chinese, host 0.3548), rap1 (29 s KV-margin whole clip, host
 * 0.1821), noise1-en (88 s long noise: host segmented 0.1922 via Silero VAD
 * while Android splits by native energy — the measured difference is the
 * point of this lane, host numbers are never substituted for device ones).
 *
 * Everything decodes on-device (emulator-5580). Performance here is
 * emulator-functional evidence, not a phone physical baseline.
 */
@RunWith(AndroidJUnit4::class)
class AsrQualityComparisonTest {

    private lateinit var engine: AsrEngine

    @Before
    fun setUp() {
        AsrDeviceFixtures.requireAsrBundle()
        engine = AsrEngine(OnnxModelManager(AsrDeviceFixtures.targetCtx))
        runBlocking { engine.load() }
    }

    private fun jsonString(s: String): String = JsonPrimitive(s).toString()

    private data class Sample(
        val stem: String,
        val maxCer: Double,
        val minLengthRatio: Double,
    )

    @Test
    fun deviceAsrMatchesHostQualityOnRealSamples() = runBlocking {
        val fx = AsrDeviceFixtures
        // minLengthRatio guards against truncation collapse (KV or segmentation);
        // CER bounds are recorded per-sample with host references kept alongside.
        val samples = listOf(
            Sample("de", maxCer = 0.10, minLengthRatio = 0.0),
            Sample("fast1", maxCer = 0.45, minLengthRatio = 0.0),
            Sample("noise2", maxCer = 0.60, minLengthRatio = 0.0),
            Sample("rap1", maxCer = 0.35, minLengthRatio = 0.6),
            Sample("noise1-en", maxCer = 0.80, minLengthRatio = 0.0),
        )
        val rows = StringBuilder()
        for (s in samples) {
            val (pcm, sr) = fx.wavAsset("${s.stem}.wav")
            val ref = fx.reference(s.stem)
            val result = engine.transcribe(pcm, language = null, sampleRate = sr)
            val cer = fx.cer(ref, result.text)
            val hypLen = result.text.filterNot { it.isWhitespace() }.length
            val refLen = ref.filterNot { it.isWhitespace() }.length
            val lenRatio = if (refLen == 0) 1.0 else hypLen.toDouble() / refLen

            // Truncation collapse guard: no bare "language", no blank decode.
            assertFalse("${s.stem}: decode collapsed", result.text.isBlank())
            assertFalse(
                "${s.stem}: KV-truncated sentinel text emitted",
                result.text.trim().lowercase() == "language",
            )
            if (s.minLengthRatio > 0) {
                assertTrue(
                    "${s.stem}: output length ratio $lenRatio < ${s.minLengthRatio} (truncation)",
                    lenRatio >= s.minLengthRatio,
                )
            }
            assertTrue(
                "${s.stem}: device CER $cer exceeds bound ${s.maxCer}: '${result.text}'",
                cer <= s.maxCer,
            )
            rows.append("""  {"stem": """ + jsonString(s.stem))
            rows.append(""", "dur_s": """ + "%.3f".format(pcm.size / sr.toDouble()))
            rows.append(""", "cer": """ + cer)
            rows.append(""", "ref_len": """ + refLen)
            rows.append(""", "hyp_len": """ + hypLen)
            rows.append(""", "segments": """ + result.segmentCount)
            rows.append(""", "decode_ms": """ + result.durationMs)
            rows.append(
                """, "rtf": """ +
                    "%.4f".format(result.durationMs / 1000.0 / (pcm.size.toDouble() / sr))
            )
            rows.append(""", "text": """ + jsonString(result.text) + "},\n")
        }

        fx.writeReport(
            "device_asr_quality.json",
            """{
  "device": "emulator-5580 API35 arm64 (emulator, not a phone baseline)",
  "engine": "AsrEngine (sherpa-onnx qwen3-asr 0.6B int8, energy split >36s, pieces <=20s)",
  "cer_normalization": "identical to convert/asr_runner.py (char edit distance, whitespace-stripped)",
  "results": [
""" + rows.toString().trimEnd().trimEnd(',') + """
  ]
}
""",
        )
    }
}
