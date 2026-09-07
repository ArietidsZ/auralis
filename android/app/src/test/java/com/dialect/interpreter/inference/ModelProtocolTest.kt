package com.dialect.interpreter.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec R05: load-time protocol checks — a mismatched model is "unsupported",
 * never silently executed.
 */
class ModelProtocolTest {

    private fun spec(names: Set<String>, dtype: String?, rank: Int?) =
        ModelProtocol.TensorSpec(names, dtype, rank)

    @Test
    fun `valid encoder protocol passes`() {
        ModelProtocol.validateAsrEncoder(
            inputs = spec(setOf("audio_features"), "FLOAT", 3),
            outputs = spec(setOf("logits"), "FLOAT", 3)
        )
    }

    @Test
    fun `encoder with wrong input names is unsupported`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.validateAsrEncoder(
                inputs = spec(setOf("audio"), "FLOAT", 3),
                outputs = spec(setOf("logits"), "FLOAT", 3)
            )
        }
    }

    @Test
    fun `encoder with wrong input dtype is unsupported`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.validateAsrEncoder(
                inputs = spec(setOf("audio_features"), "INT64", 3),
                outputs = spec(setOf("logits"), "FLOAT", 3)
            )
        }
    }

    @Test
    fun `encoder with wrong input rank is unsupported`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.validateAsrEncoder(
                inputs = spec(setOf("audio_features"), "FLOAT", 2),
                outputs = spec(setOf("logits"), "FLOAT", 3)
            )
        }
    }

    @Test
    fun `valid decoder protocol passes`() {
        ModelProtocol.validateAsrDecoder(
            inputs = mapOf(
                "input_ids" to spec(setOf("input_ids"), "INT64", 2),
                "encoder_hidden_states" to spec(setOf("encoder_hidden_states"), "FLOAT", 3)
            ),
            outputs = spec(setOf("logits"), "FLOAT", 3)
        )
    }

    @Test
    fun `decoder missing input_ids is unsupported`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.validateAsrDecoder(
                inputs = mapOf(
                    "encoder_hidden_states" to spec(setOf("encoder_hidden_states"), "FLOAT", 3)
                ),
                outputs = spec(setOf("logits"), "FLOAT", 3)
            )
        }
    }

    @Test
    fun `decoder with non-int64 ids is unsupported`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.validateAsrDecoder(
                inputs = mapOf(
                    "input_ids" to spec(setOf("input_ids"), "FLOAT", 2),
                    "encoder_hidden_states" to spec(setOf("encoder_hidden_states"), "FLOAT", 3)
                ),
                outputs = spec(setOf("logits"), "FLOAT", 3)
            )
        }
    }

    @Test
    fun `tts module without inputs or outputs is unsupported`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.validateTtsModule(
                "TTS talker LM",
                inputs = spec(emptySet(), null, null),
                outputs = spec(setOf("codes"), "FLOAT", 3)
            )
        }
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.validateTtsModule(
                "TTS vocoder",
                inputs = spec(setOf("codes"), "FLOAT", 3),
                outputs = spec(emptySet(), null, null)
            )
        }
    }

    @Test
    fun `all-zero speaker embedding is rejected`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.requireValidSpeakerEmbedding(FloatArray(192))
        }
    }

    @Test
    fun `nan speaker embedding is rejected`() {
        val embedding = FloatArray(192) { 0.1f }.also { it[7] = Float.NaN }
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.requireValidSpeakerEmbedding(embedding)
        }
    }

    @Test
    fun `empty speaker embedding is rejected`() {
        assertThrows(ModelProtocol.UnsupportedModelException::class.java) {
            ModelProtocol.requireValidSpeakerEmbedding(FloatArray(0))
        }
    }

    @Test
    fun `real speaker embedding passes`() {
        val embedding = FloatArray(192) { 0.05f }
        ModelProtocol.requireValidSpeakerEmbedding(embedding)
        assertTrue(true)
    }

    @Test
    fun `language code normalization covers known names and codes`() {
        assertEquals("zh", LanguageCodes.normalize("普通话"))
        assertEquals("zh", LanguageCodes.normalize("Chinese"))
        assertEquals("en", LanguageCodes.normalize("English"))
        assertEquals("de", LanguageCodes.normalize("de"))
        assertEquals(null, LanguageCodes.normalize("Klingon"))
    }
}
