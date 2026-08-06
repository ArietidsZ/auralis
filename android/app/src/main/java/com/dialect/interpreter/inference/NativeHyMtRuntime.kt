package com.dialect.interpreter.inference

/**
 * JNI bridge for AngelSlim/Hy-MT1.5-1.8B-1.25bit GGUF inference.
 *
 * The native side is expected to expose a thin wrapper around the packaged
 * Hy-MT/llama.cpp-compatible runtime and the `mt/Hy-MT1.5-1.8B-1.25bit.gguf`
 * model file. Keeping this bridge strict avoids silently shipping passthrough
 * "translations" when the real runtime is not bundled.
 */
class NativeHyMtRuntime : HyMtRuntime {

    override val runtimeLabel: String = "hymt_jni"

    private var handle: Long = 0L

    override fun load(modelPath: String) {
        check(libraryLoaded) {
            "Hy-MT native runtime library 'hymt_jni' is not packaged. " +
                "Bundle the AngelSlim/Hy-MT1.5-1.8B-1.25bit GGUF runtime before starting live translation."
        }
        if (handle != 0L) return
        handle = nativeCreate(modelPath)
        check(handle != 0L) { "Hy-MT native runtime failed to create a model handle" }
    }

    override fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        context: List<String>
    ): String {
        check(handle != 0L) { "Hy-MT native runtime is not loaded" }
        return nativeTranslate(
            handle = handle,
            text = text,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            context = context.toTypedArray()
        )
    }

    override fun release() {
        val current = handle
        handle = 0L
        if (libraryLoaded && current != 0L) {
            nativeRelease(current)
        }
    }

    private external fun nativeCreate(modelPath: String): Long

    private external fun nativeTranslate(
        handle: Long,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        context: Array<String>
    ): String

    private external fun nativeRelease(handle: Long)

    companion object {
        private val libraryLoaded: Boolean = runCatching {
            System.loadLibrary("hymt_jni")
        }.isSuccess
    }
}
