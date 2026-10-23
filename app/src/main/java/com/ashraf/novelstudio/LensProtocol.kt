package com.ashraf.novelstudio

import java.io.ByteArrayOutputStream

/**
 * Wire format of the Google Lens "overlay" upload that Chromium itself uses
 * (POST https://lensfrontend-pa.googleapis.com/v1/crupload, body = protobuf).
 *
 * IMPORTANT: this is NOT a documented or supported public API. Google can change or block it at any
 * time. Everything here is therefore written defensively: the parser rejects anything that does not
 * look like the expected message instead of guessing, so a changed protocol is reported as a failure
 * (and the app falls back to another OCR engine) rather than shown as an "empty but successful" scan.
 *
 * Only the handful of fields needed for text recognition are encoded/decoded, by hand, so no protobuf
 * library has to be added to the app. Field numbers were read from the message descriptors shipped
 * with the reference projects (chrome-lens-py, owocr, chrome-lens-ocr) and cross-checked against each
 * other; none of their source code is copied. Pure Kotlin, no Android types: unit-testable on the JVM.
 *
 * Request  = ServerRequest{ 1: objects_request{ 1: request_context{ 3: request_id, 4: client_context },
 *                                               3: image_data{ 1: payload{1: bytes}, 3: metadata{1: w, 2: h} } } }
 * Response = ServerResponse{ 1: error{1: type}, 2: objects_response{ 3: text{ 1: text_layout{ 1: paragraphs } } } }
 */
internal object LensProtocol {

    /** Thrown for a body that is not a valid/expected Lens response. */
    class ProtocolException(message: String) : Exception(message)

    // ---- enum values (lens_overlay_*.proto) -----------------------------------------------------
    private const val PLATFORM_WEB = 3
    private const val SURFACE_CHROMIUM = 4
    private const val RENDERING_ENV_LENS_OVERLAY = 14
    private const val FILTER_AUTO = 7
    private const val COORD_IMAGE = 2
    private const val WRITING_TOP_TO_BOTTOM = 2

    // =============================================================================================
    // Encoding
    // =============================================================================================

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

        fun uint(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun int(field: Int, v: Int) { tag(field, 0); varint(v.toLong()) }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun message(field: Int, body: Writer.() -> Unit) {
            val inner = Writer()
            inner.body()
            bytes(field, inner.toByteArray())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /**
     * Full-image OCR request. [language]/[region]/[timeZone] only describe the caller's locale; Lens
     * detects the language of the pixels itself and this request asks for NO translation (no
     * TRANSLATE filter is ever sent - translating is the chatbot's job).
     */
    fun buildObjectsRequest(
        jpeg: ByteArray, width: Int, height: Int,
        language: String, region: String, timeZone: String,
        uuid: Long, timeUsec: Long
    ): ByteArray {
        require(width > 0 && height > 0 && jpeg.isNotEmpty()) { "empty image" }
        val root = Writer()
        root.message(1) {                                   // objects_request
            message(1) {                                    // request_context
                message(3) {                                // request_id
                    uint(1, uuid)                           //   uuid
                    int(2, 1)                               //   sequence_id
                    int(3, 1)                               //   image_sequence_id
                    uint(7, timeUsec)                       //   time_usec
                }
                message(4) {                                // client_context
                    int(1, PLATFORM_WEB)
                    int(2, SURFACE_CHROMIUM)
                    message(4) {                            //   locale_context
                        string(1, language)
                        string(2, region)
                        string(3, timeZone)
                    }
                    message(17) {                           //   client_filters
                        message(1) { int(1, FILTER_AUTO) }  //     one filter: AUTO_FILTER (never TRANSLATE)
                    }
                    message(20) {                           //   rendering_context
                        int(2, RENDERING_ENV_LENS_OVERLAY)
                    }
                }
            }
            message(3) {                                    // image_data
                message(1) { bytes(1, jpeg) }               //   payload.image_bytes
                message(3) { int(1, width); int(2, height) }//   image_metadata
            }
        }
        return root.toByteArray()
    }

    // =============================================================================================
    // Decoding
    // =============================================================================================

    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= end || shift > 63) throw ProtocolException("truncated varint")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Next field header as (fieldNumber shl 3 | wireType). */
        fun tag(): Int {
            val t = varint()
            if (t <= 0 || t > Int.MAX_VALUE) throw ProtocolException("bad tag")
            return t.toInt()
        }

        fun fixed32(): Int {
            if (pos + 4 > end) throw ProtocolException("truncated fixed32")
            val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun float(): Float = java.lang.Float.intBitsToFloat(fixed32())

        /** Length-delimited payload as a sub-reader over the same buffer (no copy). */
        fun sub(): Reader {
            val len = varint()
            if (len < 0 || len > (end - pos).toLong()) throw ProtocolException("bad length")
            val r = Reader(buf, pos, pos + len.toInt())
            pos += len.toInt()
            return r
        }

        fun string(): String {
            val len = varint()
            if (len < 0 || len > (end - pos).toLong()) throw ProtocolException("bad length")
            val s = String(buf, pos, len.toInt(), Charsets.UTF_8)
            pos += len.toInt()
            return s
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { if (pos + 8 > end) throw ProtocolException("truncated fixed64"); pos += 8 }
                2 -> { val len = varint(); if (len < 0 || len > (end - pos).toLong()) throw ProtocolException("bad length"); pos += len.toInt() }
                5 -> { if (pos + 4 > end) throw ProtocolException("truncated fixed32"); pos += 4 }
                else -> throw ProtocolException("unsupported wire type $wire")
            }
        }
    }

    /** A box exactly as Lens reports it. [normalized] = fractions of the uploaded image, else pixels of it. */
    class Box(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val rotationRad: Float, val normalized: Boolean
    )

    class Line(val text: String, val box: Box?)
    class Paragraph(val lines: List<Line>, val vertical: Boolean, val box: Box?)

    /**
     * [valid] is true only when the body really was a ServerResponse that carried an objects_response.
     * [serverError] is the server-reported error type (0 = none).
     */
    /** Field-number-only trace of the response tree; deliberately contains no OCR text or geometry values. */
    class Shape(
        val rootFields: List<Int>,
        val objectFields: List<Int>,
        val textFields: List<Int>,
        val layoutFields: List<Int>,
        val hasText: Boolean,
        val hasTextLayout: Boolean
    ) {
        override fun toString(): String =
            "rootFields=$rootFields objectFields=$objectFields hasText=$hasText " +
                "textFields=$textFields hasTextLayout=$hasTextLayout layoutFields=$layoutFields"
    }

    class Response(
        val paragraphs: List<Paragraph>,
        val valid: Boolean,
        val serverError: Int,
        val shape: Shape
    )

    private class ParseTrace {
        val rootFields = ArrayList<Int>()
        val objectFields = ArrayList<Int>()
        val textFields = ArrayList<Int>()
        val layoutFields = ArrayList<Int>()
        var hasText = false
        var hasTextLayout = false
        fun shape() = Shape(rootFields.toList(), objectFields.toList(), textFields.toList(),
            layoutFields.toList(), hasText, hasTextLayout)
    }

    fun parseResponse(body: ByteArray): Response {
        if (body.isEmpty()) throw ProtocolException("empty response body")
        val root = Reader(body, 0, body.size)
        val trace = ParseTrace()
        var sawObjects = false
        var serverError = 0
        val paragraphs = ArrayList<Paragraph>()
        while (root.hasMore()) {
            val t = root.tag()
            val field = t ushr 3
            val wire = t and 7
            trace.rootFields.add(field)
            when {
                field == 1 && wire == 2 -> {                       // error
                    val e = root.sub()
                    while (e.hasMore()) {
                        val et = e.tag()
                        if (et ushr 3 == 1 && et and 7 == 0) serverError = e.varint().toInt() else e.skip(et and 7)
                    }
                }
                field == 2 && wire == 2 -> {                       // objects_response
                    sawObjects = true
                    readObjectsResponse(root.sub(), paragraphs, trace)
                }
                else -> root.skip(wire)
            }
        }
        return Response(paragraphs, sawObjects, serverError, trace.shape())
    }

    private fun readObjectsResponse(r: Reader, out: MutableList<Paragraph>, trace: ParseTrace) {
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            trace.objectFields.add(field)
            if (field == 3 && wire == 2) {
                trace.hasText = true
                readText(r.sub(), out, trace)
            } else r.skip(wire)
        }
    }

    private fun readText(r: Reader, out: MutableList<Paragraph>, trace: ParseTrace) {
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            trace.textFields.add(field)
            if (field == 1 && wire == 2) {
                trace.hasTextLayout = true
                readTextLayout(r.sub(), out, trace)
            } else r.skip(wire)
        }
    }

    private fun readTextLayout(r: Reader, out: MutableList<Paragraph>, trace: ParseTrace) {
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            trace.layoutFields.add(field)
            if (field == 1 && wire == 2) out.add(readParagraph(r.sub())) else r.skip(wire)
        }
    }

    private fun readParagraph(r: Reader): Paragraph {
        val lines = ArrayList<Line>()
        var box: Box? = null
        var vertical = false
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                field == 2 && wire == 2 -> lines.add(readLine(r.sub()))
                field == 3 && wire == 2 -> box = readGeometry(r.sub())
                field == 4 && wire == 0 -> vertical = r.varint().toInt() == WRITING_TOP_TO_BOTTOM
                else -> r.skip(wire)
            }
        }
        return Paragraph(lines, vertical, box)
    }

    private fun readLine(r: Reader): Line {
        val sb = StringBuilder()
        val wordBoxes = ArrayList<Box>()
        var box: Box? = null
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                field == 1 && wire == 2 -> readWord(r.sub(), sb, wordBoxes)
                field == 2 && wire == 2 -> box = readGeometry(r.sub())
                else -> r.skip(wire)
            }
        }
        // Lens can omit Line.geometry and Paragraph.geometry while still returning precise
        // per-word Geometry (TextLayout.Word.geometry = field 4). Do not discard valid OCR
        // just because the server chose the word-level layout.
        if (box == null) box = unionBoxes(wordBoxes)
        return Line(sb.toString().trim(), box)
    }

    /** Appends plain_text + text_separator and collects the optional geometry of one word. */
    private fun readWord(r: Reader, sb: StringBuilder, wordBoxes: MutableList<Box>) {
        var text = ""
        var sep = ""
        var geometry: Box? = null
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                field == 2 && wire == 2 -> text = r.string()
                field == 3 && wire == 2 -> sep = r.string()
                field == 4 && wire == 2 -> geometry = readGeometry(r.sub())
                else -> r.skip(wire)
            }
        }
        sb.append(text).append(sep)
        geometry?.let { wordBoxes.add(it) }
    }

    /** Geometry{ 1: bounding_box = CenterRotatedBox }. */
    private fun readGeometry(r: Reader): Box? {
        var box: Box? = null
        while (r.hasMore()) {
            val t = r.tag()
            if (t ushr 3 == 1 && t and 7 == 2) box = readBox(r.sub()) else r.skip(t and 7)
        }
        return box
    }

    private fun readBox(r: Reader): Box {
        var cx = 0f; var cy = 0f; var w = 0f; var h = 0f; var rot = 0f
        var coord = 0
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                wire == 5 && field == 1 -> cx = r.float()
                wire == 5 && field == 2 -> cy = r.float()
                wire == 5 && field == 3 -> w = r.float()
                wire == 5 && field == 4 -> h = r.float()
                wire == 5 && field == 5 -> rot = r.float()
                wire == 0 && field == 6 -> coord = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        return Box(cx, cy, w, h, rot, normalized = coord != COORD_IMAGE)
    }

    // =============================================================================================
    // Mapping to the app's OcrBlock format
    // =============================================================================================

    /**
     * Converts a parsed response into the blocks the rest of NovelStudio already consumes, in pixels of
     * the ORIGINAL bitmap ([origW] x [origH]); [sentW] x [sentH] is what was actually uploaded.
     *
     * Lens already does the hard part - it decides which lines belong to ONE paragraph (= speech bubble /
     * caption). Earlier versions threw that knowledge away, emitted every line alone and let the app guess
     * the bubbles again from pixel geometry. That guess could fuse the top and bottom line of a bubble while
     * the middle line was left behind, which is the "middle line is skipped" bug. Now:
     *  - the lines of one Lens paragraph become ONE block (text joined in Lens' own reading order, box = union);
     *  - a paragraph is only split when its lines are obviously not one block (huge gap, very different
     *    glyph size, no horizontal relation) or when a line is tilted (sound effects stay separate);
     *  - vertical text is one block per paragraph, its columns in the order Lens reported them.
     */
    fun toBlocks(resp: Response, origW: Int, origH: Int, sentW: Int, sentH: Int): List<OcrBlock> {
        val out = ArrayList<OcrBlock>()
        for (p in resp.paragraphs) {
            if (p.vertical) {
                val text = p.lines.map { it.text }.filter { it.isNotEmpty() }.joinToString(" ")
                if (text.isEmpty()) continue
                val g = pixels(p.box ?: unionOf(p.lines), origW, origH, sentW, sentH) ?: continue
                out.add(block(text, g, vertical = true))
                continue
            }
            val lines = horizontalLines(p, origW, origH, sentW, sentH)
            if (lines.isEmpty()) continue
            val chunk = ArrayList<LineGeo>()
            for (l in lines) {
                if (chunk.isNotEmpty() && !continues(chunk.last(), l)) {
                    out.add(chunkBlock(chunk)); chunk.clear()
                }
                chunk.add(l)
            }
            if (chunk.isNotEmpty()) out.add(chunkBlock(chunk))
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private class LineGeo(val text: String, val g: Px)

    /** Non-empty, non-watermark lines of a horizontal paragraph with a pixel box each (Lens order kept). */
    private fun horizontalLines(p: Paragraph, origW: Int, origH: Int, sentW: Int, sentH: Int): List<LineGeo> {
        val real = p.lines.filter { it.text.isNotEmpty() }
        if (real.isEmpty()) return emptyList()
        val pbox = pixels(p.box, origW, origH, sentW, sentH)
        // No geometry on any line, only on the paragraph: one block (or tilted: never split a rotated box).
        if (pbox != null && real.all { it.box == null } && (real.size == 1 || Math.abs(pbox.angle) >= 6f)) {
            val kept = real.map { it.text }.filterNot { OcrPost.isSiteMark(it) }
            return if (kept.isEmpty()) emptyList() else listOf(LineGeo(BubbleGrouping.joinLines(kept), pbox))
        }
        val n = real.size
        val out = ArrayList<LineGeo>(n)
        for ((i, l) in real.withIndex()) {
            if (OcrPost.isSiteMark(l.text)) continue     // watermark line: dropped alone, the bubble survives
            var g = pixels(l.box, origW, origH, sentW, sentH)
            if (g == null && pbox != null) {
                // Share the paragraph box in equal horizontal bands instead of stacking identical boxes.
                g = Px(pbox.cx, pbox.cy - pbox.h / 2f + pbox.h * (i + 0.5f) / n, pbox.w, pbox.h / n, pbox.angle)
            }
            if (g == null) continue
            out.add(LineGeo(l.text, g))
        }
        return out
    }

    /** Sanity check on Lens' paragraph: do these two consecutive lines really belong to one block? */
    private fun continues(a: LineGeo, b: LineGeo): Boolean {
        val ga = a.g; val gb = b.g
        if (Math.abs(ga.angle) >= 6f || Math.abs(gb.angle) >= 6f) return false
        val ratio = ga.h / gb.h
        if (ratio < 0.45f || ratio > 2.2f) return false
        val f = Math.max(ga.h, gb.h)
        val vGap = Math.max(0f, Math.max(ga.cy - ga.h / 2f, gb.cy - gb.h / 2f) - Math.min(ga.cy + ga.h / 2f, gb.cy + gb.h / 2f))
        if (vGap > f * 2.6f) return false
        val hGap = Math.max(0f, Math.max(ga.cx - ga.w / 2f, gb.cx - gb.w / 2f) - Math.min(ga.cx + ga.w / 2f, gb.cx + gb.w / 2f))
        return hGap <= f * 3f
    }

    private fun chunkBlock(c: List<LineGeo>): OcrBlock {
        if (c.size == 1) {
            val g = c[0].g
            // A single narrow glyph ("I", "!", "?") is a tall thin box but not an upright column.
            val tinyAscii = c[0].text.length <= 2 && c[0].text.all { it.code < 128 }
            return block(c[0].text, g, vertical = g.h > g.w * 1.6f && Math.abs(g.angle) < 6f && !tinyAscii)
        }
        val l = c.minOf { it.g.cx - it.g.w / 2f }
        val r = c.maxOf { it.g.cx + it.g.w / 2f }
        val t = c.minOf { it.g.cy - it.g.h / 2f }
        val b = c.maxOf { it.g.cy + it.g.h / 2f }
        val hs = c.map { it.g.h }.sorted()
        val med = hs[hs.size / 2]
        return OcrBlock(
            text = BubbleGrouping.joinLines(c.map { it.text }),
            top = t.toInt(), left = l.toInt(),
            cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, angle = 0f,
            fontPx = med * 0.9f, vertical = false, confidence = 0f
        )
    }

    private class Px(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)

    private fun unionOf(lines: List<Line>): Box? = unionBoxes(lines.mapNotNull { it.box })

    private fun unionBoxes(boxes: List<Box>): Box? {
        if (boxes.isEmpty()) return null
        val l = boxes.minOf { it.cx - it.w / 2f }
        val r = boxes.maxOf { it.cx + it.w / 2f }
        val t = boxes.minOf { it.cy - it.h / 2f }
        val b = boxes.maxOf { it.cy + it.h / 2f }
        return Box((l + r) / 2f, (t + b) / 2f, r - l, b - t, 0f, boxes[0].normalized)
    }

    private fun pixels(b: Box?, origW: Int, origH: Int, sentW: Int, sentH: Int): Px? {
        if (b == null) return null
        val kx = if (b.normalized) origW.toFloat() else origW.toFloat() / sentW.coerceAtLeast(1)
        val ky = if (b.normalized) origH.toFloat() else origH.toFloat() / sentH.coerceAtLeast(1)
        val px = Px(b.cx * kx, b.cy * ky, b.w * kx, b.h * ky,
            // Assumption (not verifiable offline): positive = clockwise in image space, as for ML Kit.
            OcrPost.foldAngle(Math.toDegrees(b.rotationRad.toDouble()).toFloat()))
        if (!px.cx.isFinite() || !px.cy.isFinite() || !px.w.isFinite() || !px.h.isFinite()) return null
        if (px.w <= 0f || px.h <= 0f) return null
        return px
    }

    private fun block(text: String, g: Px, vertical: Boolean): OcrBlock {
        val glyph = (if (vertical) g.w else g.h) * 0.9f
        return OcrBlock(
            text = text,
            top = (g.cy - g.h / 2f).toInt(), left = (g.cx - g.w / 2f).toInt(),
            cx = g.cx, cy = g.cy, w = g.w, h = g.h, angle = g.angle,
            fontPx = glyph, vertical = vertical,
            confidence = 0f   // Lens reports none; 0 = "unknown" for OcrLineFusion
        )
    }
}
