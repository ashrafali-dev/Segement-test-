package com.ashraf.novelstudio

import android.graphics.Bitmap

/**
 * Bitmap side of [BubbleFitCore]: cuts a window around the recognised lettering out of the page, rebuilds the
 * speech bubble that surrounds it and reports where a translation fits inside that bubble.
 * All rectangles are [left, top, right, bottom] in [bmp] pixels.
 */
internal object BubbleFit {
    class Fit(
        /** Bounding box of the bubble interior. */
        val interior: IntArray,
        /** Largest rectangle that lies completely inside the bubble (the area the translation may use). */
        val safe: IntArray,
        val mask: BubbleFitCore.Mask,
        /** Window origin inside the page bitmap. */
        val ox: Int,
        val oy: Int
    ) {
        /** Share (0..1) of the rectangle (page pixels) that lies inside the bubble. */
        fun coverage(l: Int, t: Int, r: Int, b: Int): Float = mask.coverage(l - ox, t - oy, r - ox, b - oy)

        /** The whole bubble interior as <= [maxBands] rectangles in page pixels (see [BubbleFitCore.Mask.bands]). */
        fun bands(maxBands: Int = 28): List<IntArray> =
            mask.bands(maxBands).map { intArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }

        /** Is page pixel (x, y) inside the bubble interior? */
        fun contains(x: Int, y: Int): Boolean {
            val cx = (x - ox) / mask.ds
            val cy = (y - oy) / mask.ds
            if (x - ox < 0 || y - oy < 0 || cx >= mask.sw || cy >= mask.sh) return false
            return mask.filled[cy * mask.sw + cx]
        }
    }

    /** Pixels of the window may not exceed this: keeps real-time reading cheap on tall webtoon strips. */
    private const val MAX_WINDOW_PIXELS = 1_500_000L

    /**
     * Do two recognised lines sit inside the SAME closed bubble? true / false, or null when no closed flat bubble
     * can be rebuilt around [a] (artwork, leaking tail ...): then the caller keeps its geometry decision.
     * This is what keeps "예." (upper bubble) and "소인 나가보겠습니다." (lower bubble, overlapping the first)
     * from being fused into one sentence: the straight strip between two overlapping bubbles often holds no
     * visible outline, but the paper of one bubble never reaches the lettering of the other.
     */
    fun sameBubble(bmp: Bitmap, a: IntArray, b: IntArray, all: List<IntArray>): Boolean? {
        val fit = try { analyse(bmp, a, all) } catch (_: Throwable) { null } ?: return null
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        if (w < 4 || h < 4) return null
        val l = b[0] + w / 4
        val r = b[2] - w / 4
        val t = b[1] + h / 4
        val bt = b[3] - h / 4
        return fit.coverage(l, t, r, bt) >= 0.5f
    }

    /**
     * [seed] = box of the recognised lettering of ONE bubble; [others] = every recognised box of the slice
     * (they count as bubble content, not as an outline). Returns null when no closed flat bubble is found
     * (lettering on artwork, a tail that leaks into the picture, a plain page) so the caller keeps its old box.
     */
    /**
     * Same result from a balloon the YOLO detector found: the mask comes from the model instead of the paper-colour
     * flood. Chooses the smallest balloon that contains the centre of [seed].
     */
    fun fromBalloons(balloons: List<YoloBubbleDetector.Balloon>, seed: IntArray, others: List<IntArray>): Fit? {
        val cx = (seed[0] + seed[2]) / 2
        val cy = (seed[1] + seed[3]) / 2
        val b = balloons.filter { it.contains(cx, cy) }.minByOrNull { it.area() } ?: return null
        val filled = b.filled.copyOf()
        // recognised lettering always counts as inside the balloon it sits in
        for (o in others) {
            val ocx = (o[0] + o[2]) / 2
            val ocy = (o[1] + o[3]) / 2
            if (!b.contains(ocx, ocy)) continue
            val x0 = ((o[0] - b.ox) / b.ds).coerceIn(0, b.sw)
            val x1 = ((o[2] - b.ox + b.ds - 1) / b.ds).coerceIn(x0, b.sw)
            val y0 = ((o[1] - b.oy) / b.ds).coerceIn(0, b.sh)
            val y1 = ((o[3] - b.oy + b.ds - 1) / b.ds).coerceIn(y0, b.sh)
            for (y in y0 until y1) for (x in x0 until x1) filled[y * b.sw + x] = true
        }
        val localSeed = intArrayOf(
            (seed[0] - b.ox).coerceIn(0, b.w), (seed[1] - b.oy).coerceIn(0, b.h),
            (seed[2] - b.ox).coerceIn(0, b.w), (seed[3] - b.oy).coerceIn(0, b.h)
        )
        val res = try { BubbleFitCore.completeMask(filled, b.sw, b.sh, b.ds, localSeed, b.w, b.h) } catch (_: Exception) { null } ?: return null
        return Fit(
            interior = intArrayOf(res.interior[0] + b.ox, res.interior[1] + b.oy, res.interior[2] + b.ox, res.interior[3] + b.oy),
            safe = intArrayOf(res.safe[0] + b.ox, res.safe[1] + b.oy, res.safe[2] + b.ox, res.safe[3] + b.oy),
            mask = res.mask, ox = b.ox, oy = b.oy
        )
    }

    fun analyse(bmp: Bitmap, seed: IntArray, others: List<IntArray>): Fit? {
        // A bubble much larger than its lettering does not fit the small first window (its paper reaches the window
        // edge and it looks like an open page): look again with a wider window before giving up.
        val first = windowFor(bmp, seed, 1f)
        val r1 = attempt(bmp, seed, others, first)
        if (r1 != null) return r1
        val second = windowFor(bmp, seed, 2.8f)
        if (second == null || (first != null && second.contentEquals(first))) return null
        return attempt(bmp, seed, others, second)
    }

    /** [x0, y0, x1, y1] of the analysis window around [seed], or null when it would be unusable. */
    private fun windowFor(bmp: Bitmap, seed: IntArray, scale: Float): IntArray? {
        val sw = seed[2] - seed[0]
        val sh = seed[3] - seed[1]
        if (sw < 4 || sh < 4) return null
        val mx = maxOf(40, (sw * 0.9f * scale).toInt())
        val my = maxOf(40, (sh * 1.2f * scale).toInt())
        var x0 = (seed[0] - mx).coerceAtLeast(0)
        var y0 = (seed[1] - my).coerceAtLeast(0)
        var x1 = (seed[2] + mx).coerceAtMost(bmp.width)
        var y1 = (seed[3] + my).coerceAtMost(bmp.height)
        // keep the pixel budget: shrink the margins evenly until the window fits
        var guard = 0
        while ((x1 - x0).toLong() * (y1 - y0) > MAX_WINDOW_PIXELS && guard++ < 8) {
            x0 = (x0 + (seed[0] - x0) / 4).coerceAtMost(seed[0])
            x1 = (x1 - (x1 - seed[2]) / 4).coerceAtLeast(seed[2])
            y0 = (y0 + (seed[1] - y0) / 4).coerceAtMost(seed[1])
            y1 = (y1 - (y1 - seed[3]) / 4).coerceAtLeast(seed[3])
        }
        if (x1 - x0 < 16 || y1 - y0 < 16 || (x1 - x0).toLong() * (y1 - y0) > MAX_WINDOW_PIXELS) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    private fun attempt(bmp: Bitmap, seed: IntArray, others: List<IntArray>, win: IntArray?): Fit? {
        if (win == null) return null
        val x0 = win[0]; val y0 = win[1]; val x1 = win[2]; val y1 = win[3]
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w * h)
        try {
            bmp.getPixels(px, 0, w, x0, y0, w, h)
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        val localSeed = intArrayOf(seed[0] - x0, seed[1] - y0, seed[2] - x0, seed[3] - y0)
        val ink = ArrayList<IntArray>(others.size)
        for (o in others) {
            if (o[2] <= x0 || o[0] >= x1 || o[3] <= y0 || o[1] >= y1) continue
            ink.add(intArrayOf(
                (o[0] - x0).coerceIn(0, w), (o[1] - y0).coerceIn(0, h),
                (o[2] - x0).coerceIn(0, w), (o[3] - y0).coerceIn(0, h)
            ))
        }
        val res = try { BubbleFitCore.analyse(px, w, h, localSeed, ink) } catch (_: Exception) { null } ?: return null
        val mask = res.mask
        return Fit(
            interior = intArrayOf(res.interior[0] + x0, res.interior[1] + y0, res.interior[2] + x0, res.interior[3] + y0),
            safe = intArrayOf(res.safe[0] + x0, res.safe[1] + y0, res.safe[2] + x0, res.safe[3] + y0),
            mask = mask, ox = x0, oy = y0
        )
    }
}
