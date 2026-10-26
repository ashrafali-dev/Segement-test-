package com.ashraf.novelstudio.manualextract

import android.graphics.Bitmap
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
        translateAndPlace: (bubbles: List<WmBubble>, text: String, script: OcrScript, onDone: (String) -> Unit) -> Unit,
        /** Called on the main thread with a short user-facing reason whenever a run does not place anything. */
        onFailure: (String) -> Unit = {}
    ) {
        if (!ManualExtractConfig.ENABLED) return
        ManualExtractController.onFailure = onFailure
        ManualExtractController.attach(object : ManualExtractPipeline.Host {
            override fun captureCrop(src: String, x: Float, y: Float, w: Float, h: Float): Bitmap? =
                capture(src, x, y, w, h)
            override fun translateAndPlace(bubbles: List<WmBubble>, text: String, script: OcrScript, onDone: (String) -> Unit) =
                translateAndPlace(bubbles, text, script, onDone)
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
