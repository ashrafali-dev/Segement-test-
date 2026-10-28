package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defect B (Lens cache short-circuit) and Defect F (LRU bounds). Pure JVM tests. */
class OcrQualityGateTest {

    private fun block(t: String, cx: Float = 10f, cy: Float = 10f) =
        OcrBlock(t, (cy - 4).toInt(), (cx - 10).toInt(), cx, cy, 20f, 8f, fontPx = 8f)

    private fun sig(fill: Int) = LensCache.signatureOf(8, 8) { _, _ -> fill }

    @Test fun lensCacheReturnsBlocksForSameScriptOnly() {
        LensCache.clear()
        LensCache.put(sig(10), LensCache.Entry(listOf(block("안녕")), 400, 800, OcrScript.KOREAN))
        assertEquals(1, LensCache.get(sig(10), OcrScript.KOREAN)?.size)
        assertNull("a different script must not reuse the read", LensCache.get(sig(10), OcrScript.JAPANESE))
    }

    @Test fun lensCacheIsBoundedAndEvictsLeastRecentlyUsed() {
        LensCache.clear()
        for (i in 1..LensCache.MAX_ENTRIES + 5) {
            LensCache.put("k$i", LensCache.Entry(listOf(block("t$i")), 100, 100, OcrScript.LATIN))
        }
        assertTrue(LensCache.size() <= LensCache.MAX_ENTRIES)
        assertNotNull(LensCache.get("k${LensCache.MAX_ENTRIES + 5}", OcrScript.LATIN))
        assertNull(LensCache.get("k1", OcrScript.LATIN))
    }

    @Test fun pixelEntryIsReachableByItsPixelDigest() {
        LensCache.clear()
        val s = sig(77)
        LensCache.putByPixels(s, LensCache.Entry(listOf(block("hello")), 300, 500, OcrScript.LATIN))
        val digest = LensCache.pixelDigest(s)
        assertEquals(1, LensCache.get(digest, OcrScript.LATIN)?.size)
        assertNotNull(LensCache.get(s, OcrScript.LATIN))
    }

    @Test fun pixelDigestIsStableAndDistinct() {
        val a = LensCache.pixelDigest(sig(200))
        val b = LensCache.pixelDigest(sig(200))
        val c = LensCache.pixelDigest(sig(20))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test fun emptyResultsAreNeverCached() {
        LensCache.clear()
        LensCache.put(sig(3), LensCache.Entry(emptyList(), 100, 100, OcrScript.LATIN))
        assertEquals(0, LensCache.size())
    }

    @Test fun signatureIsStableForIdenticalPixels() {
        val a = LensCache.signatureOf(64, 64) { x, y -> (x + y) % 256 }
        val b = LensCache.signatureOf(64, 64) { x, y -> (x + y) % 256 }
        assertEquals(a, b)
        assertEquals(32 * 32, a.length)
        assertTrue(LensCache.signatureOf(64, 64) { _, _ -> 10 } != LensCache.signatureOf(64, 64) { _, _ -> 240 })
    }
}
