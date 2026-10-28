package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Defect C: the user-region chain must stop at the first rung that reads text, in order, and must never
 * report an empty success. The engines are faked (they take the Bitmap only as a token), so this runs on the
 * JVM without Android; contrast preprocessing is switched off for the same reason.
 */
class UserRegionOcrTest {

    @Before fun noAndroidPreprocess() { UserRegionPreprocess.enabled = false }

    private fun block(t: String, cx: Float = 20f, cy: Float = 10f) =
        OcrBlock(t, (cy - 4).toInt(), (cx - 5).toInt(), cx, cy, 10f, 8f, fontPx = 8f)

    @Test fun chainOrderIsTheDocumentedOne() {
        assertEquals(
            listOf(UserRegionEngine.LENS_FULL_2X, UserRegionEngine.LENS_FAST, UserRegionEngine.MLKIT,
                UserRegionEngine.LENS_FULL_4X_CONTRAST),
            UserRegionOcr.chain(OcrScript.JAPANESE).map { it.engine })
    }

    @Test fun firstRungThatReadsTextStopsTheChain() {
        val order = ArrayList<UserRegionEngine>()
        val eng = UserRegionOcrEngine(
            lensFull = { _, _ -> order.add(UserRegionEngine.LENS_FULL_2X); OcrResult(listOf(block("hello"))) },
            lensFast = { _, _ -> order.add(UserRegionEngine.LENS_FAST); OcrResult(listOf(block("wrong"))) },
            mlkit = { _, _ -> order.add(UserRegionEngine.MLKIT); OcrResult(listOf(block("wrong"))) }
        )
        val r = eng.ocrUserRegion(null, OcrScript.LATIN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.LENS_FULL_2X, r.engine)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X), order)
    }

    @Test fun emptyRungsFallThroughInOrderToMlKit() {
        val order = ArrayList<UserRegionEngine>()
        val eng = UserRegionOcrEngine(
            lensFull = { _, _ -> order.add(UserRegionEngine.LENS_FULL_2X); OcrResult(emptyList()) },
            lensFast = { _, _ -> order.add(UserRegionEngine.LENS_FAST); OcrResult(listOf(block("  "))) },
            mlkit = { _, _ -> order.add(UserRegionEngine.MLKIT); OcrResult(listOf(block("작화도움"))) }
        )
        val r = eng.ocrUserRegion(null, OcrScript.KOREAN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.MLKIT, r.engine)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X, UserRegionEngine.LENS_FAST, UserRegionEngine.MLKIT), order)
    }

    @Test fun engineErrorsDoNotStopTheChain() {
        val eng = UserRegionOcrEngine(
            lensFull = { _, _ -> throw IllegalStateException("offline") },
            lensFast = { _, _ -> throw IllegalStateException("offline") },
            mlkit = { _, _ -> OcrResult(listOf(block("text"))) }
        )
        val r = eng.ocrUserRegion(null, OcrScript.LATIN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.MLKIT, r.engine)
        assertTrue(r.trace.contains("err=IllegalStateException"))
    }

    @Test fun allRungsEmptyIsAFailureWithAReasonNeverAnEmptySuccess() {
        val eng = UserRegionOcrEngine(
            lensFull = { _, _ -> OcrResult(emptyList()) },
            lensFast = { _, _ -> OcrResult(emptyList()) },
            mlkit = { _, _ -> OcrResult(emptyList()) }
        )
        val r = eng.ocrUserRegion(null, OcrScript.LATIN)
        assertFalse(r.ok)
        assertNull(r.engine)
        assertTrue(r.trace.contains("chain=FAILED"))
        assertTrue("the 4x+contrast rung must still have been tried", r.trace.contains("Lens full 4x+contrast"))
        assertTrue(UserRegionOcr.failureMessage().contains("মার্ক করুন"))
    }

    @Test fun cancellationStopsBeforeTheNextRung() {
        val order = ArrayList<UserRegionEngine>()
        var checks = 0
        val eng = UserRegionOcrEngine(
            lensFull = { _, _ -> order.add(UserRegionEngine.LENS_FULL_2X); OcrResult(emptyList()) },
            lensFast = { _, _ -> order.add(UserRegionEngine.LENS_FAST); OcrResult(emptyList()) },
            mlkit = { _, _ -> order.add(UserRegionEngine.MLKIT); OcrResult(listOf(block("x"))) },
            cancelled = { checks++ > 0 }
        )
        val r = eng.ocrUserRegion(null, OcrScript.LATIN)
        assertFalse(r.ok)
        // The first rung ran (the flag was checked before it); the rest were skipped as cancelled.
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X), order)
        assertTrue(r.trace.contains("cancelled"))
    }

    @Test fun upscaledCoordinatesMapBackToTheCrop() {
        val plan = RegionPlan(UserRegionEngine.LENS_FULL_2X, upscale = 2f, contrast = false, applicable = true)
        val back = UserRegionOcr.mapBack(listOf(block("hi", cx = 40f, cy = 20f)), plan)
        assertEquals(20f, back[0].cx, 0.001f)
        assertEquals(10f, back[0].cy, 0.001f)
        assertEquals(5f, back[0].w, 0.001f)
        assertEquals(4f, back[0].fontPx, 0.001f)
    }

    @Test fun placementsSitExactlyOnTheMarkedRegionPlusBlockOffset() {
        // Region at (300,500) size 200x100; block centre (40,20) inside the crop.
        val r = UserRegionResult(listOf(block("hi", cx = 40f, cy = 20f)), UserRegionEngine.LENS_FULL_2X, "")
        val out = r.placements(CanvasBox(300f, 500f, 500f, 600f))
        assertEquals(340f, out[0].cx, 0.001f)
        assertEquals(520f, out[0].cy, 0.001f)
        // ...and scroll is applied once, later, by placeOnCanvas.
        val canvas = placeOnCanvas(out[0], 1200)
        assertEquals(1720f, canvas.cy, 0.001f)
        assertEquals(340f, canvas.cx, 0.001f)
    }

    @Test fun lettersCountsWhatAnEngineReallyRead() {
        assertTrue(UserRegionOcr.letters(listOf(block("!! ~"))) < UserRegionOcr.MIN_LETTERS)
        assertTrue(UserRegionOcr.letters(listOf(block("a"))) >= UserRegionOcr.MIN_LETTERS)
        assertNotNull(UserRegionOcr.failureMessage(false))
    }
}
