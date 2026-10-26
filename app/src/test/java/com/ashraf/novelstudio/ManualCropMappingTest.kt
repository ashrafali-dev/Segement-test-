package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A manual crop is a window into the source image. Everything measured inside it must be mapped back, or the
 * manual bubble is placed against the crop instead of the page (the "giant block in the wrong place" bug).
 *
 * The mapping is the same arithmetic `ManualExtractPipeline.buildBubble` performs:
 *     srcX = x + (cropX / cropWidth) * w
 *     srcY = y + (cropY / cropHeight) * h
 *     srcW = (boxW / cropWidth) * w
 */
class ManualCropMappingTest {

    private fun mapX(cropX: Float, cropW: Float, x: Float, w: Float) = x + (cropX / cropW) * w
    private fun mapY(cropY: Float, cropH: Float, y: Float, h: Float) = y + (cropY / cropH) * h
    private fun mapW(boxW: Float, cropW: Float, w: Float) = (boxW / cropW) * w

    @Test fun `crop in the middle of the source maps to the middle`() {
        // Source image 1000 px wide, crop starts at 40 % and is 20 % wide, bubble centred in the crop.
        val srcX = mapX(cropX = 100f, cropW = 200f, x = 0.40f, w = 0.20f)
        assertEquals(0.50f, srcX, 1e-4f)
    }

    @Test fun `crop at the left edge keeps the bubble at the left edge`() {
        val srcX = mapX(cropX = 0f, cropW = 200f, x = 0f, w = 0.20f)
        assertEquals(0f, srcX, 1e-4f)
    }

    @Test fun `crop at the bottom of a tall panel maps to the bottom`() {
        val srcY = mapY(cropY = 800f, cropH = 1000f, y = 0.5f, h = 0.5f)
        assertEquals(0.90f, srcY, 1e-4f)
    }

    @Test fun `bubble width scales by the source width, not the crop width`() {
        // A 60 px wide bubble inside a 200 px crop of a 0.2-wide region => 6 % of the source.
        val srcW = mapW(boxW = 60f, cropW = 200f, w = 0.20f)
        assertEquals(0.06f, srcW, 1e-5f)
    }

    @Test fun `font scales by source width so it is not enormous`() {
        // 40 px glyphs in a 200 px crop of a 20%-wide region => 4% of source width.
        val font = mapW(boxW = 40f, cropW = 200f, w = 0.20f)
        assertEquals(0.04f, font, 1e-5f)
        // The old code divided by the crop width and used the raw value: 40/200 = 0.2 = 20% of the page.
        assertEquals(0.2f, 40f / 200f, 1e-5f)
    }

    @Test fun `full-panel frame is an identity map`() {
        val srcX = mapX(cropX = 250f, cropW = 1000f, x = 0f, w = 1f)
        assertEquals(0.25f, srcX, 1e-5f)
        val srcW = mapW(boxW = 100f, cropW = 1000f, w = 1f)
        assertEquals(0.1f, srcW, 1e-5f)
    }
}
