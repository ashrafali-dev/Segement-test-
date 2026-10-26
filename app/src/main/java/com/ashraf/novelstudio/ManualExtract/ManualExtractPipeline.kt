package com.ashraf.novelstudio.manualextract

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.ashraf.novelstudio.OcrScript
import com.ashraf.novelstudio.WmBubble

/**
 * One manual-extract run, on the calling worker thread:
 *   capture(region) → crop → downscale → OCR (crop only) → build bubble (built-in mask) → translate → place
 *
 * Never takes a full-screen shot, never upscales, never retries on a wider image, and recycles every bitmap it
 * allocates on every path.
 */
internal class ManualExtractPipeline(
    private val ocr: ManualExtractOcr,
    private val host: Host
) {
    /** The Activity side: how to get pixels for a region, and how to translate + place a single bubble. */
    interface Host {
        /**
         * Returns the encoded bytes (or a decoded bitmap) of the page region for [src], cropping to the given
         * normalized rect. Implementations MUST only fetch the tapped image, never a full-page screenshot.
         */
        fun captureCrop(src: String, x: Float, y: Float, w: Float, h: Float): Bitmap?

        /**
         * Translate [text] and place it over [bubble] using the existing overlay (built-in mask). [onDone] is
         * called on the main thread with the translated text (blank = nothing placed).
         */
        fun translateAndPlace(bubble: WmBubble, text: String, script: OcrScript, onDone: (String) -> Unit)
    }

    data class Result(val ok: Boolean, val translated: String, val reason: String)

    fun run(src: String, x: Float, y: Float, w: Float, h: Float, script: OcrScript, cancelled: () -> Boolean = { false }): Result {
        val m = ManualExtractMetrics()
        val tStart = SystemClock.elapsedRealtime()
        if (cancelled()) return fail(m, "cancelled")
        // A crop that is only part of the source image means the user pointed somewhere; a full-panel crop
        // (0,0,1,1 from the Extract button) means "read everything visible".
        val hasTapPoint = !(x <= 0.001f && y <= 0.001f && w >= 0.999f && h >= 0.999f)

        // 1) capture region only
        val raw = host.captureCrop(src, x, y, w, h) ?: return fail(m, "capture failed")
        m.mark("capture")
        return ocrAndPlace(raw, src, x, y, w, h, hasTapPoint, m, tStart, script, cancelled)
    }

    /**
     * Same pipeline, on pixels the caller already captured (the 6-dot frame drawn by the user).
     * Takes ownership of [bitmap] and recycles it. There is no tap point: the frame IS the selection.
     */
    fun runOnBitmap(bitmap: Bitmap, script: OcrScript, cancelled: () -> Boolean = { false }): Result {
        val m = ManualExtractMetrics()
        val tStart = SystemClock.elapsedRealtime()
        if (cancelled()) { bitmap.recycle(); return fail(m, "cancelled") }
        m.cropW = bitmap.width; m.cropH = bitmap.height
        m.mark("capture")
        return ocrAndPlace(bitmap, "", 0f, 0f, 1f, 1f, false, m, tStart, script, cancelled)
    }

    private fun ocrAndPlace(
        raw: Bitmap, src: String, x: Float, y: Float, w: Float, h: Float, hasTapPoint: Boolean,
        m: ManualExtractMetrics, tStart: Long, script: OcrScript, cancelled: () -> Boolean
    ): Result {
        var crop: Bitmap = raw
        try {
            if (cancelled()) return fail(m, "cancelled")
            val cw = crop.width; val ch = crop.height
            m.cropW = cw; m.cropH = ch
            // 14) validate crop
            if (cw < ManualExtractConfig.MIN_CROP_PX || ch < ManualExtractConfig.MIN_CROP_PX ||
                cw > ManualExtractConfig.ABORT_IF_LARGER_THAN * 2 ||
                ch > ManualExtractConfig.ABORT_IF_LARGER_THAN * 2) {
                return fail(m, "crop out of bounds ${cw}x$ch")
            }

            // 3) downscale so max dim ≤ MAX_OCR_DIM (never upscale)
            val ocrBmp = downscale(crop, ManualExtractConfig.MAX_OCR_DIM)
            if (ocrBmp !== crop) { crop.recycle(); crop = ocrBmp }
            m.ocrW = ocrBmp.width; m.ocrH = ocrBmp.height
            m.mark("crop")
            if (cancelled()) return fail(m, "cancelled")

            // 4) OCR crop only
            var outcome = ocr.read(ocrBmp, script, ManualExtractConfig.OCR_CAP_MS)
            // A crop can take in a neighbour bubble or a wide area selection. Without a tap position (Extract
            // reads the whole visible panel) all blocks are correct; with one, keep only the text the user
            // pointed at so the translation lands on the bubble they tapped.
            if (hasTapPoint) outcome = outcome.narrowToTap(ocrBmp, x, y)
            m.mark("ocr")
            if (outcome.blocks.isEmpty() && SystemClock.elapsedRealtime() - tStart < ManualExtractConfig.TOTAL_CAP_MS / 2) {
                // one slightly-expanded retry — still a crop, never full-panel. A preset bitmap (6-dot frame)
                // has no source image to re-capture from, and the frame is exactly what the user chose.
                val expanded = if (src.isBlank()) null else expandCrop(host, src, x, y, w, h)
                if (expanded != null) {
                    try {
                        val e = downscale(expanded, ManualExtractConfig.MAX_OCR_DIM)
                        if (e !== expanded) expanded.recycle()
                        outcome = ocr.read(e, script, ManualExtractConfig.OCR_CAP_MS)
                        if (e !== expanded) e.recycle()
                    } catch (_: Throwable) { /* keep first outcome */ }
                    m.mark("ocr_retry")
                }
            }
            m.engine = outcome.engine
            m.blocks = outcome.blocks.size
            m.letters = outcome.blocks.sumOf { b -> b.text.count { it.isLetterOrDigit() } }
            if (outcome.blocks.isEmpty()) return fail(m, "no text")

            // 5) translate (cache first) + place using the built-in mask
            val paragraph = outcome.blocks.inReadingOrder().toParagraph()
            val cacheKey = ManualExtractCache.key(paragraph, script.name)
            val cached = ManualExtractCache.get(cacheKey)
            m.mark("build")

            val bubble = buildBubble(ocrBmp, outcome.blocks, src)
            if (cancelled()) return fail(m, "cancelled")
            if (cached != null) {
                host.translateAndPlace(bubble, cached, script) { }
                m.mark("place_cached")
                m.log(true, "cache hit")
                return Result(true, cached, "ok")
            }
            // Translation is asynchronous on the Activity side; this call starts it and records the step.
            host.translateAndPlace(bubble, paragraph, script) { translated ->
                if (translated.isNotBlank()) ManualExtractCache.put(cacheKey, translated)
            }
            m.mark("translate_started")
            m.log(true, "started")
            return Result(true, "", "ok")
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun fail(m: ManualExtractMetrics, reason: String): Result {
        m.log(false, reason)
        return Result(false, "", reason)
    }

    /** Build one WmBubble for the whole crop using the existing geometry helpers (built-in mask path). */
    private fun buildBubble(bmp: Bitmap, blocks: List<com.ashraf.novelstudio.OcrBlock>, src: String): WmBubble {
        val rects = blocks.map { com.ashraf.novelstudio.WmBubbleStyle.rectOf(it, bmp.width, bmp.height) }
        // Cover box = union of all recognised boxes (a crop is usually one bubble).
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var btm = Int.MIN_VALUE
        for (rc in rects) { l = minOf(l, rc[0]); t = minOf(t, rc[1]); r = maxOf(r, rc[2]); btm = maxOf(btm, rc[3]) }
        val style = try { com.ashraf.novelstudio.WmBubbleStyle.analyse(bmp, intArrayOf(l, t, r, btm), rects, expand = true) }
            catch (_: Throwable) { null }
        val sl = style?.l ?: l; val st = style?.t ?: t; val sr = style?.r ?: r; val sb = style?.b ?: btm
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        // Built-in mask (BubbleFit): largest write-rectangle inside the bubble, when a shape is found.
        var tcx = 0f; var tcy = 0f; var tbw = 0f; var tbh = 0f
        try {
            val fit = com.ashraf.novelstudio.BubbleFit.analyse(bmp, intArrayOf(sl, st, sr, sb), rects)
            if (fit != null) {
                val safe = fit.safe
                tcx = ((safe[0] + safe[2]) / 2f) / bw
                tcy = ((safe[1] + safe[3]) / 2f) / bh
                tbw = (safe[2] - safe[0]) / bw
                tbh = (safe[3] - safe[1]) / bh
            }
        } catch (_: Throwable) { }
        val text = blocks.inReadingOrder().toParagraph()
        // Font size comes from the engine's own line height, not from the box. Measuring the bubble height
        // made every short-bubble translation enormous ("text fills the whole screen"), because a 3-line
        // crop is far taller than one glyph. Fall back to the median block height only when the engine
        // reported nothing.
        val fontPx = blocks.mapNotNull { b -> b.fontPx.takeIf { it > 0f } }
            .sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val fontNorm = (if (fontPx > 0f) fontPx else (sb - st).toFloat() / 3f) / bw
        // When no bubble shape was found, the text rectangle stays the OCR box itself (never the whole crop),
        // so the translation is fitted into what was actually read instead of spilling over the panel.
        val hasFit = tbw > 0f && tbh > 0f
        return WmBubble(
            src = src,
            cx = ((sl + sr) / 2f) / bw, cy = ((st + sb) / 2f) / bh,
            w = (sr - sl) / bw, h = (sb - st) / bh,
            angle = 0f, font = fontNorm.coerceIn(0.002f, 0.25f),
            bg = style?.bg ?: 0xFFFFFFFF.toInt(), fg = style?.fg ?: 0xFF000000.toInt(),
            fg2 = style?.fg2 ?: 0xFF000000.toInt(),
            soft = style?.soft ?: false, stroke = style?.stroke ?: 0,
            sourceText = text,
            // No fit rectangle means no bubble shape was found: keep the writer on the OCR box rather than
            // letting it size text against the full crop.
            tcx = tcx, tcy = tcy, tbw = if (hasFit) tbw else 0f, tbh = if (hasFit) tbh else 0f
        )
    }

    /** Downscale so the longest side ≤ [maxDim]; returns the SAME bitmap when no scaling is needed. */
    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val k = maxDim.toFloat() / longest
        val w = (src.width * k).toInt().coerceAtLeast(1)
        val h = (src.height * k).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** One slightly-expanded capture, still a crop of the tapped image. */
    private fun expandCrop(host: Host, src: String, x: Float, y: Float, w: Float, h: Float): Bitmap? {
        val e = ManualExtractConfig.EXPAND_RETRY
        val nx = (x - w * e).coerceIn(0f, 0.99f)
        val ny = (y - h * e).coerceIn(0f, 0.99f)
        val nw = (w * (1f + 2f * e)).coerceAtMost(1f - nx)
        val nh = (h * (1f + 2f * e)).coerceAtMost(1f - ny)
        return try { host.captureCrop(src, nx, ny, nw, nh) } catch (_: Throwable) { null }
    }
}
