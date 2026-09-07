package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.SherpaJni
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceResamplerTest {
    @Test
    fun nativeReferenceResampler_filtersAliasesAndChecksBoundaries() {
        SherpaJni.load()
        val low = FloatArray(48000) { sin(2 * PI * 1000 * it / 48000).toFloat() }
        val high = FloatArray(48000) { sin(2 * PI * 14000 * it / 48000).toFloat() }
        fun rms(pcm: FloatArray): Double {
            val middle = pcm.copyOfRange(256, pcm.size - 256)
            return sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
        }
        val passed = SherpaJni.resample(low, 48000, 16000)
        val rejected = SherpaJni.resample(high, 48000, 16000)
        assertEquals(16000, passed.size)
        assertTrue("passband was attenuated", rms(passed) in 0.70..0.72)
        assertTrue("high-frequency alias leaked", rms(rejected) < 0.003)
        assertEquals(55, SherpaJni.resample(FloatArray(101), 44100, 24000).size)
        assertEquals(2400, SherpaJni.resample(FloatArray(1600), 16000, 24000).size)
        assertSame(low, SherpaJni.resample(low, 48000, 48000))
        assertThrows(IllegalArgumentException::class.java) {
            SherpaJni.resample(FloatArray(0), 16000, 16000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SherpaJni.resample(FloatArray(64), 191999, 192000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SherpaJni.resample(floatArrayOf(Float.NaN), 16000, 16000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SherpaJni.resample(floatArrayOf(Float.POSITIVE_INFINITY), 16000, 24000)
        }
        for (rate in listOf(0, -1, 7999, 192001)) {
            assertThrows(IllegalArgumentException::class.java) { SherpaJni.resample(low, rate, 24000) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SherpaJni.resample(FloatArray(8000 * 30 + 1), 8000, 24000)
        }
        AsrDeviceFixtures.writeReport("reference_resampler.json",
            """{"passband_rms":${rms(passed)},"stopband_rms":${rms(rejected)},"ceil_length":true,"boundaries_checked":true}""")
    }
}
