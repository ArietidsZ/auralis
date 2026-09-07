package com.dialect.interpreter.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModelAccessGateTest {
    @Test
    fun `updates wait for every native reader including a stopping session`() {
        val gate = ModelAccessGate()
        val session = gate.acquireUse()
        val encoder = gate.acquireUse()
        assertNull(gate.tryAcquireChange())
        encoder.close()
        assertNull("session still owns its native handles", gate.tryAcquireChange())
        session.close()
        val change = gate.tryAcquireChange()
        assertNotNull(change)
        change!!.close()
    }

    @Test
    fun `an install refuses new readers and competing mutations`() {
        val gate = ModelAccessGate()
        gate.tryAcquireChange()!!.use {
            assertNull(gate.tryAcquireChange())
            try {
                gate.acquireUse()
                fail("a model must not load during replacement")
            } catch (_: IllegalStateException) {
                // Admission failed before opening a native handle.
            }
        }
        gate.acquireUse().close()
    }

    @Test
    fun `closing an old lease twice cannot release a new reader`() {
        val gate = ModelAccessGate()
        val old = gate.acquireUse()
        old.close()
        val current = gate.acquireUse()
        old.close()
        assertNull(gate.tryAcquireChange())
        current.close()
        gate.tryAcquireChange()!!.close()
    }

    @Test
    fun `a failed operation relinquishes its mutation lease`() {
        val gate = ModelAccessGate()
        try {
            gate.tryAcquireChange()!!.use { throw IllegalArgumentException("copy failed") }
        } catch (_: IllegalArgumentException) {
            // The old package stays readable after an installation failure.
        }
        gate.acquireUse().close()
        gate.tryAcquireChange()!!.close()
    }

    @Test
    fun `lease ownership is visible to another thread`() {
        val gate = ModelAccessGate()
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.acquireUse().use {
                assertTrue(executor.submit<Boolean> { gate.tryAcquireChange() == null }.get(2, TimeUnit.SECONDS))
            }
            val change = executor.submit<AutoCloseable?> { gate.tryAcquireChange() }.get(2, TimeUnit.SECONDS)
            assertNotNull(change)
            change!!.close()
        } finally {
            executor.shutdownNow()
        }
    }
}
