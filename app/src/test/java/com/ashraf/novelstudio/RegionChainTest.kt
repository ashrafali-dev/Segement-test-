package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defect C: runs the REAL chain loop ([RegionChainCore]) with fake engines and fake bitmaps, so order, early
 * stop, error tolerance, cancellation, recycling and the upscale map-back are all under test on the JVM.
 */
class RegionChainTest {

    private class Bmp(val w: Int, val h: Int) { var recycled = false }

    private fun block(t: String, cx: Float = 20f, cy: Float = 10f) =
        OcrBlock(t, (cy - 4).toInt(), (cx - 5).toInt(), cx, cy, 10f, 8f, fontPx = 8f)

    private class Harness(
        val order: MutableList<UserRegionEngine> = ArrayList(),
        val created: MutableList<Bmp> = ArrayList()
    ) {
        fun core(
            lensFull: (OcrScript) -> OcrResult = { OcrResult(emptyList()) },
            lensFast: (OcrScript) -> OcrResult = { OcrResult(emptyList()) },
            mlkit: (OcrScript) -> OcrResult = { OcrResult(emptyList()) },
            cancelled: () -> Boolean = { false }
        ): RegionChainCore<Bmp> = RegionChainCore<Bmp>(
            lensFull = { _, s -> order.add(UserRegionEngine.LENS_FULL_2X); lensFull(s) },
            lensFast = { _, s -> order.add(UserRegionEngine.LENS_FAST); lensFast(s) },
            mlkit = { _, s -> order.add(UserRegionEngine.MLKIT); mlkit(s) },
            // Fake upscale: a clearly different bitmap, with the requested factor "applied".
            scale = { b, k -> Bmp(b.w * 2, b.h * 2).also { created.add(it) } to k },
            contrast = { b -> Bmp(b.w, b.h).also { created.add(it) } },
            recycle = { b -> b.recycled = true },
            same = { a, b -> a === b },
            cancelled = cancelled
        )
    }

    @Test fun firstRungThatReadsTextStopsTheChain() {
        val h = Harness()
        val crop = Bmp(100, 50)
        val r = h.core(lensFull = { OcrResult(listOf(block("hello", cx = 40f, cy = 20f))) }).run(crop, OcrScript.LATIN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.LENS_FULL_2X, r.engine)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X), h.order)
        // Rung 1 is the 2x rung: the block came back at (80,40) in the upscaled copy and must map to (40,20).
        assertEquals(20f, r.blocks[0].cx, 0.001f)
        assertEquals(10f, r.blocks[0].cy, 0.001f)
        // The upscaled copy is freed; the caller's crop is NOT.
        assertTrue(h.created.single().recycled)
        assertFalse(crop.recycled)
    }

    @Test fun emptyRungsFallThroughInOrderToMlKit() {
        val h = Harness()
        val r = h.core(
            lensFull = { OcrResult(emptyList()) },
            lensFast = { OcrResult(listOf(block("  "))) },
            mlkit = { OcrResult(listOf(block("작화도움"))) }).run(Bmp(100, 50), OcrScript.KOREAN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.MLKIT, r.engine)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X, UserRegionEngine.LENS_FAST, UserRegionEngine.MLKIT), h.order)
    }

    @Test fun engineErrorsDoNotStopTheChain() {
        val h = Harness()
        val r = h.core(
            lensFull = { throw IllegalStateException("offline") },
            mlkit = { OcrResult(listOf(block("text"))) }).run(Bmp(100, 50), OcrScript.LATIN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.MLKIT, r.engine)
        assertTrue(r.trace.contains("err=IllegalStateException"))
    }

    @Test fun allRungsEmptyIsAFailureWithAReasonNeverAnEmptySuccess() {
        val h = Harness()
        val r = h.core().run(Bmp(100, 50), OcrScript.LATIN)
        assertFalse(r.ok)
        assertNull(r.engine)
        assertTrue(r.trace.startsWith("chain=FAILED"))
        assertTrue("the 4x+contrast rung must have been tried", r.trace.contains("Lens full 4x+contrast"))
        assertTrue(UserRegionOcr.failureMessage().contains("মার্ক করুন"))
        // Every bitmap the chain created (2x, 4x and the contrast copy) is freed.
        assertEquals(3, h.created.size)
        assertTrue(h.created.all { it.recycled })
    }

    @Test fun cancellationStopsBeforeTheNextRung() {
        val h = Harness()
        var checks = 0
        val r = h.core(cancelled = { checks++ > 0 }).run(Bmp(100, 50), OcrScript.LATIN)
        assertFalse(r.ok)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X), h.order)
        assertTrue(r.trace.contains("cancelled"))
    }

    @Test fun regionPlacedExactlyOnTheMarkPlusScroll() {
        val h = Harness()
        val r = h.core(lensFull = { OcrResult(listOf(block("hi", cx = 40f, cy = 20f))) })
            .run(Bmp(100, 50), OcrScript.LATIN)
        val out = r.placements(CanvasBox(300f, 500f, 500f, 600f))
        assertEquals(340f, out[0].cx, 0.001f)
        assertEquals(520f, out[0].cy, 0.001f)
        assertEquals(1720f, placeOnCanvas(out[0], 1200).cy, 0.001f)
    }

    @Test fun lettersCountsWhatAnEngineReallyRead() {
        assertTrue(UserRegionOcr.letters(listOf(block("!! ~"))) < UserRegionOcr.MIN_LETTERS)
        assertTrue(UserRegionOcr.letters(listOf(block("a"))) >= UserRegionOcr.MIN_LETTERS)
    }
}
