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

    /**
     * Largest working area for ONE bubble while erasing (~500k px ≈ 2 MB of masks). Above this the box is not a
     * single line of lettering but a mis-grouped region, and the bounded processing rule applies instead of
     * allocating a multi-megabyte scratch set next to the other worker's.
     */
    private const val MAX_REGION_PIXELS = 500_000L

    /**
     * Fair, serialized cleanup queue. Skipping a busy slot left OCR text under translations; waiting here is safe
     * because callers run on the OCR worker, not the Android UI thread, and keeps only one decoded panel alive.
     */
    private val cleanSlot = java.util.concurrent.locks.ReentrantLock(true)

    /**
     * Erase lettering using the DETECTOR's boxes instead of the pixel guess.
     *
     * For every `text_bubble` detection the bubble's real fill colour is sampled from the ring between the text
     * box and the bubble outline (median per channel, not mean — one dark glyph pixel must not tint it), and that
     * area is filled. `text_free` lettering keeps its own box and is filled from its local background. This is
     * the pass that removes the original Japanese/Korean text on artwork and inside bubbles the pixel finder
     * could not resolve.
     *
     * @return number of pixels changed.
     */
    fun eraseDetected(
        bitmap: Bitmap,
        dets: List<BubbleDetectorCore.Det>?,
        recognizedLines: List<OcrBlock> = emptyList(),
        onOcrRegionErased: ((OcrBlock) -> Unit)? = null,
        /**
         * Optional existing AI inpainter, applied ONLY when the paper around an OCR line is too complex for the
         * local fill to reconstruct safely. Signature matches [WebModeInpainter.fill]: region + region-local mask,
         * returns the filled region or null. Null (the default) keeps the pure local fill.
         *
         * Callers that want AI pass `WebModeInpainter::fill` bound to their context, after doing the same
         * availability gate the full cleaner uses. Callers that must not warm the model (the mask-only pass, the
         * manual crop) leave it null; a flat balloon is reconstructed exactly either way, so nothing is lost.
         */
        inpaint: ((x0: Int, y0: Int, rw: Int, rh: Int, mask: ByteArray) -> IntArray?)? = null
    ): Int {
        if (dets.isNullOrEmpty()) return 0
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return 0
        var changed = 0

        // 1) Koharu's dialogue_text instances: associate by mask coverage, not by two box corners.
        // Rounded/cloud balloons often place a tight text box partly over the contour even when most of
        // the text is inside. Fill remains clipped to the balloon mask so neighbouring art/whispers survive.
        val balloonMasks = dets.filter {
            it.label == BubbleDetectorCore.CLS_BUBBLE && it.mask != null
        }
        for (text in dets.filter {
            it.label == BubbleDetectorCore.CLS_TEXT_BUBBLE && it.mask != null
        }) {
            val outline = balloonMasks.asSequence()
                .filter { maskCoverage(it, text) >= 0.40f }
                .minByOrNull { it.area }

            // Koharu's class-1 mask is its direct dialogue-text segmentation. Erase that glyph mask first,
            // clipping to the balloon silhouette when known. Do not replace it with a broad rectangle or
            // depend on a uniform ring colour; both can leave Korean strokes behind on shaded paper.
            val modelMaskPixels = eraseTextInstance(
                bitmap, text, width, height, padding = 2, clipTo = outline
            )
            changed += modelMaskPixels
            // Don't remove the opaque cover because a few mask pixels changed; require meaningful coverage
            // of this text instance before treating the source lettering as fully erased.
            val minimumTextEvidence = max(24, (text.w.toLong() * text.h * 6L / 100L).toInt())
            if (modelMaskPixels >= minimumTextEvidence) {
                notifyErasedOcrLines(text, recognizedLines, onOcrRegionErased)
                continue
            }

            // Conservative fallback for a missing/degenerate text mask. It still detects only ink inside the
            // actual balloon and never paints outside the model silhouette.
            if (outline == null) continue
            val fill = medianRingColour(bitmap, outline, text, width, height) ?: continue
            val l = (text.l - 2).coerceAtLeast(0)
            val t = (text.t - 2).coerceAtLeast(0)
            val r = (text.r + 2).coerceAtMost(width)
            val b = (text.b + 2).coerceAtMost(height)
            val fallbackPixels = fillMaskedRect(bitmap, outline, l, t, r, b, fill, width, height)
            changed += fallbackPixels
            if (fallbackPixels >= max(24, (text.w.toLong() * text.h * 6L / 100L).toInt()))
                notifyErasedOcrLines(text, recognizedLines, onOcrRegionErased)
        }

        // 2) Koharu's onomatopoeia_text instances: the mask here IS the lettering, so erase its shape and
        // restore each pixel from the surrounding picture (flat colour or local ramp), never one flat patch.
        for (free in dets.filter {
            it.label == BubbleDetectorCore.CLS_TEXT_FREE && it.mask != null
        }) {
            val pixels = eraseTextInstance(bitmap, free, width, height, padding = 2)
            changed += pixels
            if (pixels >= max(24, (free.w.toLong() * free.h * 6L / 100L).toInt()))
                notifyErasedOcrLines(free, recognizedLines, onOcrRegionErased)
        }
        // Older detectors may provide text boxes without instance masks; preserve their compatible fallback.
        for (free in dets.filter {
            it.label == BubbleDetectorCore.CLS_TEXT_FREE && it.mask == null
        }) {
            val pad = max(4, min(free.w, free.h) / 4).coerceAtMost(24)
            val asBubble = BubbleDetectorCore.Det(
                BubbleDetectorCore.CLS_BUBBLE, 1f,
                (free.l - pad).coerceAtLeast(0), (free.t - pad).coerceAtLeast(0),
                (free.r + pad).coerceAtMost(width), (free.b + pad).coerceAtMost(height)
            )
            val ring = medianRingColour(bitmap, asBubble, free, width, height)
            changed += if (ring != null) fillRect(bitmap, free.l, free.t, free.r, free.b, ring, width, height)
            else gradientFillRect(bitmap, free.l, free.t, free.r, free.b, width, height)
        }

        // The replacement YOLO model predicts balloon masks only (it has no text_bubble/text_free classes). Manual
        // extraction already has OCR line boxes, so use those lines as the text regions and the real segmentation
        // mask as the trusted background sampling area. This keeps flat white lettering removable without guessing
        // from the balloon's full rectangular bounding box.
        if (recognizedLines.isNotEmpty()) {
            val maskedBalloons = dets.filter { it.label == BubbleDetectorCore.CLS_BUBBLE && it.mask != null }
            for (line in recognizedLines) {
                if (line.w < 2f || line.h < 2f || line.text.isBlank()) continue
                val cx = line.cx
                val cy = line.cy
                val outer = maskedBalloons.asSequence()
                    .filter { it.contains(cx, cy) }
                    .minByOrNull { it.area } ?: continue

                // Expand tight OCR boxes just enough to catch anti-aliased glyph edges. The model mask
                // below clips the fill to the actual balloon silhouette, so this margin cannot paint outside it.
                val px = (line.fontPx * 0.08f).toInt().coerceIn(1, 4)
                val py = (line.fontPx * 0.12f).toInt().coerceIn(1, 5)
                val rawL = (cx - line.w / 2f).toInt() - px
                val rawT = (cy - line.h / 2f).toInt() - py
                val rawR = (cx + line.w / 2f).toInt() + px
                val rawB = (cy + line.h / 2f).toInt() + py
                val l = max(rawL, outer.l).coerceIn(0, width - 1)
                val t = max(rawT, outer.t).coerceIn(0, height - 1)
                val r = min(rawR, outer.r).coerceIn(l + 1, width)
                val b = min(rawB, outer.b).coerceIn(t + 1, height)
                if (r - l < 2 || b - t < 2 || (r - l).toLong() * (b - t) > 1_250_000L) continue

                val inner = BubbleDetectorCore.Det(BubbleDetectorCore.CLS_TEXT_BUBBLE, 1f, l, t, r, b)
                // A line centre is already inside this instance mask. Curved balloons can leave rounded
                // OCR-box corners outside the mask; allow those because pixels are clipped to the mask below.
                if (maskCoverage(outer, inner) < 0.36f) continue
                val fill = medianRingColour(bitmap, outer, inner, width, height) ?: continue
                val regionChanged = fillMaskedRect(bitmap, outer, l, t, r, b, fill, width, height, inpaint)
                changed += regionChanged
                val minimumLineEvidence = max(24, (line.w.toLong() * line.h * 6L / 100L).toInt())
                if (regionChanged >= minimumLineEvidence) onOcrRegionErased?.invoke(line)
            }
        }
        return changed
    }

    /**
     * Erase ONE OCR region that sits inside a balloon, without painting over the balloon itself.
     *
     * The model's instance mask is the balloon SILHOUETTE. It says where the balloon is; it does NOT say which
     * pixels are glyphs. Filling every masked pixel - what this used to do - re-tinted the whole balloon with the
     * sampled colour, which on a curved, shaded or outlined balloon reads as a rectangular patch.
     *
     * So: the model mask decides WHERE work may happen, the region's own paper colour decides WHAT is lettering,
     * and the ink is restored from the surrounding paper.
     */
    private fun fillMaskedRect(
        bitmap: Bitmap,
        balloon: BubbleDetectorCore.Det,
        l: Int, t: Int, r: Int, b: Int,
        colour: Int,
        width: Int, height: Int,
        inpaint: ((x0: Int, y0: Int, rw: Int, rh: Int, mask: ByteArray) -> IntArray?)? = null
    ): Int {
        if (balloon.mask == null || r <= l || b <= t) return 0
        val x0 = l.coerceIn(0, width - 1)
        val y0 = t.coerceIn(0, height - 1)
        val x1 = r.coerceIn(x0 + 1, width)
        val y1 = b.coerceIn(y0 + 1, height)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0 || w.toLong() * h > 1_250_000L) return 0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        val mask = TextRemovalMask.inkMask(pixels, w, h, 0, 0, w, h, colour) { xx, yy ->
            // Model silhouette only: pixels outside it are never touched, so artwork under a rounded edge and any
            // whisper text outside the balloon stay intact.
            balloon.contains(x0 + xx + 0.5f, y0 + yy + 0.5f)
        } ?: return 0
        // A flat balloon is reconstructed exactly from its own paper. Only when that paper is COMPLEX (artwork,
        // heavy shading, colour noise) is the picture otherwise smeared, and only then does the existing
        // inpainter run - on this one text region, never on the whole balloon.
        val ai = if (inpaint != null && !TextRemovalMask.paperIsFlat(mask, pixels, w, h)) {
            try { inpaint(x0, y0, w, h, mask) } catch (_: Throwable) { null }
        } else null
        val restored = ai?.takeIf { it.size == pixels.size }?.let { fill ->
            // The inpainter returns a full region; keep it only on masked pixels, rest untouched.
            IntArray(pixels.size) { i -> if (mask[i].toInt() != 0) (0xFF000000.toInt() or (fill[i] and 0xFFFFFF)) else pixels[i] }
        } ?: TextRemovalMask.restoreFromSurroundings(mask, pixels, w, h, maxRun = 48)
        var changed = 0
        for (i in pixels.indices) {
            if (mask[i].toInt() == 0) continue
            if (restored[i] != pixels[i]) changed++
            pixels[i] = restored[i]
        }
        if (changed > 0) bitmap.setPixels(pixels, 0, w, x0, y0, w, h)
        return changed
    }

    /**
     * Erase the model's segmented TEXT mask (dialogue_text / onomatopoeia_text plus a tiny anti-alias margin).
     * This is the one place where the mask really is lettering, so the mask itself is the erase shape; the fill
     * still comes from the surrounding pixels, never from one flat colour.
     */
    private fun eraseTextInstance(
        bitmap: Bitmap,
        instance: BubbleDetectorCore.Det,
        width: Int,
        height: Int,
        padding: Int,
        clipTo: BubbleDetectorCore.Det? = null
    ): Int = editInstanceMask(bitmap, instance, width, height, padding) { pixels, w, h, globalL, globalT ->
        val mask = ByteArray(w * h)
        var ink = 0
        for (yy in 0 until h) for (xx in 0 until w) {
            val gx = globalL + xx + 0.5f
            val gy = globalT + yy + 0.5f
            // Padding removes antialiased fringe, but the detected balloon silhouette is a hard boundary.
            // No cleaned pixel is allowed to damage its outline or nearby whisper/artwork.
            if ((clipTo == null || clipTo.contains(gx, gy)) &&
                instanceMaskNear(instance, gx, gy, padding)) {
                mask[yy * w + xx] = 1
                ink++
            }
        }
        // Almost the whole box is mask: that is a filled rectangle, not lettering. Leave the picture alone.
        if (ink < 4 || ink > w * h * 0.9f) return@editInstanceMask 0
        val restored = TextRemovalMask.restoreFromSurroundings(mask, pixels, w, h, maxRun = 32)
        var changed = 0
        for (i in pixels.indices) {
            if (mask[i].toInt() == 0) continue
            if (restored[i] != pixels[i]) changed++
            pixels[i] = restored[i]
        }
        changed
    }

    /**
     * Mark a source OCR line as erased only when this model text-instance box covers a useful share of the line.
     * This prevents a partial word mask from telling the overlay to remove its remaining cover.
     */
    private fun notifyErasedOcrLines(
        instance: BubbleDetectorCore.Det,
        recognizedLines: List<OcrBlock>,
        callback: ((OcrBlock) -> Unit)?
    ) {
        if (callback == null) return
        for (line in recognizedLines) {
            if (line.w <= 0f || line.h <= 0f) continue
            val l = line.cx - line.w / 2f
            val t = line.cy - line.h / 2f
            val r = line.cx + line.w / 2f
            val b = line.cy + line.h / 2f
            val iw = (minOf(r, instance.r.toFloat()) - maxOf(l, instance.l.toFloat())).coerceAtLeast(0f)
            val ih = (minOf(b, instance.b.toFloat()) - maxOf(t, instance.t.toFloat())).coerceAtLeast(0f)
            val lineArea = line.w * line.h
            // Prefer a conservative false negative over transparent text with a few source glyphs still visible.
            // The caller keeps an opaque shaped cover when the model mask only explains a minority of this OCR line.
            if (iw * ih >= lineArea * 0.80f) callback(line)
        }
    }

    private inline fun editInstanceMask(
        bitmap: Bitmap,
        instance: BubbleDetectorCore.Det,
        width: Int,
        height: Int,
        padding: Int,
        edit: (pixels: IntArray, w: Int, h: Int, globalL: Int, globalT: Int) -> Int
    ): Int {
        if (instance.mask == null || instance.r <= instance.l || instance.b <= instance.t) return 0
        val pad = padding.coerceIn(0, 4)
        val left = (instance.l - pad).coerceIn(0, width - 1)
        val top = (instance.t - pad).coerceIn(0, height - 1)
        val right = (instance.r + pad).coerceIn(left + 1, width)
        val bottom = (instance.b + pad).coerceIn(top + 1, height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0 || w.toLong() * h > 350_000L) return 0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        val changed = edit(pixels, w, h, left, top)
        if (changed > 0) bitmap.setPixels(pixels, 0, w, left, top, w, h)
        return changed
    }

    private fun instanceMaskNear(
        instance: BubbleDetectorCore.Det,
        x: Float,
        y: Float,
        padding: Int
    ): Boolean {
        if (instance.contains(x, y)) return true
        if (padding <= 0) return false
        // Test a cross of neighbouring samples; this dilates only the binary instance mask by a few pixels.
        return instance.contains(x - padding, y) || instance.contains(x + padding, y) ||
            instance.contains(x, y - padding) || instance.contains(x, y + padding)
    }

    /** Fraction of a text box that actually falls inside the instance mask. */
    private fun maskCoverage(
        balloon: BubbleDetectorCore.Det,
        text: BubbleDetectorCore.Det
    ): Float {
        if (balloon.mask == null) return 0f
        var inside = 0
        val grid = 5
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val x = text.l + (gx + 0.5f) * text.w / grid
            val y = text.t + (gy + 0.5f) * text.h / grid
            if (balloon.contains(x, y)) inside++
        }
        return inside.toFloat() / (grid * grid)
    }

    /** Median colour of the ring between [inner] and [outer]; null when the ring is too textured to trust. */
    private fun medianRingColour(
        bitmap: Bitmap, outer: BubbleDetectorCore.Det, inner: BubbleDetectorCore.Det, width: Int, height: Int
    ): Int? {
        val pad = 2
        val stepX = ((outer.w / 24).coerceAtLeast(1))
        val stepY = ((outer.h / 24).coerceAtLeast(1))
        val rs = ArrayList<Int>(1024); val gs = ArrayList<Int>(1024); val bs = ArrayList<Int>(1024)
        // Read the whole outer box in ONE getPixels call. getPixel() per sample was ~1 JNI round trip per ring
        // pixel on every bubble, which dominated realtime cleaning time on low-end phones.
        val bw = (outer.r - outer.l).coerceAtLeast(1)
        val bh = (outer.b - outer.t).coerceAtLeast(1)
        if (bw <= 0 || bh <= 0 || bw.toLong() * bh.toLong() > MAX_REGION_PIXELS) return null
        val box = IntArray(bw * bh)
        bitmap.getPixels(box, 0, bw, outer.l, outer.t, bw, bh)
        var yy = outer.t + pad
        while (yy < outer.b - pad) {
            var xx = outer.l + pad
            while (xx < outer.r - pad) {
                val inside = xx >= inner.l - pad && xx < inner.r + pad && yy >= inner.t - pad && yy < inner.b + pad
                if (!inside && xx in 0 until width && yy in 0 until height &&
                    (outer.mask == null || outer.contains(xx.toFloat(), yy.toFloat()))) {
                    val c = box[(yy - outer.t) * bw + (xx - outer.l)]
                    rs.add((c shr 16) and 0xFF); gs.add((c shr 8) and 0xFF); bs.add(c and 0xFF)
                }
                xx += stepX
            }
            yy += stepY
        }
        if (rs.size < 24) return null
        rs.sort(); gs.sort(); bs.sort()
        val mid = rs.size / 2
        val r = rs[mid]; val g = gs[mid]; val b = bs[mid]
        // Reject a ring dominated by artwork (no single dominant colour): leave it to the caller's fallback.
        var agreeing = 0
        for (i in rs.indices) {
            if (Math.abs(rs[i] - r) + Math.abs(gs[i] - g) + Math.abs(bs[i] - b) <= 60) agreeing++
        }
        if (agreeing * 100 < rs.size * 70) return null
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /**
     * Shape-correct cover rectangles for the readable balloons of one picture.
     *
     * The overlay used to cover a bubble the cleaner could not erase with ONE rectangle over the balloon box,
     * which is the box-shaped patch reported on curved/cloud balloons. These bands come from the model's own
     * silhouette and only cover pixels whose colour is the balloon's paper, so the cover follows the contour.
     *
     * @return flat [l, t, r, b] rectangles in image pixels; empty when no balloon has a flat interior.
     */
    fun coverBandsFor(
        bitmap: Bitmap,
        dets: List<BubbleDetectorCore.Det>?,
        maxBands: Int = 48
    ): List<IntArray> {
        if (dets.isNullOrEmpty()) return emptyList()
        val balloons = dets.filter { it.label == BubbleDetectorCore.CLS_BUBBLE && it.mask != null }
        if (balloons.isEmpty()) return emptyList()
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return emptyList()
        val out = ArrayList<IntArray>()
        for (d in balloons) {
            if (out.size >= maxBands) break
            val bands = try { interiorBandsOf(bitmap, d, width, height) } catch (_: Throwable) { emptyList() }
            for (b in bands) {
                if (out.size >= maxBands) break
                out.add(b)
            }
        }
        return out
    }

    /** Interior bands of ONE balloon, reading only that balloon's box (a tall page never enters memory). */
    private fun interiorBandsOf(
        bitmap: Bitmap, d: BubbleDetectorCore.Det, width: Int, height: Int
    ): List<IntArray> {
        val x0 = d.l.coerceIn(0, width - 1)
        val y0 = d.t.coerceIn(0, height - 1)
        val x1 = d.r.coerceIn(x0 + 1, width)
        val y1 = d.b.coerceIn(y0 + 1, height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 16 || h < 16 || w.toLong() * h > 4_000_000L) return emptyList()
        val box = IntArray(w * h)
        bitmap.getPixels(box, 0, w, x0, y0, w, h)
        val local = BubbleDetectorCore.Det(
            d.label, d.score, d.l - x0, d.t - y0, d.r - x0, d.b - y0, d.mask
        )
        return TextRemovalMask.interiorBands(local, box, w, h).map { b ->
            intArrayOf(b[0] + x0, b[1] + y0, b[2] + x0, b[3] + y0)
        }
    }

    /** Fill a rectangle with [colour], leaving pixels that already match it untouched. Returns pixels changed. */
    private fun fillRect(
        bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int, colour: Int, width: Int, height: Int
    ): Int {
        val x0 = l.coerceIn(0, width - 1); val x1 = r.coerceIn(x0 + 1, width)
        val y0 = t.coerceIn(0, height - 1); val y1 = b.coerceIn(y0 + 1, height)
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0 || w.toLong() * h > 2_000_000L) return 0
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, x0, y0, w, h)
        var changed = 0
        for (i in px.indices) {
            if (px[i] != colour) { px[i] = colour; changed++ }
        }
        if (changed > 0) bitmap.setPixels(px, 0, w, x0, y0, w, h)
        return changed
    }

    /** Repaint a rectangle from its immediate surroundings (artwork / gradient), ring by ring outside in. */
    private fun gradientFillRect(
        bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int, width: Int, height: Int
    ): Int {
        val x0 = l.coerceIn(0, width - 1); val x1 = r.coerceIn(x0 + 1, width)
        val y0 = t.coerceIn(0, height - 1); val y1 = b.coerceIn(y0 + 1, height)
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0 || w.toLong() * h > 1_500_000L) return 0
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, x0, y0, w, h)
        val out = px.copyOf()
        // For each pixel, blend the nearest untouched pixel to the left/right/up/down (1/d weighting).
        for (yy in 0 until h) for (xx in 0 until w) {
            var sr = 0.0; var sg = 0.0; var sb = 0.0; var sw = 0.0
            for ((dx, dy) in arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                var d = 1
                while (d <= 40) {
                    val nx = xx + dx * d; val ny = yy + dy * d
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) break
                    // Only accept a pixel outside the erased band.
                    if (nx in 0 until w && ny in 0 until h) {
                        val c = px[ny * w + nx]
                        val wt = 1.0 / (d * d)
                        sr += ((c shr 16) and 0xFF) * wt
                        sg += ((c shr 8) and 0xFF) * wt
                        sb += (c and 0xFF) * wt
                        sw += wt
                        break
                    }
                    d++
                }
            }
            out[yy * w + xx] = if (sw > 0.0) {
                0xFF000000.toInt() or
                    ((sr / sw).toInt().coerceIn(0, 255) shl 16) or
                    ((sg / sw).toInt().coerceIn(0, 255) shl 8) or
                    (sb / sw).toInt().coerceIn(0, 255)
            } else px[yy * w + xx]
        }
        bitmap.setPixels(out, 0, w, x0, y0, w, h)
        return w * h
    }

    /**
     * Associate every recognized source line with exactly one output bubble.
     * Bubble geometry may be larger than the original glyph box, so use spatial containment first and
     * normalized source text to disambiguate overlapping balloon rectangles.
     */
    internal fun recognizedLinesByBubble(
        bubbles: List<WmBubble>,
        recognizedLines: List<OcrBlock>,
        width: Int,
        height: Int
    ): Map<String, List<OcrBlock>> {
        if (width <= 0 || height <= 0) return emptyMap()
        val grouped = LinkedHashMap<String, MutableList<OcrBlock>>()
        for (line in recognizedLines) {
            if (line.text.isBlank() || line.w < 2f || line.h < 2f) continue
            val nx = line.cx / width
            val ny = line.cy / height
            val covering = bubbles.filter { bb ->
                nx >= bb.cx - bb.w / 2f && nx <= bb.cx + bb.w / 2f &&
                    ny >= bb.cy - bb.h / 2f && ny <= bb.cy + bb.h / 2f
            }
            if (covering.isEmpty()) continue
            val lineText = WebModeLayout.normalizeOcrText(line.text)
            val textMatch = covering.filter { bb ->
                val bText = WebModeLayout.normalizeOcrText(bb.sourceText)
                bText.isNotBlank() && lineText.isNotBlank() &&
                    (bText.contains(lineText) || lineText.contains(bText))
            }
            val chosen = (textMatch.ifEmpty { covering }).minByOrNull { bb ->
                kotlin.math.abs(bb.cx * width - line.cx) + kotlin.math.abs(bb.cy * height - line.cy)
            } ?: continue
            grouped.getOrPut(WebModeTextRemovalCache.bubbleKey(chosen)) { ArrayList() }.add(line)
        }
        return grouped
    }

    /** A bubble is eligible for transparent rendering only when every source line assigned to it was erased. */
    internal fun erasedBubbleKeys(
        linesByBubble: Map<String, List<OcrBlock>>,
        erasedLines: Set<OcrBlock>
    ): Set<String> = linesByBubble.asSequence()
        .filter { (_, lines) -> lines.isNotEmpty() && lines.all { erasedLines.contains(it) } }
        .map { it.key }
        .toSet()

    /**
     * Find the balloon instance associated with the original OCR box. This uses the detector's compact mask
     * directly and does not rerun inference or build a second page-sized bitmap.
     */
    private fun speechBubbleMaskFor(
        bubble: WmBubble,
        dets: List<BubbleDetectorCore.Det>?,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        bw: Float,
        bh: Float
    ): BubbleDetectorCore.Det? {
        if (bubble.free || dets.isNullOrEmpty() || width <= 0 || height <= 0) return null
        val l = (cx - bw / 2f).toInt().coerceIn(0, width - 1)
        val t = (cy - bh / 2f).toInt().coerceIn(0, height - 1)
        val r = kotlin.math.ceil(cx + bw / 2f).toInt().coerceIn(l + 1, width)
        val b = kotlin.math.ceil(cy + bh / 2f).toInt().coerceIn(t + 1, height)
        return dets.asSequence()
            .filter { it.label == BubbleDetectorCore.CLS_BUBBLE && it.mask != null && it.contains(cx, cy) }
            .filter { BubbleDetectorCore.maskCoverage(it, l, t, r, b) >= 0.36f }
            .minByOrNull { it.area }
    }

    /**
     * Restrict speech-bubble cleaning to a known balloon silhouette/interior. FREE text is intentionally allowed
     * outside balloons. When no trustworthy shape was detected, preserve the legacy local-text path; the overlay
     * keeps an opaque fallback rather than treating uncertain cleanup as complete.
     */
    private fun insideSpeechBubbleShape(
        bubble: WmBubble,
        balloonMask: BubbleDetectorCore.Det?,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Boolean {
        if (bubble.free) return true
        if (balloonMask?.mask != null) return balloonMask.contains(x, y)
        if (bubble.cv.size < 4 || width <= 0 || height <= 0) return true
        val nx = x / width
        val ny = y / height
        var i = 0
        while (i + 3 < bubble.cv.size) {
            if (nx >= bubble.cv[i] && nx <= bubble.cv[i + 2] &&
                ny >= bubble.cv[i + 1] && ny <= bubble.cv[i + 3]) return true
            i += 4
        }
        return false
    }


    /**
     * Conservative recovery for lettering that OCR did not include in its text box.
     *
     * Only the caller's segmentation-model-derived interior bands are accepted. We additionally
     * verify that at least 72% of their pixels match the sampled paper colour and that strong ink
     * occupies less than 45%; textured/gradient artwork therefore falls back to glyph-only cleanup.
     * The band geometry has already been inset from the model silhouette, so this does not paint
     * the balloon outline or its external background.
     */
    private fun wipeModelValidatedFlatInterior(
        bitmap: Bitmap,
        cv: List<Float>,
        width: Int,
        height: Int,
        bg: Int
    ): Int {
        if (cv.size < 4 || width <= 0 || height <= 0) return 0
        val rects = ArrayList<IntArray>()
        var totalArea = 0L
        var q = 0
        while (q + 3 < cv.size && rects.size < 64) {
            val l = (cv[q] * width).toInt().coerceIn(0, width - 1)
            val t = (cv[q + 1] * height).toInt().coerceIn(0, height - 1)
            val r = ceil(cv[q + 2] * width).toInt().coerceIn(l + 1, width)
            val b = ceil(cv[q + 3] * height).toInt().coerceIn(t + 1, height)
            val area = (r - l).toLong() * (b - t).toLong()
            if (area >= 4L && area <= 1_250_000L && totalArea + area <= 2_000_000L) {
                rects.add(intArrayOf(l, t, r, b))
                totalArea += area
            }
            q += 4
        }
        if (rects.isEmpty()) return 0

        val paper = opaque(bg)
        var total = 0L
        var nearPaper = 0L
        var strongInk = 0L
        for (rc in rects) {
            val w = rc[2] - rc[0]
            val h = rc[3] - rc[1]
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, rc[0], rc[1], w, h)
            for (p in pixels) {
                total++
                val d = distance(p, paper)
                if (d <= 30f) nearPaper++
                if (d >= 60f) strongInk++
            }
        }
        // A flat fill is safe only when most of the interior agrees with its paper colour.
        if (total < 32L || nearPaper * 100L < total * 72L ||
            strongInk * 100L > total * 45L) return 0

        var changed = 0
        for (rc in rects) {
            val w = rc[2] - rc[0]
            val h = rc[3] - rc[1]
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, rc[0], rc[1], w, h)
            var any = false
            for (i in pixels.indices) {
                if (distance(pixels[i], paper) >= 4f) {
                    pixels[i] = paper
                    changed++
                    any = true
                }
            }
            if (any) bitmap.setPixels(pixels, 0, w, rc[0], rc[1], w, h)
        }
        return changed
    }


    /**
     * Resolve interior bands for a bubble/text OCR record. Non-rotated bubbles already carry the geometry that
     * was used for placement. Rotated text (notably vertical Japanese) skips that placement fit, so find the
     * containing instance mask from the original OCR box and reuse its inset bands for cleanup only.
     */
    private fun modelInteriorBandsFor(
        bubble: WmBubble,
        dets: List<BubbleDetectorCore.Det>?,
        width: Int,
        height: Int
    ): List<Float> {
        if (bubble.free || width <= 0 || height <= 0) return emptyList()
        if (bubble.modelMaskFit && bubble.cv.size >= 4) return bubble.cv
        if (dets.isNullOrEmpty()) return emptyList()

        val hasOcrBox = bubble.ocrW > 0f && bubble.ocrH > 0f
        val cx = (if (hasOcrBox) bubble.ocrCx else bubble.cx) * width
        val cy = (if (hasOcrBox) bubble.ocrCy else bubble.cy) * height
        val bw = (if (hasOcrBox) bubble.ocrW else bubble.w) * width
        val bh = (if (hasOcrBox) bubble.ocrH else bubble.h) * height
        if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite() || bw < 4f || bh < 4f) {
            return emptyList()
        }
        val l = (cx - bw / 2f).toInt().coerceIn(0, width - 1)
        val t = (cy - bh / 2f).toInt().coerceIn(0, height - 1)
        val r = ceil(cx + bw / 2f).toInt().coerceIn(l + 1, width)
        val b = ceil(cy + bh / 2f).toInt().coerceIn(t + 1, height)
        val shape = BubbleDetectorCore.maskGeometryFor(dets, l, t, r, b) ?: return emptyList()
        if (shape.bands.isEmpty()) return emptyList()
        val out = ArrayList<Float>(shape.bands.size * 4)
        for (band in shape.bands) {
            if (band.size < 4) continue
            out.add(band[0].toFloat() / width)
            out.add(band[1].toFloat() / height)
            out.add(band[2].toFloat() / width)
            out.add(band[3].toFloat() / height)
        }
        return out
    }

    fun cleanAndCache(
        context: Context,
        sourceUrl: String,
        encoded: ByteArray,
        bubbles: List<WmBubble>,
        allowAi: Boolean = true,
        dets: List<BubbleDetectorCore.Det>? = null,
        recognizedLines: List<OcrBlock> = emptyList()
    ): Boolean {
        // When the menu toggle is OFF, keep the legacy pixel/AI remover disabled, but still allow
        // a dedicated segmentation-mask pass for OCR regions that the balloon model can actually validate.
        val maskOnly = !Prefs.bool(context, "wmTextRemoval2", true)
        if (sourceUrl.isBlank() || encoded.size !in 1..MAX_BYTES || bubbles.isEmpty()) return false
        if (isAnimatedOrUnsupported(encoded)) return false

        // Do not drop a bubble just because another worker is cleaning a different panel. These calls run on OCR
        // workers; a fair lock queues them and holds at most one full ARGB bitmap/scratch-mask working set at a time.
        val waitStarted = android.os.SystemClock.elapsedRealtime()
        cleanSlot.lock()
        val queueWaitMs = android.os.SystemClock.elapsedRealtime() - waitStarted
        try {
            // The same source may be read once by the visible worker and again by read-ahead/rescan. Start from
            // the prior cleaned pixels and retain its verified bubble keys, otherwise the second pass restores the
            // first bubble's original lettering and replaces its success state with only the second bubble's key.
            val sourceChanged = WebModeTextRemovalCache.prepareSource(sourceUrl, encoded)
            val previousImage = WebModeTextRemovalCache.readCleanedBytes(sourceUrl)
            val previousKeys = if (previousImage != null && !maskOnly)
                WebModeTextRemovalCache.erasedKeys(sourceUrl) else emptySet()
            if (previousImage == null) WebModeTextRemovalCache.setErased(sourceUrl, emptySet())

            val cleanBase = previousImage ?: encoded
            val cleaned = cleanAndCacheLocked(
                context, sourceUrl, cleanBase, bubbles, allowAi, dets, recognizedLines, maskOnly, previousKeys
            )
            OcrDiag.log("CLEAN_QUEUE", "waitMs=$queueWaitMs reusedCleanedImage=${previousImage != null} " +
                "sourceChanged=$sourceChanged priorErased=${previousKeys.size} newBubbles=${bubbles.size} result=$cleaned")
            return cleaned
        } finally {
            cleanSlot.unlock()
        }
    }

    private fun cleanAndCacheLocked(
        context: Context,
        sourceUrl: String,
        encoded: ByteArray,
        bubbles: List<WmBubble>,
        allowAi: Boolean,
        dets: List<BubbleDetectorCore.Det>?,
        recognizedLines: List<OcrBlock>,
        maskOnly: Boolean,
        previouslyErased: Set<String>
    ): Boolean {

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
            if (maskOnly) {
                // Koharu returns three useful instance classes (dialogue_text, balloon, onomatopoeia_text).
                // Pass the full model result to the existing eraser; filtering to balloon masks alone silently
                // discarded the model's direct text/SFX masks, which is precisely the advantage of Koharu.
                val allDetections = dets.orEmpty()
                val balloonMasks = allDetections.filter {
                    it.label == BubbleDetectorCore.CLS_BUBBLE && it.mask != null
                }
                val textMasks = allDetections.filter {
                    (it.label == BubbleDetectorCore.CLS_TEXT_BUBBLE ||
                        it.label == BubbleDetectorCore.CLS_TEXT_FREE) && it.mask != null
                }
                if (balloonMasks.isEmpty() && textMasks.isEmpty()) {
                    OcrDiag.log("MASK_CLEAN", "skip: model returned no balloon or text instance masks")
                    return false
                }
                if (recognizedLines.isEmpty() && textMasks.isEmpty()) {
                    OcrDiag.log("MASK_CLEAN", "skip: neither OCR regions nor model text masks are available")
                    return false
                }

                // The mask-only path has no independent pixel-level verification against the original glyph ink.
                // Preserve the cleaned pixels, but leave speech-bubble covers opaque to prevent leftover strokes
                // from showing through a translation when the detector segmented only part of a line.
                val changedPixels = eraseDetected(bitmap, allDetections, recognizedLines)
                val erasedKeys = emptySet<String>()
                if (changedPixels < 4 || textMasks.isEmpty()) {
                    OcrDiag.log("MASK_CLEAN", "skip: model masks did not safely erase text pixels")
                    return false
                }
                val stored = storeCleanedBitmap(context, sourceUrl, bitmap, erasedKeys)
                OcrDiag.log("MASK_CLEAN", "ocrRegions=${recognizedLines.size} balloonMasks=${balloonMasks.size} textMasks=${textMasks.size} erasedBubbles=0 pixels=$changedPixels stored=$stored opaqueCoverFallback=true")
                return stored
            }

            var totalRemoved = 0
            // AI inpainting (optional, downloaded on request; used automatically once installed) is slow on a phone:
            // only a few artwork bubbles / free-text boxes per image.
            var aiBudget = 6
            var aiWanted = allowAi && Prefs.bool(context, "wmAiInpaint", true) && WebModeInpainter.isReady(context) &&
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
            val erased = HashSet<String>().apply { if (!maskOnly) addAll(previouslyErased) }
            // Pixel-level verification must inspect the ORIGINAL source ink before the detector reconstruction
            // changes it. Detector masks are applied at the end, but may never authorize transparent rendering.
            val boundedBubbles = bubbles.asSequence()
                .filter { it.sourceText.isNotBlank() && it.w > 0f && it.h > 0f }
                .take(500)
                .toList()

            // Step 1 - NO whole-interior wipe.
            //
            // A previous version painted every pixel inside the bubble's interior rectangles with one flat colour
            // (wipeInterior). That is what damaged the balloon: a curved, tailed or coloured balloon became a flat
            // rectangular patch, its outline and the artwork inside it were destroyed, and no lettering was needed
            // for it to happen — it ran before (and independently of) the glyph test below.
            //
            // The OCR box already says exactly where the lettering is. Erasing is driven by that box from here on,
            // and every fill samples the background that actually surrounds the glyphs (see sampleLocalBackground /
            // wipeLetteringByBackground), so only the text is removed and its own colours are restored.

            for (bubble in boundedBubbles) {
                // Revisit previously erased bubbles against the current OCR boxes: a later rescan can recover a
                // missing row in the SAME speech balloon. Starting from cleanBase is idempotent for removed ink,
                // while skipping the whole bubble here would preserve any newly discovered source lettering.
                // The cover geometry can span the whole speech balloon. Text cleaning must always use the
                // original OCR glyph box, never that expanded cover rectangle.
                val hasOcrBox = bubble.ocrW > 0f && bubble.ocrH > 0f
                val cx = (if (hasOcrBox) bubble.ocrCx else bubble.cx) * width
                val cy = (if (hasOcrBox) bubble.ocrCy else bubble.cy) * height
                val bw = (if (hasOcrBox) bubble.ocrW else bubble.w) * width
                val bh = (if (hasOcrBox) bubble.ocrH else bubble.h) * height
                if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) continue
                val balloonMask = speechBubbleMaskFor(bubble, dets, width, height, cx, cy, bw, bh)
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
                // Bound the per-bubble working set. One bubble used to reserve up to 2 M px, which is an 8 MB
                // IntArray plus six ByteArray(regionCount) masks allocated back to back: ~12-20 MB of garbage
                // per bubble. Two workers doing that at once on a low-RAM phone is exactly the memory
                // sawtooth that made realtime stutter. A genuine OCR line never needs this much area; a
                // bigger box is a mis-grouped paragraph and is left for the next pass.
                if (regionSize > MAX_REGION_PIXELS) continue
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
                    if (!insideSpeechBubbleShape(bubble, balloonMask, width, height,
                            x0 + (i % rw) + 0.5f, y0 + (i / rw) + 0.5f)) continue
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
                            if (ny in 0 until rh && strokeCand[ny * rw + rx].toInt() != 0 &&
                                insideSpeechBubbleShape(bubble, balloonMask, width, height,
                                    x0 + rx + 0.5f, y0 + ny + 0.5f)) candidate[ny * rw + rx] = 1
                        }
                    }
                }

                // Non-AI background-driven erase, for flat AND multi-coloured balloons.
                //
                // A side-sampled background model describes what the paper looks like across this OCR box: one
                // colour when it is flat, a smooth ramp when it is a gradient, or a per-side mix when several
                // colours meet. The glyph mask is then simply "pixels that disagree with that model", and each one
                // is restored with the model's own colour at that exact spot. This is what keeps a pale-green or
                // coloured balloon its own colour instead of turning it into a white rectangle.
                // A side-colour model is valid for speech-bubble/paper fill, not illustrated artwork.
                // On a flat-green recipe panel it mistook cup/line-art pixels for dark lettering and painted
                // over them. Artwork uses the stricter connected-glyph path below and requires true inpainting.
                val bgRemoved = if (soft) 0 else eraseByBackgroundModel(
                    bitmap, region, rw, rh, ix0, iy0, ix1, iy1, candidate, regionCount,
                    fg1, fg2, fg3, fontPx, x0, y0, innerArea,
                    bubble, balloonMask, width, height
                )
                // -1 means the OCR box contains strong non-text structure or its paper cannot be trusted.
                // Do NOT fall through to the connected-component erase in that case: line-art pixels can have
                // the same colour as the text and would otherwise be removed from the drawing too.
                if (bgRemoved < 0) {
                    OcrDiag.log("ERASE", "skip ambiguous OCR box; preserve source art")
                    continue
                }
                // OCR boxes are now source-glyph bounds. A 1% hit was too easy for a partial stroke to satisfy.
                // Keep an opaque shaped cover whenever only a small fragment could be reconstructed.
                val minimumErasedPixels = max(32, (innerArea * 0.06f).toInt())
                if (bgRemoved >= minimumErasedPixels) {
                    totalRemoved += bgRemoved
                    erased.add(WebModeTextRemovalCache.bubbleKey(bubble))
                    continue
                }

                val queue = IntArray(regionCount)
                var selectedCount = 0
                var selectedInsideCount = 0
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
                    selectedInsideCount += insidePixels
                }

                // If the inferred mask covers too much of the OCR box, it is probably art rather than text.
                // Dense bold CJK glyphs legitimately hold more ink than Latin text: a tight Japanese OCR box
                // around two heavy kanji columns is ~70 % ink, and the old 50 % cap threw the whole bubble away.
                // Only very dense (>= 85 %) masks are treated as artwork now; the shape-size limits above and
                // the on-artwork AI gate still decide whether this is really lettering.
                var candidateInkCount = 0
                for (ry in iy0 until iy1) for (rx in ix0 until ix1) {
                    if (candidate[ry * rw + rx].toInt() != 0) candidateInkCount++
                }
                // If most candidate ink was rejected as ambiguous/art, we cannot prove the whole source line
                // has disappeared. Leave the original under an opaque shaped cover instead.
                if (selectedCount < max(32, (innerArea * 0.06f).toInt()) ||
                    candidateInkCount == 0 || selectedInsideCount < candidateInkCount * 0.80f ||
                    selectedCount > innerArea * (if (soft) 0.82 else 0.6)) continue

                // Flat bubble: mop up the faint ghosts the strict glyph test leaves behind (anti-aliasing, JPEG
                // ringing, halos, thin strokes, shapes rejected by size).
                //
                // It must take the LETTERING colours only. The old rule marked every pixel that merely differed
                // from the paper colour, so on a shaded or gradient balloon it erased the shading too and left a
                // flat rectangle behind. A ghost is now a pixel that is still recognisably the ink (a faint blend
                // of the ink with the paper), never a pixel that merely differs from the paper.
                if (!soft) {
                    val m = (fontPx * 0.12f).toInt().coerceIn(1, 4)
                    val sx0 = max(0, ix0 - m); val sy0 = max(0, iy0 - m)
                    val sx1 = min(rw, ix1 + m); val sy1 = min(rh, iy1 + m)
                    // How far from the pure ink a pixel may be and still count as its anti-aliased edge: at most
                    // half the ink/paper distance. Beyond that it is paper, shading, or a different colour.
                    val ghostThr = max(34f, min(distance(bg, fg1), distance(bg, fg2)) * 0.5f).coerceAtMost(72f)
                    for (ry in sy0 until sy1) for (rx in sx0 until sx1) {
                        val i = ry * rw + rx
                        if (selected[i].toInt() != 0 || protect[i].toInt() != 0) continue
                        val p = region[i]
                        if (!insideSpeechBubbleShape(bubble, balloonMask, width, height,
                                x0 + rx + 0.5f, y0 + ry + 0.5f)) continue
                        if (distance(p, bg) < 9f) continue
                        val ink = min(distance(p, fg1), distance(p, fg2))
                        if (ink <= ghostThr || distance(p, fg3) <= ghostThr) selected[i] = 1
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
                                    insideSpeechBubbleShape(bubble, balloonMask, width, height,
                                        x0 + nx + 0.5f, y0 + ny + 0.5f) &&
                                    distance(region[ni], bg) >= 8f &&
                                    (soft || min(distance(region[ni], fg1), distance(region[ni], fg2)) <=
                                        max(58f, min(distance(bg, fg1), distance(bg, fg2)) * 0.6f))) mask[ni] = 1
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
                // Never erase or approve transparent rendering over illustration using a background guess or
                // onion-peel fill. A recipe drawing/face behind the OCR box can have the same ink colour as text.
                // Without a successful AI fill, leave these pixels intact; the writer also suppresses the opaque
                // sampled-colour cover for soft/free text so artwork is not replaced by a blank rectangle.
                if (soft && aiFill == null) {
                    OcrDiag.log("ERASE", "skip artwork: no verified inpaint fill; keep source pixels")
                    continue
                }
                val tele: IntArray? = null
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
            // Recovery pass from the faster cleaner: remove missed rows/SFX inside a VERIFIED flat speech bubble.
            // Prefer the cached model-derived bands; for rotated Japanese OCR, derive the same bands directly from the
            // already-returned balloon mask and the original OCR box (no new inference). In either case the paper must
            // pass a strict flat-colour check. The pass runs AFTER glyph verification and never touches free text.
            val flatBandOwners = LinkedHashMap<List<Float>, MutableList<WmBubble>>()
            for (bubble in boundedBubbles) {
                if (bubble.free) continue
                val bands = try {
                    modelInteriorBandsFor(bubble, dets, width, height)
                } catch (_: Throwable) { emptyList() }
                if (bands.size < 4) continue
                flatBandOwners.getOrPut(bands) { ArrayList() }.add(bubble)
            }
            for ((bands, owners) in flatBandOwners) {
                var changed = 0
                val triedPaper = HashSet<Int>()
                for (owner in owners) {
                    val paper = opaque(owner.bg)
                    if (!triedPaper.add(paper)) continue
                    changed = try {
                        wipeModelValidatedFlatInterior(bitmap, bands, width, height, paper)
                    } catch (_: Throwable) { 0 }
                    if (changed > 0) break
                }
                if (changed <= 0) continue

                totalRemoved += changed
                // One model mask can own multiple OCR rows. Mark every owner only after its entire verified
                // flat interior has actually been reconstructed, so the writer can safely use a transparent overlay.
                owners.forEach { erased.add(WebModeTextRemovalCache.bubbleKey(it)) }
                OcrDiag.log("FLAT_INTERIOR", "pixels=$changed bands=${bands.size / 4} related=${owners.size} paperCheck=passed")
            }

            // The detector's balloon mask can occasionally cover a whole illustrated panel (for example a
            // recipe page). Feeding ALL OCR boxes to reconstruction then repaints cups/line art as if they were
            // dialogue. Restrict this final pass to OCR lines inside a non-art speech bubble whose own glyph box
            // already passed the independent pixel-erasure verifier above.
            val verifiedDialogueLines = if (maskOnly) emptyList() else recognizedLines.filter { line ->
                if (line.w <= 0f || line.h <= 0f) false else boundedBubbles.any { bubble ->
                    if (bubble.soft || bubble.free ||
                        WebModeTextRemovalCache.bubbleKey(bubble) !in erased) false
                    else {
                        val bx = (if (bubble.ocrW > 0f) bubble.ocrCx else bubble.cx) * width
                        val by = (if (bubble.ocrH > 0f) bubble.ocrCy else bubble.cy) * height
                        val bw = (if (bubble.ocrW > 0f) bubble.ocrW else bubble.w) * width
                        val bh = (if (bubble.ocrH > 0f) bubble.ocrH else bubble.h) * height
                        val il = max(bx - bw / 2f, line.cx - line.w / 2f)
                        val it = max(by - bh / 2f, line.cy - line.h / 2f)
                        val ir = min(bx + bw / 2f, line.cx + line.w / 2f)
                        val ib = min(by + bh / 2f, line.cy + line.h / 2f)
                        val intersection = (ir - il).coerceAtLeast(0f) * (ib - it).coerceAtLeast(0f)
                        val lineArea = line.w * line.h
                        val lineText = WebModeLayout.normalizeOcrText(line.text)
                        val bubbleText = WebModeLayout.normalizeOcrText(bubble.sourceText)
                        lineArea > 0f && intersection / lineArea >= 0.65f &&
                            lineText.isNotBlank() && bubbleText.isNotBlank() &&
                            (bubbleText.contains(lineText) || lineText.contains(bubbleText))
                    }
                }
            }
            val erasedByDetector = try {
                if (verifiedDialogueLines.isEmpty()) 0
                else eraseDetected(bitmap, dets, verifiedDialogueLines)
            } catch (_: Throwable) { 0 }
            if (erasedByDetector > 0) {
                totalRemoved += erasedByDetector
                OcrDiag.log("ERASE", "detector reconstruction pixels=$erasedByDetector; verifiedTransparentBubbles=${erased.size}/${boundedBubbles.size}")
            }
            if (totalRemoved < 8) return false

            return storeCleanedBitmap(context, sourceUrl, bitmap, erased)
        } catch (_: OutOfMemoryError) {
            return false
        } catch (_: Exception) {
            return false
        } finally {
            bitmap.recycle()
        }
    }

    /** Save one cleaned bitmap through the same cache path used by the full remover. */
    private fun storeCleanedBitmap(
        context: Context,
        sourceUrl: String,
        bitmap: Bitmap,
        erased: Set<String>
    ): Boolean {
        val bytesOut = ByteArrayOutputStream()
        // API 30+ supports lossless WebP. Older Android versions use lossless PNG rather than lossy WebP.
        val modern = Build.VERSION.SDK_INT >= 30
        val format = if (modern) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
        if (!bitmap.compress(format, 100, bytesOut)) return false
        val cleaned = bytesOut.toByteArray()
        if (cleaned.isEmpty() || cleaned.size > MAX_BYTES) return false
        val mime = if (modern) "image/webp" else "image/png"
        if (sourceUrl.startsWith("ns-canvas:", true)) {
            WebModeTextRemovalCache.storeCanvasCleaned(
                sourceUrl,
                "data:$mime;base64," + android.util.Base64.encodeToString(cleaned, android.util.Base64.NO_WRAP)
            )
            WebModeTextRemovalCache.setErased(sourceUrl, erased)
            return true
        }
        val stored = WebModeTextRemovalCache.store(context, sourceUrl, cleaned, mime)
        if (stored) WebModeTextRemovalCache.setErased(sourceUrl, erased)
        return stored
    }

    /**
     * Background model for the OCR box, which is what actually gets erased.
     *
     * The previous code could only answer "one flat colour" or "give up". Real pages are neither: a balloon may
     * be pale green, a panel red, or the paper may carry a soft light gradient. This samples the four sides of
     * the box and keeps, per side, the median colour that is clearly NOT lettering (the ring is where the original
     * text is absent). Those side colours are then bilinearly interpolated across the box, so a gradient or a
     * multi-coloured background is restored with its own variation instead of one average colour.
     *
     * Returns null when no side yields a trustworthy colour (heavy artwork), which leaves the existing masked
     * local-fill path in charge.
     */
    internal class BackgroundModel(
        val left: Int, val right: Int, val top: Int, val bottom: Int,
        val uniform: Boolean
    ) {
        /** Background colour at region pixel ([x],[y]) inside the box, interpolated from the four sides. */
        fun at(x: Int, y: Int, x0: Int, x1: Int, y0: Int, y1: Int): Int {
            if (uniform) return left
            val fx = if (x1 > x0) ((x - x0).toFloat() / (x1 - x0)).coerceIn(0f, 1f) else 0.5f
            val fy = if (y1 > y0) ((y - y0).toFloat() / (y1 - y0)).coerceIn(0f, 1f) else 0.5f
            // Bilinear reproduction of the four sampled ends, built as "top value + the two ramps away from it":
            //   value = top + (bottom - top) * fy + (left - right) * (1 - fx)
            // so at fy=0 the top colour is reproduced exactly, at fy=1 the bottom one, and the horizontal term only
            // adds the left/right difference. Each channel is interpolated on its own; mixing the channels of
            // different sides would invent colours that appear nowhere in the panel.
            //
            // The horizontal term used to be added with the wrong sign, which tilted every side sample toward the
            // opposite edge: on a pale top / dark bottom panel the restored ends came out swapped - the "random
            // colour smear" seen on gradient and shaded balloons.
            fun ch(l: Int, r: Int, t: Int, b: Int): Int {
                val vertical = lerp(t, b, fy)
                val horizontal = (l - r) * (1f - fx)
                return Math.round(vertical + horizontal).coerceIn(0, 255)
            }
            val r = ch(red(left), red(right), red(top), red(bottom))
            val g = ch(green(left), green(right), green(top), green(bottom))
            val b = ch(blue(left), blue(right), blue(top), blue(bottom))
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Float = a + (b - a) * t

    /**
     * Erase the lettering inside one OCR box using a locally sampled background. Return restored pixels,
     * 0 when the candidate mask is insufficient, or -1 when the box is unsafe and ALL erase paths must be skipped.
     *
     * Only pixels that are clearly different from the background *at their own position* count as glyphs, and each
     * is rewritten with that position's background colour. A flat, coloured, dark or gradient balloon is therefore
     * restored exactly where the text was, and nothing else in the balloon is touched.
     */
    private fun eraseByBackgroundModel(
        bitmap: Bitmap,
        region: IntArray, rw: Int, rh: Int,
        ix0: Int, iy0: Int, ix1: Int, iy1: Int,
        candidate: ByteArray, regionCount: Int,
        fg1: Int, fg2: Int, fg3: Int, fontPx: Float,
        x0: Int, y0: Int, innerArea: Long,
        bubble: WmBubble, balloonMask: BubbleDetectorCore.Det?, imageWidth: Int, imageHeight: Int
    ): Int {
        val model = estimateBackgroundModel(region, rw, rh, ix0, iy0, ix1, iy1, fg1, fg2)
            ?: return -1
        val paperRef = model.at((ix0 + ix1) / 2, (iy0 + iy1) / 2, ix0, ix1, iy0, iy1)
        // If the paper and glyph colours cannot be separated reliably, no other broad fill should run either.
        if (min(distance(paperRef, fg1), distance(paperRef, fg2)) < 52f) return -1

        // Reject boxes that contain illustration, even when the border ring itself looks like a flat paper colour.
        // A plate/cup/face can sit INSIDE an OCR paragraph box while the surrounding pixels are uniformly green/white;
        // painting every text-coloured pixel against that paper model would erase the drawing's contours as well.
        var offModelStructure = 0
        for (ry in iy0 until iy1) for (rx in ix0 until ix1) {
            val at = ry * rw + rx
            if (candidate[at].toInt() != 0) continue
            val pixel = region[at]
            val paper = model.at(rx, ry, ix0, ix1, iy0, iy1)
            if (distance(pixel, paper) >= 52f &&
                min(distance(pixel, fg1), distance(pixel, fg2)) > 110f) offModelStructure++
        }
        if (offModelStructure > innerArea * 0.055f) {
            OcrDiag.log("ERASE", "skip background model: non-text artwork inside OCR box pixels=$offModelStructure area=$innerArea")
            return -1
        }

        val inkMask = ByteArray(regionCount)
        var candidateCount = 0
        var inkCount = 0
        for (ry in iy0 until iy1) for (rx in ix0 until ix1) {
            val at = ry * rw + rx
            if (candidate[at].toInt() == 0) continue
            candidateCount++
            val bgHere = model.at(rx, ry, ix0, ix1, iy0, iy1)
            if (distance(region[at], bgHere) < 30f) continue
            val inkDist = minOf(
                distance(region[at], fg1),
                distance(region[at], fg2),
                distance(region[at], fg3)
            )
            if (inkDist > max(96f, distance(bgHere, fg1) * 1.05f)) continue
            inkMask[at] = 1
            inkCount++
        }
        // Never call a partial mask a successful removal. The background model must account for almost all
        // text-coloured candidates inside the OCR box; otherwise skip to the stricter connected-component path.
        // This prevents one remaining row/partial stroke from being marked clean and exposing Korean underneath.
        if (candidateCount < 24 ||
            inkCount < max(24, (innerArea * 0.06f).toInt()) ||
            inkCount * 100L < candidateCount * 88L ||
            inkCount > innerArea * 0.85f) return 0

        val mask = inkMask.copyOf()
        val grow = (fontPx * 0.05f).toInt().coerceIn(1, 3)
        for (ry in iy0 until iy1) for (rx in ix0 until ix1) {
            if (inkMask[ry * rw + rx].toInt() == 0) continue
            for (dy in -grow..grow) for (dx in -grow..grow) {
                val nx = rx + dx; val ny = ry + dy
                if (nx !in ix0 until ix1 || ny !in iy0 until iy1) continue
                val ni = ny * rw + nx
                if (mask[ni].toInt() != 0) continue
                if (!insideSpeechBubbleShape(bubble, balloonMask, imageWidth, imageHeight,
                        x0 + nx + 0.5f, y0 + ny + 0.5f)) continue
                val bgHere = model.at(nx, ny, ix0, ix1, iy0, iy1)
                // Only the anti-aliased edge of a glyph, never clean background.
                if (distance(region[ni], bgHere) >= 20f &&
                    min(distance(region[ni], fg1), distance(region[ni], fg2)) <= 120f) {
                    mask[ni] = 1
                }
            }
        }

        val out = region.copyOf()
        var removedPixels = 0
        for (ry in iy0 until iy1) for (rx in ix0 until ix1) {
            val at = ry * rw + rx
            if (mask[at].toInt() == 0) continue
            out[at] = model.at(rx, ry, ix0, ix1, iy0, iy1)
            removedPixels++
        }
        if (removedPixels < 4) return 0
        bitmap.setPixels(out, 0, rw, x0, y0, rw, rh)
        OcrDiag.log("ERASE", "bg-model ${if (model.uniform) "flat" else "gradient"} " +
            "box=${ix1 - ix0}x${iy1 - iy0} ink=$removedPixels")
        return removedPixels
    }

    internal fun estimateBackgroundModel(
        region: IntArray, rw: Int, rh: Int, ix0: Int, iy0: Int, ix1: Int, iy1: Int,
        fg1: Int, fg2: Int
    ): BackgroundModel? {
        // A thin band hugging the box. A thick one averages the gradient away: samples taken a whole band away
        // describe the middle of the ramp, not the colour that touches the lettering.
        val band = max(2, ((ix1 - ix0) / 12).coerceAtMost(((iy1 - iy0) / 12)).coerceAtLeast(2))
        val minInk = max(58f, minOf(distance(fg1, fg2) * 0.5f, 90f))

        fun medianSide(pxs: (Int) -> Pair<Int, Int>, n: Int): Int? {
            val rs = ArrayList<Int>(n); val gs = ArrayList<Int>(n); val bs = ArrayList<Int>(n)
            for (i in 0 until n) {
                val (x, y) = pxs(i)
                if (x < 0 || y < 0 || x >= rw || y >= rh) continue
                val p = region[y * rw + x]
                // Skip glyph pixels and their anti-aliasing: the ring must describe the paper, not the text.
                if (min(distance(p, fg1), distance(p, fg2)) <= minInk) continue
                rs.add(red(p)); gs.add(green(p)); bs.add(blue(p))
            }
            if (rs.size < 6) return null
            rs.sort(); gs.sort(); bs.sort()
            val m = rs.size / 2
            // Reject a side that is mostly artwork (no dominant colour).
            val ref = 0xFF000000.toInt() or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
            var agree = 0
            for (i in rs.indices) {
                val p = 0xFF000000.toInt() or (rs[i] shl 16) or (gs[i] shl 8) or bs[i]
                if (distance(p, ref) <= 46f) agree++
            }
            return if (agree * 100 >= rs.size * 60) ref else null
        }

        val spanX = (ix1 - ix0).coerceAtLeast(1)
        val spanY = (iy1 - iy0).coerceAtLeast(1)
        // Walk each side band: rows for the left/right bands (so "band" steps across the box height) and columns
        // for the top/bottom bands. The step used to be computed from the OTHER axis, so on a tall box the side
        // bands wrapped onto their own column and every side sample was a single vertical strip.
        val left = medianSide({ i -> Pair(ix0 - 1 - (i / spanY) % band, iy0 + i % spanY) }, band * spanY)
        val right = medianSide({ i -> Pair(ix1 + (i / spanY) % band, iy0 + i % spanY) }, band * spanY)
        val top = medianSide({ i -> Pair(ix0 + i % spanX, iy0 - 1 - (i / spanX) % band) }, spanX * band)
        val bottom = medianSide({ i -> Pair(ix0 + i % spanX, iy1 + (i / spanX) % band) }, spanX * band)

        val sides = listOfNotNull(left, right, top, bottom)
        if (sides.isEmpty()) return null
        val base = sides[0]
        val l = left ?: base; val r = right ?: base
        val tp = top ?: base; val bt = bottom ?: base
        // "Uniform" must describe what the model will actually paint, not the side medians. A smooth gradient
        // can leave every side's median near the middle while the top and bottom of the box still differ, and
        // calling that flat is exactly the one-average-colour bug this model exists to avoid. Check BOTH axes:
        // a left-to-right ramp has equal top/bottom medians but its two horizontal ends are far apart.
        val uniform = distance(l, r) <= 24f && distance(tp, bt) <= 24f
        return BackgroundModel(
            left = l, right = r,
            top = tp, bottom = bt,
            uniform = uniform
        )
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
     * True when the pixels just around the masked lettering are plain (flat colour or a soft gradient). Free lettering
     * on such a background can be erased with the onion fill without smearing any artwork; before, free lettering was
     * erased only by the AI painter, so on a phone without it the old Korean stayed under the translation.
     */
    private fun smoothSurround(region: IntArray, mask: ByteArray, rw: Int, rh: Int): Boolean {
        var n = 0
        var sum = 0L
        var sum2 = 0L
        var y = 0
        while (y < rh) {
            var x = 0
            while (x < rw) {
                if (mask[y * rw + x].toInt() == 0) {
                    var near = false
                    var dy = -6
                    while (dy <= 6 && !near) {
                        val ny = y + dy
                        if (ny in 0 until rh) {
                            var dx = -6
                            while (dx <= 6) {
                                val nx = x + dx
                                if (nx in 0 until rw && mask[ny * rw + nx].toInt() != 0) { near = true; break }
                                dx += 3
                            }
                        }
                        dy += 3
                    }
                    if (near) {
                        val p = region[y * rw + x]
                        val l = (299 * red(p) + 587 * green(p) + 114 * blue(p)) / 1000
                        n++; sum += l; sum2 += l.toLong() * l
                    }
                }
                x += 2
            }
            y += 2
        }
        if (n < 20) return false
        val mean = sum.toDouble() / n
        val variance = sum2.toDouble() / n - mean * mean
        return variance <= 20.0 * 20.0
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
