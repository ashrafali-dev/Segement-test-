package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSegCoreAdjacentSplitTest {
    @Test fun touchingRoundLobesAreSplitWithoutChangingTheirPixels() {
        val seg = maskSeg(60, 30) { x, y ->
            val left = (x - 15) * (x - 15) + (y - 15) * (y - 15) <= 14 * 14
            val right = (x - 44) * (x - 44) + (y - 15) * (y - 15) <= 14 * 14
            left || right
        }
        val result = BubbleSegCore.splitAdjacentRoundSegments(listOf(seg))
        assertEquals(2, result.segments.size)
        assertEquals(1, result.splits.size)
        assertEquals("x", result.splits.single().axis)
        assertTrue(result.splits.single().valleyRatio <= 0.48f)
        assertTrue(result.segments[0].r <= result.segments[1].l)
        assertTrue(result.segments.all { it.w in 25..31 && it.h in 25..31 })
    }

    @Test fun aFlatElongatedBoxIsNotSplitAsTwoBubbles() {
        val seg = maskSeg(60, 30) { x, y -> x in 2..57 && y in 10..19 }
        val result = BubbleSegCore.splitAdjacentRoundSegments(listOf(seg))
        assertEquals(1, result.segments.size)
        assertTrue(result.splits.isEmpty())
        assertTrue(result.merges.isEmpty())
    }

    private fun maskSeg(w: Int, h: Int, contains: (Int, Int) -> Boolean): BubbleSegCore.Seg {
        val bits = ByteArray((w * h + 7) / 8)
        for (y in 0 until h) for (x in 0 until w) {
            if (!contains(x, y)) continue
            val at = y * w + x
            bits[at ushr 3] = (bits[at ushr 3].toInt() or (1 shl (at and 7))).toByte()
        }
        return BubbleSegCore.Seg(0.9f, 0, 0, w, h, bits)
    }
}
