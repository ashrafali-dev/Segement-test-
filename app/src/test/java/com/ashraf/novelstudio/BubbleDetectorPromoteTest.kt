package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The model install step must never leave the user without a working model.
 *
 * The old code deleted the installed model and *then* renamed the new file into place, so any failure
 * of that rename destroyed the previous model. These are file-only tests (no Android, no network).
 */
class BubbleDetectorPromoteTest {
    private fun tempDir(): File = File.createTempFile("koharu-promote", "").let {
        it.delete(); it.mkdirs(); it
    }

    @Test fun promotesNewFileAndRemovesBackup() {
        val dir = tempDir()
        val target = File(dir, "model.onnx").apply { writeText("OLD") }
        val part = File(dir, "model.onnx.part").apply { writeText("NEW") }

        assertTrue(BubbleDetector.promoteVerifiedModel(part, target))
        assertEquals("NEW", target.readText())
        assertFalse("backup must be cleaned up", File(dir, "model.onnx.bak").exists())
        assertFalse("part must be consumed", part.exists())
        dir.deleteRecursively()
    }

    @Test fun installsWhenNoPreviousModelExists() {
        val dir = tempDir()
        val target = File(dir, "model.onnx")
        val part = File(dir, "model.onnx.part").apply { writeText("NEW") }

        assertTrue(BubbleDetector.promoteVerifiedModel(part, target))
        assertEquals("NEW", target.readText())
        dir.deleteRecursively()
    }

    @Test fun missingPartDoesNotDestroyTheWorkingModel() {
        val dir = tempDir()
        val target = File(dir, "model.onnx").apply { writeText("OLD") }
        val part = File(dir, "model.onnx.part") // never created

        assertFalse(BubbleDetector.promoteVerifiedModel(part, target))
        assertTrue("the previous working model must survive a failed install", target.isFile)
        assertEquals("OLD", target.readText())
        dir.deleteRecursively()
    }
}
