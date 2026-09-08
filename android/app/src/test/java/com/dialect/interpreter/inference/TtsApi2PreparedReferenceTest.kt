package com.dialect.interpreter.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract tests for [TtsApi2Runtime.PreparedReference] immutability —
 * the conditioning package produced by [TtsApi2Runtime.prepareReference].
 *
 * The class is internal-nested with an internal constructor, but the unit-test
 * compilation is a Kotlin friend module of the app module, so the constructor
 * and [TtsApi2Runtime.VocoderWarmState] are constructible here without a
 * device. No model files and no ONNX runtime are involved: a prepared
 * reference is pure data (exactly how the engine treats it after release()),
 * so the defensive-copy contract is fully verifiable on the JVM.
 *
 * Coverage boundary: the engine-side release of the ~190 MB reference_encoder
 * session on failed/cancelled preparations is a resource-lifecycle property
 * (OnnxModelManager.release -> sessions.remove(key)?.close()) with no public
 * observable, so it is covered by the try/finally restructure in
 * prepareReference and static review, not by an automated test here.
 */
class TtsApi2PreparedReferenceTest {

    private fun warmState(): TtsApi2Runtime.VocoderWarmState = TtsApi2Runtime.VocoderWarmState(
        convState = floatArrayOf(1f, 2f, 3f),
        pastKeys = floatArrayOf(4f),
        pastValues = floatArrayOf(5f, 6f),
        position = 7L)

    private fun prepared(
        embedding: FloatArray = floatArrayOf(0.25f, -0.5f, 1f),
        referenceText: String? = "reference transcript",
        referenceTokenIds: IntArray? = intArrayOf(11, 12, 13),
        referenceCodes: IntArray? = intArrayOf(21, 22, 23, 24),
        referenceFrames: Int = 2,
        warm: TtsApi2Runtime.VocoderWarmState? = warmState(),
        engineToken: Any = Any(),
        generation: Int = 3,
    ): TtsApi2Runtime.PreparedReference = TtsApi2Runtime.PreparedReference(
        embedding, referenceText, referenceTokenIds, referenceCodes, referenceFrames,
        "identity-digest", warm, engineToken, generation)

    @Test
    fun `embedding is copied at construction and on every read`() {
        val source = floatArrayOf(0.25f, -0.5f, 1f)
        val p = prepared(embedding = source)

        // Mutating the constructor argument after construction must not leak in.
        source[0] = 99f
        source[1] = 99f
        assertTrue(p.embedding.contentEquals(floatArrayOf(0.25f, -0.5f, 1f)))

        // Mutating a handed-out array must not leak back into the instance.
        val handedOut = p.embedding
        handedOut[2] = -99f
        assertTrue(p.embedding.contentEquals(floatArrayOf(0.25f, -0.5f, 1f)))

        // Every read hands out an independent copy, never the stored snapshot.
        assertNotSame(handedOut, p.embedding)
    }

    @Test
    fun `reference token ids are copied at construction and on every read`() {
        val source = intArrayOf(11, 12, 13)
        val p = prepared(referenceTokenIds = source)

        source[0] = -1
        assertTrue(p.referenceTokenIds!!.contentEquals(intArrayOf(11, 12, 13)))

        val handedOut = p.referenceTokenIds!!
        handedOut[1] = -1
        assertTrue(p.referenceTokenIds!!.contentEquals(intArrayOf(11, 12, 13)))
        assertNotSame(handedOut, p.referenceTokenIds)
    }

    @Test
    fun `reference codes are copied at construction and on every read`() {
        val source = intArrayOf(21, 22, 23, 24)
        val p = prepared(referenceCodes = source)

        source[0] = -1
        assertTrue(p.referenceCodes!!.contentEquals(intArrayOf(21, 22, 23, 24)))

        val handedOut = p.referenceCodes!!
        handedOut[3] = -1
        assertTrue(p.referenceCodes!!.contentEquals(intArrayOf(21, 22, 23, 24)))
        assertNotSame(handedOut, p.referenceCodes)
    }

    @Test
    fun `scalar and binding fields pass through unchanged`() {
        val engineToken = Any()
        val warm = warmState()
        val p = prepared(
            referenceText = "reference transcript",
            referenceFrames = 2,
            warm = warm,
            engineToken = engineToken,
            generation = 3,
        )
        assertEquals("reference transcript", p.referenceText)
        assertEquals(2, p.referenceFrames)
        assertEquals("identity-digest", p.identity)
        assertEquals(3, p.generation)
        // The staleness binding keeps the exact token object the engine handed over.
        assertSame(engineToken, p.engineToken)
        // The warm snapshot is the exact instance the engine produced; a lost
        // or copied replacement here would fail every ICL turn.
        assertSame(warm, p.vocoderWarmState)
    }

    @Test
    fun `isIcl tracks the constructed codes and cannot be toggled through copies`() {
        val p = prepared()
        assertTrue(p.isIcl)
        assertEquals(p.referenceCodes != null, p.isIcl)

        // Overwriting a handed-out copy's contents cannot turn ICL off or on.
        p.referenceCodes!![0] = -1
        assertTrue(p.isIcl)
        assertTrue(p.referenceCodes!!.contentEquals(intArrayOf(21, 22, 23, 24)))
    }

    @Test
    fun `xvector-only prepared reference has no ICL conditioning`() {
        val p = prepared(
            referenceText = null,
            referenceTokenIds = null,
            referenceCodes = null,
            referenceFrames = 0,
            warm = null,
        )
        assertFalse(p.isIcl)
        assertNull(p.referenceText)
        assertNull(p.referenceTokenIds)
        assertNull(p.referenceCodes)
        assertNull(p.vocoderWarmState)
        // The embedding remains fully usable for the legacy xvector port.
        assertTrue(p.embedding.contentEquals(floatArrayOf(0.25f, -0.5f, 1f)))
    }

    @Test
    fun `vocoder warm state copies are independent of the source snapshot`() {
        // The engine hands the warm snapshot to every turn through the same
        // array-copy mechanism (VocoderTurnState.fromSnapshot copies each
        // array); VocoderWarmState.copy() exercises that copying directly.
        val snapshot = warmState()
        val turn = snapshot.copy()
        assertNotSame(snapshot.convState, turn.convState)
        assertNotSame(snapshot.pastKeys, turn.pastKeys)
        assertNotSame(snapshot.pastValues, turn.pastValues)
        assertEquals(snapshot.position, turn.position)

        turn.convState[0] = -99f
        turn.pastKeys[0] = -99f
        turn.pastValues[0] = -99f
        assertTrue(snapshot.convState.contentEquals(floatArrayOf(1f, 2f, 3f)))
        assertTrue(snapshot.pastKeys.contentEquals(floatArrayOf(4f)))
        assertTrue(snapshot.pastValues.contentEquals(floatArrayOf(5f, 6f)))
    }
}
