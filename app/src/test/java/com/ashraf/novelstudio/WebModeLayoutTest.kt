package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}


/** Marker parser regression tests: skipped OCR noise must not shift or cancel valid replies. */
class WebModePartialAlignmentTest {
    private val src = "ns-canvas:0,0,800x1200"

    private fun bubble(text: String, index: Int) = WmBubble(
        src = src, cx = 0.2f + index * 0.2f, cy = 0.2f, w = 0.12f, h = 0.08f,
        angle = 0f, font = 0.02f, bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(),
        sourceText = text
    )

    private fun layout(n: Int = 3) = WebModeLayout(0, (1..n).map { bubble("source $it", it) })

    @Test fun blankMarkerDoesNotDiscardOtherTranslations() {
        val aligned = WebModeLayout.align("[001] হ্যালো\\n\\n[002]   \\n\\n[003] কেমন আছো", layout())
        assertEquals(listOf("হ্যালো", null, "কেমন আছো"), aligned)
    }

    @Test fun omittedMarkerLeavesItsOwnSlotEmptyWithoutShiftingLaterAnswers() {
        val aligned = WebModeLayout.align("[001] প্রথম\\n\\n[003] তৃতীয়", layout())
        assertEquals(listOf("প্রথম", null, "তৃতীয়"), aligned)
    }

    @Test fun outOfOrderMarkersStillMapByTheirExplicitIds() {
        val aligned = WebModeLayout.align("[003] তৃতীয়\\n\\n[001] প্রথম", layout())
        assertEquals(listOf("প্রথম", null, "তৃতীয়"), aligned)
    }

    @Test fun duplicateMarkerOnlyInvalidatesThatAmbiguousSlot() {
        val aligned = WebModeLayout.align(
            "[001] প্রথম উত্তর\\n\\n[001] অন্য উত্তর\\n\\n[002] দ্বিতীয়\\n\\n[003] তৃতীয়",
            layout()
        )
        assertEquals(listOf(null, "দ্বিতীয়", "তৃতীয়"), aligned)
    }

    @Test fun tildePlaceholderDoesNotDiscardOtherTranslations() {
        val aligned = WebModeLayout.align("[001] প্রথম\\n\\n[002] ~\\n\\n[003] তৃতীয়", layout())
        assertEquals(listOf("প্রথম", null, "তৃতীয়"), aligned)
    }

    @Test fun allEmptyEntriesAreRejectedRatherThanReportedAsSuccess() {
        assertNull(WebModeLayout.align("[001]\\n\\n[002]\\n\\n[003] ~", layout()))
    }
}
