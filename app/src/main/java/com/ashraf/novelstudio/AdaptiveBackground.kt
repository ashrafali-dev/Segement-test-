package com.ashraf.novelstudio

import android.graphics.Bitmap

/**
 * Feature D — adaptive gradient background behind every translation.
 *
 * The gradient is derived from the bubble NEIGHBOURHOOD (a ring around the text box, inside the bubble) so the
 * translation blends into the artwork instead of sitting on a flat box, and the text colour is then solved
 * AGAINST that gradient: keep it, flip black/white, or flip and add an outline. Hue is aesthetic; contrast is
 * always fixed on the text side — never by changing the gradient.
 *
 * Pure functions + one bitmap sampler. The decision is computed ONCE per bubble when the overlay is built.
 */
internal data class GradientStyle(
    /** ARGB top stop. */
    val top: Int,
    /** ARGB bottom stop. */
    val bottom: Int,
    /** Alpha of the background (>= 0.90 by spec: the gradient must actually hide the old lettering). */
    val alpha: Float,
    /** Final text colour (unchanged, or flipped to black/white). */
    val textColor: Int,
    /** True when the text colour was flipped. */
    val flipped: Boolean,
    /** Outline colour for the glyphs, or 0 for none (only when the flip alone is not enough). */
    val outline: Int,
    val outlineAlpha: Float,
    /** WCAG ratio of the final text colour against the WORSE gradient stop. */
    val minContrast: Float
)

internal object AdaptiveBackground {
    const val MIN_CONTRAST = 4.5f
    const val GRADIENT_ALPHA = 0.94f
    const val OUTLINE_ALPHA = 0.85f
    const val DESATURATE = 0.45f
    /** Padding around the text box, in dp. */
    const val PAD_DP = 8f
    /** Corner radius, in dp. */
    const val CORNER_DP = 6f
    /** Width of the sampled ring around the padded box, in px. */
    private const val RING_PX = 8

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Relative luminance per WCAG 2.x (sRGB, 0..1). */
    fun luminance(argb: Int): Float {
        fun ch(v: Int): Float {
            val s = (v and 0xFF) / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val r = ch(argb shr 16 and 0xFF)
        val g = ch(argb shr 8 and 0xFF)
        val b = ch(argb)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** WCAG contrast ratio between two opaque colours, 1..21. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = Math.max(la, lb)
        val lo = Math.min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** HSV of a colour: hue degrees 0..360, s/v in 0..1. */
    fun hsv(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> (60f * (((g - b) / d) % 6f) + 360f) % 360f
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        val s = if (max == 0f) 0f else d / max
        return floatArrayOf(h, s, max)
    }

    /** ARGB from HSV (h degrees, s/v 0..1), opaque. */
    fun hsvColor(h: Float, s: Float, v: Float, alpha: Int = 0xFF): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - Math.abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun q(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
    }

    /** Median of a list (0 for empty). */
    fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val s = values.copyOf(); s.sort()
        return s[s.size / 2]
    }

    /** Circular-free median of hues (they live on a circle; a plain median would break at 0/360). */
    fun medianHue(hues: FloatArray): Float {
        if (hues.isEmpty()) return 0f
        // Pick the hue with the smallest total circular distance: stable for a tight cluster, correct near 0.
        var best = hues[0]; var bestCost = Float.MAX_VALUE
        for (candidate in hues) {
            var cost = 0f
            for (h in hues) {
                val d = Math.abs(h - candidate)
                cost += minOf(d, 360f - d)
            }
            if (cost < bestCost) { bestCost = cost; best = candidate }
        }
        return best
    }

    /**
     * Build the gradient from the median HSV of the samples and then solve the text colour.
     *
     * Exactly the spec: desaturate S by 0.45, keep hue; top V = clamp(1.10V, .55, .95), bottom V =
     * clamp(0.92V, .45, .90); opaque colours (alpha is a separate field so the contrast maths stays honest).
     */
    fun fromSamples(hues: FloatArray, sats: FloatArray, vals: FloatArray, textColor: Int): GradientStyle {
        val h = medianHue(hues)
        val s = median(sats) * DESATURATE
        val v = median(vals)
        val topV = (v * 1.10f).coerceIn(0.55f, 0.95f)
        val bottomV = (v * 0.92f).coerceIn(0.45f, 0.90f)
        val top = hsvColor(h, s, topV)
        val bottom = hsvColor(h, s, bottomV)
        return solve(top, bottom, textColor)
    }

    /** Text-side contrast decision (the decision table in the PR body). */
    fun solve(top: Int, bottom: Int, textColor: Int): GradientStyle {
        val cTop = contrast(textColor, top)
        val cBottom = contrast(textColor, bottom)
        val start = minOf(cTop, cBottom)
        if (start >= MIN_CONTRAST) {
            return GradientStyle(top, bottom, GRADIENT_ALPHA, textColor, flipped = false, outline = 0, outlineAlpha = 0f, minContrast = start)
        }
        // Flip black <-> white: whoever is lighter gets the darker one.
        val flipped = if (luminance(textColor) >= 0.5f) BLACK else WHITE
        val fStart = minOf(contrast(flipped, top), contrast(flipped, bottom))
        val needsOutline = fStart < MIN_CONTRAST
        return GradientStyle(
            top, bottom, GRADIENT_ALPHA, flipped, flipped = true,
            outline = if (needsOutline) opposite(flipped) else 0,
            outlineAlpha = if (needsOutline) OUTLINE_ALPHA else 0f,
            minContrast = fStart
        )
    }

    fun opposite(color: Int): Int = if (luminance(color) >= 0.5f) BLACK else WHITE

    /**
     * Sample a ring around [boxPx] (left, top, right, bottom, in [src] pixels), padded by [padPx], and build
     * the style. The ring stays inside the bitmap; samples are opaque (alpha is not part of the decision).
     * Cost is a few thousand getPixel calls once per bubble: well under the 2 ms budget.
     */
    fun forBubble(src: Bitmap, boxPx: IntArray, textColor: Int, padPx: Int = 24): GradientStyle {
        val l = (boxPx[0] - padPx - RING_PX).coerceIn(0, src.width - 1)
        val t = (boxPx[1] - padPx - RING_PX).coerceIn(0, src.height - 1)
        val r = (boxPx[2] + padPx + RING_PX).coerceIn(l + 1, src.width)
        val b = (boxPx[3] + padPx + RING_PX).coerceIn(t + 1, src.height)
        val idx = (boxPx[0] - padPx).coerceAtLeast(0)
        val idy = (boxPx[1] - padPx).coerceAtLeast(0)
        val iw = (boxPx[2] + padPx).coerceAtMost(src.width)
        val ih = (boxPx[3] + padPx).coerceAtMost(src.height)
        val hues = ArrayList<Float>(256); val sats = ArrayList<Float>(256); val vals = ArrayList<Float>(256)
        val step = Math.max(1, (r - l) / 40)
        fun take(x: Int, y: Int) {
            // Ring = outer band MINUS the padded text box itself.
            if (x in idx until iw && y in idy until ih) return
            val c = src.getPixel(x.coerceIn(0, src.width - 1), y.coerceIn(0, src.height - 1))
            val hsv = hsv(c or (0xFF shl 24))
            hues.add(hsv[0]); sats.add(hsv[1]); vals.add(hsv[2])
        }
        var x = l
        while (x < r) { take(x, t); take(x, b - 1); x += step }
        var y = t
        while (y < b) { take(l, y); take(r - 1, y); y += step }
        if (hues.isEmpty()) return solve(WHITE, 0xFFF2F2F2.toInt(), textColor)
        return fromSamples(hues.toFloatArray(), sats.toFloatArray(), vals.toFloatArray(), textColor)
    }
}
