package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable

/**
 * The Toon Sensei mascot: a flat, static bookworm on an open book (round glasses, small smile),
 * traced from the shapes in the approved boards on a 120x120 grid.
 *
 * Never animated, never used inside a progress indicator. It appears only in the splash, Home and
 * empty/error states.
 *
 * The body colours are the board's fixed illustration palette (an illustration, not UI chrome), so
 * they stay identical in both themes; only the book page uses a light fill that reads on either
 * background, and the outlines use ink so the drawing still reads on Charcoal.
 */
class MascotDrawable(private val dark: Boolean = false) : Drawable() {

    private val body = 0xFF9DB8A0.toInt()
    private val page = if (dark) 0xFFE8E4DA.toInt() else 0xFFFBFAF6.toInt()
    private val ink = if (dark) 0xFF1F1D1A.toInt() else 0xFF1F1D1A.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val size = minOf(b.width(), b.height()).toFloat()
        val s = size / 120f
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(s, s)

        fun line(w: Float) { stroke.strokeWidth = w; stroke.color = ink }
        fun ball(cx: Float, cy: Float, r: Float, w: Float = 2.5f) {
            fill.color = body
            canvas.drawCircle(cx, cy, r, fill)
            line(w)
            canvas.drawCircle(cx, cy, r, stroke)
        }

        // open book (two pages)
        path.reset()
        path.moveTo(16f, 92f)
        path.cubicTo(31f, 85f, 45f, 85f, 60f, 92f)
        path.cubicTo(75f, 85f, 89f, 85f, 104f, 92f)
        path.lineTo(104f, 101f)
        path.cubicTo(89f, 94f, 75f, 94f, 60f, 101f)
        path.cubicTo(45f, 94f, 31f, 94f, 16f, 101f)
        path.close()
        fill.color = page
        canvas.drawPath(path, fill)
        line(2.5f)
        canvas.drawPath(path, stroke)

        // worm body: overlapping circles from tail to head
        ball(48f, 86f, 12f)
        ball(61f, 79f, 13f)
        ball(68f, 64f, 14f)
        ball(63f, 43f, 20f)

        // glasses
        fill.color = Color.WHITE
        canvas.drawCircle(56f, 42f, 6.5f, fill); line(2f); canvas.drawCircle(56f, 42f, 6.5f, stroke)
        fill.color = Color.WHITE
        canvas.drawCircle(71f, 42f, 6.5f, fill); canvas.drawCircle(71f, 42f, 6.5f, stroke)
        line(2f)
        canvas.drawLine(62.5f, 42f, 63.5f, 42f, stroke)

        // pupils
        fill.color = ink
        canvas.drawCircle(57f, 43f, 2f, fill)
        canvas.drawCircle(72f, 43f, 2f, fill)

        // smile
        path.reset()
        path.moveTo(57f, 52f)
        path.quadTo(64f, 57f, 71f, 52f)
        line(2f)
        canvas.drawPath(path, stroke)

        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { fill.colorFilter = cf; stroke.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** A ready-to-use ImageView for [sizeDp]. */
        fun view(c: Context, sizeDp: Int, dark: Boolean): android.widget.ImageView =
            android.widget.ImageView(c).apply {
                setImageDrawable(MascotDrawable(dark))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = "Toon Sensei"
                val px = (sizeDp * c.resources.displayMetrics.density + 0.5f).toInt()
                layoutParams = android.widget.FrameLayout.LayoutParams(px, px)
            }
    }
}
