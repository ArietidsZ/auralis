package com.dialect.interpreter.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HyMtTranslationEngineTest {

    @Test
    fun `repeated requests use the translation cache`() = runBlocking {
        val runtime = FakeHyMtRuntime("Hello")
        val engine = engine(runtime)

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
    fun `blank input returns blank without calling runtime`() = runBlocking {
        val runtime = FakeHyMtRuntime("unused")
        val engine = engine(runtime)

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

    @Test
    fun `translate before load fails`() = runBlocking<Unit> {
        val engine = engine(FakeHyMtRuntime("Hello"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                engine.translate(
                    TranslationRequest(text = "hi", sourceLanguage = "zh", targetLanguage = "en")
                )
            }
        }
    }

    @Test
    fun `context is truncated deterministically before hitting the runtime`() = runBlocking {
        val runtime = FakeHyMtRuntime("ok")
        val engine = engine(runtime, contextEntryMaxChars = 8)

        engine.load()
        engine.translate(
            TranslationRequest(
                text = "正文",
                sourceLanguage = "Chinese",
                targetLanguage = "English",
                context = listOf(
                    "一".repeat(100),
                    "   ",               // blank → dropped
                    "二".repeat(4),
                    "三".repeat(4),
                    "四".repeat(4),
                    "五".repeat(4)      // 5 valid-ish entries → keep last 4
                )
            )
        )

        val context = runtime.lastContext
        assertEquals(4, context.size)
        for (entry in context) {
            assertTrue(
                "entries are clipped to the budget: ${entry.length}",
                entry.length <= 8 + 1 // clip + ellipsis
            )
        }
        assertEquals("五五五五", context.last())
    }

    @Test
    fun `cancel request aborts translate with a cancellation exception`() = runBlocking<Unit> {
        val runtime = FakeHyMtRuntime("never")
        val engine = engine(runtime)

        engine.load()
        runtime.abortNextTranslate = true

        try {
            engine.translate(
                TranslationRequest(text = "你好", sourceLanguage = "Chinese", targetLanguage = "English")
            )
            throw AssertionError("expected CancellationException")
        } catch (expected: CancellationException) {
            // Coroutine stack-trace recovery may wrap the engine's
            // CancellationException in another CancellationException layer, so
            // the original AbortedException can sit deeper in the chain.
            var cause: Throwable? = expected.cause
            while (cause != null && cause !is NativeHyMtRuntime.AbortedException) {
                cause = cause.cause
            }
            assertTrue(
                "abort must surface as cancellation keeping its original cause, chain was: " +
                    generateSequence(expected as Throwable) { it.cause }.joinToString(" <- ") { it::class.java.simpleName },
                cause is NativeHyMtRuntime.AbortedException
            )
        }

        // An explicit cancel request reaches the runtime (stop-path contract).
        engine.cancel()
        assertEquals(1, runtime.cancelCalls)
    }

    @Test
    fun `native runtime fails fast when the library is not packaged`() {
        val runtime = NativeHyMtRuntime()
        val error = assertThrows(IllegalStateException::class.java) {
            runtime.load("/models/mt/Hy-MT1.5-1.8B-1.25bit.gguf")
        }
        assertTrue(error.message!!.contains("hymt_jni"))

        // Without a handle, translate/release are safe no-ops or failures.
        assertThrows(IllegalStateException::class.java) {
            runtime.translate("hi", "zh", "en", emptyList())
        }
        runtime.cancel() // safe without handle
        runtime.release() // safe without handle
    }

    private fun engine(
        runtime: HyMtRuntime,
        contextEntryMaxChars: Int = HyMtTranslationEngine.DEFAULT_CONTEXT_ENTRY_MAX_CHARS
    ) = HyMtTranslationEngine(
        modelFile = File("Hy-MT1.5-1.8B-1.25bit.gguf"),
        runtime = runtime,
        contextEntryMaxChars = contextEntryMaxChars
    )

    private class FakeHyMtRuntime(private val output: String) : HyMtRuntime {
        var loadCalls = 0
            private set
        var translateCalls = 0
            private set
        var cancelCalls = 0
            private set
        var lastContext: List<String> = emptyList()
            private set
        var abortNextTranslate = false

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
            lastContext = context
            if (abortNextTranslate) {
                throw NativeHyMtRuntime.AbortedException("aborted by cancel")
            }
            return output
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun release() = Unit
    }
}
