package com.dialect.interpreter.inference

/**
 * Machine translation boundary for the live interpretation pipeline.
 *
 * The app-level pipeline depends on this interface instead of a concrete model
 * runtime so Hy-MT can be packaged through GGUF/native kernels without coupling
 * UI or ASR/TTS code to that runtime. There is no passthrough implementation:
 * a missing/broken runtime surfaces as a failure or as "translation
 * unavailable", never as fabricated output.
 */
interface TranslationEngine {
    suspend fun load()

    suspend fun translate(request: TranslationRequest): TranslationResult

    /**
     * Best-effort abort request for an in-flight [translate] (native generate).
     * Safe to call before load, without a handle, or repeatedly; the in-flight
     * call still returns (with an abort failure) — cancel never frees state.
     */
    fun cancel()

    fun release()
}

data class TranslationRequest(
    val text: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: List<String> = emptyList()
)

data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val latencyMs: Long,
    val runtime: String
)

/**
 * Native runtime handle boundary (JNI over llama.cpp/Hy-MT). All lifecycle
 * operations are serialized by the implementation; release waits for in-flight
 * generate calls to finish (see NativeHyMtRuntime).
 */
interface HyMtRuntime {
    val runtimeLabel: String

    fun load(modelPath: String)

    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        context: List<String>
    ): String

    /** Request abort of an in-flight [translate]; safe concurrently with it. */
    fun cancel()

    fun release()
}
