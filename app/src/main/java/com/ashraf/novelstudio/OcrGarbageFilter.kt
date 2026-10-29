package com.ashraf.novelstudio

/** Conservative script/noise filtering. Short dialogue is not garbage merely because it is short. */
internal object OcrGarbageFilter {
    private val koreanShortDialogue = setOf(
        "아", "어", "응", "와", "야", "왜", "뭐", "네", "예", "헉", "윽", "흡", "훗", "헤", "후",
        "탓", "우", "사", "탁", "흐", "음", "핫"
    )
    private val japaneseOnomatopoeia = setOf("あ", "う", "え", "お", "ん", "ハ", "ふ", "ぁ", "っ")
    private val knownKoreanNoise = setOf("려은걱해야하고싶")
    private fun scriptOf(ch: Char): Character.UnicodeScript = Character.UnicodeScript.of(ch.code)
    private fun confidenceKnown(confidence: Float): Boolean = confidence > 0f

    private fun shortDialoguePasses(block: OcrBlock, script: OcrScript): Boolean {
        val compact = block.text.trim().filter(Char::isLetterOrDigit)
        if (compact.isEmpty() || compact.length > 2) return false
        if (confidenceKnown(block.confidence) && block.confidence < 0.35f) return false
        val letters = compact.filter(Char::isLetter)
        if (letters.isEmpty()) return false
        return when (script) {
            OcrScript.KOREAN -> letters.all { scriptOf(it) == Character.UnicodeScript.HANGUL } &&
                (compact in koreanShortDialogue || letters.length <= 2)
            OcrScript.JAPANESE -> letters.all {
                scriptOf(it) == Character.UnicodeScript.HIRAGANA ||
                    scriptOf(it) == Character.UnicodeScript.KATAKANA
            } && (compact in japaneseOnomatopoeia || letters.length <= 2)
            else -> false
        }
    }

    fun filter(blocks: List<OcrBlock>, script: OcrScript, width: Int, height: Int): List<OcrBlock> {
        if (blocks.isEmpty()) return blocks
        val withLetters = blocks.filter { it.text.any(Char::isLetter) }
        val panelIsLatin = withLetters.isNotEmpty() && withLetters.all { block ->
            val letters = block.text.filter(Char::isLetter)
            letters.isNotEmpty() && letters.all { scriptOf(it) == Character.UnicodeScript.LATIN }
        }
        return blocks.filter { block ->
            val reason = rejectReason(block, script, width, height, panelIsLatin)
            val safeText = block.text.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", " ").replace("\r", " ").take(100)
            if (reason == null) {
                if (shortDialoguePasses(block, script)) {
                    val conf = String.format(java.util.Locale.US, "%.2f", block.confidence)
                    try { OcrDiag.log("FILTER_PASS", "reason=short_dialogue text=\"$safeText\" conf=$conf") } catch (_: Throwable) {}
                }
                true
            } else {
                try { OcrDiag.log("FILTER_REJECT", "reason=$reason text=\"$safeText\"") } catch (_: Throwable) {}
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
        val compact = text.filter(Char::isLetterOrDigit)
        if (letters.isEmpty()) return "single_char" // punctuation-only/noise
        val latin = letters.count { scriptOf(it) == Character.UnicodeScript.LATIN }
        val hangul = letters.count { scriptOf(it) == Character.UnicodeScript.HANGUL }
        val kana = letters.count {
            val s = scriptOf(it)
            s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA
        }
        val han = letters.count { scriptOf(it) == Character.UnicodeScript.HAN }

        if (script == OcrScript.KOREAN) {
            if (compact in knownKoreanNoise) return "latin_mixed"
            if (hangul > 0 && (latin > 4 || (latin == 1 && hangul == 1))) return "latin_mixed"
            if (latin > 4 && !panelIsLatin) return "latin_mixed"
        }
        // 0.0 means the OCR engine does not expose confidence, not a measured zero.
        if (compact.length <= 2 && confidenceKnown(block.confidence) && block.confidence < 0.35f) {
            return "low_conf"
        }
        val nonLatin = hangul + kana + han
        if (compact.length <= 2 && latin > 0 && latin >= nonLatin) return "single_char"

        val expected = when (script) {
            OcrScript.KOREAN -> if (panelIsLatin) latin else hangul
            OcrScript.JAPANESE -> kana + han
            OcrScript.CHINESE -> han
            OcrScript.LATIN -> latin
        }
        if ((letters.length - expected) * 2 > letters.length) return "script_mismatch"

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
