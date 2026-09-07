package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dialect.interpreter.data.WavCodec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One real utterance; each stage consumes the preceding stage's output. */
@RunWith(AndroidJUnit4::class)
class FullTranslationChainTest {
    @Test
    fun realGermanAsr_toChineseMt_toClonedChineseTts() = runBlocking {
        val fx = AsrDeviceFixtures
        fx.requireAsrBundle()
        val manager = OnnxModelManager(fx.targetCtx)
        val asr = AsrEngine(manager)
        val mt = HyMtTranslationEngine(File(manager.getModelsDir(),
            "${OnnxModelManager.MT_DIR}/${OnnxModelManager.HY_MT_GGUF}"))
        val tts = TtsEngine(manager)
        val report = JSONObject().put("source_audio", "de.wav")
        fun checkpoint(stage: String) {
            report.put("stage", stage).put("rss_kb", fx.vmRssKb())
                .put("java_heap_limit_bytes", Runtime.getRuntime().maxMemory())
                .put("java_heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                .put("native_heap_allocated_bytes", android.os.Debug.getNativeHeapAllocatedSize())
            fx.writeReport("full_translation_chain.json", report.toString(2))
        }
        try {
            checkpoint("begin")
            val (pcm, rate) = fx.wavAsset("de.wav")
            asr.load()
            val recognized = asr.transcribe(pcm, null, rate)
            report.put("asr_text", recognized.text).put("asr_ms", recognized.durationMs)
            checkpoint("asr_complete")
            mt.load()
            val translated = mt.translate(TranslationRequest(
                text = recognized.text, sourceLanguage = "German", targetLanguage = "Chinese"))
            report.put("mt_text", translated.translatedText).put("mt_ms", translated.latencyMs)
            assertTrue(translated.translatedText.isNotBlank() && translated.translatedText != recognized.text)
            checkpoint("mt_complete")
            val (reference, referenceRate) = fx.wavAsset("ref_zh.wav")
            val speaker = tts.extractSpeakerEmbedding(reference, inputSampleRate = referenceRate)
            val audio = tts.synthesize(translated.translatedText, "Chinese", speaker)
            report.put("tts_input", translated.translatedText).put("tts_ms", audio.inferenceTimeMs)
                .put("audio_samples", audio.audioData.size).put("sample_rate", audio.sampleRate)
                .put("audio_ms", audio.durationMs)
            val output = File(fx.targetCtx.getExternalFilesDir(null), "asr_validation/full_chain_zh.wav")
            WavCodec.writeAtomically(output, WavCodec.encodeMonoPcm16(audio.audioData, audio.sampleRate))
            checkpoint("tts_complete")
            val roundtrip = asr.transcribe(audio.audioData, null, audio.sampleRate)
            report.put("roundtrip_text", roundtrip.text).put("roundtrip_ms", roundtrip.durationMs)
            checkpoint("complete")
        } finally {
            tts.release()
            mt.release()
            asr.release()
            manager.releaseAll()
        }
    }
}
