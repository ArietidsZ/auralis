package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.SherpaJni
import java.nio.FloatBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs a real TTS speaker graph and ASR through one ORT 1.24.2 instance. */
@RunWith(AndroidJUnit4::class)
class TtsGraphCoexistenceTest {

    @Test
    fun realTtsSpeakerGraph_runsWhileSherpaAsrLoaded() = runBlocking {
        val fx = AsrDeviceFixtures
        fx.requireSpeakerEncoder()
        fx.requireAsrBundle()
        val (de, sr) = fx.wavAsset("de.wav")

        // sherpa side loaded first with a live recognizer
        SherpaJni.load()
        val engine = AsrEngine(OnnxModelManager(fx.targetCtx))
        engine.load()

        val env = OrtEnvironment.getEnvironment()
        assertEquals("1.24.2", env.getVersion())
        val session = env.createSession(fx.ttsSpeakerEncoderFile().absolutePath)

        val melRun: () -> FloatArray = {
            val audio24k = SherpaJni.resample(de, sr, 24000)
            val mel = Qwen3TtsProtocol.logMelSpectrogram(audio24k, 24000)
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(mel.data), longArrayOf(1, mel.frames.toLong(), 128)
            ).use { input ->
                session.run(mapOf("mel_spectrogram" to input)).use { result ->
                    val tensor = result.get("speaker_embedding").orElse(null) as? OnnxTensor
                        ?: error("speaker encoder produced no tensor output")
                    assertTrue(
                        "shape ${tensor.info.shape.toList()}",
                        tensor.info.shape.contentEquals(longArrayOf(1, 1024)),
                    )
                    FloatArray(1024).also { tensor.floatBuffer.get(it) }
                }
            }
        }

        val emb1 = melRun()
        assertTrue("speaker encoder produced non-finite values", emb1.all { it.isFinite() })

        // Alternate: ASR decode (native ORT 1.24.2) between speaker-encoder runs (AAR 1.24.2)
        val asr = engine.transcribe(de, language = null, sampleRate = sr)
        assertTrue(asr.text.isNotBlank())
        val emb2 = melRun()
        assertTrue(emb2.contentEquals(emb1))

        engine.release()
        val emb3 = melRun()
        assertTrue(emb3.all { it.isFinite() })
        session.close()

        fx.writeReport(
            "tts_graph_coexistence.json",
            """{
  "order": "sherpa-first-then-real-tts-graph",
  "graph": "tts/hf/speaker_encoder.onnx (ECAPA-TDNN, mel_spectrogram -> speaker_embedding[1,1024])",
  "input": "de.wav 16kHz -> 24kHz resample -> 128-dim log-mel (Qwen3TtsProtocol real frontend)",
  "asr_text_while_coexistent": "${asr.text.replace("\"", "'")}",
  "embedding_finite": true,
  "vm_rss_kb": ${fx.vmRssKb()},
  "device": "emulator-5580 API35 arm64"
}
""",
        )
    }
}
