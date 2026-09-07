package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.SherpaJni
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Java loads ORT 1.24.2 first; native ASR reuses that exact runtime. */
@RunWith(AndroidJUnit4::class)
class OrtFirstCoexistenceTest {

    private lateinit var engine: AsrEngine

    /** Java ORT run of the binding probe; expected C = A+B. */
    private fun ortBindingRun(session: OrtSession): FloatArray {
        session.run(mapOf<String, OnnxTensor>()).use { result ->
            val tensor = result.get(0) as? OnnxTensor
                ?: error("binding probe produced no output")
            assertTrue("shape ${tensor.info.shape.toList()}", tensor.info.shape.contentEquals(longArrayOf(2, 2)))
            val out = FloatArray(4)
            tensor.floatBuffer.get(out)
            return out
        }
    }

    private fun newProbeSession(env: OrtEnvironment): OrtSession =
        env.createSession(AsrDeviceFixtures.bindingProbeModelBytes())

    @Before
    fun setUp() {
        AsrDeviceFixtures.requireAsrBundle()
        engine = AsrEngine(OnnxModelManager(AsrDeviceFixtures.targetCtx))
    }

    @Test
    fun ortFirstThenSherpa_bothRealInferenceAndAlternation() = runBlocking {
        val fx = AsrDeviceFixtures
        val (de, sr) = fx.wavAsset("de.wav")

        // --- Phase A: Java ORT (AAR 1.24.2) first ---
        assertFalse(
            "sherpa must not be loaded yet, mapped=${fx.mappedApkLibs()}",
            fx.mappedApkLibs().contains("libsherpa-onnx-jni.so"),
        )
        val env = OrtEnvironment.getEnvironment()
        val ortVersion = env.getVersion()
        assertEquals("1.24.2", ortVersion)
        val probeRun: () -> FloatArray = {
            newProbeSession(env).use { session -> ortBindingRun(session) }
        }
        val probe1 = probeRun()
        // C = A + B from the graph's own initializers
        assertTrue(
            "binding probe wrong result: ${probe1.toList()}",
            probe1.contentEquals(floatArrayOf(11f, 22f, 33f, 44f)),
        )
        assertTrue(
            "AAR libonnxruntime.so must be mapped after Java ORT ran; " +
                "mapped=${fx.mappedApkLibs()} near=${fx.mapsOffsetsNear(fx.debugLibDataOffset("lib/arm64-v8a/libonnxruntime.so"))}",
            fx.mappedApkLibs().contains("libonnxruntime.so"),
        )

        // --- Phase B: sherpa native ORT joins the same process ---
        SherpaJni.load()
        val libs = fx.mappedApkLibs()
        assertFalse("renamed Runtime must be absent", libs.contains("libonnxrtnsher.so"))
        assertTrue("libsherpa-onnx-jni.so must be mapped", libs.contains("libsherpa-onnx-jni.so"))
        assertTrue(
            "the shared ORT must remain mapped",
            libs.contains("libonnxruntime.so"),
        )

        engine.load()
        val first = engine.transcribe(de, language = null, sampleRate = sr)
        val deRef = fx.reference("de")
        val deCer = fx.cer(deRef, first.text)
        assertTrue(
            "de.wav device CER $deCer too high (text='${first.text}')",
            deCer <= 0.10 && first.text.isNotBlank(),
        )

        // --- Phase C: alternation while both runtimes are warm ---
        val probe2 = probeRun()
        val again = engine.transcribe(de, language = null, sampleRate = sr)
        assertEquals("ASR greedy decode must be deterministic across alternation", first.text, again.text)
        assertTrue(probe2.contentEquals(probe1))

        // --- Phase D: release ASR; the Java ORT side keeps working (no UAF) ---
        engine.release()
        val probe3 = probeRun()
        assertTrue(probe3.contentEquals(probe1))

        fx.writeReport(
            "ort_first_coexistence.json",
            """{
  "order": "java-ort-first",
  "ort_java_version": "$ortVersion",
  "binding_probe_output": ${probe1.toList()},
  "libs_after_both_loaded": ${fx.mappedApkLibs().filter { it.contains("onnx") || it.contains("sherpa") }.sorted().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
  "de_cer": $deCer,
  "de_text": "${first.text.replace("\"", "'")}",
  "de_decode_ms": ${first.durationMs},
  "vm_rss_kb_after_both": ${fx.vmRssKb()},
  "device": "emulator-5580 API35 arm64"
}
""",
        )
    }
}
