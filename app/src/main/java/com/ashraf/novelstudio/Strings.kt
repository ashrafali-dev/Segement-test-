package com.ashraf.novelstudio

/**
 * Every string of the Toon Sensei UI lives here, so the interface language can be changed later
 * without touching the screens. Default is English, matching the existing code.
 *
 * Formatting uses explicit replaces instead of format strings, so a missing argument can never throw.
 */
object Strings {

    // dock
    const val MODE_NOVEL = "Novel"
    const val MODE_SCAN = "Scan"
    const val MODE_LIVE = "Live"
    const val MODE_LITE = "Lite"
    const val ACTION_TRANSLATE = "Translate"
    const val ACTION_STOP = "Stop"
    const val ACTION_SCAN = "Scan"
    const val ACTION_PAUSE = "Pause"
    const val ACTION_RESUME = "Resume"
    const val ACTION_READ = "Read"
    const val ACTION_START = "Start"
    const val PRIOR = "Previous"
    const val NEXT = "Next"

    // chat ring / status
    const val CHATBOT = "Chatbot"
    const val WAITING = "Waiting"
    const val TRANSLATING = "Translating"
    const val CHECKING = "Checking the answer"
    const val READING = "Reading"
    const val PLACING = "Adding the translation"
    const val IDLE = "Idle"
    const val WORKING_ELSE = "Working"
    const val WAITING_FOR_CHATBOT = "Waiting for the chatbot"

    // tasks tray
    const val TASKS = "Tasks"
    const val STOP_ALL = "Stop all"
    const val ROW_CHATBOT = "Chatbot"
    const val ROW_NEXT = "Next chapter"
    const val ROW_AHEAD = "Read ahead"
    const val ROW_QUEUE = "Queue"
    const val AHEAD = "Ahead"
    const val AHEAD_DESC = "Translates the next chapter while you read. Uses more chatbot messages. Novel and Scanning only."
    const val RESEND = "Resend"
    const val OPEN_CHATBOT = "Open chatbot"
    const val LOG = "Log"
    const val LOG_EMPTY = "Nothing yet."

    // top bar
    const val RELOAD = "Reload"
    const val STOP_LOADING = "Stop loading"
    const val OPEN_TABS = "Open tabs"
    const val MORE = "More"
    const val SLOW_LOAD = "Still loading. The connection is slow."

    // mode sheet
    const val MODE_TITLE = "Mode"
    const val DESC_NOVEL = "Translate chapter text on the page"
    const val DESC_SCAN = "Prepare a whole comic chapter"
    const val DESC_LIVE = "Translate as you scroll"
    const val DESC_LITE = "Read one screen when you tap. Light on battery and RAM"

    // notices
    const val DISMISS = "Dismiss"
    const val RETRY = "Retry"

    // home / entry (phase 2)
    const val WORDMARK = "Toon Sensei"
    const val SEARCH_HINT = "Search or paste a link"
    const val LIBRARY = "Library"
    const val MENU = "Menu"
    const val LINK_TRANSLATE = "Link → Translate"
    const val SOON = "Soon"

    // modes on the home chips
    const val CHIP_NOVEL = "Novel"
    const val CHIP_SCANNING = "Scanning"
    const val CHIP_REALTIME = "Real-time"
    const val CHIP_LITE = "Manual Lite"

    /** "42 / 120" or "42" when there is no total. */
    fun count(n: Int, total: Int?): String = if (total != null && total > 0) "$n / $total" else n.toString()

    /** "Waiting · 25 s · 42/120" */
    fun statusLine(state: String, seconds: Int, n: Int, total: Int?): String {
        val parts = ArrayList<String>(3)
        parts.add(state)
        if (seconds > 0) parts.add("${seconds} s")
        if (total != null && total > 0) parts.add("$n/$total")
        return parts.joinToString(" · ")
    }

    /** "Found 6 names" */
    fun found(n: Int) = "Found $n names"
}
