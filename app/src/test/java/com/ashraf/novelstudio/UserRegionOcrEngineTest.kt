package com.ashraf.novelstudio

import com.ashraf.novelstudio.fake.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Defect C: runs the REAL chain loop in [UserRegionOcrEngine] with fake engines and fake bitmaps, so the order,
 * the early stop, the error tolerance, cancellation and the upscale map-back are all under test.
 */
class UserRegionOcrEngineTest {

    @Before fun noAndroidPreprocess() { UserRegionPreprocess.enabled = false }

    private fun block(t: String, cx: Float = 20f, cy: Float = 10f) =
        OcrBlock(t, (cy - 4).toInt(), (cx - 5).toInt(), cx, cy, 10f, 8f, fontPx = 8f)

    private fun crop() = Bitmap(100, 50)

    private fun engine(
        order: MutableList<UserRegionEngine> = ArrayList(),
        lensFull: (OcrScript) -> OcrResult = { OcrResult(emptyList()) },
        lensFast: (OcrScript) -> OcrResult = { OcrResult(emptyList()) },
        mlkit: (OcrScript) -> OcrResult = { OcrResult(emptyList()) },
        cancelled: () -> Boolean = { false },
        scale: (Bitmap, Float) -> Bitmap = { b, _ -> b },
        contrast: (Bitmap) -> Bitmap = { it }
    ) = UserRegionOcrEngine(
        lensFull = { _, _ -> order.add(UserRegionEngine.LENS_FULL_2X); lensFull(it) },
        lensFast = { _, _ -> order.add(UserRegionEngine.LENS_FAST); lensFast(it) },
        mlkit = { _, _ -> order.add(UserRegionEngine.MLKIT); mlkit(it) },
        cancelled = cancelled,
        scale = scale,
        contrast = contrast,
        upscaleApplied = { _, k -> k }
    )

    @Test fun firstRungThatReadsTextStopsTheChain() {
        val order = ArrayList<UserRegionEngine>()
        val r = engine(order, lensFull = { OcrResult(listOf(block("hello", cx = 40f, cy = 20f))) })
            .ocrUserRegion(crop(), OcrScript.LATIN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.LENS_FULL_2X, r.engine)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X), order)
        // Rung 1 is the 2x rung: a block at crop pixel (40,20) came back at (80,40) and must be mapped back.
        assertEquals(20f, r.blocks[0].cx, 0.001f)
        assertEquals(10f, r.blocks[0].cy, 0.001f)
    }

    @Test fun emptyRungsFallThroughInOrderToMlKit() {
        val order = ArrayList<UserRegionEngine>()
        val r = engine(order,
            lensFull = { OcrResult(emptyList()) },
            lensFast = { OcrResult(listOf(block("  "))) },
            mlkit = { OcrResult(listOf(block("작화도움"))) }).ocrUserRegion(crop(), OcrScript.KOREAN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.MLKIT, r.engine)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X, UserRegionEngine.LENS_FAST, UserRegionEngine.MLKIT), order)
    }

    @Test fun engineErrorsDoNotStopTheChain() {
        val r = engine(
            lensFull = { throw IllegalStateException("offline") },
            mlkit = { OcrResult(listOf(block("text"))) }).ocrUserRegion(crop(), OcrScript.LATIN)
        assertTrue(r.ok)
        assertEquals(UserRegionEngine.MLKIT, r.engine)
        assertTrue(r.trace.contains("err=IllegalStateException"))
    }

    @Test fun allRungsEmptyIsAFailureWithAReasonNeverAnEmptySuccess() {
        val r = engine().ocrUserRegion(crop(), OcrScript.LATIN)
        assertFalse(r.ok)
        assertNull(r.engine)
        assertTrue(r.trace.startsWith("chain=FAILED"))
        assertTrue("the 4x+contrast rung must have been tried", r.trace.contains("Lens full 4x+contrast"))
        assertTrue(UserRegionOcr.failureMessage().contains("মার্ক করুন"))
    }

    @Test fun cancellationStopsBeforeTheNextRung() {
        val order = ArrayList<UserRegionEngine>()
        var checks = 0
        val r = engine(order, cancelled = { checks++ > 0 }).ocrUserRegion(crop(), OcrScript.LATIN)
        assertFalse(r.ok)
        assertEquals(listOf(UserRegionEngine.LENS_FULL_2X), order)
        assertTrue(r.trace.contains("cancelled"))
    }

    @Test fun contrastRungReceivesAContrastCopyAndEverythingIsRecycled() {
        val produced = ArrayList<Bitmap>()
        val r = engine(
            lensFull = { OcrResult(emptyList()) },
            scale = { b, _ -> Bitmap(b.width * 4, b.height * 4).also { produced.add(it) } },
            contrast = { b -> Bitmap(b.width, b.height).also { produced.add(it) } }
        ).ocrUserRegion(crop(), OcrScript.LATIN)
        assertFalse(r.ok)
        // Uppercases: rung 1 and rung 4 upscale (2), rung 4 also contrasts (1). All three are freed.
        assertEquals(3, produced.size)
        assertTrue(produced.all { it.recycled })
    }

    @Test fun regionPlacedExactlyOnTheMarkPlusScroll() {
        val r = engine(lensFull = { OcrResult(listOf(block("hi", cx = 40f, cy = 20f))) })
            .ocrUserRegion(crop(), OcrScript.LATIN)
        val out = r.placements(CanvasBox(300f, 500f, 500f, 600f))
        assertEquals(340f, out[0].cx, 0.001f)
        assertEquals(520f, out[0].cy, 0.001f)
        assertEquals(1720f, placeOnCanvas(out[0], 1200).cy, 0.001f)
    }
}
