package com.dialect.interpreter.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {

    private fun chunk(value: Float, size: Int = 1600): FloatArray =
        FloatArray(size) { value }

    private val silence = chunk(0f)
    private val speech = chunk(0.5f)

    @Test
    fun `loud chunk transitions to speech and reports onset`() {
        val vad = VoiceActivityDetector()
        val result = vad.process(speech, 16000)

        assertTrue(result.isSpeech)
        assertTrue(result.onset)
        assertFalse(result.utteranceComplete)
    }

    @Test
    fun `leading silence is not speech`() {
        val vad = VoiceActivityDetector()
        val result = vad.process(silence, 16000)

        assertEquals(false, result.isSpeech)
        assertEquals(false, result.onset)
        assertEquals(false, result.utteranceComplete)
    }

    @Test
    fun `trailing silence completes the utterance after timeout`() {
        val vad = VoiceActivityDetector()
        vad.process(speech, 16000)
        vad.process(speech, 16000)

        // The energy low-pass needs several silence chunks to decay below the
        // threshold, then SILENCE_TIMEOUT_MS (800ms) more of trailing silence.
        var complete = false
        for (i in 0 until 40) {
            val result = vad.process(silence, 16000)
            complete = result.utteranceComplete
            if (complete) break
        }
        assertTrue("Utterance should complete after sustained silence", complete)
    }

    @Test
    fun `clock is driven by actual sample counts`() {
        fun chunksUntilComplete(chunkSamples: Int): Int {
            val vad = VoiceActivityDetector()
            vad.process(FloatArray(chunkSamples) { 0.5f }, 16000)
            repeat(400) { i ->
                if (vad.process(FloatArray(chunkSamples), 16000).utteranceComplete) return i + 1
            }
            throw AssertionError("never completed")
        }

        val normal = chunksUntilComplete(1600)   // 100ms chunks
        val half = chunksUntilComplete(800)      // 50ms chunks
        val normalTimeMs = normal * 100L
        val halfTimeMs = half * 50L
        val ratio = halfTimeMs.toDouble() / normalTimeMs
        assertTrue(
            "completion time must be chunk-size invariant (normal=${normalTimeMs}ms, " +
                "half=${halfTimeMs}ms ratio=$ratio)",
            ratio in 0.6..1.4
        )
    }

    @Test
    fun `brief silence resumes speech without completing`() {
        val vad = VoiceActivityDetector()
        vad.process(speech, 16000)   // SPEECH
        vad.process(silence, 16000)  // -> TRAILING_SILENCE (only 100ms in)
        val resumed = vad.process(speech, 16000) // back to SPEECH

        assertEquals(true, resumed.isSpeech)
        assertEquals(false, resumed.utteranceComplete)
    }

    @Test
    fun `speech duration is tracked for the active utterance`() {
        val vad = VoiceActivityDetector()
        vad.process(speech, 16000) // onset at t=0
        repeat(4) { vad.process(speech, 16000) } // +4 x 100ms

        assertTrue(vad.currentSpeechDurationMs() >= 400)
    }

    @Test
    fun `reset clears state`() {
        val vad = VoiceActivityDetector()
        vad.process(speech, 16000)
        assertEquals(true, vad.process(speech, 16000).isSpeech)

        vad.reset()
        val after = vad.process(silence, 16000)
        assertEquals(false, after.isSpeech)
        assertEquals(0L, vad.currentSpeechDurationMs())
    }
}
