package com.ashraf.novelstudio

/**
 * Pure-Kotlin geometry and compatibility helpers for balloon detections; Android-free so this code is JVM-testable.
 *
 * The active ONNX model is Koharu YOLO26s segmentation, decoded in KoharuSegCore. Its frame/dialogue_text/balloon/
 * onomatopoeia_text outputs are mapped into these stable labels so existing grouping, mask and placement code can
 * consume them without a second classification heuristic.
 */
internal object BubbleDetectorCore {
    const val SIZE = 640
    const val CLS_BUBBLE = 0
    const val CLS_TEXT_BUBBLE = 1
    const val CLS_TEXT_FREE = 2
    const val SCORE_MIN = 0.30f

    /** Compact instance mask sampled over the associated detector box. 1 = inside the balloon mask. */
    class Mask(val width: Int, val height: Int, val pixels: ByteArray) {
        fun contains(x: Float, y: Float, l: Int, t: Int, r: Int, b: Int): Boolean {
            if (width <= 0 || height <= 0 || pixels.size < width * height || r <= l || b <= t) return true
            val mx = (((x - l) / (r - l) * width).toInt()).coerceIn(0, width - 1)
            val my = (((y - t) / (b - t) * height).toInt()).coerceIn(0, height - 1)
            return pixels[my * width + mx].toInt() != 0
        }
    }

    /** One detection in the coordinates of the page that was scanned. */
    class Det(
        val label: Int, val score: Float, val l: Int, val t: Int, val r: Int, val b: Int,
        val mask: Mask? = null
    ) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        val area: Long get() = w.toLong() * h
        fun contains(x: Float, y: Float): Boolean {
            if (x < l || x >= r || y < t || y >= b) return false
            return mask?.contains(x, y, l, t, r, b) ?: true
        }
        override fun toString(): String = "Det(label=$label score=$score box=[$l,$t,$r,$b] hasMask=${mask != null})"
    }

    /**
     * Windows [x0, y0, x1, y1] to run the 640x640 model on. A tall webtoon slice squeezed into a square would make the
     * lettering unreadable for the model, so tall (or very wide) pictures are cut into overlapping, roughly
     * square-ish tiles. A normal page returns one tile.
     */
    fun planTiles(w: Int, h: Int): List<IntArray> {
        if (w < 16 || h < 16) return emptyList()
        val maxAspect = 1.9f   // was 1.5: a 700x1280 webtoon slice needed 2 model runs (about 3.9 s on the A13), now 1
        if (h > w * maxAspect) {
            val th = Math.round(w * maxAspect).coerceIn(16, h)
            return strips(h, th).map { intArrayOf(0, it[0], w, it[1]) }
        }
        if (w > h * maxAspect) {
            val tw = Math.round(h * maxAspect).coerceIn(16, w)
            return strips(w, tw).map { intArrayOf(it[0], 0, it[1], h) }
        }
        return listOf(intArrayOf(0, 0, w, h))
    }

    /** Cuts [0,total) into windows of [size] with ~20 % overlap; the last window is shifted back so it is full size. */
    internal fun strips(total: Int, size: Int): List<IntArray> {
        if (total <= size) return listOf(intArrayOf(0, total))
        val step = Math.max(1, (size * 0.8f).toInt())
        val out = ArrayList<IntArray>()
        var s = 0
        while (true) {
            val e = s + size
            if (e >= total) { out.add(intArrayOf(Math.max(0, total - size), total)); break }
            out.add(intArrayOf(s, e))
            s += step
        }
        return out
    }

    /** Safety cap: a tile list longer than this is thinned (very tall stitched pages) so one scan stays bounded. */
    const val MAX_TILES = 8

    fun capTiles(tiles: List<IntArray>): List<IntArray> {
        if (tiles.size <= MAX_TILES) return tiles
        // Keep evenly spaced tiles; the remaining area is simply not read by the detector (OCR still reads it).
        val out = ArrayList<IntArray>(MAX_TILES)
        for (i in 0 until MAX_TILES) out.add(tiles[(i.toLong() * (tiles.size - 1) / (MAX_TILES - 1)).toInt()])
        return out
    }

    /**
     * Converts the raw model output of ONE tile to page coordinates.
     * [boxes] holds [count]*4 floats (x1,y1,x2,y2 in 640x640 model space). Boxes outside the tile are clamped.
     */
    fun decode(
        labels: FloatArray, boxes: FloatArray, scores: FloatArray, count: Int,
        tile: IntArray, scoreMin: Float = SCORE_MIN
    ): List<Det> {
        val n = Math.min(count, Math.min(labels.size, Math.min(scores.size, boxes.size / 4)))
        val tw = (tile[2] - tile[0]).toFloat()
        val th = (tile[3] - tile[1]).toFloat()
        val out = ArrayList<Det>()
        for (i in 0 until n) {
            val s = scores[i]
            if (!s.isFinite() || s < scoreMin) continue
            val lab = Math.round(labels[i])
            if (lab < CLS_BUBBLE || lab > CLS_TEXT_FREE) continue
            val x1 = boxes[4 * i]; val y1 = boxes[4 * i + 1]; val x2 = boxes[4 * i + 2]; val y2 = boxes[4 * i + 3]
            if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) continue
            val l = Math.floor((x1 / SIZE * tw).toDouble()).toInt().coerceIn(0, tw.toInt()) + tile[0]
            val t = Math.floor((y1 / SIZE * th).toDouble()).toInt().coerceIn(0, th.toInt()) + tile[1]
            val r = Math.ceil((x2 / SIZE * tw).toDouble()).toInt().coerceIn(0, tw.toInt()) + tile[0]
            val b = Math.ceil((y2 / SIZE * th).toDouble()).toInt().coerceIn(0, th.toInt()) + tile[1]
            if (r - l < 6 || b - t < 6) continue
            out.add(Det(lab, s, l, t, r, b))
        }
        return out
    }

    private fun iou(a: Det, b: Det): Float {
        val iw = Math.min(a.r, b.r) - Math.max(a.l, b.l)
        val ih = Math.min(a.b, b.b) - Math.max(a.t, b.t)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toLong() * ih
        val uni = a.area + b.area - inter
        return if (uni <= 0L) 0f else inter.toFloat() / uni
    }

    private fun containedShare(small: Det, big: Det): Float {
        val iw = Math.min(small.r, big.r) - Math.max(small.l, big.l)
        val ih = Math.min(small.b, big.b) - Math.max(small.t, big.t)
        if (iw <= 0 || ih <= 0 || small.area <= 0L) return 0f
        return (iw.toLong() * ih).toFloat() / small.area
    }

    /** Joins the detections of overlapping tiles: the same object seen twice is kept once (best score). */
    fun merge(all: List<Det>, maxOut: Int = 150): List<Det> {
        val kept = ArrayList<Det>()
        for (d in all.sortedByDescending { it.score }) {
            var dup = false
            for (k in kept) {
                if (k.label != d.label) continue
                if (iou(d, k) > 0.7f || containedShare(if (d.area <= k.area) d else k, if (d.area <= k.area) k else d) > 0.85f) {
                    dup = true; break
                }
            }
            if (!dup) kept.add(d)
            if (kept.size >= maxOut) break
        }
        return kept
    }

    /** Fills a CHW float plane set (R, G, B, each size*size, 0..1) from ARGB pixels (row-major, size*size). */
    fun fillChw(argb: IntArray, size: Int, out: FloatArray) =
        fillChw(argb, size, size, out)

    /** Fills RGB planes for a dynamic Koharu input; the image is row-major ARGB. */
    fun fillChw(argb: IntArray, width: Int, height: Int, out: FloatArray) {
        val plane = width * height
        require(width > 0 && height > 0 && argb.size >= plane && out.size >= plane * 3) {
            "buffer too small for ${width}x${height}"
        }
        for (i in 0 until plane) {
            val p = argb[i]
            out[i] = ((p ushr 16) and 0xff) / 255f
            out[plane + i] = ((p ushr 8) and 0xff) / 255f
            out[2 * plane + i] = (p and 0xff) / 255f
        }
    }

    // ---- how the extractor uses detections ---------------------------------------------------------------------

    /**
     * Inner area of the speech bubble the detector found around a text line [l,t,r,b], or null when it is not sure.
     * Real-time reads skip the slow pixel flood-fill, so until now the translation was drawn in the box of the OCR lines
     * only - the detector knew the real bubble but its box was never used for placement.
     */
    fun interiorFor(dets: List<Det>?, l: Int, t: Int, r: Int, b: Int): IntArray? {
        if (dets == null) return null
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        var best: Det? = null
        for (d in dets) {
            if (d.label != CLS_BUBBLE || !d.contains(cx, cy)) continue
            if (best == null || d.area < best.area) best = d
        }
        val d = best ?: return null
        // The lettering must sit (almost) completely inside: otherwise this is a neighbouring or cut-off box.
        if (l < d.l - 4 || r > d.r + 4 || t < d.t - 4 || b > d.b + 4) return null
        // Keep the legacy size-ratio guard for box-only detections, where it helps reject panel-sized false
        // alarms. The new YOLO segmentation model emits a balloon class plus an instance mask, so a large real
        // balloon around a short text line must not be rejected just because its area/text-area ratio is high.
        val textArea = Math.max(1L, (r - l).toLong() * (b - t).toLong())
        if (d.mask == null && (d.r - d.l).toLong() * (d.b - d.t).toLong() > textArea * 40L) return null
        // Pull the box in a little: the outline and the tail are part of the detector box, round bubbles are not boxes.
        val mw = Math.max(2, ((d.r - d.l) * 0.06f).toInt())
        val mh = Math.max(2, ((d.b - d.t) * 0.06f).toInt())
        return intArrayOf(
            Math.min(d.l + mw, l), Math.min(d.t + mh, t),
            Math.max(d.r - mw, r), Math.max(d.b - mh, b)
        )
    }

    /** The model mask, expressed in page pixels: a safe text rectangle plus interior cover bands. */
    class MaskGeometry(val detection: Det, val safe: IntArray, val bands: List<IntArray>)

    /** Fraction of a pixel-space OCR box that the model's instance mask actually contains. */
    fun maskCoverage(d: Det, l: Int, t: Int, r: Int, b: Int, grid: Int = 7): Float {
        if (d.mask == null || r <= l || b <= t) return 0f
        var inside = 0
        var total = 0
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val x = l + (gx + 0.5f) * (r - l) / grid
            val y = t + (gy + 0.5f) * (b - t) / grid
            total++
            if (d.contains(x, y)) inside++
        }
        return if (total == 0) 0f else inside.toFloat() / total
    }

    /**
     * Reuse the segmentation model's existing mask for translation placement and cover geometry.
     * This does not generate a second mask: it extracts the largest inscribed rectangle and safe
     * horizontal bands from the model's binary instance mask.
     */
    fun maskGeometryFor(dets: List<Det>?, l: Int, t: Int, r: Int, b: Int): MaskGeometry? {
        if (dets.isNullOrEmpty() || r <= l || b <= t) return null
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val d = dets.asSequence()
            .filter { it.label == CLS_BUBBLE && it.mask != null && it.contains(cx, cy) }
            .minByOrNull { it.area } ?: return null
        // Refuse a mask that only happens to contain the centre of a neighbouring OCR box.
        if (maskCoverage(d, l, t, r, b) < 0.52f) return null
        val mask = d.mask ?: return null
        val mw = mask.width
        val mh = mask.height
        if (mw < 8 || mh < 8 || mask.pixels.size < mw * mh || d.w < 16 || d.h < 16) return null

        // Maximal all-foreground rectangle, in O(mask.width * mask.height), with a monotonic histogram stack.
        val heights = IntArray(mw)
        var bestArea = 0
        var bestL = 0
        var bestR = 0
        var bestT = 0
        var bestB = 0
        val stack = IntArray(mw + 1)
        for (y in 0 until mh) {
            for (x in 0 until mw) {
                heights[x] = if (mask.pixels[y * mw + x].toInt() != 0) heights[x] + 1 else 0
            }
            var top = 0
            for (x in 0..mw) {
                val cur = if (x == mw) 0 else heights[x]
                while (top > 0 && heights[stack[top - 1]] > cur) {
                    val bar = stack[--top]
                    val hRect = heights[bar]
                    val left = if (top == 0) 0 else stack[top - 1] + 1
                    val width = x - left
                    val area = width * hRect
                    if (area > bestArea) {
                        bestArea = area
                        bestL = left; bestR = x
                        bestT = y - hRect + 1; bestB = y + 1
                    }
                }
                stack[top++] = x
            }
        }
        if (bestArea <= 0) return null
        // Inset one low-resolution mask cell so antialiasing/quantisation cannot include the outline.
        val sl = bestL + 1
        val sr = bestR - 1
        val st = bestT + 1
        val sb = bestB - 1
        if (sr - sl < mw * 0.18f || sb - st < mh * 0.12f) return null
        val safe = intArrayOf(
            d.l + kotlin.math.ceil(sl * d.w.toDouble() / mw).toInt(),
            d.t + kotlin.math.ceil(st * d.h.toDouble() / mh).toInt(),
            d.l + kotlin.math.floor(sr * d.w.toDouble() / mw).toInt(),
            d.t + kotlin.math.floor(sb * d.h.toDouble() / mh).toInt()
        )
        if (safe[2] - safe[0] < 12 || safe[3] - safe[1] < 12) return null
        val safeArea = (safe[2] - safe[0]).toLong() * (safe[3] - safe[1])
        if (safeArea * 5L < d.area) return null
        val bands = maskBands(d, maxBands = 28)
        if (bands.isEmpty()) return null
        return MaskGeometry(d, safe, bands)
    }

    /** Compress the model's binary mask into horizontal rectangles which stay inside each row-run. */
    private fun maskBands(d: Det, maxBands: Int): List<IntArray> {
        val mask = d.mask ?: return emptyList()
        val mw = mask.width
        val mh = mask.height
        if (mw < 2 || mh < 2 || mask.pixels.size < mw * mh) return emptyList()
        val rows = ArrayList<IntArray>(mh)
        for (y in 0 until mh) {
            val runs = ArrayList<Int>()
            var x = 0
            while (x < mw) {
                if (mask.pixels[y * mw + x].toInt() == 0) { x++; continue }
                val start = x
                while (x < mw && mask.pixels[y * mw + x].toInt() != 0) x++
                if (x - start >= 2) { runs.add(start); runs.add(x) }
            }
            rows.add(runs.toIntArray())
        }
        for (tol in 1..10) {
            val out = ArrayList<IntArray>()
            var groupStart = -1
            var active: IntArray? = null
            fun flush(endY: Int) {
                val cur = active ?: return
                var i = 0
                while (i + 1 < cur.size) {
                    val pxL = d.l + kotlin.math.ceil(cur[i] * d.w.toDouble() / mw).toInt()
                    val pxR = d.l + kotlin.math.floor(cur[i + 1] * d.w.toDouble() / mw).toInt()
                    val pxT = d.t + kotlin.math.ceil(groupStart * d.h.toDouble() / mh).toInt()
                    val pxB = d.t + kotlin.math.floor(endY * d.h.toDouble() / mh).toInt()
                    if (pxR - pxL >= 2 && pxB - pxT >= 2) out.add(intArrayOf(pxL, pxT, pxR, pxB))
                    i += 2
                }
                active = null
            }
            for (y in 0 until mh) {
                val row = rows[y]
                val cur = active
                var compatible = cur != null && cur.size == row.size && row.isNotEmpty()
                if (compatible && cur != null) {
                    var i = 0
                    while (i + 1 < row.size) {
                        if (kotlin.math.abs(row[i] - cur[i]) > tol ||
                            kotlin.math.abs(row[i + 1] - cur[i + 1]) > tol) {
                            compatible = false
                            break
                        }
                        i += 2
                    }
                }
                if (compatible && cur != null) {
                    var i = 0
                    while (i + 1 < row.size) {
                        cur[i] = maxOf(cur[i], row[i])
                        cur[i + 1] = minOf(cur[i + 1], row[i + 1])
                        i += 2
                    }
                    if ((0 until cur.size step 2).any { cur[it + 1] - cur[it] < 2 }) {
                        flush(y)
                        active = if (row.isNotEmpty()) row.copyOf() else null
                        groupStart = y
                    }
                } else {
                    flush(y)
                    if (row.isNotEmpty()) {
                        active = row.copyOf()
                        groupStart = y
                    }
                }
            }
            flush(mh)
            if (out.size in 1..maxBands) return out
        }
        return emptyList()
    }

    /** Smallest bubble-like box (bubble / text_bubble) around the point, or null. */
    fun bubbleAt(dets: List<Det>?, x: Float, y: Float): Det? {
        if (dets == null) return null
        var best: Det? = null
        for (d in dets) {
            if (d.label == CLS_TEXT_FREE) continue
            if (!d.contains(x, y)) continue
            if (best == null || d.area < best.area) best = d
        }
        return best
    }

    /**
     * True when the detector says the two points are inside DIFFERENT speech bubbles: lines of two bubbles must never be
     * joined into one sentence. Unknown (no detection around one of them) is never a veto.
     */
    fun inDifferentBubbles(dets: List<Det>?, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        if (dets == null) return false
        // Use the real "bubble" (outline) boxes only: text_bubble boxes can legitimately be split per text column.
        val a = containerAt(dets, ax, ay)
        val b = containerAt(dets, bx, by)
        if (a != null && b != null) return a !== b
        if (a != null || b != null) return false
        // Neither piece is inside a speech bubble: free lettering (captions, labels, notes on artwork). If each one
        // sits in a text_free box and no single box holds both, they are separate texts.
        var inA = false
        var inB = false
        for (d in dets) {
            if (d.label != CLS_TEXT_FREE) continue
            val ca = d.contains(ax, ay)
            val cb = d.contains(bx, by)
            if (ca && cb) return false
            if (ca) inA = true
            if (cb) inB = true
        }
        return inA && inB
    }

    private fun containerAt(dets: List<Det>, x: Float, y: Float): Det? {
        var best: Det? = null
        for (d in dets) {
            if (d.label != CLS_BUBBLE || !d.contains(x, y)) continue
            if (best == null || d.area < best.area) best = d
        }
        return best
    }

    /**
     * True when lettering at [x],[y] is free text (SFX, narration on artwork): the detector found a text_free box around
     * it and found NO bubble around it. Such text has no speech bubble to fit into.
     */
    fun isFreeText(dets: List<Det>?, x: Float, y: Float): Boolean {
        if (dets == null) return false
        if (bubbleAt(dets, x, y) != null) return false
        for (d in dets) if (d.label == CLS_TEXT_FREE && d.contains(x, y)) return true
        return false
    }
}
