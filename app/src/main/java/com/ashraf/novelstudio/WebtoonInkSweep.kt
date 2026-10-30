package com.ashraf.novelstudio

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * WEBTOON ONLY (never used for Japanese manga paths).
 *
 * Problem this fixes: the realtime path has no bubble detector, so the cover (the paper-coloured mask that hides the
 * old lettering) is only the OCR box plus a small pad. Stylised / italic / shouting lettering is often WIDER than
 * what the recogniser boxed, so the left/right ends of the old words ("MAN…", "…ING", "YC", "E?") stay visible on both
 * sides of the Bengali text.
 *
 * What it does: looks at the pixels around the cover, finds every LETTER-SIZED ink blob (connected component) that
 * chains outwards from the cover (letter -> letter -> word, gap <= ~1 font) and returns small rectangles around them.
 * The caller adds those rectangles to the bubble's cover shapes (`cv`), so they are painted in the bubble's paper
 * colour just like the main cover.
 *
 * Safety rules (so it never eats the bubble outline or artwork):
 *  - a blob taller than ~2.4 fonts or wider than ~7 fonts is NOT lettering (bubble outline, panel border, art);
 *  - blobs touching the analysis window edge are rejected (they continue outside, so they are an outline);
 *  - blobs inside another bubble's OCR box are rejected;
 *  - if the surroundings are not mostly paper-coloured (artwork / photo), nothing is returned at all.
 */
internal object WebtoonInkSweep {
    private const val MAX_WINDOW_PIXELS = 480_000L
    private const val DS = 2                    // work on a half-resolution grid: 4x cheaper, letters are still > 3 cells
    private const val MAX_RECTS = 96

    /**
     * @param cover   [l, t, r, b] of the existing cover, bitmap pixels
     * @param font    one line height in bitmap pixels
     * @param bg      paper colour of the bubble (ARGB)
     * @param keepOut OCR boxes of OTHER bubbles ([l, t, r, b]); nothing inside them is touched
     * @return rectangles [l, t, r, b] (bitmap pixels) to paint in [bg]; empty when nothing safe was found
     */
    fun sweep(bmp: Bitmap, cover: IntArray, font: Float, bg: Int, keepOut: List<IntArray>): List<IntArray> {
        try {
            val f = font.coerceIn(8f, 120f)
            val cw = cover[2] - cover[0]
            val ch = cover[3] - cover[1]
            if (cw < 6 || ch < 6) return emptyList()

            var reachX = max((f * 4.5f).toInt(), (cw * 0.55f).toInt()).coerceAtMost(520)
            var reachY = max((f * 1.6f).toInt(), 28).coerceAtMost(240)
            var wl = (cover[0] - reachX).coerceAtLeast(0)
            var wt = (cover[1] - reachY).coerceAtLeast(0)
            var wr = (cover[2] + reachX).coerceAtMost(bmp.width)
            var wb = (cover[3] + reachY).coerceAtMost(bmp.height)
            var guard = 0
            while ((wr - wl).toLong() * (wb - wt) > MAX_WINDOW_PIXELS && guard++ < 10) {
                reachX = (reachX * 0.8f).toInt(); reachY = (reachY * 0.85f).toInt()
                wl = (cover[0] - reachX).coerceAtLeast(0); wt = (cover[1] - reachY).coerceAtLeast(0)
                wr = (cover[2] + reachX).coerceAtMost(bmp.width); wb = (cover[3] + reachY).coerceAtMost(bmp.height)
            }
            val ww = wr - wl
            val wh = wb - wt
            if (ww < 24 || wh < 24 || ww.toLong() * wh > MAX_WINDOW_PIXELS) return emptyList()

            val px = IntArray(ww * wh)
            bmp.getPixels(px, 0, ww, wl, wt, ww, wh)

            // ---- ink map on a half-resolution grid ------------------------------------------------
            val gw = (ww + DS - 1) / DS
            val gh = (wh + DS - 1) / DS
            val ink = BooleanArray(gw * gh)
            var paper = 0L
            var total = 0L
            for (gy in 0 until gh) {
                val y = gy * DS
                for (gx in 0 until gw) {
                    val x = gx * DS
                    var any = false
                    var near = true
                    var yy = y
                    while (yy < min(y + DS, wh)) {
                        var xx = x
                        while (xx < min(x + DS, ww)) {
                            val d = dist(px[yy * ww + xx], bg)
                            if (d > 120) any = true
                            if (d > 60) near = false
                            xx++
                        }
                        yy++
                    }
                    ink[gy * gw + gx] = any
                    total++
                    if (near) paper++
                }
            }
            // Artwork / photo around the lettering: not a flat bubble. Do nothing rather than paint over art.
            if (paper * 100 < total * 40) return emptyList()

            // ---- connected components (8-neighbour) ---------------------------------------------------
            val label = IntArray(gw * gh) { -1 }
            class Blob(val l: Int, val t: Int, val r: Int, val b: Int, val n: Int, val edge: Boolean)
            val blobs = ArrayList<Blob>()
            val stack = IntArray(gw * gh)
            for (start in ink.indices) {
                if (!ink[start] || label[start] >= 0) continue
                var sp = 0
                stack[sp++] = start
                label[start] = blobs.size
                var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
                var n = 0
                var edge = false
                while (sp > 0) {
                    val cur = stack[--sp]
                    val cx = cur % gw
                    val cy = cur / gw
                    n++
                    if (cx < l) l = cx; if (cx > r) r = cx; if (cy < t) t = cy; if (cy > b) b = cy
                    if (cx == 0 || cy == 0 || cx == gw - 1 || cy == gh - 1) edge = true
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx; val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                        val ni = ny * gw + nx
                        if (!ink[ni] || label[ni] >= 0) continue
                        label[ni] = blobs.size
                        stack[sp++] = ni
                    }
                }
                blobs.add(Blob(l * DS, t * DS, (r + 1) * DS, (b + 1) * DS, n, edge))
            }
            if (blobs.isEmpty()) return emptyList()

            // ---- keep only letter-sized blobs that are not other bubbles' text ------------------------
            val maxH = f * 2.4f
            val maxW = f * 7f
            val cand = ArrayList<IntArray>()           // window coordinates [l, t, r, b]
            for (bl in blobs) {
                if (bl.edge || bl.n < 2) continue
                val bw = bl.r - bl.l
                val bh = bl.b - bl.t
                if (bh > maxH || bw > maxW) continue
                val gl = bl.l + wl; val gt = bl.t + wt; val gr = bl.r + wl; val gb = bl.b + wt
                var foreign = false
                for (k in keepOut) {
                    val ix = min(gr, k[2]) - max(gl, k[0])
                    val iy = min(gb, k[3]) - max(gt, k[1])
                    if (ix > 0 && iy > 0 && ix.toLong() * iy * 100 >= bw.toLong() * bh * 25) { foreign = true; break }
                }
                if (!foreign) cand.add(intArrayOf(bl.l, bl.t, bl.r, bl.b))
            }
            if (cand.isEmpty()) return emptyList()

            // ---- grow outwards from the cover: letter -> letter -> word -----------------------------------
            val covered = ArrayList<IntArray>()
            covered.add(intArrayOf(cover[0] - wl, cover[1] - wt, cover[2] - wl, cover[3] - wt))
            val gapX = (f * 1.15f).toInt()
            val gapY = (f * 0.55f).toInt()
            val taken = BooleanArray(cand.size)
            var changed = true
            var pass = 0
            while (changed && pass++ < 10) {
                changed = false
                for (i in cand.indices) {
                    if (taken[i]) continue
                    val c = cand[i]
                    var near = false
                    for (cv in covered) {
                        val ix = min(c[2] + gapX, cv[2]) - max(c[0] - gapX, cv[0])
                        val iy = min(c[3] + gapY, cv[3]) - max(c[1] - gapY, cv[1])
                        if (ix > 0 && iy > 0) { near = true; break }
                    }
                    if (near) { taken[i] = true; covered.add(c); changed = true }
                }
            }

            // ---- merge accepted blobs into few row-shaped rectangles ---------------------------------------
            val pad = max(2, (f * 0.10f).toInt())
            val rects = ArrayList<IntArray>()
            for (i in cand.indices) if (taken[i]) {
                val c = cand[i]
                rects.add(intArrayOf(c[0] + wl - pad, c[1] + wt - pad, c[2] + wl + pad, c[3] + wt + pad))
            }
            if (rects.isEmpty()) return emptyList()
            val mergeGap = (f * 1.0f).toInt()
            var merged = true
            while (merged && rects.size > 1) {
                merged = false
                loop@ for (i in 0 until rects.size) for (j in i + 1 until rects.size) {
                    val a = rects[i]; val b = rects[j]
                    val vo = min(a[3], b[3]) - max(a[1], b[1])
                    val minH = min(a[3] - a[1], b[3] - b[1])
                    val hg = max(a[0], b[0]) - min(a[2], b[2])
                    if (vo * 100 >= minH * 40 && hg <= mergeGap) {
                        rects[i] = intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
                        rects.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
            // Never let a merged rectangle swallow another bubble's text.
            val out = ArrayList<IntArray>()
            for (r in rects) {
                val rr = intArrayOf(r[0].coerceAtLeast(0), r[1].coerceAtLeast(0), r[2].coerceAtMost(bmp.width), r[3].coerceAtMost(bmp.height))
                if (rr[2] - rr[0] < 2 || rr[3] - rr[1] < 2) continue
                var hitsForeign = false
                for (k in keepOut) {
                    val ix = min(rr[2], k[2]) - max(rr[0], k[0])
                    val iy = min(rr[3], k[3]) - max(rr[1], k[1])
                    if (ix > 0 && iy > 0) { hitsForeign = true; break }
                }
                if (!hitsForeign) out.add(rr)
            }
            return if (out.size > MAX_RECTS) out.sortedByDescending { (it[2] - it[0]) * (it[3] - it[1]) }.take(MAX_RECTS) else out
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    /** Sum of channel differences (0..765). */
    private fun dist(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
            abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
            abs((a and 255) - (b and 255))
}
