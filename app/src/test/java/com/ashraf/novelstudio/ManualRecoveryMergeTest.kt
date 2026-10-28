package com.ashraf.novelstudio

import com.ashraf.novelstudio.manualextract.ManualCropGeometry
import com.ashraf.novelstudio.manualextract.ManualExtractOcr
import com.ashraf.novelstudio.manualextract.isBetterThan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Missing-line recovery: several missed lines from ONE selection, a whisper kept out of a neighbouring bubble,
 * and source coordinates that survive cropping + resizing.
 */
class ManualRecoveryMergeTest {

    private fun block(text: String, cx: Float, cy: Float, w: Float = 160f, h: Float = 24f) =
        OcrBlock(
            text = text,
            top = (cy - h / 2).toInt(), left = (cx - w / 2).toInt(),
            cx = cx, cy = cy, w = w, h = h, fontPx = h
        )

    @Test fun `one selection recovers several missed lines at their own coordinates`() {
        // A two-line bubble: the first read only found the top line, the recovery found both, plus a third line
        // on the same balloon. Every line must keep its own box rather than collapsing into one block.
        val partial = ManualExtractOcr.Outcome(listOf(block("THE ROPE IS", 120f, 100f)), "Lens")
        val recovered = ManualExtractOcr.Outcome(
            listOf(
                block("THE ROPE IS", 120f, 100f),
                block("HARD TO UNTIE", 120f, 130f),
                block("SO NOBODY MOVES", 120f, 160f)
            ),
            "Lens"
        )
        assertTrue("the fuller read must win", recovered.isBetterThan(partial))
        assertEquals("all recovered lines are kept", 3, recovered.blocks.size)
        assertEquals(
            "each line keeps its own vertical coordinate",
            listOf(100f, 130f, 160f),
            recovered.blocks.map { it.cy }
        )
    }

    @Test fun `a whisper outside the balloon is not merged into it`() {
        val bubble = BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 40, 40, 260, 240)
        val whisper = BubbleDetectorCore.Det(BubbleDetectorCore.CLS_TEXT_FREE, 0.8f, 300, 60, 380, 90)
        val dets = listOf(bubble, whisper)
        val line = block("MAIN BUBBLE", 150f, 140f)
        val small = block("psst", 340f, 75f, w = 60f, h = 14f)

        // The whisper is free lettering, not part of the balloon.
        assertFalse(BubbleDetectorCore.isFreeText(dets, line.cx, line.cy))
        assertTrue(BubbleDetectorCore.isFreeText(dets, small.cx, small.cy))
        // Grouping is what actually keeps them apart: one balloon group and one free group, never merged into a
        // single bubble. (inDifferentBubbles only answers "two DIFFERENT bubbles", so it must stay false here —
        // the whisper is in no bubble at all.)
        val groups = BubbleBuilder.group(listOf(line, small), dets)
        assertEquals("the whisper must not merge into the balloon", 2, groups.size)
        assertTrue("the balloon group is not free", groups.any { !it.free && it.text.contains("MAIN") })
        assertTrue("the whisper group is free", groups.any { it.free && it.text.contains("psst") })
        val balloon = groups.first { !it.free }
        assertFalse("the balloon box must not swallow the whisper", balloon.r > small.left)
    }

    @Test fun `source coordinates survive crop and resize`() {
        // A crop of a 1000x2000 page: region 40 %..60 % wide, 10 %..30 % tall, decoded at 400x500.
        val crop = ManualCropGeometry.Crop(
            x = 0.40f, y = 0.10f, w = 0.20f, h = 0.20f,
            cropW = 400, cropH = 400, pageW = 1000, pageH = 2000
        )
        // A line at the centre of the crop is the centre of that region of the page.
        assertEquals(0.50f, crop.fx(200f), 1e-4f)
        assertEquals(0.20f, crop.fy(200f), 1e-4f)
        // Its width scales by the region's share of the page, not by the crop's pixel width.
        assertEquals(0.05f, crop.fw(100f), 1e-4f)
        // pageL/pageT convert crop pixels to the PAGE pixel frame the detector works in.
        assertEquals(500, crop.pageL(100f))   // 0.40*1000 + 100 px of the region
        assertEquals(300, crop.pageT(100f))   // 0.10*2000 + 100 px
    }

    @Test fun `a downscaled crop keeps the same source mapping`() {
        val hi = ManualCropGeometry.Crop(0.2f, 0.2f, 0.4f, 0.4f, cropW = 800, cropH = 800, pageW = 2000, pageH = 2000)
        val lo = hi.copy(cropW = 400, cropH = 400)
        // The same FEATURE, at half the crop resolution, must map to the same page fraction.
        assertEquals(hi.fx(400f), lo.fx(200f), 1e-4f)
        assertEquals(hi.fy(400f), lo.fy(200f), 1e-4f)
        assertEquals(hi.fw(80f), lo.fw(40f), 1e-4f)
    }

    @Test fun `detections outside the crop are dropped and inside ones are windowed`() {
        val full = 2000
        val crop = ManualCropGeometry.Crop(0.5f, 0.5f, 0.25f, 0.25f, cropW = 500, cropH = 500, pageW = full, pageH = full)
        val inside = BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 1100, 1100, 1300, 1300)
        val outside = BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 100, 100, 300, 300)
        val kept = ManualCropGeometry.detectionsInCrop(listOf(inside, outside), crop)!!
        assertEquals(1, kept.size)
        val k = 500f / (0.25f * full)   // crop px per page px
        assertEquals(((1100 - 1000) * k).toInt(), kept[0].l)
        assertEquals(((1300 - 1000) * k).toInt(), kept[0].r)
        assertNotEquals("the outside bubble must not be re-anchored into the crop", 0, kept[0].l)
    }
}
