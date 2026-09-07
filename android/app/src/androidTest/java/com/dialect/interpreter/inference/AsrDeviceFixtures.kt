package com.dialect.interpreter.inference

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.dialect.interpreter.data.WavCodec
import java.io.File
import java.io.IOException

/**
 * Device-side fixtures for the Android ASR validation lane.
 *
 * Real artifacts only: the ASR bundle must be pushed to
 * `filesDir/dialect_models/asr/` and the TTS speaker-encoder graph to
 * `filesDir/dialect_models/tts/` before these tests run (see
 * reports/continuation-android-runtime.md). No fixture fabricates inference.
 */
object AsrDeviceFixtures {

    val instrumentationCtx: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    val targetCtx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun asrModelDir(): File = File(targetCtx.filesDir, "dialect_models/asr")

    fun ttsSpeakerEncoderFile(): File =
        File(targetCtx.filesDir, "dialect_models/tts/speaker_encoder.onnx")

    /** Fails with push instructions when the bundle is absent — never skips silently. */
    fun requireAsrBundle() {
        val dir = asrModelDir()
        val missing = listOf(
            "conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx"
        ).filterNot { File(dir, it).isFile() } +
            (if (File(dir, "tokenizer").isDirectory) emptyList() else listOf("tokenizer/"))
        check(missing.isEmpty()) {
            "ASR bundle incomplete under ${dir.absolutePath}; missing=$missing. " +
                "Push the sherpa-onnx-qwen3-asr-0.6B-int8 bundle first."
        }
    }

    fun requireSpeakerEncoder() {
        val f = ttsSpeakerEncoderFile()
        check(f.isFile && File(f.parentFile, "speaker_encoder.onnx.data").isFile()) {
            "TTS speaker encoder graph missing at ${f.absolutePath} (+.data). " +
                "Push the tts/hf/speaker_encoder.onnx{,.data} files first."
        }
    }

    /** True when the real TTS speaker-encoder graph has been staged on device. */
    fun speakerEncoderAvailable(): Boolean =
        ttsSpeakerEncoderFile().isFile &&
            File(ttsSpeakerEncoderFile().parentFile, "speaker_encoder.onnx.data").isFile

    /**
     * 179-byte handcrafted graph (C = A + B): proves ONLY that the Java ORT
     * API binds and executes against the AAR's libonnxruntime.so 1.24.2.
     * Never used as TTS-quality evidence (see
     * reports/continuation-android-runtime.md).
     */
    fun bindingProbeModelBytes(): ByteArray =
        instrumentationCtx.assets.open("ort_binding_probe.onnx").use { it.readBytes() }

    /** Decode a WAV from androidTest assets into mono PCM (real bytes, strict parser). */
    fun wavAsset(name: String): Pair<FloatArray, Int> {
        val bytes = instrumentationCtx.assets.open("samples/$name").use { it.readBytes() }
        val pcm = WavCodec.decode(bytes)
        return pcm.toMono() to pcm.sampleRate
    }

    // ---- Reference transcripts (same index file the host runner uses) ----

    private val transcriptIndex: Map<String, String> by lazy {
        val index = HashMap<String, String>()
        instrumentationCtx.assets.open("samples/transcript.txt").bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.isEmpty()) continue
                val stem = parts[0].substringAfterLast('/').removeSuffix(".wav")
                index[stem] = parts.getOrElse(1) { "" }.trim()
            }
        }
        index
    }

    fun reference(stem: String): String =
        transcriptIndex[stem] ?: throw IOException("no reference transcript for $stem")

    // ---- CER: identical normalization to convert/asr_runner.py ----

    fun cer(reference: String, hypothesis: String): Double {
        val ref = reference.filterNot { it.isWhitespace() }
        val hyp = hypothesis.filterNot { it.isWhitespace() }
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        return editDistance(ref, hyp).toDouble() / ref.length
    }

    private fun editDistance(a: CharSequence, b: CharSequence): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    // ---- Process introspection (proves real coexistence, not mergeNativeLibs) ----

    /*
     * The app ships with extractNativeLibs=false: .so files are mapped
     * directly from base.apk, so /proc/self/maps shows the APK path (not
     * individual .so paths). We identify which specific library's pages are
     * resident by matching each APK entry's data offset against the maps
     * file-offset column — file-level, unambiguous evidence.
     */

    private val apkFile: File get() = File(targetCtx.applicationInfo.sourceDir)

    /** (entryName, dataOffset, size) for every .so stored inside the APK. */
    private val apkLibEntries: List<Triple<String, Long, Long>> by lazy {
        val central = ArrayList<Triple<String, Long, Long>>() // (name, size, localHeaderOffset)
        java.io.RandomAccessFile(apkFile, "r").use { raf ->
            // 1. find End Of Central Directory in the last 64 KiB
            val tailLen = minOf(65536L, raf.length())
            raf.seek(raf.length() - tailLen)
            val tail = ByteArray(tailLen.toInt())
            raf.readFully(tail)
            var eocd = -1
            var i = tail.size - 22
            while (i >= 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    eocd = i
                    break
                }
                i--
            }
            check(eocd >= 0) { "EOCD not found in APK" }
            val count = readLeU16(tail, eocd + 10)
            val cdOffset = readLeU32(tail, eocd + 16).toLong() and 0xFFFFFFFFL
            // 2. walk central directory
            var pos = cdOffset
            val hdr = ByteArray(46)
            repeat(count) {
                raf.seek(pos)
                raf.readFully(hdr)
                check(
                    hdr[0] == 0x50.toByte() && hdr[1] == 0x4B.toByte() &&
                        hdr[2] == 0x01.toByte() && hdr[3] == 0x02.toByte()
                ) {
                    "bad central directory entry at $pos"
                }
                val size = readLeU32(hdr, 20).toLong() and 0xFFFFFFFFL
                val nameLen = readLeU16(hdr, 28)
                val extraLen = readLeU16(hdr, 30)
                val commentLen = readLeU16(hdr, 32)
                val lho = readLeU32(hdr, 42).toLong() and 0xFFFFFFFFL
                val name = ByteArray(nameLen).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                if (name.endsWith(".so")) central.add(Triple(name, size, lho))
                pos += 46 + nameLen + extraLen + commentLen
            }
        }
        // 3. resolve each entry's local header → data offset
        val out = ArrayList<Triple<String, Long, Long>>()
        java.io.RandomAccessFile(apkFile, "r").use { raf ->
            val lh = ByteArray(30)
            for ((name, size, lho) in central) {
                raf.seek(lho)
                raf.readFully(lh)
                val nameLen = readLeU16(lh, 26)
                val extraLen = readLeU16(lh, 28)
                out.add(Triple(name, size, lho + 30 + nameLen + extraLen))
            }
        }
        out
    }

    private fun readLeU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLeU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private data class MapsLine(val offset: Long, val pathname: String)

    private fun mapsLines(): List<MapsLine> {
        val out = ArrayList<MapsLine>()
        File("/proc/self/maps").useLines { lines ->
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"), limit = 6)
                if (parts.size >= 6) out.add(MapsLine(parts[2].toLong(16), parts[5]))
            }
        }
        return out
    }

    /**
     * Which of the APK's stored .so files have pages resident right now.
     * A lib counts as mapped only when a maps line's file offset EXACTLY
     * equals that entry's data offset (the ELF's first PT_LOAD, p_offset=0,
     * 16 KiB zipalign-aligned). ART also maps the APK itself in large
     * regions, so offset-in-range matching would false-positive every lib.
     */
    fun mappedApkLibs(): Set<String> {
        val lines = mapsLines()
        val out = HashSet<String>()
        for ((name, _, dataOffset) in apkLibEntries) {  // Triple = (name, size, dataOffset)
            if (lines.any { it.pathname == apkFile.path && it.offset == dataOffset }) {
                out.add(name.substringAfterLast('/'))
            }
        }
        return out
    }

    fun debugLibDataOffset(entryName: String): Long =
        apkLibEntries.firstOrNull { it.first.endsWith(entryName) }?.third ?: -1

    /** Diagnostic: maps lines within +/- span of a given file offset. */
    fun mapsOffsetsNear(target: Long, span: Long = 1 shl 20): List<String> {
        val out = ArrayList<String>()
        File("/proc/self/maps").useLines { lines ->
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"), limit = 6)
                if (parts.size >= 6 && parts[4].toLong() != 0L) {
                    val off = parts[2].toLong(16)
                    if (kotlin.math.abs(off - target) < span) {
                        out.add("off=0x" + off.toString(16) + " perms=" + parts[1] +
                            " path=" + parts[5].substringAfterLast('/'))
                    }
                }
            }
        }
        return out
    }

    fun vmRssKb(): Long {
        File("/proc/self/status").useLines { lines ->
            for (line in lines) {
                if (line.startsWith("VmRSS:")) {
                    return line.split(Regex("\\s+"))[1].toLong()
                }
            }
        }
        return -1
    }

    // ---- Report output (pulled via adb; emulator evidence, not phone) ----

    fun reportDir(): File =
        File(targetCtx.getExternalFilesDir(null) ?: targetCtx.filesDir, "asr_validation")

    fun writeReport(name: String, content: String) {
        val dir = reportDir()
        dir.mkdirs()
        File(dir, name).writeText(content)
    }
}
