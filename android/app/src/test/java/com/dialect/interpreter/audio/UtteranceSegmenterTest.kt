package com.dialect.interpreter.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture policy tests (spec V07): pre-roll, short-pause preservation, forced
 * split, noise rejection, half-duplex reset.
 */
class UtteranceSegmenterTest {

    private fun chunk(value: Float, ms: Long): FloatArray =
        FloatArray((ms * 16000L / 1000L).toInt()) { value }

    private val silence = { chunk(0f, 100L) }
    private val speech = { chunk(0.5f, 100L) }

    private fun feedSilence(segmenter: UtteranceSegmenter, chunks: Int) {
        repeat(chunks) { segmenter.process(silence()) }
    }

    private fun feedSpeech(segmenter: UtteranceSegmenter, chunks: Int) {
        repeat(chunks) { segmenter.process(speech()) }
    }

    @Test
    fun `complete utterance includes pre-roll audio before the onset`() {
        val segmenter = UtteranceSegmenter()
        feedSilence(segmenter, 3) // 300ms of quiet audio before speech

        feedSpeech(segmenter, 5)
        var utterance: UtteranceSegmenter.Utterance? = null
        repeat(30) {
            val result = segmenter.process(silence())
            if (result != null) {
                utterance = result
                return@repeat
            }
        }

        val committed = assertNotNull(utterance)
        // 5 speech chunks + ≥2 pre-roll chunks (200ms cap) + trailing decay.
        assertTrue(
            "pre-roll must be prepended, got ${committed.audio.size} samples",
            committed.audio.size >= (5 + 2) * 1600
        )
        // The very first sample is pre-roll silence, not the loud onset.
        assertEquals(0f, committed.audio[0], 1e-6f)
    }

    @Test
    fun `short intra-utterance pause does not split the utterance`() {
        val segmenter = UtteranceSegmenter()
        feedSpeech(segmenter, 5)
        feedSilence(segmenter, 2) // 200ms pause (< 800ms timeout)
        feedSpeech(segmenter, 5)

        var committed = 0
        var last: UtteranceSegmenter.Utterance? = null
        repeat(40) {
            val result = segmenter.process(silence())
            if (result != null) {
                committed++
                last = result
            }
        }

        assertEquals("one utterance across the pause", 1, committed)
        assertTrue(
            "speech on both sides of the pause is preserved",
            (last?.audio?.size ?: 0) >= 10 * 1600
        )
    }

    @Test
    fun `long utterances are force split at the configured bound`() {
        val segmenter = UtteranceSegmenter(maxUtteranceMs = 1000)
        val committed = mutableListOf<UtteranceSegmenter.Utterance>()
        repeat(15) {
            segmenter.process(speech())?.let(committed::add) // 100ms each
        }

        assertTrue("forced split must emit the first segment", committed.isNotEmpty())
        assertTrue(
            "first segment respects the bound",
            committed[0].audio.size * 1000L / 16000L >= 1000
        )
        assertTrue(
            "segmenter continues a second segment mid-speech",
            segmenter.isActiveUtterance
        )
    }

    @Test
    fun `very short blips are rejected as noise before a turn exists`() {
        val segmenter = UtteranceSegmenter()
        segmenter.process(speech()) // 100ms < minSpeechMs(200)

        var committed = 0
        repeat(40) {
            if (segmenter.process(silence()) != null) committed++
        }

        assertEquals("blip must not commit an utterance", 0, committed)
    }

    @Test
    fun `reset drops partial utterance and vad state`() {
        val segmenter = UtteranceSegmenter()
        feedSpeech(segmenter, 5)
        assertTrue(segmenter.isActiveUtterance)

        segmenter.reset()

        assertFalse(segmenter.isActiveUtterance)
        // After reset, silence must not complete a stale utterance.
        var committed = 0
        repeat(30) { if (segmenter.process(silence()) != null) committed++ }
        assertEquals(0, committed)
    }

    @Test
    fun `preroll buffer is bounded`() {
        val segmenter = UtteranceSegmenter()
        feedSilence(segmenter, 50) // 5s of silence, preroll capped at 200ms

        feedSpeech(segmenter, 3)
        var last: UtteranceSegmenter.Utterance? = null
        repeat(30) {
            val result = segmenter.process(silence())
            if (result != null) last = result
        }

        val committed = assertNotNull(last)
        // Preroll is capped at 200ms; the utterance may also include the decay
        // + trailing-silence tail (≤30 fed silence chunks), never the full 5s
        // of leading silence.
        assertTrue(
            "utterance must not absorb the full leading silence: ${committed.audio.size}",
            committed.audio.size <= 1600 * (3 + 30 + 2)
        )
    }

    private fun assertNotNull(value: UtteranceSegmenter.Utterance?): UtteranceSegmenter.Utterance =
        value ?: throw AssertionError("expected an utterance")
}
