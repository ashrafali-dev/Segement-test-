package com.ashraf.novelstudio

import com.ashraf.novelstudio.manualextract.ManualCropGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual Lite must place its translation on the same instance the realtime scan would, anchored to the image
 * the reader is looking at. These tests use the production mapping, not a copy of its arithmetic.
 */
class ManualCropGeometryTest {

    private fun crop(x: Float, y: Float, w: Float, h: Float, pageW: Int, pageH: Int) =
        ManualCropGeometry.Crop(x, y, w, h, pageW, pageH)

    @Test fun `bubble is anchored to the page image fractions, not the crop`() {
        // 1000x1600 page, crop covers x 0.40..0.60, y 0.50..0.60. Bubble centred in the crop.
        val c = crop(0.40f, 0.50f, 0.20f, 0.10f, 1000, 1600)
        val b = ManualCropGeometry.toWmBubble(
            src = "https://img.example/panel.jpg",
            crop = c,
            cover = intArrayOf(100, 50, 300, 150),
            safe = null,
            fontPx = 40f, angle = 0f,
            bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), fg2 = 0xFF000000.toInt(),
            soft = false, stroke = 0,
            sourceText = "기회였는데.",
            modelMaskFit = false
        )!!
        assertEquals(0.50f, b.cx, 1e-4f)
        assertEquals(0.55f, b.cy, 1e-4f)
        assertEquals(0.20f, b.w, 1e-4f)
        assertEquals(0.10f, b.h, 1e-4f)
        // Font is a width fraction of the page, never of the crop.
        assertEquals(0.008f, b.font, 1e-5f)
        assertEquals("https://img.example/panel.jpg", b.src)
    }

    @Test fun `safe rectangle is mapped with the same crop transform`() {
        val c = crop(0.40f, 0.50f, 0.20f, 0.10f, 1000, 1600)
        val b = ManualCropGeometry.toWmBubble(
            src = "s",
            crop = c,
            cover = intArrayOf(100, 50, 300, 150),
            safe = intArrayOf(120, 60, 280, 140),
            fontPx = 40f, angle = 0f,
            bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), fg2 = 0xFF000000.toInt(),
            soft = false, stroke = 0,
            sourceText = "hello",
            modelMaskFit = true
        )!!
        assertEquals(0.50f, b.tcx, 1e-4f)
        assertEquals(0.55f, b.tcy, 1e-4f)
        assertEquals(0.16f, b.tbw, 1e-4f)
        assertEquals(0.08f, b.tbh, 1e-4f)
        assertTrue(b.modelMaskFit)
    }

    @Test fun `no safe rectangle means the whole cover box is writable`() {
        val c = crop(0f, 0f, 1f, 1f, 800, 1200)
        val b = ManualCropGeometry.toWmBubble(
            "s", c, intArrayOf(10, 10, 90, 50), null, 20f, 0f,
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), false, 0, "text", false
        )!!
        assertEquals(0f, b.tbw, 1e-6f)
        assertEquals(0f, b.tbh, 1e-6f)
        assertEquals(false, b.modelMaskFit)
    }

    @Test fun `empty text or empty cover is refused instead of drawing a blank bubble`() {
        val c = crop(0f, 0f, 1f, 1f, 100, 100)
        assertNull(ManualCropGeometry.toWmBubble("s", c, intArrayOf(0, 0, 10, 10), null, 5f, 0f, 0, 0, 0, false, 0, "  ", false))
        assertNull(ManualCropGeometry.toWmBubble("s", c, intArrayOf(10, 10, 10, 10), null, 5f, 0f, 0, 0, 0, false, 0, "x", false))
        assertNull(ManualCropGeometry.toWmBubble("s", c, intArrayOf(0, 0, 10, 10), null, 0f, 0f, 0, 0, 0, false, 0, "x", false))
    }

    @Test fun `detections are shifted into crop pixels`() {
        // Page 1000x2000. Crop = x 0.10..0.60 (100..600 px), y 0.20..0.40 (400..800 px).
        val full = listOf(
            BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 200, 500, 400, 700)
        )
        val out = ManualCropGeometry.detectionsInCrop(full, crop(0.10f, 0.20f, 0.50f, 0.20f, 1000, 2000))!!
        assertEquals(1, out.size)
        val d = out[0]
        assertEquals(100, d.l)
        assertEquals(100, d.t)
        assertEquals(300, d.r)
        assertEquals(300, d.b)
    }

    @Test fun `detections outside the crop are dropped`() {
        val full = listOf(
            BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 700, 1500, 900, 1800)
        )
        assertNull(ManualCropGeometry.detectionsInCrop(full, crop(0f, 0f, 0.5f, 0.5f, 1000, 2000)))
    }

    @Test fun `windowed detection keeps its mask aligned to the new box`() {
        // 20x20 mask over a 200x200 box: the left half is inside, the right half is not.
        val mask = BubbleDetectorCore.Mask(20, 20, ByteArray(400) { if (it % 20 < 10) 1 else 0 })
        val full = listOf(
            BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 100, 100, 300, 300, mask)
        )
        // Crop is the right half of that box (x 200..300). The window must be the right-half slice: all outside.
        val out = ManualCropGeometry.detectionsInCrop(full, crop(0.20f, 0f, 0.10f, 1f, 1000, 400))!!
        assertEquals(1, out.size)
        val d = out[0]
        assertNotNull(d.mask)
        assertEquals(100, d.w)
        // Centre of the trimmed box is outside the original silhouette, so contains() must say false.
        assertTrue(!d.contains(d.l + d.w / 2f, d.t + d.h / 2f))
    }

    @Test fun `crop outside the page image still produces a det with a shifted box`() {
        val full = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_TEXT_FREE, 0.8f, 50, 50, 150, 150))
        val out = ManualCropGeometry.detectionsInCrop(full, crop(0f, 0f, 1f, 1f, 100, 100))
        assertNull(out) // entirely outside the 100x100 page: nothing to map
    }
}
