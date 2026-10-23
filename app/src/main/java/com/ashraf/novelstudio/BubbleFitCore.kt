package com.ashraf.novelstudio

/**
 * Pure geometry (no Android types) that finds where a translation may be written INSIDE a speech bubble.
 *
 * Ported idea from Manga Translator (BubbleShapePaths.insetTextBounds / findLargestFilledRect): a bubble is a
 * SHAPE, the text goes into the largest rectangle that fits inside that shape, and the font is searched for
 * that rectangle. Manga Translator gets the shape from a segmentation model; here it is rebuilt cheaply from the
 * picture itself: the flat paper colour around the recognised lettering is flood-filled on a small (<= ~200 px)
 * grid, so the cost stays tiny even in real-time mode.
 *
 * Window pixels are ARGB ints of a window cut out of the page. All rectangles are [left, top, right, bottom]
 * with right/bottom exclusive, in window pixels.
 */
internal object BubbleFitCore {

    /** Filled-cell grid of the bubble interior plus the scale back to window pixels. */
    class Mask(val sw: Int, val sh: Int, val ds: Int, val filled: BooleanArray) {
        /** Share (0..1) of the window rectangle that lies inside the bubble interior. */
        fun coverage(l: Int, t: Int, r: Int, b: Int): Float {
            val x0 = (l / ds).coerceIn(0, sw)
            val x1 = ((r + ds - 1) / ds).coerceIn(x0, sw)
            val y0 = (t / ds).coerceIn(0, sh)
            val y1 = ((b + ds - 1) / ds).coerceIn(y0, sh)
            val total = (x1 - x0) * (y1 - y0)
            if (total <= 0) return 0f
            var inside = 0
            for (y in y0 until y1) {
                val row = y * sw
                for (x in x0 until x1) if (filled[row + x]) inside++
            }
            return inside.toFloat() / total
        }
    }

    class Result(
        /** Bounding box of the bubble interior (thin tails ignored). */
        val interior: IntArray,
        /** Largest rectangle completely inside the bubble, already inset a little from the outline. */
        val safe: IntArray,
        val mask: Mask
    )

    private const val TOL = 38
    private const val MAX_GRID = 200

    private fun ch(c: Int, shift: Int) = (c shr shift) and 255
    private fun dist(a: Int, b: Int): Int =
        maxOf(Math.abs(ch(a, 16) - ch(b, 16)), Math.abs(ch(a, 8) - ch(b, 8)), Math.abs(ch(a, 0) - ch(b, 0)))

    private fun median(v: IntArray, n: Int): Int {
        java.util.Arrays.sort(v, 0, n)
        return v[n / 2]
    }

    /** Median colour of a thin ring just outside [seed]; null when that ring is not a flat colour. */
    fun paperColour(px: IntArray, w: Int, h: Int, seed: IntArray): Int? {
        val xs = ArrayList<Int>()
        val ring = 3
        val l = seed[0] - ring; val t = seed[1] - ring; val r = seed[2] + ring - 1; val b = seed[3] + ring - 1
        val stepX = maxOf(1, (r - l) / 60)
        val stepY = maxOf(1, (b - t) / 60)
        var x = l
        while (x <= r) { xs.add(x); x += stepX }
        val samples = ArrayList<Int>()
        for (sx in xs) {
            if (sx in 0 until w) {
                if (t in 0 until h) samples.add(px[t * w + sx])
                if (b in 0 until h) samples.add(px[b * w + sx])
            }
        }
        var y = t
        while (y <= b) {
            if (y in 0 until h) {
                if (l in 0 until w) samples.add(px[y * w + l])
                if (r in 0 until w) samples.add(px[y * w + r])
            }
            y += stepY
        }
        if (samples.size < 12) return null
        val n = samples.size
        val rr = IntArray(n) { ch(samples[it], 16) }
        val gg = IntArray(n) { ch(samples[it], 8) }
        val bb = IntArray(n) { ch(samples[it], 0) }
        val bg = (0xFF shl 24) or (median(rr, n) shl 16) or (median(gg, n) shl 8) or median(bb, n)
        var near = 0
        for (s in samples) if (dist(s, bg) <= TOL) near++
        return if (near * 100 >= n * 85) bg else null
    }

    /**
     * Rebuilds the bubble that surrounds the lettering box [seed] and returns its interior + the safe text
     * rectangle, or null when no closed flat-coloured bubble is found (artwork, leaking tail, page background).
     * [ink] are the recognised text boxes: they count as inside the bubble even though they are not paper coloured.
     */
    fun analyse(px: IntArray, w: Int, h: Int, seed: IntArray, ink: List<IntArray>): Result? {
        if (w < 16 || h < 16 || px.size < w * h) return null
        if (seed[2] - seed[0] < 4 || seed[3] - seed[1] < 4) return null
        val bg = paperColour(px, w, h, seed) ?: return null

        val ds = maxOf(1, (maxOf(w, h) + MAX_GRID - 1) / MAX_GRID)
        val sw = (w + ds - 1) / ds
        val sh = (h + ds - 1) / ds
        // A cell is paper only if EVERY pixel of its block is paper coloured: thin outlines must survive the shrink.
        val paper = BooleanArray(sw * sh)
        for (sy in 0 until sh) {
            val y0 = sy * ds
            val y1 = minOf(h, y0 + ds)
            for (sx in 0 until sw) {
                val x0 = sx * ds
                val x1 = minOf(w, x0 + ds)
                var ok = true
                var y = y0
                while (ok && y < y1) {
                    val row = y * w
                    var x = x0
                    while (x < x1) {
                        if (dist(px[row + x], bg) > TOL) { ok = false; break }
                        x++
                    }
                    y++
                }
                paper[sy * sw + sx] = ok
            }
        }

        val filled = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        var qh = 0
        var qt = 0
        fun push(cx: Int, cy: Int) {
            if (cx < 0 || cy < 0 || cx >= sw || cy >= sh) return
            val i = cy * sw + cx
            if (filled[i] || !paper[i]) return
            filled[i] = true
            queue[qt++] = i
        }
        // seeds: a ring just outside the lettering box
        val rl = (seed[0] - 3) / ds; val rt = (seed[1] - 3) / ds
        val rr = (seed[2] + 3) / ds; val rb = (seed[3] + 3) / ds
        for (cx in rl..rr) { push(cx, rt); push(cx, rb) }
        for (cy in rt..rb) { push(rl, cy); push(rr, cy) }
        if (qt == 0) return null
        while (qh < qt) {
            val i = queue[qh++]
            val cx = i % sw
            val cy = i / sw
            push(cx + 1, cy); push(cx - 1, cy); push(cx, cy + 1); push(cx, cy - 1)
        }

        // A bubble is closed by its outline. Paper that reaches the window edge on many cells is just the page.
        var edge = 0
        for (cx in 0 until sw) { if (filled[cx]) edge++; if (filled[(sh - 1) * sw + cx]) edge++ }
        for (cy in 0 until sh) { if (filled[cy * sw]) edge++; if (filled[cy * sw + sw - 1]) edge++ }
        if (edge * 100 > 2 * (sw + sh) * 15) return null

        // Lettering is not paper coloured: count the recognised boxes as inside, then close every pocket that is
        // completely surrounded by bubble (glyph islands, lettering the recogniser missed).
        for (r in ink) {
            val ccx = ((r[0] + r[2]) / 2) / ds
            val ccy = ((r[1] + r[3]) / 2) / ds
            val touchesSeed = r[0] < seed[2] && r[2] > seed[0] && r[1] < seed[3] && r[3] > seed[1]
            if (!touchesSeed && !(ccx in 0 until sw && ccy in 0 until sh && filled[ccy * sw + ccx])) continue
            val x0 = (r[0] / ds).coerceIn(0, sw)
            val x1 = ((r[2] + ds - 1) / ds).coerceIn(x0, sw)
            val y0 = (r[1] / ds).coerceIn(0, sh)
            val y1 = ((r[3] + ds - 1) / ds).coerceIn(y0, sh)
            for (y in y0 until y1) for (x in x0 until x1) filled[y * sw + x] = true
        }
        val outside = BooleanArray(sw * sh)
        qh = 0; qt = 0
        fun pushOut(cx: Int, cy: Int) {
            if (cx < 0 || cy < 0 || cx >= sw || cy >= sh) return
            val i = cy * sw + cx
            if (outside[i] || filled[i]) return
            outside[i] = true
            queue[qt++] = i
        }
        for (cx in 0 until sw) { pushOut(cx, 0); pushOut(cx, sh - 1) }
        for (cy in 0 until sh) { pushOut(0, cy); pushOut(sw - 1, cy) }
        while (qh < qt) {
            val i = queue[qh++]
            val cx = i % sw
            val cy = i / sw
            pushOut(cx + 1, cy); pushOut(cx - 1, cy); pushOut(cx, cy + 1); pushOut(cx, cy - 1)
        }
        var area = 0
        for (i in filled.indices) {
            if (!filled[i] && !outside[i]) filled[i] = true
            if (filled[i]) area++
        }
        if (area < 12) return null

        // Interior bounds, ignoring thin protrusions such as the bubble tail.
        val rows = IntArray(sh)
        val cols = IntArray(sw)
        for (i in filled.indices) if (filled[i]) { rows[i / sw]++; cols[i % sw]++ }
        val maxRow = rows.max()
        val maxCol = cols.max()
        if (maxRow == 0 || maxCol == 0) return null
        var t = 0; while (t < sh && rows[t] * 10 < maxRow * 3) t++
        var b = sh - 1; while (b > t && rows[b] * 10 < maxRow * 3) b--
        var l = 0; while (l < sw && cols[l] * 10 < maxCol * 3) l++
        var r = sw - 1; while (r > l && cols[r] * 10 < maxCol * 3) r--
        if (r - l < 3 || b - t < 3) return null

        val mask = Mask(sw, sh, ds, filled)
        val seedCells = intArrayOf(seed[0] / ds, seed[1] / ds, (seed[2] + ds - 1) / ds, (seed[3] + ds - 1) / ds)
        val best = largestFilledRect(filled, sw, sh, seedCells) ?: return null
        val interior = intArrayOf(l * ds, t * ds, minOf(w, (r + 1) * ds), minOf(h, (b + 1) * ds))
        var sl = best[0] * ds; var st = best[1] * ds
        var sr = minOf(w, best[2] * ds); var sb = minOf(h, best[3] * ds)
        // Keep a small distance from the outline (Manga Translator: 2.5% of the shorter side).
        val inset = (minOf(sr - sl, sb - st) * 0.025f).toInt().coerceAtLeast(1)
        sl += inset; st += inset; sr -= inset; sb -= inset
        if (sr - sl < 8 || sb - st < 8) return null
        return Result(interior, intArrayOf(sl, st, sr, sb), mask)
    }

    /**
     * Largest-area rectangle made only of filled cells (histogram + stack, one pass over the grid).
     * A rectangle that overlaps the lettering box ([near], in cells) is preferred, so a two-lobed or tailed bubble
     * does not send the text to the other lobe. Returns [left, top, right, bottom) in cells.
     */
    fun largestFilledRect(filled: BooleanArray, w: Int, h: Int, near: IntArray? = null): IntArray? {
        val heights = IntArray(w)
        val stack = IntArray(w + 1)
        var best: IntArray? = null
        var bestArea = 0
        var bestNear: IntArray? = null
        var bestNearArea = 0
        val nearArea = if (near == null) 0 else maxOf(1, (near[2] - near[0]) * (near[3] - near[1]))
        for (y in 0 until h) {
            for (x in 0 until w) heights[x] = if (filled[y * w + x]) heights[x] + 1 else 0
            var size = 0
            var x = 0
            while (x <= w) {
                val cur = if (x == w) 0 else heights[x]
                if (size == 0 || cur >= heights[stack[size - 1]]) {
                    stack[size++] = x
                    x++
                } else {
                    val top = stack[--size]
                    val rh = heights[top]
                    if (rh <= 0) continue
                    val left = if (size == 0) 0 else stack[size - 1] + 1
                    val rw = x - left
                    val a = rw * rh
                    val cand = intArrayOf(left, y - rh + 1, x, y + 1)
                    if (a > bestArea) { bestArea = a; best = cand }
                    if (near != null && a > bestNearArea) {
                        val ox = minOf(cand[2], near[2]) - maxOf(cand[0], near[0])
                        val oy = minOf(cand[3], near[3]) - maxOf(cand[1], near[1])
                        if (ox > 0 && oy > 0 && ox * oy * 100 >= nearArea * 40) { bestNearArea = a; bestNear = cand }
                    }
                }
            }
        }
        return bestNear ?: best
    }
}
