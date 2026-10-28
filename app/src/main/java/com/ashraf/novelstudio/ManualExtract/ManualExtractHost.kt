package com.ashraf.novelstudio.manualextract

import android.graphics.Bitmap
import com.ashraf.novelstudio.BubbleDetectorCore
import com.ashraf.novelstudio.OcrScript
import com.ashraf.novelstudio.WmBubble

/**
 * Glue between [ManualExtractPipeline] and the reader Activity. The Activity supplies two lambdas (capture a
 * crop of the tapped image; translate + place one bubble through the existing chatbot/overlay path). All of the
 * mode's logic stays in this package; the Activity only calls [install].
 */
internal object ManualExtractHost {

    fun install(
        capture: (src: String, x: Float, y: Float, w: Float, h: Float) -> Bitmap?,
        translateAndPlace: (bubble: WmBubble, text: String, script: OcrScript, onDone: (String) -> Unit) -> Unit,
        /** One marked request for N bubbles; the Activity places each answer at its own bubble. */
        translateAndPlaceAll: (bubbles: List<WmBubble>, prompt: String, script: OcrScript, onDone: (List<String?>) -> Unit) -> Unit,
        /** Detector output over the whole page image, or null when no model runs. */
        detectPage: (src: String) -> List<BubbleDetectorCore.Det>? = { null },
        /** Pixel size of the whole page image, or null when unknown. */
        pageSize: (src: String) -> Pair<Int, Int>? = { null },
        /** Called on the main thread with a short user-facing reason whenever a run does not place anything. */
        onFailure: (String) -> Unit = {}
    ) {
        if (!ManualExtractConfig.ENABLED) return
        ManualExtractController.onFailure = onFailure
        ManualExtractController.attach(object : ManualExtractPipeline.Host {
            override fun captureCrop(src: String, x: Float, y: Float, w: Float, h: Float): Bitmap? =
                capture(src, x, y, w, h)
            override fun translateAndPlace(bubble: WmBubble, text: String, script: OcrScript, onDone: (String) -> Unit) =
                translateAndPlace(bubble, text, script, onDone)
            override fun translateAndPlaceAll(
                bubbles: List<WmBubble>, prompt: String, script: OcrScript, onDone: (List<String?>) -> Unit
            ) = translateAndPlaceAll(bubbles, prompt, script, onDone)
            override fun detectPage(src: String): List<BubbleDetectorCore.Det>? = detectPage(src)
            override fun pageSize(src: String): Pair<Int, Int>? = pageSize(src)
        })
    }

    /** Convenience: entry used by the Activity's tap hook. */
    fun start(context: android.content.Context, src: String, x: Float, y: Float, w: Float, h: Float, script: OcrScript) =
        ManualExtractController.startExtract(context, src, x, y, w, h, script)

    /**
     * Run on pixels the Activity already captured (the 6-dot frame). No image lookup and no second screenshot:
     * the caller owns [bitmap] and the pipeline recycles it.
     */
    fun startOnBitmap(context: android.content.Context, src: String, bitmap: Bitmap, script: OcrScript) =
        ManualExtractController.startExtractWithBitmap(context, src, bitmap, script)

    fun cancel() = ManualExtractController.cancel()
}
