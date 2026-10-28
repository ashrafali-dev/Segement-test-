package com.ashraf.novelstudio

import org.junit.Assert.*
import org.junit.Test

/** Synthetic, deterministic tests; these do not replace a real Android reader test. */
class ScanQualityRegressionTest {
    private fun line(text: String, x: Float, y: Float, f: Float, w: Float = 180f) =
        OcrBlock(text, (y-f/2).toInt(), (x-w/2).toInt(), x, y, w, f, fontPx=f)
    private fun bubble(s: String = "hello") = WmBubble("https://test/page.jpg", .5f, .5f, .3f, .2f,
        0f, .02f, -1, -16777216, sourceText=s)
    private fun layout(n: Int, offset: Int = 0) = WebModeLayout(offset, (1..n).map { bubble("source $it") })

    @Test fun whisperIsNotPartOfMainDialogue() {
        val main = line("배고픈 거랑", 250f, 200f, 38f, 260f)
        val aside = line("그게 제가 경험한 시장!", 305f, 247f, 25f, 230f)
        assertTrue(BubbleGrouping.isAsidePair(main, aside))
        assertEquals(2, BubbleGrouping.groups(listOf(main, aside), listOf(0,1)).size)
        assertEquals(2, OcrPost.mergeNeighbours(listOf(main, aside)).size)
    }
    @Test fun centeredEmphasisStillBelongsToOneBubble() {
        val a=line("BIG",300f,100f,44f);val b=line("smaller",300f,140f,30f)
        assertFalse(BubbleGrouping.isAsidePair(a,b))
        assertEquals(1,BubbleGrouping.groups(listOf(a,b),listOf(0,1)).size)
    }
    @Test fun horizontalRowsWaitForBoundaryEvidence() {
        val a=line("first",200f,100f,30f);val b=line("second",200f,134f,30f)
        assertEquals(2,OcrPost.mergeNeighbours(listOf(a,b),verticalOnly=true).size)
    }
    @Test fun incompleteReplyCannotBeSuccessful() {
        assertNull(WebModeLayout.align("[001] one [002] two [003] three",layout(4)))
    }
    @Test fun duplicateMarkerIsAmbiguousEvenWithFullCoverage() {
        assertNull(WebModeLayout.align("[001] one [001] wrong [002] two",layout(2)))
    }
    @Test fun missingBubbleCannotBeHiddenByTitleMarker() {
        assertNull(WebModeLayout.align("[001] title [002] one [003] two [004] three",layout(4,1)))
    }
    @Test fun exactMarkersKeepTheirOwnBoxes() {
        assertEquals(listOf("one","two"),WebModeLayout.align("[001] one [002] two",layout(2)))
    }
    @Test fun outOfOrderOrExtraMarkersAreRejected() {
        assertNull(WebModeLayout.align("[002] two [001] one",layout(2)))
        assertNull(WebModeLayout.align("[001] one [002] two [003] extra",layout(2)))
    }
    @Test fun singleBubbleDoesNotSwallowMarkedMultipleAnswers() {
        assertNull(WebModeLayout.alignWithFallback("[001] one [002] other",layout(1)))
        assertEquals(listOf("one"),WebModeLayout.alignWithFallback("one",layout(1)))
    }
    @Test fun generationSuffixKeepsStableSlotButDistinctFingerprint() {
        val a="ns-canvas:0,0,800x1200@1_3_800_1200"
        val b="ns-canvas:0,0,800x1200@1_4_800_1200"
        assertTrue(WebModeLayoutStore.sameCanvasSource(a,b))
        assertNotEquals(WebModeLayout.fingerprint(bubble().copy(src=a)),WebModeLayout.fingerprint(bubble().copy(src=b)))
    }
    @Test fun legacyCanvasSlotStillParses() {
        assertEquals("ns-canvas:0,0",WebModeLayoutStore.canvasSourceSlot("ns-canvas:0,0,800x1200"))
    }
    @Test fun lensParagraphCannotAbsorbTheWhisperBeforeGrouping() {
        val shape=LensProtocol.Shape(emptyList(),emptyList(),emptyList(),emptyList(),true,true)
        val paragraph=LensProtocol.Paragraph(listOf(
            LensProtocol.Line("배고픈 거랑",LensProtocol.Box(250f,200f,260f,38f,0f,false)),
            LensProtocol.Line("그게 제가 경험한 시장!",LensProtocol.Box(305f,247f,230f,25f,0f,false))),false,null)
        val response=LensProtocol.Response(listOf(paragraph),true,0,shape)
        assertEquals(2,LensProtocol.toBlocks(response,1000,1000,1000,1000).size)
    }
    @Test fun cleanedCanvasDoesNotLeakIntoTheNextGeneration() {
        val a="ns-canvas:0,0,810x1210@9_31_810_1210"
        val b="ns-canvas:0,0,810x1210@9_32_810_1210"
        WebModeTextRemovalCache.storeCanvasCleaned(a,"data:image/png;base64,TEST_A")
        assertEquals("data:image/png;base64,TEST_A",WebModeTextRemovalCache.urlFor(a))
        assertNull(WebModeTextRemovalCache.urlFor(b))
    }
    @Test fun manualRecoveryIsNotCappedAtOneAndHalfSeconds() {
        assertTrue(com.ashraf.novelstudio.manualextract.ManualExtractConfig.OCR_CAP_MS >= 30_000)
        assertFalse(com.ashraf.novelstudio.manualextract.ManualExtractConfig.USE_TRANSLATION_CACHE)
    }
}
