package com.dialect.interpreter.inference

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HyMtTranslationEngineTest {

    @Test
    fun repeatedRequestsUseTheTranslationCache() = runBlocking {
        val runtime = FakeHyMtRuntime("Hello")
        val engine = HyMtTranslationEngine(
            modelFile = File("Hy-MT1.5-1.8B-1.25bit.gguf"),
            runtime = runtime,
            cache = TranslationCache(maxEntries = 4)
        )

        engine.load()
        val request = TranslationRequest(
            text = "  你好  ",
            sourceLanguage = "Chinese",
            targetLanguage = "English",
            context = listOf("上一句")
        )

        val first = engine.translate(request)
        val second = engine.translate(request)

        assertEquals("Hello", first.translatedText)
        assertEquals("Hello", second.translatedText)
        assertEquals(1, runtime.loadCalls)
        assertEquals(1, runtime.translateCalls)
        assertEquals("Hy-MT1.5-1.8B-1.25bit", first.runtime)
    }

    @Test
    fun blankInputReturnsBlankWithoutCallingRuntime() = runBlocking {
        val runtime = FakeHyMtRuntime("unused")
        val engine = HyMtTranslationEngine(
            modelFile = File("Hy-MT1.5-1.8B-1.25bit.gguf"),
            runtime = runtime,
            cache = TranslationCache(maxEntries = 4)
        )

        engine.load()
        val result = engine.translate(
            TranslationRequest(
                text = "   ",
                sourceLanguage = "Chinese",
                targetLanguage = "English"
            )
        )

        assertEquals("", result.translatedText)
        assertEquals(1, runtime.loadCalls)
        assertEquals(0, runtime.translateCalls)
    }

    private class FakeHyMtRuntime(private val output: String) : HyMtRuntime {
        var loadCalls = 0
            private set
        var translateCalls = 0
            private set

        override val runtimeLabel: String = "fake-hymt"

        override fun load(modelPath: String) {
            loadCalls += 1
        }

        override fun translate(
            text: String,
            sourceLanguage: String,
            targetLanguage: String,
            context: List<String>
        ): String {
            translateCalls += 1
            return output
        }

        override fun release() = Unit
    }
}
