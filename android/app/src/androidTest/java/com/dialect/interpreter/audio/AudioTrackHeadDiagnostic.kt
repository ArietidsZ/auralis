package com.dialect.interpreter.audio

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.MainActivity
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic v7: isolate the wedge trigger with the focus-at-first-write player.
 * P1 MainActivity + plain play (baseline)
 * P2 no MainActivity: never-writing playStream + stop, then play
 * P3 MainActivity + never-writing playStream + stop, then play
 * P4 MainActivity + UNDISPATCHED play + stop, then play
 * P5 fresh AudioPlayer + plain play (recovery) */
@RunWith(AndroidJUnit4::class)
class AudioTrackHeadDiagnostic {
    private fun pcm(frames: Int) = FloatArray(frames) { (sin(it * 0.05) * 0.03).toFloat() }
    private fun log(msg: String) = android.util.Log.i("AudioTrackDiag", msg)

    private suspend fun tryPlay(player: AudioPlayer, frames: Int, label: String): Boolean = try {
        withTimeout(4000) { player.play(pcm(frames), 24000) }
        log("$label ok")
        true
    } catch (e: Exception) {
        log("$label FAILED: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private suspend fun neverWriteCycle(player: AudioPlayer) {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) {
                player.playStream(24000) { entered.complete(Unit); awaitCancellation() }
            }
            withTimeout(4000) { entered.await() }
            player.stop()
            first.join()
        }
    }

    private suspend fun undispatchedCycle(player: AudioPlayer) {
        supervisorScope {
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                player.play(pcm(2400), 24000)
            }
            player.stop()
            second.join()
        }
    }

    @Test fun decompose() {
        runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // P1: MainActivity + plain play, fresh player
        ActivityScenario.launch(MainActivity::class.java).use {
            val p1 = AudioPlayer(context); p1.setVolume(0f)
            val a = tryPlay(p1, 2400, "P1_mainActivity_plain_play"); p1.release()
            val p2 = AudioPlayer(context); p2.setVolume(0f)
            neverWriteCycle(p2)
            val b = tryPlay(p2, 2400, "P2_noActivity_neverWrite_stop_play_isMainActivityLaunched")
            p2.release()
            val c = tryPlay(AudioPlayer(context).also { it.setVolume(0f) }, 2400, "P3_fresh_after_P2")
            AudioPlayer(context).also { it.setVolume(0f); it.release() }
            // P4: fresh player, undispatched play + stop, then play (same player)
            val p4 = AudioPlayer(context); p4.setVolume(0f)
            undispatchedCycle(p4)
            val d = tryPlay(p4, 2400, "P4_undispatched_stop_play")
            // P5: fresh player after P4
            val e = tryPlay(AudioPlayer(context).also { it.setVolume(0f) }, 2400, "P5_fresh_after_P4")
            log("SUMMARY p1=$a p2=$b p3=$c p4=$d p5=$e")
        }
        }
    }
}
