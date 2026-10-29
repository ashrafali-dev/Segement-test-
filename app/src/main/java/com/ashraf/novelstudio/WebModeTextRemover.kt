package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
    // Residual is normalized to a 0..100 percentage of full RGB contrast across the text mask.
    private const val TIER2_RESIDUAL_THRESHOLD = 5f
    private const val TIER3_RESIDUAL_THRESHOLD = 8f
    private const val CLEAN_FAILED_RESIDUAL_THRESHOLD = 15f
    private const val BBOX_DILATION_PX = 3
    private const val TIER1_FILL_ALPHA = 0.98f
    private const val TIER2_FILL_ALPHA = 0.98f
    private const val TIER3_FILL_ALPHA = 1.00f
    @Volatile var lastResidualMax: Float = 0f
        private set
    private val cleanSemaphore = Semaphore(2, true)
    private val cleanDurationMs = ThreadLocal<Long>()
    fun lastCleanMsForThread(): Long = cleanDurationMs.get() ?: 0L

    private const val MAX_WIDTH = 4096
    private const val MAX_HEIGHT = 20000
    private const val MAX_PIXELS = 14_000_000L
    private const val MAX_BYTES = 18 * 1024 * 1024

    fun cleanAndCache(
        context: Context,
        sourceUrl: String,
        encoded: ByteArray,
        bubbles: List<WmBubble>,
        allowAi: Boolean = true,
        realtimeMode: Boolean = false
    ): Boolean {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var acquired = false
        try {
            acquired = try { cleanSemaphore.tryAcquire(1, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            if (!acquired) {
                OcrDiag.log("MEM_GUARD", "clean_skipped_concurrency activeLimit=2 waitMs=1000")
                return false
            }
            return cleanAndCacheUnlocked(context, sourceUrl, encoded, bubbles, allowAi, realtimeMode)
        } finally {
            if (acquired) cleanSemaphore.release()
            cleanDurationMs.set(android.os.SystemClock.elapsedRealtime() - startedAt)
        }
    }

    private fun cleanAndCacheUnlocked(
        context: Context,
        sourceUrl: String,
        encoded: ByteArray,
        bubbles: List<WmBubble>,
        allowAi: Boolean,
        realtimeMode: Boolean
    ): Boolean {
        lastResidualMax = 0f
        val callStartedMs = android.os.SystemClock.elapsedRealtime()
        fun earlySkip(reason: String): Boolean {
            OcrDiag.log(
                "AI_PANEL",
                "allowAi=true policy=tiered tier1=0 tier2=0 tier3=0 aiExecutionAllowed=$allowAi " +
                    "calls=0 ok=0 aiMs=0 elapsedMs=${android.os.SystemClock.elapsedRealtime() - callStartedMs} " +
                    "removedPixels=0 residual_max=0 skip=$reason"
            )
            return false
        }
        if (!Prefs.bool(context, "wmTextRemoval2", true)) return earlySkip("text_removal_disabled")
        if (sourceUrl.isBlank() || encoded.size !in 1..MAX_BYTES || bubbles.isEmpty()) return earlySkip("invalid_panel_or_no_bubbles")
        if (isAnimatedOrUnsupported(encoded)) return earlySkip("unsupported_image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_WIDTH || height !in 1..MAX_HEIGHT ||
            width.toLong() * height.toLong() > MAX_PIXELS) return earlySkip("image_dimensions_out_of_bounds")
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        val freeHeapMb = (runtime.maxMemory() - usedBytes) / 1_048_576L
        val estimatedBitmapMb = ((width.toLong() * height.toLong() * 4L) + 1_048_575L) / 1_048_576L
        if (freeHeapMb < 30L || freeHeapMb < estimatedBitmapMb + 12L) {
            OcrDiag.log("MEM_GUARD", "clean_skipped_oom freeHeapMB=$freeHeapMb estimateMB=$estimatedBitmapMb")
            return earlySkip("memory_headroom")
        }

        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return earlySkip("decode_failed")
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) decoded
            else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                ?: run { decoded.recycle(); return earlySkip("bitmap_copy_failed") }

        val panelStartedMs = android.os.SystemClock.elapsedRealtime()
        var aiCallsAttempted = 0
        var aiCallsSucceeded = 0
        var aiTotalMs = 0L
        var panelRemovedForLog = 0
        var tier1Count = 0
        var tier2Count = 0
        var tier3Count = 0
        var panelResidualMax = 0f
        try {
            var totalRemoved = 0
            // Full scans may run MI-GAN on each hard bubble; realtime keeps a strict three-call budget.
            var aiBudget = if (realtimeMode) 3 else Int.MAX_VALUE
            val aiEnabledPref = Prefs.bool(context, "wmAiInpaint", true)
            val aiModelReady = WebModeInpainter.isReady(context)
            val aiCoolingDown = WebModeInpainter.tooSlow
            val aiLoadedAtStart = WebModeInpainter.isLoaded()
            val miganStatusReason = when {
                !aiEnabledPref -> "disabled"
                !allowAi -> "not_allowed"
                !aiModelReady -> "file_missing"
                aiCoolingDown -> "cooldown"
                aiLoadedAtStart -> "ready"
                realtimeMode -> "not_loaded_realtime"
                else -> "not_loaded_scan_can_lazy_load"
            }
            OcrDiag.log(
                "MIGAN_STATUS",
                "loaded=$aiLoadedAtStart reason=$miganStatusReason mode=${if (realtimeMode) "realtime" else "scan"}"
            )
            // Scan mode may load lazily if a hard bubble needs Tier 3. Realtime only warms in background.
            val aiWanted = allowAi && aiEnabledPref && aiModelReady && !aiCoolingDown &&
                (!realtimeMode || aiLoadedAtStart)
            if (allowAi && aiEnabledPref && aiModelReady && !aiCoolingDown && !aiLoadedAtStart) {
                WebModeInpainter.warmUpAsync(context)
            }
            val aiStartMs = android.os.SystemClock.elapsedRealtime()
            val aiStartMs = android.os.SystemClock.elapsedRealtime()
            var aiLastMs = 0L
            // Every bubble whose lettering was really erased (flat AND artwork). The writer only drops its
            // opaque cover box for bubbles in this set; any other bubble keeps the safe box fallback.
            val erased = HashSet<String>()
            val boundedBubbles = bubbles.asSequence()
                .filter { it.sourceText.isNotBlank() && it.w > 0f && it.h > 0f }
                .take(500)
                .toList()

            // Do NOT paint the entire speech-bubble interior from cv: that flattens paper texture/gradients and
            // can turn the bubble into a visibly boxy patch. cv is contour/placement guidance, not a text mask.
            // The loop below edits only pixels selected as lettering inside the OCR box plus a small safety margin.
            // If the local glyph mask is not confident, leave this bubble for the writer's conservative fallback.
            for ((bubbleIndex, bubble) in boundedBubbles.withIndex()) {
                val bubbleNo = bubbleIndex + 1
                val cx = bubble.cx * width
                val cy = bubble.cy * height
                val bw = bubble.w * width
                val bh = bubble.h * height
                if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) {
                    OcrDiag.log("MASK_DILATE", "bubble=$bubbleNo origArea=0 dilatedArea=0 increasePct=0.0 scope=bbox pad=$BBOX_DILATION_PX reason=invalid_bbox")
                    continue
                }
                // Widen the OCR box before mask creation: OCR geometry often cuts antialiased letter edges.
                val origOx0 = (cx - bw / 2f).toInt().coerceIn(0, width - 1)
                val origOy0 = (cy - bh / 2f).toInt().coerceIn(0, height - 1)
                val origOx1 = ceil(cx + bw / 2f).toInt().coerceIn(origOx0 + 1, width)
                val origOy1 = ceil(cy + bh / 2f).toInt().coerceIn(origOy0 + 1, height)
                val origBoxArea = (origOx1 - origOx0).toLong() * (origOy1 - origOy0).toLong()
                val ox0 = (origOx0 - BBOX_DILATION_PX).coerceAtLeast(0)
                val oy0 = (origOy0 - BBOX_DILATION_PX).coerceAtLeast(0)
                val ox1 = (origOx1 + BBOX_DILATION_PX).coerceAtMost(width)
                val oy1 = (origOy1 + BBOX_DILATION_PX).coerceAtMost(height)
                val dilatedBoxArea = (ox1 - ox0).toLong() * (oy1 - oy0).toLong()
                val boxIncreasePct = if (origBoxArea <= 0L) 0.0 else
                    ((dilatedBoxArea - origBoxArea).toDouble() * 100.0 / origBoxArea.toDouble())
                OcrDiag.log(
                    "MASK_DILATE",
                    "bubble=$bubbleNo origArea=$origBoxArea dilatedArea=$dilatedBoxArea " +
                        "increasePct=${String.format(java.util.Locale.US, "%.1f", boxIncreasePct)} " +
                        "scope=bbox pad=$BBOX_DILATION_PX"
                )
                val innerW = ox1 - ox0
                val innerH = oy1 - oy0
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
                // Dense bold CJK glyphs legitimately hold more ink than Latin text: a tight Japanese OCR box
                // around two heavy kanji columns is ~70 % ink, and the old 50 % cap threw the whole bubble away.
                // Only very dense (>= 85 %) masks are treated as artwork now; the shape-size limits above and
                // the on-artwork AI gate still decide whether this is really lettering.
                if (selectedCount < 4 || selectedCount > innerArea * (if (soft) 0.82 else 0.6)) continue

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
                val maskAreaOriginal = selected.count { it.toInt() != 0 }
                // Morphological dilation: binary 3x3 kernel, one iteration, excluding protected art.
                for (ry in 0 until rh) for (rx in 0 until rw) {
                    if (selected[ry * rw + rx].toInt() == 0) continue
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = rx + dx
                        val ny = ry + dy
                        if (nx !in 0 until rw || ny !in 0 until rh) continue
                        val ni = ny * rw + nx
                        if (mask[ni].toInt() == 0 && protect[ni].toInt() == 0) mask[ni] = 1
                    }
                }
                val maskAreaDilated = mask.count { it.toInt() != 0 }
                val maskIncreasePct = if (maskAreaOriginal == 0) 0.0 else
                    ((maskAreaDilated - maskAreaOriginal).toDouble() * 100.0 / maskAreaOriginal.toDouble())
                OcrDiag.log(
                    "MASK_DILATE",
                    "bubble=$bubbleNo origArea=$maskAreaOriginal dilatedArea=$maskAreaDilated " +
                        "increasePct=${String.format(java.util.Locale.US, "%.1f", maskIncreasePct)} " +
                        "scope=mask kernel=3x3 iterations=1 bboxPad=$BBOX_DILATION_PX"
                )
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

                // Tier 1: near-opaque median-ring paper fill, bounded to a rounded text box.
                // Textured/free lettering skips the solid fill and advances to inpainting.
                val paper = medianRingColor(region, rw, rh, ix0, iy0, ix1, iy1) ?: bg
                val tier1Start = android.os.SystemClock.elapsedRealtime()
                val tier1Region = region.copyOf()
                val fillPad = grow.coerceIn(1, 8)
                if (!soft && !bubble.free) {
                    fillRoundedRect(
                        tier1Region, rw, rh,
                        (ix0 - fillPad).coerceAtLeast(0), (iy0 - fillPad).coerceAtLeast(0),
                        (ix1 + fillPad).coerceAtMost(rw), (iy1 + fillPad).coerceAtMost(rh),
                        paper, TIER1_FILL_ALPHA, (fontPx * 0.18f).toInt().coerceIn(1, 12)
                    )
                }
                val residual1 = residualEnergy(tier1Region, mask, paper, soft, rw)
                OcrDiag.log(
                    "CLEAN_RESIDUAL",
                    "bubble=$bubbleNo tier=1 residual_max=${String.format(java.util.Locale.US, "%.2f", residual1)} " +
                        "threshold=${String.format(java.util.Locale.US, "%.2f", TIER2_RESIDUAL_THRESHOLD)} metric=normalized_0_100"
                )
                if (!soft && !bubble.free && residual1 <= TIER2_RESIDUAL_THRESHOLD) {
                    bitmap.setPixels(tier1Region, 0, rw, x0, y0, rw, rh)
                    totalRemoved += selectedCount
                    tier1Count++
                    panelResidualMax = max(panelResidualMax, residual1)
                    erased.add(WebModeTextRemovalCache.bubbleKey(bubble))
                    OcrDiag.log(
                        "CLEAN_TIER",
                        "bubble=$bubbleNo tier=1 ms=${android.os.SystemClock.elapsedRealtime() - tier1Start} alpha=$TIER1_FILL_ALPHA"
                    )
                    continue
                }
                OcrDiag.log(
                    "CLEAN_ESCALATE",
                    "bubble=$bubbleNo tierFrom=1 tierTo=2 residual=${String.format(java.util.Locale.US, "%.2f", residual1)} " +
                        "threshold=${String.format(java.util.Locale.US, "%.2f", TIER2_RESIDUAL_THRESHOLD)}"
                )
                // Tier 2: OpenCV Telea inpaint; apply a 0.98 fill alpha before measuring residual.
                val tier2Start = android.os.SystemClock.elapsedRealtime()
                if (bubble.free && !smoothSurround(region, mask, rw, rh) &&
                    (!aiWanted || (realtimeMode && !WebModeInpainter.isLoaded()))) {
                    panelResidualMax = max(panelResidualMax, residual1)
                    OcrDiag.log(
                        "CLEAN_RESIDUAL",
                        "bubble=$bubbleNo tier=2 residual_max=${String.format(java.util.Locale.US, "%.2f", residual1)} skipped=true"
                    )
                    if (residual1 > TIER2_RESIDUAL_THRESHOLD) {
                        OcrDiag.log(
                            "CLEAN_WARN",
                            "bubble=$bubbleNo residual_max=${String.format(java.util.Locale.US, "%.2f", residual1)} reason=ghost_likely"
                        )
                    }
                    if (residual1 > TIER3_RESIDUAL_THRESHOLD && !aiWanted) {
                        val reason = when {
                            !aiModelReady -> "file_missing"
                            !aiEnabledPref -> "disabled"
                            !allowAi -> "not_allowed"
                            else -> miganStatusReason
                        }
                        OcrDiag.log("CLEAN", "migan_unavailable reason=$reason tier3_skipped")
                    }
                    continue
                }
                val cvConfidence = if (!soft) 0.82f
                    else if (estimateFlatBackground(region, rw, rh, ix0, iy0, ix1, iy1) != null) 0.78f else 0.42f
                val cvOut = openCvInpaint(region, rw, rh, mask)
                val out = cvOut ?: region.copyOf()
                val tele = if (cvOut == null && soft) onionFill(
                    region, mask, rw, rh, (fontPx * 0.3f).toInt().coerceIn(4, 10), bg
                ) else null
                for (ry in 0 until rh) {
                    for (rx in 0 until rw) {
                        val at = ry * rw + rx
                        if (mask[at].toInt() == 0) continue
                        if (cvOut != null) {
                            out[at] = cvOut[at]
                            continue
                        }
                        if (soft) {
                            out[at] = if (tele != null) (0xFF000000.toInt() or (tele[at] and 0xFFFFFF))
                                else 0xFF000000.toInt() or
                                    gradientFill(region, mask, rw, rh, rx, ry, (fontPx * 1.6f).toInt().coerceIn(8, 60), bg)
                            continue
                        }
                        var sampleN = 0
                        var sumR = 0; var sumG = 0; var sumB = 0
                        var radius = 1
                        while (radius <= 9 && sampleN < 6) {
                            for (dy in -radius..radius) for (dx in -radius..radius) {
                                if (max(abs(dx), abs(dy)) != radius) continue
                                val nx = rx + dx; val ny = ry + dy
                                if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                                val ni = ny * rw + nx
                                if (mask[ni].toInt() != 0) continue
                                val p = region[ni]
                                if (distance(p, paper) > 72f) continue
                                sumR += red(p); sumG += green(p); sumB += blue(p); sampleN++
                            }
                            radius++
                        }
                        val fill = if (sampleN >= 3) (sumR / sampleN shl 16) or (sumG / sampleN shl 8) or (sumB / sampleN)
                            else paper and 0x00FFFFFF
                        out[at] = 0xFF000000.toInt() or fill
                    }
                }
                for (at in mask.indices) {
                    if (mask[at].toInt() != 0) out[at] = blendPixel(region[at], out[at], TIER2_FILL_ALPHA)
                }
                tier2Count++
                val tier2Ms = android.os.SystemClock.elapsedRealtime() - tier2Start
                var residualFinal = residualEnergy(out, mask, paper, soft, rw)
                OcrDiag.log(
                    "CLEAN_TIER",
                    "bubble=$bubbleNo tier=2 ms=$tier2Ms reason=tier1_incomplete alpha=$TIER2_FILL_ALPHA"
                )
                OcrDiag.log(
                    "CLEAN_RESIDUAL",
                    "bubble=$bubbleNo tier=2 residual_max=${String.format(java.util.Locale.US, "%.2f", residualFinal)} " +
                        "threshold=${String.format(java.util.Locale.US, "%.2f", TIER3_RESIDUAL_THRESHOLD)} metric=normalized_0_100"
                )

                // Tier 3 uses residual only (not CV confidence). Scans have no bubble cap; realtime keeps three calls.
                if (residualFinal > TIER3_RESIDUAL_THRESHOLD) {
                    OcrDiag.log(
                        "CLEAN_ESCALATE",
                        "bubble=$bubbleNo tierFrom=2 tierTo=3 residual=${String.format(java.util.Locale.US, "%.2f", residualFinal)} " +
                            "threshold=${String.format(java.util.Locale.US, "%.2f", TIER3_RESIDUAL_THRESHOLD)}"
                    )
                    val realtimeTimeExpired = realtimeMode &&
                        (android.os.SystemClock.elapsedRealtime() - aiStartMs + aiLastMs >= 8_000L)
                    val tier3Allowed = aiWanted && aiBudget > 0 &&
                        (!realtimeMode || WebModeInpainter.isLoaded()) && !realtimeTimeExpired
                    if (!tier3Allowed) {
                        val reason = when {
                            !aiEnabledPref -> "disabled"
                            !allowAi -> "not_allowed"
                            !aiModelReady -> "file_missing"
                            aiCoolingDown -> "cooldown"
                            realtimeMode && aiBudget <= 0 -> "realtime_budget"
                            realtimeTimeExpired -> "realtime_time_budget"
                            realtimeMode && !WebModeInpainter.isLoaded() -> "not_loaded_realtime"
                            else -> miganStatusReason
                        }
                        OcrDiag.log("CLEAN", "migan_unavailable reason=$reason tier3_skipped")
                    } else {
                        val heap = Runtime.getRuntime()
                        val freeHeapMb = (heap.maxMemory() - (heap.totalMemory() - heap.freeMemory())) / 1_048_576L
                        if (freeHeapMb < 30L) {
                            OcrDiag.log("MEM_GUARD", "migan_skipped_oom bubble=$bubbleNo freeHeapMB=$freeHeapMb")
                            OcrDiag.log("CLEAN", "migan_unavailable reason=low_heap tier3_skipped")
                            WebModeInpainter.releaseRuntimeForMemoryPressure()
                        } else {
                            val t3Start = android.os.SystemClock.elapsedRealtime()
                            aiBudget--
                            aiCallsAttempted++
                            tier3Count++
                            val aiFill = try {
                                WebModeInpainter.fill(context, bitmap, x0, y0, rw, rh, mask)
                            } catch (_: Throwable) { null }
                            aiLastMs = android.os.SystemClock.elapsedRealtime() - t3Start
                            aiTotalMs += aiLastMs
                            if (aiFill != null) {
                                aiCallsSucceeded++
                                for (at in mask.indices) {
                                    if (mask[at].toInt() != 0 && aiFill[at] != 0) {
                                        out[at] = blendPixel(region[at], aiFill[at], TIER3_FILL_ALPHA)
                                    }
                                }
                                residualFinal = residualEnergy(out, mask, paper, soft, rw)
                                OcrDiag.log(
                                    "CLEAN_TIER",
                                    "bubble=$bubbleNo tier=3 ms=$aiLastMs result=ok alpha=$TIER3_FILL_ALPHA " +
                                        "reason=tier2_residual conf=${String.format(java.util.Locale.US, "%.2f", cvConfidence)}"
                                )
                            } else {
                                OcrDiag.log(
                                    "CLEAN_TIER",
                                    "bubble=$bubbleNo tier=3 ms=$aiLastMs result=failed alpha=$TIER3_FILL_ALPHA"
                                )
                                OcrDiag.log("AI_PAINT", "region src=${sourceUrl.take(80)} box=${rw}x${rh} result=skipped/failed ms=$aiLastMs")
                            }
                            val afterHeap = Runtime.getRuntime()
                            val afterFreeMb = (afterHeap.maxMemory() - (afterHeap.totalMemory() - afterHeap.freeMemory())) / 1_048_576L
                            if (afterFreeMb < 30L) {
                                OcrDiag.log("MEM_GUARD", "migan_runtime_release_after_bubble bubble=$bubbleNo freeHeapMB=$afterFreeMb")
                                WebModeInpainter.releaseRuntimeForMemoryPressure()
                            }
                        }
                    }
                    OcrDiag.log(
                        "CLEAN_RESIDUAL",
                        "bubble=$bubbleNo tier=3 residual_max=${String.format(java.util.Locale.US, "%.2f", residualFinal)} metric=normalized_0_100"
                    )
                }
                panelResidualMax = max(panelResidualMax, residualFinal)
                OcrDiag.log(
                    "CLEAN_RESIDUAL",
                    "bubble=$bubbleNo tier=final residual_max=${String.format(java.util.Locale.US, "%.2f", residualFinal)} " +
                        "threshold=${String.format(java.util.Locale.US, "%.2f", TIER2_RESIDUAL_THRESHOLD)} metric=normalized_0_100"
                )
                if (residualFinal > TIER2_RESIDUAL_THRESHOLD) {
                    OcrDiag.log(
                        "CLEAN_WARN",
                        "bubble=$bubbleNo residual_max=${String.format(java.util.Locale.US, "%.2f", residualFinal)} " +
                            "reason=ghost_likely failed=${residualFinal > CLEAN_FAILED_RESIDUAL_THRESHOLD}"
                    )
                }
                bitmap.setPixels(out, 0, rw, x0, y0, rw, rh)
                // Only <5% residual suppresses the opaque bubble-cover fallback.
                if (residualFinal <= TIER2_RESIDUAL_THRESHOLD) {
                    totalRemoved += selectedCount
                    erased.add(WebModeTextRemovalCache.bubbleKey(bubble))
                }
            }
            panelRemovedForLog = totalRemoved
            lastResidualMax = panelResidualMax
            // Compact Japanese-script audit: show whether Lens/OCR found a bubble but the local cleaner
            // skipped it. This distinguishes OCR geometry/color-mask misses from MI-GAN timing without
            // logging the dialogue itself or emitting a separate line for every bubble.
            val japaneseBubbles = boundedBubbles.filter { bubble ->
                bubble.sourceText.any { ch ->
                    ch.code in 0x3040..0x30FF || ch.code in 0x3400..0x9FFF
                }
            }
            if (japaneseBubbles.isNotEmpty()) {
                val erasedJapanese = japaneseBubbles.count {
                    WebModeTextRemovalCache.bubbleKey(it) in erased
                }
                OcrDiag.log(
                    "CLEAN_JP_AUDIT",
                    "src=${sourceUrl.take(80)} detected=${japaneseBubbles.size} erased=$erasedJapanese " +
                        "unmatched=${japaneseBubbles.size - erasedJapanese} " +
                        "soft=${japaneseBubbles.count { it.soft }} allowAi=true policy=tiered"
                )
            }
            if (totalRemoved < 8 || panelResidualMax > CLEAN_FAILED_RESIDUAL_THRESHOLD) return false

            val bytesOut = ByteArrayOutputStream()
            // API 30+ supports lossless WebP. Older Android versions use lossless PNG instead
            // of re-encoding the whole comic page with lossy WebP.
            val modern = Build.VERSION.SDK_INT >= 30
            // Lossless WebP encoding took ~1 s of CPU per picture on a mid-range phone (a 126-picture chapter scan spent
            // minutes there). High-quality lossy WebP is several times faster and keeps the line art; switch the
            // pref wmCleanLossless on if the halftone dots of a title look soft.
            val lossless = Prefs.bool(context, "wmCleanLossless", false)
            val format = if (modern) (if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY)
                         else Bitmap.CompressFormat.PNG
            if (!bitmap.compress(format, if (modern && !lossless) 94 else 100, bytesOut)) return false
            val cleaned = bytesOut.toByteArray()
            if (cleaned.isEmpty() || cleaned.size > MAX_BYTES) return false
            val mime = if (modern) "image/webp" else "image/png"
            // Canvas and blob URLs cannot be replaced by the file-backed request interceptor. Keep only a small,
            // size-bounded inline data URL so the writer can show the locally cleaned pixels without retaining large
            // pages in memory. Oversized items use the small OCR-box cover instead of a false "cleaned" success.
            if (sourceUrl.startsWith("ns-canvas:", true) || sourceUrl.startsWith("blob:", true)) {
                val stored = WebModeTextRemovalCache.storeInlineCleanedBytes(sourceUrl, cleaned, mime)
                if (!stored) return false
                WebModeTextRemovalCache.setErased(sourceUrl, erased)
                return true
            }
            val stored = WebModeTextRemovalCache.store(context, sourceUrl, cleaned, mime)
            if (stored) WebModeTextRemovalCache.setErased(sourceUrl, erased)
            return stored
        } catch (_: OutOfMemoryError) {
            return false
        } catch (_: Exception) {
            return false
        } finally {
            lastResidualMax = panelResidualMax
            OcrDiag.log(
                "AI_PANEL",
                "allowAi=true policy=tiered tier1=$tier1Count tier2=$tier2Count tier3=$tier3Count aiExecutionAllowed=$allowAi " +
                    "calls=$aiCallsAttempted ok=$aiCallsSucceeded aiMs=$aiTotalMs " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - panelStartedMs} " +
                    "removedPixels=$panelRemovedForLog residual_max=${String.format(java.util.Locale.US, "%.2f", panelResidualMax)} residual_metric=normalized_0_100 failedThreshold=$CLEAN_FAILED_RESIDUAL_THRESHOLD"
            )
            val releasedKb = bitmap.byteCount / 1024L
            bitmap.recycle()
            OcrDiag.log("MEM_GUARD", "released panelBitmap freedKB=$releasedKb afterClean")
        }
    }

    /** Median channel colour from the ring around the text box. */
    private fun medianRingColor(region: IntArray, rw: Int, rh: Int, ix0: Int, iy0: Int, ix1: Int, iy1: Int): Int? {
        val inner = (ix1 - ix0).toLong() * (iy1 - iy0).toLong()
        val ring = rw.toLong() * rh.toLong() - inner
        if (ring < 24L) return null
        // Keep the median estimate bounded so big speech bubbles do not allocate hundreds of thousands of boxed pixels.
        val stride = max(1, kotlin.math.sqrt(ring.toDouble() / 1600.0).toInt())
        val capacity = min(1800, (ring / (stride * stride)).toInt() + 8).coerceAtLeast(8)
        val samples = ArrayList<Int>(capacity)
        var y = 0
        while (y < rh) {
            var x = 0
            while (x < rw) {
                if (x < ix0 || x >= ix1 || y < iy0 || y >= iy1) samples.add(region[y * rw + x])
                x += stride
            }
            y += stride
        }
        if (samples.size < 8) return null
        val rs = IntArray(samples.size) { red(samples[it]) }.also { it.sort() }
        val gs = IntArray(samples.size) { green(samples[it]) }.also { it.sort() }
        val bs = IntArray(samples.size) { blue(samples[it]) }.also { it.sort() }
        val mid = samples.size / 2
        return 0xFF000000.toInt() or (rs[mid] shl 16) or (gs[mid] shl 8) or bs[mid]
    }

    private fun fillRoundedRect(
        pixels: IntArray, rw: Int, rh: Int, x0: Int, y0: Int, x1: Int, y1: Int,
        color: Int, alpha: Float, radius: Int
    ) {
        if (rw <= 0 || rh <= 0 || x1 <= x0 || y1 <= y0) return
        val a = alpha.coerceIn(0f, 1f)
        val r = radius.coerceIn(0, min((x1 - x0) / 2, (y1 - y0) / 2))
        val cr = red(color); val cg = green(color); val cb = blue(color)
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(rh)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(rw)) {
                val dx = when {
                    x < x0 + r -> (x0 + r - x).toFloat()
                    x >= x1 - r -> (x - (x1 - r - 1)).toFloat()
                    else -> 0f
                }
                val dy = when {
                    y < y0 + r -> (y0 + r - y).toFloat()
                    y >= y1 - r -> (y - (y1 - r - 1)).toFloat()
                    else -> 0f
                }
                if (dx > 0f && dy > 0f && dx * dx + dy * dy > r * r) continue
                val at = y * rw + x
                val old = pixels[at]
                val rr = (cr * a + red(old) * (1f - a)).toInt().coerceIn(0, 255)
                val gg = (cg * a + green(old) * (1f - a)).toInt().coerceIn(0, 255)
                val bb = (cb * a + blue(old) * (1f - a)).toInt().coerceIn(0, 255)
                pixels[at] = 0xFF000000.toInt() or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    /** Mean remaining ink contrast on the text mask; textured panels use a local neighbourhood reference. */
    private fun residualEnergy(region: IntArray, mask: ByteArray, background: Int, soft: Boolean, width: Int): Float {
        var sum = 0.0
        var count = 0
        for (at in mask.indices) {
            if (mask[at].toInt() == 0) continue
            val p = region[at]
            val sample = if (soft) neighbourMean(region, mask, at, width) else null
            val reference = sample ?: background
            sum += (kotlin.math.abs(red(p) - red(reference)) + kotlin.math.abs(green(p) - green(reference)) +
                kotlin.math.abs(blue(p) - blue(reference))) / 3.0
            count++
        }
        return if (count == 0) 0f else (sum / count / 255.0 * 100.0).toFloat()
    }

    private fun blendPixel(original: Int, fill: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        val inv = 1f - a
        val r = (red(original) * inv + red(fill) * a).toInt().coerceIn(0, 255)
        val g = (green(original) * inv + green(fill) * a).toInt().coerceIn(0, 255)
        val b = (blue(original) * inv + blue(fill) * a).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun neighbourMean(region: IntArray, mask: ByteArray, at: Int, width: Int): Int? {
        if (width <= 0) return null
        val height = region.size / width
        val x = at % width; val y = at / width
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (radius in 1..5) {
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != radius) continue
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val i = ny * width + nx
                if (mask[i].toInt() != 0) continue
                val p = region[i]
                sr += red(p); sg += green(p); sb += blue(p); n++
            }
            if (n >= 3) break
        }
        return if (n == 0) null else 0xFF000000.toInt() or ((sr / n).toInt() shl 16) or
            ((sg / n).toInt() shl 8) or (sb / n).toInt()
    }

    /** Official OpenCV Telea inpaint, capped to <=250k pixels for low-memory phones. */
    private fun openCvInpaint(region: IntArray, rw: Int, rh: Int, mask: ByteArray): IntArray? {
        if (rw <= 0 || rh <= 0 || rw.toLong() * rh > 250_000L || mask.size != rw * rh) return null
        return try {
            if (!org.opencv.android.OpenCVLoader.initLocal()) return null
            val src = org.opencv.core.Mat(rh, rw, org.opencv.core.CvType.CV_8UC3)
            val maskMat = org.opencv.core.Mat(rh, rw, org.opencv.core.CvType.CV_8UC1)
            val dst = org.opencv.core.Mat()
            try {
                val srcBytes = ByteArray(region.size * 3)
                val maskBytes = ByteArray(mask.size)
                for (i in region.indices) {
                    val p = region[i]; val j = i * 3
                    srcBytes[j] = blue(p).toByte()
                    srcBytes[j + 1] = green(p).toByte()
                    srcBytes[j + 2] = red(p).toByte()
                    maskBytes[i] = if (mask[i].toInt() == 0) 0 else 255.toByte()
                }
                src.put(0, 0, srcBytes)
                maskMat.put(0, 0, maskBytes)
                org.opencv.photo.Photo.inpaint(src, maskMat, dst, 3.0, org.opencv.photo.Photo.INPAINT_TELEA)
                val output = ByteArray(region.size * 3)
                dst.get(0, 0, output)
                IntArray(region.size) { i ->
                    val j = i * 3
                    0xFF000000.toInt() or ((output[j + 2].toInt() and 255) shl 16) or
                        ((output[j + 1].toInt() and 255) shl 8) or (output[j].toInt() and 255)
                }
            } finally {
                src.release(); maskMat.release(); dst.release()
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Estimate the background from the ring OUTSIDE an OCR text box. Return a colour only when
     * enough ring pixels agree with the median; textured art therefore falls back to the normal mask
     * and non-AI local interpolation instead of being flattened.
     */
    private fun estimateFlatBackground(
        region: IntArray, rw: Int, rh: Int, ix0: Int, iy0: Int, ix1: Int, iy1: Int
    ): Int? {
        val inner = (ix1 - ix0).toLong() * (iy1 - iy0).toLong()
        val ring = rw.toLong() * rh.toLong() - inner
        if (ring < 24L) return null
        val stride = max(1, kotlin.math.sqrt(ring.toDouble() / 1600.0).toInt())
        val samples = ArrayList<Int>(min(1800, (ring / (stride * stride)).toInt() + 8))
        var y = 0
        while (y < rh) {
            var x = 0
            while (x < rw) {
                if (x < ix0 || x >= ix1 || y < iy0 || y >= iy1) samples.add(region[y * rw + x])
                x += stride
            }
            y += stride
        }
        if (samples.size < 12) return null
        val rs = IntArray(samples.size) { red(samples[it]) }.also { it.sort() }
        val gs = IntArray(samples.size) { green(samples[it]) }.also { it.sort() }
        val bs = IntArray(samples.size) { blue(samples[it]) }.also { it.sort() }
        val mid = samples.size / 2
        val paper = 0xFF000000.toInt() or (rs[mid] shl 16) or (gs[mid] shl 8) or bs[mid]
        var agreeing = 0
        for (p in samples) if (distance(p, paper) <= 30f) agreeing++
        return if (agreeing.toFloat() / samples.size >= 0.68f) paper else null
    }

    /**
     * Paints the paper colour over every pixel inside the bubble-interior rectangles [cv] (flat list of left, top, right,
     * bottom image fractions) that differs from it. Returns the number of pixels changed. If more than ~45 % of the
     * interior is dark/coloured it is not an empty bubble with lettering but artwork: nothing is touched.
     */
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
