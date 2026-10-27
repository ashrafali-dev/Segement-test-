package com.ashraf.novelstudio.manualextract

import com.ashraf.novelstudio.BubbleDetectorCore
import com.ashraf.novelstudio.OcrBlock
import com.ashraf.novelstudio.WmBubble

/**
 * Maps a Manual Lite selection back onto the page image.
 *
 * The overlay anchors bubbles to an IMAGE URL and treats `cx, cy, w, h, font` as fractions of that whole
 * image, so every measurement taken inside a crop must be converted with the crop rectangle. Two separate
 * bugs came from ignoring that:
 *
 *  - the bubble carried the page URL as its anchor instead of the image URL, so the writer could not find
 *    an `<img>` to draw on and the translation silently vanished;
 *  - the cover/safe rectangles came from a crop downscaled for OCR, so their fractions described the
 *    downscaled crop rather than the page, and the text landed off its own bubble.
 *
 * Pure geometry (no bitmap work) so it can be unit-tested on the JVM; bitmap work stays in
 * [ManualExtractPipeline.buildBubble].
 */
internal object ManualCropGeometry {

    /**
     * A crop rectangle in page-image fractions, plus the pixel size the crop decoded at.
     *
     * [cropW]/[cropH] are the crop's OWN pixels (what OCR measured in). [pageW]/[pageH] are the whole page
     * image's pixels (what the detector works in). The two are different: dividing crop pixels by the page
     * width mixes the units and puts every bubble in the wrong place.
     */
    data class Crop(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val cropW: Int, val cropH: Int,
        val pageW: Int = cropW, val pageH: Int = cropH
    ) {
        fun fx(px: Float): Float = x + (px / cropW) * w
        fun fy(py: Float): Float = y + (py / cropH) * h
        fun fw(px: Float): Float = (px / cropW) * w
        fun fh(py: Float): Float = (py / cropH) * h

        /** Crop pixel coordinate -> whole-page pixel coordinate (the detector's frame). */
        fun pageL(px: Float): Int = (x * pageW + px).toInt()
        fun pageT(py: Float): Int = (y * pageH + py).toInt()
    }

    /**
     * Map a measured pixel rectangle inside [crop] to an [WmBubble] anchored on the page image.
     *
     * [cover] and [safe] are both in crop pixels. [safe] is empty when no interior rectangle is known, and the
     * bubble then writes inside its whole cover box exactly as the realtime path does.
     */
    fun toWmBubble(
        src: String,
        crop: Crop,
        cover: IntArray,
        safe: IntArray?,
        fontPx: Float,
        angle: Float,
        bg: Int, fg: Int, fg2: Int, soft: Boolean, stroke: Int,
        sourceText: String,
        modelMaskFit: Boolean
    ): WmBubble? {
        if (crop.cropW <= 0 || crop.cropH <= 0) return null
        if (sourceText.isBlank()) return null
        val cw = cover[2] - cover[0]
        val chh = cover[3] - cover[1]
        if (cw <= 0 || chh <= 0) return null
        if (fontPx <= 0f) return null

        val s = safe?.takeIf { it[2] > it[0] && it[3] > it[1] }
        return WmBubble(
            src = src,
            cx = crop.fx((cover[0] + cover[2]) / 2f),
            cy = crop.fy((cover[1] + cover[3]) / 2f),
            w = crop.fw(cw.toFloat()),
            h = crop.fh(chh.toFloat()),
            angle = angle, font = crop.fw(fontPx).coerceIn(0.002f, 0.30f),
            bg = bg, fg = fg, fg2 = fg2, soft = soft, stroke = stroke,
            sourceText = sourceText.trim(),
            tcx = if (s != null) crop.fx((s[0] + s[2]) / 2f) else 0f,
            tcy = if (s != null) crop.fy((s[1] + s[3]) / 2f) else 0f,
            tbw = if (s != null) crop.fw((s[2] - s[0]).toFloat()) else 0f,
            tbh = if (s != null) crop.fh((s[3] - s[1]).toFloat()) else 0f,
            modelMaskFit = s != null && modelMaskFit
        )
    }

    /**
     * Detector results measured on the FULL page image, trimmed to the region covered by [crop] and shifted
     * into crop pixels.
     *
     * Running the detector on the downscaled crop gave masks in crop coordinates, bound to a throwaway
     * bitmap, so erasing never reached the page. Detecting once on the full image and trimming here keeps the
     * instance silhouette exact: the crop becomes a window into the same mask, so the balloon outline, the
     * tail and neighbouring panel pixels inside the crop are still excluded by the mask itself.
     *
     * Detection boxes that do not intersect the crop are dropped; boxes that only partly overlap it are
     * windowed together with their compact mask, so [BubbleDetectorCore.Det.contains] keeps resolving
     * correctly over the new box.
     */
    fun detectionsInCrop(
        full: List<BubbleDetectorCore.Det>?,
        crop: Crop
    ): List<BubbleDetectorCore.Det>? {
        if (full.isNullOrEmpty()) return null
        // The crop window in PAGE pixels (what the detector's boxes live in).
        val px0 = crop.x * crop.pageW
        val py0 = crop.y * crop.pageH
        val px1 = px0 + crop.w * crop.pageW
        val py1 = py0 + crop.h * crop.pageH
        // Page pixels -> crop pixels. The crop may have been decoded/downscaled to a different pixel size, so
        // the offset cannot be subtracted raw.
        val kx = crop.cropW.toFloat() / crop.pageW.coerceAtLeast(1)
        val ky = crop.cropH.toFloat() / crop.pageH.coerceAtLeast(1)
        val out = ArrayList<BubbleDetectorCore.Det>()
        for (d in full) {
            val l = maxOf(d.l.toFloat(), px0)
            val t = maxOf(d.t.toFloat(), py0)
            val r = minOf(d.r.toFloat(), px1)
            val b = minOf(d.b.toFloat(), py1)
            if (r - l < 2f || b - t < 2f) continue
            val box = BubbleDetectorCore.Det(
                d.label, d.score,
                ((l - px0) * kx).toInt(), ((t - py0) * ky).toInt(),
                ((r - px0) * kx).toInt(), ((b - py0) * ky).toInt()
            )
            val m = d.mask
            if (m == null) { out.add(box); continue }
            val window = windowMask(m, d.l.toFloat(), d.t.toFloat(), d.w.toFloat(), d.h.toFloat(), l, t, r, b)
            out.add(
                if (window != null) {
                    BubbleDetectorCore.Det(box.label, box.score, box.l, box.t, box.r, box.b, window)
                } else {
                    // An unusable window means the mask cannot be trusted over this box: keep the box and let
                    // the eraser fall back to OCR geometry instead of painting with a wrong silhouette.
                    box
                }
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Copy the [mask] region that covers [l,t,r,b] of the detection box and normalise it to that region. */
    private fun windowMask(
        mask: BubbleDetectorCore.Mask,
        boxL: Float, boxT: Float, boxW: Float, boxH: Float,
        l: Float, t: Float, r: Float, b: Float
    ): BubbleDetectorCore.Mask? {
        val mw = mask.width
        val mh = mask.height
        if (mw <= 0 || mh <= 0 || boxW <= 0f || boxH <= 0f) return null
        val sx = mw / boxW
        val sy = mh / boxH
        val sx0 = ((l - boxL) * sx).toInt().coerceIn(0, mw - 1)
        val sy0 = ((t - boxT) * sy).toInt().coerceIn(0, mh - 1)
        val sx1 = (((r - boxL) * sx).toInt()).coerceIn(sx0 + 1, mw)
        val sy1 = (((b - boxT) * sy).toInt()).coerceIn(sy0 + 1, mh)
        val w = sx1 - sx0
        val h = sy1 - sy0
        if (w < 1 || h < 1) return null
        val px = ByteArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(mask.pixels, (sy0 + y) * mw + sx0, px, y * w, w)
        }
        return BubbleDetectorCore.Mask(w, h, px)
    }

    /** OCR line boxes measured in crop pixels -> page-image fractions, for the cleaner's mask test. */
    fun toPageFraction(crop: Crop, line: OcrBlock): OcrBlock = OcrBlock(
        text = line.text,
        top = crop.pageT(line.top.toFloat()),
        left = crop.pageL(line.left.toFloat()),
        cx = crop.fx(line.cx),
        cy = crop.fy(line.cy),
        w = crop.fw(line.w),
        h = crop.fh(line.h),
        angle = line.angle,
        fontPx = crop.fw(line.fontPx),
        vertical = line.vertical,
        confidence = line.confidence,
        observations = line.observations,
        polygon = line.polygon.map { crop.fx(it.first) to crop.fy(it.second) }
    )
}
