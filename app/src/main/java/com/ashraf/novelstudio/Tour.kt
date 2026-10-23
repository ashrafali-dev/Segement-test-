package com.ashraf.novelstudio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * First-run guided tour: the screen is dimmed, one control at a time is lit up with a pulsing ring,
 * and a small card explains it in plain English. Next / Skip tour. It blocks touches while it is open.
 */
class TourOverlay(private val ctx: Context) : FrameLayout(ctx) {
    class Step(val title: String, val body: String, val target: () -> RectF?)

    private val dens = ctx.resources.displayMetrics.density
    private val bold: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

    private var steps: List<Step> = emptyList()
    private var index = 0
    private var onDone: () -> Unit = {}

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC050711.toInt() }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dens
    }
    private val hole = RectF()
    private val tmp = RectF()
    private var holeAnim: ValueAnimator? = null
    private var pulse = 0f
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    private val titleTv = TextView(ctx)
    private val bodyTv = TextView(ctx)
    private val countTv = TextView(ctx)
    private val nextBtn = TextView(ctx)
    private val skipBtn = TextView(ctx)
    private val card = LinearLayout(ctx)

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        elevation = 24f * dens

        titleTv.apply {
            textSize = 18f
            typeface = bold
            setTextColor(UiTheme.text)
        }
        bodyTv.apply {
            textSize = 14.5f
            setTextColor(UiTheme.dim)
            setLineSpacing(0f, 1.15f)
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(14))
        }
        countTv.apply {
            textSize = 12f
            setTextColor(UiTheme.faint)
        }
        skipBtn.apply {
            text = "Skip tour"
            textSize = 14f
            setTextColor(UiTheme.dim)
            setPadding(ctx.dpi(10), ctx.dpi(8), ctx.dpi(14), ctx.dpi(8))
            isClickable = true
            setOnClickListener { finish() }
        }
        nextBtn.apply {
            textSize = 14f
            typeface = bold
            setTextColor(Color.WHITE)
            setPadding(ctx.dpi(20), ctx.dpi(9), ctx.dpi(20), ctx.dpi(9))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(UiTheme.accentA, UiTheme.accentB)).apply {
                cornerRadius = ctx.dpf(20)
            }
            isClickable = true
            setOnClickListener { next() }
        }
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(countTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(skipBtn)
            addView(nextBtn)
        }
        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(18), ctx.dpi(16), ctx.dpi(14))
            background = Glass.solid(ctx, UiTheme.sheet, 22, UiTheme.stroke)
            elevation = 12f * dens
            addView(titleTv)
            addView(bodyTv)
            addView(bottom)
        }
        addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** [local] is a rectangle in [v]'s own coordinates (optional); the result is in this overlay's coordinates. */
    fun rectOf(v: View, local: RectF? = null): RectF {
        val a = IntArray(2)
        v.getLocationOnScreen(a)
        val b = IntArray(2)
        getLocationOnScreen(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        return if (local == null) RectF(l, t, l + v.width, t + v.height)
        else RectF(l + local.left, t + local.top, l + local.right, t + local.bottom)
    }

    fun isRunning(): Boolean = visibility == VISIBLE

    fun start(list: List<Step>, done: () -> Unit) {
        if (list.isEmpty()) return
        steps = list
        onDone = done
        index = 0
        hole.setEmpty()
        visibility = VISIBLE
        if (!pulseAnim.isStarted) pulseAnim.start()
        // wait one frame so width/height and the targets' positions are known
        post { show(0, animate = false) }
    }

    fun finish() {
        if (visibility != VISIBLE) return
        holeAnim?.cancel()
        pulseAnim.cancel()
        visibility = GONE
        onDone()
    }

    private fun next() {
        if (index >= steps.lastIndex) finish() else show(index + 1, animate = true)
    }

    private fun show(i: Int, animate: Boolean) {
        index = i
        val step = steps[i]
        titleTv.text = step.title
        bodyTv.text = step.body
        countTv.text = "${i + 1} / ${steps.size}"
        nextBtn.text = if (i == steps.lastIndex) "Got it" else "Next"

        val target = try { step.target() } catch (_: Exception) { null }
        val pad = 6f * dens
        val to = if (target == null) RectF() else RectF(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)

        holeAnim?.cancel()
        if (!animate || hole.isEmpty || to.isEmpty) {
            hole.set(to)
            invalidate()
        } else {
            val from = RectF(hole)
            holeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                addUpdateListener {
                    val f = it.animatedValue as Float
                    hole.set(
                        from.left + (to.left - from.left) * f,
                        from.top + (to.top - from.top) * f,
                        from.right + (to.right - from.right) * f,
                        from.bottom + (to.bottom - from.bottom) * f
                    )
                    invalidate()
                }
                start()
            }
        }

        // card above the spotlight when the control is in the lower half (the dock), below it otherwise
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val margin = ctx.dpi(16)
        lp.setMargins(margin, margin, margin, margin)
        val below = !to.isEmpty && to.centerY() < height / 2f
        lp.gravity = if (below) Gravity.TOP else Gravity.BOTTOM
        if (to.isEmpty) {
            lp.gravity = Gravity.CENTER
        } else if (below) {
            lp.topMargin = (to.bottom + ctx.dpf(18)).toInt()
        } else {
            lp.bottomMargin = (height - to.top + ctx.dpf(18)).toInt()
        }
        card.layoutParams = lp
        card.alpha = 0f
        card.translationY = if (below) -ctx.dpf(10) else ctx.dpf(10)
        card.animate().alpha(1f).translationY(0f).setDuration(260L).start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val layer = c.saveLayer(0f, 0f, w, h, null)
        c.drawRect(0f, 0f, w, h, dim)
        if (!hole.isEmpty) {
            val r = min(hole.width(), hole.height()) / 2f
            c.drawRoundRect(hole, r, r, clear)
        }
        c.restoreToCount(layer)
        if (!hole.isEmpty) {
            val r = min(hole.width(), hole.height()) / 2f
            val grow = 2f * dens * pulse
            tmp.set(hole.left - grow, hole.top - grow, hole.right + grow, hole.bottom + grow)
            ring.color = ModeUi.withAlpha(Color.WHITE, (110 + 130 * (1f - pulse)).toInt())
            c.drawRoundRect(tmp, r + grow, r + grow, ring)
        }
    }

    override fun onDetachedFromWindow() {
        holeAnim?.cancel()
        pulseAnim.cancel()
        super.onDetachedFromWindow()
    }
}
