package com.dialect.interpreter.audio

/**
 * Turns a raw chunk stream into committed utterances. Pure logic — no Android,
 * no coroutines — so capture policy is unit-testable (V07).
 *
 * Policy (spec R03/R04):
 *  - ~200 ms pre-roll is prepended to each utterance so soft onsets are not cut.
 *  - Short intra-utterance pauses do not split (VAD trailing-silence resume).
 *  - Utterances are force-split at [maxUtteranceMs] so a queued turn can never
 *    exceed the session's 10 s bound (bytes/time stay bounded).
 *  - Utterances with speech below [minSpeechMs] are treated as noise and
 *    silently dropped before a turn is ever minted.
 */
class UtteranceSegmenter(
    private val vad: VoiceActivityDetector = VoiceActivityDetector(),
    private val sampleRate: Int = AudioRecorder.SAMPLE_RATE,
    private val prerollMs: Long = 200,
    private val maxUtteranceMs: Long = 10_000,
    private val minSpeechMs: Long = 200
) {

    /** A committed utterance, ready for the session queue. */
    data class Utterance(val audio: FloatArray) {
        val durationMs: Long get() = audio.size * 1000L / 16000L
    }

    private val preroll = ArrayDeque<FloatArray>()
    private var prerollSamples = 0
    private val maxPrerollSamples = (prerollMs * sampleRate / 1000L).toInt()

    private var inUtterance = false
    private val speechBuffer = mutableListOf<FloatArray>()
    private var speechSamples = 0

    val isActiveUtterance: Boolean get() = inUtterance

    /**
     * Feed one capture chunk; returns an [Utterance] when one was committed
     * (end-of-speech or forced split), otherwise null.
     */
    fun process(chunk: FloatArray): Utterance? {
        val result = vad.process(chunk, sampleRate)

        if (!inUtterance) {
            if (result.onset) {
                // Seed the utterance with the pre-roll, then the current chunk.
                speechBuffer.addAll(preroll)
                speechSamples = prerollSamples
                preroll.clear()
                prerollSamples = 0
                inUtterance = true
                appendToBuffer(chunk)
                return maybeForceSplit(result)
            }
            pushPreroll(chunk)
            return null
        }

        appendToBuffer(chunk)
        if (result.utteranceComplete) {
            return finalize(forced = false, stillSpeaking = false, speechMs = result.speechDurationMs)
        }
        return maybeForceSplit(result)
    }

    /**
     * Drop any partial utterance and VAD state. Called by the session while
     * playback is active (half-duplex: never recognize the synthesized voice)
     * and when starting a fresh capture.
     */
    fun reset() {
        vad.reset()
        preroll.clear()
        prerollSamples = 0
        speechBuffer.clear()
        speechSamples = 0
        inUtterance = false
    }

    private fun maybeForceSplit(result: VoiceActivityDetector.VadResult): Utterance? {
        val durationMs = speechSamples * 1000L / sampleRate.toLong()
        if (durationMs < maxUtteranceMs) return null
        return finalize(
            forced = true,
            stillSpeaking = result.isSpeech,
            speechMs = result.speechDurationMs
        )
    }

    private fun finalize(forced: Boolean, stillSpeaking: Boolean, speechMs: Long): Utterance? {
        val utterance = buildUtterance()
        speechBuffer.clear()
        speechSamples = 0
        inUtterance = false

        val accepted = forced || speechMs >= minSpeechMs
        if (!accepted) {
            // Noise/very short blip: rejected before any turn exists.
            return null
        }

        if (stillSpeaking) {
            // Forced split mid-speech: the next chunk continues a new segment
            // (without pre-roll; nothing before the boundary is lost).
            inUtterance = true
        }
        return utterance
    }

    private fun buildUtterance(): Utterance {
        val total = speechSamples
        if (total == 0) return Utterance(FloatArray(0))
        val out = FloatArray(total)
        var offset = 0
        for (part in speechBuffer) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return Utterance(out)
    }

    private fun appendToBuffer(chunk: FloatArray) {
        speechBuffer.add(chunk)
        speechSamples += chunk.size
    }

    private fun pushPreroll(chunk: FloatArray) {
        preroll.addLast(chunk)
        prerollSamples += chunk.size
        while (prerollSamples > maxPrerollSamples && preroll.size > 1) {
            val dropped = preroll.removeFirst()
            prerollSamples -= dropped.size
        }
    }
}
