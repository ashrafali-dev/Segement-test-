package com.ashraf.novelstudio

/**
 * Decides what an old `toast("…")` call should become, without any Android dependency (unit-tested).
 *
 * The old code has ~190 toast calls. Until each one is converted by hand, the shim uses this:
 *  - a message that reports a failure or asks the user to act  -> type C banner
 *  - everything else                                          -> the quiet Tasks log
 * Nothing ever appears at the bottom of the screen again.
 */
object NotifyClass {

    enum class Kind { BANNER, LOG }

    private val NEEDS_YOU = Regex(
        "could not|couldn't|failed|failure|error|not found|login|log in|captcha|stuck|" +
            "can't|cannot|unable|no answer|did not|not ready|blocked|denied|expired|" +
            "press send|paste it|try again|still running|already running",
        RegexOption.IGNORE_CASE
    )

    /** Sentences that are success confirmations even though they contain a scary word. */
    private val SUCCESS = Regex(
        "copied|saved|deleted|stopped|enabled|done|ready|reloaded|refreshing|learned|cleared",
        RegexOption.IGNORE_CASE
    )

    fun classify(message: String): Kind {
        // Leading emoji carry no meaning for the decision and clean() strips them for display anyway.
        val m = message.trim()
        if (m.isEmpty()) return Kind.LOG
        // A leading "could not" that is actually "Translation stopped" style is rare; the success list wins
        // only for short, clearly-positive messages, so "Could not save" still becomes a banner.
        if (NEEDS_YOU.containsMatchIn(m)) {
            if (m.length <= 40 && SUCCESS.containsMatchIn(m) && !Regex("^(could not|couldn't|cannot|can't|failed)", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
                return Kind.LOG
            }
            return Kind.BANNER
        }
        return Kind.LOG
    }

    /**
     * Keep log lines plain text (no emoji in the UI chrome).
     *
     * Two passes, so real text is never damaged:
     *  1) inside the line, remove only the unambiguous emoji planes (U+1F000+, ⚡/✅/❌/⚠, ★, ZWJ);
     *     arrows and "→" stay, because "Link → Translate" is a real label.
     *  2) at the very start, drop a leading pictograph/arrow/showstopper the old code used as an icon
     *     (⏹ ⏸ ▶ ⟳ ▪ ← …), which is what "leading icon + text" toasts look like.
     */
    fun clean(message: String): String {
        val s = StringBuilder()
        var i = 0
        while (i < message.length) {
            val cp = message.codePointAt(i)
            val emoji = cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF ||   // ⚡ ✅ ❌ ⚠ ☀ ★ ✅
                cp in 0x2B00..0x2BFF ||   // ⬛ ⭐
                cp == 0xFE0F || cp == 0x200D || cp == 0x20E3
            if (!emoji) s.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        var text = s.toString().trimStart()
        while (text.isNotEmpty()) {
            val cp = text.codePointAt(0)
            val leadIcon = cp in 0x2190..0x21FF ||   // arrows (⟳ ⟲ ← →) as a leading icon
                cp in 0x2300..0x23FF ||              // ⏹ ⏸ ⏳ ⏱
                cp in 0x25A0..0x25FF ||              // ▶ ◀ ■
                Character.isWhitespace(cp) || Character.getType(cp) == Character.FORMAT.toInt()
            if (!leadIcon) break
            text = text.substring(Character.charCount(cp))
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
