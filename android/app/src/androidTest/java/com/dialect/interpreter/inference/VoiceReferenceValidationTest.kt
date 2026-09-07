package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceReferenceValidationTest {
    @Test
    fun invalidReferencesAreRejectedBeforeGraphLoad_andIdsDoNotCacheAudio() = runBlocking {
        val fx = AsrDeviceFixtures
        val manager = OnnxModelManager(fx.targetCtx)
        val engine = TtsEngine(manager)
        val field = OnnxModelManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        val sessions = field.get(manager) as Map<*, *>
        try {
            for (audio in listOf(FloatArray(0), floatArrayOf(Float.NaN), FloatArray(16000),
                FloatArray(16000) { 1e-6f }, FloatArray(16000 * 30 + 1) { 0.1f })) {
                assertTrue(runCatching { engine.extractSpeakerEmbedding(audio, "same", 16000) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue("invalid input loaded an ONNX graph", sessions.isEmpty())
            }
            val (zh, zhRate) = fx.wavAsset("ref_zh.wav")
            val (en, enRate) = fx.wavAsset("ref_en.wav")
            val first = engine.extractSpeakerEmbedding(zh, "same", zhRate)
            assertTrue(runCatching { engine.extractSpeakerEmbedding(FloatArray(16000), "same", 16000) }.exceptionOrNull() is IllegalArgumentException)
            val changed = engine.extractSpeakerEmbedding(en, "same", enRate)
            val uncached = engine.extractSpeakerEmbedding(en, null, enRate)
            assertFalse("same ID returned another reference's embedding", first.contentEquals(changed))
            assertTrue("valid reference changed through ID handling", changed.contentEquals(uncached))
        } finally {
            engine.release()
            manager.releaseAll()
        }
    }
}
