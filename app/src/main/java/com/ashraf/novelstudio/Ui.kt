package com.ashraf.novelstudio

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Novel Studio design system ("Aurora Glass").
 * Everything visual that is shared between screens lives here: colours, vector icons drawn with
 * Canvas (no emoji in the chrome), glass surfaces, the glow progress bar, the extract button
 * with its progress ring, and the bottom-sheet / full-screen dialog helpers.
 */
object UiTheme {
    val bg = 0xFF07080D.toInt()
    val sheet = 0xFF10121B.toInt()
    val text = 0xFFF4F6FB.toInt()
    val dim = 0xFF9AA3B8.toInt()
    val faint = 0xFF6B7488.toInt()
    val iconIdle = 0xFFC9D0E4.toInt()
    val accentA = 0xFF7B8CFF.toInt()
    val accentB = 0xFFB27CFF.toInt()
    val accent = 0xFF8C9CFF.toInt()
    val ok = 0xFF4ADE80.toInt()
    val danger = 0xFFFF6B7A.toInt()
    const val stroke = 0x2EFFFFFF
    const val strokeSoft = 0x1AFFFFFF
}

internal fun Context.dpi(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
internal fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

// ============================================================================ icons

enum class Ic {
    BACK, FORWARD, RELOAD, HOME, GRID, DOTS, PLUS, CLOSE, SHARE, SEARCH, BOLT, BOOKMARK, BOOK,
    CHAT, SPLIT, SPARK, SAVE, STOP, GLOBE, CHECK, LOCK, CLOCK, SLIDERS, MOON, SHIELD, TEXT, CHEVRON
}

/** 24x24 stroke icons, drawn at any size. */
class IconDrawable(private val ic: Ic, private var color: Int, private val strokeUnits: Float = 1.9f) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    private fun poly(c: Canvas, vararg p: Float, close: Boolean = false) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, stroke)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, stroke)

    private fun arrowHead(c: Canvas, tx: Float, ty: Float, dx: Float, dy: Float, len: Float) {
        val a = 0.75
        for (sign in intArrayOf(-1, 1)) {
            val cs = Math.cos(a * sign).toFloat()
            val sn = Math.sin(a * sign).toFloat()
            val rx = dx * cs - dy * sn
            val ry = dx * sn + dy * cs
            c.drawLine(tx, ty, tx - rx * len, ty - ry * len, stroke)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val size = minOf(b.width(), b.height()).toFloat()
        val s = size / 24f
        stroke.color = color
        stroke.strokeWidth = strokeUnits
        fill.color = color
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(s, s)
        when (ic) {
            Ic.BACK -> poly(canvas, 15f, 5f, 8f, 12f, 15f, 19f)
            Ic.FORWARD -> poly(canvas, 9f, 5f, 16f, 12f, 9f, 19f)
            Ic.CHEVRON -> poly(canvas, 9f, 6f, 15f, 12f, 9f, 18f)
            Ic.RELOAD -> {
                rect.set(5f, 5f, 19f, 19f)
                canvas.drawArc(rect, -40f, 290f, false, stroke)
                // clockwise tangent at the arc end (250 degrees)
                val th = Math.toRadians(250.0)
                val tx = 12f + 7f * Math.cos(th).toFloat()
                val ty = 12f + 7f * Math.sin(th).toFloat()
                arrowHead(canvas, tx, ty, -Math.sin(th).toFloat(), Math.cos(th).toFloat(), 3.8f)
            }
            Ic.HOME -> {
                poly(canvas, 4f, 11f, 12f, 4f, 20f, 11f)
                poly(canvas, 6f, 10f, 6f, 20f, 18f, 20f, 18f, 10f)
            }
            Ic.GRID -> {
                rect.set(4.5f, 4.5f, 10.5f, 10.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
                rect.set(13.5f, 4.5f, 19.5f, 10.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
                rect.set(4.5f, 13.5f, 10.5f, 19.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
                rect.set(13.5f, 13.5f, 19.5f, 19.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
            }
            Ic.DOTS -> {
                canvas.drawCircle(5.5f, 12f, 1.8f, fill)
                canvas.drawCircle(12f, 12f, 1.8f, fill)
                canvas.drawCircle(18.5f, 12f, 1.8f, fill)
            }
            Ic.PLUS -> { line(canvas, 12f, 5f, 12f, 19f); line(canvas, 5f, 12f, 19f, 12f) }
            Ic.CLOSE -> { line(canvas, 6f, 6f, 18f, 18f); line(canvas, 18f, 6f, 6f, 18f) }
            Ic.SHARE -> {
                poly(canvas, 6f, 12f, 6f, 19f, 18f, 19f, 18f, 12f)
                line(canvas, 12f, 4f, 12f, 15f)
                poly(canvas, 8.5f, 7.5f, 12f, 4f, 15.5f, 7.5f)
            }
            Ic.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 5.5f, stroke)
                line(canvas, 15f, 15f, 20f, 20f)
            }
            Ic.BOLT -> {
                path.reset()
                path.moveTo(13f, 2.5f); path.lineTo(5f, 13.5f); path.lineTo(11f, 13.5f)
                path.lineTo(10f, 21.5f); path.lineTo(19f, 10f); path.lineTo(13f, 10f); path.close()
                canvas.drawPath(path, fill)
                stroke.strokeWidth = 1.4f
                canvas.drawPath(path, stroke)
            }
            Ic.BOOKMARK -> poly(canvas, 7f, 4f, 17f, 4f, 17f, 20f, 12f, 16f, 7f, 20f, close = true)
            Ic.BOOK -> {
                path.reset()
                path.moveTo(12f, 6f)
                path.cubicTo(9f, 4f, 6f, 4f, 3.5f, 5f)
                path.lineTo(3.5f, 19f)
                path.cubicTo(6f, 18f, 9f, 18f, 12f, 20f)
                path.cubicTo(15f, 18f, 18f, 18f, 20.5f, 19f)
                path.lineTo(20.5f, 5f)
                path.cubicTo(18f, 4f, 15f, 4f, 12f, 6f)
                path.close()
                canvas.drawPath(path, stroke)
                line(canvas, 12f, 6f, 12f, 20f)
            }
            Ic.CHAT -> {
                rect.set(4f, 5f, 20f, 16f); canvas.drawRoundRect(rect, 4f, 4f, stroke)
                poly(canvas, 9f, 16f, 9f, 20f, 13.5f, 16f)
            }
            Ic.SPLIT -> {
                rect.set(4f, 5f, 20f, 19f); canvas.drawRoundRect(rect, 4f, 4f, stroke)
                line(canvas, 4f, 12f, 20f, 12f)
            }
            Ic.SPARK -> {
                path.reset()
                path.moveTo(12f, 3f); path.lineTo(14f, 10f); path.lineTo(21f, 12f); path.lineTo(14f, 14f)
                path.lineTo(12f, 21f); path.lineTo(10f, 14f); path.lineTo(3f, 12f); path.lineTo(10f, 10f); path.close()
                canvas.drawPath(path, fill)
                stroke.strokeWidth = 1.2f
                canvas.drawPath(path, stroke)
            }
            Ic.SAVE -> {
                line(canvas, 12f, 4f, 12f, 15f)
                poly(canvas, 8f, 11f, 12f, 15f, 16f, 11f)
                poly(canvas, 5f, 16f, 5f, 20f, 19f, 20f, 19f, 16f)
            }
            Ic.STOP -> {
                rect.set(7f, 7f, 17f, 17f)
                canvas.drawRoundRect(rect, 2.5f, 2.5f, fill)
            }
            Ic.GLOBE -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                line(canvas, 4f, 12f, 20f, 12f)
                rect.set(8f, 4f, 16f, 20f); canvas.drawOval(rect, stroke)
            }
            Ic.CHECK -> poly(canvas, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Ic.LOCK -> {
                rect.set(6f, 11f, 18f, 20f); canvas.drawRoundRect(rect, 2.5f, 2.5f, stroke)
                rect.set(8.5f, 4f, 15.5f, 14f); canvas.drawArc(rect, 180f, 180f, false, stroke)
            }
            Ic.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                poly(canvas, 12f, 7f, 12f, 12f, 15.5f, 14f)
            }
            Ic.SLIDERS -> {
                line(canvas, 4f, 7f, 20f, 7f); line(canvas, 4f, 12f, 20f, 12f); line(canvas, 4f, 17f, 20f, 17f)
                fill.color = UiTheme.sheet
                canvas.drawCircle(9f, 7f, 2.3f, fill); canvas.drawCircle(15f, 12f, 2.3f, fill); canvas.drawCircle(8f, 17f, 2.3f, fill)
                canvas.drawCircle(9f, 7f, 2.3f, stroke); canvas.drawCircle(15f, 12f, 2.3f, stroke); canvas.drawCircle(8f, 17f, 2.3f, stroke)
            }
            Ic.MOON -> {
                val outer = Path().apply { addCircle(12f, 12f, 8.2f, Path.Direction.CW) }
                val cut = Path().apply { addCircle(16.2f, 8.6f, 7f, Path.Direction.CW) }
                outer.op(cut, Path.Op.DIFFERENCE)
                canvas.drawPath(outer, fill)
            }
            Ic.SHIELD -> {
                path.reset()
                path.moveTo(12f, 3f); path.lineTo(19f, 6f); path.lineTo(19f, 12f)
                path.cubicTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
                path.cubicTo(8f, 19.5f, 5f, 16.5f, 5f, 12f)
                path.lineTo(5f, 6f); path.close()
                canvas.drawPath(path, stroke)
            }
            Ic.TEXT -> {
                fill.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                fill.textAlign = Paint.Align.CENTER
                fill.textSize = 13f
                canvas.drawText("Aa", 12f, 16.4f, fill)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { stroke.colorFilter = cf; fill.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ============================================================================ glass surfaces

object Glass {
    /** Frosted capsule: light-on-dark gradient fill with a hairline edge. */
    fun panel(c: Context, radiusDp: Number, strong: Boolean = false): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (strong) 0x3AFFFFFF else 0x2CFFFFFF, if (strong) 0x1CFFFFFF else 0x12FFFFFF)
        ).apply {
            cornerRadius = c.dpf(radiusDp)
            setStroke(c.dpi(1), UiTheme.stroke)
        }

    fun field(c: Context, radiusDp: Number, focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = c.dpf(radiusDp)
            setColor(if (focused) 0x1FFFFFFF else 0x12FFFFFF)
            setStroke(c.dpi(if (focused) 1.5f else 1f), if (focused) UiTheme.accent else UiTheme.strokeSoft)
        }

    fun accent(c: Context, radiusDp: Number, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT): GradientDrawable =
        GradientDrawable(orientation, intArrayOf(UiTheme.accentA, UiTheme.accentB)).apply {
            cornerRadius = c.dpf(radiusDp)
        }

    fun solid(c: Context, color: Int, radiusDp: Number, strokeColor: Int = 0, strokeDp: Number = 1): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = c.dpf(radiusDp)
            setColor(color)
            if (strokeColor != 0) setStroke(c.dpi(strokeDp), strokeColor)
        }

    fun oval(color: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeColor != 0) setStroke(strokePx, strokeColor)
        }

    /** Touch ripple over [content] (optional), round or rounded-rectangle. */
    fun ripple(c: Context, content: Drawable?, radiusDp: Number? = null): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            if (radiusDp == null) shape = GradientDrawable.OVAL else cornerRadius = c.dpf(radiusDp)
        }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask)
    }
}

/** Square icon button with a round ripple. */
internal fun Context.iconButton(ic: Ic, desc: String, sizeDp: Int = 22, boxDp: Int = 44, color: Int = UiTheme.iconIdle, onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageDrawable(IconDrawable(ic, color))
        val pad = dpi((boxDp - sizeDp) / 2f)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = Glass.ripple(this@iconButton, null)
        contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

// ============================================================================ glow progress bar

class GlowBar(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0f
    private var target = 0
    private var phase = 0f
    private var move: ValueAnimator? = null
    private val shimmer = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    /** 0..100, animated. */
    var progress: Int
        get() = target
        set(v) {
            val t = v.coerceIn(0, 100)
            if (t == target) return
            target = t
            move?.cancel()
            move = ValueAnimator.ofFloat(shown, t.toFloat()).apply {
                duration = 420
                interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) { if (!shimmer.isStarted) shimmer.start() } else shimmer.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && !shimmer.isStarted) shimmer.start()
    }

    override fun onDetachedFromWindow() {
        shimmer.cancel()
        move?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        c.drawRoundRect(0f, 0f, w, h, r, r, track)
        val fw = maxOf(w * shown / 100f, if (shown > 0f) h else 0f)
        if (fw <= 0f) return
        fillP.shader = LinearGradient(0f, 0f, maxOf(fw, 1f), 0f, UiTheme.accentA, UiTheme.accentB, Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, fw, h, r, r, fillP)
        val bw = w * 0.30f
        val x = -bw + (fw + bw) * phase
        shine.shader = LinearGradient(x, 0f, x + bw, 0f, intArrayOf(0x00FFFFFF, 0x70FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        val save = c.save()
        c.clipRect(0f, 0f, fw, h)
        c.drawRoundRect(x, 0f, x + bw, h, r, r, shine)
        c.restoreToCount(save)
    }
}

// ============================================================================ extract button

/** The big centre button of the dock: a raised bubble, gradient disc + bolt, ringed by 24 ticks that show progress / activity. */
class ExtractButton(ctx: Context) : View(ctx) {
    /** -1 = no ring, 0 = busy (spinning), 1..100 = determinate */
    var ringProgress: Int = -1
        set(v) { field = v; invalidate() }
    var autoOn: Boolean = true
        set(v) { field = v; invalidate() }

    private val dens = ctx.resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * dens; color = 0x2EFFFFFF }
    private val ringFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * dens; strokeCap = Paint.Cap.ROUND; color = Color.WHITE
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = IconDrawable(Ic.BOLT, Color.WHITE)
    private val oval = RectF()
    private var rot = 0f

    /** 0 = Novel, 1 = Scan, 2 = Live. Tints the disc and the idle ticks (see [ModeUi]). */
    var modeIndex: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = UiTheme.stroke }

    // The little badge is also the live activity light. MainActivity feeds it real task state.
    // A solid green/grey dot means idle; active OCR/translation stages pulse in their own colours.
    private var activityIndicatorColor: Int? = null
    private var activityIndicatorBlinking = false
    private var activityIndicatorAlpha = 1f
    private var activityIndicatorLabel = ""
    private val activityIndicatorPulse = ValueAnimator.ofFloat(1f, 0.18f).apply {
        duration = 520L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            activityIndicatorAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { rot = it.animatedValue as Float; invalidate() }
    }

    /** Set the live status of the small badge without changing the bolt or progress ring. */
    fun setActivityIndicator(color: Int, blinking: Boolean, label: String) {
        val changed = activityIndicatorColor != color ||
            activityIndicatorBlinking != blinking ||
            activityIndicatorLabel != label
        activityIndicatorColor = color
        activityIndicatorBlinking = blinking
        activityIndicatorLabel = label
        if (changed) {
            contentDescription = "Extract chapter. $label"
            invalidate()
        }
        if (blinking) {
            if (!activityIndicatorPulse.isStarted) activityIndicatorPulse.start()
        } else {
            if (activityIndicatorPulse.isStarted) activityIndicatorPulse.cancel()
            if (activityIndicatorAlpha != 1f) {
                activityIndicatorAlpha = 1f
                invalidate()
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Extract chapter"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (74f * dens).toInt()
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.92f else 1f).scaleY(if (pressed) 0.92f else 1f).setDuration(110).start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        activityIndicatorPulse.cancel()
        activityIndicatorAlpha = 1f
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (activityIndicatorBlinking && !activityIndicatorPulse.isStarted) activityIndicatorPulse.start()
    }

    override fun onDraw(c: Canvas) {
        // The ring "comet" runs while a job is busy (ringProgress == 0) or the activity light is blinking.
        val comet = ringProgress == 0 || (ringProgress < 0 && activityIndicatorBlinking)
        if (comet && !spin.isStarted) spin.start()
        if (!comet && spin.isStarted) spin.cancel()

        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val mStart = ModeUi.start(modeIndex)
        val mEnd = ModeUi.end(modeIndex)

        glow.shader = RadialGradient(cx, cy, r, intArrayOf(ModeUi.withAlpha(mStart, 0x44), ModeUi.withAlpha(mStart, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, glow)

        // the bubble the button sits in
        val bubR = r - 2f * dens
        bubble.color = UiTheme.sheet
        c.drawCircle(cx, cy, bubR, bubble)
        bubbleStroke.strokeWidth = 1f * dens
        c.drawCircle(cx, cy, bubR, bubbleStroke)

        // 24 ticks: idle = dim, comet = busy, filled up to N% = determinate progress
        val n = 24
        val tOuter = bubR - 3.5f * dens
        val tInner = tOuter - 5f * dens
        tick.strokeWidth = 2.4f * dens
        val active = ringProgress >= 0 || activityIndicatorBlinking
        val tickColor = if (active) (activityIndicatorColor ?: mStart) else mStart
        val head = ((rot / 360f) * n).toInt() % n
        val litCount = if (ringProgress > 0) (n * ringProgress / 100f).toInt() else -1
        for (i in 0 until n) {
            var a = 0.22f
            if (comet) {
                val behind = (head - i + n) % n
                if (behind < 6) a = 1f - behind * 0.15f
            } else if (litCount >= 0 && i < litCount) {
                a = 1f
            }
            tick.color = ModeUi.withAlpha(tickColor, (a * 255f).toInt())
            val ang = Math.toRadians(i * 360.0 / n - 90.0)
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            c.drawLine(cx + ca * tInner, cy + sa * tInner, cx + ca * tOuter, cy + sa * tOuter, tick)
        }

        // gradient disc + bolt
        val br = tInner - 4f * dens
        disc.shader = LinearGradient(cx - br, cy - br, cx + br, cy + br, mStart, mEnd, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, br, disc)
        val s = (br * 1.05f).toInt()
        bolt.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
        bolt.draw(c)

        // activity light, sitting on the bubble's top-right edge
        val bx = cx + (bubR - 1f * dens) * 0.707f
        val by = cy - (bubR - 1f * dens) * 0.707f
        badge.alpha = 255
        badge.color = UiTheme.bg
        c.drawCircle(bx, by, 6.5f * dens, badge)
        badge.color = activityIndicatorColor ?: if (autoOn) UiTheme.ok else UiTheme.faint
        badge.alpha = (255f * if (activityIndicatorBlinking) activityIndicatorAlpha else 1f).toInt().coerceIn(0, 255)
        c.drawCircle(bx, by, 4.5f * dens, badge)
        badge.alpha = 255
    }
}

// ============================================================================ dialogs / sheets

object Sheets {
    /** A borderless dialog hosting [content]; bottom-anchored, translucent, blurs the app behind it on Android 12+. */
    fun create(activity: Activity, content: View, fullScreen: Boolean = false, bottom: Boolean = true, dim: Float = 0.5f): Dialog {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCanceledOnTouchOutside(true)
        d.setContentView(content)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, if (fullScreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(if (bottom) Gravity.BOTTOM else Gravity.CENTER)
            w.setDimAmount(dim)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    if (activity.windowManager.isCrossWindowBlurEnabled) {
                        w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        val lp = w.attributes
                        lp.blurBehindRadius = 28
                        w.attributes = lp
                    }
                } catch (_: Throwable) {}
            }
        }
        d.setOnShowListener { slideUp(content) }
        return d
    }

    fun slideUp(v: View) {
        v.alpha = 0f
        v.post {
            v.translationY = v.height * 0.3f
            v.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Rounded top-corner glass background for sheets. */
    fun sheetBackground(c: Context, topRadiusDp: Number = 30): GradientDrawable {
        val r = c.dpf(topRadiusDp)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF5202332.toInt(), 0xF710121B.toInt())
        ).apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(c.dpi(1), 0x24FFFFFF)
        }
    }
}

/** ScrollView that never grows past [maxHeightPx] (sheets keep a gap above them). */
class MaxHeightScroll(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}
