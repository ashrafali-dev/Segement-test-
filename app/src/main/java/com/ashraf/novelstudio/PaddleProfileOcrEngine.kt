package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Routes PP-OCRv6 Small/Medium to scripts covered by the downloaded PP-OCRv6 pack.
 *
 * PP-OCRv6 officially supports Japanese, so Japanese must use the selected Small/Medium model.
 * Its published language list does not include Korean, so Korean uses the optional "PP-OCRv5 Korean" pack when
 * it is installed and ML Kit's Korean recognizer otherwise.
 * The old custom ONNX path rotated tall crops and ran one inference per detected box; that was both
 * unreliable for upright vertical manga lettering and painfully slow on long webtoon panels.
 * The standalone RapidOCR/legacy option remains separate for explicit testing.
 */
internal class PaddleProfileOcrEngine(
    context: Context,
    tier: String
) : OcrEngine {
    private val appContext = context.applicationContext
    private val paddleV6 = PaddleV6OcrEngine(appContext, tier)
    private val paddleKo = PaddleV6OcrEngine(appContext, "korean")
    private val mlKitCjk = MlKitOcrEngine()
    @Volatile private var koCheckedVersion = -1
    @Volatile private var koInstalled = false
    @Volatile private var activeFamily: String = ""
    private var lastPaddleUseMs = 0L

    private companion object { const val PADDLE_IDLE_RELEASE_MS = 120_000L }

    init {
        PaddleOcrModels.init(appContext)
    }

    /** Which engine family reads [script] right now: "paddle-v6", "paddle-ko" or "mlkit-korean". */
    fun familyFor(script: OcrScript): String {
        if (script != OcrScript.KOREAN) return "paddle-v6"
        val v = PaddleOcrModels.version
        if (v != koCheckedVersion) {               // re-check the files only when a pack was (un)installed
            koInstalled = PaddleOcrModels.koreanInstalled()
            koCheckedVersion = v
        }
        return if (koInstalled) "paddle-ko" else "mlkit-korean"
    }

    private fun forLanguage(bitmap: Bitmap, script: OcrScript, call: (OcrEngine) -> OcrResult): OcrResult {
        // Korean: the downloadable PP-OCRv5 Korean pack when installed, otherwise ML Kit (the PP-OCRv6 Small/Medium
        // dictionaries do not cover Hangul). Logged on every call so the engine that really ran is never a guess.
        val family = familyFor(script)
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            // Every call arrives through OcrGate (RoutingOcrEngine.route), so no inference can be running here and
            // releasing is safe. Paddle sessions are released only after PADDLE_IDLE_RELEASE_MS without any Paddle
            // use (frees RAM during long ML Kit reads) -- not on every language switch, which reloaded ~140 MB.
            val now = System.nanoTime() / 1_000_000L
            if (family != "mlkit-korean") {
                lastPaddleUseMs = now
            } else if (lastPaddleUseMs != 0L && now - lastPaddleUseMs > PADDLE_IDLE_RELEASE_MS) {
                runCatching { paddleV6.close() }
                runCatching { paddleKo.close() }
                lastPaddleUseMs = 0L
                Log.i("NovelStudioOCR", "paddleProfile released idle Paddle sessions")
            }
            return call(when (family) { "paddle-ko" -> paddleKo; "mlkit-korean" -> mlKitCjk; else -> paddleV6 })
        } finally {
            if (activeFamily != family) {
                Log.i("NovelStudioOCR", "paddleProfile language=${script.name} activeFamily=$family " +
                    "(previous=${activeFamily.ifEmpty { "none" }})")
                activeFamily = family
            }
            Log.i("NovelStudioOCR", "paddleProfile language=${script.name} activeFamily=$family " +
                "ms=${android.os.SystemClock.elapsedRealtime() - t0}")
        }
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognize(bitmap, script) }

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognizeFast(bitmap, script) }

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognizeFallback(bitmap, script) }

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognizeRegion(bitmap, script) }

    override fun close() {
        runCatching { paddleV6.close() }
        runCatching { paddleKo.close() }
        runCatching { mlKitCjk.close() }
        activeFamily = ""
    }
}
