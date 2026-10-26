package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * manualLite build: no ONNX bubble/text detector. This file replaces the real [BubbleDetector] (src/main)
 * which imports ONNX Runtime. The lite APK ships no libonnxruntime*.so.
 *
 * [detect] always returns null, which is the detector-off path the rest of the code already handles: grouping
 * falls back to the built-in pixel bubble finder ([BubbleFit] / [BubbleGrouping]). Every other member answers
 * "not installed / off" so the settings UI and callers compile and behave.
 */
internal object BubbleDetector {
    const val MODEL_URL = ""
    const val MODEL_SHA256 = ""
    const val MODEL_BYTES = 0L

    @Volatile var lastError: String = "not available in the lite build"
        private set
    @Volatile var lastInfo: String = "off (lite build)"
        private set

    fun modelFile(c: Context): File = File(c.applicationContext.filesDir, "models/detection/bubble_detector.onnx")
    fun isInstalled(c: Context): Boolean = false
    fun isDownloading(): Boolean = false
    fun downloadPercent(): Int = 0

    fun mode(c: Context): String = "off"
    fun setMode(c: Context, m: String) { /* not supported in the lite build */ }
    fun enabled(c: Context): Boolean = false
    fun status(c: Context): String = "Not available in the lite (low-RAM) build"
    fun delete(c: Context) { /* nothing to delete */ }
    fun release() { /* nothing to release */ }

    fun download(c: Context, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        onDone("bubble detector is not included in the lite build")
    }

    /** Always null = detector off; callers use the built-in pixel bubble finder. */
    fun detect(c: Context, bmp: Bitmap): List<BubbleDetectorCore.Det>? = null
}
