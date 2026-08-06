package com.dialect.interpreter.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    private fun request(
        text: String = "Hello",
        source: String = "English",
        target: String = "Chinese",
        context: List<String> = emptyList()
    ) = TranslationRequest(
        text = text,
        sourceLanguage = source,
        targetLanguage = target,
        context = context
    )

    private fun result(text: String) = TranslationResult(
        sourceText = text,
        translatedText = text,
        sourceLanguage = "",
        targetLanguage = "",
        latencyMs = 1L,
        runtime = "test"
    )

    @Test
    fun `normalized keys collapse equivalent requests`() {
        val cache = TranslationCache(maxEntries = 4)
        cache.put(
            request(text = "  Hello   world  ", source = "English", target = "Chinese"),
            result("你好 世界")
        )

        // Different whitespace but semantically identical — must hit the same key.
        val hit = cache.get(request(text = "Hello world", source = "english", target = "chinese"))
        assertEquals("你好 世界", hit?.translatedText)
    }

    @Test
    fun `context changes produce distinct keys`() {
        val cache = TranslationCache(maxEntries = 4)
        cache.put(
            request(text = "Bank", context = listOf("river")),
            result("河岸")
        )
        cache.put(
            request(text = "Bank", context = listOf("money")),
            result("银行")
        )

        assertEquals("河岸", cache.get(request(text = "Bank", context = listOf("river")))?.translatedText)
        assertEquals("银行", cache.get(request(text = "Bank", context = listOf("money")))?.translatedText)
    }

    @Test
    fun `empty context keys are normalized to blank`() {
        val cache = TranslationCache(maxEntries = 4)
        cache.put(request(text = "Hi", context = listOf("   ")), result("你好"))

        // Blank-only context entries are filtered out, so this must hit.
        assertEquals("你好", cache.get(request(text = "Hi", context = emptyList()))?.translatedText)
    }

    @Test
    fun `LRU evicts least recently used entry`() {
        val cache = TranslationCache(maxEntries = 2)

        cache.put(request(text = "A"), result("a"))
        cache.put(request(text = "B"), result("b"))
        // Touch A so it is most-recently-used.
        cache.get(request(text = "A"))
        // Inserting C now evicts B (the LRU), not A.
        cache.put(request(text = "C"), result("c"))

        assertNull(cache.get(request(text = "B")))
        assertEquals("a", cache.get(request(text = "A"))?.translatedText)
        assertEquals("c", cache.get(request(text = "C"))?.translatedText)
    }

    @Test
    fun `clear removes all entries`() {
        val cache = TranslationCache(maxEntries = 4)
        cache.put(request(text = "A"), result("a"))
        cache.clear()

        assertNull(cache.get(request(text = "A")))
    }
}