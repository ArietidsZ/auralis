package com.dialect.interpreter.session

import kotlinx.coroutines.flow.StateFlow

/**
 * Frozen session contract (spec docs/specs/2026-09-05-auralis/02-runtime.md R02).
 *
 * Lane B renders sessions only through [SessionController]. Field names/types are
 * frozen; adding defaulted fields or implementation detail is allowed, renaming
 * or retyping is not (must go through reports/interface-A.md first).
 */
data class SessionConfig(
    val sourceLanguage: String,
    val targetLanguage: String,
    val voiceProfileId: String? = null
)

enum class SessionPhase { IDLE, STARTING, ACTIVE, STOPPING, FAILED }

enum class WorkStage { CAPTURE, ASR, MT, TTS, PLAYBACK }

enum class TurnStatus {
    CAPTURED, RECOGNIZING, TRANSLATING, SYNTHESIZING,
    PLAYING, COMPLETE, FAILED, DROPPED, CANCELLED
}

data class SessionProblem(
    val code: String,
    val stage: WorkStage?,
    val message: String,
    val recoverable: Boolean
)

data class TranscriptTurn(
    val sessionId: Long,
    val id: Long,
    val sourceText: String = "",
    val translatedText: String? = null,
    val status: TurnStatus = TurnStatus.CAPTURED,
    val problem: SessionProblem? = null
)

data class SessionSnapshot(
    val sessionId: Long = 0,
    val phase: SessionPhase = SessionPhase.IDLE,
    val captureActive: Boolean = false,
    val activeStages: Set<WorkStage> = emptySet(),
    val turns: List<TranscriptTurn> = emptyList(),
    val problem: SessionProblem? = null,
    val droppedTurns: Long = 0
)

interface SessionController {
    val snapshot: StateFlow<SessionSnapshot>
    val amplitude: StateFlow<Float>
    suspend fun start(config: SessionConfig)
    suspend fun stop()
    fun close() // request cleanup on owned scope; idempotent
}

/**
 * Resolves a voice profile id into the speaker embedding used for TTS
 * conditioning. Implemented by lane B on top of the voice profile repository so
 * profile validation cannot be bypassed. Returns null when the profile is
 * missing/invalid; never a fabricated (e.g. all-zero) embedding.
 */
fun interface VoiceProfileResolver {
    suspend fun resolve(profileId: String): FloatArray?
}

/**
 * Thrown by [SessionController.start] when a session cannot begin (already
 * started, controller closed, RECORD_AUDIO missing, mandatory ASR model or
 * capture hardware unavailable). The same fact is recorded in
 * [SessionSnapshot.phase] = [SessionPhase.FAILED] with [SessionSnapshot.problem],
 * so callers observing state never miss it, but the throw makes the failure
 * impossible to ignore at the call site.
 */
class SessionStartException(val problem: SessionProblem) :
    IllegalStateException(problem.message)
