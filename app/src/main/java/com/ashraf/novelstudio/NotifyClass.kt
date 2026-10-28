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

    /** Strip emoji/leading symbols so the log stays plain text (no emoji in the UI chrome). */
    fun clean(message: String): String {
        val s = StringBuilder()
        var i = 0
        while (i < message.length) {
            val cp = message.codePointAt(i)
            val isEmoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
                cp == 0xFE0F || cp == 0x200D
            if (!isEmoji) s.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return s.toString().replace(Regex("\\s+"), " ").trim()
    }
}
