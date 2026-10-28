package com.ashraf.novelstudio

import android.graphics.Bitmap

/** The engine that produced a user-region read (shown in the OCR report and the failure toast). */
internal enum class UserRegionEngine(val label: String) {
    LENS_FULL_2X("Lens full 2x"),
    LENS_FAST("Lens fast"),
    MLKIT("ML Kit"),
    LENS_FULL_4X_CONTRAST("Lens full 4x+contrast")
}

/** One rung of the chain: a fresh bitmap to read, and how its coordinates map back to the crop. */
internal data class RegionPlan(
    val engine: UserRegionEngine,
    /** Upscale applied to the crop before OCR (1 = the crop as-is). Coordinates are divided by this. */
    val upscale: Float,
    /** Whether the plan needs a contrast-stretched copy. */
    val contrast: Boolean,
    /** The engine may not answer this (Manga OCR is only meaningful for Japanese). */
    val applicable: Boolean
)

/**
 * Defect C — a user-drawn region must succeed or fail loudly.
 *
 * A marked region is the user's statement that text is there. Every shortcut the ordinary scanner uses to
 * save time or bytes (ML Kit prefilter, dedupe, engine cache, fast mode) is a way to return nothing from a
 * box the user is looking at, so this chain bypasses all of them and stops at the first rung that reads text:
 *
 *   1. Lens, full quality, 2x upscale
 *   2. Lens, fast
 *   3. ML Kit, full inference on the crop
 *   4. Lens, full quality, 4x upscale + contrast stretch
 *   5. (Manga OCR — Japanese only; see [UserRegionOcr.CHAIN] note: needs the ONNX manga model, not bundled)
 *   6. failure, with a reason the UI shows verbatim
 *
 * It never fabricates text: a rung "succeeds" only with at least one letter/digit.
 */
internal object UserRegionOcr {
    /** Minimum letters/digits for a rung to count as a read (a lone mark is not a translation). */
    const val MIN_LETTERS = 1

    /**
     * The chain, in order. Manga OCR is gated to Japanese; it is not part of the ordered list today because the
     * app ships no manga OCR model (Lens/ML Kit already read Japanese, and rungs 1-4 cover it). Keeping the
     * gate here documents the contract: if the model is added, it goes between rungs 4 and 5.
     */
    fun chain(script: OcrScript): List<RegionPlan> = listOf(
        RegionPlan(UserRegionEngine.LENS_FULL_2X, 2f, contrast = false, applicable = true),
        RegionPlan(UserRegionEngine.LENS_FAST, 1f, contrast = false, applicable = true),
        RegionPlan(UserRegionEngine.MLKIT, 1f, contrast = false, applicable = true),
        RegionPlan(UserRegionEngine.LENS_FULL_4X_CONTRAST, 4f, contrast = true, applicable = true)
    )

    /** Localised failure text; the toast/overlay shows exactly this (app i18n: Bengali default, English fallback). */
    fun failureMessage(bengali: Boolean = true): String =
        if (bengali) "এই অংশ থেকে লেখা পাওয়া যায়নি — আরেকটু বড় করে মার্ক করুন"
        else "No text could be read from this area — please mark a slightly larger region"

    fun letters(blocks: List<OcrBlock>): Int = blocks.sumOf { b -> b.text.count { it.isLetterOrDigit() } }

    /** Map a rung's result back to crop pixels (upscales and contrast copies keep the same aspect). */
    fun mapBack(blocks: List<OcrBlock>, plan: RegionPlan): List<OcrBlock> {
        if (plan.upscale <= 1f) return blocks
        val u = plan.upscale
        return blocks.map { b ->
            b.copy(
                top = (b.top / u).toInt(), left = (b.left / u).toInt(),
                cx = b.cx / u, cy = b.cy / u, w = b.w / u, h = b.h / u,
                fontPx = b.fontPx / u,
                polygon = b.polygon.map { (x, y) -> Pair(x / u, y / u) }
            )
        }
    }
}

/** Result of [UserRegionOcrEngine.ocrUserRegion]: either text (never silently empty) or a visible reason. */
internal data class UserRegionResult(
    val blocks: List<OcrBlock>,
    val engine: UserRegionEngine?,
    /** Section 1 of the debug log: which rung succeeded, per-rung outcome, total ms. */
    val trace: String
) {
    val ok: Boolean get() = blocks.isNotEmpty()

    /** Text placed exactly over the marked region: region top-left + block offset, no scale, no scroll. */
    fun placements(regionInPanel: CanvasBox): List<CanvasBox> = blocks.map { b ->
        regionBox(regionInPanel.left, regionInPanel.top, regionInPanel.width, regionInPanel.height, b.cx, b.cy, b.w, b.h)
    }
}

/**
 * Runs the chain over a crop of the user-marked region. Engines are injected as lambdas so the ORDER (and the
 * fact that a successful rung stops the chain) is unit-testable without Android OCR.
 */
internal class UserRegionOcrEngine(
    private val lensFull: (Bitmap, OcrScript) -> OcrResult,
    private val lensFast: (Bitmap, OcrScript) -> OcrResult,
    private val mlkit: (Bitmap, OcrScript) -> OcrResult,
    private val cancelled: () -> Boolean = { false }
) {
    /**
     * [crop] is the user's region in panel pixels. Returns the first non-empty read, or a failure result whose
     * [UserRegionResult.trace] says which rungs were tried and why they lost. Never returns an empty success.
     * Every bitmap it creates is recycled; [crop] itself belongs to the caller and is never recycled here.
     */
    fun ocrUserRegion(crop: Bitmap, script: OcrScript): UserRegionResult {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val log = StringBuilder()
        val plans = UserRegionOcr.chain(script)
        for (plan in plans) {
            if (cancelled()) { log.append(plan.engine.label).append(":cancelled; "); break }
            if (!plan.applicable) { log.append(plan.engine.label).append(":n/a; "); continue }
            var work: Bitmap? = null
            var stretched: Bitmap? = null
            try {
                // Upscale clamped to MAX_SIDE (a big selection must not OOM a 192 MB device); the factor that
                // was actually applied is what coordinates are mapped back with.
                var used = plan
                if (plan.upscale > 1.01f) {
                    val longest = maxOf(crop.width, crop.height).coerceAtLeast(1)
                    val k = minOf(plan.upscale, MAX_SIDE / longest.toFloat()).coerceAtLeast(1f)
                    used = plan.copy(upscale = k)
                    work = if (k > 1.01f) Bitmap.createScaledBitmap(crop, (crop.width * k).toInt().coerceAtLeast(8),
                        (crop.height * k).toInt().coerceAtLeast(8), true) else crop
                } else work = crop
                // Contrast-stretched copy only for rung 4; the toggle keeps the chain testable off-device.
                val w0 = work
                val prepared = UserRegionPreprocess.apply(w0, plan.contrast)
                if (prepared !== w0) stretched = prepared
                val sent = prepared
                val r = when (plan.engine) {
                    UserRegionEngine.LENS_FULL_2X, UserRegionEngine.LENS_FULL_4X_CONTRAST -> lensFull(sent, script)
                    UserRegionEngine.LENS_FAST -> lensFast(sent, script)
                    UserRegionEngine.MLKIT -> mlkit(sent, script)
                }
                val blocks = r.blocks.filter { it.text.isNotBlank() }
                val letters = UserRegionOcr.letters(blocks)
                log.append(plan.engine.label).append(":blocks=").append(blocks.size).append(",letters=").append(letters).append("; ")
                if (letters >= UserRegionOcr.MIN_LETTERS) {
                    val mapped = UserRegionOcr.mapBack(blocks, used)
                    val trace = "chain=${plan.engine.label} ${log}totalMs=${android.os.SystemClock.elapsedRealtime() - t0}"
                    OcrDiag.log("USER_REGION", "success $trace")
                    return UserRegionResult(mapped, plan.engine, trace)
                }
            } catch (e: Throwable) {
                log.append(plan.engine.label).append(":err=").append(e.javaClass.simpleName).append("; ")
            } finally {
                // Local copies so the captured variables are read once (the contrast copy may alias the upscale).
                val up = work
                val st = stretched
                if (st != null && st !== up && !st.isRecycled) st.recycle()
                if (up != null && up !== crop && !up.isRecycled) up.recycle()
            }
        }
        val trace = "chain=FAILED ${log}totalMs=${android.os.SystemClock.elapsedRealtime() - t0}"
        OcrDiag.log("USER_REGION", trace)
        return UserRegionResult(emptyList(), null, trace)
    }

    private companion object {
        /** Longest side of an upscaled rung. 3200 keeps a 4x read of a 800px crop inside ~10 MP. */
        const val MAX_SIDE = 3200f
    }
}
