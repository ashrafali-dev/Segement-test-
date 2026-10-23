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
        private val maskCoverage: (Int, Int, Int, Int) -> Float
    ) {
        /** Share (0..1) of the rectangle that lies inside the bubble. */
        fun coverage(l: Int, t: Int, r: Int, b: Int): Float = maskCoverage(l, t, r, b)
    }

    /** Pixels of the window may not exceed this: keeps real-time reading cheap on tall webtoon strips. */
    private const val MAX_WINDOW_PIXELS = 1_500_000L

    /**
     * [seed] = box of the recognised lettering of ONE bubble; [others] = every recognised box of the slice
     * (they count as bubble content, not as an outline). Returns null when no closed flat bubble is found
     * (lettering on artwork, a tail that leaks into the picture, a plain page) so the caller keeps its old box.
     */
    fun analyse(bmp: Bitmap, seed: IntArray, others: List<IntArray>): Fit? {
        val bw = bmp.width
        val bh = bmp.height
        val sw = seed[2] - seed[0]
        val sh = seed[3] - seed[1]
        if (sw < 4 || sh < 4) return null
        val mx = maxOf(40, (sw * 0.9f).toInt())
        val my = maxOf(40, (sh * 1.2f).toInt())
        val x0 = (seed[0] - mx).coerceAtLeast(0)
        val y0 = (seed[1] - my).coerceAtLeast(0)
        val x1 = (seed[2] + mx).coerceAtMost(bw)
        val y1 = (seed[3] + my).coerceAtMost(bh)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 16 || h < 16 || w.toLong() * h > MAX_WINDOW_PIXELS) return null
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
            maskCoverage = { l, t, r, b -> mask.coverage(l - x0, t - y0, r - x0, b - y0) }
        )
    }
}
