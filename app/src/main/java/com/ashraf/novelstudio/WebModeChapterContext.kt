package com.ashraf.novelstudio

/**
 * Stable chapter-level view of image OCR. Marker numbers remain the placement contract used by
 * WebModeLayout; panel/bubble IDs and semantic roles are context for the translator only.
 */
internal enum class WebModeTextRole { DIALOGUE, SFX, NARRATION }

internal data class WebModeChapterEntry(
    val markerNumber: Int,
    val panelIndex: Int,
    val bubbleIndex: Int,
    val id: String,
    val role: WebModeTextRole,
    val speaker: String?,
    val text: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val source: String
)

internal data class WebModeChapterWindow(
    val index: Int,
    val firstPanel: Int,
    val lastPanelInclusive: Int,
    val contextEntries: List<WebModeChapterEntry>,
    val outputEntries: List<WebModeChapterEntry>
)

internal data class WebModeChapterResponse(
    val translation: String,
    val summary: String
)

internal data class WebModeChapterContext(
    val chapterId: String,
    val mangaTitle: String,
    val chapterNumber: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val entries: List<WebModeChapterEntry>
) {
    val panelCount: Int get() = entries.map { it.panelIndex }.distinct().size
    val dialogueCount: Int get() = entries.count { it.role == WebModeTextRole.DIALOGUE }
    val sfxCount: Int get() = entries.count { it.role == WebModeTextRole.SFX }
    val narrationCount: Int get() = entries.count { it.role == WebModeTextRole.NARRATION }

    /**
     * Usually one whole-chapter request. Longer chapters are chunked by no more than 20 panels and
     * a source-character budget; each later window has two prior panels for story context.
     */
    fun windows(maxPanels: Int = 20, overlapPanels: Int = 2, maxSourceChars: Int = 24_000): List<WebModeChapterWindow> {
        if (entries.isEmpty()) return emptyList()
        val panelIndices = entries.map { it.panelIndex }.distinct().sorted()
        if (panelIndices.isEmpty()) return emptyList()
        val maxP = maxPanels.coerceAtLeast(1)
        val overlap = overlapPanels.coerceIn(0, maxP - 1)
        val charBudget = maxSourceChars.coerceAtLeast(2_000)
        val panelCharCounts = entries.groupBy { it.panelIndex }.mapValues { (_, items) ->
            items.sumOf { it.text.length + 80 }
        }
        val spans = ArrayList<Pair<Int, Int>>()
        var startAt = 0
        while (startAt < panelIndices.size) {
            var endAt = startAt
            var chars = 0
            while (endAt < panelIndices.size && endAt - startAt < maxP) {
                val count = panelCharCounts[panelIndices[endAt]] ?: 0
                if (endAt > startAt && chars + count > charBudget) break
                chars += count
                endAt++
            }
            if (endAt == startAt) endAt++
            spans.add(panelIndices[startAt] to panelIndices[endAt - 1])
            if (endAt >= panelIndices.size) break
            val next = (endAt - overlap).coerceAtLeast(startAt + 1)
            startAt = next
        }
        return spans.mapIndexed { index, span ->
            val inRange = entries.filter { it.panelIndex in span.first..span.second }
            val outputStart = if (index == 0) span.first else spans[index - 1].second + 1
            val out = inRange.filter { it.panelIndex >= outputStart }
            WebModeChapterWindow(index, span.first, span.second, inRange, out)
        }
    }

    fun promptForWindow(
        window: WebModeChapterWindow,
        previousTranslations: Map<Int, String> = emptyMap(),
        previousSummaries: List<String> = emptyList()
    ): String {
        val outputMarkers = window.outputEntries.map { it.markerNumber }.toSet()
        val body = window.contextEntries.joinToString("\n") { entry ->
            val marker = "[${entry.markerNumber.toString().padStart(3, '0')}]"
            val record = "panel=${entry.panelIndex} bubble=${entry.id} role=${entry.role.name.lowercase()} " +
                "bbox=${fmt(entry.cx)},${fmt(entry.cy)},${fmt(entry.w)},${fmt(entry.h)} " +
                "text=\"${entry.text.replace(Regex("\\s+"), " ").replace("\"", "'").trim()}\""
            if (entry.markerNumber in outputMarkers) {
                "$marker $record"
            } else {
                val prior = previousTranslations[entry.markerNumber].orEmpty()
                    .replace(Regex("\\s+"), " ").replace("\"", "'").trim()
                "CONTEXT_ONLY $record prior_translation=\"$prior\""
            }
        }
        val summaryContext = previousSummaries.filter { it.isNotBlank() }.takeLast(4)
            .joinToString("\n") { "- ${it.replace(Regex("\\s+"), " ").trim()}" }
        val contextOnly = window.contextEntries.size - window.outputEntries.size
        return buildString {
            appendLine("[CHAPTER-LEVEL COMIC TRANSLATION]")
            appendLine("Manga: ${mangaTitle.ifBlank { "Unknown manga" }}")
            appendLine("Chapter: ${chapterNumber.ifBlank { "unknown" }}")
            appendLine("Source language: $sourceLanguage")
            appendLine("Target language: $targetLanguage")
            appendLine("Translate this window as part of one continuous chapter. Read all entries together before translating.")
            appendLine("Preserve character names, pronouns, relationships, tense, tone, and continuity across bubbles.")
            appendLine("DIALOGUE is spoken text; NARRATION is narration/caption; SFX is a sound effect or stylized on-art lettering.")
            appendLine("For SFX, return a short expressive transliteration/translation, retaining emphasis (example: BOOM -> ধুম!).")
            appendLine("Translate only text content. Do not translate IDs, panel numbers, bubble IDs, role labels, or bbox metadata.")
            if (contextOnly > 0) {
                appendLine("CONTEXT_ONLY entries and their prior translations are for continuity only. Do NOT return their markers again.")
            }
            if (summaryContext.isNotBlank()) {
                appendLine("Earlier-window summaries for chapter continuity:")
                appendLine(summaryContext)
                appendLine("The final CHAPTER_SUMMARY must summarize the whole chapter, not just this window.")
            }
            appendLine()
            appendLine("Input entries:")
            appendLine(body)
            appendLine()
            appendLine("Return exactly one line for every output marker listed above, using this form: [001] translated text.")
            appendLine("Return output markers in their original order, once each. Do not omit, merge, reorder, or split an output marker.")
            appendLine("Do not return CONTEXT_ONLY records. A non-dialogue/UI/noise entry may have an empty translation after its marker.")
            appendLine("Keep each translated bubble concise enough to fit its original position without dropping meaning.")
            appendLine("At the end, return a brief 1–3 sentence chapter summary with the main characters introduced and the key plot beat.")
            appendLine("Wrap that final summary exactly in [CHAPTER_SUMMARY] and [/CHAPTER_SUMMARY].")
        }
    }

    /** Build the marker-bearing text used for placement. Summary is displayed separately, never placed on a bubble. */
    fun assembleMarkedTranslation(translations: Map<Int, String>): String =
        entries.sortedBy { it.markerNumber }.joinToString("\n\n") { entry ->
            "[${entry.markerNumber.toString().padStart(3, '0')}] ${translations[entry.markerNumber].orEmpty().trim()}"
        }

    companion object {
        private val MARKED_BLOCK = Regex("(?ms)^\\[(\\d{3,})\\]\\s*(.*?)(?=^\\[\\d{3,}\\]\\s*|\\z)")
        private val MARKED_REPLY = Regex("(?s)\\[(\\d{3,})\\]\\s*(.*?)(?=(?:\\[\\d{3,}\\])|\\z)")
        private val SUMMARY_BLOCK = Regex("(?is)\\[CHAPTER_SUMMARY\\]\\s*(.*?)\\s*\\[/CHAPTER_SUMMARY\\]")

        fun fromMarkedText(
            chapterId: String,
            mangaTitle: String,
            chapterNumber: String,
            targetLanguage: String,
            markedText: String,
            layout: WebModeLayout?
        ): WebModeChapterContext? {
            val parsed = MARKED_BLOCK.findAll(markedText).mapNotNull { match ->
                val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                number to match.groupValues[2].trim()
            }.toList()
            if (parsed.isEmpty()) return null
            val bubbles = layout?.bubbles.orEmpty()
            val entries = ArrayList<WebModeChapterEntry>(parsed.size)
            var panelIndex = 0
            var bubbleIndex = 0
            var previousSource = ""
            var previousY = -1f
            for ((position, item) in parsed.withIndex()) {
                val bubble = bubbles.getOrNull(position)
                val normalizedSource = bubble?.src.orEmpty()
                if (position > 0 && bubble != null) {
                    val switchedSource = normalizedSource != previousSource
                    val restartedVerticalPosition = normalizedSource == previousSource &&
                        previousY > 0.68f && bubble.cy < 0.32f
                    if (switchedSource || restartedVerticalPosition) {
                        panelIndex++
                        bubbleIndex = 0
                    }
                }
                val text = item.second
                val role = classifyRole(text, bubble)
                entries.add(
                    WebModeChapterEntry(
                        markerNumber = item.first,
                        panelIndex = panelIndex,
                        bubbleIndex = bubbleIndex,
                        id = "p${panelIndex}b${bubbleIndex + 1}",
                        role = role,
                        speaker = null,
                        text = text,
                        cx = bubble?.cx ?: 0f,
                        cy = bubble?.cy ?: 0f,
                        w = bubble?.w ?: 0f,
                        h = bubble?.h ?: 0f,
                        source = normalizedSource
                    )
                )
                bubbleIndex++
                if (bubble != null) {
                    previousSource = normalizedSource
                    previousY = bubble.cy
                }
            }
            return WebModeChapterContext(
                chapterId = chapterId,
                mangaTitle = mangaTitle,
                chapterNumber = chapterNumber,
                sourceLanguage = inferSourceLanguage(parsed.joinToString(" ") { it.second }, chapterId),
                targetLanguage = targetLanguage,
                entries = entries
            )
        }

        fun classifyRole(text: String, bubble: WmBubble?): WebModeTextRole {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) return WebModeTextRole.DIALOGUE
            val letters = cleaned.filter { it.isLetter() }
            val latinOnly = letters.isNotEmpty() && letters.all { it.code < 0x0250 }
            val latinCaps = latinOnly && letters.any { it.isLetter() } &&
                letters.filter { it.isLetter() }.all { it.isUpperCase() }
            val compact = cleaned.count { it.isLetterOrDigit() }
            val repeated = Regex("(?i)(.{1,2})\\1{2,}").containsMatchIn(cleaned) ||
                Regex("[!?！？…]{2,}").containsMatchIn(cleaned)
            val onArtwork = bubble?.free == true || bubble?.soft == true
            val outlined = (bubble?.stroke ?: 0) != 0
            val likelySfx = outlined ||
                (onArtwork && compact <= 4) ||
                (onArtwork && latinCaps && compact <= 20) ||
                (onArtwork && repeated && compact <= 16)
            return when {
                likelySfx -> WebModeTextRole.SFX
                onArtwork -> WebModeTextRole.NARRATION
                else -> WebModeTextRole.DIALOGUE
            }
        }

        fun parseResponse(response: String): WebModeChapterResponse {
            val summaryMatch = SUMMARY_BLOCK.find(response)
            if (summaryMatch != null) {
                return WebModeChapterResponse(
                    translation = SUMMARY_BLOCK.replace(response, "").trim(),
                    summary = summaryMatch.groupValues[1].trim()
                )
            }
            return WebModeChapterResponse(
                translation = response.trim(),
                summary = ""
            )
        }

        fun parseMarkedTranslations(response: String): Map<Int, String> {
            val out = LinkedHashMap<Int, String>()
            for (match in MARKED_REPLY.findAll(response)) {
                val number = match.groupValues[1].toIntOrNull() ?: continue
                if (number !in out) out[number] = match.groupValues[2].trim()
            }
            return out
        }

        fun sourceRoleCounts(bubbles: List<WmBubble>): Triple<Int, Int, Int> {
            var dialogue = 0
            var sfx = 0
            var narration = 0
            for (bubble in bubbles) {
                when (classifyRole(bubble.sourceText, bubble)) {
                    WebModeTextRole.DIALOGUE -> dialogue++
                    WebModeTextRole.SFX -> sfx++
                    WebModeTextRole.NARRATION -> narration++
                }
            }
            return Triple(dialogue, sfx, narration)
        }

        private fun inferSourceLanguage(text: String, url: String): String {
            if (text.any { it.code in 0xAC00..0xD7AF }) return "Korean"
            if (text.any { it.code in 0x3040..0x30FF }) return "Japanese"
            if (text.any { it.code in 0x3400..0x9FFF }) {
                return when (WebModeLogic.pickScript("", url)) {
                    OcrScript.KOREAN -> "Korean"
                    OcrScript.JAPANESE -> "Japanese"
                    OcrScript.CHINESE -> "Chinese"
                    else -> "Chinese/Japanese (CJK)"
                }
            }
            return "undetermined"
        }

        private fun fmt(value: Float): String =
            if (!value.isFinite()) "0.000" else String.format(java.util.Locale.US, "%.3f", value.coerceIn(0f, 1f))
    }
}
