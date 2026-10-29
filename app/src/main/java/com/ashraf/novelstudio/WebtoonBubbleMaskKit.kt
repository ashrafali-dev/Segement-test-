package com.ashraf.novelstudio

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Webtoon-only speech-bubble cleanup.
 *
 * This file is intentionally isolated from the Japanese manga cleanup path. It uses the
 * already-verified interior bands from BubbleFit / segmentation, estimates the paper colour
 * from the bubble's own edge pixels, then repaints those bands row-by-row. This removes OCR-missed
 * Latin glyph edges without placing a rectangular white patch over the artwork.
 *
 * The method is conservative: uncertain shape geometry, patterned interiors, and dark artwork
 * are rejected so the caller can keep its existing safer fallback.
 */
internal object WebtoonBubbleMaskKit {
    internal data class FillResult(
        val accepted: Boolean,
        val reason: String,
        val bands: Int = 0,
        val area: Long = 0L,
        val changed: Int = 0,
        val inkPct: Float = 0f,
        val paperPct: Float = 0f
    )

    private data class Band(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private const val MAX_AREA = 2_880_000L
    private const val MIN_AREA = 96L
    private const val PAPER_DISTANCE = 42
    private const val INK_DISTANCE = 62
    private const val EDGE_DISTANCE = 52

    /** Only Webtoon URLs enter this path. Manga routes such as /manga/... remain untouched. */
    internal fun shouldHandle(pageUrl: String): Boolean {
        val normalized = pageUrl.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        val authorityAndPath = normalized.substringAfter("://", normalized)
        val host = authorityAndPath.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':').removePrefix("www.")
        val path = authorityAndPath.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val webtoonPath = Regex("""(?:^|/)(?:webtoon|webtoons)(?:/|$)""").containsMatchIn("/$path/")
        val webtoonsHost = host == "webtoons.com" || host.endsWith(".webtoons.com")
        return webtoonPath || webtoonsHost
    }

    /**
     * Paint a verified bubble's interior bands with colour sampled from their clean edge pixels.
     * Geometry is normalized (left, top, right, bottom) and must already exclude the outline.
     * Processing uses a single scanline buffer so tall webtoon pages do not allocate another bitmap.
     */
    internal fun fillInterior(
        bitmap: Bitmap,
        cv: List<Float>,
        width: Int,
        height: Int,
        fallbackBackground: Int
    ): FillResult {
        if (bitmap.isRecycled || width != bitmap.width || height != bitmap.height || width <= 0 || height <= 0) {
            return FillResult(false, "invalid_bitmap")
        }
        if (cv.size < 4 || cv.size % 4 != 0) {
            return FillResult(false, "no_shape_bands", bands = cv.size / 4)
        }
        if (width.toLong() * height.toLong() > MAX_AREA) {
            return FillResult(false, "image_area_guard")
        }

        val bands = ArrayList<Band>(min(cv.size / 4, 512))
        var area = 0L
        var i = 0
        while (i + 3 < cv.size) {
            val lf = cv[i]
            val tf = cv[i + 1]
            val rf = cv[i + 2]
            val bf = cv[i + 3]
            if (!lf.isFinite() || !tf.isFinite() || !rf.isFinite() || !bf.isFinite()) {
                return FillResult(false, "invalid_band_geometry", bands = bands.size)
            }
            val l = (lf * width).toInt().coerceIn(0, width)
            val t = (tf * height).toInt().coerceIn(0, height)
            val r = (rf * width).toInt().coerceIn(l, width)
            val b = (bf * height).toInt().coerceIn(t, height)
            if (r - l >= 2 && b - t >= 1) {
                val bandArea = (r - l).toLong() * (b - t).toLong()
                if (bandArea <= 0L || bandArea > MAX_AREA || area + bandArea > MAX_AREA) {
                    return FillResult(false, "shape_area_guard", bands = bands.size, area = area + bandArea)
                }
                bands.add(Band(l, t, r, b))
                area += bandArea
            }
            i += 4
        }
        if (bands.isEmpty() || area < MIN_AREA) {
            return FillResult(false, "no_valid_shape_area", bands = bands.size, area = area)
        }

        val row = IntArray(width)
        val lightSamples = ArrayList<Int>(256)
        var allEdgeSamples = 0
        // Sample the left and right interior margins at a few heights, avoiding the speech-bubble rim.
        // The edge pixels are deliberately preferred over the OCR text box: letters often span only
        // the centre, while missed antialiased glyphs are exactly what this pass needs to remove.
        sampleLoop@ for (band in bands) {
            val sampleYs = intArrayOf(
                band.top,
                band.top + (band.height - 1) / 2,
                band.bottom - 1
            ).distinct()
            val edgeSpan = max(1, band.width / 5)
            val step = max(1, edgeSpan / 4)
            for (y in sampleYs) {
                bitmap.getPixels(row, 0, width, band.left, y, band.width, 1)
                var x = 0
                while (x < edgeSpan) {
                    val c = opaque(row[x])
                    allEdgeSamples++
                    if (isLightPaper(c)) lightSamples.add(c)
                    if (lightSamples.size >= 4096 || allEdgeSamples >= 8192) break@sampleLoop
                    x += step
                }
                x = max(0, band.width - edgeSpan)
                while (x < band.width) {
                    val c = opaque(row[x])
                    allEdgeSamples++
                    if (isLightPaper(c)) lightSamples.add(c)
                    if (lightSamples.size >= 4096 || allEdgeSamples >= 8192) break@sampleLoop
                    x += step
                }
            }
        }

        val fallback = opaque(fallbackBackground)
        val paper = inferPaperColour(lightSamples, allEdgeSamples)
            ?: fallback.takeIf { isLightPaper(it) }
            ?: return FillResult(false, "paper_colour_uncertain", bands.size, area)

        // Validate the entire shape before modifying a single pixel. Text and anti-aliasing may be
        // dark, but the interior must still be mostly the same light paper colour; a shaded/art region
        // is rejected and left to the normal mask/inpainting path.
        var inkPixels = 0L
        var paperPixels = 0L
        var changeCandidates = 0L
        for (band in bands) {
            for (y in band.top until band.bottom) {
                bitmap.getPixels(row, 0, width, band.left, y, band.width, 1)
                for (x in 0 until band.width) {
                    val d2 = distanceSquared(row[x], paper)
                    if (d2 >= INK_DISTANCE * INK_DISTANCE * 3L) inkPixels++
                    if (d2 <= PAPER_DISTANCE * PAPER_DISTANCE * 3L) paperPixels++
                    if (d2 >= 4L * 4L * 3L) changeCandidates++
                }
            }
        }
        val inkPct = inkPixels.toFloat() * 100f / area.toFloat()
        val paperPct = paperPixels.toFloat() * 100f / area.toFloat()
        if (inkPct > 45f) {
            return FillResult(false, "dense_ink_or_art", bands.size, area, 0, inkPct, paperPct)
        }
        if (paperPct < 48f) {
            return FillResult(false, "interior_not_flat_paper", bands.size, area, 0, inkPct, paperPct)
        }
        // Validate before mutating the bitmap. If the inferred paper is already indistinguishable
        // from the input, return safely without a partial edit followed by a rejection.
        if (changeCandidates < 8L) {
            return FillResult(false, "no_pixels_to_change", bands.size, area, changeCandidates.toInt(), inkPct, paperPct)
        }

        var changed = 0
        // Reconstruct each scanline from clean pixels near the left/right edge. Interpolation keeps
        // a mild paper gradient and avoids the hard rectangular white patch that the screenshots show.
        for (band in bands) {
            val edgeSpan = max(1, band.width / 5)
            for (y in band.top until band.bottom) {
                bitmap.getPixels(row, 0, width, band.left, y, band.width, 1)
                val left = edgeAverage(row, 0, edgeSpan, paper)
                val right = edgeAverage(row, max(0, band.width - edgeSpan), band.width, paper)
                val leftColour = left ?: paper
                val rightColour = right ?: paper
                val denom = max(1, band.width - 1).toFloat()
                for (x in 0 until band.width) {
                    val replacement = interpolate(leftColour, rightColour, x / denom)
                    if (row[x] != replacement) changed++
                    row[x] = replacement
                }
                bitmap.setPixels(row, 0, width, band.left, y, band.width, 1)
            }
        }

        if (changed == 0) {
            return FillResult(false, "no_pixels_to_change", bands.size, area, changed, inkPct, paperPct)
        }
        return FillResult(true, "webtoon_shape_paper_fill", bands.size, area, changed, inkPct, paperPct)
    }

    private fun inferPaperColour(samples: List<Int>, totalSamples: Int): Int? {
        if (samples.size < 6 || totalSamples < 6 || samples.size.toFloat() / totalSamples.toFloat() < 0.28f) {
            return null
        }
        val buckets = HashMap<Int, Int>()
        for (sample in samples) {
            val key = bucket(sample)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        val dominant = buckets.maxByOrNull { it.value } ?: return null
        if (dominant.value.toFloat() / samples.size.toFloat() < 0.30f) return null
        val centre = bucketCentre(dominant.key)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (sample in samples) {
            if (distanceSquared(sample, centre) <= 54L * 54L * 3L) {
                r += (sample shr 16) and 0xff
                g += (sample shr 8) and 0xff
                b += sample and 0xff
                count++
            }
        }
        if (count < 4) return null
        val paper = argb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        return paper.takeIf { isLightPaper(it) }
    }

    private fun edgeAverage(row: IntArray, start: Int, end: Int, paper: Int): Int? {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        val from = start.coerceIn(0, row.size)
        val until = end.coerceIn(from, row.size)
        for (x in from until until) {
            val c = opaque(row[x])
            if (distanceSquared(c, paper) <= EDGE_DISTANCE * EDGE_DISTANCE * 3L) {
                r += (c shr 16) and 0xff
                g += (c shr 8) and 0xff
                b += c and 0xff
                count++
            }
        }
        if (count == 0) return null
        return argb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun isLightPaper(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        val luma = (299 * r + 587 * g + 114 * b) / 1000
        val chroma = max(r, max(g, b)) - min(r, min(g, b))
        return luma >= 170 && chroma <= 105
    }

    private fun bucket(c: Int): Int =
        ((((c shr 16) and 0xff) shr 3) shl 10) or
            (((((c shr 8) and 0xff) shr 3) shl 5)) or
            ((c and 0xff) shr 3)

    private fun bucketCentre(key: Int): Int {
        val r = (((key shr 10) and 31) shl 3) + 4
        val g = (((key shr 5) and 31) shl 3) + 4
        val b = ((key and 31) shl 3) + 4
        return argb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun opaque(c: Int): Int = 0xff000000.toInt() or (c and 0x00ffffff)

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xff000000.toInt() or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun distanceSquared(a: Int, b: Int): Long {
        val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return dr.toLong() * dr + dg.toLong() * dg + db.toLong() * db
    }

    private fun interpolate(a: Int, b: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val r = (((a shr 16) and 0xff) * (1f - t) + ((b shr 16) and 0xff) * t).toInt()
        val g = (((a shr 8) and 0xff) * (1f - t) + ((b shr 8) and 0xff) * t).toInt()
        val blue = ((a and 0xff) * (1f - t) + (b and 0xff) * t).toInt()
        return argb(r, g, blue)
    }
}
