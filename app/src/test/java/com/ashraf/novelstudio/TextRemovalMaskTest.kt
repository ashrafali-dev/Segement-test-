package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Shape-preserving text removal.
 *
 * The model's balloon instance mask is a SILHOUETTE. Filling every pixel it covers repainted the whole balloon
 * with one colour, which is the box-shaped patch and the damaged outline reported on curved, shaded, coloured
 * and outlined balloons. These tests pin the replacement: only pixels that disagree with the balloon's own paper
 * colour are erased, inside the silhouette only, and each one is restored from its local background.
 */
class TextRemovalMaskTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun near(a: Int, b: Int, tol: Int = 12): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    @Test fun `only glyph pixels are marked, never the balloon shading`() {
        val paper = argb(250, 250, 245)
        val shade = argb(232, 232, 226)   // the balloon's own shading, NOT lettering
        val ink = argb(20, 20, 20)
        val w = 80; val h = 80
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                y < 20 -> shade
                x in 30..49 && y in 30..49 -> ink
                else -> paper
            }
        }
        val mask = TextRemovalMask.inkMask(img, w, h, 20, 20, 60, 60, paper)
        assertNotNull(mask)
        // The glyph block is marked.
        assertTrue("glyph must be erased", mask!![35 * w + 35].toInt() != 0)
        // The shading band is untouched: it is not the paper colour, but it is not lettering either.
        assertEquals("shading must survive", 0, mask[5 * w + 5].toInt())
        // And so is clean paper.
        assertEquals(0, mask[70 * w + 70].toInt())
    }

    @Test fun `the model silhouette bounds what is erased`() {
        val paper = argb(255, 255, 255)
        val ink = argb(0, 0, 0)
        val w = 60; val h = 60
        // Ink in two places: inside the balloon (left) and a whisper outside it (right).
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x in 10..19 && y in 20..29 -> ink
                x in 40..49 && y in 20..29 -> ink
                else -> paper
            }
        }
        val insideBalloon = { x: Int, _: Int -> x < 30 }
        val mask = TextRemovalMask.inkMask(img, w, h, 5, 15, 55, 35, paper, insideBalloon)
        assertNotNull(mask)
        assertTrue("inside-balloon text is erased", mask!![25 * w + 15].toInt() != 0)
        assertEquals("whisper text outside the silhouette is kept", 0, mask[25 * w + 45].toInt())
    }

    @Test fun `a box that is all ink is refused instead of wiped`() {
        val ink = argb(10, 10, 10)
        val img = IntArray(40 * 40) { ink }
        assertNull("artwork must not be treated as lettering",
            TextRemovalMask.inkMask(img, 40, 40, 0, 0, 40, 40, argb(250, 250, 250)))
    }

    @Test fun `an empty box is not an erase`() {
        val paper = argb(255, 255, 255)
        val img = IntArray(40 * 40) { paper }
        assertNull(TextRemovalMask.inkMask(img, 40, 40, 0, 0, 40, 40, paper))
    }

    @Test fun `restored glyphs take the balloon paper, not a white rectangle`() {
        val paleGreen = argb(200, 240, 205)
        val ink = argb(25, 25, 25)
        val w = 70; val h = 70
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x in 25..44 && y in 25..44) ink else paleGreen
        }
        val mask = TextRemovalMask.inkMask(img, w, h, 10, 10, 60, 60, paleGreen)!!
        val out = TextRemovalMask.restoreFromSurroundings(mask, img, w, h)
        assertTrue("glyph pixel restored to the balloon colour: ${hex(out[35 * w + 35])}",
            near(out[35 * w + 35], paleGreen))
        assertTrue("must not become white", !near(out[35 * w + 35], argb(255, 255, 255), 20))
    }

    @Test fun `a vertical gradient under the text is continued, not flattened`() {
        val ink = argb(0, 0, 0)
        val w = 90; val h = 90
        fun panel(y: Int): Int {
            val t = (y / (h - 1f)).coerceIn(0f, 1f)
            return argb((240 - 180 * t).toInt(), (240 - 180 * t).toInt(), (245 - 180 * t).toInt())
        }
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x in 30..59 && y in 30..59) ink else panel(y)
        }
        val paper = panel(45)
        val mask = TextRemovalMask.inkMask(img, w, h, 15, 15, 75, 75, paper)!!
        val out = TextRemovalMask.restoreFromSurroundings(mask, img, w, h)
        // Inside the glyph block the ramp must follow the panel above and below it.
        assertTrue("top of the block follows the panel: ${hex(out[32 * w + 45])} vs ${hex(panel(32))}",
            near(out[32 * w + 45], panel(32), 18))
        assertTrue("bottom of the block follows the panel: ${hex(out[57 * w + 45])} vs ${hex(panel(57))}",
            near(out[57 * w + 45], panel(57), 18))
        assertTrue("the two ends must differ", !near(out[32 * w + 45], out[57 * w + 45], 20))
    }

    @Test fun `a left to right ramp is not called uniform`() {
        val w = 140; val h = 120
        val ink = argb(15, 15, 15)
        fun panel(x: Int): Int {
            val t = (x / (w - 1f)).coerceIn(0f, 1f)
            return argb((230 - 190 * t).toInt(), 40, (40 + 170 * t).toInt())
        }
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x in 55..85 && y in 45..75) ink else panel(x)
        }
        val m = WebModeTextRemover.estimateBackgroundModel(
            img, w, h, 55, 45, 85, 75, ink, ink
        )
        assertNotNull(m)
        assertTrue("a horizontal ramp must not collapse to one colour", !m!!.uniform)
        assertTrue("left end follows the panel: ${hex(m.at(55, 60, 55, 85, 45, 75))} vs ${hex(panel(55))}",
            near(m.at(55, 60, 55, 85, 45, 75), panel(55), 26))
        assertTrue("right end follows the panel: ${hex(m.at(85, 60, 55, 85, 45, 75))} vs ${hex(panel(85))}",
            near(m.at(85, 60, 55, 85, 45, 75), panel(85), 26))
    }

    @Test fun `interior bands stay inside a curved silhouette`() {
        // An oval balloon: 64x64 mask cells, filled inside an ellipse.
        val mw = 64; val mh = 64
        val pixels = ByteArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            val dx = (x - 31.5f) / 30f
            val dy = (y - 31.5f) / 22f
            if (dx * dx + dy * dy <= 1f) pixels[y * mw + x] = 1
        }
        val mask = BubbleDetectorCore.Mask(mw, mh, pixels)
        val imgW = 200; val imgH = 200
        val img = IntArray(imgW * imgH) { argb(252, 252, 250) }
        // Detector box is the mask's own bounding box.
        val det = BubbleDetectorCore.Det(
            BubbleDetectorCore.CLS_BUBBLE, 0.9f, 20, 20, 20 + imgW - 40, 20 + imgH - 40, mask
        )
        val bands = TextRemovalMask.interiorBands(det, img, imgW, imgH)
        assertTrue("a flat balloon yields interior bands", bands.isNotEmpty())
        for (b in bands) {
            // Each band's corners must be inside the ellipse, not on its bounding box.
            for ((x, y) in listOf(b[0] to b[1], b[2] - 1 to b[1], b[0] to b[3] - 1, b[2] - 1 to b[3] - 1)) {
                assertTrue("band corner ($x,$y) must be inside the balloon", det.contains(x + 0.5f, y + 0.5f))
            }
        }
        // The top row of the box is outside the ellipse, so no band may start there.
        assertTrue("no band covers the balloon's top-left corner",
            bands.none { it[0] < 30 && it[1] < 30 })
    }

    @Test fun `artwork inside a balloon yields no paint bands`() {
        val mw = 64; val mh = 64
        val pixels = ByteArray(mw * mh) { 1 }
        val mask = BubbleDetectorCore.Mask(mw, mh, pixels)
        val imgW = 128; val imgH = 128
        // Nothing like a flat fill: every pixel differs.
        val img = IntArray(imgW * imgH) { i -> argb(i % 251, (i * 7) % 253, (i * 13) % 249) }
        val det = BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 0.9f, 0, 0, imgW, imgH, mask)
        assertTrue(TextRemovalMask.interiorBands(det, img, imgW, imgH).isEmpty())
    }

    @Test fun `removing several lines never paints outside the balloon mask`() {
        // A round balloon with THREE lines of lettering. The balloon mask is an ellipse, so the OCR box corners
        // (which are outside the ellipse) must survive untouched after the erase.
        val paper = argb(252, 252, 250)
        val ink = argb(18, 18, 18)
        val w = 160; val h = 160
        val cx = 80f; val cy = 80f
        val inside = { x: Int, y: Int ->
            val dx = (x + 0.5f - cx) / 70f
            val dy = (y + 0.5f - cy) / 70f
            dx * dx + dy * dy <= 1f
        }
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                inside(x, y) && x in 40..119 && (y in 55..69 || y in 75..89 || y in 95..109) -> ink
                else -> paper
            }
        }
        val before = img.copyOf()
        var changedOutside = 0
        val lines = listOf(62 to 62, 82 to 82, 102 to 102)
        for ((y0, mid) in lines) {
            val mask = TextRemovalMask.inkMask(img, w, h, 30, y0 - 12, 130, y0 + 12, paper) { x, y -> inside(x, y) }
            assertNotNull("line at y=$y0 must be recognised as lettering", mask)
            val restored = TextRemovalMask.restoreFromSurroundings(mask!!, img, w, h)
            for (i in img.indices) {
                if (mask[i].toInt() != 0) img[i] = restored[i]
            }
            assertTrue("line at y=$mid should be gone", near(img[mid * w + 80], paper))
        }
        // Nothing outside the ellipse may have changed, even though the OCR box reached into the corners.
        for (i in img.indices) {
            val x = i % w; val y = i / w
            if (!inside(x, y) && img[i] != before[i]) changedOutside++
        }
        assertEquals("pixels outside the balloon mask must be untouched", 0, changedOutside)
        // The lettering lines are restored to the balloon paper, not left as ink.
        for (mid in listOf(62, 82, 102)) assertTrue("ink at y=$mid must be restored", near(img[mid * w + 80], paper))
    }

    @Test fun `pixels outside the selected text region are preserved exactly`() {
        // Text sits in a sub-rect of a larger balloon. Only the ink inside that sub-rect is rewritten; a glyph and
        // the shading outside the region must keep their exact original values.
        val paper = argb(250, 248, 240)
        val shade = argb(226, 224, 216)
        val ink = argb(15, 15, 15)
        val w = 120; val h = 120
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x < 40 -> shade                              // left part of the balloon, no text
                x in 55..64 && y in 50..59 -> ink            // the line to remove
                x in 100..109 && y in 100..109 -> ink        // another glyph FAR outside the selected region
                else -> paper
            }
        }
        val before = img.copyOf()
        val mask = TextRemovalMask.inkMask(img, w, h, 50, 45, 70, 65, paper)!!
        val restored = TextRemovalMask.restoreFromSurroundings(mask, img, w, h)
        for (i in img.indices) {
            if (mask[i].toInt() != 0) continue
            assertEquals("untouched pixel $i must be identical", before[i], restored[i])
        }
        // The far glyph is not part of the selection and keeps its ink.
        assertEquals("the glyph outside the region is preserved", ink, restored[105 * w + 105])
        // The left shading band is untouched.
        assertEquals("shading outside the region is preserved", shade, restored[60 * w + 10])
    }

    @Test fun `flat paper is recognised so the local fill is used`() {
        val paper = argb(250, 248, 240)
        val w = 80; val h = 60
        val img = IntArray(w * h) { paper }
        val mask = ByteArray(w * h) { if (it % 17 == 0) 1 else 0 }   // some "glyphs"
        assertTrue("a flat panel must be treated as reconstructable", TextRemovalMask.paperIsFlat(mask, img, w, h))
    }

    @Test fun `a soft ramp is still reconstructable`() {
        val w = 100; val h = 80
        fun ramp(y: Int) = argb(235 - y / 3, 235 - y / 3, 240 - y / 3)
        val img = IntArray(w * h) { ramp(it / w) }
        val mask = ByteArray(w * h) { if (it % 23 == 0) 1 else 0 }
        assertTrue("a gentle gradient must not trigger inpainting", TextRemovalMask.paperIsFlat(mask, img, w, h))
    }

    @Test fun `busy artwork is not flat and is left for the inpainter`() {
        val w = 96; val h = 96
        val img = IntArray(w * h) { i -> argb((i * 37) % 251, (i * 53 + 11) % 253, (i * 97 + 7) % 249) }
        val mask = ByteArray(w * h) { if (it % 29 == 0) 1 else 0 }
        assertTrue("artwork must be reported as complex", !TextRemovalMask.paperIsFlat(mask, img, w, h))
    }
}
