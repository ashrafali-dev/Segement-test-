package com.ashraf.novelstudio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Background reconstruction for text removal.
 *
 * The old cleaner painted the whole balloon interior with one flat colour and treated every pixel that merely
 * differed from the paper colour as lettering. On a coloured panel, a gradient or a dark balloon that erased the
 * background too and left a flat rectangle. These tests pin the replacement: the background is sampled from the
 * ring around the glyphs and restored with its own local colour, never with a single average.
 */
class TextRemovalBackgroundTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Build a region image: a solid or gradient background, with an opaque ink block in the middle. */
    private fun region(
        w: Int, h: Int,
        bg: (x: Int, y: Int) -> Int,
        ink: Int,
        inkBox: IntArray
    ): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val inside = x >= inkBox[0] && x < inkBox[2] && y >= inkBox[1] && y < inkBox[3]
        if (inside) ink else bg(x, y)
    }

    private fun near(a: Int, b: Int, tol: Int = 12): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol

    @Test fun `flat white balloon is rebuilt as white`() {
        val white = argb(255, 255, 255)
        val ink = argb(20, 20, 20)
        val img = region(120, 120, { _, _ -> white }, ink, intArrayOf(40, 40, 80, 80))
        val m = WebModeTextRemover.estimateBackgroundModel(img, 120, 120, 40, 40, 80, 80, ink, ink)
        assertNotNull(m)
        assertTrue("expected a flat model", m!!.uniform)
        assertTrue(near(m.at(60, 60, 40, 80, 40, 80), white))
    }

    @Test fun `pale green balloon keeps its own colour, not white`() {
        val paleGreen = argb(200, 240, 205)
        val ink = argb(30, 60, 40)
        val img = region(120, 120, { _, _ -> paleGreen }, ink, intArrayOf(40, 40, 80, 80))
        val m = WebModeTextRemover.estimateBackgroundModel(img, 120, 120, 40, 40, 80, 80, ink, ink)
        assertNotNull(m)
        val restored = m!!.at(60, 60, 40, 80, 40, 80)
        assertTrue("must stay pale green, was ${Integer.toHexString(restored)}", near(restored, paleGreen))
        assertTrue("must not turn white", !near(restored, argb(255, 255, 255), 20))
    }

    @Test fun `dark balloon keeps its black background under white lettering`() {
        val black = argb(12, 12, 14)
        val white = argb(250, 250, 250)
        val img = region(120, 120, { _, _ -> black }, white, intArrayOf(40, 40, 80, 80))
        val m = WebModeTextRemover.estimateBackgroundModel(img, 120, 120, 40, 40, 80, 80, white, white)
        assertNotNull(m)
        val restored = m!!.at(60, 60, 40, 80, 40, 80)
        assertTrue("dark balloon must stay dark", near(restored, black, 14))
    }

    @Test fun `vertical gradient is estimated as a ramp, not one average colour`() {
        // Dark lettering on a coloured panel that fades top to bottom. Dark ink is what a real balloon uses: an
        // ink colour that itself matched the panel gradient would be genuinely ambiguous and is not the case
        // this model claims to handle.
        val ink = argb(18, 18, 22)
        val img = region(140, 140, { _, y ->
            val t = y / 139f
            argb((220 * (1f - t) + 30 * t).toInt(), 20, (20 * (1f - t) + 210 * t).toInt())
        }, ink, intArrayOf(50, 50, 90, 90))
        val m = WebModeTextRemover.estimateBackgroundModel(img, 140, 140, 50, 50, 90, 90, ink, ink)
        assertNotNull(m)
        assertTrue("a ramp must not be collapsed to one colour", !m!!.uniform)
        val top = m.at(70, 52, 50, 90, 50, 90)
        val bottom = m.at(70, 88, 50, 90, 50, 90)
        // The two ends of the ramp must differ, and each must match the real background nearby.
        assertTrue("ends must differ", (!near(top, bottom, 24)))
        assertTrue("top end follows the panel", near(top, img[52 * 140 + 70], 26))
        assertTrue("bottom end follows the panel", near(bottom, img[88 * 140 + 70], 26))
    }

    @Test fun `artwork with no dominant background yields no model`() {
        // Every pixel a different colour: a photograph, not a balloon.
        val img = IntArray(120 * 120) { i -> argb(i * 7 % 256, i * 13 % 256, i * 29 % 256) }
        assertNull(WebModeTextRemover.estimateBackgroundModel(img, 120, 120, 40, 40, 80, 80, argb(0, 0, 0), argb(0, 0, 0)))
    }
}
