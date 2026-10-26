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

    data class Outcome(val blocks: List<OcrBlock>, val engine: String)

    /** Blocks whose text is non-blank. May be empty when neither engine saw text. */
    fun read(bitmap: Bitmap, script: OcrScript, deadlineMs: Long): Outcome {
        val t0 = SystemClock.elapsedRealtime()
        val useLens = Prefs.get(app, "ocrEngine", "mlkit") == "lens"

        if (useLens) {
            val lensBlocks = try {
                val e = lens ?: LensOcrEngine(app).also { lens = it }
                e.recognizeRegion(bitmap, script).blocks.filter { it.text.isNotBlank() }
            } catch (_: Throwable) { emptyList() }
            if (lensBlocks.isNotEmpty()) return Outcome(lensBlocks, "Google Lens")
            // No text from Lens → ML Kit on the SAME crop (never a different/larger image).
            if (SystemClock.elapsedRealtime() - t0 >= deadlineMs) return Outcome(emptyList(), "Google Lens")
        }
        val mlBlocks = try {
            val e = mlkit ?: MlKitOcrEngine().also { mlkit = it }
            e.recognizeFast(bitmap, script).blocks.filter { it.text.isNotBlank() }
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

/** Merge OCR blocks in reading order (top-left first) for one crop. */
internal fun List<OcrBlock>.inReadingOrder(): List<OcrBlock> =
    sortedWith(compareBy({ it.top }, { it.left }))

/** Concatenate block text into one paragraph (newlines become spaces, bubbles are joined by a blank line). */
internal fun List<OcrBlock>.toParagraph(): String =
    joinToString("\n\n") { it.text.replace('\n', ' ').trim() }.trim()

@Suppress("unused")
private fun OcrResult.isBlank() = blocks.all { it.text.isBlank() }
