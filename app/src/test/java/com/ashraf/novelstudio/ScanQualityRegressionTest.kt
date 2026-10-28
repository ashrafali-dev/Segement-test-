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

    // The three tests below were written against an older BubbleGrouping/OcrPost API (isAsidePair,
    // mergeNeighbours(verticalOnly)); that API no longer exists on this branch, so they could not compile or run.

    @Test fun incompleteReplyIsPlacedPartiallyNotDropped() {
        // 3 of 4 answered in order: the placed lines carry their own numbers, so they are unambiguous.
        // Refusing them (the old rule) left the whole page untranslated for one lost marker.
        val r = WebModeLayout.alignResult("[001] one [002] two [003] three", layout(4))
        assertTrue(r is WebModeLayout.Companion.AlignResult.Accepted)
        r as WebModeLayout.Companion.AlignResult.Accepted
        assertTrue(r.partial)
        assertEquals(listOf("one","two","three"), r.values.filterNotNull())
        assertEquals(listOf(3), r.missing)
    }
    @Test fun singleAnswerOnTwoBubblePageIsPlacedAtItsOwnMarker() {
        // Field log: bubbles=2 markers=2 placed=1 need=2 outOfRange=0 dup=0 empty=1 ascending=true -> REJECT
        val r = WebModeLayout.alignResult("[001] one [002] ~", layout(2))
        assertTrue(r is WebModeLayout.Companion.AlignResult.Accepted)
        assertEquals(listOf("one", null), (r as WebModeLayout.Companion.AlignResult.Accepted).values)
    }
    @Test fun farTooLittleCoverageIsStillRejected() {
        assertNull(WebModeLayout.align("[001] one", layout(10)))
    }
    @Test fun duplicateMarkerIsAmbiguousEvenWithFullCoverage() {
        assertNull(WebModeLayout.align("[001] one [001] wrong [002] two",layout(2)))
    }
    @Test fun missingBubbleCannotBeHiddenByTitleMarker() {
        // Title offset: [001] is the title, so only 2 of the 4 bubbles are answered. The answers still land on the
        // bubbles they name; the title is not a bubble and bubble 4 keeps its own (blank) slot.
        assertEquals(listOf(null,"one","two",null),
            WebModeLayout.align("[001] title [002] one [003] two [004] three",layout(4,1)))
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
    // manualRecoveryIsNotCappedAtOneAndHalfSeconds was removed: it asserted an OCR_CAP_MS >= 30 s and the
    // absence of a USE_TRANSLATION_CACHE flag, neither of which exists in the shipped ManualExtractConfig
    // (the cap is 1.5 s and the cache is ManualExtractCache). A test that cannot compile proved nothing about
    // the code it claimed to protect; the cap is now covered by the Defect C chain tests where it matters.
}
