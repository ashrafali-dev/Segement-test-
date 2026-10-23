package com.ashraf.novelstudio

import android.content.Context

/**
 * "Translate from X to Y" setting. The translation prompts are generated from it, so a user who picks
 * Hindi gets a prompt that asks for Hindi. Bengali with auto-detected source keeps the original
 * hand-written Bengali prompts untouched. A prompt the user edited by hand is never overwritten.
 */
object Langs {
    const val AUTO = "Auto-detect"
    const val DEFAULT_TARGET = "Bengali"

    /** Every language the chatbots can handle. Bengali stays first in the target list because it is the default. */
    val ALL = listOf(
        "Afrikaans",
        "Albanian",
        "Amharic",
        "Arabic",
        "Armenian",
        "Assamese",
        "Azerbaijani",
        "Basque",
        "Belarusian",
        "Bengali",
        "Bosnian",
        "Bulgarian",
        "Burmese",
        "Catalan",
        "Cebuano",
        "Chinese",
        "Chinese (Traditional)",
        "Corsican",
        "Croatian",
        "Czech",
        "Danish",
        "Dutch",
        "English",
        "Esperanto",
        "Estonian",
        "Filipino",
        "Finnish",
        "French",
        "Galician",
        "Georgian",
        "German",
        "Greek",
        "Gujarati",
        "Haitian Creole",
        "Hausa",
        "Hawaiian",
        "Hebrew",
        "Hindi",
        "Hmong",
        "Hungarian",
        "Icelandic",
        "Igbo",
        "Indonesian",
        "Irish",
        "Italian",
        "Japanese",
        "Javanese",
        "Kannada",
        "Kazakh",
        "Khmer",
        "Kinyarwanda",
        "Korean",
        "Kurdish",
        "Kyrgyz",
        "Lao",
        "Latin",
        "Latvian",
        "Lithuanian",
        "Luxembourgish",
        "Macedonian",
        "Malagasy",
        "Malay",
        "Malayalam",
        "Maltese",
        "Maori",
        "Marathi",
        "Mongolian",
        "Nepali",
        "Norwegian",
        "Odia",
        "Pashto",
        "Persian",
        "Polish",
        "Portuguese",
        "Punjabi",
        "Romanian",
        "Russian",
        "Samoan",
        "Scots Gaelic",
        "Serbian",
        "Sesotho",
        "Shona",
        "Sindhi",
        "Sinhala",
        "Slovak",
        "Slovenian",
        "Somali",
        "Spanish",
        "Sundanese",
        "Swahili",
        "Swedish",
        "Tajik",
        "Tamil",
        "Tatar",
        "Telugu",
        "Thai",
        "Tigrinya",
        "Turkish",
        "Turkmen",
        "Ukrainian",
        "Urdu",
        "Uyghur",
        "Uzbek",
        "Vietnamese",
        "Welsh",
        "Xhosa",
        "Yiddish",
        "Yoruba",
        "Zulu"
    )
    val SOURCES = listOf(AUTO) + ALL
    val TARGETS = listOf(DEFAULT_TARGET) + ALL.filter { it != DEFAULT_TARGET }

    fun source(c: Context): String = Prefs.get(c, "srcLang", AUTO).let { if (it in SOURCES) it else AUTO }
    fun target(c: Context): String = Prefs.get(c, "tgtLang", DEFAULT_TARGET).let { if (it in TARGETS) it else DEFAULT_TARGET }
    fun setupDone(c: Context): Boolean = Prefs.bool(c, "langSetupDone")

    fun save(c: Context, src: String, tgt: String) {
        Prefs.put(c, "srcLang", src)
        Prefs.put(c, "tgtLang", tgt)
        Prefs.putBool(c, "langSetupDone", true)
        applyRuntime(c)
    }

    /** Pushes the choice into code that has no Context (the sound-effect safety net is Bengali-only). */
    fun applyRuntime(c: Context) {
        WmSfx.bengali = target(c) == DEFAULT_TARGET
    }

    /** True when a hand-edited translation prompt is saved (the language choice will not replace it). */
    fun hasCustomPrompt(c: Context): Boolean {
        val p = Prefs.get(c, "prompt", "")
        val r = Prefs.get(c, "realtimePrompt", "")
        val sc = Prefs.get(c, "scanPrompt", "")
        return (p.isNotBlank() && !Prefs.isDefaultNovelPrompt(p)) || (r.isNotBlank() && !Prefs.isDefaultRealtimePrompt(c, r)) || (sc.isNotBlank() && !Prefs.isDefaultScanPrompt(c, sc))
    }

    private fun classic(c: Context) = source(c) == AUTO && target(c) == DEFAULT_TARGET

    private fun fromPart(c: Context): String {
        val s = source(c)
        return if (s == AUTO) " (detect the source language automatically)" else " written in $s"
    }

    fun novelPrompt(c: Context): String {
        val src = source(c)
        val tgt = target(c)
        val sourceDescription = if (src == AUTO) {
            "automatically detect the source language from the text"
        } else {
            src
        }
        return "Translation target: $tgt. Source language: $sourceDescription.\n\n" + Prefs.DEFAULT_PROMPT
    }

    /**
     * A separate chapter-wide scanning prompt. Unlike the bubble prompt, it uses the full chapter
     * as context and explicitly forbids filling OCR gaps with invented story content.
     */
    fun scanPrompt(c: Context): String {
        val src = source(c)
        val tgt = target(c)
        val sourceDescription = if (src == AUTO) {
            "automatically detect the source language from the text"
        } else {
            src
        }
        return "Translation target: $tgt. Source language: $sourceDescription.\n\n" + Prefs.DEFAULT_SCAN_PROMPT
    }

    fun realtimePrompt(c: Context): String {
        val src = source(c)
        val tgt = target(c)
        val sourceDescription = if (src == AUTO) {
            "automatically detect the source language from the text"
        } else {
            src
        }
        return "Translation target: $tgt. Source language: $sourceDescription.\n\n" + Prefs.DEFAULT_REALTIME_PROMPT
    }

    /**
     * The three lines of the comic-page rules that name the target language. For Bengali this is exactly
     * the original wording (including the Bengali sound-effect examples).
     */
    fun sfxRule(c: Context): String {
        val tgt = target(c)
        if (tgt == DEFAULT_TARGET) return """sound effect. Translate EVERY marker into Bengali script, including sound effects and exclamations
(BOOM -> ধুম!, HAH -> হাঃ, GRR -> গরররর!). If a word has no Bengali equivalent, write its sound in
Bengali letters. Never leave a marker in English letters, never skip a marker, and never put two"""
        return """sound effect. Translate EVERY marker into $tgt, including sound effects and exclamations:
write each sound the way $tgt readers expect to see it. If a word has no $tgt equivalent, write its
sound in $tgt letters. Never leave a marker in its original language, never skip a marker, and never put two"""
    }
}
