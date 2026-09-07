package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lane 3 — lifecycle guards: no use-after-free on cancel/release, no emit
 * after stop, KV-budget segmentation contract. These exercise the exact
 * production failure mode: PipelineOrchestrator.stop() cancels the worker
 * job mid-decode and then calls release() on another thread.
 */
@RunWith(AndroidJUnit4::class)
class AsrLifecycleGuardTest {

    private lateinit var engine: AsrEngine

    @Before
    fun setUp() {
        AsrDeviceFixtures.requireAsrBundle()
        engine = AsrEngine(OnnxModelManager(AsrDeviceFixtures.targetCtx))
    }

    @Test
    fun transcribeBeforeLoad_throwsIllegalState() = runBlocking {
        try {
            engine.transcribe(FloatArray(1600), language = null, sampleRate = 16000)
            fail("transcribe before load() must throw, not emit")
        } catch (expected: IllegalStateException) {
            // engine-level guard fired before any native call
        }
    }

    @Test
    fun releaseIsIdempotent_reloadWorks_noUaf() = runBlocking {
        val (de, sr) = AsrDeviceFixtures.wavAsset("de.wav")
        engine.load()
        val first = engine.transcribe(de, language = null, sampleRate = sr)
        assertTrue(first.text.isNotBlank())

        engine.release()
        engine.release() // idempotent, no native double-free
        try {
            engine.transcribe(de, language = null, sampleRate = sr)
            fail("transcribe after release must throw — no emit after stop")
        } catch (expected: IllegalStateException) {
        }

        // Reload from fully released state must not crash (fresh native handles).
        engine.load()
        val second = engine.transcribe(de, language = null, sampleRate = sr)
        assertEquals(first.text, second.text)
        engine.release()
    }

    @Test
    fun releaseDuringInFlightDecode_joinsNoUafAndNoEmitAfterStop() = runBlocking {
        val (de, sr) = AsrDeviceFixtures.wavAsset("de.wav")
        engine.load()

        val decode = async(Dispatchers.Default) { runCatching { engine.transcribe(de, language = null, sampleRate = sr) } }
        withContext(Dispatchers.IO) { delay(200) } // decode is now in flight

        // Production stop() path: cancel the caller, then release from outside.
        engine.release()
        val outcome = decode.await()
        // Either the decode joined and completed, or it observed release —
        // a segfault/SIGSEGV here fails the whole process instead.
        assertTrue(
            "in-flight transcribe must complete or throw ISE, got ${outcome.exceptionOrNull()}",
            outcome.isSuccess || outcome.exceptionOrNull() is IllegalStateException,
        )
        try {
            engine.transcribe(de, language = null, sampleRate = sr)
            fail("transcribe must not emit after release returned")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun concurrentTranscribes_serializeOnTheNativeHandle() = runBlocking {
        val (de, sr) = AsrDeviceFixtures.wavAsset("de.wav")
        engine.load()
        val results = ArrayList<AsrEngine.TranscriptionResult>(3)
        val jobs = List(3) {
            launch(Dispatchers.Default) {
                val r = engine.transcribe(de, language = null, sampleRate = sr) // no suspend call inside synchronized
                synchronized(results) { results.add(r) }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(3, results.size)
        assertTrue(results.all { it.text == results[0].text })
        engine.release()
    }

    @Test
    fun boundSegments_keepsEveryPieceInsideKvBudget() {
        // 88.19 s of real noise1-en: every piece must be <= 20 s and cover the
        // whole clip (no sample loss) — the KV 512 input-budget guard.
        val (noise, sr) = AsrDeviceFixtures.wavAsset("noise1-en.wav")
        val pieces = engine.boundSegments(noise, sr)
        val maxSamples = (20.0 * sr).toInt()
        assertTrue("expected split for 88s clip, got ${pieces.size} piece(s)", pieces.size >= 4)
        assertTrue(
            "piece exceeds KV budget: ${pieces.maxOf { it.size }} > $maxSamples",
            pieces.all { it.size <= maxSamples },
        )
        assertEquals(
            "segmentation must not lose samples",
            noise.size.toLong(),
            pieces.sumOf { it.size.toLong() },
        )
        // Short clips stay whole (production trigger is 36 s).
        val short = noise.copyOfRange(0, 10 * sr)
        val whole = engine.boundSegments(short, sr)
        assertEquals(1, whole.size)
        assertEquals(short.size, whole[0].size)
        // Empty input is rejected by segmentation, not fed to native decode.
        assertTrue(engine.boundSegments(FloatArray(0), sr).isEmpty())
    }
}
