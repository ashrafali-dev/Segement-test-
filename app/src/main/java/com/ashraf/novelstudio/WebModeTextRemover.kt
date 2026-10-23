package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative, pixel-level remover for OCR-confirmed lettering on flat/near-flat backgrounds.
 *
 * Design rules borrowed as concepts (not copied implementation) from BubbleBlaster and PanelCleaner:
 * use OCR geometry, make a per-pixel mask instead of painting an entire text rectangle, and skip a
 * region when its foreground/background or connected-component shape is ambiguous. Complex art is
 * deliberately left for the existing overlay instead of being damaged by a guessed fill.
 *
 * This is a lightweight first-stage cleaner, not a neural inpainting model. It is intentionally
 * bounded for phones and returns false when the image is too large or the mask is unsafe.
 */
internal object WebModeTextRemover {
    private const val MAX_WIDTH = 4096
    private const val MAX_HEIGHT = 20000
    private const val MAX_PIXELS = 14_000_000L
    private const val MAX_BYTES = 18 * 1024 * 1024

    fun cleanAndCache(
        context: Context,
        sourceUrl: String,
        encoded: ByteArray,
        bubbles: List<WmBubble>
    ): Boolean {
        if (!Prefs.bool(context, "wmTextRemoval", true)) return false
        if (sourceUrl.isBlank() || encoded.size !in 1..MAX_BYTES || bubbles.isEmpty()) return false
        if (isAnimatedOrUnsupported(encoded)) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_WIDTH || height !in 1..MAX_HEIGHT ||
            width.toLong() * height.toLong() > MAX_PIXELS) return false

        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return false
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) decoded
            else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                ?: run { decoded.recycle(); return false }

        try {
            var totalRemoved = 0
            // AI inpainting (optional, downloaded on request) is slow on a phone: only a few artwork bubbles per image.
            var aiBudget = 3
            var aiWanted = Prefs.bool(context, "wmAiInpaint", false) && WebModeInpainter.isReady(context) &&
                !WebModeInpainter.tooSlow
            if (aiWanted && !WebModeInpainter.isLoaded()) {
                // Loading the model takes several seconds: do it in the background; THIS image uses the fast cleaner
                // so the page never waits for it.
                WebModeInpainter.warmUpAsync(context)
                aiWanted = false
            }
            val aiStartMs = android.os.SystemClock.elapsedRealtime()
            var aiLastMs = 0L
            // Every bubble whose lettering was really erased (flat AND artwork). The writer only drops its
            // opaque cover box for bubbles in this set; any other bubble keeps the safe box fallback.
            val erased = HashSet<String>()
            val boundedBubbles = bubbles.asSequence()
                .filter { it.sourceText.isNotBlank() && it.w > 0f && it.h > 0f }
                .take(500)
                .toList()

            for (bubble in boundedBubbles) {
                val cx = bubble.cx * width
                val cy = bubble.cy * height
                val bw = bubble.w * width
                val bh = bubble.h * height
                if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) continue
                // ox/oy = the exact OCR box (where the old lettering is).
                val ox0 = (cx - bw / 2f).toInt().coerceIn(0, width - 1)
                val oy0 = (cy - bh / 2f).toInt().coerceIn(0, height - 1)
                val ox1 = ceil(cx + bw / 2f).toInt().coerceIn(ox0 + 1, width)
                val oy1 = ceil(cy + bh / 2f).toInt().coerceIn(oy0 + 1, height)
                val innerW = ox1 - ox0
                val innerH = oy1 - oy0
                if (innerW < 4 || innerH < 4 || innerW.toLong() * innerH.toLong() > 1_250_000L) continue

                val soft = bubble.soft
                val bg = opaque(bubble.bg)
                val fg1 = opaque(bubble.fg)
                val fg2 = opaque(bubble.fg2)
                // Outline colour of fill+stroke lettering is ink too (otherwise a white edge would stay behind).
                val fg3 = if (bubble.stroke != 0) opaque(bubble.stroke) else fg2
                if (distance(bg, fg1) < 52 && distance(bg, fg2) < 52 && distance(bg, fg3) < 52) continue

                val fontPx = (bubble.font * width).takeIf { it.isFinite() && it > 0f }
                    ?.coerceIn(3f, 400f) ?: max(3f, min(innerW, innerH).toFloat() * 0.65f)

                // Analyse a region a little LARGER than the OCR box. OCR boxes are tight, so a vertical
                // Japanese column (or any dense line) used to touch the region edge and was thrown away as
                // if it were the bubble outline. With padding, real lettering sits inside, while outlines
                // still cross the edge and are rejected by the border rule below.
                val pad = (fontPx * 0.5f).toInt().coerceIn(2, 24)
                val x0 = max(0, ox0 - pad)
                val y0 = max(0, oy0 - pad)
                val x1 = min(width, ox1 + pad)
                val y1 = min(height, oy1 + pad)
                val rw = x1 - x0
                val rh = y1 - y0
                val regionSize = rw.toLong() * rh.toLong()
                if (regionSize > 2_000_000L) continue
                val regionCount = regionSize.toInt()
                // OCR box in region coordinates.
                val ix0 = ox0 - x0; val iy0 = oy0 - y0
                val ix1 = ox1 - x0; val iy1 = oy1 - y0
                val innerArea = innerW.toLong() * innerH.toLong()

                // Only this small region is copied out of the bitmap (not the whole page), so tall
                // webtoon strips fit in memory.
                val region = IntArray(regionCount)
                bitmap.getPixels(region, 0, rw, x0, y0, rw, rh)

                // Size limits for ONE connected shape. On artwork they stay strict (line art survives).
                // On flat bubbles a whole touching column/line of glyphs is allowed, up to the OCR box size.
                val maxComponentW = if (soft) max(8, min(innerW + 4, (fontPx * 16f).toInt())) else innerW + 4
                val maxComponentH = if (soft) max(8, min(innerH + 4, (fontPx * 16f).toInt())) else innerH + 4
                val maxComponentPixels = if (soft) max(18, (innerArea * 0.4).toInt())
                    else (innerArea * 0.7).toInt().coerceAtLeast(18)

                val candidate = ByteArray(regionCount)
                val seen = ByteArray(regionCount)
                val selected = ByteArray(regionCount)
                // Shapes that are certainly NOT lettering (outline / other bubble): the sweep below never touches them.
                val protect = ByteArray(regionCount)
                val hasStroke = bubble.stroke != 0
                val core = if (hasStroke) ByteArray(regionCount) else null
                val strokeCand = if (hasStroke) ByteArray(regionCount) else null
                for (i in 0 until regionCount) {
                    val p = region[i]
                    val fromBg = distance(p, bg)
                    if (fromBg < (if (soft) 52f else 46f)) continue
                    val thr = if (soft) 78f else max(82f, fromBg * 1.08f)
                    // Include anti-aliased letter edges, but require a real contrast from the bubble fill.
                    // On artwork (soft) only pixels that are clearly the lettering colour qualify, so line art survives.
                    if (min(distance(p, fg1), distance(p, fg2)) <= thr) {
                        candidate[i] = 1
                        if (core != null) core[i] = 1
                    } else if (strokeCand != null && distance(p, fg3) <= thr) {
                        strokeCand[i] = 1
                    }
                }
                // The OUTLINE colour (often white/grey) also occurs all over the background (sky, walls, windows).
                // It only counts where it hugs the lettering's own fill: within a stroke width of a fill pixel.
                if (core != null && strokeCand != null) {
                    val rad = (fontPx * 0.12f).toInt().coerceIn(2, 8)
                    val tmp = ByteArray(regionCount)
                    for (ry in 0 until rh) for (rx in 0 until rw) {
                        if (core[ry * rw + rx].toInt() == 0) continue
                        for (dx in -rad..rad) {
                            val nx = rx + dx
                            if (nx in 0 until rw) tmp[ry * rw + nx] = 1
                        }
                    }
                    for (ry in 0 until rh) for (rx in 0 until rw) {
                        if (tmp[ry * rw + rx].toInt() == 0) continue
                        for (dy in -rad..rad) {
                            val ny = ry + dy
                            if (ny in 0 until rh && strokeCand[ny * rw + rx].toInt() != 0) candidate[ny * rw + rx] = 1
                        }
                    }
                }

                val queue = IntArray(regionCount)
                var selectedCount = 0
                for (start in 0 until regionCount) {
                    if (candidate[start].toInt() == 0 || seen[start].toInt() != 0) continue
                    var head = 0
                    var tail = 0
                    queue[tail++] = start
                    seen[start] = 1
                    var minX = rw; var minY = rh; var maxX = -1; var maxY = -1
                    var componentPixels = 0
                    var insidePixels = 0
                    while (head < tail) {
                        val at = queue[head++]
                        val ry = at / rw
                        val rx = at - ry * rw
                        minX = min(minX, rx); maxX = max(maxX, rx)
                        minY = min(minY, ry); maxY = max(maxY, ry)
                        componentPixels++
                        if (rx in ix0 until ix1 && ry in iy0 until iy1) insidePixels++
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = rx + dx; val ny = ry + dy
                            if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                            val ni = ny * rw + nx
                            if (candidate[ni].toInt() != 0 && seen[ni].toInt() == 0) {
                                seen[ni] = 1
                                queue[tail++] = ni
                            }
                        }
                    }
                    val cw = maxX - minX + 1
                    val ch = maxY - minY + 1
                    // A shape that mostly lies OUTSIDE the OCR box belongs to something else (neighbouring
                    // bubble text, outline, art): never touch it.
                    if (insidePixels * 2 < componentPixels) {
                        for (i in 0 until tail) protect[queue[i]] = 1
                        continue
                    }
                    // A big shape that reaches two sides of the region is the bubble outline, not lettering.
                    val touches = (if (minX == 0) 1 else 0) + (if (maxX == rw - 1) 1 else 0) +
                        (if (minY == 0) 1 else 0) + (if (maxY == rh - 1) 1 else 0)
                    if (touches >= 2 && (cw > fontPx * 2.2f || ch > fontPx * 2.2f)) {
                        for (i in 0 until tail) protect[queue[i]] = 1
                        continue
                    }
                    // Large lines, bubble contours and art strokes are ambiguous: keep them.
                    if (componentPixels < max(3, (fontPx * 0.06f).toInt()) ||
                        cw > maxComponentW || ch > maxComponentH ||
                        componentPixels > maxComponentPixels) continue
                    for (i in 0 until tail) selected[queue[i]] = 1
                    selectedCount += componentPixels
                }

                // If the inferred mask covers too much of the OCR box, it is probably art rather than text.
                // Dense bold CJK glyphs legitimately hold more ink than Latin text.
                if (selectedCount < 4 || selectedCount > innerArea * (if (soft) 0.5 else 0.6)) continue

                // Flat bubble: sweep EVERYTHING inside the OCR box (slightly widened) that differs from the paper
                // colour, unless it belongs to a protected shape. This removes the faint ghosts that the strict
                // glyph test leaves behind: anti-aliasing, JPEG ringing, halos, thin strokes, shapes rejected by size.
                if (!soft) {
                    val m = (fontPx * 0.12f).toInt().coerceIn(1, 4)
                    val sx0 = max(0, ix0 - m); val sy0 = max(0, iy0 - m)
                    val sx1 = min(rw, ix1 + m); val sy1 = min(rh, iy1 + m)
                    for (ry in sy0 until sy1) for (rx in sx0 until sx1) {
                        val i = ry * rw + rx
                        if (selected[i].toInt() == 0 && protect[i].toInt() == 0 &&
                            distance(region[i], bg) >= 9f) selected[i] = 1
                    }
                }

                // Grow the mask a little: letter edges (anti-aliasing, JPEG ringing, faint halo) are lighter than
                // the core pixels and used to stay as a ghost of the old text behind the translation.
                val grow = if (soft) (fontPx * 0.14f).toInt().coerceIn(2, 6) else (fontPx * 0.07f).toInt().coerceIn(1, 3) + 1
                val mask = selected.copyOf()
                for (ry in 0 until rh) {
                    for (rx in 0 until rw) {
                        if (selected[ry * rw + rx].toInt() == 0) continue
                        for (dy in -grow..grow) {
                            val ny = ry + dy
                            if (ny < 0 || ny >= rh) continue
                            for (dx in -grow..grow) {
                                val nx = rx + dx
                                if (nx < 0 || nx >= rw) continue
                                val ni = ny * rw + nx
                                if (mask[ni].toInt() == 0 && protect[ni].toInt() == 0 &&
                                    distance(region[ni], bg) >= 8f) mask[ni] = 1
                            }
                        }
                    }
                }

                // Lettering on artwork: let the AI model rebuild the picture when it is installed and enabled.
                var aiFill: IntArray? = null
                // Hard time cap: no new AI call if it would probably push this image past ~8 s in total.
                if (soft && aiWanted && aiBudget > 0 &&
                    (android.os.SystemClock.elapsedRealtime() - aiStartMs) + aiLastMs < 8000L) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    aiFill = try { WebModeInpainter.fill(context, bitmap, x0, y0, rw, rh, mask) } catch (_: Throwable) { null }
                    aiLastMs = android.os.SystemClock.elapsedRealtime() - t0
                    if (aiFill != null) aiBudget--
                }
                // Artwork without AI: onion-peel (Telea-style) fill, outside in, layer by layer.
                val tele = if (soft && aiFill == null)
                    onionFill(region, mask, rw, rh, (fontPx * 0.3f).toInt().coerceIn(4, 10), bg) else null
                // Fill only the masked glyph pixels from their surroundings (never repaint a whole box).
                val out = region.copyOf()
                for (ry in 0 until rh) {
                    for (rx in 0 until rw) {
                        val at = ry * rw + rx
                        if (mask[at].toInt() == 0) continue
                        if (soft) {
                            // Lettering on artwork: rebuild the picture from the surroundings (sky, shading, screentone
                            // gradients) by blending the nearest untouched pixels left/right/up/down, nearest weighted most.
                            out[at] = if (aiFill != null) (0xFF000000.toInt() or (aiFill[at] and 0xFFFFFF))
                                else if (tele != null) (0xFF000000.toInt() or (tele[at] and 0xFFFFFF))
                                else 0xFF000000.toInt() or
                                    gradientFill(region, mask, rw, rh, rx, ry, (fontPx * 1.6f).toInt().coerceIn(8, 60), bg)
                            continue
                        }
                        var sampleN = 0
                        var sumR = 0; var sumG = 0; var sumB = 0
                        var radius = 1
                        while (radius <= 9 && sampleN < 6) {
                            for (dy in -radius..radius) {
                                for (dx in -radius..radius) {
                                    if (max(abs(dx), abs(dy)) != radius) continue
                                    val nx = rx + dx; val ny = ry + dy
                                    if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                                    val ni = ny * rw + nx
                                    if (mask[ni].toInt() != 0) continue
                                    val p = region[ni]
                                    if (distance(p, bg) > 72f) continue
                                    sumR += red(p); sumG += green(p); sumB += blue(p); sampleN++
                                }
                            }
                            radius++
                        }
                        val fill = if (sampleN >= 3) {
                            (sumR / sampleN shl 16) or (sumG / sampleN shl 8) or (sumB / sampleN)
                        } else bg and 0x00FFFFFF
                        out[at] = 0xFF000000.toInt() or fill
                    }
                }
                bitmap.setPixels(out, 0, rw, x0, y0, rw, rh)
                totalRemoved += selectedCount
                erased.add(WebModeTextRemovalCache.bubbleKey(bubble))
            }
            if (totalRemoved < 8) return false

            val bytesOut = ByteArrayOutputStream()
            // API 30+ supports lossless WebP. Older Android versions use lossless PNG instead
            // of re-encoding the whole comic page with lossy WebP.
            val modern = Build.VERSION.SDK_INT >= 30
            val format = if (modern) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            if (!bitmap.compress(format, 100, bytesOut)) return false
            val cleaned = bytesOut.toByteArray()
            if (cleaned.isEmpty() || cleaned.size > MAX_BYTES) return false
            val stored = WebModeTextRemovalCache.store(context, sourceUrl, cleaned,
                if (modern) "image/webp" else "image/png")
            if (stored) WebModeTextRemovalCache.setErased(sourceUrl, erased)
            return stored
        } catch (_: OutOfMemoryError) {
            return false
        } catch (_: Exception) {
            return false
        } finally {
            bitmap.recycle()
        }
    }

    /** Colour for one erased pixel on artwork: inverse-distance blend of the nearest kept pixel in each of 8 directions (1/d² weights: no streaks, no pulling in far colours). */
    private fun gradientFill(
        region: IntArray, mask: ByteArray, rw: Int, rh: Int,
        rx: Int, ry: Int, maxScan: Int, fallback: Int
    ): Int {
        var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
        for (dir in 0 until 8) {
            val dx = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)[dir]
            val dy = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)[dir]
            var d = 1
            while (d <= maxScan) {
                val nx = rx + dx * d
                val ny = ry + dy * d
                if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) break
                if (mask[ny * rw + nx].toInt() == 0) {
                    val p = region[ny * rw + nx]
                    val wgt = 1f / (d * d)
                    sr += red(p) * wgt; sg += green(p) * wgt; sb += blue(p) * wgt; sw += wgt
                    break
                }
                d++
            }
        }
        if (sw <= 0f) return fallback and 0x00FFFFFF
        val r = (sr / sw).toInt().coerceIn(0, 255)
        val g = (sg / sw).toInt().coerceIn(0, 255)
        val b = (sb / sw).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private class IntBuf {
        var a = IntArray(64)
        var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    }

    /**
     * Onion-peel inpainting (the idea behind Telea's method, without a priority queue): the hole is filled from its
     * border inwards. Each round fills the pixels that touch known picture; a pixel becomes the inverse-square-distance
     * weighted average of the known pixels within [radius]. A round reads only what was known when it started, so the
     * result has no scan-direction streaks. Anything that still cannot be reached gets [fallback].
     */
    private fun onionFill(region: IntArray, mask: ByteArray, rw: Int, rh: Int, radius: Int, fallback: Int): IntArray {
        val total = rw * rh
        val out = region.copyOf()
        val known = BooleanArray(total) { mask[it].toInt() == 0 }
        val queued = BooleanArray(total)
        val colours = IntArray(total)
        var frontier = IntBuf()
        for (y in 0 until rh) for (x in 0 until rw) {
            val i = y * rw + x
            if (known[i]) continue
            val touches = (x > 0 && known[i - 1]) || (x < rw - 1 && known[i + 1]) ||
                (y > 0 && known[i - rw]) || (y < rh - 1 && known[i + rw])
            if (touches) { frontier.add(i); queued[i] = true }
        }
        var r = radius
        var stalls = 0
        while (frontier.n > 0) {
            val r2 = r * r
            val filled = IntBuf()
            val pending = IntBuf()
            for (k in 0 until frontier.n) {
                val i = frontier.a[k]
                val y = i / rw
                val x = i - y * rw
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (dy in -r..r) {
                    val ny = y + dy
                    if (ny < 0 || ny >= rh) continue
                    for (dx in -r..r) {
                        val nx = x + dx
                        if (nx < 0 || nx >= rw) continue
                        val d2 = dx * dx + dy * dy
                        if (d2 == 0 || d2 > r2) continue
                        val ni = ny * rw + nx
                        if (!known[ni]) continue
                        val p = out[ni]
                        val w = 1f / d2
                        sr += ((p shr 16) and 255) * w
                        sg += ((p shr 8) and 255) * w
                        sb += (p and 255) * w
                        sw += w
                    }
                }
                if (sw > 0f) {
                    colours[i] = (0xFF shl 24) or ((sr / sw).toInt().coerceIn(0, 255) shl 16) or
                        ((sg / sw).toInt().coerceIn(0, 255) shl 8) or (sb / sw).toInt().coerceIn(0, 255)
                    filled.add(i)
                } else {
                    pending.add(i)
                }
            }
            if (filled.n == 0) {
                // nothing reachable at this radius: look further a few times, then give up
                if (++stalls > 3) break
                r *= 2
                continue
            }
            stalls = 0
            for (k in 0 until filled.n) {
                val i = filled.a[k]
                out[i] = colours[i]
                known[i] = true
            }
            val next = IntBuf()
            for (k in 0 until pending.n) next.add(pending.a[k])
            for (k in 0 until filled.n) {
                val i = filled.a[k]
                val y = i / rw
                val x = i - y * rw
                if (x > 0 && !known[i - 1] && !queued[i - 1]) { queued[i - 1] = true; next.add(i - 1) }
                if (x < rw - 1 && !known[i + 1] && !queued[i + 1]) { queued[i + 1] = true; next.add(i + 1) }
                if (y > 0 && !known[i - rw] && !queued[i - rw]) { queued[i - rw] = true; next.add(i - rw) }
                if (y < rh - 1 && !known[i + rw] && !queued[i + rw]) { queued[i + rw] = true; next.add(i + rw) }
            }
            frontier = next
        }
        // whatever is still unknown gets the fallback colour
        for (i in 0 until total) if (!known[i]) out[i] = 0xFF000000.toInt() or (fallback and 0xFFFFFF)
        return out
    }

    private fun isAnimatedOrUnsupported(bytes: ByteArray): Boolean {
        if (bytes.size >= 6) {
            val header = String(bytes, 0, 6, Charsets.US_ASCII)
            if (header == "GIF87a" || header == "GIF89a") return true
        }
        if (bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") {
            val marker = String(bytes, 12, min(4, bytes.size - 12), Charsets.US_ASCII)
            if (marker == "ANIM" || marker == "ANMF") return true
        }
        return false
    }

    private fun opaque(color: Int): Int = color or (0xFF000000.toInt())
    private fun red(color: Int) = (color ushr 16) and 0xFF
    private fun green(color: Int) = (color ushr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF

    private fun distance(a: Int, b: Int): Float {
        return (abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))) / 3f
    }

}
