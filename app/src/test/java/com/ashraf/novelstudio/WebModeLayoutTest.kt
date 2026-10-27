package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests of the recycled-canvas transition decision (no Android types). */
class WebModeLayoutTest {
    private val src = "ns-canvas:0,0,800x1200"
    private fun b(text: String, cx: Float, cy: Float) =
        WmBubble(src = src, cx = cx, cy = cy, w = 0.2f, h = 0.08f, angle = 0f, font = 0.02f,
            bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), sourceText = text)

    private val pageA = listOf(
        b("where are you going", 0.3f, 0.2f), b("I have to leave now", 0.7f, 0.4f), b("please wait for me", 0.4f, 0.7f))

    @Test fun canvasLifecycleIsSelectedByCanvasSourceNotHost() {
        assertTrue(WebModeLayoutStore.hasCanvasSources(WebModeLayout(0, pageA)))
        val ordinaryImageLayout = WebModeLayout(0, pageA.map {
            it.copy(src = "https://cdn.example.test/manga/page-1.jpg")
        })
        assertFalse(WebModeLayoutStore.hasCanvasSources(ordinaryImageLayout))
    }

    @Test fun resizedCanvasUsesTheSameStableSlot() {
        val resized = pageA.map { it.copy(src = "ns-canvas:0,0,900x1350") }
        assertTrue(WebModeLayoutStore.sameCanvasSource(src, "ns-canvas:0,0,900x1350"))
        assertTrue(WebModeLayoutStore.canvasSourceSlot(src) == WebModeLayoutStore.canvasSourceSlot("ns-canvas:0,0,900x1350"))
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA, resized))
    }

    @Test fun changedPageOnResizedCanvasIsStillATransition() {
        val other = listOf(
            b("different page line one", 0.1f, 0.1f),
            b("different page line two", 0.5f, 0.5f),
            b("different page line three", 0.9f, 0.9f)
        ).map { it.copy(src = "ns-canvas:0,0,900x1350") }
        assertTrue(WebModeLayoutStore.canvasTransitionDetected(pageA, other))
    }

    @Test fun canvasGenerationIdentitySurvivesSpaUrlAndSizeChanges() {
        val pageOne = WebModeLayoutStore.canvasIdentityKey(
            "https://reader.example/chapter/1", "ns-canvas:0,0,800x1200"
        )
        val nextPage = WebModeLayoutStore.canvasIdentityKey(
            "https://reader.example/chapter/2", "ns-canvas:0,0,900x1350"
        )
        val otherHost = WebModeLayoutStore.canvasIdentityKey(
            "https://other.example/chapter/2", "ns-canvas:0,0,900x1350"
        )
        assertTrue(pageOne == nextPage)
        assertFalse(pageOne == otherHost)
    }

    @Test fun differentCanvasSlotsDoNotMatch() {
        assertFalse(WebModeLayoutStore.sameCanvasSource(src, "ns-canvas:0,30,800x1200"))
    }

    @Test fun sparseTotallyDifferentReadIsATransition() {
        val fresh = listOf(b("completely other words", 0.5f, 0.1f), b("nothing in common here", 0.2f, 0.9f))
        assertTrue(WebModeLayoutStore.canvasTransitionDetected(pageA, fresh))
    }

    @Test fun reReadOfTheSamePageIsNotATransition() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA, pageA))
    }

    @Test fun sparseReReadOfSomeOfTheSameBubblesIsNotATransition() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA, listOf(pageA[0], pageA[2])))
    }

    @Test fun noisyReReadStillExplainsItself() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA, listOf(b("where are you goinq", 0.3f, 0.2f))))
    }

    @Test fun priorWithFewerThanThreeBubblesIsInconclusive() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA.take(2), listOf(b("other words", 0.5f, 0.5f))))
    }

    @Test fun fullDifferentPageIsStillATransition() {
        val other = listOf(b("aaa bbb ccc", 0.1f, 0.1f), b("ddd eee fff", 0.5f, 0.5f), b("ggg hhh iii", 0.9f, 0.9f))
        assertTrue(WebModeLayoutStore.canvasTransitionDetected(pageA, other))
    }
}

class WebModeSamePositionTest {
    private val src = "https://x.test/p.jpg"
    private fun b(cx: Float, cy: Float, w: Float, h: Float) =
        WmBubble(src = src, cx = cx, cy = cy, w = w, h = h, angle = 0f, font = 0.01f,
            bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), sourceText = "x")

    @Test fun twoBubblesOneUnderTheOtherOnATallStripAreNotTheSame() {
        // 12000 px tall strip: 0.009 of the height is ~108 px. The old fixed 0.012 tolerance called these "the same".
        assertFalse(WebModeLayoutStore.samePosition(b(0.5f, 0.500f, 0.6f, 0.008f), b(0.5f, 0.509f, 0.6f, 0.008f)))
    }

    @Test fun aSmallJitterOfTheSameBubbleIsStillTheSame() {
        assertTrue(WebModeLayoutStore.samePosition(b(0.5f, 0.500f, 0.6f, 0.008f), b(0.501f, 0.5005f, 0.58f, 0.0078f)))
    }

    @Test fun differentSizeIsNotTheSameBubble() {
        assertFalse(WebModeLayoutStore.samePosition(b(0.5f, 0.5f, 0.6f, 0.008f), b(0.5f, 0.5f, 0.6f, 0.02f)))
    }

    @Test fun identicalTextInTwoDifferentBubblesHasDistinctIdentity() {
        // Two bubbles that both say "!" (extremely common in comics) must not share a cache identity,
        // otherwise the translation produced for one is drawn over the other.
        val first = b(0.25f, 0.20f, 0.2f, 0.08f).copy(sourceText = "!")
        val second = b(0.75f, 0.60f, 0.2f, 0.08f).copy(sourceText = "!")
        assertEquals(first.sourceText, second.sourceText)
        assertNotEquals(WebModeLayout.bubbleIdentity(first), WebModeLayout.bubbleIdentity(second))
        assertNotEquals(WebModeLayout.fingerprint(first), WebModeLayout.fingerprint(second))
    }

    @Test fun identicalTextAtTheSamePlaceKeepsOneIdentity() {
        // The same bubble re-read after a scroll must keep its identity so it is not re-translated.
        val a = b(0.40f, 0.55f, 0.2f, 0.08f).copy(sourceText = "wait")
        val same = b(0.40f, 0.55f, 0.2f, 0.08f).copy(sourceText = "wait")
        assertEquals(WebModeLayout.bubbleIdentity(a), WebModeLayout.bubbleIdentity(same))
        assertEquals(WebModeLayout.fingerprint(a), WebModeLayout.fingerprint(same))
    }
}

class WebModeTextRemovalSafetyTest {
    private val src = "https://cdn.example.test/chapter/page-1.jpg"

    private fun bubble(text: String, cx: Float, cy: Float) = WmBubble(
        src = src,
        cx = cx, cy = cy, w = 0.22f, h = 0.10f,
        angle = 0f, font = 0.025f,
        bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(),
        sourceText = text
    )

    private fun line(text: String, cx: Float, cy: Float) = OcrBlock(
        text = text, top = cy.toInt(), left = cx.toInt(),
        cx = cx, cy = cy, w = 110f, h = 24f
    )

    @Test fun oneChangedBubbleNeverMarksEveryBubbleOnTheImageErased() {
        val first = bubble("first bubble", 0.25f, 0.25f)
        val second = bubble("second bubble", 0.75f, 0.75f)
        val sourceLines = listOf(line("first bubble", 250f, 250f), line("second bubble", 750f, 750f))
        val groups = WebModeTextRemover.recognizedLinesByBubble(listOf(first, second), sourceLines, 1000, 1000)
        assertEquals(2, groups.size)

        val erased = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<OcrBlock, Boolean>()
        )
        erased.add(sourceLines[0])
        assertEquals(
            setOf(WebModeTextRemovalCache.bubbleKey(first)),
            WebModeTextRemover.erasedBubbleKeys(groups, erased)
        )
        erased.add(sourceLines[1])
        assertEquals(
            setOf(WebModeTextRemovalCache.bubbleKey(first), WebModeTextRemovalCache.bubbleKey(second)),
            WebModeTextRemover.erasedBubbleKeys(groups, erased)
        )
    }

    @Test fun aBubbleWithMultipleSourceLinesIsNotTransparentUntilEveryLineWasErased() {
        val b = bubble("first line second line", 0.5f, 0.5f).copy(w = 0.40f, h = 0.20f)
        val first = line("first line", 470f, 490f)
        val second = line("second line", 530f, 510f)
        val groups = WebModeTextRemover.recognizedLinesByBubble(listOf(b), listOf(first, second), 1000, 1000)
        val key = WebModeTextRemovalCache.bubbleKey(b)
        assertEquals(2, groups.getValue(key).size)

        val erased = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<OcrBlock, Boolean>()
        )
        erased.add(first)
        assertTrue(WebModeTextRemover.erasedBubbleKeys(groups, erased).isEmpty())
        erased.add(second)
        assertEquals(setOf(key), WebModeTextRemover.erasedBubbleKeys(groups, erased))
    }

    @Test fun expandedCoverAndOriginalOcrGeometryRemainSeparate() {
        val original = bubble("source lettering", 0.5f, 0.5f).copy(
            w = 0.72f, h = 0.38f,
            ocrCx = 0.5f, ocrCy = 0.49f, ocrW = 0.21f, ocrH = 0.07f
        )
        // Adding/replacing translated text must not collapse the original OCR rectangle into the larger cover box.
        val copied = original.copy(translatedText = "অনুবাদ")
        assertEquals(0.72f, copied.w, 0.00001f)
        assertEquals(0.38f, copied.h, 0.00001f)
        assertEquals(0.21f, copied.ocrW, 0.00001f)
        assertEquals(0.07f, copied.ocrH, 0.00001f)
        assertEquals(0.49f, copied.ocrCy, 0.00001f)
        assertEquals(0.5f, copied.ocrCx, 0.00001f)
    }
}
