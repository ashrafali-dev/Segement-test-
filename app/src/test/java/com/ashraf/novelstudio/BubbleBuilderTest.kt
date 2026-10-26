package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grouping used by every mode; these cases cover the shapes that used to break it. */
class BubbleBuilderTest {

    private fun block(text: String, cx: Float, cy: Float, w: Float = 100f, h: Float = 20f, vertical: Boolean = false) =
        OcrBlock(text = text, top = (cy - h / 2).toInt(), left = (cx - w / 2).toInt(),
            cx = cx, cy = cy, w = w, h = h, fontPx = h, vertical = vertical)

    @Test fun `two lines in one detector bubble become one group`() {
        val dets = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 100, 100, 400, 300))
        val blocks = listOf(
            block("first", 250f, 160f),
            block("second", 250f, 200f)
        )
        val groups = BubbleBuilder.group(blocks, dets)
        assertEquals(1, groups.size)
        assertTrue(groups[0].text.contains("first"))
        assertTrue(groups[0].text.contains("second"))
        assertEquals(100, groups[0].l)
        assertEquals(400, groups[0].r)
    }

    @Test fun `lines in different detector bubbles stay separate`() {
        val dets = listOf(
            BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 0, 0, 200, 200),
            BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 300, 0, 500, 200)
        )
        val groups = BubbleBuilder.group(listOf(block("left", 100f, 100f), block("right", 400f, 100f)), dets)
        assertEquals(2, groups.size)
    }

    @Test fun `detector text_free lettering is its own group and marked free`() {
        val dets = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_TEXT_FREE, 0.8f, 0, 400, 200, 460))
        val groups = BubbleBuilder.group(listOf(block("BOOM", 100f, 430f)), dets)
        assertEquals(1, groups.size)
        assertTrue(groups[0].free)
    }

    @Test fun `without a detector nearby lines still group by pixels`() {
        val groups = BubbleBuilder.group(listOf(block("a", 200f, 100f), block("b", 200f, 126f)), null)
        assertEquals(1, groups.size)
    }

    @Test fun `vertical japanese columns group instead of fragmenting`() {
        val groups = BubbleBuilder.groupByPixel(
            listOf(
                block("あ", 300f, 100f, w = 24f, h = 90f, vertical = true),
                block("い", 300f, 200f, w = 24f, h = 90f, vertical = true)
            )
        )
        assertEquals(1, groups.size)
    }

    @Test fun `group text joins lines in reading order`() {
        val dets = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 0, 0, 500, 400))
        val groups = BubbleBuilder.group(
            listOf(block("second", 250f, 220f), block("first", 250f, 160f)), dets
        )
        assertEquals("first second", groups[0].text)
        assertFalse(groups[0].free)
    }

    @Test fun `block outside every detection falls back to its own box`() {
        val dets = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 0, 0, 100, 100))
        val groups = BubbleBuilder.group(listOf(block("far", 800f, 800f)), dets)
        assertEquals(1, groups.size)
        assertTrue(groups[0].l > 700)
    }
}
