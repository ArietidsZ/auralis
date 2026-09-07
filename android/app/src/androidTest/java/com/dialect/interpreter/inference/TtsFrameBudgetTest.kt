package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsFrameBudgetTest {
    @Test
    fun exhaustedBudgetWithoutEos_rejectsRealGraphOutput() = runBlocking {
        val fx = AsrDeviceFixtures
        fx.requireSpeakerEncoder()
        val manager = OnnxModelManager(fx.targetCtx)
        val engine = TtsEngine(manager, maxFrames = 1)
        try {
            val (pcm, rate) = fx.wavAsset("ref_zh.wav")
            val speaker = engine.extractSpeakerEmbedding(pcm, inputSampleRate = rate)
            // EOS is suppressed in the first two frames by the real protocol.
            val result = runCatching { engine.synthesize("你好，世界。", "Chinese", speaker) }
            val error = result.exceptionOrNull()
            assertTrue("truncated audio must not be returned: $error",
                error is ModelProtocol.UnsupportedModelException &&
                    error.message.orEmpty().contains("frame budget exhausted before codec EOS"))
            fx.writeReport("tts_frame_budget.json",
                """{"max_frames":1,"real_graphs_executed":true,"success_audio_returned":false,"eos_required":true}""")
        } finally {
            engine.release()
            manager.releaseAll()
        }
    }
}
