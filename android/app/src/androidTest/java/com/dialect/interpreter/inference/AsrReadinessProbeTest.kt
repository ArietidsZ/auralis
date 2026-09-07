package com.dialect.interpreter.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dialect.interpreter.ModelRuntimeProbe
import com.dialect.interpreter.data.ModelRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lane 4 — Android runtime readiness must be a REAL native load+release,
 * never file-existence or class-loading (the iOS probe's check-exists shortcut
 * is a known issue left to the main reviewer; this pins the Android side).
 */
@RunWith(AndroidJUnit4::class)
class AsrReadinessProbeTest {

    @Test
    fun probeAsr_realLoadAndRelease_trueWithBundle() = runBlocking {
        AsrDeviceFixtures.requireAsrBundle()
        val probe = ModelRuntimeProbe(AsrDeviceFixtures.targetCtx)
        val t0 = System.nanoTime()
        val ready = probe.isRuntimeReady(ModelRepository.PACKAGE_ASR)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("probe must report ready when the real load succeeds", ready)
        assertTrue(
            "probe took ${ms}ms — too fast to have created a real OfflineRecognizer",
            ms > 100,
        )
    }

    @Test
    fun probeAsr_falseWhenDecoderMissing_notFileExistsOnly() = runBlocking {
        AsrDeviceFixtures.requireAsrBundle()
        val decoder = File(
            AsrDeviceFixtures.asrModelDir(), "decoder.int8.onnx"
        )
        val hidden = File(decoder.parentFile, "decoder.int8.onnx.probe-hidden")
        assertTrue(decoder.renameTo(hidden))
        try {
            val probe = ModelRuntimeProbe(AsrDeviceFixtures.targetCtx)
            assertFalse(
                "probe must fail the real load when the decoder is absent",
                probe.isRuntimeReady(ModelRepository.PACKAGE_ASR),
            )
        } finally {
            assertTrue(hidden.renameTo(decoder))
        }
        // Bundle restored: real load passes again.
        assertTrue(
            ModelRuntimeProbe(AsrDeviceFixtures.targetCtx)
                .isRuntimeReady(ModelRepository.PACKAGE_ASR)
        )
    }

    @Test
    fun probeUnknownPackage_false() = runBlocking {
        assertFalse(
            ModelRuntimeProbe(AsrDeviceFixtures.targetCtx)
                .isRuntimeReady("nonexistent-package")
        )
    }
}
