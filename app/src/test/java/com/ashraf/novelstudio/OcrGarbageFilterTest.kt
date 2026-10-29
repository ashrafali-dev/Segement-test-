package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrGarbageFilterTest {
    private fun b(text: String, confidence: Float = 0.9f, cx: Float = 50f, cy: Float = 50f,
                  w: Float = 40f, h: Float = 16f) =
        OcrBlock(text, (cy - h / 2).toInt(), (cx - w / 2).toInt(), cx, cy, w, h,
            confidence = confidence)

    @Test fun koreanOnomatopoeiaPasses() {
        assertNull(OcrGarbageFilter.rejectReason(b("아"), OcrScript.KOREAN, 100, 100, false))
    }

    @Test fun mixedLatinHangulIsRejected() {
        assertEquals("latin_mixed", OcrGarbageFilter.rejectReason(b("하c"), OcrScript.KOREAN, 100, 100, false))
    }

    @Test fun shortLatinFragmentsAreRejectedOnKoreanPanels() {
        assertEquals("single_char", OcrGarbageFilter.rejectReason(b("YO"), OcrScript.KOREAN, 100, 100, false))
        assertEquals("single_char", OcrGarbageFilter.rejectReason(b("P"), OcrScript.KOREAN, 100, 100, false))
    }

    @Test fun observedKoreanGarbageIsRejected() {
        assertEquals("latin_mixed", OcrGarbageFilter.rejectReason(
            b("려은 걱 해야 하고 싶"), OcrScript.KOREAN, 100, 100, false))
    }

    @Test fun hanCharacterOnKoreanPanelIsScriptMismatch() {
        assertEquals("script_mismatch", OcrGarbageFilter.rejectReason(b("宮"), OcrScript.KOREAN, 100, 100, false))
    }

    @Test fun longLatinFragmentNeedsPanelWideLatinEvidence() {
        assertEquals("latin_mixed", OcrGarbageFilter.rejectReason(
            b("Hemingway Re"), OcrScript.KOREAN, 100, 100, false))
        assertNull(OcrGarbageFilter.rejectReason(
            b("Hemingway Re"), OcrScript.KOREAN, 100, 100, true))
    }

    @Test fun validKoreanDialoguePasses() {
        listOf("뭐냐?", "흐음..", "토톡", "넘 맛있었당").forEach { value ->
            assertNull(value, OcrGarbageFilter.rejectReason(b(value), OcrScript.KOREAN, 100, 100, false))
        }
    }

    @Test fun lowConfidenceShortTextIsRejected() {
        assertEquals("low_conf", OcrGarbageFilter.rejectReason(
            b("가나", confidence = 0.2f), OcrScript.KOREAN, 100, 100, false))
    }

    @Test fun boxMostlyOutsidePanelIsRejected() {
        assertEquals("border", OcrGarbageFilter.rejectReason(
            b("안녕하세요", cx = -50f, cy = 50f, w = 120f, h = 20f),
            OcrScript.KOREAN, 100, 100, false))
    }
}
