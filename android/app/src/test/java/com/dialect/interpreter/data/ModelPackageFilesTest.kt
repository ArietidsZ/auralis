package com.dialect.interpreter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModelPackageFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun weight(path: String, content: String): File = File(temp.root, "$path/model.bin").also {
        it.parentFile!!.mkdirs()
        it.writeText(content)
    }

    @Test
    fun `a verified package replaces the old directory and clears backup`() {
        weight("asr", "old")
        weight(".staging/asr", "new")
        ModelPackageFiles(temp.root).activate("asr")
        assertEquals("new", File(temp.root, "asr/model.bin").readText())
        assertFalse(File(temp.root, ".backup/asr").exists())
        assertFalse(File(temp.root, ".staging/asr").exists())
    }

    @Test
    fun `failure activating a replacement restores the old package`() {
        weight("asr", "old")
        weight(".staging/asr", "new")
        val files = ModelPackageFiles(temp.root) { source, target ->
            if (source.parentFile!!.name == ".staging") false else source.renameTo(target)
        }
        try {
            files.activate("asr")
            fail("expected a failed activation")
        } catch (_: IOException) {
            assertEquals("old", File(temp.root, "asr/model.bin").readText())
        }
    }

    @Test
    fun `crash between renames restores backup before removing partial staging`() {
        weight(".backup/asr", "old")
        weight(".staging/asr", "partial")
        ModelPackageFiles(temp.root).recover("asr")
        assertEquals("old", File(temp.root, "asr/model.bin").readText())
        assertFalse(File(temp.root, ".staging/asr").exists())
    }

    @Test
    fun `a failed rollback preserves backup for the next recovery`() {
        weight("asr", "old")
        weight(".staging/asr", "new")
        val failing = ModelPackageFiles(temp.root) { source, target ->
            if (source.name == "asr" && source.parentFile == temp.root) source.renameTo(target) else false
        }
        try {
            failing.activate("asr")
            fail("expected a failed rollback")
        } catch (_: IOException) {
            assertEquals("old", File(temp.root, ".backup/asr/model.bin").readText())
        }
        ModelPackageFiles(temp.root).recover("asr")
        assertEquals("old", File(temp.root, "asr/model.bin").readText())
    }

    @Test
    fun `failed recovery never removes the only complete backup`() {
        weight(".backup/asr", "old")
        val files = ModelPackageFiles(temp.root) { _, _ -> false }
        try {
            files.recover("asr")
            fail("expected recovery failure")
        } catch (_: IOException) {
            assertTrue(File(temp.root, ".backup/asr/model.bin").isFile)
        }
    }
}
