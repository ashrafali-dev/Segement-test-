package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap

/**
 * manualLite build: no AI painter. This file replaces the real [WebModeInpainter] (src/main) which imports
 * ONNX Runtime. The lite APK ships no libonnxruntime*.so, so the 37 MB LaMa model could never load anyway.
 *
 * Every member the rest of the code calls is present with the safe "off" answer, so call sites compile and
 * behave exactly as on a phone where the AI eraser is not installed. Placement falls back to the built-in
 * mask / opaque cover boxes (unchanged code in WebModeWriter).
 */
internal object WebModeInpainter {
    @Volatile var lastNote: String = "off (manualLite build)"

    val tooSlow: Boolean get() = false

    fun isReady(ctx: Context): Boolean = false
    fun isDownloading(): Boolean = false
    fun isLoaded(): Boolean = false

    fun warmUpAsync(ctx: Context) { /* nothing to warm */ }

    fun delete(ctx: Context): Boolean = true

    fun download(ctx: Context, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit) {
        onDone("AI eraser is not included in the lite build")
    }

    /** Never inpaints in the lite build. */
    fun fill(ctx: Context, bitmap: Bitmap, x0: Int, y0: Int, rw: Int, rh: Int, mask: ByteArray): IntArray? = null
}
