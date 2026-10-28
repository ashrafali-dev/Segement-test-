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
        const val MAX_FORCE_DIM = 1600
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

        if (useLens) {
            val lensBlocks = try {
                val e = lens ?: LensOcrEngine(app).also { lens = it }
                // Fresh: bypassCache also skips the short-lived empty-region cache, which would otherwise
                // answer a user-selected crop with "empty" just because realtime scanned that area before.
                e.recognizeFresh(bitmap, script, fastMode = false).blocks.filter { it.text.isNotBlank() }
            } catch (_: Throwable) { emptyList() }
            if (lensBlocks.isNotEmpty()) return Outcome(lensBlocks, "Google Lens")
            // No text from Lens → ML Kit on the SAME crop (never a different/larger image).
            if (SystemClock.elapsedRealtime() - t0 >= deadlineMs) return Outcome(emptyList(), "Google Lens")
        }
        val mlBlocks = try {
            val e = mlkit ?: MlKitOcrEngine().also { mlkit = it }
            // Full inference, not the fast path: the user marked this box to get every glyph.
            e.recognizeRegion(bitmap, script).blocks.filter { it.text.isNotBlank() }
        } catch (_: Throwable) { emptyList() }
        val engine = if (useLens) "ML Kit (Lens found none)" else "ML Kit"
        return Outcome(mlBlocks, engine)
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
