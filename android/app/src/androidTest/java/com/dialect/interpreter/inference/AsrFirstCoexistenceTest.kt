package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.SherpaJni
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native ASR loads ORT 1.24.2 first; the Java JNI reuses that runtime. */
@RunWith(AndroidJUnit4::class)
class AsrFirstCoexistenceTest {

    private lateinit var engine: AsrEngine

    @Before
    fun setUp() {
        AsrDeviceFixtures.requireAsrBundle()
        engine = AsrEngine(OnnxModelManager(AsrDeviceFixtures.targetCtx))
    }

    @Test
    fun asrFirstThenOrtJava_bothRealInferenceAndAlternation() = runBlocking {
        val fx = AsrDeviceFixtures
        val (de, sr) = fx.wavAsset("de.wav")

        // --- Phase A: sherpa native ORT first (fresh process) ---
        assertFalse(
            "AAR libonnxruntime.so must not be mapped yet, mapped=${fx.mappedApkLibs()}",
            fx.mappedApkLibs().contains("libonnxruntime.so"),
        )
        SherpaJni.load()
        engine.load()
        val first = engine.transcribe(de, language = null, sampleRate = sr)
        val deRef = fx.reference("de")
        val deCer = fx.cer(deRef, first.text)
        assertTrue("de.wav device CER $deCer too high", deCer <= 0.10 && first.text.isNotBlank())

        assertFalse("Java JNI must remain unloaded during ASR-only inference",
            fx.mappedApkLibs().contains("libonnxruntime4j_jni.so"))

        // --- Phase B: Java ORT (AAR 1.24.2) joins; binding probe executes ---
        val env = OrtEnvironment.getEnvironment()
        val ortVersion = env.getVersion()
        assertEquals("1.24.2", ortVersion)
        val probeBytes = fx.bindingProbeModelBytes()
        val probeRun: () -> FloatArray = {
            env.createSession(probeBytes).use { session ->
                session.run(mapOf<String, OnnxTensor>()).use { result ->
                    val tensor = result.get(0) as? OnnxTensor
                        ?: error("binding probe produced no output")
                    assertTrue(
                        "shape ${tensor.info.shape.toList()}",
                        tensor.info.shape.contentEquals(longArrayOf(2, 2)),
                    )
                    FloatArray(4).also { tensor.floatBuffer.get(it) }
                }
            }
        }
        val probe1 = probeRun()
        assertTrue("binding probe wrong result: ${probe1.toList()}",
            probe1.contentEquals(floatArrayOf(11f, 22f, 33f, 44f)))

        // --- Phase C: coexistence + alternation ---
        val libs = fx.mappedApkLibs()
        assertTrue(
            "shared ORT and Java JNI must be mapped: ${libs.filter { it.contains("onnx") }}",
            !libs.contains("libonnxrtnsher.so") && libs.contains("libonnxruntime.so") &&
                libs.contains("libonnxruntime4j_jni.so"),
        )
        val again = engine.transcribe(de, language = null, sampleRate = sr)
        assertEquals(first.text, again.text)
        val probe2 = probeRun()
        assertTrue(probe2.contentEquals(probe1))

        // --- Phase D: release ASR while the Java side keeps working ---
        engine.release()
        val probe3 = probeRun()
        assertTrue(probe3.contentEquals(probe1))

        fx.writeReport(
            "asr_first_coexistence.json",
            """{
  "order": "sherpa-native-first",
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
