package com.ashraf.novelstudio

import android.content.Context

/**
 * Chatbot-independent webtoon real-time mode.
 *
 * Keeps its own preference snapshot so switching it off restores the user's
 * previous extraction/chatbot settings instead of permanently changing them.
 */
internal object DeepSeekRealtimeTranslation {
    const val DEEPSEEK_URL = "https://chat.deepseek.com/"

    private const val ENABLED = "deepSeekRealtimeTranslation"
    private const val SAVED_WEB_MODE = "dsrtSavedWebMode"
    private const val SAVED_NO_AUTO = "dsrtSavedNoAuto"
    private const val SAVED_AUTO_EXTRACT_NEXT = "dsrtSavedAutoExtractNext"
    private const val SAVED_BOT_URL = "dsrtSavedBotUrl"

    fun isEnabled(context: Context): Boolean = Prefs.bool(context, ENABLED)

    fun menuLabel(context: Context): String =
        (if (isEnabled(context)) "✅" else "⬜") +
            " 🤖 AI Real-time Translation"

    fun enable(context: Context) {
        if (!isEnabled(context)) {
            Prefs.putBool(context, SAVED_WEB_MODE, Prefs.bool(context, "webMode"))
            Prefs.putBool(context, SAVED_NO_AUTO, Prefs.bool(context, "noAuto"))
            Prefs.putBool(context, SAVED_AUTO_EXTRACT_NEXT, Prefs.bool(context, "autoExtractNext", true))
            Prefs.put(context, SAVED_BOT_URL,
                Prefs.get(context, "botUrl", "https://chatgpt.com/").ifBlank { "https://chatgpt.com/" })
        }

        // Reuse the existing OCR + chatbot + translation-panel pipeline.
        Prefs.putBool(context, ENABLED, true)
        Prefs.putBool(context, "webMode", true)
        Prefs.putBool(context, "noAuto", false)
        Prefs.putBool(context, "autoExtractNext", true)
        // Keep the currently selected chatbot; real-time OCR is provider-independent.
    }

    /** Returns the chatbot URL that was selected before this mode was enabled. */
    fun disable(context: Context): String {
        val previousBot = Prefs.get(context, SAVED_BOT_URL, "https://chatgpt.com/")
            .ifBlank { "https://chatgpt.com/" }

        Prefs.putBool(context, ENABLED, false)
        Prefs.putBool(context, "webMode", Prefs.bool(context, SAVED_WEB_MODE))
        Prefs.putBool(context, "noAuto", Prefs.bool(context, SAVED_NO_AUTO))
        Prefs.putBool(context, "autoExtractNext", Prefs.bool(context, SAVED_AUTO_EXTRACT_NEXT, true))
        Prefs.put(context, "botUrl", previousBot)
        return previousBot
    }
}
