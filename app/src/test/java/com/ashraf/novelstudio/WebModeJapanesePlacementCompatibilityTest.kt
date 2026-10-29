package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Compatibility contract for the currently-good Tonari Japanese placement.
 *
 * This fixture models a known Japanese panel with a known bubble/translation.
 * The generated overlay must serialize the exact existing normalized bbox and
 * font cap; projecting it to the original 800x1200 panel must remain within 2 px.
 */
class WebModeJapanesePlacementCompatibilityTest {
    @Test
    fun japaneseBubbleKeepsItsExistingBoxAndFontCap() {
        val panelWidth = 800f
        val panelHeight = 1200f
        val bubble = WmBubble(
            src = "https://toparinouj.jp/episode/1220742198423.webp",
            cx = 0.5f,
            cy = 0.25f,
            w = 0.2f,
            h = 0.08f,
            angle = 0f,
            font = 0.02f,
            bg = 0xFFFFFFFF.toInt(),
            fg = 0xFF111111.toInt(),
            sourceText = "あはは!"
        )
        val translation = "হাহা!"
        val script = WebModeWriter.bubbleScript(
            WebModeLayout(0, listOf(bubble)),
            listOf(translation),
            font = "",
            pageId = "jp-placement-regression"
        )

        // Geometry is the unchanged contract passed to the existing JS placement engine.
        assertTrue(script.contains("\"t\":\"$translation\""))
        assertTrue(script.contains("\"cx\":0.5"))
        assertTrue(script.contains("\"cy\":0.25"))
        assertTrue(script.contains("\"w\":0.2"))
        assertTrue(script.contains("\"h\":0.08"))
        assertTrue(script.contains("\"a\":0.0"))
        assertTrue(script.contains("\"f\":0.02"))
        assertEquals("JAPANESE",
            WebModeTextRemover.scriptLabelForBubble(bubble.sourceText, bubble.src, bubble.src))

        val expectedLeft = 320f
        val expectedTop = 252f
        val expectedRight = 480f
        val expectedBottom = 348f
        val left = (bubble.cx - bubble.w / 2f) * panelWidth
        val top = (bubble.cy - bubble.h / 2f) * panelHeight
        val right = (bubble.cx + bubble.w / 2f) * panelWidth
        val bottom = (bubble.cy + bubble.h / 2f) * panelHeight

        assertTrue("left bbox changed by more than 2 px", abs(left - expectedLeft) <= 2f)
        assertTrue("top bbox changed by more than 2 px", abs(top - expectedTop) <= 2f)
        assertTrue("right bbox changed by more than 2 px", abs(right - expectedRight) <= 2f)
        assertTrue("bottom bbox changed by more than 2 px", abs(bottom - expectedBottom) <= 2f)
        assertEquals("font cap changed", 16f, bubble.font * panelWidth, 0.01f)
    }
}
