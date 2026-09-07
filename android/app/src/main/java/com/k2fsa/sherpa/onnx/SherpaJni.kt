package com.k2fsa.sherpa.onnx

/**
 * Lazy JNI load. Official wrappers used a static init { System.loadLibrary },
 * which would run as soon as the class is resolved (including JVM unit tests
 * that only touch [com.dialect.interpreter.inference.AsrEngine.TranscriptionResult]).
 */
internal object SherpaJni {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-jni")
        loaded = true
    }

    /** Band-limited reference resampling; call [load] before this JNI entry. */
    @JvmStatic
    external fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray
}
