package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Original FP32 CP graph, captured real inputs, one opt-in session setting. */
@RunWith(AndroidJUnit4::class)
class Bf16CodePredictorProbeTest {
    @Test
    fun measureFixedWeightCpWithCapturedRealInputs() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual BF16 experiment only", arguments.getString("bf16Probe") == "true")
        val mode = arguments.getString("bf16Mode", "0")!!
        require(mode == "0" || mode == "1")
        val fx = AsrDeviceFixtures
        val env = OrtEnvironment.getEnvironment()
        assertEquals("1.24.2", env.version)
        val model = File(fx.targetCtx.filesDir, "dialect_models/tts/code_predictor.onnx")
        check(model.isFile)
        val modelHash = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { stream ->
            val block = ByteArray(1024 * 1024)
            while (true) { val size = stream.read(block); if (size < 0) break; modelHash.update(block, 0, size) }
        }
        val assets = fx.instrumentationCtx.assets
        val metadata = JSONObject(assets.open("bf16_cp_inputs/metadata.json").bufferedReader().use { it.readText() })
        val inputList = metadata.getJSONArray("inputs")
        val input = HashMap<String, OnnxTensor>()
        try {
            for (i in 0 until inputList.length()) {
                val item = inputList.getJSONObject(i)
                val bytes = assets.open("bf16_cp_inputs/" + item.getString("file")).use { it.readBytes() }
                val shapeJson = item.getJSONArray("shape")
                val shape = LongArray(shapeJson.length()) { shapeJson.getLong(it) }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                input[item.getString("name")] = when (item.getString("dtype")) {
                    "<f4" -> OnnxTensor.createTensor(env, buffer.asFloatBuffer(), shape)
                    "<i8" -> OnnxTensor.createTensor(env, buffer.asLongBuffer(), shape)
                    else -> error("Unsupported captured dtype")
                }
            }
            val report = JSONObject().put("mode", mode).put("ort_version", env.version)
                .put("model_sha256", modelHash.digest().joinToString("") { "%02x".format(it) })
                .put("input_provenance", metadata).put("rss_before_session_kb", fx.vmRssKb())
            OnnxModelManager(fx.targetCtx).createSessionOptions().use { options ->
                options.addConfigEntry(Bf16MatMulProbeTest.FASTMATH_KEY, mode)
                val started = System.nanoTime()
                env.createSession(model.absolutePath, options).use { session ->
                    report.put("session_create_ms", (System.nanoTime() - started) / 1e6)
                        .put("rss_after_session_kb", fx.vmRssKb())
                        .put("native_heap_after_session_bytes", android.os.Debug.getNativeHeapAllocatedSize())
                    val directory = File(fx.targetCtx.getExternalFilesDir(null), "bf16_probe").apply { mkdirs() }
                    val shapes = JSONObject()
                    session.run(input).use { result ->
                        for (name in session.outputNames) {
                            val tensor = result.get(name).get() as OnnxTensor
                            val floats = FloatArray(tensor.floatBuffer.remaining()).also { tensor.floatBuffer.get(it) }
                            assertTrue(floats.all { it.isFinite() })
                            val data = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                            data.asFloatBuffer().put(floats)
                            File(directory, "cp_${mode}_$name.f32").writeBytes(data.array())
                            shapes.put(name, JSONArray(tensor.info.shape.toList()))
                        }
                    }
                    repeat(4) { session.run(input).close() }
                    val times = ArrayList<Double>()
                    repeat(21) {
                        val start = System.nanoTime(); session.run(input).close()
                        times.add((System.nanoTime() - start) / 1e6)
                    }
                    report.put("run_ms", JSONArray(times)).put("median_ms", times.sorted()[10])
                        .put("output_shapes", shapes).put("rss_after_runs_kb", fx.vmRssKb())
                        .put("native_heap_after_runs_bytes", android.os.Debug.getNativeHeapAllocatedSize())
                    File(directory, "cp_$mode.json").writeText(report.toString(2))
                }
            }
        } finally {
            input.values.forEach { it.close() }
        }
    }
}
