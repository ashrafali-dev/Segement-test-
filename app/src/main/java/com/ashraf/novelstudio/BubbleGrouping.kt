package com.ashraf.novelstudio

/**
 * Pure geometry behind "one speech bubble = one translation unit" (moved verbatim out of WebModeExtractor so it
 * can be unit-tested on the JVM). Behaviour is unchanged.
 */
internal object BubbleGrouping {
    fun aligned(a: OcrBlock, b: OcrBlock): Boolean {
        val overlap = Math.min(a.cx + a.w / 2f, b.cx + b.w / 2f) - Math.max(a.cx - a.w / 2f, b.cx - b.w / 2f)
        return overlap >= Math.min(a.w, b.w) * 0.3f || Math.abs(a.cx - b.cx) <= Math.max(a.w, b.w) * 0.6f
    }

    fun likelySameBubbleLine(a: OcrBlock, b: OcrBlock, maxGapFonts: Float = 2.0f, loose: Boolean = false): Boolean {
        if (a.vertical || b.vertical) return false
        val maxTilt = if (loose) 20f else 6f
        if (Math.abs(OcrPost.usableTilt(a.angle)) >= maxTilt ||
            Math.abs(OcrPost.usableTilt(b.angle)) >= maxTilt) return false
        val fa = if (a.fontPx > 0f) a.fontPx else a.h
        val fb = if (b.fontPx > 0f) b.fontPx else b.h
        if (fa <= 0f || fb <= 0f) return false
        val ratio = fa / fb
        if (ratio < (if (loose) 0.4f else 0.55f) || ratio > (if (loose) 2.5f else 1.8f)) return false
        val scale = maxOf(fa, fb)
        val atop = a.cy - a.h / 2f
        val abot = a.cy + a.h / 2f
        val btop = b.cy - b.h / 2f
        val bbot = b.cy + b.h / 2f
        val gap = maxOf(0f, maxOf(atop, btop) - minOf(abot, bbot))
        if (gap > scale * maxGapFonts) return false
        val al = a.cx - a.w / 2f; val ar = a.cx + a.w / 2f
        val bl = b.cx - b.w / 2f; val br = b.cx + b.w / 2f
        val horizontalGap = maxOf(0f, maxOf(al, bl) - minOf(ar, br))
        return horizontalGap <= scale * 4f
    }

    /** A line may join an existing group only if its font is within this ratio of the group's median font. */
    const val GROUP_FONT_RATIO_MIN = 0.5f
    const val GROUP_FONT_RATIO_MAX = 2.0f
    /**
     * Once a group has 2+ lines its own leading is known: a new line must sit within
     * max(LEADING_TOLERANCE x median leading, MIN_LEADING_FONTS x font) of the last line.
     * (A single-line group only has the generic "<= 1 font" gap rule.)
     */
    const val LEADING_TOLERANCE = 2.0f
    const val MIN_LEADING_FONTS = 0.6f

    private fun font(b: OcrBlock) = if (b.fontPx > 0f) b.fontPx else b.h
    private fun gap(a: OcrBlock, b: OcrBlock): Float =
        maxOf(0f, maxOf(a.cy - a.h / 2f, b.cy - b.h / 2f) - minOf(a.cy + a.h / 2f, b.cy + b.h / 2f))
    private fun hOverlap(a: OcrBlock, b: OcrBlock): Float =
        minOf(a.cx + a.w / 2f, b.cx + b.w / 2f) - maxOf(a.cx - a.w / 2f, b.cx - b.w / 2f)

    /** Pairwise rule used while no bubble outline is known (generic gap <= 1 font, aligned, similar font). */
    fun sameBubbleFast(a: OcrBlock, b: OcrBlock): Boolean =
        // OCR boxes often leave a larger vertical gap than the glyph height inside tall Korean webtoon balloons.
        // Production callers also pass outlineBetween() as a veto, so tolerate wider line spacing without
        // blindly chaining two visibly separate balloons together.
        likelySameBubbleLine(a, b, 1.6f, loose = true) && aligned(a, b)

    /**
     * Groups the lines [pool] (indices into [blocks]) into speech bubbles.
     *
     * Lines are visited top to bottom and each one is attached to AT MOST ONE existing group, and groups are never
     * merged afterwards. The old code grew a group with `grp.any { pairwise match }`, so one stray line that sat within
     * range of two different bubbles fused them (transitive chain-merge). Here a line also has to match the group's
     * own leading and font size, and an optional [separated] predicate (image evidence: an outline between the two
     * lines) can veto a join. When two groups both qualify the closer one (then the more overlapping one) wins.
     */
    fun groups(
        blocks: List<OcrBlock>, pool: List<Int>,
        separated: ((OcrBlock, OcrBlock) -> Boolean)? = null
    ): List<List<Int>> {
        val order = pool.distinct().sortedWith(compareBy({ blocks[it].cy }, { blocks[it].cx }))
        val groups = ArrayList<MutableList<Int>>()
        for (idx in order) {
            val b = blocks[idx]
            var best = -1
            var bestGap = Float.MAX_VALUE
            var bestOverlap = -Float.MAX_VALUE
            for ((gi, g) in groups.withIndex()) {
                val last = blocks[g.last()]
                if (!sameBubbleFast(last, b)) continue
                // A line that sits between [last] and [b] but belongs elsewhere must not be skipped over.
                if (blockBetween(blocks, order, last, b)) continue
                val fonts = g.map { font(blocks[it]) }.sorted()
                val med = fonts[fonts.size / 2]
                val ratio = font(b) / med
                if (ratio < GROUP_FONT_RATIO_MIN || ratio > GROUP_FONT_RATIO_MAX) continue
                val gp = gap(last, b)
                if (g.size >= 2) {
                    val gaps = (1 until g.size).map { gap(blocks[g[it - 1]], blocks[g[it]]) }.sorted()
                    val limit = maxOf(LEADING_TOLERANCE * gaps[gaps.size / 2], MIN_LEADING_FONTS * maxOf(med, font(b)))
                    if (gp > limit) continue
                }
                if (separated != null && separated(last, b)) continue
                val ov = hOverlap(last, b)
                if (gp < bestGap || (gp == bestGap && ov > bestOverlap)) { best = gi; bestGap = gp; bestOverlap = ov }
            }
            if (best >= 0) groups[best].add(idx) else groups.add(arrayListOf(idx))
        }
        return groups
    }

    /** True when some other block of [pool] sits vertically between [a] and [b] and overlaps either horizontally. */
    private fun blockBetween(blocks: List<OcrBlock>, pool: List<Int>, a: OcrBlock, b: OcrBlock): Boolean {
        val top = if (a.cy <= b.cy) a else b
        val bot = if (top === a) b else a
        val y0 = top.cy + top.h * 0.25f
        val y1 = bot.cy - bot.h * 0.25f
        if (y1 <= y0) return false
        for (i in pool) {
            val c = blocks[i]
            if (c === a || c === b) continue
            if (c.cy <= y0 || c.cy >= y1) continue
            if (hOverlap(c, a) > 0f || hOverlap(c, b) > 0f) return true
        }
        return false
    }

    /** The group of [i] among [pool] (always contains [i]). */
    fun groupContaining(
        blocks: List<OcrBlock>, i: Int, pool: List<Int>,
        separated: ((OcrBlock, OcrBlock) -> Boolean)? = null
    ): List<Int> = groups(blocks, pool + i, separated).first { i in it }

    private fun isUnspacedScript(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /** Joins the lines of one bubble: a space between words/Hangul lines, none between Japanese/Chinese lines. */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty() && !(isUnspacedScript(sb.last()) && isUnspacedScript(t.first()))) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }
}
