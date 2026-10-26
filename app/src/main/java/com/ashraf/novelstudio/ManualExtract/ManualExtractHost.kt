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
        translateAndPlace: (bubble: WmBubble, text: String, script: OcrScript, onDone: (String) -> Unit) -> Unit
    ) {
        if (!ManualExtractConfig.ENABLED) return
        ManualExtractController.attach(object : ManualExtractPipeline.Host {
            override fun captureCrop(src: String, x: Float, y: Float, w: Float, h: Float): Bitmap? =
                capture(src, x, y, w, h)
            override fun translateAndPlace(bubble: WmBubble, text: String, script: OcrScript, onDone: (String) -> Unit) =
                translateAndPlace(bubble, text, script, onDone)
        })
    }

    /** Convenience: entry used by the Activity's tap hook. */
    fun start(context: android.content.Context, src: String, x: Float, y: Float, w: Float, h: Float, script: OcrScript) =
        ManualExtractController.startExtract(context, src, x, y, w, h, script)

    fun cancel() = ManualExtractController.cancel()
}
