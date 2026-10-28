package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marker numbering must always equal bubble index. These are the hostile inputs that used to shift it.
 */
class MarkerCodecTest {

    private fun bubble(text: String) = WmBubble(
        src = "https://example.com/1.jpg",
        cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.1f,
        angle = 0f, font = 0.02f,
        bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), fg2 = 0xFF000000.toInt(),
        soft = false, stroke = 0, sourceText = text
    )

    @Test fun `one marker per bubble even when a bubble contains blank lines`() {
        val bubbles = listOf(bubble("first\n\nsecond"), bubble("only"))
        val marked = MarkerCodec.build(bubbles)
        assertEquals(2, MarkerCodec.numbers(marked).size)
        assertEquals(listOf(1, 2), MarkerCodec.numbers(marked))
        // The blank line must not survive as a separate block.
        assertTrue(marked.contains("[001] first second"))
    }

    @Test fun `empty bubble text still gets a marker so later numbers do not shift`() {
        val bubbles = listOf(bubble("alpha"), bubble("   "), bubble("gamma"))
        val marked = MarkerCodec.build(bubbles)
        assertEquals(listOf(1, 2, 3), MarkerCodec.numbers(marked))
        assertEquals(3, MarkerCodec.expectedMarkers(bubbles))
    }

    @Test fun `a 900-char bubble is never chunked into extra markers`() {
        val long = "word ".repeat(180).trim()
        val bubbles = listOf(bubble("short"), bubble(long), bubble("third"))
        val marked = MarkerCodec.build(bubbles)
        assertEquals(3, MarkerCodec.numbers(marked).size)
    }

    @Test fun `two bubbles with eight lines each stay two markers`() {
        val a = (1..8).joinToString("\n") { "line$it" }
        val b = (1..8).joinToString("\n") { "other$it" }
        val marked = MarkerCodec.build(listOf(bubble(a), bubble(b)))
        assertEquals(listOf(1, 2), MarkerCodec.numbers(marked))
    }

    @Test fun `title offset adds exactly one leading marker`() {
        val bubbles = listOf(bubble("a"), bubble("b"))
        val marked = MarkerCodec.build(bubbles, titleOffset = 1, title = "Chapter 7")
        assertEquals(listOf(1, 2, 3), MarkerCodec.numbers(marked))
        assertEquals(3, MarkerCodec.expectedMarkers(bubbles, 1, "Chapter 7"))
    }

    @Test fun `one bad marker is skipped on its own, the rest are placed`() {
        val reply = """
            [001] one
            [999] out of range
            [002] two
        """.trimIndent()
        val skips = ArrayList<String>()
        val placed = MarkerCodec.align(reply, bubbleCount = 3) { no, reason -> skips.add("$no:$reason") }
        assertEquals("one", placed[0])
        assertEquals("two", placed[1])
        assertNull(placed[2])
        assertTrue(skips.any { it.startsWith("999:") })
    }

    @Test fun `duplicate marker keeps the first answer`() {
        val placed = MarkerCodec.align("[001] first\n[001] second\n[002] two", 2)
        assertEquals("first", placed[0])
        assertEquals("two", placed[1])
    }

    @Test fun `out of range marker is not reassigned to a different bubble`() {
        val reply = "[001] one\n[999] wrong bubble\n[003] three"
        val firstPass = MarkerCodec.align(reply, 3)
        val repaired = MarkerCodec.recoverFailedMarkersBySpatialOrder(reply, 3, 0, firstPass, listOf(0, 1, 2))
        assertEquals(listOf("one", null, "three"), repaired)
    }

    @Test fun `duplicate marker body is not moved to the next bubble`() {
        val reply = "[001] first\n[001] ambiguous second\n[003] third"
        val firstPass = MarkerCodec.align(reply, 3)
        val repaired = MarkerCodec.recoverFailedMarkersBySpatialOrder(reply, 3, 0, firstPass, listOf(0, 1, 2))
        assertEquals(listOf("first", null, "third"), repaired)
    }

    @Test fun `empty body is never recovered as a translation`() {
        val reply = "[001] ~\n[999] ~\n[003] third"
        val firstPass = MarkerCodec.align(reply, 3)
        val repaired = MarkerCodec.recoverFailedMarkersBySpatialOrder(reply, 3, 0, firstPass, listOf(0, 1, 2))
        assertNull(repaired[0])
        assertNull(repaired[1])
        assertEquals("third", repaired[2])
    }

    @Test fun `markerless paragraph count mismatch is not spatially guessed`() {
        val repaired = MarkerCodec.recoverFailedMarkersBySpatialOrder(
            "upper whisper\n\nmain bubble", 3, 0, listOf(null, null, null), listOf(2, 0, 1)
        )
        assertEquals(listOf(null, null, null), repaired)
    }

    @Test fun `markerless paragraphs align spatially only with exact bubble count`() {
        val repaired = MarkerCodec.recoverFailedMarkersBySpatialOrder(
            "upper whisper\n\nmain bubble\n\nlower bubble", 3, 0,
            listOf(null, null, null), listOf(2, 0, 1)
        )
        assertEquals("main bubble", repaired[0])
        assertEquals("lower bubble", repaired[1])
        assertEquals("upper whisper", repaired[2])
    }

    @Test fun `trailing extra marker is ignored once all spatial slots are filled`() {
        val reply = "[001] one\n[002] two\n[003] three\n[999] extra"
        val firstPass = MarkerCodec.align(reply, 3)
        val repaired = MarkerCodec.recoverFailedMarkersBySpatialOrder(reply, 3, 0, firstPass, listOf(0, 1, 2))
        assertEquals(listOf("one", "two", "three"), repaired)
    }

    @Test fun `garbled marker is skipped without killing the reply`() {
        val placed = MarkerCodec.align("[abc] noise\n[001] one", 1)
        assertEquals("one", placed[0])
    }

    @Test fun `tilde marker counts as empty, not as a translation`() {
        val placed = MarkerCodec.align("[001] ~\n[002] real", 2)
        assertNull(placed[0])
        assertEquals("real", placed[1])
    }

    @Test fun `no percentage threshold - one match out of twenty is still placed`() {
        val reply = (1..20).joinToString("\n") { if (it == 7) "[%03d] seven".format(it) else "[%03d] ~".format(it) }
        val placed = MarkerCodec.align(reply, 20)
        assertEquals("seven", placed[6])
        assertEquals(1, placed.count { !it.isNullOrBlank() })
    }

    @Test fun `markerless reply aligns by paragraph count only when counts match`() {
        val exact = MarkerCodec.alignByParagraphs("one\n\ntwo\n\nthree", 3)
        assertEquals(listOf("one", "two", "three"), exact)

        val wrong = MarkerCodec.alignByParagraphs("one\n\ntwo", 3)
        assertEquals(3, wrong.size)
        assertTrue(wrong.all { it == null })
    }

    @Test fun `P1_L1 style markers are accepted`() {
        val placed = MarkerCodec.align("[P1_L1] hello\n[P1_L2] world", 2)
        assertEquals("hello", placed[0])
        assertEquals("world", placed[1])
    }

    @Test fun `missing reports only the unplaced bubbles`() {
        val placed = listOf("a", null, "c", null)
        assertEquals(listOf(1, 3), MarkerCodec.missing(placed))
    }

    @Test fun `align returns null-friendly list when the reply has no markers`() {
        val placed = MarkerCodec.align("just prose", 2)
        assertNotNull(placed)
        assertEquals(2, placed.size)
        assertTrue(placed.all { it == null })
    }
}
