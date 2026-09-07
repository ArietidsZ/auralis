package com.dialect.interpreter.inference

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsCancellationTest {
    @Test
    fun cancellationDuringRealCodePrediction_joinsWithoutAudio() = runBlocking {
        val fx = AsrDeviceFixtures
        fx.requireSpeakerEncoder()
        val manager = OnnxModelManager(fx.targetCtx)
        val tts = TtsEngine(manager)
        val (pcm, rate) = fx.wavAsset("ref_zh.wav")
        val speaker = tts.extractSpeakerEmbedding(pcm, inputSampleRate = rate)
        // Observe the real session cache only in this test, without adding a
        // production callback or substituting synthetic inference.
        val field = OnnxModelManager::class.java.getDeclaredField("sessions")
        field.isAccessible = true
        val sessions = field.get(manager) as ConcurrentHashMap<*, *>
        val returnedAudio = AtomicBoolean(false)
        val work = async(Dispatchers.Default) {
            tts.synthesize("今天天气很好，我们一起去公园散步。".repeat(3), "Chinese", speaker)
            returnedAudio.set(true)
        }
        try {
            withTimeout(30_000) {
                while (!sessions.containsKey("tts/code_predictor.onnx")) delay(20)
            }
            delay(150)
            assertFalse("generation must still be active", work.isCompleted)
            val started = SystemClock.elapsedRealtime()
            withTimeout(3_000) { work.cancelAndJoin() }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue(work.isCancelled)
            assertFalse(returnedAudio.get())
            assertFalse(sessions.containsKey("tts/talker_decode.onnx"))
            val recovered = tts.synthesize("你好，世界。", "Chinese", speaker)
            assertTrue(recovered.audioData.isNotEmpty())
            fx.writeReport("tts_cancellation.json",
                """{"real_code_predictor_loaded":true,"cancel_join_ms":$elapsed,"audio_returned":false,"next_turn_succeeded":true}""")
        } finally {
            withContext(NonCancellable) {
                work.cancelAndJoin()
                withContext(Dispatchers.IO) { tts.release(); manager.releaseAll() }
            }
        }
    }
}
