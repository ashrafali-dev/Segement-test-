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
        // This is the exact normalized geometry contract consumed by bubbleScript before JSON encoding.
        // It avoids the Android org.json stub in local JVM tests while exercising the production serializer input.
        val placement = requireNotNull(WebModeWriter.placementContract(bubble, translation))
        assertEquals(0.5, placement.cx, 0.00001)
        assertEquals(0.25, placement.cy, 0.00001)
        assertEquals(0.2, placement.w, 0.00001)
        assertEquals(0.08, placement.h, 0.00001)
        assertEquals(0.0, placement.angle, 0.00001)
        assertEquals(0.02, placement.font, 0.00001)
        assertEquals("JAPANESE",
            WebModeTextRemover.scriptLabelForBubble(bubble.sourceText, bubble.src, bubble.src))

        val expectedLeft = 320f
        val expectedTop = 252f
        val expectedRight = 480f
        val expectedBottom = 348f
        val left = (placement.cx - placement.w / 2.0) * panelWidth
        val top = (placement.cy - placement.h / 2.0) * panelHeight
        val right = (placement.cx + placement.w / 2.0) * panelWidth
        val bottom = (placement.cy + placement.h / 2.0) * panelHeight

        assertTrue("left bbox changed by more than 2 px", abs(left - expectedLeft) <= 2f)
        assertTrue("top bbox changed by more than 2 px", abs(top - expectedTop) <= 2f)
        assertTrue("right bbox changed by more than 2 px", abs(right - expectedRight) <= 2f)
        assertTrue("bottom bbox changed by more than 2 px", abs(bottom - expectedBottom) <= 2f)
        assertEquals("font cap changed", 16f, (placement.font * panelWidth).toFloat(), 0.01f)
    }
}
