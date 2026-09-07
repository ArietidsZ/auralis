package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MT lane JNI smoke on the dedicated emulator, against the CURRENT production
 * wiring: `files/dialect_models/mt/` + `OnnxModelManager.HY_MT_GGUF`
 * (Hy-MT1.5-1.8B-1.25bit-stq43.gguf, derived per shared mt.json
 * source.transform, runtime pinned 1e411d8f). Real native generation, real
 * cancel, real release/reload. Not a quality benchmark.
 */
@RunWith(AndroidJUnit4::class)
class MtJniSmokeTest {

    private lateinit var modelFile: File

    @Before
    fun setUp() {
        modelFile = File(
            AsrDeviceFixtures.targetCtx.filesDir,
            "${OnnxModelManager.MODELS_DIR}/${OnnxModelManager.MT_DIR}/${OnnxModelManager.HY_MT_GGUF}"
        )
        check(modelFile.isFile && modelFile.length() > 100_000_000) {
            "MT gguf missing or truncated at ${modelFile.absolutePath} — push " +
                "${OnnxModelManager.HY_MT_GGUF} first"
        }
    }

    @Test
    fun nativeMt_load_translate_cancel_reload() = runBlocking {
        val fx = AsrDeviceFixtures
        val engine = HyMtTranslationEngine(modelFile)

        // --- load: real llama.cpp model creation ---
        engine.load()

        // --- real short translation ---
        val result = engine.translate(
            TranslationRequest(
                text = "今天天气很好，我们一起去公园散步吧。",
                sourceLanguage = "Chinese",
                targetLanguage = "English",
            )
        )
        assertTrue(
            "MT produced empty translation",
            result.translatedText.isNotBlank(),
        )
        assertTrue("MT latency must be recorded", result.latencyMs > 0)

        // --- cancel path: long input, cancel mid-generation ---
        val longRequest = TranslationRequest(
            text = ("跨方言语音传译系统需要在离线设备上完成识别、翻译与合成。" +
                "为了测试取消路径，这段输入足够长，生成需要一定时间。" ).repeat(12),
            sourceLanguage = "Chinese",
            targetLanguage = "English",
        )
        val generation = async(Dispatchers.Default) {
            runCatching { engine.translate(longRequest) }
        }
        withContext(Dispatchers.IO) { Thread.sleep(400) }
        engine.cancel()
        val cancelled = generation.await()
        assertTrue(
            "cancelled generation must abort or complete, not crash: ${cancelled.exceptionOrNull()}",
            cancelled.isSuccess || cancelled.exceptionOrNull() is CancellationException,
        )

        // --- release + reload: no UAF across the cycle ---
        engine.release()
        engine.release() // idempotent
        engine.load()
        val again = engine.translate(
            TranslationRequest(
                text = "谢谢你的帮助。",
                sourceLanguage = "Chinese",
                targetLanguage = "English",
            )
        )
        assertTrue(again.translatedText.isNotBlank())
        engine.release()

        // --- evidence: device-side model hash + timings ---
        val sha = withContext(Dispatchers.IO) {
            val proc = ProcessBuilder("sha256sum", modelFile.absolutePath)
                .redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText().trim().split(" ").firstOrNull() ?: ""
        }
        fx.writeReport(
            "mt_jni_smoke.json",
            """{
  "device": "emulator-5580 API35 arm64 (emulator, not a phone baseline)",
  "model_file": "${OnnxModelManager.HY_MT_GGUF}",
  "model_sha256_device": "$sha",
  "runtime_pin": "1e411d8f5a1e23525fa3265dfb4bd76265465397",
  "short_translation": {
    "source": "今天天气很好，我们一起去公园散步吧。",
    "translated": "${result.translatedText.replace("\"", "'")}",
    "latency_ms": ${result.latencyMs},
    "runtime": "${result.runtime}"
  },
  "cancel_path": "aborted-or-completed-no-crash",
  "reload_after_release": "ok"
}
""",
        )
    }
}
