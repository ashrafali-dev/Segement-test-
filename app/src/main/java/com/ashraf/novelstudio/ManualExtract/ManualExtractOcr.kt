package com.ashraf.novelstudio.manualextract

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.ashraf.novelstudio.LensOcrEngine
import com.ashraf.novelstudio.MlKitOcrEngine
import com.ashraf.novelstudio.OcrBlock
import com.ashraf.novelstudio.OcrResult
import com.ashraf.novelstudio.OcrScript
import com.ashraf.novelstudio.Prefs

/**
 * OCR façade for manual extract, on a CROP only.
 *
 * Lens first; if it yields nothing, ML Kit on the SAME crop. There is no router, no gate, no audit pass and
 * no full-panel fallback here: the crop is the whole product of this class.
 *
 * The shared engines are used through their public entry points; those files are not modified.
 */
internal class ManualExtractOcr(context: Context) {

    private val app = context.applicationContext
    private var lens: LensOcrEngine? = null
    private var mlkit: MlKitOcrEngine? = null

    private companion object {
        /**
         * Manual crops are only downscaled when they exceed this. Lens/ML Kit both lose small glyphs when a
         * big selection is shrunk first, so a user-marked box keeps far more resolution than the realtime cap.
         */
        const val MAX_FORCE_DIM = 2400
    }

    data class Outcome(val blocks: List<OcrBlock>, val engine: String)

    /**
     * Blocks whose text is non-blank. May be empty when neither engine saw text.
     *
     * This is the user's own selection, so nothing about it is guessed: the request always goes out fresh
     * (no image cache, no negative cache), and the crop is never shrunk beyond [MAX_FORCE_DIM]. A tap means
     * "there is text here", so the engines are asked at full quality rather than in fast mode.
     */
    fun read(bitmap: Bitmap, script: OcrScript, deadlineMs: Long): Outcome {
        val t0 = SystemClock.elapsedRealtime()
        val useLens = Prefs.get(app, "ocrEngine", "mlkit") == "lens"
        fun budget() = !Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() - t0 < deadlineMs
        fun infer(input: Bitmap, remote: Boolean): List<OcrBlock> = try {
            val found = if (remote) {
                val e = lens ?: LensOcrEngine(app).also { lens = it }
                e.recognizeFresh(input, script, fastMode = false).blocks
            } else {
                val e = mlkit ?: MlKitOcrEngine().also { mlkit = it }
                e.recognizeRegion(input, script).blocks
            }
            found.filter { it.text.isNotBlank() }
        } catch (_: Exception) { emptyList() }
        if (useLens) {
            val first = infer(bitmap, true)
            if (first.isNotEmpty()) return Outcome(first, "Google Lens (fresh region)")
        }
        if (!budget()) return Outcome(emptyList(), "OCR deadline")
        val local = infer(bitmap, false)
        if (local.isNotEmpty()) return Outcome(local, "ML Kit (fresh region)")
        // The frame is authoritative. Retry the SAME pixels at larger scale / contrast;
        // never invent text and never let a cached empty result satisfy this request.
        for (variant in 0..2) {
            if (!budget()) break
            val scale = minOf(2f, MAX_FORCE_DIM.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtLeast(1f)
            val ww = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val hh = (bitmap.height * scale).toInt().coerceAtLeast(1)
            var view: Bitmap? = null
            try {
                view = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
                val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                if (variant > 0) {
                    val gain = if (variant == 2) -1.5f else 1.6f
                    val bias = if (variant == 2) 320f else -65f
                    paint.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                        gain,0f,0f,0f,bias, 0f,gain,0f,0f,bias,
                        0f,0f,gain,0f,bias, 0f,0f,0f,1f,0f))
                }
                android.graphics.Canvas(view).drawBitmap(bitmap, null,
                    android.graphics.Rect(0, 0, ww, hh), paint)
                val engines = if (useLens) listOf(true, false) else listOf(false)
                for (remote in engines) {
                    if (!budget()) break
                    val found = infer(view, remote)
                    if (found.isNotEmpty()) {
                        val sx = bitmap.width.toFloat() / ww
                        val sy = bitmap.height.toFloat() / hh
                        val mapped = found.map { q -> q.copy(
                            top = (q.top * sy).toInt(), left = (q.left * sx).toInt(),
                            cx = q.cx * sx, cy = q.cy * sy, w = q.w * sx, h = q.h * sy,
                            fontPx = q.fontPx * minOf(sx, sy),
                            polygon = q.polygon.map { Pair(it.first * sx, it.second * sy) }) }
                        return Outcome(mapped, "${if (remote) "Google Lens" else "ML Kit"} (region recovery ${variant + 1})")
                    }
                }
            } catch (_: OutOfMemoryError) { break }
            finally { view?.recycle() }
        }
        return Outcome(emptyList(), "Region recovery exhausted; source retained")
    }

    fun close() {
        try { lens?.close() } catch (_: Throwable) {}
        try { mlkit?.close() } catch (_: Throwable) {}
        lens = null
        mlkit = null
    }
}

/**
 * Keep the text the user actually pointed at.
 *
 * The crop is taken around a tap, so it usually contains the target bubble plus part of its neighbour. Picking
 * the block nearest the tap keeps the translation on the tapped bubble. The block already carries the engine's
 * own line box (top/left/w/h), which is the only geometry needed — no detector model, no extra inference.
 *
 * If nothing lies within one block-height of the tap, everything is kept: a wide area selection should still
 * translate all of it rather than nothing.
 */
internal fun ManualExtractOcr.Outcome.narrowToTap(bmp: Bitmap, x: Float, y: Float): ManualExtractOcr.Outcome {
    if (blocks.size <= 1) return this
    val px = x.coerceIn(0f, 1f) * bmp.width
    val py = y.coerceIn(0f, 1f) * bmp.height
    var best: OcrBlock? = null
    var bestD = Float.MAX_VALUE
    for (b in blocks) {
        val bw = if (b.w > 0f) b.w else 1f
        val bh = if (b.h > 0f) b.h else 1f
        val dx = (px - b.cx).coerceIn(-bw / 2f, bw / 2f)
        val dy = (py - b.cy).coerceIn(-bh / 2f, bh / 2f)
        val d = kotlin.math.hypot(px - (b.cx + dx), py - (b.cy + dy))
        if (d < bestD) { bestD = d; best = b }
    }
    val target = best ?: return this
    val reach = (if (target.h > 0f) target.h else bmp.height * 0.1f) * 2.2f
    if (bestD > reach) return this
    // Take the nearest block plus any block that vertically overlaps it (multi-line bubble, vertical CJK column).
    val t = target
    val tTop = t.cy - t.h / 2f
    val tBot = t.cy + t.h / 2f
    val keep = blocks.filter { b ->
        val bTop = b.cy - b.h / 2f
        val bBot = b.cy + b.h / 2f
        b === t || (bTop < tBot && bBot > tTop && kotlin.math.abs(b.cx - t.cx) <= bmp.width * 0.45f)
    }
    return if (keep.isEmpty()) this else ManualExtractOcr.Outcome(keep, engine)
}

/** Merge OCR blocks in reading order (top-left first) for one crop. */
internal fun List<OcrBlock>.inReadingOrder(): List<OcrBlock> =
    sortedWith(compareBy({ it.top }, { it.left }))

/** Concatenate block text into one paragraph (newlines become spaces, bubbles are joined by a blank line). */
internal fun List<OcrBlock>.toParagraph(): String =
    joinToString("\n\n") { it.text.replace('\n', ' ').trim() }.trim()

@Suppress("unused")
private fun OcrResult.isBlank() = blocks.all { it.text.isBlank() }
