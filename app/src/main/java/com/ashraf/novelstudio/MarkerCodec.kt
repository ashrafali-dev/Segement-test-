package com.ashraf.novelstudio

/**
 * Builds and parses the numbered markers that tie a chatbot reply to comic bubbles.
 *
 * The one rule this file exists to enforce: **marker number N is bubble index N**, always.
 *
 * Before, every prompt builder joined the bubbles with blank lines and then a separate function
 * (`MainActivity.addInternalMarkers`) split that string back up — by blank line, by line count, and by
 * sentence chunking. Any bubble whose text contained a blank line, any bubble with empty text, any long
 * bubble, or a one/two-bubble batch with many lines shifted the numbering, so `[007]` no longer meant
 * bubble 7 and the alignment dropped the whole reply. Nothing here splits, merges or drops: the marker
 * count is exactly the bubble count.
 */
internal object MarkerCodec {

    /** Width of the zero-padded marker number, e.g. `[001]`. */
    private const val WIDTH = 3

    /** A marker in a reply: `[1]`, `[001]`, `[P1_L1]` — the model is not always consistent. */
    private val MARK = Regex("\\[(?:P\\d+[_\\-]L?)?(\\d{1,6})\\]")

    /** Collapse every kind of line break to a single space and trim. Never leaves a blank line. */
    fun oneLine(text: String): String =
        text.replace(Regex("[\\r\\n\\u2028\\u2029]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    fun marker(no: Int): String = "[" + no.toString().padStart(WIDTH, '0') + "]"

    /**
     * Ollama-style prompt body: one marker per bubble, in bubble order.
     *
     * @param titleOffset 1 when the chapter title is sent as its own `[001]` above the bubbles
     *   (full-chapter scan), 0 when the reply starts at the first bubble (realtime, manual).
     */
    fun build(bubbles: List<WmBubble>, titleOffset: Int = 0, title: String = ""): String {
        val sb = StringBuilder()
        var no = 1
        if (titleOffset > 0 && title.isNotBlank()) {
            sb.append(marker(no++)).append(' ').append(oneLine(title)).append('\n')
        }
        for (b in bubbles) {
            // A bubble with no text still gets its marker: dropping it would shift every later number,
            // which is exactly the bug this class replaces. An empty body tells the model to answer
            // nothing for that bubble, and the empty reply is simply not placed.
            sb.append(marker(no++)).append(' ').append(oneLine(b.sourceText)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** How many markers [build] would emit, so callers can assert marker count == bubble count. */
    fun expectedMarkers(bubbles: List<WmBubble>, titleOffset: Int = 0, title: String = ""): Int =
        bubbles.size + if (titleOffset > 0 && title.isNotBlank()) 1 else 0

    /** True when the reply carries at least one marker (so positional fallback must not be used). */
    fun hasMarkers(text: String): Boolean = MARK.containsMatchIn(text)

    /** Every marker number in the reply, in order (duplicates preserved). */
    fun numbers(text: String): List<Int> =
        MARK.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

    /**
     * Map a marked reply onto bubble indices.
     *
     * Per-marker, never all-or-nothing: a marker that is out of range, duplicated, garbled or empty is
     * skipped on its own and reported, while every other marker is still placed. Returns a list the same
     * length as [bubbleCount]; `null` at index i means "nothing to place for bubble i".
     *
     * @param titleOffset see [build]. Markers are 1-based in the reply; bubble 0 is marker `1 + titleOffset`.
     * @param onSkip called for every skipped marker with a short reason, for the OCR log.
     */
    fun align(
        text: String,
        bubbleCount: Int,
        titleOffset: Int = 0,
        onSkip: (no: Int, reason: String) -> Unit = { _, _ -> }
    ): List<String?> {
        val out = MutableList<String?>(bubbleCount) { null }
        if (bubbleCount <= 0) return out
        val ms = MARK.findAll(text).toList()
        if (ms.isEmpty()) return out

        val firstBubbleNo = 1 + titleOffset
        val lastBubbleNo = bubbleCount + titleOffset
        val seen = HashSet<Int>()

        for ((k, m) in ms.withIndex()) {
            val raw = m.groupValues[1]
            val no = raw.toIntOrNull()
            if (no == null) { onSkip(-1, "garbled number '$raw'"); continue }
            if (!seen.add(no)) { onSkip(no, "duplicate marker"); continue }
            val end = if (k + 1 < ms.size) ms[k + 1].range.first else text.length
            val body = text.substring(m.range.last + 1, end)
            val clean = oneLine(body)
            // Older prompts used a tilde as a fake translation for a skipped OCR line; treat it as empty
            // so it is never painted over a bubble.
            if (clean.isEmpty() || clean.matches(Regex("[~～]+"))) { onSkip(no, "empty body"); continue }
            if (no < firstBubbleNo || no > lastBubbleNo) { onSkip(no, "out of range 1..$lastBubbleNo"); continue }
            out[no - firstBubbleNo] = clean
        }
        return out
    }

    /**
     * Positional alignment for a marker-less reply: accepted only when the paragraph count matches the
     * bubble count exactly, so a guess can never shift every line by one.
     */
    fun alignByParagraphs(
        text: String,
        bubbleCount: Int,
        onSkip: (no: Int, reason: String) -> Unit = { _, _ -> }
    ): List<String?> {
        val out = MutableList<String?>(bubbleCount) { null }
        if (bubbleCount <= 0) return out
        val paras = text.split(Regex("\\n\\s*\\n")).map { oneLine(it) }.filter { it.isNotEmpty() }
        if (paras.size != bubbleCount) {
            onSkip(-1, "no markers and ${paras.size} paragraphs != $bubbleCount bubbles")
            return out
        }
        for (i in 0 until bubbleCount) out[i] = paras[i]
        return out
    }

    /**
     * Complete only unambiguous placement.
     *
     * Explicit marker IDs are never remapped: duplicate, garbled, or out-of-range bodies remain unplaced rather
     * than being guessed onto another bubble. Markerless paragraphs are accepted only when their count matches
     * every bubble (or one title plus every bubble), then mapped by spatial order.
     */
    fun recoverFailedMarkersBySpatialOrder(
        text: String,
        bubbleCount: Int,
        titleOffset: Int,
        existing: List<String?>,
        spatialOrder: List<Int>,
        onFallback: (no: Int, targetIndex: Int, reason: String) -> Unit = { _, _, _ -> }
    ): List<String?> {
        val out = MutableList<String?>(bubbleCount) { existing.getOrNull(it) }
        if (bubbleCount <= 0) return out
        val matches = MARK.findAll(text).toList()
        val order = (spatialOrder.filter { it in 0 until bubbleCount }.distinct() +
            (0 until bubbleCount).filter { it !in spatialOrder })

        if (matches.isNotEmpty()) {
            // Explicit marker IDs are the only reliable bubble identity. Reassigning a duplicate or out-of-range
            // marker to a nearby empty slot silently puts a valid translation on the WRONG bubble. Keep every
            // exact valid marker from [align] and leave failed markers empty for a targeted retry.
            return out
        }

        // Markerless output may be placed only when its paragraph count maps exactly to the source bubbles
        // (or to one title plus all bubbles). Never shift a partial answer across unrelated speech balloons.
        val rawParagraphs = text.split(Regex("\\n\\s*\\n"))
            .map { oneLine(it) }.filter { it.isNotEmpty() }
        val paragraphs = if (titleOffset > 0 && rawParagraphs.size == bubbleCount + 1)
            rawParagraphs.drop(1) else rawParagraphs
        if (paragraphs.size != bubbleCount) {
            onFallback(-1, -1, "markerless count mismatch: ${paragraphs.size} paragraphs for $bubbleCount bubbles")
            return out
        }
        for (i in paragraphs.indices) {
            val target = order[i]
            if (out[target].isNullOrBlank()) {
                out[target] = paragraphs[i]
                onFallback(-1, target, "exact-count markerless spatial fallback")
            }
        }
        return out
    }

    /** True when at least one bubble got text. */
    fun hasPlacement(placed: List<String?>): Boolean = placed.any { !it.isNullOrBlank() }

    /** Bubble indices that still have nothing, for a targeted follow-up request. */
    fun missing(placed: List<String?>): List<Int> =
        placed.indices.filter { placed[it].isNullOrBlank() }
}
