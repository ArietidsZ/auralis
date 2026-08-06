package com.dialect.interpreter.inference

import java.util.LinkedHashMap

class TranslationCache(
    private val maxEntries: Int = 128
) {
    private val entries = object : LinkedHashMap<Key, TranslationResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TranslationResult>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(request: TranslationRequest): TranslationResult? =
        entries[Key.from(request)]

    @Synchronized
    fun put(request: TranslationRequest, result: TranslationResult) {
        entries[Key.from(request)] = result
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    data class Key(
        val text: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val context: List<String>
    ) {
        companion object {
            fun from(request: TranslationRequest): Key = Key(
                text = normalizeText(request.text),
                sourceLanguage = normalizeLanguage(request.sourceLanguage),
                targetLanguage = normalizeLanguage(request.targetLanguage),
                context = request.context.map(::normalizeText).filter { it.isNotBlank() }
            )
        }
    }

    companion object {
        private val whitespace = Regex("\\s+")

        fun normalizeText(value: String): String =
            value.trim().replace(whitespace, " ")

        fun normalizeLanguage(value: String): String =
            value.trim().lowercase()
    }
}
