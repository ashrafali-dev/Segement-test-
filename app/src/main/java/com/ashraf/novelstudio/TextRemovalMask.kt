package com.ashraf.novelstudio

import kotlin.math.abs

/**
 * Android-free pixel algorithms for text removal: deciding WHICH pixels are lettering and reconstructing WHAT was
 * underneath them.
 *
 * Kept separate from [WebModeTextRemover] because that file needs `android.graphics.Bitmap`; every decision here
 * is a pure function of an ARGB array, so it is covered by JVM unit tests on the device as well as in CI.
 */
internal object TextRemovalMask {

    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun green(c: Int) = (c ushr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    internal fun distance(a: Int, b: Int): Float =
        (abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))) / 3f

    /**
     * Pixels the OLD lettering occupies inside one OCR region.
     *
     *  - the model's instance mask is the balloon SILHOUETTE, not a glyph mask, so filling the whole masked area
     *    repaints the balloon's own shading and anything inside it;
     *  - the region's own pixels separate lettering from paper: [paper] is the colour sampled from the ring just
     *    outside the box, glyphs are the pixels that clearly disagree with it.
     *
     * @param insideBalloon restricts the work to the model silhouette (defaults to the whole box).
     * @return one byte per region pixel (1 = lettering), or null when the box is not lettering at all.
     */
    /**
     * The paper tone of ONE row of [x0],[x1): the median of that row, which on a shaded or gradient balloon is
     * the colour the glyphs in that row were drawn over. Falls back to [paper] when the row is almost all ink.
     */
    private fun rowPaperTone(region: IntArray, rw: Int, ry: Int, x0: Int, x1: Int, paper: Int): Int {
        val rs = IntArray(x1 - x0); val gs = IntArray(x1 - x0); val bs = IntArray(x1 - x0)
        var n = 0
        for (x in x0 until x1) {
            val c = region[ry * rw + x]
            rs[n] = red(c); gs[n] = green(c); bs[n] = blue(c); n++
        }
        if (n < 4) return paper
        java.util.Arrays.sort(rs, 0, n)
        java.util.Arrays.sort(gs, 0, n)
        java.util.Arrays.sort(bs, 0, n)
        val m = n / 2
        return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    fun inkMask(
        region: IntArray, rw: Int, rh: Int,
        ix0: Int, iy0: Int, ix1: Int, iy1: Int,
        paper: Int,
        insideBalloon: (x: Int, y: Int) -> Boolean = { _, _ -> true }
    ): ByteArray? {
        val x0 = ix0.coerceIn(0, rw)
        val x1 = ix1.coerceIn(x0, rw)
        val y0 = iy0.coerceIn(0, rh)
        val y1 = iy1.coerceIn(y0, rh)
        val spanX = x1 - x0
        val spanY = y1 - y0
        if (spanX < 4 || spanY < 4) return null
        val out = ByteArray(rw * rh)
        // The threshold has to scale with THIS box's real ink/paper contrast. A fixed "26 away from paper" made a
        // balloon with a soft gradient count its own paper as lettering wherever the gradient drifted, so clean
        // shading was erased. A box with no strong contrast anywhere is not a line of lettering at all.
        var maxDist = 0f
        for (ry in y0 until y1) for (rx in x0 until x1) {
            if (!insideBalloon(rx, ry)) continue
            val dd = distance(region[ry * rw + rx], paper)
            if (dd > maxDist) maxDist = dd
        }
        if (maxDist < 60f) return null
        // 18 % of the strongest contrast, floored at the anti-aliasing/JPEG-ringing band. No upper cap: a balloon
        // whose ink is jet black on pale paper must accept the full range or the glyph cores are left behind.
        val tol = (maxDist * 0.18f).coerceAtLeast(26f).coerceAtMost(maxDist * 0.6f)
        var ink = 0
        // Paper is judged per ROW, not against one sample. A shaded balloon drifts vertically, so a glyph at the
        // bottom of the box is far from a paper sample taken at the top even though it is plainly lettering. The
        // row's own median is the paper that actually surrounds that row's glyphs, so a gradient cannot masquerade
        // as ink at the ends of the box and real ink never drifts out of tolerance.
        val rowPaper = IntArray(rh)
        for (ry in y0 until y1) rowPaper[ry] = rowPaperTone(region, rw, ry, x0, x1, paper)
        for (ry in y0 until y1) for (rx in x0 until x1) {
            if (!insideBalloon(rx, ry)) continue
            if (distance(region[ry * rw + rx], rowPaper[ry]) < tol) continue
            out[ry * rw + rx] = 1
            ink++
        }
        val area = spanX.toLong() * spanY
        // Nothing but paper: a blank box. Almost all ink: artwork, not a line of lettering.
        if (ink < 4 || ink > area * 0.85f) return null
        // Grow one pixel so each glyph's own anti-aliased rim is restored too; never past the box edge.
        for (ry in y0 until y1) for (rx in x0 until x1) {
            if (out[ry * rw + rx].toInt() == 0) continue
            for (dy in -1..1) {
                val ny = ry + dy
                if (ny < y0 || ny >= y1) continue
                for (dx in -1..1) {
                    val nx = rx + dx
                    if (nx < x0 || nx >= x1) continue
                    val at = ny * rw + nx
                    if (out[at].toInt() != 0 || !insideBalloon(nx, ny)) continue
                    val d = distance(region[at], paper)
                    // Only a faint blend of the ink with the paper counts as its edge.
                    if (d >= tol * 0.5f && d <= maxDist) out[at] = 1
                }
            }
        }
        return out
    }

    /**
     * Reconstruct every masked pixel from the background that actually surrounds it.
     *
     * Only UNMASKED original pixels are used: every masked pixel takes the nearest clean original along its row
     * and along its column, weighted by how much each axis varies, so a shaded or gradient balloon continues its
     * ramp. Reading only originals (never values this pass just wrote) is what stops a block of glyphs from
     * collapsing into one flat colour.
     */
    fun restoreFromSurroundings(
        mask: ByteArray, region: IntArray, rw: Int, rh: Int,
        maxRun: Int = 48
    ): IntArray {
        val paper = dominantOutside(mask, region, rw, rh)
        val leftOf = IntArray(rw * rh) { -1 }
        val rightOf = IntArray(rw * rh) { -1 }
        val upOf = IntArray(rw * rh) { -1 }
        val downOf = IntArray(rw * rh) { -1 }
        for (y in 0 until rh) {
            var last = -1
            for (x in 0 until rw) {
                val at = y * rw + x
                if (mask[at].toInt() == 0) last = x else if (last >= 0) leftOf[at] = last
            }
            var next = -1
            for (x in rw - 1 downTo 0) {
                val at = y * rw + x
                if (mask[at].toInt() == 0) next = x else if (next >= 0) rightOf[at] = next
            }
        }
        for (x in 0 until rw) {
            var last = -1
            for (y in 0 until rh) {
                val at = y * rw + x
                if (mask[at].toInt() == 0) last = y else if (last >= 0) upOf[at] = last
            }
            var next = -1
            for (y in rh - 1 downTo 0) {
                val at = y * rw + x
                if (mask[at].toInt() == 0) next = y else if (next >= 0) downOf[at] = next
            }
        }
        val out = region.copyOf()
        for (y in 0 until rh) for (x in 0 until rw) {
            val at = y * rw + x
            if (mask[at].toInt() == 0) continue
            val li = leftOf[at]
            val ri = rightOf[at]
            val ui = upOf[at]
            val di = downOf[at]
            val hasH = li >= 0 && ri >= 0 && (x - li) + (ri - x) <= maxRun
            val hasV = ui >= 0 && di >= 0 && (y - ui) + (di - y) <= maxRun
            if (hasH && hasV) {
                val l = region[y * rw + li]
                val r = region[y * rw + ri]
                val u = region[ui * rw + x]
                val d = region[di * rw + x]
                val h = mix(l, r, (x - li).toFloat() / (ri - li))
                val v = mix(u, d, (y - ui).toFloat() / (di - ui))
                val hVar = distance(l, r)
                val vVar = distance(u, d)
                out[at] = if (hVar + vVar <= 0.001f) h else mix(h, v, hVar / (hVar + vVar))
            } else if (hasH) {
                out[at] = mix(region[y * rw + li], region[y * rw + ri], (x - li).toFloat() / (ri - li))
            } else if (hasV) {
                out[at] = mix(region[ui * rw + x], region[di * rw + x], (y - ui).toFloat() / (di - ui))
            } else {
                // No clean neighbour on either axis: the paper is the only background known here.
                out[at] = paper
            }
        }
        return out
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int, e: Int) = Math.round(s + (e - s) * t).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(red(a), red(b)) shl 16) or (ch(green(a), green(b)) shl 8) or ch(blue(a), blue(b))
    }

    /** The colour the mask was drawn over: the most common colour among the pixels the mask does not cover. */
    fun dominantOutside(mask: ByteArray, region: IntArray, rw: Int, rh: Int): Int {
        fun q(c: Int) = (0xFF shl 24) or
            ((((c shr 16) and 0xFF) and 0xFC) shl 16) or
            ((((c shr 8) and 0xFF) and 0xFC) shl 8) or
            ((c and 0xFF) and 0xFC)
        val counts = HashMap<Int, Int>(64)
        var best = 0xFF000000.toInt()
        var bestN = 0
        for (i in region.indices) {
            if (i < mask.size && mask[i].toInt() != 0) continue
            val k = q(region[i])
            val n = (counts[k] ?: 0) + 1
            counts[k] = n
            if (n > bestN) { bestN = n; best = k }
        }
        if (bestN == 0) return 0xFF000000.toInt()
        // Median of the pixels inside the winning bucket, so the answer is not pushed onto the 4-level grid.
        val rs = IntArray(bestN); val gs = IntArray(bestN); val bs = IntArray(bestN)
        var n = 0
        for (i in region.indices) {
            if (i < mask.size && mask[i].toInt() != 0) continue
            val c = region[i]
            if (q(c) != best || n >= bestN) continue
            rs[n] = red(c); gs[n] = green(c); bs[n] = blue(c); n++
        }
        if (n == 0) return best
        java.util.Arrays.sort(rs, 0, n)
        java.util.Arrays.sort(gs, 0, n)
        java.util.Arrays.sort(bs, 0, n)
        val m = n / 2
        return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    /** True when two colours are the same background (per-channel tolerance). */
    fun near(a: Int, b: Int, tol: Int): Boolean =
        abs(red(a) - red(b)) <= tol && abs(green(a) - green(b)) <= tol && abs(blue(a) - blue(b)) <= tol

    /**
     * Bands for the balloon interior as it really is: inside the model silhouette AND inside each row of the
     * picture. The writer paints these with one colour, so a curved balloon must NOT get its bounding box.
     *
     * Uses [pixels] (the box's own pixels, row-major, [width] x [height]) only to find the balloon's paper colour.
     * When that paper is not flat - artwork inside the balloon - no bands are returned.
     */
    fun interiorBands(
        det: BubbleDetectorCore.Det,
        pixels: IntArray,
        width: Int, height: Int,
        maxBands: Int = 24
    ): List<IntArray> {
        val mask = det.mask ?: return emptyList()
        val mw = mask.width
        val mh = mask.height
        if (mw < 8 || mh < 8 || mask.pixels.size < mw * mh || det.w < 16 || det.h < 16) return emptyList()
        val bx0 = det.l.coerceIn(0, width - 1)
        val by0 = det.t.coerceIn(0, height - 1)
        val bx1 = det.r.coerceIn(bx0 + 1, width)
        val by1 = det.b.coerceIn(by0 + 1, height)
        fun inside(x: Int, y: Int) = det.contains(x + 0.5f, y + 0.5f)

        val step = Math.max(1, Math.min(bx1 - bx0, by1 - by0) / 64)
        val counts = HashMap<Int, Int>(64)
        var sampled = 0
        var sy = by0
        while (sy < by1) {
            var sx = bx0
            while (sx < bx1) {
                if (inside(sx, sy)) {
                    val c = pixels[sy * width + sx]
                    val q = (0xFF shl 24) or
                        ((((c shr 16) and 0xFF) and 0xFC) shl 16) or
                        ((((c shr 8) and 0xFF) and 0xFC) shl 8) or
                        ((c and 0xFF) and 0xFC)
                    counts[q] = (counts[q] ?: 0) + 1
                    sampled++
                }
                sx += step
            }
            sy += step
        }
        if (sampled < 24) return emptyList()
        val paperEntry = counts.entries.maxByOrNull { it.value } ?: return emptyList()
        if (paperEntry.value * 100 < sampled * 70) return emptyList()
        val paper = paperEntry.key

        val bands = ArrayList<IntArray>()
        val rowStep = Math.max(2, (by1 - by0) / 28)
        var gy = by0
        while (gy < by1 && bands.size < maxBands) {
            var bestL = -1; var bestR = -1; var bestLen = 0
            var x = bx0
            var runStart = -1
            while (x <= bx1) {
                val ok = x < bx1 && inside(x, gy) && near(pixels[gy * width + x], paper, 34)
                if (ok && runStart < 0) runStart = x
                if (!ok && runStart >= 0) {
                    if (x - runStart > bestLen) { bestLen = x - runStart; bestL = runStart; bestR = x }
                    runStart = -1
                }
                x++
            }
            if (bestLen >= 8) {
                // The band may only extend downwards while EVERY row keeps those pixels inside the balloon and on
                // its paper. A curved or cloud-shaped balloon narrows quickly, so assuming the whole rowStep is
                // covered would put the band's bottom corners outside the silhouette - the box-patch artefact.
                var bottom = gy
                while (bottom + 1 < by1 && bottom + 1 < gy + rowStep) {
                    val ny = bottom + 1
                    var ok = true
                    for (x in bestL until bestR) {
                        if (!inside(x, ny) || !near(pixels[ny * width + x], paper, 34)) { ok = false; break }
                    }
                    if (!ok) break
                    bottom = ny
                }
                val inset = Math.max(1, bestLen / 40)
                if (bottom > gy) bands.add(intArrayOf(bestL + inset, gy, bestR - inset, bottom))
            }
            gy += rowStep
        }
        if (bands.isEmpty()) return emptyList()
        val merged = ArrayList<IntArray>()
        for (b in bands) {
            val last = merged.lastOrNull()
            if (last != null && b[1] == last[3] + 1 &&
                abs(b[0] - last[0]) <= 2 && abs(b[2] - last[2]) <= 2) {
                last[0] = minOf(last[0], b[0])
                last[2] = maxOf(last[2], b[2])
                last[3] = b[3]
            } else merged.add(b.copyOf())
        }
        return merged
    }
}
