package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled protocol faults followed by real synthesis on the same engine. */
@RunWith(AndroidJUnit4::class)
class TtsStageCleanupTest {
    @Test
    fun stageFailure_closesSession_beforeTheNextTurn() = runBlocking {
        val fx = AsrDeviceFixtures
        val manager = OnnxModelManager(fx.targetCtx)
        val tts = TtsEngine(manager)
        val field = OnnxModelManager::class.java.getDeclaredField("sessions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(manager) as ConcurrentHashMap<String, OrtSession>
        try {
            val (pcm, rate) = fx.wavAsset("ref_zh.wav")
            val speaker = tts.extractSpeakerEmbedding(pcm, inputSampleRate = rate)
            for (stage in listOf("talker_prefill.onnx", "vocoder.onnx")) {
                val key = "tts/$stage"
                // This tiny real binding graph intentionally has the wrong
                // protocol. It is a fault injector, never a synthesis fixture.
                val injected = OrtEnvironment.getEnvironment().createSession(fx.bindingProbeModelBytes())
                assertFalse(sessions.containsKey(key))
                sessions[key] = injected
                val failed = runCatching { tts.synthesize("你好，世界。", "Chinese", speaker) }
                assertTrue("$stage should fail its protocol check", failed.isFailure)
                assertFalse("$stage stayed cached after failure", sessions.containsKey(key))
                assertTrue("$stage session was not closed", runCatching {
                    injected.run(emptyMap<String, OnnxTensor>()).use { }
                }.isFailure)
                val recovered = tts.synthesize("你好，世界。", "Chinese", speaker)
                assertTrue("real next turn failed after $stage", recovered.audioData.isNotEmpty())
            }
            fx.writeReport("tts_stage_cleanup.json",
                """{"failed_stages":["prefill","vocoder"],"sessions_closed":true,"same_engine_next_turns_succeeded":2}""")
        } finally {
            tts.release()
            manager.releaseAll()
        }
    }
}
