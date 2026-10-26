package com.ashraf.novelstudio

/**
 * Groups OCR lines into speech bubbles and produces one [WmBubble] per bubble.
 *
 * This is the shared geometry used by every mode. Previously each mode had its own version: scanning/real-time
 * grouped inside `WebModeExtractor.readSlice`, while manual took the union of every OCR box and produced one
 * giant block. That is why manual put the whole screen in one bubble at the wrong position and scanning relied
 * on a pixel guess whenever the detector was skipped.
 *
 * The rules, in order:
 *  1. A detector `text_bubble` line belongs to the bubble whose outline holds it.
 *  2. A detector `text_free` line is its own bubble (captions, SFX, narration on artwork).
 *  3. Without a detector, fall back to the pixel grouping helpers ([BubbleGrouping]).
 *
 * Pure Kotlin apart from reading pixels through the supplied callbacks, so it is unit-tested on the JVM with
 * synthetic boxes (see BubbleBuilderTest).
 */
internal object BubbleBuilder {

    /** One grouped bubble in *image* pixels. */
    class Group(
        /** Interior used for the cover/mask; equal to the text box when no outline was found. */
        val l: Int, val t: Int, val r: Int, val b: Int,
        /** Union of the member text boxes, used for the erase test and font size. */
        val textL: Int, val textT: Int, val textR: Int, val textB: Int,
        val lines: List<OcrBlock>,
        /** True for lettering that sits outside any bubble outline (SFX, narration on artwork). */
        val free: Boolean
    ) {
        val text: String get() = lines.sortedWith(compareBy({ it.cy }, { it.cx }))
            .joinToString(" ") { it.text.replace(Regex("\\s+"), " ").trim() }
            .trim()

        /** Median font size of the member lines; 0 when the engine reported none. */
        val fontPx: Float
            get() = lines.mapNotNull { b -> b.fontPx.takeIf { it > 0f } }
                .sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

        /** Per-line grouped text, used by vertical (right-to-left) columns. */
        fun columnText(vertical: Boolean): String = if (!vertical) text else
            lines.sortedWith(compareByDescending<OcrBlock> { it.cx })
                .joinToString(" ") { it.text.replace(Regex("\\s+"), " ").trim() }.trim()
    }

    /**
     * Group [blocks] (in image pixels) into bubbles.
     *
     * @param dets detector output for this image, or null when the detector is not available.
     */
    fun group(blocks: List<OcrBlock>, dets: List<BubbleDetectorCore.Det>?): List<Group> {
        if (blocks.isEmpty()) return emptyList()
        val rects = blocks.map { intArrayOf(it.cx.toInt() - (it.w / 2).toInt(), it.cy.toInt() - (it.h / 2).toInt(), it.cx.toInt() + (it.w / 2).toInt(), it.cy.toInt() + (it.h / 2).toInt()) }

        // 1) Assign each line to a container: a detector bubble outline, else its own free box, else null.
        val containerOf = arrayOfNulls<BubbleDetectorCore.Det>(blocks.size)
        val freeOf = BooleanArray(blocks.size)
        for (i in blocks.indices) {
            val rc = rects[i]
            val inter = BubbleDetectorCore.interiorFor(dets, rc[0], rc[1], rc[2], rc[3])
            if (inter != null) {
                containerOf[i] = dets?.firstOrNull { d ->
                    d.label == BubbleDetectorCore.CLS_BUBBLE &&
                        (d.l == inter[0] && d.t == inter[1] && d.r == inter[2] && d.b == inter[3])
                } ?: BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, 1f, inter[0], inter[1], inter[2], inter[3])
            } else if (dets != null && BubbleDetectorCore.isFreeText(dets, blocks[i].cx, blocks[i].cy)) {
                freeOf[i] = true
            }
        }

        val groups = ArrayList<Group>()
        val used = BooleanArray(blocks.size)

        // 2) Detector bubbles first: every line inside one outline becomes one group.
        val byContainer = LinkedHashMap<String, MutableList<Int>>()
        for (i in blocks.indices) {
            val c = containerOf[i] ?: continue
            byContainer.getOrPut("${c.l}:${c.t}:${c.r}:${c.b}") { ArrayList() }.add(i)
        }
        for ((_, members) in byContainer) {
            val c = containerOf[members[0]]!!
            members.forEach { used[it] = true }
            groups.add(makeGroup(members.map { blocks[it] }, c.l, c.t, c.r, c.b, free = false))
        }

        // 3) Free lettering: each detected text_free box is one group; leftover free lines group by proximity.
        val freeIdx = blocks.indices.filter { freeOf[it] }
        val freeBoxes = dets?.filter { it.label == BubbleDetectorCore.CLS_TEXT_FREE } ?: emptyList()
        val claimed = BooleanArray(blocks.size)
        for (fb in freeBoxes) {
            val members = freeIdx.filter { !claimed[it] && fb.contains(blocks[it].cx, blocks[it].cy) && !used[it] }
            if (members.isEmpty()) continue
            members.forEach { claimed[it] = true; used[it] = true }
            groups.add(makeGroup(members.map { blocks[it] }, fb.l, fb.t, fb.r, fb.b, free = true))
        }
        for (i in freeIdx) {
            if (used[i]) continue
            used[i] = true
            val rc = rects[i]
            groups.add(makeGroup(listOf(blocks[i]), rc[0], rc[1], rc[2], rc[3], free = true))
        }

        // 4) Anything the detector did not cover: pixel grouping (same rule the old code used).
        val rest = blocks.indices.filter { !used[it] }
        for (g in groupByPixel(rest.map { blocks[it] }) ) groups.add(g)

        return groups.filter { it.text.isNotBlank() || it.lines.isNotEmpty() }
    }

    /**
     * Pixel-only fallback: join lines that sit on top of each other, then take each cluster's union box.
     * Used when the detector is not installed, and for every line the detector missed.
     */
    fun groupByPixel(blocks: List<OcrBlock>, dets: List<BubbleDetectorCore.Det>? = null): List<Group> {
        if (blocks.isEmpty()) return emptyList()
        val n = blocks.size
        val parent = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }

        for (i in 0 until n) for (j in i + 1 until n) {
            val a = blocks[i]; val b = blocks[j]
            val different = dets != null && BubbleDetectorCore.inDifferentBubbles(dets, a.cx, a.cy, b.cx, b.cy)
            if (different) continue
            val same = if (a.vertical || b.vertical) {
                // Japanese columns run top-to-bottom and right-to-left: never apply the horizontal rule.
                BubbleGrouping.verticalNeighbour(a, b)
            } else {
                BubbleGrouping.likelySameBubbleLine(a, b, maxGapFonts = 2.4f, loose = true)
            }
            if (same) union(i, j)
        }

        val clusters = LinkedHashMap<Int, MutableList<OcrBlock>>()
        for (i in 0 until n) clusters.getOrPut(find(i)) { ArrayList() }.add(blocks[i])
        return clusters.values.map { members ->
            val l = members.minOf { (it.cx - it.w / 2).toInt() }
            val t = members.minOf { (it.cy - it.h / 2).toInt() }
            val r = members.maxOf { (it.cx + it.w / 2).toInt() }
            val b = members.maxOf { (it.cy + it.h / 2).toInt() }
            makeGroup(members, l, t, r, b, free = false)
        }
    }

    private fun makeGroup(
        lines: List<OcrBlock>, l: Int, t: Int, r: Int, b: Int, free: Boolean
    ): Group {
        val textL = lines.minOf { (it.cx - it.w / 2).toInt() }
        val textT = lines.minOf { (it.cy - it.h / 2).toInt() }
        val textR = lines.maxOf { (it.cx + it.w / 2).toInt() }
        val textB = lines.maxOf { (it.cy + it.h / 2).toInt() }
        return Group(l, t, r, b, textL, textT, textR, textB, lines, free)
    }
}
