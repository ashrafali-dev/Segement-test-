package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Defect C wiring: turns "the user marked this region" into a [UserRegionOcrEngine] run on a background thread,
 * independent of the scanning pipeline.
 *
 * Why its own path: in a Lens run the scanner would (a) run the ML Kit prefilter, (b) answer from the dedupe /
 * engine caches, (c) use fast mode — exactly the three shortcuts that made a marked box come back empty. The
 * user's box is read by the must-succeed chain instead, from the crop of the marked region, and cancelled cleanly
 * when the user leaves the screen or marks again.
 *
 * Threading: one daemon worker (`ns-user-region`). Every run checks its token before each rung and before
 * placing. Bitmaps this class creates are recycled on every path; the caller's bitmap is never touched.
 */
internal object UserRegionHost {
    /** Placement hook installed by the Activity: put [bubbles] on the overlay and translate them. */
    interface Placement {
        /**
         * [bubbles] carry region-space geometry (fractions of the marked crop, like every WmBubble fraction);
         * [placements] holds the canvas box per bubble computed as region top-left + block offset (no scale, no
         * scroll). [crop] is a copy of the region for colour sampling; it belongs to the callee and must be
         * recycled.
         */
        fun place(bubbles: List<WmBubble>, placements: List<CanvasBox>, src: String, crop: Bitmap, script: OcrScript)
    }

    @Volatile private var placement: Placement? = null
    fun install(p: Placement) { placement = p }

    private val seq = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ns-user-region").apply { isDaemon = true }
    }
    @Volatile private var lens: LensOcrEngine? = null
    @Volatile private var mlkit: MlKitOcrEngine? = null
    @Volatile private var busy = false

    /** Cancel the in-flight run (new mark, gesture released, screen left). */
    fun cancel() { seq.incrementAndGet() }

    fun isBusy(): Boolean = busy

    /**
     * Reads [region] (PANEL pixels) out of the panel's own [panelBytes] and places the text exactly over the
     * marked box. [onFailure] is called with the user-facing message already prepared (never silent).
     */
    fun start(
        context: Context, src: String, panelBytes: ByteArray, region: CanvasBox, panelTop: Int,
        script: OcrScript, onDone: (UserRegionResult) -> Unit = {}, onFailure: (String) -> Unit = {}
    ) {
        val token = seq.incrementAndGet()
        busy = true
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            var crop: Bitmap? = null
            var handedOver = false
            try {
                if (token != seq.get()) return@execute
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(panelBytes, 0, panelBytes.size, bounds)
                val w = bounds.outWidth; val h = bounds.outHeight
                if (w <= 0 || h <= 0) { onFailure(UserRegionOcr.failureMessage()); return@execute }
                val l = region.left.toInt().coerceIn(0, w - 1)
                val t = region.top.toInt().coerceIn(0, h - 1)
                val r = region.right.toInt().coerceIn(l + 4, w)
                val b = region.bottom.toInt().coerceIn(t + 4, h)
                val rect = android.graphics.Rect(l, t, r, b)
                // Region decoder: only the marked pixels are decoded, never the whole panel.
                @Suppress("DEPRECATION")
                val rd = try { BitmapRegionDecoder.newInstance(panelBytes, 0, panelBytes.size, false) } catch (_: Exception) { null }
                if (rd != null) {
                    try { crop = rd.decodeRegion(rect, BitmapFactory.Options()) } finally { rd.recycle() }
                }
                if (crop == null) {
                    var full: Bitmap? = null
                    try {
                        full = BitmapFactory.decodeByteArray(panelBytes, 0, panelBytes.size)
                        crop = full?.let { f -> Bitmap.createBitmap(f, l, t, r - l, b - t) }
                    } catch (_: Exception) { crop = null } finally {
                        // createBitmap shares pixels; the copy above is independent, so the full decode can go.
                        try { if (full != null && full !== crop && !full.isRecycled) full.recycle() } catch (_: Throwable) {}
                    }
                }
                val c = crop
                if (c == null || c.width < 8 || c.height < 8) {
                    onFailure(UserRegionOcr.failureMessage()); return@execute
                }
                val app = context.applicationContext
                val eng = UserRegionOcrEngine(
                    lensFull = { bmp, s -> (lens ?: LensOcrEngine(app).also { lens = it }).recognizeFresh(bmp, s, fastMode = false) },
                    lensFast = { bmp, s -> (lens ?: LensOcrEngine(app).also { lens = it }).recognizeFresh(bmp, s, fastMode = true) },
                    mlkit = { bmp, s -> (mlkit ?: MlKitOcrEngine().also { mlkit = it }).recognizeRegion(bmp, s) },
                    cancelled = { token != seq.get() }
                )
                val result = eng.ocrUserRegion(c, script)
                if (token != seq.get()) return@execute
                if (!result.ok) {
                    OcrDiag.log("USER_REGION", "no text in marked region ${l},${t} ${r - l}x${b - t}; not placing anything")
                    onFailure(UserRegionOcr.failureMessage())
                    onDone(result)
                    return@execute
                }
                val pl = placement
                if (pl == null) { onFailure(UserRegionOcr.failureMessage()); return@execute }
                // Exact placement: region top-left + block offset, no scale, no scroll (scroll is applied once,
                // at draw time, by placeOnCanvas). Bubble fractions are of the marked crop.
                val regionInPanel = CanvasBox(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
                val placementsLocal = result.placements(regionInPanel)
                val cw = c.width.toFloat().coerceAtLeast(1f)
                val bubbles = result.blocks.indices.map { i ->
                    val blk = result.blocks[i]
                    val pb = placementsLocal[i]
                    WmBubble(
                        src = src,
                        cx = ((pb.cx - l) / cw).coerceIn(0f, 1f),
                        cy = ((pb.cy - t) / c.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                        w = (pb.width / cw).coerceIn(0.001f, 1f),
                        h = (pb.height / c.height.toFloat().coerceAtLeast(1f)).coerceIn(0.001f, 1f),
                        angle = 0f, font = (blk.fontPx / cw).coerceIn(0.002f, 0.25f),
                        bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(),
                        sourceText = blk.text.replace(Regex("\\s+"), " ").trim()
                    )
                }
                OcrDiag.log("USER_REGION", "placed=${bubbles.size} engine=${result.engine?.label} " +
                    "region=${l},${t} ${r - l}x${b - t} panel=${w}x$h top=$panelTop totalMs=${SystemClock.elapsedRealtime() - t0}")
                handedOver = true
                OcrDiag.log("USER_REGION", "canvas=${placementsLocal.joinToString(" | ") { b ->
                    String.format(java.util.Locale.US, "[%.0f,%.0f,%.0f,%.0f]", b.left, b.top, b.right, b.bottom)
                }}")
                pl.place(bubbles, placementsLocal, src, c, script)
                onDone(result)
            } catch (e: Throwable) {
                OcrDiag.log("USER_REGION", "crashed ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                if (token == seq.get()) onFailure(UserRegionOcr.failureMessage())
            } finally {
                busy = false
                // Only recycle what was not handed to the placement hook.
                if (!handedOver) { try { crop?.recycle() } catch (_: Throwable) {} }
            }
        }
    }

    /** Release engines when the reader is destroyed. */
    fun release() {
        cancel()
        try { lens?.close() } catch (_: Throwable) {}
        try { mlkit?.close() } catch (_: Throwable) {}
        lens = null
        mlkit = null
        placement = null
    }
}
