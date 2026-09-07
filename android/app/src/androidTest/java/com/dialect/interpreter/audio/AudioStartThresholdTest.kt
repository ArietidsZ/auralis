package com.dialect.interpreter.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dialect.interpreter.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Same initialized track and PCM: changing only startup threshold starts it. */
@RunWith(AndroidJUnit4::class)
class AudioStartThresholdTest {
    @Test fun shortPcmStartsWhenThresholdIsLowered() = runBlocking<Unit> {
        check(Build.VERSION.SDK_INT >= 31) { "This API31+ comparison requires a matching test device" }
        ActivityScenario.launch(MainActivity::class.java).use {
            val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(minimum * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            try {
                track.setVolume(0f)
                val originalThreshold = track.startThresholdInFrames
                val frames = minOf(2400, originalThreshold / 2)
                assertTrue(frames > 0 && frames < originalThreshold)
                val pcm = FloatArray(frames) { (sin(it * 0.05) * 0.03).toFloat() }
                assertEquals(frames, track.write(pcm, 0, frames, AudioTrack.WRITE_NON_BLOCKING))
                track.play()
                delay(300)
                val before = track.playbackHeadPosition
                assertEquals("Below-threshold PCM should still be buffered", 0, before)
                assertEquals(1, track.setStartThresholdInFrames(1))
                withTimeout(2000) { while (track.playbackHeadPosition < frames) delay(10) }
                Log.i("AuralisThreshold", "capacity=${track.bufferCapacityInFrames} " +
                    "threshold=$originalThreshold frames=$frames before=$before after=${track.playbackHeadPosition}")
            } finally { track.release() }
        }
    }
}
