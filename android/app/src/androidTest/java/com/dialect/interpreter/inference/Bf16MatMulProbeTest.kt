package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated runtime-dispatch experiment; production options are never changed. */
@RunWith(AndroidJUnit4::class)
class Bf16MatMulProbeTest {
    companion object {
        const val FASTMATH_KEY = "mlas.enable_gemm_fastmath_arm64_bfloat16"
    }
    private fun values(size: Int): FloatArray = FloatArray(size) { i ->
        (((i.toLong() * 1664525L + 1013904223L) and 0xffffff).toFloat() / 8388608f - 1f)
    }
    private fun bf16(value: Float): Float {
        val bits = value.toRawBits()
        return Float.fromBits((bits + 0x7fff + ((bits ushr 16) and 1)) and -65536)
    }
    private fun options(enabled: Boolean): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        setIntraOpNumThreads(4)
        setInterOpNumThreads(1)
        addConfigEntry(FASTMATH_KEY, if (enabled) "1" else "0")
    }
    @Test
    fun compareActualFp32AndFastmathOutputsAndTiming() {
        assumeTrue("Manual capability probe only",
            InstrumentationRegistry.getArguments().getString("bf16Probe") == "true")
        val context = AsrDeviceFixtures.targetCtx
        val env = OrtEnvironment.getEnvironment()
        assertEquals("1.24.2", env.version)
        val graph = AsrDeviceFixtures.instrumentationCtx.assets.open("bf16_matmul_probe.onnx").use { it.readBytes() }
        val report = JSONObject().put("ort_version", env.version).put("config_key", FASTMATH_KEY)
            .put("graph_dtype", "FLOAT; dynamic A and B; no Cast; one MatMul")
            .put("optimization", "NO_OPT").put("threads", 4)
        val rows = JSONArray()
        options(false).use { offOptions -> options(true).use { onOptions ->
            env.createSession(graph, offOptions).use { off -> env.createSession(graph, onOptions).use { on ->
                for ((label, dimensions) in listOf(
                    "below_threshold" to intArrayOf(4, 4, 4),
                    "identity_precision" to intArrayOf(64, 1024, 1024),
                    "dense_throughput" to intArrayOf(256, 1024, 1024))) {
                    val (m, k, n) = dimensions
                    val a = values(m * k)
                    val b = if (label == "dense_throughput") values(k * n) else FloatArray(k * n) { i -> if (i / n == i % n) 1f else 0f }
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(a), longArrayOf(m.toLong(), k.toLong())).use { ta ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(b), longArrayOf(k.toLong(), n.toLong())).use { tb ->
                            val input = mapOf("A" to ta, "B" to tb)
                            fun output(session: OrtSession): FloatArray = session.run(input).use { r ->
                                FloatArray(m * n).also { (r[0] as OnnxTensor).floatBuffer.get(it) }
                            }
                            val fp32 = output(off); val fast = output(on)
                            assertTrue(fp32.all { it.isFinite() } && fast.all { it.isFinite() })
                            val changed = fp32.indices.count { fp32[it].toRawBits() != fast[it].toRawBits() }
                            val maxDiff = fp32.indices.maxOf { abs(fp32[it].toDouble() - fast[it]) }
                            val rmse = sqrt(fp32.indices.sumOf { val d = fp32[it].toDouble() - fast[it]; d * d } / fp32.size)
                            val row = JSONObject().put("case", label).put("m", m).put("k", k).put("n", n)
                                .put("changed_outputs", changed).put("outputs", fp32.size)
                                .put("max_abs_difference", maxDiff).put("rmse", rmse)
                            if (label != "dense_throughput") {
                                val fpError = a.indices.maxOf { abs(a[it] - fp32[it]) }
                                val roundedMatches = a.indices.count { bf16(a[it]).toRawBits() == fast[it].toRawBits() }
                                row.put("fp32_identity_error", fpError).put("bf16_rne_matches", roundedMatches)
                                    .put("non_bf16_input_count", a.count { bf16(it).toRawBits() != it.toRawBits() })
                                assertEquals(0f, fpError, 0f)
                                if (label == "below_threshold") assertEquals(0, changed)
                            }
                            if (label == "dense_throughput") {
                                repeat(4) { off.run(input).close(); on.run(input).close() }
                                val offMs = ArrayList<Double>(); val onMs = ArrayList<Double>()
                                fun timed(session: OrtSession): Double {
                                    val start = System.nanoTime(); session.run(input).close()
                                    return (System.nanoTime() - start) / 1e6
                                }
                                repeat(21) { index ->
                                    if (index % 2 == 0) { offMs.add(timed(off)); onMs.add(timed(on)) }
                                    else { onMs.add(timed(on)); offMs.add(timed(off)) }
                                }
                                row.put("off_ms", JSONArray(offMs)).put("on_ms", JSONArray(onMs))
                                    .put("off_median_ms", offMs.sorted()[10]).put("on_median_ms", onMs.sorted()[10])
                            }
                            rows.put(row)
                        }
                    }
                }
            } }
        } }
        report.put("cases", rows)
        val identity = rows.getJSONObject(1)
        val effective = identity.getInt("changed_outputs") > 0 &&
            identity.getInt("bf16_rne_matches") == identity.getInt("outputs")
        report.put("bf16_activation_rounding_observed", effective)
        val dir = File(context.getExternalFilesDir(null), "bf16_probe").apply { mkdirs() }
        File(dir, "matmul.json").writeText(report.toString(2))
        assertTrue("No BF16 activation-rounding signature; inspect report instead of assuming support", effective)
    }
}
