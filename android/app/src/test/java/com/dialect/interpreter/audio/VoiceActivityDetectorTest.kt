package com.dialect.interpreter.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {

    private fun chunk(value: Float, size: Int = 1600): FloatArray =
        FloatArray(size) { value }

    private val silence = chunk(0f)
    private val speech = chunk(0.5f)

    @Test
    fun `loud chunk transitions to speech`() {
        val vad = VoiceActivityDetector()
        val result = vad.process(speech, 200L)

        assertTrue(result.isSpeech)
        assertEquals(false, result.utteranceComplete)
    }

    @Test
    fun `leading silence is not speech`() {
        val vad = VoiceActivityDetector()
        val result = vad.process(silence, 200L)

        assertEquals(false, result.isSpeech)
        assertEquals(false, result.utteranceComplete)
    }

    @Test
    fun `trailing silence completes the utterance after timeout`() {
        val vad = VoiceActivityDetector()
        vad.process(speech, 200L)   // enter SPEECH
        vad.process(speech, 200L)   // stay SPEECH

        // The energy low-pass filter needs several silence chunks to decay below the
// threshold before the trailing-silence timer starts, then SILENCE_TIMEOUT_MS
// (800ms) more. Feed sustained silence well past that.
        var complete = false
        for (i in 0 until 40) {
            complete = vad.process(silence, 200L).utteranceComplete
            if (complete) break
        }
        assertTrue("Utterance should complete after sustained silence", complete)
    }

    @Test
    fun `brief silence resumes speech without completing`() {
        val vad = VoiceActivityDetector()
        vad.process(speech, 200L)   // SPEECH
        vad.process(silence, 200L)  // -> TRAILING_SILENCE (only 200ms in)
        val resumed = vad.process(speech, 200L) // back to SPEECH

        assertEquals(true, resumed.isSpeech)
        assertEquals(false, resumed.utteranceComplete)
    }

    @Test
    fun `reset clears state`() {
        val vad = VoiceActivityDetector()
        vad.process(speech, 200L)
        assertEquals(true, vad.process(speech, 200L).isSpeech)

        vad.reset()
        val after = vad.process(silence, 200L)
        assertEquals(false, after.isSpeech)
    }
}