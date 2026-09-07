package com.dialect.interpreter.inference

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI bridge for AngelSlim/Hy-MT1.5-1.8B-1.25bit GGUF inference (see
 * src/main/cpp/hymt_jni for the native sources and build entry).
 *
 * Handle discipline (spec R05: load/generate/cancel/free 按一个句柄所有者串行化):
 *  - [load] / [translate] / [release] mutate or observe the handle under
 *    [stateLock]; an in-flight generate never overlaps release.
 *  - [cancel] flips the abort flag, then calls the JNI abort hook under
 *    [stateLock] so it can never race a concurrent free (the flag write is
 *    outside the lock; the hook only sets an abort flag and returns quickly).
 *  - [release] waits (interruptibly) until no generate is in flight, frees the
 *    current handle exactly once and RESETS to the unloaded state — a pipeline
 *    reusing this engine for a later session may load again. Repeated release
 *    without an intervening load is a no-op.
 */
class NativeHyMtRuntime : HyMtRuntime {

    /** Generation was aborted through [cancel]. */
    class AbortedException(message: String) : IllegalStateException(message)

    override val runtimeLabel: String = "hymt_jni"

    private val stateLock = ReentrantLock()
    private val idleCondition = stateLock.newCondition()
    private var handle: Long = 0L
    private var inFlightGenerations = 0
    private val abortRequested = AtomicBoolean(false)

    override fun load(modelPath: String) {
        stateLock.withLock {
            if (handle != 0L) return
            check(libraryLoaded) {
                "Hy-MT native runtime library 'hymt_jni' is not packaged. " +
                    "Bundle the AngelSlim/Hy-MT1.5-1.8B-1.25bit GGUF runtime before starting " +
                    "live translation (see src/main/cpp/hymt_jni/README.md)."
            }
            abortRequested.set(false)
            val created = nativeCreate(modelPath)
            check(created != 0L) { "Hy-MT native runtime failed to create a model handle" }
            handle = created
        }
    }

    override fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        context: List<String>
    ): String {
        val currentHandle = beginGeneration()
        try {
            val result = try {
                nativeTranslate(
                    handle = currentHandle,
                    text = text,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    context = context.toTypedArray()
                )
            } catch (error: RuntimeException) {
                if (abortRequested.get()) {
                    throw AbortedException("Hy-MT generation aborted by cancel request").apply {
                        initCause(error)
                    }
                }
                throw error
            }
            if (abortRequested.get()) {
                throw AbortedException("Hy-MT generation aborted by cancel request")
            }
            return result
        } finally {
            endGeneration()
        }
    }

    override fun cancel() {
        abortRequested.set(true)
        // Under the same lock as free: the abort hook only sets a flag and
        // returns quickly, so holding the lock here is cheap and makes a
        // concurrent release() unable to free the handle underneath us.
        stateLock.withLock {
            if (libraryLoaded && handle != 0L) {
                nativeCancel(handle)
            }
        }
    }

    override fun release() {
        val handleToFree = stateLock.withLock {
            if (handle == 0L) return
            // Wait for in-flight generations: free must never overlap generate
            // (spec R01/R05: 等待在途 native 调用结束 → 关闭模型).
            while (inFlightGenerations > 0) {
                idleCondition.await()
            }
            val current = handle
            handle = 0L
            current
        }
        if (libraryLoaded && handleToFree != 0L) {
            nativeRelease(handleToFree)
        }
        // Reset to the unloaded state: a later session's start/load on the same
        // engine must succeed (permanent teardown belongs to controller close,
        // which releases the engines themselves).
        abortRequested.set(false)
    }

    private fun beginGeneration(): Long = stateLock.withLock {
        check(handle != 0L) { "Hy-MT native runtime is not loaded" }
        if (abortRequested.get()) throw AbortedException("Hy-MT generation already cancelled")
        inFlightGenerations++
        handle
    }

    private fun endGeneration() {
        stateLock.withLock {
            inFlightGenerations--
            // Wake a release() waiting for in-flight work.
            idleCondition.signalAll()
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

    private external fun nativeCancel(handle: Long)

    private external fun nativeRelease(handle: Long)

    companion object {
        private val libraryLoaded: Boolean = runCatching {
            System.loadLibrary("hymt_jni")
        }.isSuccess
    }
}
