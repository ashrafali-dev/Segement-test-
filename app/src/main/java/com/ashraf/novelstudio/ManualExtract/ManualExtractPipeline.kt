package com.ashraf.novelstudio.manualextract

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.ashraf.novelstudio.BubbleBuilder
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
         * Detector output for the WHOLE page image [src], in that image's pixels, or null when no model is
         * installed/enabled. Called at most once per manual run.
         *
         * Detection must run on the full image, not on the crop: a downscaled crop returns masks bound to a
         * throwaway bitmap that never reaches the page, while a full-image detection gives the same instance
         * silhouettes the realtime scan uses, so the crop just becomes a window into them.
         */
        fun detectPage(src: String): List<BubbleDetectorCore.Det>? = null

        /** Pixel size of the whole page image [src], or null when it is unknown (canvas / live capture). */
        fun pageSize(src: String): Pair<Int, Int>? = null

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
        // The selected rectangle itself is the user's target. x/y are its page-normalized origin,
        // not a tap point inside the cropped bitmap; never use them to discard OCR blocks within this selection.
        // 1) capture region only
        val raw = host.captureCrop(src, x, y, w, h) ?: return fail(m, "capture failed")
        m.mark("capture")
        return ocrAndPlace(raw, src, x, y, w, h, m, tStart, script, cancelled)
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
        return ocrAndPlace(bitmap, src, 0f, 0f, 1f, 1f, m, tStart, script, cancelled)
    }

    private fun ocrAndPlace(
        raw: Bitmap, src: String, x: Float, y: Float, w: Float, h: Float,
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
            var ocrBmp = downscale(crop, ManualExtractConfig.MAX_OCR_DIM)
            if (ocrBmp !== crop) { crop.recycle(); crop = ocrBmp }
            m.ocrW = ocrBmp.width; m.ocrH = ocrBmp.height
            m.mark("crop")
            if (cancelled()) return fail(m, "cancelled")

            // 4) OCR crop only. A mark means "there IS text here", so read it as a deliberate recovery: first a
            //    padded, full-quality read of exactly what was marked.
            var outcome = readMarked(ocrBmp, script, ManualExtractConfig.OCR_PAD_FIRST)
            // The selected crop is the authoritative region. Keep every OCR block inside it, including missing
            // lines near the top/left; x/y are not coordinates inside this cropped bitmap.
            m.mark("ocr")
            m.reads = 1

            // Escalate when the read is empty OR clearly incomplete. This used to happen only on a completely
            // empty result, so a half-read bubble (one line recognised, the other missed) was accepted as final
            // - which is exactly the "missing text" the user marked the region to recover.
            if (needsStrongerRead(outcome, ocrBmp) &&
                SystemClock.elapsedRealtime() - tStart < ManualExtractConfig.TOTAL_CAP_MS) {
                OcrDiag.log("MANUAL", "retry: blocks=${outcome.blocks.size} letters=${outcome.letters} " +
                    "reason=${if (outcome.blocks.isEmpty()) "empty" else "incomplete"}")
                // A wider crop of the SAME marked image. Still a crop, never the full panel; the original
                // coordinate origin is kept so every block still maps back onto the page.
                if (src.isNotBlank()) {
                    val expanded = expandCrop(host, src, x, y, w, h)
                    if (expanded != null) {
                        var e: Bitmap? = null
                        try {
                            e = downscale(expanded, ManualExtractConfig.MAX_OCR_DIM)
                            val retry = readMarked(e, script, ManualExtractConfig.OCR_PAD_RETRY)
                            m.reads++
                            if (retry.isBetterThan(outcome)) outcome = retry
                        } catch (_: Throwable) {
                            // keep the first outcome
                        } finally {
                            // The expanded bitmap is owned here; recycle it and the downscaled copy (if
                            // different) only AFTER OCR has finished with it.
                            if (e != null && e !== expanded && !e.isRecycled) e.recycle()
                            if (!expanded.isRecycled) expanded.recycle()
                        }
                    }
                }
                // Final recovery: if the remote Lens pass still leaves unexplained ink, run local ML Kit too,
                // even when Lens returned SOME text. A partial remote read is not success for a user-selected
                // missing-text region. Keep whichever pass actually recognised more text; never downgrade a good read.
                val needsLocalRecovery = outcome.blocks.isEmpty() ||
                    (needsStrongerRead(outcome, ocrBmp) && !outcome.engine.contains("ML Kit", ignoreCase = true))
                if (needsLocalRecovery &&
                    SystemClock.elapsedRealtime() - tStart < ManualExtractConfig.TOTAL_CAP_MS) {
                    OcrDiag.log("MANUAL", "local recovery: blocks=${outcome.blocks.size} letters=${outcome.letters} engine=${outcome.engine}")
                    val local = ocr.readLocalOnly(ocrBmp, script, ManualExtractConfig.OCR_CAP_MS)
                    m.reads++
                    if (local.isBetterThan(outcome)) outcome = local
                }

                m.mark("ocr_retry")
            }
            m.engine = outcome.engine
            m.blocks = outcome.blocks.size
            m.letters = outcome.letters
            if (outcome.blocks.isEmpty()) return fail(m, "no text")

            // Detector-guided cleanup writes pixels. Captured crops may be immutable, so promote the owned OCR
            // bitmap to a mutable ARGB copy when possible. Coordinates from OCR remain valid because the copy
            // has identical dimensions; the finally block still owns and recycles this replacement.
            if (!ocrBmp.isMutable) {
                val mutableCopy = try { ocrBmp.copy(Bitmap.Config.ARGB_8888, true) } catch (_: Throwable) { null }
                if (mutableCopy != null) {
                    if (mutableCopy !== ocrBmp) {
                        if (!ocrBmp.isRecycled) ocrBmp.recycle()
                        crop = mutableCopy
                        ocrBmp = mutableCopy
                    }
                }
            }

            // 5) ONE bubble per detected bubble, each mapped back to source-image coordinates.
            // Detections come from the FULL page image (host.detectPage) and are trimmed to this crop; the crop
            // therefore uses the same instance masks as the realtime scan instead of masks from a downscale.
            val page = host.pageSize(src)
            // Without the page size the crop's own pixel size is all that is known; the fractional mapping
            // stays exact (crop px / crop px), and only detector trimming loses the page origin.
            val cropRect = ManualCropGeometry.Crop(
                if (page == null) 0f else x, if (page == null) 0f else y,
                if (page == null) 1f else w, if (page == null) 1f else h,
                cropW = ocrBmp.width, cropH = ocrBmp.height,
                pageW = page?.first ?: cw, pageH = page?.second ?: ch
            )
            val dets = detsFor(src, cropRect)
            // Erase the original lettering in the crop itself. cleanAndCache() is not used here: it is keyed by
            // an image URL and served through the WebView interceptor, while a manual crop is a one-off bitmap.
            // The OCR line boxes are the text region; the model masks only constrain the fill.
            try {
                val erasedPx = com.ashraf.novelstudio.WebModeTextRemover.eraseDetected(ocrBmp, dets, outcome.blocks)
                if (erasedPx > 0) OcrDiag.log("ERASE", "manual crop pixels=$erasedPx dets=${dets?.size ?: 0}")
            } catch (_: Throwable) { }
            val groups = BubbleBuilder.group(outcome.blocks, dets)
            if (groups.isEmpty()) return fail(m, "no bubbles")
            val bubbles = groups.mapNotNull { g ->
                buildBubble(ocrBmp, g, src, cropRect)
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
     * One full-quality read of the marked pixels, with [padFraction] of whitespace-margin added around them.
     *
     * Padding is what stops a mark drawn tight on the glyphs from clipping their anti-aliased edges (and the
     * first/last character of every line). The result is mapped back onto the ORIGINAL crop, not the padded one,
     * so downstream geometry keeps using the same coordinates as the page.
     */
    private fun readMarked(bmp: Bitmap, script: OcrScript, padFraction: Float): ManualExtractOcr.Outcome {
        val padded = pad(bmp, padFraction)
        if (padded === bmp) return ocr.read(bmp, script, ManualExtractConfig.OCR_CAP_MS)
        val left = (bmp.width * padFraction).toInt()
        val top = (bmp.height * padFraction).toInt()
        return try {
            val inner = ocr.read(padded, script, ManualExtractConfig.OCR_CAP_MS)
            // Shift every box back into the unpadded crop's coordinate system.
            ManualExtractOcr.Outcome(inner.blocks.map { shiftBlock(it, -left, -top) }, inner.engine)
        } finally {
            if (!padded.isRecycled) padded.recycle()
        }
    }

    /** Copy [src] onto a larger canvas of its own paper colour; returns [src] itself when no padding is useful. */
    private fun pad(src: Bitmap, fraction: Float): Bitmap {
        if (fraction <= 0f) return src
        val px = (src.width * fraction).toInt()
        val py = (src.height * fraction).toInt()
        if (px < 3 && py < 3) return src
        if (src.width + 2 * px > ManualExtractConfig.MAX_OCR_DIM + px ||
            src.height + 2 * py > ManualExtractConfig.MAX_OCR_DIM + py) return src
        val w = src.width + 2 * px
        val h = src.height + 2 * py
        if (w.toLong() * h > 12_000_000L) return src
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            // The crop's own dominant colour is the only honest "outside" this picture has.
            canvas.drawColor(dominantColour(src))
            canvas.drawBitmap(src, px.toFloat(), py.toFloat(), null)
            out
        } catch (_: Throwable) {
            src
        }
    }

    private fun dominantColour(src: Bitmap): Int {
        // Four-corner sample: the crop border is what the mark touched, so its corners describe the margin.
        val xs = intArrayOf(0, src.width - 1, 0, src.width - 1)
        val ys = intArrayOf(0, 0, src.height - 1, src.height - 1)
        var r = 0; var g = 0; var b = 0
        for (i in 0 until 4) {
            val c = src.getPixel(xs[i].coerceIn(0, src.width - 1), ys[i].coerceIn(0, src.height - 1))
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        return 0xFF000000.toInt() or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
    }

    private fun shiftBlock(b: com.ashraf.novelstudio.OcrBlock, dx: Int, dy: Int): com.ashraf.novelstudio.OcrBlock =
        b.copy(
            top = b.top + dy, left = b.left + dx,
            cx = b.cx + dx, cy = b.cy + dy,
            polygon = b.polygon.map { (x, y) -> (x + dx) to (y + dy) }
        )

    /**
     * True when a read of [bmp] should be retried with a wider/stronger pass:
     *  - nothing was recognised at all, or
     *  - very few glyphs came back while the box is clearly full of ink (a partial line read).
     *
     * A box that genuinely holds no lettering (blank part of the balloon) stays "empty" and is reported as such;
     * the check is about the crop's ink, not about a fixed character count.
     */
    private fun needsStrongerRead(outcome: ManualExtractOcr.Outcome, bmp: Bitmap): Boolean {
        val letters = outcome.letters
        if (outcome.blocks.isEmpty()) return true
        if (letters < 2) return true
        val ink = inkFraction(bmp, outcome.blocks)
        return ink >= ManualExtractConfig.INCOMPLETE_INK_FRACTION && letters < 24
    }

    /** Share of the crop's dark/contrasting pixels that are NOT inside any recognised block. */
    private fun inkFraction(bmp: Bitmap, blocks: List<com.ashraf.novelstudio.OcrBlock>): Float {
        if (bmp.isRecycled || bmp.width < 8 || bmp.height < 8) return 0f
        val stepX = maxOf(1, bmp.width / 120)
        val stepY = maxOf(1, bmp.height / 120)
        var total = 0
        var unexplained = 0
        // Threshold on the crop's own mean, so a dark page with pale lettering is handled too.
        var sum = 0L
        var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return 0f
        val mean = (sum / n).toInt()
        val thresh = 52
        y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val l = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (Math.abs(l - mean) >= thresh) {
                    total++
                    val covered = blocks.any { b ->
                        x >= b.left && x < b.left + b.w && y >= b.top && y < b.top + b.h
                    }
                    if (!covered) unexplained++
                }
                x += stepX
            }
            y += stepY
        }
        if (total < 12) return 0f
        return unexplained.toFloat() / total
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
        bmp: Bitmap, g: BubbleBuilder.Group, src: String, crop: ManualCropGeometry.Crop
    ): WmBubble? {
        if (bmp.width <= 0 || bmp.height <= 0) return null
        val rects = g.lines.map { com.ashraf.novelstudio.WmBubbleStyle.rectOf(it, bmp.width, bmp.height) }
        val style = try {
            com.ashraf.novelstudio.WmBubbleStyle.analyse(bmp, intArrayOf(g.l, g.t, g.r, g.b), rects, expand = true)
        } catch (_: Throwable) { null }
        val sl = style?.l ?: g.l
        val st = style?.t ?: g.t
        val sr = style?.r ?: g.r
        val sb = style?.b ?: g.b

        // BubbleFit supplies the largest write-rectangle inside the outline (used to fit the translation).
        var safeRect: IntArray? = null
        try {
            val fit = com.ashraf.novelstudio.BubbleFit.analyse(bmp, intArrayOf(sl, st, sr, sb), rects)
            if (fit != null) {
                val safe = fit.safe
                if (safe[2] > safe[0] && safe[3] > safe[1]) safeRect = intArrayOf(safe[0], safe[1], safe[2], safe[3])
            }
        } catch (_: Throwable) { }

        // Font: the engine's own line height (never the box height, which made short bubbles huge).
        val fontPx = g.fontPx.takeIf { it > 0f }
            ?: (g.lines.map { it.h }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] })
        if (fontPx <= 0f) return null

        val text = if (g.lines.any { it.vertical }) g.columnText(vertical = true) else g.text
        if (text.isBlank()) return null
        val angle = g.lines.map { it.angle }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

        return ManualCropGeometry.toWmBubble(
            src = src,
            crop = crop,
            cover = intArrayOf(sl, st, sr, sb),
            safe = safeRect,
            fontPx = fontPx,
            angle = angle,
            bg = style?.bg ?: 0xFFFFFFFF.toInt(),
            fg = style?.fg ?: 0xFF000000.toInt(),
            fg2 = style?.fg2 ?: style?.fg ?: 0xFF000000.toInt(),
            soft = style?.soft ?: false,
            stroke = style?.stroke ?: 0,
            sourceText = text,
            modelMaskFit = safeRect != null
        )
    }

    /**
     * Detector output for this crop, or null when the model is not installed/enabled (pixel grouping then).
     *
     * Masked detections are taken from a full-image detection and trimmed to the crop ([ManualCropGeometry]).
     * The old code detected on the downscaled crop, so the masks described a throwaway bitmap and the
     * balloon outline was never used for erasing or for the safe text rectangle.
     */
    private fun detsFor(src: String, crop: ManualCropGeometry.Crop): List<BubbleDetectorCore.Det>? = try {
        ManualCropGeometry.detectionsInCrop(host.detectPage(src), crop)
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
