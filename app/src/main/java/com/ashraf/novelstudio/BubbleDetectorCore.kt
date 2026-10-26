package com.ashraf.novelstudio

/**
 * Pure-Kotlin part of the bubble / text detector (no Android types, so it is unit-tested on the JVM).
 *
 * Model: Sundowner123/comic-text-and-bubble-detector (int8 ONNX copy of ogkalu/comic-text-and-bubble-detector,
 * RT-DETR-v2, Apache-2.0). Contract taken from its model card:
 *   inputs : images [N,3,640,640] float32 RGB 0..1 (the picture is STRETCHED, not letter-boxed),
 *            orig_target_sizes [N,2] int64
 *   outputs: labels [N,300], boxes [N,300,4] (xyxy), scores [N,300]   (no NMS needed, it is a DETR)
 *   classes: 0 bubble (empty outline), 1 text_bubble (lettering inside a bubble), 2 text_free (lettering outside)
 *
 * The app passes orig_target_sizes = (SIZE, SIZE) on purpose: the returned boxes are then in 640x640 model space and
 * [decode] scales them to the tile itself, so the width/height order of that tensor can never matter.
 */
internal object BubbleDetectorCore {
    const val SIZE = 640
    const val CLS_BUBBLE = 0
    const val CLS_TEXT_BUBBLE = 1
    const val CLS_TEXT_FREE = 2
    const val SCORE_MIN = 0.30f

    /** One detection in the coordinates of the page that was scanned. */
    class Det(
        val label: Int, val score: Float, val l: Int, val t: Int, val r: Int, val b: Int,
        /** Real balloon outline from the optional mask model; null = only the box of the main detector is known. */
        val seg: BubbleSegCore.Seg? = null
    ) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        val area: Long get() = w.toLong() * h
        fun contains(x: Float, y: Float): Boolean {
            if (!(x >= l && x < r && y >= t && y < b)) return false
            val s = seg ?: return true
            return s.inside(x.toInt(), y.toInt())
        }
        override fun toString(): String = "Det(label=$label score=$score box=[$l,$t,$r,$b])"
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
                if (iou(d, k) > 0.5f || containedShare(if (d.area <= k.area) d else k, if (d.area <= k.area) k else d) > 0.85f) {
                    dup = true; break
                }
            }
            if (!dup) kept.add(d)
            if (kept.size >= maxOut) break
        }
        return kept
    }

    /** Fills a CHW float plane set (R, G, B, each size*size, 0..1) from ARGB pixels (row-major, size*size). */
    fun fillChw(argb: IntArray, size: Int, out: FloatArray) {
        val plane = size * size
        require(argb.size >= plane && out.size >= plane * 3) { "buffer too small" }
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
        // A "bubble" many times larger than its text is a panel-sized false alarm.
        val textArea = Math.max(1L, (r - l).toLong() * (b - t).toLong())
        if ((d.r - d.l).toLong() * (d.b - d.t).toLong() > textArea * 40L) return null
        // Pull the box in a little: the outline and the tail are part of the detector box, round bubbles are not boxes.
        val mw = Math.max(2, ((d.r - d.l) * 0.06f).toInt())
        val mh = Math.max(2, ((d.b - d.t) * 0.06f).toInt())
        return intArrayOf(
            Math.min(d.l + mw, l), Math.min(d.t + mh, t),
            Math.max(d.r - mw, r), Math.max(d.b - mh, b)
        )
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
        // With a real balloon MASK the outline is exact: lettering inside the balloon and a small whisper / note next to
        // it OUTSIDE the outline are two texts (they used to be glued under one marker).
        if (a != null) return a.seg != null
        if (b != null) return b.seg != null
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

    /** How the masks were merged into the main detector's list (for the log). */
    class MergeStats(val attached: Int, val maskOnly: Int, val boxOnly: Int)

    /**
     * Gives the main detector's balloon boxes their real outline. A mask whose box matches a main-detector balloon
     * (IoU >= 0.4, or the mask centre lies in that box) is attached to it; a mask the main detector missed is added as a
     * balloon of its own. Lettering classes (text_bubble / text_free) are never touched: the mask model only knows balloons.
     */
    fun attachMasks(dets: List<Det>, segs: List<BubbleSegCore.Seg>): Pair<List<Det>, MergeStats> {
        val out = ArrayList<Det>(dets.size + segs.size)
        val used = BooleanArray(segs.size)
        var attached = 0
        for (d in dets) {
            if (d.label != CLS_BUBBLE) { out.add(d); continue }
            var best = -1; var bestIou = 0f
            for ((i, s) in segs.withIndex()) {
                if (used[i]) continue
                val iw = Math.min(d.r, s.r) - Math.max(d.l, s.l)
                val ih = Math.min(d.b, s.b) - Math.max(d.t, s.t)
                if (iw <= 0 || ih <= 0) continue
                val inter = iw.toLong() * ih
                val uni = d.area + s.w.toLong() * s.h - inter
                val v = if (uni <= 0L) 0f else inter.toFloat() / uni
                if (v > bestIou) { bestIou = v; best = i }
            }
            if (best >= 0 && bestIou >= 0.4f) {
                used[best] = true; attached++
                val s = segs[best]
                out.add(Det(CLS_BUBBLE, d.score, d.l, d.t, d.r, d.b, s))
            } else out.add(d)
        }
        var maskOnly = 0
        for ((i, s) in segs.withIndex()) if (!used[i]) { maskOnly++; out.add(Det(CLS_BUBBLE, s.score, s.l, s.t, s.r, s.b, s)) }
        val boxOnly = dets.count { it.label == CLS_BUBBLE } - attached
        return Pair(out, MergeStats(attached, maskOnly, boxOnly))
    }
}
