package com.ashraf.novelstudio

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM tests of the hand-written Lens protobuf codec. Responses are assembled byte by byte here
 * (independently of the production reader) using the field numbers of the real Lens messages.
 * No network is touched.
 */
class LensProtocolTest {

    // ---- tiny independent encoder for building fixtures ----
    private fun varint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        o.write(x.toInt())
    }
    private fun len(field: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        varint(o, ((field shl 3) or 2).toLong()); varint(o, body.size.toLong()); o.write(body)
        return o.toByteArray()
    }
    private fun str(field: Int, s: String) = len(field, s.toByteArray(Charsets.UTF_8))
    private fun ev(field: Int, v: Int): ByteArray {
        val o = ByteArrayOutputStream(); varint(o, (field shl 3).toLong()); varint(o, v.toLong()); return o.toByteArray()
    }
    private fun f32(field: Int, v: Float): ByteArray {
        val o = ByteArrayOutputStream(); varint(o, ((field shl 3) or 5).toLong())
        val b = java.lang.Float.floatToIntBits(v)
        for (i in 0..3) o.write((b ushr (8 * i)) and 0xFF)
        return o.toByteArray()
    }
    private fun cat(vararg p: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); p.forEach { o.write(it) }; return o.toByteArray()
    }
    private fun box(cx: Float, cy: Float, w: Float, h: Float, rot: Float = 0f, coord: Int = 1): ByteArray =
        len(1, cat(f32(1, cx), f32(2, cy), f32(3, w), f32(4, h), f32(5, rot), ev(6, coord)))   // Geometry{bounding_box}
    private fun word(text: String, sep: String) = len(1, cat(str(2, text), str(3, sep)))           // Line.words
    private fun line(words: ByteArray, geo: ByteArray) = len(2, cat(words, len(2, geo)))          // Paragraph.lines
    private fun response(vararg paragraphs: ByteArray): ByteArray =
        len(2, len(3, len(1, cat(*paragraphs.map { len(1, it) }.toTypedArray()))))               // objects.text.layout.paragraphs

    @Test fun separateParagraphsBecomePixelBlocksOfTheOriginalBitmap() {
        val p1 = line(cat(word("Hello", " "), word("world", "")), box(0.5f, 0.25f, 0.4f, 0.05f))
        val p2 = line(cat(word("second", " "), word("line", "")), box(0.5f, 0.45f, 0.3f, 0.05f))
        val resp = LensProtocol.parseResponse(response(p1, p2))
        assertTrue(resp.valid)
        // original 1000x2000, uploaded at 800x1600: normalized boxes map by the ORIGINAL size
        val blocks = LensProtocol.toBlocks(resp, 1000, 2000, 800, 1600)
        assertEquals(listOf("Hello world", "second line"), blocks.map { it.text })
        assertEquals(500f, blocks[0].cx, 0.01f)
        assertEquals(500f, blocks[0].cy, 0.01f)
        assertEquals(400f, blocks[0].w, 0.01f)
        assertEquals(100f, blocks[0].h, 0.01f)
        assertFalse(blocks[0].vertical)
        assertTrue(blocks[0].top < blocks[1].top)          // reading order
    }

    @Test fun linesOfOneLensParagraphBecomeOneBlock() {
        val p = cat(
            line(word("WHY IS THIS ROPE", ""), box(0.5f, 0.20f, 0.40f, 0.04f)),
            line(word("SO HARD TO", ""), box(0.5f, 0.245f, 0.30f, 0.04f)),
            line(word("UNTIE?", ""), box(0.5f, 0.29f, 0.20f, 0.04f)))
        val blocks = LensProtocol.toBlocks(LensProtocol.parseResponse(response(p)), 1000, 1000, 1000, 1000)
        assertEquals(listOf("WHY IS THIS ROPE SO HARD TO UNTIE?"), blocks.map { it.text })
        assertEquals(0.20f * 1000 - 20f, blocks[0].cy - blocks[0].h / 2f, 0.5f)   // union box spans all 3 lines
        assertEquals(0.29f * 1000 + 20f, blocks[0].cy + blocks[0].h / 2f, 0.5f)
    }

    @Test fun narrowMiddleLineIsNeverLeftBehind() {
        // Regression: a 1-glyph middle line ("I") is a tall thin box. It used to be flagged "vertical", could not be
        // joined, and the top+bottom lines were fused around it so the middle line was skipped.
        val p = cat(
            line(word("TOP LINE", ""), box(0.5f, 0.20f, 0.30f, 0.04f)),
            line(word("I", ""), box(0.5f, 0.245f, 0.008f, 0.04f)),
            line(word("BOTTOM LINE", ""), box(0.5f, 0.29f, 0.35f, 0.04f)))
        val blocks = LensProtocol.toBlocks(LensProtocol.parseResponse(response(p)), 1000, 1000, 1000, 1000)
        assertEquals(1, blocks.size)
        assertEquals("TOP LINE I BOTTOM LINE", blocks[0].text)
        assertFalse(blocks[0].vertical)
    }

    @Test fun farApartLinesOfOneParagraphAreSplit() {
        val p = cat(
            line(word("first", ""), box(0.5f, 0.10f, 0.30f, 0.04f)),
            line(word("second", ""), box(0.5f, 0.50f, 0.30f, 0.04f)))
        val blocks = LensProtocol.toBlocks(LensProtocol.parseResponse(response(p)), 1000, 1000, 1000, 1000)
        assertEquals(listOf("first", "second"), blocks.map { it.text })
    }

    @Test fun watermarkLineIsDroppedButTheBubbleSurvives() {
        val p = cat(
            line(word("real dialogue", ""), box(0.5f, 0.20f, 0.30f, 0.04f)),
            line(word("asurascans.com", ""), box(0.5f, 0.245f, 0.30f, 0.04f)))
        val blocks = LensProtocol.toBlocks(LensProtocol.parseResponse(response(p)), 1000, 1000, 1000, 1000)
        assertEquals(listOf("real dialogue"), blocks.map { it.text })
    }

    @Test fun paragraphGeometryOnlyIsSharedInBandsNotStacked() {
        // two lines without any geometry, paragraph box present: lines must get separate bands
        val lines = cat(len(2, len(1, cat(str(2, "one"), str(3, "")))), len(2, len(1, cat(str(2, "two"), str(3, "")))))
        val para = cat(lines, len(3, box(0.5f, 0.5f, 0.4f, 0.1f)))
        val blocks = LensProtocol.toBlocks(LensProtocol.parseResponse(response(para)), 1000, 1000, 1000, 1000)
        assertEquals(listOf("one two"), blocks.map { it.text })      // consecutive bands touch -> one block
        assertEquals(100f, blocks[0].h, 0.5f)
    }

    @Test fun verticalParagraphKeepsColumnsTogetherAndIsFlaggedVertical() {
        val cols = cat(
            line(word("こんにちは", ""), box(0.8f, 0.5f, 0.04f, 0.2f)),
            line(word("世界", ""), box(0.75f, 0.5f, 0.04f, 0.1f))
        )
        val p = cat(cols, len(3, box(0.775f, 0.5f, 0.09f, 0.2f)), ev(4, 2))   // writing_direction = TOP_TO_BOTTOM
        val blocks = LensProtocol.toBlocks(LensProtocol.parseResponse(response(p)), 1000, 1000, 1000, 1000)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].vertical)
        assertEquals("こんにちは 世界", blocks[0].text)
    }

    @Test fun imagePixelCoordinatesAreScaledBackFromTheUploadedSize() {
        val p = line(word("PX", ""), box(300f, 200f, 100f, 40f, 0f, coord = 2))
        val blocks = LensProtocol.toBlocks(LensProtocol.parseResponse(response(p)), 1000, 2000, 500, 1000)
        assertEquals(600f, blocks[0].cx, 0.01f)
        assertEquals(400f, blocks[0].cy, 0.01f)
    }

    @Test fun wordGeometryRecoversWhenLineAndParagraphGeometryAreMissing() {
        // Current Lens responses may put Geometry on each TextLayout.Word (field 4)
        // without setting either Line.geometry or Paragraph.geometry.
        val words = cat(
            len(1, cat(str(2, "Hello"), str(3, " "), len(4, box(0.4f, 0.5f, 0.1f, 0.05f)))),
            len(1, cat(str(2, "world"), str(3, ""), len(4, box(0.5f, 0.5f, 0.1f, 0.05f))))
        )
        val paragraphWithoutGeometry = len(2, words) // Paragraph.lines only
        val parsed = LensProtocol.parseResponse(response(paragraphWithoutGeometry))
        val blocks = LensProtocol.toBlocks(parsed, 1000, 1000, 1000, 1000)

        assertEquals(listOf("Hello world"), blocks.map { it.text })
        assertEquals(450f, blocks[0].cx, 0.01f)
        assertEquals(200f, blocks[0].w, 0.01f)
        assertEquals(500f, blocks[0].cy, 0.01f)
    }

    @Test fun validReplyWithoutTextIsValidButEmpty() {
        val resp = LensProtocol.parseResponse(len(2, str(7, "cluster")))     // objects_response with only unrelated data
        assertTrue(resp.valid)
        assertTrue(resp.paragraphs.isEmpty())
    }

    @Test fun serverErrorIsReportedAndReplyIsNotValid() {
        val resp = LensProtocol.parseResponse(len(1, ev(1, 3)))
        assertEquals(3, resp.serverError)
        assertFalse(resp.valid)
    }

    @Test fun garbageAndTruncatedBodiesAreRejectedNotGuessed() {
        for (bad in listOf(ByteArray(0), ByteArray(12) { 0xFF.toByte() })) {
            try { LensProtocol.parseResponse(bad); fail("accepted a bad body") } catch (_: LensProtocol.ProtocolException) {}
        }
        val good = response(line(word("abc", ""), box(0.5f, 0.5f, 0.1f, 0.1f)))
        try { LensProtocol.parseResponse(good.copyOf(good.size - 3)); fail("accepted a truncated body") }
        catch (_: LensProtocol.ProtocolException) {}
    }

    @Test fun requestNeverAsksForTranslation() {
        val bytes = LensProtocol.buildObjectsRequest(
            ByteArray(10) { 1 }, 100, 100, "ja", "US", "UTC", 1L, 2L)
        // AppliedFilter.translate would be field 3 (tag 0x1A) inside client_filters (field 17 = tag 0x8A 0x01).
        // The only filter we send is {filter_type = 7}: 0x0A 0x02 0x08 0x07 inside client_filters.
        val s = bytes.joinToString("") { "%02x".format(it) }
        assertTrue("AUTO_FILTER present", s.contains("8a01040a020807"))
        assertFalse("no TRANSLATE filter type (2)", s.contains("8a01040a020802"))
    }
}
