package com.dialect.interpreter.audio

/**
 * Simple energy-based Voice Activity Detector.
 * Detects when the user is speaking vs. silence.
 */
class VoiceActivityDetector {

    companion object {
        private const val ENERGY_THRESHOLD = 0.02f      // RMS threshold for speech
        private const val SILENCE_TIMEOUT_MS = 800L      // Silence before end-of-utterance
        private const val MIN_SPEECH_DURATION_MS = 200L  // Minimum speech to consider valid
    }

    enum class State { SILENCE, SPEECH, TRAILING_SILENCE }

    private var state = State.SILENCE
    private var speechStartTime = 0L
    private var silenceStartTime = 0L
    private var audioClockMs = 0L
    private var energySmoothed = 0f

    data class VadResult(
        val isSpeech: Boolean,
        val utteranceComplete: Boolean,
        val energy: Float
    )

    /**
     * Process an audio chunk and return VAD result.
     *
     * @param audioChunk Float PCM audio chunk
     * @param chunkDurationMs Duration of the chunk in milliseconds
     * @return VAD result
     */
    fun process(audioChunk: FloatArray, chunkDurationMs: Long = 200): VadResult {
        if (audioChunk.isEmpty()) {
            return VadResult(
                isSpeech = state == State.SPEECH || state == State.TRAILING_SILENCE,
                utteranceComplete = false,
                energy = energySmoothed
            )
        }

        // Calculate RMS energy
        val rms = kotlin.math.sqrt(
            audioChunk.sumOf { (it * it).toDouble() } / audioChunk.size
        ).toFloat()

        // Exponential smoothing
        energySmoothed = 0.7f * energySmoothed + 0.3f * rms

        val isSpeech = energySmoothed > ENERGY_THRESHOLD
        val now = audioClockMs
        var utteranceComplete = false

        when (state) {
            State.SILENCE -> {
                if (isSpeech) {
                    state = State.SPEECH
                    speechStartTime = now
                }
            }
            State.SPEECH -> {
                if (!isSpeech) {
                    state = State.TRAILING_SILENCE
                    silenceStartTime = now
                }
            }
            State.TRAILING_SILENCE -> {
                if (isSpeech) {
                    state = State.SPEECH
                } else if (now - silenceStartTime > SILENCE_TIMEOUT_MS) {
                    val speechDuration = silenceStartTime - speechStartTime
                    if (speechDuration >= MIN_SPEECH_DURATION_MS) {
                        utteranceComplete = true
                    }
                    state = State.SILENCE
                }
            }
        }

        audioClockMs += chunkDurationMs.coerceAtLeast(1L)

        return VadResult(
            isSpeech = state == State.SPEECH || state == State.TRAILING_SILENCE,
            utteranceComplete = utteranceComplete,
            energy = energySmoothed
        )
    }

    /**
     * Reset the VAD state.
     */
    fun reset() {
        state = State.SILENCE
        speechStartTime = 0L
        silenceStartTime = 0L
        audioClockMs = 0L
        energySmoothed = 0f
    }
}
