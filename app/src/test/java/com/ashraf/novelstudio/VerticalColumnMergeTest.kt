package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC tests for tategaki column merging in OcrPost.mergeNeighbours (no bitmap: geometry only). */
class VerticalColumnMergeTest {
    private fun col(text: String, cx: Float, cy: Float, w: Float, h: Float) =
        OcrBlock(text = text, top = (cy - h / 2).toInt(), left = (cx - w / 2).toInt(), cx = cx, cy = cy,
            w = w, h = h, angle = 0f, fontPx = w * 0.9f, vertical = true)

    private fun row(text: String, cx: Float, cy: Float, w: Float, h: Float) =
        OcrBlock(text = text, top = (cy - h / 2).toInt(), left = (cx - w / 2).toInt(), cx = cx, cy = cy,
            w = w, h = h, angle = 0f, fontPx = h * 0.9f, vertical = false)

    @Test fun twoColumnsOfOneBubbleBecomeOneBlockReadRightToLeft() {
        // like the "みんな" / "…な!" bubble: big right column, smaller left column
        val merged = OcrPost.mergeNeighbours(listOf(col("…な!", 150f, 625f, 20f, 36f), col("みんな", 183f, 629f, 28f, 78f)))
        assertEquals(1, merged.size)
        assertEquals("みんな…な!", merged[0].text)
        assertTrue(merged[0].vertical)
    }

    @Test fun farApartColumnsStaySeparate() {
        val merged = OcrPost.mergeNeighbours(listOf(col("あ", 500f, 100f, 26f, 80f), col("い", 100f, 700f, 26f, 80f)))
        assertEquals(2, merged.size)
    }

    @Test fun columnsWithoutSharedHeightStaySeparate() {
        assertFalse(OcrPost.joinableColumns(col("あ", 300f, 100f, 26f, 60f), col("い", 270f, 400f, 26f, 60f)))
    }

    @Test fun veryDifferentGlyphSizeIsNotOneBubble() {
        assertFalse(OcrPost.joinableColumns(col("あ", 300f, 100f, 80f, 200f), col("い", 240f, 100f, 14f, 60f)))
    }

    @Test fun verticalAndHorizontalNeverJoin() {
        val merged = OcrPost.mergeNeighbours(listOf(col("あ", 300f, 100f, 26f, 80f), row("ABC", 250f, 100f, 90f, 22f)))
        assertEquals(2, merged.size)
    }

    @Test fun threeColumnsChainIntoOneBlock() {
        val merged = OcrPost.mergeNeighbours(listOf(
            col("三", 120f, 300f, 24f, 90f), col("二", 150f, 300f, 24f, 90f), col("一", 180f, 300f, 24f, 90f)))
        assertEquals(1, merged.size)
        assertEquals("一二三", merged[0].text)
    }
}
