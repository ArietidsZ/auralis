package com.dialect.interpreter.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hy-MT translation engine over an injectable [HyMtRuntime] (native by
 * default). Contract discipline:
 *  - load must succeed before translate; failure surfaces, never degrades to
 *    passthrough output.
 *  - context is truncated deterministically before hitting the runtime so the
 *    token budget stays bounded (spec R05).
 *  - [cancel] is forwarded so a stopping session can abort native generation
 *    before release (release waits for in-flight calls).
 */
class HyMtTranslationEngine(
    private val modelFile: File,
    private val runtime: HyMtRuntime = NativeHyMtRuntime(),
    private val modelLabel: String = DEFAULT_MODEL_LABEL,
    private val cache: TranslationCache = TranslationCache(modelId = modelLabel),
    private val contextEntryMaxChars: Int = DEFAULT_CONTEXT_ENTRY_MAX_CHARS
) : TranslationEngine {

    companion object {
        const val DEFAULT_MODEL_LABEL = "Hy-MT1.5-1.8B-1.25bit"
        const val DEFAULT_CONTEXT_ENTRY_MAX_CHARS = 512
        const val MAX_CONTEXT_ENTRIES = 4
    }

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
            val translatedText = try {
                runtime.translate(
                    text = normalizedText,
                    sourceLanguage = request.sourceLanguage,
                    targetLanguage = request.targetLanguage,
                    context = truncateContext(request.context)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: NativeHyMtRuntime.AbortedException) {
                // Generation aborted by an explicit cancel request.
                throw CancellationException("Hy-MT generation aborted", e)
            }
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

    override fun cancel() {
        if (!isLoaded) return
        runtime.cancel()
    }

    override fun release() {
        isLoaded = false
        cache.clear()
        runtime.release()
    }

    /**
     * Deterministic context truncation: newest entries last, oldest dropped
     * first, each entry clipped to [contextEntryMaxChars] so a single turn
     * cannot blow the generation token budget.
     */
    internal fun truncateContext(context: List<String>): List<String> {
        val cleaned = context
            .map { TranslationCache.normalizeText(it) }
            .filter { it.isNotBlank() }
            .takeLast(MAX_CONTEXT_ENTRIES)
        return cleaned.map { entry ->
            if (entry.length > contextEntryMaxChars) {
                entry.take(contextEntryMaxChars) + "…"
            } else {
                entry
            }
        }
    }
}
