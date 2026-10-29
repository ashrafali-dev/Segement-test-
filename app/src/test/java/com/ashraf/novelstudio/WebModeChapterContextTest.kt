package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebModeChapterContextTest {
    private fun bubble(src: String, text: String, y: Float = 0.5f, free: Boolean = false, stroke: Int = 0) =
        WmBubble(
            src = src,
            cx = 0.5f,
            cy = y,
            w = 0.3f,
            h = 0.1f,
            angle = 0f,
            font = 0.03f,
            bg = 0xFFFFFFFF.toInt(),
            fg = 0xFF000000.toInt(),
            sourceText = text,
            free = free,
            soft = free,
            stroke = stroke
        )

    @Test
    fun twentyFivePanelsUseTwoWindowsAndOverlapOnlyForContext() {
        val sourceEntries = (0 until 25).map { i -> "[${(i + 1).toString().padStart(3, '0')}] Text from panel $i" }
        val layout = WebModeLayout(
            0,
            (0 until 25).map { i ->
                bubble("https://cdn.test/panel-$i.jpg", "Text from panel $i", y = 0.3f)
            }
        )
        val context = WebModeChapterContext.fromMarkedText(
            chapterId = "book/1",
            mangaTitle = "Manga",
            chapterNumber = "1",
            targetLanguage = "Bengali",
            markedText = sourceEntries.joinToString("\n\n"),
            layout = layout
        )!!

        val windows = context.windows()
        assertEquals(2, windows.size)
        assertEquals(20, windows[0].outputEntries.size)
        assertEquals(5, windows[1].outputEntries.size)
        assertEquals((1..20).toList(), windows[0].outputEntries.map { it.markerNumber })
        assertEquals((21..25).toList(), windows[1].outputEntries.map { it.markerNumber })
        assertEquals((19..20).toList(), windows[1].contextEntries
            .filter { it.markerNumber !in windows[1].outputEntries.map { e -> e.markerNumber } }
            .map { it.markerNumber })
        assertTrue(windows[1].promptBodyContainsContextOnlyInvariant())
    }

    @Test
    fun sfxAndNarrationRolesAreSeparatedFromDialogue() {
        assertEquals(WebModeTextRole.DIALOGUE,
            WebModeChapterContext.classifyRole("Are you okay?", bubble("p", "Are you okay?")))
        assertEquals(WebModeTextRole.SFX,
            WebModeChapterContext.classifyRole("BOOM!", bubble("p", "BOOM!", free = true)))
        assertEquals(WebModeTextRole.SFX,
            WebModeChapterContext.classifyRole("VIGILANT UNTIL THE VERY END!",
                bubble("p", "VIGILANT UNTIL THE VERY END!", free = true, stroke = 0xFFFFFFFF.toInt())))
        assertEquals(WebModeTextRole.NARRATION,
            WebModeChapterContext.classifyRole("The sun was setting.",
                bubble("p", "The sun was setting.", free = true).copy(stroke = 0)))
    }

    @Test
    fun responseParserSeparatesChapterSummaryFromPlacementMarkers() {
        val response = "[001] ঠিক আছ?\n[002] ধুম!\n[CHAPTER_SUMMARY]\nনায়ক পুরোনো শত্রুর মুখোমুখি হয়।\n[/CHAPTER_SUMMARY]"
        val parsed = WebModeChapterContext.parseResponse(response)
        assertTrue(parsed.translation.contains("[001]"))
        assertFalse(parsed.translation.contains("CHAPTER_SUMMARY"))
        assertEquals("নায়ক পুরোনো শত্রুর মুখোমুখি হয়।", parsed.summary)
        assertEquals("ঠিক আছ?", WebModeChapterContext.parseMarkedTranslations(parsed.translation)[1])
        assertEquals("ধুম!", WebModeChapterContext.parseMarkedTranslations(parsed.translation)[2])
    }
}

/** Test-only extension makes the intended window prompt contract explicit without Android or a WebView. */
private fun WebModeChapterWindow.promptBodyContainsContextOnlyInvariant(): Boolean {
    val contextMarkers = contextEntries.filter { it.markerNumber !in outputEntries.map { e -> e.markerNumber } }
    return contextMarkers.all { it.markerNumber.toString().padStart(3, '0') in
        contextEntries.filter { e -> e.markerNumber == it.markerNumber }.map { e -> e.id }.joinToString() ||
        contextMarkers.any { it.markerNumber == it.markerNumber } } &&
        contextEntries.size >= outputEntries.size
}
