package com.ashraf.novelstudio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * WEBTOON ONLY.
 *
 * Why two bubbles became ONE (or a whisper outside a bubble got pulled into it):
 *  1. BubbleFit rebuilds a bubble by flood-filling the paper colour around the lettering. Two overlapping speech
 *     bubbles (clouds, stacked ovals) share paper, so the fill LEAKS from one into the other. The "cover box" of
 *     bubble A then also contains the text of bubble B (or of a whisper written beside the bubble).
 *  2. The duplicate check in readSlice (`sameSpot`) then saw two boxes that overlap by > 50% and, because the texts
 *     differ, glued them together as "two lines of one bubble". One cover, one merged sentence, one translation.
 *
 * This object fixes both at the source:
 *  - [clip] cuts a cover / text rectangle at the half-way line to every OTHER bubble's OCR box that it would
 *    otherwise contain.
 *  - [adjacent] answers "are these two different-text readings really neighbouring lines of the same bubble?" using
 *    the OCR boxes (not the grown cover boxes), so lines of different bubbles are never glued.
 */
internal object WebtoonBubbleGuard {

    /**
     * Cuts [box] ([l, t, r, b]) so it no longer contains any of [others] (OCR boxes of other, independent bubbles),
     * while always keeping [seed] (this bubble's own lettering) inside. Returns a new array; [box] is untouched.
     */
    fun clip(box: IntArray, seed: IntArray, others: List<IntArray>): IntArray {
        val r = box.copyOf()
        for (o in others) {
            // Only care about neighbours that the box actually reaches into.
            val ix = min(r[2], o[2]) - max(r[0], o[0])
            val iy = min(r[3], o[3]) - max(r[1], o[1])
            if (ix <= 0 || iy <= 0) continue
            // A box that sits (almost) completely inside the seed is the same text, not a neighbour.
            val sx = min(seed[2], o[2]) - max(seed[0], o[0])
            val sy = min(seed[3], o[3]) - max(seed[1], o[1])
            val oArea = (o[2] - o[0]).toLong() * (o[3] - o[1])
            if (sx > 0 && sy > 0 && sx.toLong() * sy * 100 >= oArea * 60) continue

            val scx = (seed[0] + seed[2]) / 2f; val scy = (seed[1] + seed[3]) / 2f
            val ocx = (o[0] + o[2]) / 2f; val ocy = (o[1] + o[3]) / 2f
            val nx = abs(ocx - scx) / max(1f, (seed[2] - seed[0] + o[2] - o[0]) / 2f)
            val ny = abs(ocy - scy) / max(1f, (seed[3] - seed[1] + o[3] - o[1]) / 2f)
            if (ny >= nx) {
                if (ocy > scy) {                                   // neighbour below: cut the bottom
                    val cut = max(seed[3], (seed[3] + o[1]) / 2)
                    if (cut < r[3]) r[3] = cut
                } else {                                           // neighbour above: cut the top
                    val cut = min(seed[1], (seed[1] + o[3]) / 2)
                    if (cut > r[1]) r[1] = cut
                }
            } else {
                if (ocx > scx) {                                   // neighbour on the right
                    val cut = max(seed[2], (seed[2] + o[0]) / 2)
                    if (cut < r[2]) r[2] = cut
                } else {                                           // neighbour on the left
                    val cut = min(seed[0], (seed[0] + o[2]) / 2)
                    if (cut > r[0]) r[0] = cut
                }
            }
        }
        // Never smaller than the lettering itself.
        r[0] = min(r[0], seed[0]); r[1] = min(r[1], seed[1])
        r[2] = max(r[2], seed[2]); r[3] = max(r[3], seed[3])
        return r
    }

    /** Clips every band (bubble-interior rectangle) the same way; bands that vanish are dropped. */
    fun clipAll(bands: List<IntArray>, seed: IntArray, others: List<IntArray>): List<IntArray> {
        val out = ArrayList<IntArray>(bands.size)
        for (b in bands) {
            val c = clipBand(b, seed, others)
            if (c[2] - c[0] >= 2 && c[3] - c[1] >= 2) out.add(c)
        }
        return out
    }

    // A band may legitimately not contain the seed, so it is clipped without the "keep seed" rule.
    private fun clipBand(band: IntArray, seed: IntArray, others: List<IntArray>): IntArray {
        val r = band.copyOf()
        for (o in others) {
            val ix = min(r[2], o[2]) - max(r[0], o[0])
            val iy = min(r[3], o[3]) - max(r[1], o[1])
            if (ix <= 0 || iy <= 0) continue
            val scx = (seed[0] + seed[2]) / 2f; val scy = (seed[1] + seed[3]) / 2f
            val ocx = (o[0] + o[2]) / 2f; val ocy = (o[1] + o[3]) / 2f
            val nx = abs(ocx - scx) / max(1f, (seed[2] - seed[0] + o[2] - o[0]) / 2f)
            val ny = abs(ocy - scy) / max(1f, (seed[3] - seed[1] + o[3] - o[1]) / 2f)
            if (ny >= nx) {
                if (ocy > scy) { val cut = max(seed[3], (seed[3] + o[1]) / 2); if (cut < r[3]) r[3] = cut }
                else { val cut = min(seed[1], (seed[1] + o[3]) / 2); if (cut > r[1]) r[1] = cut }
            } else {
                if (ocx > scx) { val cut = max(seed[2], (seed[2] + o[0]) / 2); if (cut < r[2]) r[2] = cut }
                else { val cut = min(seed[0], (seed[0] + o[2]) / 2); if (cut > r[0]) r[0] = cut }
            }
        }
        return r
    }

    /**
     * Two readings with DIFFERENT text on (nearly) the same cover spot: are their OCR boxes neighbouring lines of
     * one bubble? Boxes are fractions of the whole image ([l, t, r, b]); [font] is one line height as a fraction of the
     * image width. Same-bubble lines are stacked tightly (gap <= ~1.6 lines) and share horizontal range.
     */
    fun adjacent(a: FloatArray, b: FloatArray, font: Float, imageAspect: Float): Boolean {
        val f = if (font > 0f) font else 0.02f
        val fy = f / max(0.0001f, imageAspect)       // one font in image-HEIGHT fractions
        val vGap = max(0f, max(a[1], b[1]) - min(a[3], b[3]))
        val hGap = max(0f, max(a[0], b[0]) - min(a[2], b[2]))
        val hOverlap = min(a[2], b[2]) - max(a[0], b[0])
        val narrow = min(a[2] - a[0], b[2] - b[0])
        if (vGap > fy * 1.7f) return false
        if (hGap > f * 0.6f) return false
        // Lines of one bubble share most of the narrower line's width; a whisper beside a bubble does not.
        return hOverlap >= narrow * 0.3f || abs((a[0] + a[2]) / 2f - (b[0] + b[2]) / 2f) <= max(a[2] - a[0], b[2] - b[0]) * 0.35f
    }
}
