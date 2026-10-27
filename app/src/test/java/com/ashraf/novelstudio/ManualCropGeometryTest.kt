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

    private fun crop(x: Float, y: Float, w: Float, h: Float, cropW: Int, cropH: Int, pageW: Int = cropW, pageH: Int = cropH) =
        ManualCropGeometry.Crop(x, y, w, h, cropW, cropH, pageW, pageH)

    private fun bubble(
        crop: ManualCropGeometry.Crop,
        cover: IntArray,
        safe: IntArray? = null,
        fontPx: Float = 40f,
        text: String = "기회였는데."
    ) = ManualCropGeometry.toWmBubble(
        src = "https://img.example/panel.jpg",
        crop = crop,
        cover = cover,
        safe = safe,
        fontPx = fontPx, angle = 0f,
        bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), fg2 = 0xFF000000.toInt(),
        soft = false, stroke = 0,
        sourceText = text,
        modelMaskFit = safe != null
    )

    @Test fun `bubble is anchored to the page image fractions, not the crop`() {
        // Crop covers x 0.40..0.60, y 0.50..0.60 of the page. Its bitmap is 200x160 px; the bubble is centred.
        val c = crop(0.40f, 0.50f, 0.20f, 0.10f, cropW = 200, cropH = 160)
        val b = bubble(c, intArrayOf(0, 0, 200, 160))!!
        assertEquals(0.50f, b.cx, 1e-4f)
        assertEquals(0.55f, b.cy, 1e-4f)
        assertEquals(0.20f, b.w, 1e-4f)
        assertEquals(0.10f, b.h, 1e-4f)
        // Font is a width fraction of the page: 40 px of a 200 px crop-wide 0.20 region.
        assertEquals(0.04f, b.font, 1e-5f)
        assertEquals("https://img.example/panel.jpg", b.src)
    }

    @Test fun `crop decoded smaller than the page still maps to the same page fractions`() {
        // Page 1000 px wide, crop x 0.50..0.60 (100 page px) but the crop bitmap is only 50 px wide.
        val c = crop(0.50f, 0f, 0.10f, 1f, cropW = 50, cropH = 800, pageW = 1000, pageH = 800)
        val b = bubble(c, intArrayOf(0, 0, 50, 800))!!
        assertEquals(0.55f, b.cx, 1e-4f)
        assertEquals(0.10f, b.w, 1e-4f)
    }

    @Test fun `safe rectangle is mapped with the same crop transform`() {
        val c = crop(0.40f, 0.50f, 0.20f, 0.10f, cropW = 200, cropH = 160)
        val b = bubble(c, intArrayOf(0, 0, 200, 160), safe = intArrayOf(20, 10, 180, 150))!!
        assertEquals(0.50f, b.tcx, 1e-4f)
        assertEquals(0.55f, b.tcy, 1e-4f)
        assertEquals(0.16f, b.tbw, 1e-4f)
        assertEquals(0.0875f, b.tbh, 1e-3f)
        assertTrue(b.modelMaskFit)
    }

    @Test fun `no safe rectangle means the whole cover box is writable`() {
        val b = bubble(crop(0f, 0f, 1f, 1f, 800, 1200), intArrayOf(10, 10, 90, 50))!!
        assertEquals(0f, b.tbw, 1e-6f)
        assertEquals(0f, b.tbh, 1e-6f)
        assertEquals(false, b.modelMaskFit)
    }

    @Test fun `empty text or empty cover is refused instead of drawing a blank bubble`() {
        val c = crop(0f, 0f, 1f, 1f, 100, 100)
        assertNull(bubble(c, intArrayOf(0, 0, 10, 10), text = "  "))
        assertNull(bubble(c, intArrayOf(10, 10, 10, 10)))
        assertNull(bubble(c, intArrayOf(0, 0, 10, 10), fontPx = 0f))
    }

    @Test fun `detections are shifted into crop pixels`() {
        // Region x 0.10..0.60, y 0.20..0.40 of a 1000x2000 page = 500x400 page px, decoded at full size.
        val c = crop(0.10f, 0.20f, 0.50f, 0.20f, cropW = 500, cropH = 400, pageW = 1000, pageH = 2000)
        val full = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 200, 500, 400, 700))
        val d = ManualCropGeometry.detectionsInCrop(full, c)!!.single()
        // Crop origin in page px is (100, 400), so the box is 100..300 / 100..300 inside the crop.
        assertEquals(100, d.l)
        assertEquals(100, d.t)
        assertEquals(300, d.r)
        assertEquals(300, d.b)
    }

    @Test fun `downscaled crop scales the detector offset, not just subtracts it`() {
        // Same 500x400 page region, but the crop bitmap is half size: 250x200.
        val c = crop(0.10f, 0.20f, 0.50f, 0.20f, cropW = 250, cropH = 200, pageW = 1000, pageH = 2000)
        val full = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 200, 500, 400, 700))
        val d = ManualCropGeometry.detectionsInCrop(full, c)!!.single()
        // Page offset (100,400) becomes crop offset (50,40) after the 0.5 scale; size 200x200 -> 100x100.
        assertEquals(50, d.l)
        assertEquals(40, d.t)
        assertEquals(150, d.r)
        assertEquals(140, d.b)
    }

    @Test fun `detections outside the crop are dropped`() {
        val c = crop(0f, 0f, 0.5f, 0.5f, cropW = 500, cropH = 1000, pageW = 1000, pageH = 2000)
        val full = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 700, 1500, 900, 1800))
        assertNull(ManualCropGeometry.detectionsInCrop(full, c))
    }

    @Test fun `windowed detection keeps its mask aligned to the new box`() {
        // 20x20 mask over a 200x200 box: the left half is inside, the right half is not.
        val mask = BubbleDetectorCore.Mask(20, 20, ByteArray(400) { if (it % 20 < 10) 1 else 0 })
        val full = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 100, 100, 300, 300, mask))
        // Crop x is the right half of that box (page 200..300) on a 1000-wide page, full height 400.
        // Decoded at 100x400 px, so page px -> crop px is 0.1 across and 1 vertically.
        val d = ManualCropGeometry.detectionsInCrop(full, crop(0.20f, 0f, 0.10f, 1f, 100, 400, 1000, 400))!!.single()
        assertNotNull(d.mask)
        // 100 page px wide clipped, scaled by 0.1 -> 10 crop px.
        assertEquals(10, d.w)
        assertTrue(d.h > 0)
        // The kept slice is the RIGHT half of the silhouette, which was all outside the mask.
        assertTrue(!d.contains(d.l + d.w / 2f, d.t + d.h / 2f))
    }

    @Test fun `partly overlapping detection is clipped to the crop`() {
        // A 50..150 box on a 100 px page overlaps the crop 50..100, so it must be kept, clipped, not dropped.
        val full = listOf(BubbleDetectorCore.Det(BubbleDetectorCore.CLS_TEXT_FREE, 0.8f, 50, 50, 150, 150))
        val d = ManualCropGeometry.detectionsInCrop(full, crop(0f, 0f, 1f, 1f, 100, 100, 100, 100))!!.single()
        assertEquals(50, d.l)
        assertEquals(100, d.r)
    }

    @Test fun `no detections means pixel grouping, not an empty result`() {
        assertNull(ManualCropGeometry.detectionsInCrop(emptyList(), crop(0f, 0f, 1f, 1f, 100, 100)))
        assertNull(ManualCropGeometry.detectionsInCrop(null, crop(0f, 0f, 1f, 1f, 100, 100)))
    }
}
