package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defect A: upload -> panel -> canvas must be scaled once and scrolled once. Pure JVM tests. */
class OcrPlacementTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = CanvasBox(l, t, r, b)

    @Test fun mapToPanelScalesOnceAndAddsNoScroll() {
        // Lens upload 690x1600 -> panel 420x975 (the field log).
        val raw = box(345f, 800f, 483f, 960f)
        val panel = mapToPanel(raw, 690, 1600, 420, 975)
        assertEquals(345f * 420f / 690f, panel.left, 0.01f)
        assertEquals(800f * 975f / 1600f, panel.top, 0.01f)
        assertEquals(483f * 420f / 690f, panel.right, 0.01f)
        assertEquals(960f * 975f / 1600f, panel.bottom, 0.01f)
        // No scroll offset anywhere: panel space never knows about scroll-top 38025.
        assertTrue(panel.top < 975f)
    }

    @Test fun placeOnCanvasAddsScrollOffsetExactlyOnce() {
        val panel = box(100f, 200f, 300f, 260f)
        val canvas = placeOnCanvas(panel, 38025)
        assertEquals(100f, canvas.left, 0f)
        assertEquals(38225f, canvas.top, 0f)
        assertEquals(38285f, canvas.bottom, 0f)
        assertEquals(panel.width, canvas.width, 0f)
    }

    @Test fun mappingIsIdempotentForTheSamePanel() {
        val raw = box(10f, 20f, 110f, 60f)
        val a = placeOnCanvas(mapToPanel(raw, 1000, 2000, 500, 1000), 7000)
        val b = placeOnCanvas(mapToPanel(raw, 1000, 2000, 500, 1000), 7000)
        assertEquals(a, b)
    }

    @Test fun degenerateDimensionsReturnTheBoxUnchanged() {
        val raw = box(1f, 2f, 3f, 4f)
        assertEquals(raw, mapToPanel(raw, 0, 100, 100, 100))
        assertEquals(raw, mapToPanel(raw, 100, 100, 100, 0))
    }

    @Test fun userRegionIsPlacedAtTopLeftPlusBlockOffset() {
        // Region at (100, 400) size 200x120; a block whose centre is (30, 20) inside the crop, 40x16 big.
        val b = regionBox(100f, 400f, 200f, 120f, 30f, 20f, 40f, 16f)
        assertEquals(110f, b.left, 0.001f)
        assertEquals(412f, b.top, 0.001f)
        assertEquals(150f, b.right, 0.001f)
        assertEquals(428f, b.bottom, 0.001f)
        // ...and only then does scroll enter, once.
        val canvas = placeOnCanvas(b, 1000)
        assertEquals(1412f, canvas.top, 0.001f)
        assertEquals(110f, canvas.left, 0.001f)
    }

    @Test fun coverageAcceptsASmallerTranslationInsideTheBubble() {
        val bubble = box(0f, 0f, 200f, 120f)
        val text = box(20f, 15f, 180f, 105f)
        assertTrue(coverage(bubble, text) >= 0.99f)
        // IoU of that same pair is low — which is why IoU alone is not the acceptance rule.
        assertTrue(iou(bubble, text) < 0.5f)
    }

    @Test fun coordinateTraceShowsAllThreeSpaces() {
        val s = coordinateTrace(box(0f, 0f, 690f, 1600f), 690, 1600, 420, 975, 38025)
        assertTrue(s.contains("COORD raw[0.0,0.0,690.0,1600.0] upload=690x1600"))
        assertTrue(s.contains("-> panel[0.0,0.0,420.0,975.0] 420x975"))
        assertTrue(s.contains("-> canvas[0.0,38025.0,420.0,39000.0] top=38025"))
    }

    @Test fun twoScansOfTheSamePanelProduceIdenticalBoxes() {
        val raw = box(12.3f, 45.6f, 78.9f, 123.4f)
        repeat(5) {
            assertEquals(coordinateTrace(raw, 690, 1600, 420, 975, 38025),
                coordinateTrace(raw, 690, 1600, 420, 975, 38025))
        }
    }
}
