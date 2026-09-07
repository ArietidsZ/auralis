package com.dialect.interpreter.audio

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.MainActivity
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic v6: decompose the wedge trigger (job.cancel vs stop). */
@RunWith(AndroidJUnit4::class)
class AudioTrackHeadDiagnostic {
    private fun pcm(frames: Int) = FloatArray(frames) { (sin(it * 0.05) * 0.03).toFloat() }
    private fun log(msg: String) = android.util.Log.i("AudioTrackDiag", msg)

    private suspend fun tryPlay(player: AudioPlayer, frames: Int, label: String): Boolean = try {
        withTimeout(4000) { player.play(pcm(frames), 24000) }
        log("$label ok")
        true
    } catch (e: Exception) {
        log("$label FAILED: ${e.message}")
        false
    }

    @Test fun decompose() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val player = AudioPlayer(context)
            player.setVolume(0f)
            val a = tryPlay(player, 4800, "A_baseline")

            // B: never-writing playStream cancelled via job.cancelAndJoin (no stop())
            supervisorScope {
                val job = async(Dispatchers.Default) {
                    player.playStream(24000) { awaitCancellation() }
                }
                delay(300)
                job.cancelAndJoin()
            }
            val b = tryPlay(player, 2400, "B_after_job_cancel")

            // C: never-writing playStream + player.stop()
            supervisorScope {
                val job = async(Dispatchers.Default) {
                    player.playStream(24000) { awaitCancellation() }
                }
                delay(300)
                player.stop()
                job.join()
            }
            val c = tryPlay(player, 2400, "C_after_stop")

            // D: is the wedge permanent for the player instance?
            val d = tryPlay(player, 2400, "D_again")

            // E: a brand-new AudioPlayer instance afterwards
            val other = AudioPlayer(context)
            other.setVolume(0f)
            val e = tryPlay(other, 2400, "E_new_player")
            other.release()

            log("SUMMARY a=$a b=$b c=$c d=$d e=$e")
            player.release()
        }
    }
}
