package com.ashraf.novelstudio

import com.ashraf.novelstudio.manualextract.ManualExtractConfig
import com.ashraf.novelstudio.manualextract.ManualExtractOcr
import com.ashraf.novelstudio.manualextract.isBetterThan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual "Scan / Select Missing Text" is a RECOVERY action, not a realtime frame.
 *
 * The user marked a region because the normal read missed or mangled something. A retry therefore has to
 * happen whenever the first pass is empty OR clearly incomplete, and it must never replace a good read with a
 * worse one, nor report an incomplete pass as a success.
 */
class ManualScanRecoveryTest {

    private fun block(text: String, left: Int = 0, top: Int = 0) =
        OcrBlock(text = text, top = top, left = left, cx = left + 20f, cy = top + 10f, w = 40f, h = 20f)

    private fun outcome(vararg texts: String, engine: String = "test") =
        ManualExtractOcr.Outcome(texts.map { block(it) }, engine)

    @Test fun `letters counts only real glyphs`() {
        assertEquals(4, outcome("??ab!!", "cd").letters)
        assertEquals(0, outcome("...", "!!").letters)
    }

    @Test fun `an empty read carries no glyphs to keep`() {
        assertEquals(0, outcome().letters)
        assertTrue(outcome().blocks.isEmpty())
    }

    @Test fun `a fuller read wins over a partial one`() {
        val partial = outcome("THE ROPE")
        val full = outcome("THE ROPE IS HARD TO UNTIE")
        assertTrue(full.isBetterThan(partial))
        assertFalse(partial.isBetterThan(full))
    }

    @Test fun `a retry never replaces a good read with a worse one`() {
        val good = outcome("A LONGER CORRECT LINE")
        val worse = outcome("A LONG")
        assertFalse(worse.isBetterThan(good))
        // Equal text: the reading that found more lines (a multi-line bubble) wins.
        val oneLine = outcome("HELLO THERE FRIEND")
        val twoLines = outcome("HELLO", "THERE FRIEND")
        assertTrue(twoLines.isBetterThan(oneLine))
    }

    @Test fun `an empty retry never replaces a working read`() {
        val good = outcome("TEXT")
        assertFalse(outcome().isBetterThan(good))
    }

    @Test fun `manual scan keeps remote-engine resolution limits separate from local`() {
        // A crop bigger than the remote upload limit must still be read locally at full detail rather than
        // being silently shrunk for the network.
        assertTrue("local cap must exceed the remote cap",
            ManualExtractConfig.MAX_OCR_DIM > ManualExtractConfig.MAX_REMOTE_DIM)
        assertTrue("mark padding exists for the first read", ManualExtractConfig.OCR_PAD_FIRST > 0f)
        assertTrue("the retry pads more than the first read",
            ManualExtractConfig.OCR_PAD_RETRY > ManualExtractConfig.OCR_PAD_FIRST)
        assertTrue("a user-marked region gets more time than one realtime frame",
            ManualExtractConfig.OCR_CAP_MS >= 3000L)
    }
}
