package com.ashraf.novelstudio

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Icons added for the Toon Sensei UI, drawn on the same 24x24 grid and stroke style as Ui.kt's
 * IconDrawable. Kept in its own file so the legacy icon file is not rewritten.
 *
 * Returns true when the icon was drawn; false means "unknown name" (the caller draws a fallback).
 */
object ToonIcons {

    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, ic: Ic, stroke: Paint, fill: Paint): Boolean {
        when (ic) {
            Ic.CHEVRON_DOWN -> poly(c, stroke, 7f, 10f, 12f, 15f, 17f, 10f)

            Ic.PAUSE -> {
                rect.set(7f, 5.5f, 10f, 18.5f); c.drawRoundRect(rect, 1.2f, 1.2f, fill)
                rect.set(14f, 5.5f, 17f, 18.5f); c.drawRoundRect(rect, 1.2f, 1.2f, fill)
            }

            Ic.PLAY -> {
                path.reset()
                path.moveTo(8f, 5.5f); path.lineTo(19f, 12f); path.lineTo(8f, 18.5f); path.close()
                c.drawPath(path, fill)
            }

            Ic.STOP_SQUARE -> {
                rect.set(7f, 7f, 17f, 17f); c.drawRoundRect(rect, 2f, 2f, stroke)
            }

            // Three spines seen from the side (Library).
            Ic.LIBRARY -> {
                rect.set(4f, 5f, 8f, 19f); c.drawRoundRect(rect, 1f, 1f, stroke)
                rect.set(10f, 5f, 14f, 19f); c.drawRoundRect(rect, 1f, 1f, stroke)
                path.reset()
                path.moveTo(16f, 6f); path.lineTo(20f, 5.2f); path.lineTo(22f, 18f); path.lineTo(18f, 18.8f); path.close()
                c.drawPath(path, stroke)
            }

            Ic.LIST -> {
                line(c, stroke, 4f, 7f, 20f, 7f)
                line(c, stroke, 4f, 12f, 20f, 12f)
                line(c, stroke, 4f, 17f, 14f, 17f)
            }

            Ic.EYE -> {
                path.reset()
                path.moveTo(2f, 12f)
                path.cubicTo(5.5f, 6.2f, 18.5f, 6.2f, 22f, 12f)
                path.cubicTo(18.5f, 17.8f, 5.5f, 17.8f, 2f, 12f)
                path.close()
                c.drawPath(path, stroke)
                c.drawCircle(12f, 12f, 2.8f, stroke)
            }

            Ic.TRASH -> {
                line(c, stroke, 5f, 7f, 19f, 7f)
                poly(c, stroke, 10f, 7f, 10f, 5f, 14f, 5f, 14f, 7f)
                poly(c, stroke, 7f, 7f, 8f, 19f, 16f, 19f, 17f, 7f)
            }

            // Filled five-point star (default chatbot marker).
            Ic.STAR -> {
                path.reset()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 7.6f else 3.4f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = 12f + r * Math.cos(a).toFloat()
                    val y = 12.6f + r * Math.sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, fill)
            }

            Ic.ZOOM -> {
                c.drawCircle(11f, 11f, 6f, stroke)
                line(c, stroke, 15.5f, 15.5f, 20f, 20f)
                line(c, stroke, 8f, 11f, 14f, 11f)
                line(c, stroke, 11f, 8f, 11f, 14f)
            }

            Ic.LINK -> {
                path.reset()
                path.moveTo(10f, 14f)
                path.cubicTo(11.6f, 15.6f, 14.4f, 15.6f, 15.7f, 14.3f)
                path.lineTo(18.7f, 11.3f)
                path.cubicTo(20f, 10f, 20f, 7.5f, 18.6f, 6.2f)
                path.cubicTo(17.3f, 4.9f, 14.9f, 4.9f, 13.6f, 6.2f)
                path.lineTo(12.6f, 7.2f)
                c.drawPath(path, stroke)
                path.reset()
                path.moveTo(14f, 10f)
                path.cubicTo(12.4f, 8.4f, 9.6f, 8.4f, 8.3f, 9.7f)
                path.lineTo(5.3f, 12.7f)
                path.cubicTo(4f, 14f, 4f, 16.5f, 5.4f, 17.8f)
                path.cubicTo(6.7f, 19.1f, 9.1f, 19.1f, 10.4f, 17.8f)
                path.lineTo(11.4f, 16.8f)
                c.drawPath(path, stroke)
            }

            else -> return false
        }
        return true
    }

    private fun poly(c: Canvas, p: Paint, vararg pts: Float) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        c.drawPath(path, p)
    }

    private fun line(c: Canvas, p: Paint, x1: Float, y1: Float, x2: Float, y2: Float) =
        c.drawLine(x1, y1, x2, y2, p)
}
