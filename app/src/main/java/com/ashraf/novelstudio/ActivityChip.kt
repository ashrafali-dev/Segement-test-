package com.ashraf.novelstudio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small floating status pill (every reading mode): three bouncing dots plus a short plain-English
 * label such as "Reading the page" or "Sending to chatbot". It never takes touches.
 * The label cross-fades when the stage changes and the whole pill fades in/out.
 */
class ActivityChip(context: Context) : LinearLayout(context) {
    private val dots = DotsView(context)
    private val label = TextView(context)
    private var shown = false
    private var currentText = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        setPadding((12 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * d
            setColor(0xE6121420.toInt())
            setStroke((1 * d).toInt().coerceAtLeast(1), 0x33FFFFFF)
        }
        elevation = 12 * d
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(dots, LayoutParams((26 * d).toInt(), (14 * d).toInt()).also { it.rightMargin = (8 * d).toInt() })
        label.apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Long engine/error labels wrap onto up to 4 lines instead of being cut off at the screen edge.
            maxLines = 4
            maxWidth = (context.resources.displayMetrics.widthPixels - 110 * d).toInt()
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        alpha = 0f
        visibility = GONE
    }

    /** text == null hides the chip. [color] tints the dots for the current stage. */
    fun update(text: String?, color: Int) {
        if (text.isNullOrBlank()) {
            if (!shown) return
            shown = false
            dots.stop()
            animate().cancel()
            animate().alpha(0f).translationY(6f * resources.displayMetrics.density).setDuration(180)
                .withEndAction { if (!shown) visibility = GONE }.start()
            return
        }
        dots.color = color
        if (!shown) {
            shown = true
            currentText = text
            label.text = text
            visibility = VISIBLE
            translationY = 6f * resources.displayMetrics.density
            animate().cancel()
            animate().alpha(1f).translationY(0f).setDuration(200).start()
            dots.start()
        } else if (text != currentText) {
            currentText = text
            label.animate().cancel()
            label.animate().alpha(0f).setDuration(90).withEndAction {
                label.text = text
                label.animate().alpha(1f).setDuration(140).start()
            }.start()
        }
    }

    private class DotsView(context: Context) : View(context) {
        var color: Int = 0xFFB388FF.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private var anim: ValueAnimator? = null

        fun start() {
            if (anim?.isRunning == true) return
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() { anim?.cancel(); anim = null }

        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = h * 0.2f
            paint.color = color
            for (i in 0..2) {
                val ph = ((t - i * 0.16f) % 1f + 1f) % 1f
                // one soft bounce per cycle: 0..0.5 up and down, then rest
                val up = if (ph < 0.5f) Math.sin(ph / 0.5f * Math.PI).toFloat() else 0f
                paint.alpha = (110 + 145 * up).toInt()
                val cx = w * (0.18f + 0.32f * i)
                val cy = h * 0.62f - up * h * 0.32f
                c.drawCircle(cx, cy, r * (1f + 0.25f * up), paint)
            }
        }
    }
}
