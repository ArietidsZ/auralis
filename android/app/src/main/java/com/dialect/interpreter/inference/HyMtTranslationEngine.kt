package com.dialect.interpreter.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HyMtTranslationEngine(
    private val modelFile: File,
    private val runtime: HyMtRuntime = NativeHyMtRuntime(),
    private val cache: TranslationCache = TranslationCache(),
    private val modelLabel: String = "Hy-MT1.5-1.8B-1.25bit"
) : TranslationEngine {

    @Volatile
    private var isLoaded = false

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        runtime.load(modelFile.absolutePath)
        isLoaded = true
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        withContext(Dispatchers.Default) {
            check(isLoaded) { "Hy-MT translation model not loaded" }

            val normalizedText = TranslationCache.normalizeText(request.text)
            if (normalizedText.isBlank()) {
                return@withContext TranslationResult(
                    sourceText = "",
                    translatedText = "",
                    sourceLanguage = request.sourceLanguage,
                    targetLanguage = request.targetLanguage,
                    latencyMs = 0L,
                    runtime = modelLabel
                )
            }

            cache.get(request)?.let { return@withContext it }

            val startedAt = System.nanoTime()
            val translatedText = runtime.translate(
                text = normalizedText,
                sourceLanguage = request.sourceLanguage,
                targetLanguage = request.targetLanguage,
                context = request.context
            )
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000

            val result = TranslationResult(
                sourceText = normalizedText,
                translatedText = TranslationCache.normalizeText(translatedText),
                sourceLanguage = request.sourceLanguage,
                targetLanguage = request.targetLanguage,
                latencyMs = latencyMs,
                runtime = modelLabel
            )
            cache.put(request, result)
            result
        }

    override fun release() {
        isLoaded = false
        cache.clear()
        runtime.release()
    }
}
