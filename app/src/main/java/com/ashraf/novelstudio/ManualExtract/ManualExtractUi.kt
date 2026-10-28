package com.ashraf.novelstudio.manualextract

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Presentation helpers for manual extract.
 *
 * Drawing/placement itself is NOT reimplemented here: the translated text is placed by the existing overlay
 * writer (WebModeWriter.bubbleScript) through the Activity's [ManualExtractPipeline.Host], i.e. the built-in
 * mask / cover-box path. This file only draws the transient "working" highlight so the user sees which bubble
 * was picked, and documents that the placement uses the built-in mask.
 */
internal object ManualExtractUi {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(200, 0x4D, 0xC3, 0xFF)   // accent blue
    }

    /** Draws a thin highlight around the tapped region (in the capture bitmap's pixel space). */
    fun drawHighlight(canvas: Canvas, rect: RectF) {
        canvas.drawRoundRect(rect, 10f, 10f, stroke)
    }
}
