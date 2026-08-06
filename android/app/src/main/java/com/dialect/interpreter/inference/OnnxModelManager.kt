package com.dialect.interpreter.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages ONNX Runtime environment with aggressive NPU optimization.
 *
 * Execution Provider priority:
 *  1. NNAPI EP → delegates to Hexagon NPU (Qualcomm), APU (MediaTek), TPU (Google), etc.
 *  2. CPU EP with XNNPACK (fallback)
 *
 * NPU Optimization Strategy:
 *  - FP16 computation on NPU (most modern NPUs natively support FP16)
 *  - Sustained performance mode for continuous inference
 *  - Burst compute for latency-critical first-frame generation
 *  - Graph-level optimization (op fusion, constant folding)
 */
class OnnxModelManager(private val context: Context) {

    companion object {
        private const val TAG = "OnnxModelManager"
        const val MODELS_DIR = "dialect_models"
        const val ASR_DIR = "asr"
        const val TTS_DIR = "tts"
        const val MT_DIR = "mt"
        const val HY_MT_GGUF = "Hy-MT1.5-1.8B-1.25bit.gguf"
    }

    enum class ExecutionProvider { NNAPI, CPU }
    enum class ModelStatus { NOT_DOWNLOADED, READY, ERROR }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = ConcurrentHashMap<String, OrtSession>()

    private val _selectedProvider = MutableStateFlow(ExecutionProvider.CPU)
    val selectedProvider: StateFlow<ExecutionProvider> = _selectedProvider

    private val _asrStatus = MutableStateFlow(ModelStatus.NOT_DOWNLOADED)
    val asrStatus: StateFlow<ModelStatus> = _asrStatus

    private val _ttsStatus = MutableStateFlow(ModelStatus.NOT_DOWNLOADED)
    val ttsStatus: StateFlow<ModelStatus> = _ttsStatus

    init {
        detectBestProvider()
        checkModelStatus()
    }

    private fun detectBestProvider() {
        // NNAPI does NOT support INT4 / MatMulNBits gen-AI ops (its op surface is
        // INT8 QLinear ops only), so it is not a valid accelerator for the INT4
        // Qwen3 transformer weights. The portable INT4 path is ORT CPU via the
        // KleidiAI-optimized MLAS kernels (ONNX Runtime >= 1.22). Qualcomm devices
        // can additionally use the QNN EP (native INT8/INT4/INT2) once a QDQ
        // context-binary model is produced — see the EP strategy in the refactor plan.
        _selectedProvider.value = ExecutionProvider.CPU
        Log.i(TAG, "CPU EP selected (portable INT4 path) — SoC: ${socName()}, Board: ${Build.BOARD}, SDK: ${Build.VERSION.SDK_INT}")
    }

    private fun socName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
    }

    private fun checkModelStatus() {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        _asrStatus.value = if (File(modelsDir, ASR_DIR).let { d ->
                d.exists() && d.listFiles()?.any { it.extension == "onnx" } == true
            }) ModelStatus.READY else ModelStatus.NOT_DOWNLOADED
        _ttsStatus.value = if (File(modelsDir, TTS_DIR).let { d ->
                d.exists() && d.listFiles()?.any { it.extension == "onnx" } == true
            }) ModelStatus.READY else ModelStatus.NOT_DOWNLOADED
    }

    /**
     * Create optimized SessionOptions targeting modern NPUs.
     */
    fun createSessionOptions(): SessionOptions {
        val options = SessionOptions()

        // Graph optimization: fuse ops, fold constants, eliminate dead code
        options.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)

        // Enable graph-level memory pattern optimization (reuse allocations)
        options.setMemoryPatternOptimization(true)

        when (_selectedProvider.value) {
            ExecutionProvider.NNAPI -> {
                try {
                    options.addNnapi(
                        java.util.EnumSet.noneOf(NNAPIFlags::class.java)
                    )
                    // Fewer CPU threads since NPU does the heavy lifting;
                    // CPU threads only handle pre/post processing
                    options.setIntraOpNumThreads(2)
                    Log.i(TAG, "NNAPI EP: default flags + memory pattern opt")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI failed, falling back to CPU: ${e.message}")
                    _selectedProvider.value = ExecutionProvider.CPU
                    configureCpuFallback(options)
                }
            }

            ExecutionProvider.CPU -> configureCpuFallback(options)
        }

        return options
    }

    private fun configureCpuFallback(options: SessionOptions) {
        // Use all big cores for maximum throughput on CPU fallback
        val cores = Runtime.getRuntime().availableProcessors()
        options.setIntraOpNumThreads(minOf(cores, 4))
        options.setInterOpNumThreads(2)
        Log.i(TAG, "CPU EP: $cores cores, intra=${minOf(cores, 4)}, inter=2")
    }

    /**
     * Load an ONNX model with NPU-optimized session.
     */
    suspend fun loadSession(subDir: String, modelFileName: String): OrtSession =
        withContext(Dispatchers.IO) {
            val key = "$subDir/$modelFileName"
            sessions[key]?.let { return@withContext it }

            val modelFile = File(context.filesDir, "$MODELS_DIR/$subDir/$modelFileName")
            require(modelFile.exists()) { "Model not found: ${modelFile.absolutePath}" }

            Log.i(TAG, "Loading: $key (${modelFile.length() / 1_000_000}MB)")
            val t0 = System.nanoTime()

            val session = env.createSession(modelFile.absolutePath, createSessionOptions())

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

    // ---- Tensor creation ----

    fun createTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(data), shape)

    fun createLongTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(data), shape)

    // ---- Lifecycle ----

    fun getModelsDir(): File = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    fun getTotalModelSizeBytes(): Long =
        getModelsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun release(key: String) { sessions.remove(key)?.close() }
    fun releaseAll() { sessions.values.forEach { it.close() }; sessions.clear() }
}
