package com.dialect.interpreter.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Real muted AudioTrack tests; no microphone/model/pretend playback backend. */
@RunWith(AndroidJUnit4::class)
class AudioPlayerStreamTest {
    private fun pcm(frames: Int) = FloatArray(frames) { (sin(it * 0.05) * 0.03).toFloat() }

    @Test fun stopDoesNotWaitForALongNativeWrite() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val player = AudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext)
            player.setVolume(0f)
            try {
                supervisorScope {
                    val job = async(Dispatchers.Default) { player.play(pcm(24000 * 30), 24000) }
                    withTimeout(4000) { player.isPlaying.first { value -> value } }
                    delay(40)
                    val elapsed = withContext(Dispatchers.Main) {
                        val start = SystemClock.elapsedRealtime()
                        player.stop()
                        SystemClock.elapsedRealtime() - start
                    }
                    assertTrue("Main stop waited "+elapsed+"ms", elapsed < 250)
                    withTimeout(2000) { job.join() }
                    assertTrue("stopped play returned success", job.isCancelled)
                }
                assertFalse(player.isPlaying.value)
            } finally { player.release() }
        }
    }

    @Test fun streamDrainsAndProducerFailureRecovers() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val player = AudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext)
            player.setVolume(0f)
            try {
                val start = SystemClock.elapsedRealtime()
                val result = withTimeout(5000) {
                    player.playStream(24000) { emit -> repeat(8) { emit(pcm(2400)) } }
                }
                assertEquals(19200L, result.renderedFrames)
                assertTrue("returned before render tail", SystemClock.elapsedRealtime() - start >= 600)
                assertFalse(player.isPlaying.value)
                val problem = IllegalStateException("controlled producer failure")
                try {
                    player.playStream(24000) { emit -> emit(pcm(2400)); throw problem }
                    fail("producer failure was swallowed")
                } catch (error: IllegalStateException) { assertSame(problem, error) }
                withTimeout(4000) { player.play(pcm(4800), 24000) }
                assertFalse(player.isPlaying.value)
            } finally { player.release() }
        }
    }

    @Test fun stopCancelsProducerAndQueuedOldPlay() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val player = AudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext)
            player.setVolume(0f)
            try {
                supervisorScope {
                    val entered = CompletableDeferred<Unit>()
                    val first = async(Dispatchers.Default) {
                        player.playStream(24000) { entered.complete(Unit); awaitCancellation() }
                    }
                    withTimeout(4000) { entered.await() }
                    val second = async(start = CoroutineStart.UNDISPATCHED) { player.play(pcm(2400), 24000) }
                    player.stop()
                    for (job in listOf(first, second)) {
                        withTimeout(2000) { job.join() }
                        assertTrue("old play survived stop", job.isCancelled)
                    }
                }
                withTimeout(4000) { player.play(pcm(2400), 24000) }
            } finally { player.release() }
        }
    }

    @Test fun focusLossIsAFailureRatherThanCompletedAudio() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val player = AudioPlayer(context)
            player.setVolume(0f)
            val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { }.build()
            try {
                supervisorScope {
                    val job = async(Dispatchers.Default) { player.play(pcm(24000 * 10), 24000) }
                    withTimeout(4000) { player.isPlaying.first { value -> value } }
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other))
                    try { withTimeout(3000) { job.await() }; fail("focus loss was reported complete") }
                    catch (_: AudioPlaybackFailure) { }
                }
            } finally {
                manager.abandonAudioFocusRequest(other)
                player.release()
            }
        }
    }
}
