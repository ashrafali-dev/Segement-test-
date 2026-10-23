package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Scanner" frame for re-reading one speech bubble: a rectangle with six dots (four corners plus the
 * middle of the top and bottom edge). Drag a dot to resize, drag inside to move. Only the framed area is read again.
 * [onScan] receives the frame as fractions (left, top, right, bottom) of THIS view.
 */
class RescanSelector(
    ctx: Context,
    private val accent: Int,
    private val onScan: (Float, Float, Float, Float) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var l = 0f
    private var t = 0f
    private var r = 0f
    private var b = 0f
    private var ready = false
    private val bar: LinearLayout
    private val baseBarMargin = (28 * d).toInt()
    private var lift = 0
    private var drag = -1                 // 0..5 = dot, 6 = whole frame
    private var lx = 0f
    private var ly = 0f

    private val dimPaint = Paint().apply { color = 0x70000000 }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 3f * d }

    init {
        setWillNotDraw(false)
        isClickable = true
        bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { setColor(0xEE232739.toInt()); cornerRadius = 26 * d }
            elevation = 12 * d
        }
        fun btn(label: String, filled: Boolean, click: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(if (filled) accent else 0x33FFFFFF)
            }
            setOnClickListener { click() }
        }
        bar.addView(btn("Cancel", false) { onCancel() }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (40 * d).toInt()))
        bar.addView(btn("Read this area", true) {
            if (width > 0 && height > 0) onScan(l / width, t / height, r / width, b / height)
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (40 * d).toInt()).also { it.leftMargin = (10 * d).toInt() })
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            it.bottomMargin = (28 * d).toInt()
        })
    }

    /**
     * Raises the Cancel / Read-this-area bar by [px] so it never hides behind the bottom dock
     * (in the webtoon view the page is drawn full-height UNDER the dock).
     */
    fun setBarLift(px: Int) {
        val v = px.coerceAtLeast(0)
        if (v == lift) return
        lift = v
        val lp = bar.layoutParams as? LayoutParams ?: return
        lp.bottomMargin = baseBarMargin + lift
        bar.layoutParams = lp
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!ready && w > 0 && h > 0) {
            l = w * 0.10f; r = w * 0.90f
            t = h * 0.40f; b = h * 0.54f
            ready = true
        }
    }

    private fun dots(): Array<FloatArray> = arrayOf(
        floatArrayOf(l, t), floatArrayOf(r, t), floatArrayOf(l, b), floatArrayOf(r, b),
        floatArrayOf((l + r) / 2f, t), floatArrayOf((l + r) / 2f, b)
    )

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (!ready) return
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, t, dimPaint)
        c.drawRect(0f, b, w, h, dimPaint)
        c.drawRect(0f, t, l, b, dimPaint)
        c.drawRect(r, t, w, b, dimPaint)
        c.drawRect(l, t, r, b, framePaint)
        for (p in dots()) {
            c.drawCircle(p[0], p[1], 9 * d, dotFill)
            c.drawCircle(p[0], p[1], 9 * d, dotRing)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ready) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = -1
                var best = 34 * d
                dots().forEachIndexed { i, p ->
                    val dist = Math.hypot((e.x - p[0]).toDouble(), (e.y - p[1]).toDouble()).toFloat()
                    if (dist < best) { best = dist; drag = i }
                }
                if (drag < 0 && e.x in l..r && e.y in t..b) drag = 6
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag >= 0) {
                    val dx = e.x - lx
                    val dy = e.y - ly
                    val minW = 56 * d
                    val minH = 36 * d
                    when (drag) {
                        0 -> { l += dx; t += dy }
                        1 -> { r += dx; t += dy }
                        2 -> { l += dx; b += dy }
                        3 -> { r += dx; b += dy }
                        4 -> t += dy
                        5 -> b += dy
                        6 -> { l += dx; r += dx; t += dy; b += dy }
                    }
                    if (drag != 6) {
                        if (r - l < minW) { if (drag == 0 || drag == 2) l = r - minW else r = l + minW }
                        if (b - t < minH) { if (drag == 0 || drag == 1 || drag == 4) t = b - minH else b = t + minH }
                    }
                    // keep inside the view
                    val fw = r - l
                    val fh = b - t
                    if (l < 0f) { l = 0f; if (drag == 6) r = fw }
                    if (t < 0f) { t = 0f; if (drag == 6) b = fh }
                    if (r > width) { r = width.toFloat(); if (drag == 6) l = r - fw }
                    if (b > height) { b = height.toFloat(); if (drag == 6) t = b - fh }
                    lx = e.x; ly = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
        return true
    }
}
