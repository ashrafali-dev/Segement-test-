package com.ashraf.novelstudio

/**
 * Pure-Kotlin decoder (no Android types, JVM-testable) for the YOLO11n-seg speech-bubble model
 * mednasserallah/manga109-segmentation-bubble-onnx (manga109_segmentation_bubble_1024.onnx).
 *
 * The ONNX file does NOT return finished masks. It returns two raw tensors:
 *   output0 (1, 37, 21504): per anchor 4 box (cx, cy, w, h) + 1 "balloon" score + 32 mask coefficients
 *   output1 (1, 32, 256, 256): 32 mask prototypes for the whole letter-boxed image
 * A balloon mask is sigmoid(coefficients @ prototypes), cropped to that balloon's own box. NMS is not part of the
 * graph. Input: 1x3x1024x1024 float RGB 0..1, the page letter-boxed (aspect kept) on a (114,114,114) canvas.
 */
internal object BubbleSegCore {
    const val SIZE = 1024
    const val PROTO = 256
    const val NC = 32
    const val ANCHORS = 21504
    const val SCORE_MIN = 0.35f
    const val NMS_IOU = 0.5f

    /** Letter-box geometry: page pixels -> model pixels is x * scale + padX. */
    class Letterbox(val scale: Float, val padX: Float, val padY: Float, val newW: Int, val newH: Int)

    fun letterbox(w: Int, h: Int): Letterbox {
        val s = Math.min(SIZE.toFloat() / w, SIZE.toFloat() / h)
        val nw = Math.max(1, Math.round(w * s))
        val nh = Math.max(1, Math.round(h * s))
        return Letterbox(s, (SIZE - nw) / 2f, (SIZE - nh) / 2f, nw, nh)
    }

    /** One balloon in page pixels. [mask] covers the box [l,t,r,b] row-major (w*h bytes, 1 = inside the balloon). */
    class Seg(val score: Float, val l: Int, val t: Int, val r: Int, val b: Int, val mask: ByteArray) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        fun inside(x: Int, y: Int): Boolean {
            if (x < l || x >= r || y < t || y >= b) return false
            return mask[(y - t) * (r - l) + (x - l)].toInt() != 0
        }
    }

    private class Cand(val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val idx: Int)

    private fun iou(a: Cand, b: Cand): Float {
        val iw = Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1)
        val ih = Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val uni = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    /**
     * [out0] is the flat (37, [anchors]) tensor (channel-major, as exported), [protos] the flat (32, 256, 256) tensor.
     * [pageW]/[pageH] is the size of the bitmap that was letter-boxed with [lb]. [originX]/[originY] shift the result
     * when the bitmap is a window of a bigger page.
     */
    fun decode(
        out0: java.nio.FloatBuffer, protos: java.nio.FloatBuffer, anchors: Int, lb: Letterbox, pageW: Int, pageH: Int,
        originX: Int = 0, originY: Int = 0, scoreMin: Float = SCORE_MIN, maxOut: Int = 40
    ): List<Seg> {
        if (out0.limit() < 37 * anchors || protos.limit() < NC * PROTO * PROTO) return emptyList()
        val cands = ArrayList<Cand>()
        for (i in 0 until anchors) {
            val s = out0.get(4 * anchors + i)
            if (!s.isFinite() || s < scoreMin) continue
            val cx = out0.get(i); val cy = out0.get(anchors + i); val w = out0.get(2 * anchors + i); val h = out0.get(3 * anchors + i)
            if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w < 4f || h < 4f) continue
            cands.add(Cand(s, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, i))
        }
        cands.sortByDescending { it.score }
        val kept = ArrayList<Cand>()
        for (c in cands) {
            var dup = false
            for (k in kept) if (iou(c, k) > NMS_IOU) { dup = true; break }
            if (!dup) kept.add(c)
            if (kept.size >= maxOut) break
        }
        val res = ArrayList<Seg>()
        val coef = FloatArray(NC)
        val plane = PROTO * PROTO
        val ratio = PROTO.toFloat() / SIZE
        for (c in kept) {
            // page box
            val l = Math.floor(((c.x1 - lb.padX) / lb.scale).toDouble()).toInt().coerceIn(0, pageW)
            val t = Math.floor(((c.y1 - lb.padY) / lb.scale).toDouble()).toInt().coerceIn(0, pageH)
            val r = Math.ceil(((c.x2 - lb.padX) / lb.scale).toDouble()).toInt().coerceIn(0, pageW)
            val b = Math.ceil(((c.y2 - lb.padY) / lb.scale).toDouble()).toInt().coerceIn(0, pageH)
            val bw = r - l; val bh = b - t
            if (bw < 6 || bh < 6) continue
            for (k in 0 until NC) coef[k] = out0.get((5 + k) * anchors + c.idx)
            val mask = ByteArray(bw * bh)
            for (yy in 0 until bh) {
                // page pixel -> model pixel -> prototype pixel (nearest)
                val my = ((t + yy + 0.5f) * lb.scale + lb.padY) * ratio
                val py = my.toInt().coerceIn(0, PROTO - 1)
                // The crop to the balloon's own box is what keeps the global prototypes from lighting up the page.
                for (xx in 0 until bw) {
                    val mx = ((l + xx + 0.5f) * lb.scale + lb.padX) * ratio
                    val px = mx.toInt().coerceIn(0, PROTO - 1)
                    var sum = 0f
                    val o = py * PROTO + px
                    for (k in 0 until NC) sum += coef[k] * protos.get(k * plane + o)
                    if (sum > 0f) mask[yy * bw + xx] = 1   // sigmoid(sum) > 0.5  <=>  sum > 0
                }
            }
            res.add(Seg(c.score, l + originX, t + originY, r + originX, b + originY, mask))
        }
        return res
    }

    /**
     * Fills a CHW float tensor (R,G,B planes, 0..1) with the page letter-boxed into SIZE x SIZE. [argb] holds the page
     * ALREADY scaled to lb.newW x lb.newH (so no full-size pixel array is ever needed); the rest is grey 114.
     */
    fun fillChw(argb: IntArray, lb: Letterbox, out: java.nio.FloatBuffer) {
        val plane = SIZE * SIZE
        require(out.capacity() >= plane * 3)
        val grey = 114f / 255f
        for (i in 0 until plane * 3) out.put(i, grey)
        val x0 = Math.round(lb.padX); val y0 = Math.round(lb.padY)
        for (y in 0 until lb.newH) {
            val row = y * lb.newW
            val dst = (y0 + y) * SIZE + x0
            for (x in 0 until lb.newW) {
                val p = argb[row + x]
                val o = dst + x
                out.put(o, ((p ushr 16) and 0xff) / 255f)
                out.put(plane + o, ((p ushr 8) and 0xff) / 255f)
                out.put(2 * plane + o, (p and 0xff) / 255f)
            }
        }
    }

    /** Smallest balloon mask that contains the point, or null. */
    fun balloonAt(segs: List<Seg>?, x: Int, y: Int): Seg? {
        if (segs == null) return null
        var best: Seg? = null
        for (s in segs) {
            if (!s.inside(x, y)) continue
            if (best == null || s.w.toLong() * s.h < best.w.toLong() * best.h) best = s
        }
        return best
    }

    /**
     * The balloon interior as rectangles [l,t,r,b] in page pixels (horizontal runs of [step] rows, equal runs merged).
     * Painted in the bubble's paper colour they follow the real balloon outline, so the old lettering is hidden
     * everywhere inside the balloon without touching the artwork around it.
     */
    fun bands(s: Seg, step: Int = 4, inset: Int = 1): List<IntArray> {
        val out = ArrayList<IntArray>()
        var y = 0
        var cur: IntArray? = null
        while (y < s.h) {
            val yy = Math.min(s.h - 1, y + step / 2)
            var first = -1; var last = -1
            val row = yy * s.w
            for (x in 0 until s.w) if (s.mask[row + x].toInt() != 0) { if (first < 0) first = x; last = x }
            if (first >= 0 && last - first >= 2 * inset + 2) {
                val l = s.l + first + inset; val r = s.l + last + 1 - inset
                val t = s.t + y; val b = s.t + Math.min(s.h, y + step)
                val c = cur
                if (c != null && Math.abs(c[0] - l) <= 2 && Math.abs(c[2] - r) <= 2 && c[3] == t) c[3] = b
                else { val n = intArrayOf(l, t, r, b); out.add(n); cur = n }
            } else cur = null
            y += step
        }
        return out
    }

    /**
     * The largest rectangle INSIDE the balloon mask that contains [seed] (page pixels [l,t,r,b]): grown side by side
     * (step 3 px) while the new strip stays inside the outline (>= 96 % of its pixels). This is the area the
     * translation may use, so it stays centred in the real balloon and never touches the outline.
     */
    fun fitRect(s: Seg, seed: IntArray, margin: Int = 4): IntArray? {
        var l = Math.max(seed[0], s.l); var t = Math.max(seed[1], s.t)
        var r = Math.min(seed[2], s.r); var b = Math.min(seed[3], s.b)
        if (r - l < 4 || b - t < 4) return null
        fun stripOk(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            if (x1 <= x0 || y1 <= y0) return false
            var inn = 0; var all = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { all++; if (s.inside(x, y)) inn++; x += 2 }
                y += 2
            }
            return all > 0 && inn * 100 >= all * 96
        }
        val step = 3
        var grew = true
        var guard = 0
        while (grew && guard++ < 400) {
            grew = false
            if (l - step >= s.l && stripOk(l - step, t, l, b)) { l -= step; grew = true }
            if (r + step <= s.r && stripOk(r, t, r + step, b)) { r += step; grew = true }
            if (t - step >= s.t && stripOk(l, t - step, r, t)) { t -= step; grew = true }
            if (b + step <= s.b && stripOk(l, b, r, b + step)) { b += step; grew = true }
        }
        l += margin; t += margin; r -= margin; b -= margin
        return if (r - l >= 8 && b - t >= 8) intArrayOf(l, t, r, b) else null
    }
}
