package com.dialect.interpreter.inference

import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.data.VoiceProfileRepository
import com.dialect.interpreter.data.WavCodec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullTtsUnifiedRuntimeTest {
    @Test
    fun fullProductionTts_withRealVoice_andAsrRoundtrip() = runBlocking {
        val fx = AsrDeviceFixtures
        fx.requireAsrBundle()
        fx.requireSpeakerEncoder()
        val holdAsr = InstrumentationRegistry.getArguments().getString("holdAsr", "true") == "true"
        val report = JSONObject().put("hold_asr_during_tts", holdAsr)
        fun checkpoint(stage: String) {
            report.put("stage", stage).put("rss_kb", fx.vmRssKb())
            val runtime = Runtime.getRuntime()
            report.put("java_heap_limit_bytes", runtime.maxMemory())
                .put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
                .put("native_heap_allocated_bytes", android.os.Debug.getNativeHeapAllocatedSize())
            fx.writeReport("full_tts_progress.json", report.toString(2))
        }
        val manager = OnnxModelManager(fx.targetCtx)
        val tts = TtsEngine(manager)
        val asr = AsrEngine(OnnxModelManager(fx.targetCtx))
        try {
            checkpoint("begin")
            if (holdAsr) asr.load()
            val (reference, referenceRate) = fx.wavAsset("ref_zh.wav")
            val embedding = tts.extractSpeakerEmbedding(reference, inputSampleRate = referenceRate)
            assertEquals(1024, embedding.size)
            checkpoint("speaker_embedding")
            val text = "你好，世界。"
            val output = tts.synthesize(text, "Chinese", embedding)
            checkpoint("synthesized")
            assertTrue(output.audioData.isNotEmpty() && output.audioData.all { it.isFinite() })
            val audioFile = File(fx.targetCtx.getExternalFilesDir(null), "asr_validation/full_tts_zh.wav")
            WavCodec.writeAtomically(audioFile, WavCodec.encodeMonoPcm16(output.audioData, output.sampleRate))
            report.put("audio_samples", output.audioData.size).put("sample_rate", output.sampleRate)
                .put("synthesis_ms", output.inferenceTimeMs).put("audio_ms", output.durationMs)
                .put("ort_version", OrtEnvironment.getEnvironment().getVersion())
            assertEquals("1.24.2", OrtEnvironment.getEnvironment().getVersion())
            tts.release()
            manager.releaseAll()
            if (!holdAsr) asr.load()
            val decoded = asr.transcribe(output.audioData, null, output.sampleRate)
            val normalize: (String) -> String = { it.replace(Regex("[\\p{P}\\s]"), "") }
            val cer = fx.cer(normalize(text), normalize(decoded.text))
            report.put("roundtrip_text", decoded.text).put("roundtrip_cer", cer)
            checkpoint("complete")
            fx.writeReport("full_tts_unified.json", report.toString(2))
            assertTrue("roundtrip CER $cer: ${decoded.text}", cer <= 0.25)
        } finally {
            tts.release()
            manager.releaseAll()
            asr.release()
        }
    }

    @Test
    fun invalidVoiceReferenceRate_isRejectedBeforeDurationArithmetic() = runBlocking {
        val repository = VoiceProfileRepository(AsrDeviceFixtures.targetCtx)
        for (rate in listOf(0, -16000, 8000)) {
            val result = repository.saveProfile("invalid-rate", FloatArray(48000) { 0.1f }, rate)
            assertTrue("rate $rate must be rejected", result is VoiceProfileRepository.SaveResult.Rejected)
        }
    }

    @Test
    fun oversizedPreview_isRejectedBeforeByteArrayAllocation() = runBlocking {
        val context = AsrDeviceFixtures.targetCtx
        val id = java.util.UUID.randomUUID().toString()
        val file = File(context.filesDir, "voice_profiles/$id.wav")
        file.parentFile!!.mkdirs()
        try {
            // Sparse: no multi-GB test payload is written. readBytes() would
            // throw an OutOfMemoryError before WAV parsing on the old path.
            java.io.RandomAccessFile(file, "rw").use { it.setLength(Int.MAX_VALUE.toLong() + 1) }
            org.junit.Assert.assertNull(VoiceProfileRepository(context).loadPreviewAudio(id))
        } finally {
            file.delete()
        }
    }
}
