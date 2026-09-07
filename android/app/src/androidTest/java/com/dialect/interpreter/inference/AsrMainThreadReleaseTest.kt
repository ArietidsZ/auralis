package com.dialect.interpreter.inference

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real decode, two concurrent suspending releases, and a queued reload. */
@RunWith(AndroidJUnit4::class)
class AsrMainThreadReleaseTest {
    @Test
    fun suspendingRelease_keepsMainResponsive_andJoinsBeforeReload() = runBlocking {
        val fx = AsrDeviceFixtures
        fx.requireAsrBundle()
        val engine = AsrEngine(OnnxModelManager(fx.targetCtx))
        val (long, sr) = fx.wavAsset("noise1-en.wav")
        val (de, deRate) = fx.wavAsset("de.wav")
        repeat(3) {
            engine.load()
            val decode = async(Dispatchers.Default) {
                runCatching { engine.transcribe(long, null, sr) }
            }
            delay(200)
            assertFalse("test needs an active native decode", decode.isCompleted)
            val repeatedLoad = async(Dispatchers.IO) { engine.load() }
            delay(50)
            val queuedDecode = async(Dispatchers.Default) {
                runCatching { engine.transcribe(de, null, deRate) }
            }
            delay(50)
            assertFalse(repeatedLoad.isCompleted)
            assertFalse(queuedDecode.isCompleted)
            val releaseStarted = CompletableDeferred<Unit>()
            val closing = async(Dispatchers.Main) {
                releaseStarted.complete(Unit)
                engine.release()
            }
            releaseStarted.await()
            val secondStarted = CompletableDeferred<Unit>()
            val closingAgain = async(Dispatchers.Main) {
                secondStarted.complete(Unit)
                engine.release()
            }
            secondStarted.await()
            val heartbeat = CompletableDeferred<Unit>()
            Handler(Looper.getMainLooper()).post { heartbeat.complete(Unit) }
            withTimeout(300) { heartbeat.await() }
            assertFalse("release must await native decode/disposal", closing.isCompleted)
            assertFalse("idempotent release must also await disposal", closingAgain.isCompleted)
            val rejected = runCatching { engine.transcribe(de, null, deRate) }
            assertTrue(rejected.exceptionOrNull() is IllegalStateException)
            val reloaded = async(Dispatchers.IO) { engine.load() }
            withTimeout(60_000) {
                closing.await()
                closingAgain.await()
                repeatedLoad.await()
                assertTrue("queued old decode must not be reopened by idempotent load",
                    queuedDecode.await().exceptionOrNull() is IllegalStateException)
                reloaded.await()
                val outcome = decode.await()
                assertTrue(outcome.isSuccess || outcome.exceptionOrNull() is IllegalStateException)
            }
            val result = engine.transcribe(de, null, deRate)
            assertTrue("reload must own a live recognizer", fx.cer(fx.reference("de"), result.text) <= 0.1)
            engine.release()
        }
        fx.writeReport("asr_structured_release.json",
            """{"cycles":3,"main_heartbeat_ms_budget":300,"two_releases_joined":true,"queued_reload_decoded":true}""")
    }
}
