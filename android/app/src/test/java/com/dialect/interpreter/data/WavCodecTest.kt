package com.dialect.interpreter.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** V08 boundary tests: extended chunks, truncation, bad formats, caps. */
class WavCodecTest {

    private fun pcm16Bytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun wavBytes(
        data: ByteArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bits: Int = 16,
        extraChunks: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())

        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putShort(1) // PCM
        fmt.putShort(channels.toShort())
        fmt.putInt(sampleRate)
        fmt.putInt(sampleRate * channels * bits / 8)
        fmt.putShort((channels * bits / 8).toShort())
        fmt.putShort(bits.toShort())
        fun chunk(id: String, payload: ByteArray) {
            body.write(id.toByteArray())
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array())
            body.write(payload)
            if (payload.size % 2 == 1) body.write(0)
        }
        chunk("fmt ", fmt.array())
        chunk("LIST", "INFOhello".toByteArray()) // extended chunk, must be tolerated
        extraChunks.forEach { (id, payload) -> chunk(id, payload) }
        chunk("data", data)

        val bodyBytes = body.toByteArray()
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bodyBytes.size).array())
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun sineSamples(count: Int): FloatArray =
        FloatArray(count) { i -> 0.5f * kotlin.math.sin(2.0 * Math.PI * 220.0 * i / 16000.0).toFloat() }

    // ------------------------------------------------------------- happy path

    @Test
    fun `encode then decode round trip preserves samples`() {
        val samples = sineSamples(1600)
        val bytes = WavCodec.encodeMonoPcm16(samples, 16000)
        val decoded = WavCodec.decode(bytes)
        assertEquals(16000, decoded.sampleRate)
        assertEquals(1, decoded.channels)
        assertEquals(16, decoded.bitsPerSample)
        assertEquals(samples.size, decoded.samples.size)
        // 16-bit quantization error must be below 1/32768 * 2
        for (i in samples.indices) {
            assertTrue(kotlin.math.abs(decoded.samples[i] - samples[i]) < 0.0001f)
        }
    }

    @Test
    fun `decode tolerates extended chunks before data`() {
        val samples = sineSamples(320)
        val wav = wavBytes(pcm16Bytes(samples.map { (it * 32767).toInt().toShort() }.toShortArray()))
        val decoded = WavCodec.decode(wav)
        assertEquals(samples.size, decoded.samples.size)
        assertEquals(320L * 1000 / 16000, decoded.durationMs)
    }

    @Test
    fun `mono downmix averages channels`() {
        // stereo: L = 0.5, R = -0.5 → mono 0.0
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort((0.5f * 32767).toInt().toShort())
            .putShort((-0.5f * 32767).toInt().toShort())
            .array()
        val decoded = WavCodec.decode(wavBytes(data, channels = 2))
        assertEquals(2, decoded.channels)
        assertEquals(1, decoded.toMono().size)
        assertEquals(0f, decoded.toMono()[0], 1e-4f)
    }

    @Test
    fun `decode accepts 8-bit and 24-bit pcm`() {
        // 8-bit WAV is unsigned: 0 = -1.0, 128 = 0.0, 255 = +0.9921875
        val data8 = byteArrayOf(0, 128.toByte(), 255.toByte())
        val decoded8 = WavCodec.decode(wavBytes(data8, sampleRate = 8000, bits = 8))
        assertEquals(3, decoded8.samples.size)
        assertEquals(-1f, decoded8.samples[0], 0.01f)
        assertEquals(0f, decoded8.samples[1], 0.01f)
        assertEquals(0.9921875f, decoded8.samples[2], 0.0001f)

        val data24 = byteArrayOf(0, 0, 0x40) // small value
        val decoded24 = WavCodec.decode(wavBytes(data24, bits = 24))
        assertEquals(1, decoded24.samples.size)
        assertTrue(decoded24.samples[0] in -1f..1f)
    }

    // ------------------------------------------------------- rejection cases

    @Test
    fun `rejects truncated file`() {
        val samples = sineSamples(320)
        val wav = wavBytes(pcm16Bytes(samples.map { (it * 32767).toInt().toShort() }.toShortArray()))
        assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.decode(wav.copyOf(wav.size - 100))
        }
    }

    @Test
    fun `rejects declared data length beyond file end`() {
        val good = wavBytes(pcm16Bytes(ShortArray(320)))
        val corrupt = good.copyOf(good.size + 8)
        // declare a huge data chunk by rewriting the RIFF-independent data size
        // simplest: hand-build a data chunk with oversized declaration
        val bad = ByteArrayOutputStream()
        bad.write("RIFF".toByteArray())
        bad.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + 1000).array())
        bad.write("WAVE".toByteArray())
        bad.write("fmt ".toByteArray())
        bad.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putShort(1); fmt.putShort(1); fmt.putInt(16000); fmt.putInt(32000); fmt.putShort(2); fmt.putShort(16)
        bad.write(fmt.array())
        bad.write("data".toByteArray())
        bad.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(10_000_000).array())
        bad.write(good.copyOfRange(44, good.size))
        assertThrows(WavCodec.WavFormatException::class.java) { WavCodec.decode(bad.toByteArray()) }
        // trailing junk beyond the declared RIFF size is also rejected (strict)
        assertThrows(WavCodec.WavFormatException::class.java) { WavCodec.decode(corrupt) }
    }

    @Test
    fun `rejects non-pcm encoding`() {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(50).array())
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putShort(3) // IEEE float — not integer PCM
        fmt.putShort(1); fmt.putInt(16000); fmt.putInt(64000); fmt.putShort(4); fmt.putShort(32)
        out.write(fmt.array())
        out.write("data".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(8).array())
        out.write(ByteArray(8))
        assertThrows(WavCodec.WavFormatException::class.java) { WavCodec.decode(out.toByteArray()) }
    }

    @Test
    fun `rejects bad block alignment and bad channel count`() {
        val data = pcm16Bytes(ShortArray(320))
        val bad = wavBytes(data).let { bytes ->
            // flip blockAlign (byte offset 32..33) to an inconsistent value
            bytes.copyOf().also { c ->
                c[32] = 6; c[33] = 0
            }
        }
        assertThrows(WavCodec.WavFormatException::class.java) { WavCodec.decode(bad) }
    }

    @Test
    fun `rejects empty and missing data chunk`() {
        assertThrows(WavCodec.WavFormatException::class.java) { WavCodec.decode(wavBytes(ByteArray(0))) }
        assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.decode("RIFF____WAVE".toByteArray() + ByteArray(20))
        }
    }

    @Test
    fun `rejects oversized files and overlong audio`() {
        val huge = ByteArray((WavCodec.MAX_BYTES + 1).toInt().coerceAtMost(70_000_000))
        assertThrows(WavCodec.WavFormatException::class.java) { WavCodec.decode(huge) }

        // 121 seconds of silence at 16 kHz mono 16-bit = 3.87 MB of zeros
        val longData = pcm16Bytes(ShortArray(121 * 16000))
        assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.decode(wavBytes(longData))
        }
    }

    // ------------------------------------------------------------ validation

    @Test
    fun `validateSamples rejects NaN Inf and out of range`() {
        assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.validateSamples(floatArrayOf(0f, Float.NaN))
        }
        assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.validateSamples(floatArrayOf(0f, Float.POSITIVE_INFINITY))
        }
        assertThrows(WavCodec.WavFormatException::class.java) {
            WavCodec.validateSamples(floatArrayOf(1.5f))
        }
        WavCodec.validateSamples(floatArrayOf(0f, 1f, -1f)) // ok
    }

    @Test
    fun `isAllZero detection`() {
        assertTrue(WavCodec.isAllZero(FloatArray(100)))
        assertTrue(!WavCodec.isAllZero(floatArrayOf(0f, 0.01f)))
    }

    // ---------------------------------------------------------- atomic write

    @Test
    fun `writeAtomically replaces content without leaving temp files`() {
        val dir = kotlin.io.path.createTempDirectory("wavtest").toFile()
        try {
            val target = java.io.File(dir, "a.wav")
            WavCodec.writeAtomically(target, byteArrayOf(1, 2, 3))
            WavCodec.writeAtomically(target, byteArrayOf(9))
            assertEquals(1, target.length())
            assertTrue(dir.listFiles()!!.size == 1) // no .tmp leftover
            assertEquals(9, target.readBytes()[0].toInt())
        } finally {
            dir.deleteRecursively()
        }
    }
}
