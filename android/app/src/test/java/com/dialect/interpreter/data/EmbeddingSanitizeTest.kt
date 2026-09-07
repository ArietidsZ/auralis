package com.dialect.interpreter.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Review item 7: embedding sanity gate (finite, non-empty, non-zero). */
class EmbeddingSanitizeTest {

    @Test
    fun `accepts finite non-zero embedding`() {
        val embedding = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertArrayEquals(embedding, VoiceProfileRepository.sanitizeEmbedding(embedding)!!, 1e-6f)
    }

    @Test
    fun `rejects null empty all-zero and non-finite embeddings`() {
        assertNull(VoiceProfileRepository.sanitizeEmbedding(null))
        assertNull(VoiceProfileRepository.sanitizeEmbedding(FloatArray(0)))
        assertNull(VoiceProfileRepository.sanitizeEmbedding(FloatArray(4))) // all zero
        assertNull(VoiceProfileRepository.sanitizeEmbedding(floatArrayOf(0.1f, Float.NaN)))
        assertNull(VoiceProfileRepository.sanitizeEmbedding(floatArrayOf(0.1f, Float.POSITIVE_INFINITY)))
        assertNull(VoiceProfileRepository.sanitizeEmbedding(floatArrayOf(Float.NEGATIVE_INFINITY)))
    }
}
