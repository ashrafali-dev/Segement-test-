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
 * Consumes only captured region pixels; bounded OCR recovery may enlarge/adjust them, and recycles every bitmap it
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
         * Translate [text] and place it over [bubbles] using the existing overlay (built-in mask). [text] holds one
         * paragraph per bubble, in the same order as [bubbles] (the paragraph order is what the [001] markers number).
         * [onDone] is called on the main thread with the translated text (blank = nothing placed).
         */
        fun translateAndPlace(bubbles: List<WmBubble>, text: String, script: OcrScript, onDone: (String) -> Unit)
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
    fun runOnBitmap(bitmap: Bitmap, src: String, script: OcrScript,
        x: Float = 0f, y: Float = 0f, w: Float = 1f, h: Float = 1f, cancelled: () -> Boolean = { false }): Result {
        val m = ManualExtractMetrics()
        val tStart = SystemClock.elapsedRealtime()
        if (cancelled()) { bitmap.recycle(); return fail(m, "cancelled") }
        m.cropW = bitmap.width; m.cropH = bitmap.height
        m.mark("capture")
        return ocrAndPlace(bitmap, src, x, y, w, h, false, m, tStart, script, cancelled)
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
            // ONE BUBBLE PER SPEECH BUBBLE. Before, every OCR block of the crop was fused into a single giant bubble
            // (union box + one paragraph): a full-screen Extract produced one huge block of huge letters that
            // covered the whole panel and had no relation to where the original lettering sat.
            val bubbles = buildBubbles(ocrBmp, outcome.blocks, src)
            if (bubbles.isEmpty()) return fail(m, "no text")
            val paragraph = bubbles.joinToString("\n\n") { it.sourceText }
            val cacheKey = ManualExtractCache.key(paragraph, script.name)
            val cached = if (ManualExtractConfig.USE_TRANSLATION_CACHE) ManualExtractCache.get(cacheKey) else null
            m.mark("build")
            if (cancelled()) return fail(m, "cancelled")
            if (cached != null) {
                host.translateAndPlace(bubbles, cached, script) { }
                m.mark("place_cached")
                m.log(true, "cache hit")
                return Result(true, cached, "ok")
            }
            // Translation is asynchronous on the Activity side; this call starts it and records the step.
            host.translateAndPlace(bubbles, paragraph, script) { translated ->
                if (ManualExtractConfig.USE_TRANSLATION_CACHE && translated.isNotBlank()) ManualExtractCache.put(cacheKey, translated)
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

    /**
     * Splits the recognised lines into speech bubbles (same grouping rules as the scanning pipeline: lines under each
     * other with a similar font are one sentence, an outline between two lines separates them) and builds ONE
     * [WmBubble] per group, in reading order. Each bubble keeps its own cover box, write rectangle, colours and font.
     */
    private fun buildBubbles(bmp: Bitmap, blocks: List<com.ashraf.novelstudio.OcrBlock>, src: String): List<WmBubble> {
        val live = blocks.filter { it.text.isNotBlank() && !com.ashraf.novelstudio.UiChromeFilter.isChrome(it.text) }
        if (live.isEmpty()) return emptyList()
        val allRects = live.map { com.ashraf.novelstudio.WmBubbleStyle.rectOf(it, bmp.width, bmp.height) }
        val groups = com.ashraf.novelstudio.BubbleGrouping.groups(live, live.indices.toList()) { a, b ->
            try { com.ashraf.novelstudio.OcrPost.outlineBetween(bmp, a, b) } catch (_: Throwable) { false }
        }
        val ordered = groups.map { g -> g.sortedWith(compareBy({ live[it].cy }, { live[it].cx })) }
            .sortedWith(compareBy({ g -> live[g.first()].cy }, { g -> live[g.first()].cx }))
        val out = ArrayList<WmBubble>(ordered.size)
        for (g in ordered) {
            val b = buildBubble(bmp, live, allRects, g, src)
            if (b != null) out.add(b)
        }
        return out
    }

    private fun buildBubble(
        bmp: Bitmap, blocks: List<com.ashraf.novelstudio.OcrBlock>, allRects: List<IntArray>,
        group: List<Int>, src: String
    ): WmBubble? {
        val text = com.ashraf.novelstudio.BubbleGrouping.joinLines(group.map { blocks[it].text.replace('\n', ' ') })
        if (text.isBlank()) return null
        val rects = group.map { allRects[it] }
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var btm = Int.MIN_VALUE
        for (rc in rects) { l = minOf(l, rc[0]); t = minOf(t, rc[1]); r = maxOf(r, rc[2]); btm = maxOf(btm, rc[3]) }
        // Other bubbles' boxes are obstacles for the colour/shape analysis, this bubble's own lines are not.
        val others = allRects.filterIndexed { i, _ -> i !in group }
        val style = try { com.ashraf.novelstudio.WmBubbleStyle.analyse(bmp, intArrayOf(l, t, r, btm), others, expand = true) }
            catch (_: Throwable) { null }
        val sl = style?.l ?: l; val st = style?.t ?: t; val sr = style?.r ?: r; val sb = style?.b ?: btm
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        // Built-in mask (BubbleFit): largest write-rectangle inside the bubble, when a shape is found.
        var tcx = 0f; var tcy = 0f; var tbw = 0f; var tbh = 0f
        try {
            val fit = com.ashraf.novelstudio.BubbleFit.analyse(bmp, intArrayOf(l, t, r, btm), others + rects)
            if (fit != null) {
                val safe = fit.safe
                tcx = ((safe[0] + safe[2]) / 2f) / bw
                tcy = ((safe[1] + safe[3]) / 2f) / bh
                tbw = (safe[2] - safe[0]) / bw
                tbh = (safe[3] - safe[1]) / bh
            }
        } catch (_: Throwable) { }
        // Font size comes from the engine's own line height, never from the box height (a 3-line bubble is far
        // taller than one glyph and made translations enormous).
        val fonts = group.mapNotNull { i -> blocks[i].fontPx.takeIf { it > 0f } }.sorted()
        val fontPx = if (fonts.isEmpty()) 0f else fonts[fonts.size / 2]
        val lineCount = group.size.coerceAtLeast(1)
        val fontNorm = (if (fontPx > 0f) fontPx else (btm - t).toFloat() / lineCount) / bw
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
            tcx = if (hasFit) tcx else 0f, tcy = if (hasFit) tcy else 0f,
            tbw = if (hasFit) tbw else 0f, tbh = if (hasFit) tbh else 0f
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
