package com.dialect.interpreter.inference

/**
 * Turnkey speaker-embedding extraction for voice-profile creation (lane B).
 *
 * Uses its own engine over the injected [OnnxModelManager]; because the
 * manager is per-controller/per-probe isolated (app-level shared managers would
 * cross-release sessions between controllers — see interface-A), extraction
 * never interferes with a live session. Only the speaker-encoder module is
 * loaded; no synthesis stack, no fabricated embeddings.
 *
 * Protocol (verified host runner convert/tts_runner.py): the bundle speaker
 * encoder consumes a 24 kHz log-mel frontend, so the 16 kHz capture is
 * resampled inside [TtsEngine.extractSpeakerEmbedding]; the returned ECAPA
 * embedding is raw (NOT L2-normalized) to match the conditioning scale the
 * verified synthesis path was proven with.
 *
 * For lane B wiring (voice profile creation):
 * ```
 * val manager = OnnxModelManager(context)          // isolated, not the app-shared one
 * val extractor = SpeakerEmbeddingExtractor(manager)
 * val embedding = extractor.extract(referenceAudio16k, profileId)
 * // persist via VoiceProfileRepository, then manager.releaseAll()
 * ```
 */
class SpeakerEmbeddingExtractor(private val modelManager: OnnxModelManager) {

    /**
     * @param referenceAudio real 16 kHz PCM reference audio; empty input fails.
     * @param profileId optional cache key; must never be a synthetic all-zero
     *   profile — the extractor refuses fabricated embeddings by contract.
     * @return Raw speaker embedding for TTS conditioning (not L2-normalized).
     */
    suspend fun extract(referenceAudio: FloatArray, profileId: String?): FloatArray {
        require(referenceAudio.isNotEmpty()) { "Reference audio is empty" }
        val engine = TtsEngine(modelManager)
        try {
            return engine.extractSpeakerEmbedding(referenceAudio, profileId)
        } finally {
            engine.release()
        }
    }
}
