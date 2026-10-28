package com.ashraf.novelstudio

import com.ashraf.novelstudio.BubbleDetectorCore as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleDetectorCoreTest {
    @Test fun normalPageIsOneTile() {
        assertEquals(1, C.planTiles(800, 1000).size)
    }

    @Test fun tallPageIsTiledWithOverlapAndFullCoverage() {
        val t = C.planTiles(800, 6000)
        assertTrue(t.size > 3)
        assertEquals(0, t.first()[1])
        assertEquals(6000, t.last()[3])
        for (tile in t) {
            assertEquals(800, tile[2] - tile[0])
            assertTrue(tile[3] - tile[1] <= 1520)
        }
        for (i in 1 until t.size) assertTrue("tile $i overlaps the previous one", t[i][1] < t[i - 1][3])
    }

    @Test fun widePageIsTiledHorizontally() {
        val t = C.planTiles(4000, 800)
        assertTrue(t.size > 1)
        for (tile in t) { assertEquals(800, tile[3] - tile[1]); assertTrue(tile[2] - tile[0] <= 1520) }
    }

    @Test fun tileCountIsCappedAndTinyImagesGetNoTiles() {
        assertEquals(C.MAX_TILES, C.capTiles(C.planTiles(800, 60000)).size)
        assertTrue(C.planTiles(10, 10).isEmpty())
    }

    @Test fun decodeScalesToTileAndFiltersBadRows() {
        val labels = floatArrayOf(1f, 2f, 0f, 1f, 7f)
        val boxes = floatArrayOf(
            64f, 64f, 320f, 320f,
            0f, 0f, 640f, 640f,
            10f, 10f, 12f, 12f,
            0f, 0f, 100f, 100f,
            0f, 0f, 100f, 100f
        )
        val scores = floatArrayOf(0.9f, 0.5f, 0.9f, 0.1f, 0.9f)
        val d = C.decode(labels, boxes, scores, 5, intArrayOf(0, 300, 800, 1500))
        assertEquals(2, d.size)
        assertEquals(listOf(80, 420, 400, 900), listOf(d[0].l, d[0].t, d[0].r, d[0].b))
        assertEquals(800, d[1].r)
        assertEquals(1500, d[1].b)
    }

    @Test fun decodeIgnoresNonFiniteValues() {
        val d = C.decode(floatArrayOf(1f), floatArrayOf(Float.NaN, 0f, 100f, 100f), floatArrayOf(0.9f), 1, intArrayOf(0, 0, 640, 640))
        assertTrue(d.isEmpty())
    }

    @Test fun mergeDropsTheSameObjectSeenByTwoTiles() {
        val m = C.merge(listOf(
            C.Det(1, .9f, 10, 10, 100, 100), C.Det(1, .8f, 12, 12, 98, 99),
            C.Det(2, .7f, 10, 10, 100, 100), C.Det(1, .6f, 500, 500, 600, 600)
        ))
        assertEquals(3, m.size)
    }

    @Test fun chwPlanesAreRgb() {
        val o = FloatArray(12)
        C.fillChw(IntArray(4) { 0xFF336699.toInt() }, 2, o)
        assertEquals(0x33 / 255f, o[0], 1e-6f)
        assertEquals(0x66 / 255f, o[4], 1e-6f)
        assertEquals(0x99 / 255f, o[8], 1e-6f)
    }

    private val dets = listOf(
        C.Det(0, .9f, 0, 0, 300, 300), C.Det(0, .9f, 400, 0, 700, 300),
        C.Det(1, .9f, 50, 50, 250, 250), C.Det(2, .9f, 100, 600, 300, 700)
    )

    @Test fun twoBubblesAreSeparatedButOneBubbleIsNot() {
        assertTrue(C.inDifferentBubbles(dets, 100f, 100f, 500f, 100f))
        assertFalse(C.inDifferentBubbles(dets, 100f, 100f, 200f, 200f))
        assertTrue("outside a known balloon is an independent aside", C.inDifferentBubbles(dets, 100f, 100f, 200f, 900f))
        assertFalse("both unknown is not evidence", C.inDifferentBubbles(dets, 800f, 800f, 900f, 900f))
        assertFalse(C.inDifferentBubbles(null, 1f, 1f, 2f, 2f))
    }

    @Test fun freeTextIsOnlyTextOutsideEveryBubble() {
        assertTrue(C.isFreeText(dets, 150f, 650f))
        assertFalse(C.isFreeText(dets, 100f, 100f))
        assertFalse(C.isFreeText(dets, 800f, 800f))
        assertFalse(C.isFreeText(null, 150f, 650f))
    }
}
