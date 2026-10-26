package com.ashraf.novelstudio.manualextract

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.ashraf.novelstudio.BubbleBuilder
import com.ashraf.novelstudio.BubbleDetector
import com.ashraf.novelstudio.BubbleDetectorCore
import com.ashraf.novelstudio.MarkerCodec
import com.ashraf.novelstudio.OcrDiag
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

        /**
         * Translate N bubbles in ONE chatbot request and place each answer at its own bubble.
         *
         * Manual Lite used to send everything as a single bubble, so three bubbles on screen became one giant
         * block with one marker. One request per bubble would be slower and would re-describe the same scene;
         * a single marked request keeps the context and gives per-bubble placement.
         */
        fun translateAndPlaceAll(
            bubbles: List<WmBubble>,
            prompt: String,
            script: OcrScript,
            onDone: (List<String?>) -> Unit
        )
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
    fun runOnBitmap(bitmap: Bitmap, src: String, script: OcrScript, cancelled: () -> Boolean = { false }): Result {
        val m = ManualExtractMetrics()
        val tStart = SystemClock.elapsedRealtime()
        if (cancelled()) { bitmap.recycle(); return fail(m, "cancelled") }
        m.cropW = bitmap.width; m.cropH = bitmap.height
        m.mark("capture")
        return ocrAndPlace(bitmap, src, 0f, 0f, 1f, 1f, false, m, tStart, script, cancelled)
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

            // 5) ONE bubble per detected bubble, each mapped back to source-image coordinates.
            val dets = detsFor(ocrBmp)
            // Erase the original lettering in the crop itself. cleanAndCache() is not used here: it is keyed by
            // an image URL and served through the WebView interceptor, while a manual crop is a one-off bitmap.
            // Same idea, applied in place: fill each detected bubble with its own colour.
            try {
                val erasedPx = com.ashraf.novelstudio.WebModeTextRemover.eraseDetected(ocrBmp, dets, outcome.blocks)
                if (erasedPx > 0) OcrDiag.log("ERASE", "manual crop pixels=$erasedPx dets=${dets?.size ?: 0}")
            } catch (_: Throwable) { }
            val groups = BubbleBuilder.group(outcome.blocks, dets)
            if (groups.isEmpty()) return fail(m, "no bubbles")
            val bubbles = groups.mapNotNull { g ->
                buildBubble(ocrBmp, g, src, x, y, w, h)
            }
            if (bubbles.isEmpty()) return fail(m, "no bubbles")
            m.blocks = bubbles.size
            if (cancelled()) return fail(m, "cancelled")

            // 6) One marked request for the whole selection, then place each answer at its own bubble.
            val prompt = MarkerCodec.build(bubbles, titleOffset = 0)
            val cacheKey = ManualExtractCache.key(prompt, script.name)
            val cached = ManualExtractCache.get(cacheKey)
            m.mark("build")
            if (cached != null) {
                host.translateAndPlaceAll(bubbles, prompt, script) { }
                m.mark("place_cached")
                m.log(true, "cache hit")
                return Result(true, cached, "ok")
            }
            host.translateAndPlaceAll(bubbles, prompt, script) { placed ->
                val joined = placed.filterNotNull().joinToString(" ")
                if (joined.isNotBlank()) ManualExtractCache.put(cacheKey, joined)
            }
            m.mark("translate_started")
            m.log(true, "started bubbles=${bubbles.size}")
            return Result(true, "", "ok")
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun fail(m: ManualExtractMetrics, reason: String): Result {
        m.log(false, reason)
        return Result(false, "", reason)
    }

    /**
     * Build ONE [WmBubble] for one detected group, mapped from crop pixels back to the SOURCE image.
     *
     * The crop is `[x, y, x+w, y+h]` in source-normalised coordinates. Everything measured inside the crop is
     * therefore converted with
     *
     *     srcX = x + (cropX / cropWidth)  * w
     *     srcY = y + (cropY / cropHeight) * h
     *     srcW = (cropW / cropWidth)      * w
     *
     * The writer treats `cx, cy, w, h, font` as fractions of the whole source image, so building them from the
     * crop directly (the old code) put every bubble in the wrong place and made the font enormous, because a
     * crop is only a fraction of the panel.
     */
    private fun buildBubble(
        bmp: Bitmap, g: BubbleBuilder.Group, src: String,
        x: Float, y: Float, w: Float, h: Float
    ): WmBubble? {
        val cw = bmp.width.toFloat(); val ch = bmp.height.toFloat()
        if (cw <= 0f || ch <= 0f) return null
        val rects = g.lines.map { com.ashraf.novelstudio.WmBubbleStyle.rectOf(it, bmp.width, bmp.height) }
        val style = try {
            com.ashraf.novelstudio.WmBubbleStyle.analyse(bmp, intArrayOf(g.l, g.t, g.r, g.b), rects, expand = true)
        } catch (_: Throwable) { null }
        val sl = style?.l ?: g.l
        val st = style?.t ?: g.t
        val sr = style?.r ?: g.r
        val sb = style?.b ?: g.b

        // BubbleFit supplies the largest write-rectangle inside the outline (used to fit the translation).
        var fitL = 0; var fitT = 0; var fitR = 0; var fitB = 0
        var hasFit = false
        try {
            val fit = com.ashraf.novelstudio.BubbleFit.analyse(bmp, intArrayOf(sl, st, sr, sb), rects)
            if (fit != null) {
                val safe = fit.safe
                if (safe[2] > safe[0] && safe[3] > safe[1]) {
                    fitL = safe[0]; fitT = safe[1]; fitR = safe[2]; fitB = safe[3]; hasFit = true
                }
            }
        } catch (_: Throwable) { }

        // Font: the engine's own line height (never the box height, which made short bubbles huge).
        val fontPx = g.fontPx.takeIf { it > 0f }
            ?: (g.lines.map { it.h }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] })
        if (fontPx <= 0f) return null

        // crop pixels -> source fractions
        fun fx(px: Float): Float = x + (px / cw) * w
        fun fy(py: Float): Float = y + (py / ch) * h
        // Font is a width fraction in the writer, so it must scale by the source width, not the crop width.
        val fontNorm = (fontPx / cw) * w

        val text = if (g.lines.any { it.vertical }) g.columnText(vertical = true) else g.text
        if (text.isBlank()) return null

        return WmBubble(
            src = src,
            cx = fx((sl + sr) / 2f), cy = fy((st + sb) / 2f),
            w = ((sr - sl) / cw) * w, h = ((sb - st) / ch) * h,
            angle = 0f, font = fontNorm.coerceIn(0.002f, 0.30f),
            bg = style?.bg ?: 0xFFFFFFFF.toInt(), fg = style?.fg ?: 0xFF000000.toInt(),
            fg2 = style?.fg2 ?: 0xFF000000.toInt(),
            soft = style?.soft ?: false, stroke = style?.stroke ?: 0,
            sourceText = text,
            tcx = if (hasFit) fx((fitL + fitR) / 2f) else 0f,
            tcy = if (hasFit) fy((fitT + fitB) / 2f) else 0f,
            tbw = if (hasFit) ((fitR - fitL) / cw) * w else 0f,
            tbh = if (hasFit) ((fitB - fitT) / ch) * h else 0f
        )
    }

    /** Detector output for this crop, or null when the model is not installed/enabled (pixel grouping then). */
    private fun detsFor(bmp: Bitmap): List<BubbleDetectorCore.Det>? = try {
        val ctx = ocr.app
        if (BubbleDetector.enabled(ctx)) BubbleDetector.detect(ctx, bmp) else null
    } catch (_: Throwable) { null }

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
