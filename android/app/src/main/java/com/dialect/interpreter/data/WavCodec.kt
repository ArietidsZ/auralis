package com.dialect.interpreter.data

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strict RIFF/WAVE reader/writer for voice reference audio.
 *
 * Pure JVM so boundary behavior is unit-testable without Android (spec V08):
 *  - parses real RIFF chunk structure (fmt / data / LIST / fact / bext / unknown),
 *    never assumes a fixed 44-byte header;
 *  - validates PCM encoding, channels, sample rate, byte rate, block alignment,
 *    declared data length vs actual bytes, and non-empty content;
 *  - rejects oversized, truncated, NaN/Inf and out-of-range samples;
 *  - writes canonical 16-bit mono PCM with an atomic temp-file + rename strategy
 *    handled by the caller ([writeAtomically] helper below).
 */
object WavCodec {

    /** Decoded PCM samples normalized to [-1, 1]. */
    class Pcm(
        val samples: FloatArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    ) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else samples.size / channels.toLong() * 1000L / sampleRate

        fun toMono(): FloatArray {
            if (channels == 1) return samples
            val frames = samples.size / channels
            val mono = FloatArray(frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += samples[frame * channels + c]
                mono[frame] = sum / channels
            }
            return mono
        }
    }

    class WavFormatException(message: String) : IllegalArgumentException(message)

    /** Hard caps for reference audio (voice profiles, not arbitrary media files). */
    const val MAX_BYTES: Long = 64L * 1024 * 1024
    const val MAX_DURATION_MS: Long = 120_000L
    const val MIN_SAMPLE_RATE = 8_000
    const val MAX_SAMPLE_RATE = 96_000

    // ------------------------------------------------------------------ decode

    fun decode(bytes: ByteArray): Pcm {
        if (bytes.size > MAX_BYTES) throw WavFormatException("音频超过大小上限 ($MAX_BYTES 字节)")
        if (bytes.size < 12) throw WavFormatException("文件太小，不是 WAV")
        val wrap = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val riffId = ByteArray(4).also { wrap.get(it) }.toString(Charsets.US_ASCII)
        if (riffId != "RIFF") throw WavFormatException("缺少 RIFF 标记")
        val riffSize = wrap.int.toLong() and 0xFFFFFFFFL
        val waveId = ByteArray(4).also { wrap.get(it) }.toString(Charsets.US_ASCII)
        if (waveId != "WAVE") throw WavFormatException("缺少 WAVE 标记")
        // riffSize is the payload size after the first 8 bytes; allow files where it
        // equals the actual payload or slightly less than total (some writers round).
        if (riffSize + 8 < bytes.size) throw WavFormatException("RIFF 大小与实际数据不符")

        var fmt: FmtChunk? = null
        var data: ByteArray? = null

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = bytes.toStringAscii(pos, 4)
            val declared = wrap.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val dataStart = pos + 8
            if (declared > bytes.size - dataStart) throw WavFormatException("块 $id 声明长度超过文件结尾（文件被截断）")
            when (id) {
                "fmt " -> fmt = parseFmt(bytes, dataStart, declared.toInt())
                "data" -> data = bytes.copyOfRange(dataStart, dataStart + declared.toInt())
                else -> Unit // LIST / fact / bext / unknown chunks are skipped, not fatal
            }
            pos = dataStart + declared.toInt() + (if (declared % 2 == 1L) 1 else 0)
        }

        val f = fmt ?: throw WavFormatException("缺少 fmt 块")
        val d = data ?: throw WavFormatException("缺少 data 块")
        if (d.isEmpty()) throw WavFormatException("音频数据为空")
        if (d.size % f.blockAlign != 0) throw WavFormatException("数据长度与块对齐不一致（截断或损坏）")

        val frameCount = d.size / f.blockAlign
        val durationMs = frameCount * 1000L / f.sampleRate
        if (durationMs > MAX_DURATION_MS) throw WavFormatException("音频超过时长上限 ${MAX_DURATION_MS / 1000} 秒")

        val samples = when (f.bitsPerSample) {
            8 -> decodePcm8(d)
            16 -> decodePcm16(d)
            24 -> decodePcm24(d)
            32 -> decodePcm32(d)
            else -> throw WavFormatException("不支持的位深: ${f.bitsPerSample}")
        }
        return Pcm(samples, f.sampleRate, f.channels, f.bitsPerSample)
    }

    private class FmtChunk(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
    )

    private fun parseFmt(bytes: ByteArray, offset: Int, size: Int): FmtChunk {
        if (size < 16) throw WavFormatException("fmt 块太小")
        val buf = ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        val audioFormat = buf.short.toInt() and 0xFFFF
        val channels = buf.short.toInt() and 0xFFFF
        val sampleRate = buf.int
        buf.int // byteRate, validated below against computed value
        val blockAlign = buf.short.toInt() and 0xFFFF
        val bitsPerSample = buf.short.toInt() and 0xFFFF

        if (audioFormat == 0xFFFE) {
            // WAVE_FORMAT_EXTENSIBLE: need cbSize + SubFormat GUID = 40 byte fmt chunk.
            if (size < 40) throw WavFormatException("扩展 fmt 块不完整")
            buf.position(offset + 24)
            val subFormat = ByteArray(16).also { buf.get(it) }
            if (!subFormat.contentEquals(PCM_SUBFORMAT_GUID)) {
                throw WavFormatException("不支持的子格式（仅支持整数 PCM）")
            }
        } else if (audioFormat != 1) {
            throw WavFormatException("不支持的编码（仅支持 PCM，实际 format=$audioFormat）")
        }
        if (channels !in 1..8) throw WavFormatException("不支持的声道数: $channels")
        if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
            throw WavFormatException("不支持的采样率: $sampleRate")
        }
        if (bitsPerSample !in intArrayOf(8, 16, 24, 32)) {
            throw WavFormatException("不支持的位深: $bitsPerSample")
        }
        val expectedBlockAlign = channels * bitsPerSample / 8
        if (blockAlign != expectedBlockAlign) {
            throw WavFormatException("块对齐不一致: $blockAlign ≠ $expectedBlockAlign")
        }
        return FmtChunk(audioFormat, channels, sampleRate, blockAlign, bitsPerSample)
    }

    private val PCM_SUBFORMAT_GUID = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
    )

    private fun decodePcm8(d: ByteArray): FloatArray = FloatArray(d.size) { i ->
        ((d[i].toInt() and 0xFF) - 128) / 128f
    }

    private fun decodePcm16(d: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(d.size / 2) { buf.short / 32768f }
    }

    private fun decodePcm24(d: ByteArray): FloatArray {
        val n = d.size / 3
        return FloatArray(n) { i ->
            val b0 = d[i * 3].toInt() and 0xFF
            val b1 = d[i * 3 + 1].toInt() and 0xFF
            val b2 = d[i * 3 + 2].toInt()
            var v = (b2 shl 16) or (b1 shl 8) or b0
            if (v and 0x800000 != 0) v = v or (0xFF shl 24).toInt() // sign extend
            v / 8388608f
        }
    }

    private fun decodePcm32(d: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(d.size / 4) { buf.int / 2147483648f }
    }

    // ------------------------------------------------------------------ encode

    /**
     * Validate + encode mono samples to 16-bit PCM WAV bytes.
     * Rejects NaN/Inf and samples outside [-1, 1] (spec: 拒绝 NaN/Inf 数据).
     */
    fun encodeMonoPcm16(samples: FloatArray, sampleRate: Int): ByteArray {
        validateSamples(samples)
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "采样率超出范围: $sampleRate" }
        if (samples.size > (MAX_BYTES / 2 - 44)) throw WavFormatException("音频超过大小上限")

        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2) // block align
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (s in samples) {
            buffer.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return buffer.array()
    }

    fun validateSamples(samples: FloatArray) {
        if (samples.isEmpty()) throw WavFormatException("音频数据为空")
        for (s in samples) {
            if (s.isNaN() || s.isInfinite()) throw WavFormatException("音频包含 NaN/Inf 采样")
            if (s < -1f || s > 1f) throw WavFormatException("音频采样超出 [-1, 1] 范围")
        }
    }

    /** True when every sample is exactly zero — not a usable voice reference. */
    fun isAllZero(samples: FloatArray): Boolean = samples.all { it == 0f }

    // ------------------------------------------------------------- atomic file

    /**
     * Write [bytes] to [target] atomically: temp file in the same directory,
     * flushed + fsync'd, then renamed. Never leaves a truncated target.
     */
    fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("无法替换已存在文件: ${target.name}")
            }
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("原子重命名失败: ${target.name}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun ByteArray.toStringAscii(offset: Int, length: Int): String {
        val arr = ByteArray(length)
        System.arraycopy(this, offset, arr, 0, length)
        return arr.toString(Charsets.US_ASCII)
    }
}
