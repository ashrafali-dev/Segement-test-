package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrGarbageFilterTest {
    private fun b(text: String, confidence: Float = 0.9f, cx: Float = 50f, cy: Float = 50f,
                  w: Float = 40f, h: Float = 16f) =
        OcrBlock(text, (cy - h / 2).toInt(), (cx - w / 2).toInt(), cx, cy, w, h,
            confidence = confidence)

    @Test fun koreanShortDialoguePassesWithoutNeedingAnAllowlistMatch() {
        listOf("뭐?", "야.", "응?", "탓", "왜?", "우", "사", "탁", "아", "어", "와!", "네.", "예", "훗", "헤", "후")
            .forEach { value -> assertNull("$value should pass",
                OcrGarbageFilter.rejectReason(b(value), OcrScript.KOREAN, 100, 100, false)) }
    }

    @Test fun twoHangulSyllablesPassAtNormalConfidence() {
        listOf("토톡", "뭐냐?", "흐음..").forEach { value -> assertNull("$value should pass",
            OcrGarbageFilter.rejectReason(b(value, confidence = 0.8f), OcrScript.KOREAN, 100, 100, false)) }
    }

    @Test fun japaneseOneAndTwoCharacterKanaDialoguePasses() {
        listOf("あ", "う", "え", "お", "ん", "ハ", "ふ", "ぁ", "っ", "あっ")
            .forEach { value -> assertNull("$value should pass",
                OcrGarbageFilter.rejectReason(b(value), OcrScript.JAPANESE, 100, 100, false)) }
    }

    @Test fun shortLatinFragmentsAreRejected() {
        listOf("Yo", "B", "th").forEach { value -> assertEquals("$value should be rejected", "single_char",
            OcrGarbageFilter.rejectReason(b(value), OcrScript.KOREAN, 100, 100, false)) }
    }

    @Test fun punctuationOnlyIsRejected() {
        listOf("!", "?!", "...").forEach { value -> assertEquals("single_char",
            OcrGarbageFilter.rejectReason(b(value), OcrScript.KOREAN, 100, 100, false)) }
    }

    @Test fun lowConfidenceShortDialogueIsRejected() {
        listOf("뭐?", "가나").forEach { value -> assertEquals("low_conf",
            OcrGarbageFilter.rejectReason(b(value, confidence = 0.2f), OcrScript.KOREAN, 100, 100, false)) }
    }

    @Test fun mixedLatinHangulIsRejected() {
        assertEquals("latin_mixed", OcrGarbageFilter.rejectReason(b("하c"), OcrScript.KOREAN, 100, 100, false))
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

    @Test fun boxMostlyOutsidePanelIsRejected() {
        assertEquals("border", OcrGarbageFilter.rejectReason(
            b("안녕하세요", cx = -50f, cy = 50f, w = 120f, h = 20f),
            OcrScript.KOREAN, 100, 100, false))
    }
}
