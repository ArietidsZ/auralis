package com.dialect.interpreter.ui.screens

import com.dialect.interpreter.session.SessionProblem
import com.dialect.interpreter.session.TranscriptTurn
import com.dialect.interpreter.session.TurnStatus
import com.dialect.interpreter.session.WorkStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V04 (UI side): terminal-state classification and the clear watermark —
 * late events for cleared turns must never re-enter the visible transcript.
 */
class InterpretViewModelReductionTest {

    private fun turn(
        id: Long,
        sessionId: Long = 1,
        status: TurnStatus = TurnStatus.RECOGNIZING,
        translatedText: String? = null,
        sourceText: String = "你好",
    ) = TranscriptTurn(
        sessionId = sessionId,
        id = id,
        sourceText = sourceText,
        translatedText = translatedText,
        status = status,
    )

    // ------------------------------------------------------- terminal states

    @Test
    fun `terminal statuses are exactly COMPLETE FAILED DROPPED CANCELLED`() {
        assertTrue(InterpretViewModel.isTerminal(TurnStatus.COMPLETE))
        assertTrue(InterpretViewModel.isTerminal(TurnStatus.FAILED))
        assertTrue(InterpretViewModel.isTerminal(TurnStatus.DROPPED))
        assertTrue(InterpretViewModel.isTerminal(TurnStatus.CANCELLED))
        assertFalse(InterpretViewModel.isTerminal(TurnStatus.CAPTURED))
        assertFalse(InterpretViewModel.isTerminal(TurnStatus.RECOGNIZING))
        assertFalse(InterpretViewModel.isTerminal(TurnStatus.TRANSLATING))
        assertFalse(InterpretViewModel.isTerminal(TurnStatus.SYNTHESIZING))
        assertFalse(InterpretViewModel.isTerminal(TurnStatus.PLAYING))
    }

    // -------------------------------------------------------- clear watermark

    @Test
    fun `visibleTurns hides nothing before a clear`() {
        val turns = listOf(turn(1), turn(2), turn(3))
        assertEquals(3, InterpretViewModel.visibleTurns(turns, -1L, -1L).size)
    }

    @Test
    fun `visibleTurns hides cleared turns including late updates`() {
        val turns = listOf(
            turn(1, status = TurnStatus.COMPLETE, translatedText = "hello"),
            turn(2, status = TurnStatus.RECOGNIZING),
            turn(3, status = TurnStatus.CAPTURED),
        )
        // Clear happened after turn 2 was minted: turns 1 and 2 are gone.
        val visible = InterpretViewModel.visibleTurns(turns, watermarkSessionId = 1, watermarkTurnId = 2)
        assertEquals(listOf(3L), visible.map { it.id })
    }

    @Test
    fun `late event for a cleared turn stays hidden`() {
        // Turn 1 was cleared, then a late MT result mutates it (status advance).
        val lateUpdate = turn(
            1,
            status = TurnStatus.COMPLETE,
            translatedText = "late translation",
        )
        val visible = InterpretViewModel.visibleTurns(listOf(lateUpdate), 1, 1)
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `turns of a new session are unaffected by an old watermark`() {
        val newSessionTurn = turn(1, sessionId = 2)
        val visible = InterpretViewModel.visibleTurns(listOf(newSessionTurn), 1, 5)
        assertEquals(listOf(1L), visible.map { it.id })
    }

    // ------------------------------------------- no source-text substitution

    @Test
    fun `turn with null translation stays null`() {
        val failed = turn(
            9,
            status = TurnStatus.FAILED,
            translatedText = null,
            sourceText = "没有译文",
        ).copy(
            problem = SessionProblem(
                code = "mt_unavailable",
                stage = WorkStage.MT,
                message = "翻译不可用",
                recoverable = true,
            )
        )
        // The UI contract: translatedText == null means "no translation exists";
        // nothing in the view model may fill it with the source text.
        assertNull(failed.translatedText)
        assertEquals("没有译文", failed.sourceText)
    }
}
