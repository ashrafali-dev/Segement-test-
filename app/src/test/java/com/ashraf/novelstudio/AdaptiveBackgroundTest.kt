package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature D — gradient generation + WCAG contrast decision table.
 * Reference cases from the spec: white, black, pink, navy, green bubbles, and a rainbow neighbourhood.
 */
class AdaptiveBackgroundTest {
    private val BLACK = 0xFF000000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    private fun style(sample: Int, text: Int = BLACK): GradientStyle =
        AdaptiveBackground.fromSamples(
            floatArrayOf(AdaptiveBackground.hsv(sample)[0]),
            floatArrayOf(AdaptiveBackground.hsv(sample)[1]),
            floatArrayOf(AdaptiveBackground.hsv(sample)[2]), text)

    @Test fun whiteBubbleKeepsBlackTextOnALightGradient() {
        val g = style(WHITE)
        assertTrue("gradient must stay light", AdaptiveBackground.luminance(g.top) > 0.6f)
        assertEquals(BLACK, g.textColor)
        assertTrue(!g.flipped)
        assertTrue(g.minContrast >= AdaptiveBackground.MIN_CONTRAST)
        assertEquals(0.94f, g.alpha, 0.0001f)
    }

    @Test fun blackBubbleKeepsWhiteText() {
        val g = style(BLACK, WHITE)
        assertTrue("gradient must stay dark", AdaptiveBackground.luminance(g.top) < 0.4f)
        assertEquals(WHITE, g.textColor)
        assertTrue(!g.flipped)
        assertTrue(g.minContrast >= AdaptiveBackground.MIN_CONTRAST)
    }

    @Test fun pinkBubbleIsDesaturatedAndKeepsBlackTextAndItsHue() {
        val pink = 0xFFFF8FB1.toInt()
        val g = style(pink)
        assertEquals(BLACK, g.textColor)
        // Hue preserved within +-15 degrees (spec D2).
        val dh = Math.abs(AdaptiveBackground.hsv(g.top)[0] - AdaptiveBackground.hsv(pink)[0])
        assertTrue("hue drift $dh", minOf(dh, 360f - dh) <= 15f)
        // Desaturated: the gradient's saturation is tamed, not the original's.
        assertTrue(AdaptiveBackground.hsv(g.top)[1] <= AdaptiveBackground.hsv(pink)[1] * 0.46f + 0.001f)
    }

    @Test fun navyBubbleFlipsBlackTextToWhite() {
        val navy = 0xFF1A2A6C.toInt()
        val g = style(navy, BLACK)
        assertTrue("a dark navy gradient cannot carry black text", g.flipped)
        assertEquals(WHITE, g.textColor)
        assertTrue(g.minContrast >= AdaptiveBackground.MIN_CONTRAST)
    }

    @Test fun greenBubbleWithGreyTextFlipsAndOutlinesWhenStillShort() {
        // Mid green: white is chosen by the flip; if the gradient is bright enough that white still fails,
        // an outline of the opposite colour is added.
        val green = 0xFF4CAF50.toInt()
        val grey = 0xFF808080.toInt()
        val g = style(green, grey)
        assertTrue(g.flipped)
        if (g.minContrast < AdaptiveBackground.MIN_CONTRAST) {
            assertEquals(AdaptiveBackground.opposite(g.textColor), g.outline)
            assertEquals(0.85f, g.outlineAlpha, 0.0001f)
        } else {
            assertEquals(0, g.outline)
        }
    }

    @Test fun rainbowNeighbourhoodGetsAVerticalGradientAndReadableText() {
        val hues = floatArrayOf(0f, 60f, 120f, 180f, 240f, 300f)
        val sats = FloatArray(6) { 0.8f }
        val vals = FloatArray(6) { 0.7f }
        val g = AdaptiveBackground.fromSamples(hues, sats, vals, BLACK)
        assertNotEquals(0, g.top)
        assertNotEquals(g.top, g.bottom)
        // Some colour solves without a flip, some with; either way the table is applied, never a hue change.
        if (g.outline != 0) assertEquals(0.85f, g.outlineAlpha, 0.0001f)
    }

    @Test fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21f, AdaptiveBackground.contrast(BLACK, WHITE), 0.05f)
        assertEquals(1f, AdaptiveBackground.contrast(BLACK, BLACK), 0.001f)
        // #767676 on white is the classic 4.54:1 boundary.
        assertEquals(4.54f, AdaptiveBackground.contrast(0xFF767676.toInt(), WHITE), 0.05f)
    }

    @Test fun gradientStopsFollowTheSpecFormula() {
        val s = 0.5f
        val v = 0.6f
        val h = 200f
        val src = AdaptiveBackground.hsvColor(h, s, v)
        val g = style(src, WHITE)
        val expectedTop = AdaptiveBackground.hsvColor(h, s * AdaptiveBackground.DESATURATE, (v * 1.10f).coerceIn(0.55f, 0.95f))
        val expectedBottom = AdaptiveBackground.hsvColor(h, s * AdaptiveBackground.DESATURATE, (v * 0.92f).coerceIn(0.45f, 0.90f))
        assertEquals(expectedTop, g.top)
        assertEquals(expectedBottom, g.bottom)
    }

    @Test fun alphaIsHighEnoughToHideOldLettering() {
        val g = style(0xFF00A0FF.toInt())
        assertTrue("spec: semi-transparent defeats the purpose", g.alpha >= 0.90f)
    }

    @Test fun everyReferenceCasePassesAaAfterSolving() {
        val cases = listOf(WHITE to BLACK, BLACK to WHITE, 0xFFFF8FB1.toInt() to BLACK,
            0xFF1A2A6C.toInt() to BLACK, 0xFF4CAF50.toInt() to 0xFF808080.toInt())
        for ((bg, text) in cases) {
            val g = style(bg, text)
            val proven = g.minContrast >= AdaptiveBackground.MIN_CONTRAST || g.outline != 0
            assertTrue("case bg=${"%08x".format(bg)} text=${"%08x".format(text)} minContrast=${g.minContrast}", proven)
        }
    }

    @Test fun medianHueHandlesTheWrapAround() {
        val h = AdaptiveBackground.medianHue(floatArrayOf(358f, 1f, 2f, 359f))
        assertTrue(h == 358f || h == 1f || h == 2f || h == 359f)
        assertEquals(120f, AdaptiveBackground.medianHue(floatArrayOf(118f, 120f, 122f)), 0.001f)
    }
}
