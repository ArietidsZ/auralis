package com.dialect.interpreter.audio

/**
 * Energy-based voice activity detector with a sample-count-driven clock.
 *
 * All timings derive from actual sample counts delivered by the capture loop
 * (spec R04) — never from a nominal chunk duration. Returns onset and speech
 * duration so the segmenter can apply pre-roll, minimum-speech and forced-split
 * policy; this class stays a pure state machine (unit-testable, no Android deps).
 */
class VoiceActivityDetector {

    companion object {
        private const val ENERGY_THRESHOLD = 0.02f   // RMS threshold for speech
        const val SILENCE_TIMEOUT_MS = 800L          // Silence before end-of-utterance
    }

    enum class State { SILENCE, SPEECH, TRAILING_SILENCE }

    private var state = State.SILENCE
    private var speechStartTimeMs = 0L
    private var silenceStartTimeMs = 0L
    private var lastVoicedEndMs = 0L
    private var audioClockMs = 0L
    private var energySmoothed = 0f

    data class VadResult(
        val isSpeech: Boolean,
        /** True on the SILENCE → SPEECH transition (utterance onset). */
        val onset: Boolean,
        /** True when trailing silence ended the utterance. */
        val utteranceComplete: Boolean,
        /** Measured duration of the speech portion of the current utterance. */
        val speechDurationMs: Long,
        val energy: Float
    )

    /**
     * Process one chunk. The chunk duration is derived from its actual sample
     * count at [sampleRate].
     */
    fun process(audioChunk: FloatArray, sampleRate: Int = 16000): VadResult {
        if (audioChunk.isEmpty()) {
            return VadResult(
                isSpeech = state == State.SPEECH || state == State.TRAILING_SILENCE,
                onset = false,
                utteranceComplete = false,
                speechDurationMs = currentSpeechDurationMs(),
                energy = energySmoothed
            )
        }

        val rms = kotlin.math.sqrt(
            audioChunk.sumOf { (it * it).toDouble() } / audioChunk.size
        ).toFloat()

        // Time-constant smoothing (τ≈557ms ≙ α=0.30 at 200ms chunks) so decay
        // behaves identically for any chunk duration the capture loop delivers.
        val alpha = 1f - kotlin.math.exp(-audioChunk.size * 1000f / sampleRate / 557f)
        energySmoothed = (1f - alpha) * energySmoothed + alpha * rms

        val isSpeech = energySmoothed > ENERGY_THRESHOLD
        val now = audioClockMs
        val chunkDurationMs = audioChunk.size * 1000L / sampleRate.toLong()
        if (rms > ENERGY_THRESHOLD) {
            // Raw (unsmoothed) voiced energy: tracks the real spoken extent,
            // independent of smoothing decay lag.
            lastVoicedEndMs = now + chunkDurationMs
        }
        var onset = false
        var utteranceComplete = false
        var completedSpeechMs = 0L

        when (state) {
            State.SILENCE -> if (isSpeech) {
                state = State.SPEECH
                onset = true
                speechStartTimeMs = now
                lastVoicedEndMs = now + chunkDurationMs
            }

            State.SPEECH -> if (!isSpeech) {
                state = State.TRAILING_SILENCE
                silenceStartTimeMs = now
            }

            State.TRAILING_SILENCE -> if (isSpeech) {
                // Short intra-utterance pause: keep the buffer going.
                state = State.SPEECH
            } else if (now - silenceStartTimeMs > SILENCE_TIMEOUT_MS) {
                // Voiced duration from onset to the last raw-speech chunk —
                // decay lag must not inflate it into fake speech.
                completedSpeechMs = lastVoicedEndMs - speechStartTimeMs
                utteranceComplete = true
                state = State.SILENCE
            }
        }

        audioClockMs += chunkDurationMs

        return VadResult(
            isSpeech = state == State.SPEECH || state == State.TRAILING_SILENCE,
            onset = onset,
            utteranceComplete = utteranceComplete,
            speechDurationMs = if (utteranceComplete) completedSpeechMs else currentSpeechDurationMs(),
            energy = energySmoothed
        )
    }

    /** Speech duration of the utterance currently being tracked. */
    fun currentSpeechDurationMs(): Long = when (state) {
        State.SILENCE -> 0L
        State.SPEECH, State.TRAILING_SILENCE -> lastVoicedEndMs - speechStartTimeMs
    }

    fun reset() {
        state = State.SILENCE
        speechStartTimeMs = 0L
        silenceStartTimeMs = 0L
        lastVoicedEndMs = 0L
        audioClockMs = 0L
        energySmoothed = 0f
    }
}
