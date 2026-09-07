package com.dialect.interpreter.data

import java.util.concurrent.atomic.AtomicBoolean

/** Keeps installed files stable until every native reader has released them. */
class ModelAccessGate {
    private var readers = 0
    private var changingFiles = false

    @Synchronized
    fun acquireUse(): AutoCloseable {
        check(!changingFiles) { "模型正在安装或校验，请稍后重试" }
        readers++
        return lease { readers-- }
    }

    @Synchronized
    fun tryAcquireChange(): AutoCloseable? {
        if (changingFiles || readers != 0) return null
        changingFiles = true
        return lease { changingFiles = false }
    }

    private fun lease(release: () -> Unit): AutoCloseable {
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(this) { release() }
            }
        }
    }
}
