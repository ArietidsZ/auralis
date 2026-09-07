package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.SherpaJni
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lane 5 — memory ablation on the single-ORT design (emulator evidence):
 * RSS after each lifecycle phase, plus the retained-memory check after
 * release (weights mmap-backed; pages stay mapped by the OS, so RSS after
 * release reflects resident pages, not leaked handles).
 */
@RunWith(AndroidJUnit4::class)
class MemoryAblationTest {

    @Test
    fun rssPhases_recordedForAblation() = runBlocking {
        val fx = AsrDeviceFixtures
        fx.requireAsrBundle()
        val (de, sr) = fx.wavAsset("de.wav")

        val baseline = fx.vmRssKb()

        // Phase 1: Java ORT env only (AAR 1.24.2 mapped)
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val afterOrtJava = fx.vmRssKb()

        // Phase 2: sherpa native ORT + full ASR model load
        SherpaJni.load()
        val afterSherpaLibs = fx.vmRssKb()
        val engine = AsrEngine(OnnxModelManager(fx.targetCtx))
        engine.load()
        val afterAsrLoad = fx.vmRssKb()
        engine.transcribe(de, language = null, sampleRate = sr)
        val afterDecode = fx.vmRssKb()

        // Phase 3: release — no UAF, RSS returns to near pre-load level
        engine.release()
        val afterRelease = fx.vmRssKb()

        fx.writeReport(
            "memory_ablation.json",
            """{
  "device": "emulator-5580 API35 arm64 (emulator, not a phone baseline)",
  "phases": {
    "process_baseline_kb": $baseline,
    "after_java_ort_env_kb": $afterOrtJava,
    "java_ort_env_cost_kb": ${afterOrtJava - baseline},
    "after_sherpa_libs_kb": $afterSherpaLibs,
    "sherpa_libs_cost_kb": ${afterSherpaLibs - afterOrtJava},
    "after_asr_model_load_kb": $afterAsrLoad,
    "asr_model_load_cost_kb": ${afterAsrLoad - afterSherpaLibs},
    "after_first_decode_kb": $afterDecode,
    "after_release_kb": $afterRelease,
    "rss_retained_after_release_kb": ${afterRelease - afterAsrLoad}
  },
  "design_notes": {
    "onnx_model_manager_env": "lazy Java environment — ASR maps the shared ORT through native JNI",
    "sherpa_jni": "single idempotent load (SherpaJni), no per-engine re-init",
    "single_ort": "one official libonnxruntime.so 1.24.2 supplies ASR and Java TTS"
  }
}
""",
        )
        assertTrue(afterAsrLoad > baseline)
        assertTrue(afterRelease < afterDecode)
    }
}
