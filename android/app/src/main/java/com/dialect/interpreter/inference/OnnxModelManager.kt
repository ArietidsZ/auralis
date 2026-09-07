package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the ONNX Runtime environment and the per-model session handles.
 *
 * Execution provider policy (spec README/V03): quantized models start on CPU;
 * other EPs are only enabled after a per-model, per-device comparison shows a
 * real win. No automatic EP guessing and no NNAPI default — ORT 1.24.2 CPU ships
 * KleidiAI-optimized MLAS INT4 kernels for the Arm path, and NNAPI's op
 * surface does not cover the INT4 MatMulNBits weights used here.
 *
 * "Ready" states are NOT derived from file extensions (spec 01: 禁止根据文件
 * 扩展名推断支持); a session only counts as usable after [loadSession] opened
 * it and the engine's protocol validation passed.
 */
class OnnxModelManager(private val context: Context) {

    companion object {
        private const val TAG = "OnnxModelManager"
        const val MODELS_DIR = "dialect_models"
        const val ASR_DIR = "asr"
        const val TTS_DIR = "tts"
        const val MT_DIR = "mt"
        const val HY_MT_GGUF = "Hy-MT1.5-1.8B-1.25bit-stq43.gguf"
    }

    // Create the Java environment on its first use. Java and native ASR
    // share the packaged ORT 1.24.2 library.
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = ConcurrentHashMap<String, OrtSession>()

    fun createSessionOptions(): SessionOptions {
        val options = SessionOptions()
        // Graph optimization: fuse ops, fold constants, eliminate dead code.
        options.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
        options.setMemoryPatternOptimization(true)

        // CPU EP baseline: bounded thread pool so one engine cannot exhaust the
        // SoC (spec R05: 默认一个引擎一个计算并发额度).
        val cores = Runtime.getRuntime().availableProcessors()
        options.setIntraOpNumThreads(minOf(cores, 4))
        options.setInterOpNumThreads(1)
        Log.i(TAG, "CPU EP: cores=$cores intra=${minOf(cores, 4)} inter=1 " +
                "SoC=${socName()} SDK=${Build.VERSION.SDK_INT}")
        return options
    }

    private fun socName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE

    /**
     * Load (or reuse) an ORT session for a model file. Fails when the file is
     * absent — callers translate that into capability loss or session failure.
     */
    suspend fun loadSession(subDir: String, modelFileName: String): OrtSession =
        withContext(Dispatchers.IO) {
            val key = "$subDir/$modelFileName"
            sessions[key]?.let { return@withContext it }

            val modelFile = File(context.filesDir, "$MODELS_DIR/$subDir/$modelFileName")
            require(modelFile.exists()) { "Model not found: ${modelFile.absolutePath}" }

            Log.i(TAG, "Loading: $key (${modelFile.length() / 1_000_000}MB)")
            val t0 = System.nanoTime()

            val options = createSessionOptions()
            val session = try {
                env.createSession(modelFile.absolutePath, options)
            } finally {
                options.close()
            }

            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "Loaded in ${elapsedMs}ms | inputs=${session.inputNames} outputs=${session.outputNames}")

            val existing = sessions.putIfAbsent(key, session)
            if (existing != null) {
                session.close()
                existing
            } else {
                session
            }
        }

    /**
     * Loads (or reuses) the session for a manifest ROLE, resolving the role to
     * its canonical manifest file path first. Sessions are keyed by the
     * canonical "<subDir>/<file>" path, so two roles pointing at the same file
     * share one session instead of loading the weights twice.
     */
    suspend fun loadRoleSession(
        subDir: String,
        roles: Map<String, String>,
        role: String,
    ): OrtSession = withContext(Dispatchers.IO) {
        val relative = roles[role]
            ?: throw IllegalArgumentException("manifest has no '$role' role")
        val prefix = "$subDir/"
        require(relative.startsWith(prefix) && !relative.contains("..")) {
            "role $role path '$relative' is not inside $prefix"
        }
        loadSession(subDir, relative.removePrefix(prefix))
    }

    /** Releases the session behind a manifest role, if one was loaded. */
    fun releaseRole(subDir: String, roles: Map<String, String>, role: String) {
        val relative = roles[role] ?: return
        val prefix = "$subDir/"
        if (relative.startsWith(prefix) && !relative.contains("..")) {
            release("$subDir/${relative.removePrefix(prefix)}")
        }
    }

    // ---- Tensor creation ----

    fun createTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(data), shape)

    fun createLongTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(data), shape)

    // ---- Lifecycle ----

    fun getModelsDir(): File = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    fun getTotalModelSizeBytes(): Long =
        getModelsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun release(key: String) {
        sessions.remove(key)?.close()
    }

    fun releaseAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
