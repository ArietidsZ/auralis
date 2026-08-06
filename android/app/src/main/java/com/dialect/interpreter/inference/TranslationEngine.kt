package com.dialect.interpreter.inference

/**
 * Machine translation boundary for the live interpretation pipeline.
 *
 * The app-level pipeline depends on this interface instead of a concrete model
 * runtime so Hy-MT can be packaged through GGUF/native kernels without coupling
 * UI or ASR/TTS code to that runtime.
 */
interface TranslationEngine {
    suspend fun load()
    suspend fun translate(request: TranslationRequest): TranslationResult
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

interface HyMtRuntime {
    val runtimeLabel: String

    fun load(modelPath: String)

    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        context: List<String>
    ): String

    fun release()
}
