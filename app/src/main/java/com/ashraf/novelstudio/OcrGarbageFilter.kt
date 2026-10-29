package com.ashraf.novelstudio

import android.util.Log

/** Rejects high-confidence script mismatches and common recognizer fragments before translation. */
internal object OcrGarbageFilter {
    private const val TAG = "NovelStudioOCR"
    private val koreanOnomatopoeia = setOf("아", "어", "흐", "음", "핫", "윽", "헉", "흡")
    private val japaneseOnomatopoeia = setOf("あ", "う", "え", "お", "ん", "ハ", "ふ", "ぁ", "っ")
    private val knownKoreanNoise = setOf("려은걱해야하고싶")

    fun filter(blocks: List<OcrBlock>, script: OcrScript, width: Int, height: Int): List<OcrBlock> {
        if (blocks.isEmpty()) return blocks
        val withLetters = blocks.filter { it.text.any(Char::isLetter) }
        val panelIsLatin = withLetters.isNotEmpty() && withLetters.all { block ->
            val letters = block.text.filter(Char::isLetter)
            letters.isNotEmpty() && letters.all {
                Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
            }
        }
        return blocks.filter { block ->
            val reason = rejectReason(block, script, width, height, panelIsLatin)
            if (reason == null) true else {
                val safeText = block.text.replace("\\", "\\\\")
                    .replace("\"", "\\\"").replace("\n", " ").replace("\r", " ").take(100)
                try { Log.i(TAG, "FILTER_REJECT: reason=$reason text=\"$safeText\"") } catch (_: Throwable) {}
                false
            }
        }
    }

    internal fun rejectReason(
        block: OcrBlock, script: OcrScript, width: Int, height: Int, panelIsLatin: Boolean
    ): String? {
        val text = block.text.trim()
        if (text.isEmpty()) return "low_conf"
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return null

        val latin = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        val hangul = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }
        val kana = letters.count {
            val value = Character.UnicodeScript.of(it.code)
            value == Character.UnicodeScript.HIRAGANA || value == Character.UnicodeScript.KATAKANA
        }
        val han = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val compact = text.filter(Char::isLetterOrDigit)
        val onomatopoeia = compact in (koreanOnomatopoeia + japaneseOnomatopoeia)

        if (letters.size == 1 && latin == 1) return "single_char"

        if (script == OcrScript.KOREAN) {
            if (compact in knownKoreanNoise) return "latin_mixed"
            // Mixed Hangul + Latin fragments are a common Lens/ML Kit hallucination, even for a short "하c".
            if (latin > 0 && hangul > 0) return "latin_mixed"
            // Tiny Latin fragments such as YO / P are not dialogue on a Korean panel.
            if (latin in 1..2 && hangul == 0 && !panelIsLatin) return "single_char"
            if (latin > 4 && !panelIsLatin) return "latin_mixed"
        }

        // A 0.0 confidence means that this OCR engine does not report confidence, not a measured zero.
        if (block.confidence > 0f && block.confidence < 0.35f && text.length < 4 && !onomatopoeia) {
            return "low_conf"
        }

        val expected = when (script) {
            OcrScript.KOREAN -> if (panelIsLatin) latin else hangul
            OcrScript.JAPANESE -> kana + han
            OcrScript.CHINESE -> han
            OcrScript.LATIN -> latin
        }
        if (letters.isNotEmpty() && (letters.size - expected) * 2 > letters.size) return "script_mismatch"

        if (letters.size == 1 && !onomatopoeia &&
            (hangul == 1 || script == OcrScript.JAPANESE && (kana + han) == 1)) {
            return "single_char"
        }

        if (width > 0 && height > 0 && block.w > 0f && block.h > 0f) {
            val area = block.w * block.h
            if (area > 0f) {
                val left = block.cx - block.w / 2f
                val top = block.cy - block.h / 2f
                val right = block.cx + block.w / 2f
                val bottom = block.cy + block.h / 2f
                val insideW = (minOf(right, width.toFloat()) - maxOf(left, 0f)).coerceAtLeast(0f)
                val insideH = (minOf(bottom, height.toFloat()) - maxOf(top, 0f)).coerceAtLeast(0f)
                val visibleFraction = (insideW * insideH / area).coerceIn(0f, 1f)
                if (1f - visibleFraction > 0.5f) return "border"
            }
        }
        return null
    }
}
