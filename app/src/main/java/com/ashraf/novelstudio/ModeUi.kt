package com.ashraf.novelstudio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Reading-mode identity shared by the dock capsule, the extract button and the centre animation.
 * Index 0 = Novel, 1 = Scan (WebToon scanning), 2 = Live (WebToon real-time).
 */
object ModeUi {
    val names = arrayOf("Novel", "Scan", "Live")
    private val starts = intArrayOf(UiTheme.accentA, 0xFF4ADEB0.toInt(), 0xFFFF8FB5.toInt())
    private val ends = intArrayOf(UiTheme.accentB, 0xFF1D9E75.toInt(), 0xFFD4537E.toInt())

    fun start(i: Int): Int = starts[i.coerceIn(0, 2)]
    fun end(i: Int): Int = ends[i.coerceIn(0, 2)]
    fun mid(i: Int): Int = lerp(start(i), end(i), 0.5f)

    /** [c] with its alpha replaced by [a] (0..255). */
    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }
}

/** Draws the three mode glyphs (book / scan frame / broadcast) on a 24-unit grid. */
class ModeGlyphPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, mode: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(s, s)
        stroke.color = color
        stroke.strokeWidth = 1.9f
        fill.color = color
        when (mode) {
            0 -> {
                path.reset()
                path.moveTo(12f, 6.5f)
                path.cubicTo(10f, 4.8f, 6.5f, 4.5f, 3.5f, 5.2f)
                path.lineTo(3.5f, 18.2f)
                path.cubicTo(6.5f, 17.5f, 10f, 17.8f, 12f, 19.5f)
                path.cubicTo(14f, 17.8f, 17.5f, 17.5f, 20.5f, 18.2f)
                path.lineTo(20.5f, 5.2f)
                path.cubicTo(17.5f, 4.5f, 14f, 4.8f, 12f, 6.5f)
                path.close()
                c.drawPath(path, stroke)
                c.drawLine(12f, 6.5f, 12f, 19.5f, stroke)
            }
            1 -> {
                path.reset()
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(4f, 15f); path.lineTo(4f, 20f); path.lineTo(9f, 20f)
                path.moveTo(15f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 15f)
                c.drawPath(path, stroke)
                c.drawLine(3f, 12f, 21f, 12f, stroke)
            }
            else -> {
                c.drawCircle(12f, 12f, 2.2f, fill)
                for (r in floatArrayOf(5.5f, 9.5f)) {
                    rect.set(12f - r, 12f - r, 12f + r, 12f + r)
                    c.drawArc(rect, -42f, 84f, false, stroke)
                    c.drawArc(rect, 138f, 84f, false, stroke)
                }
            }
        }
        c.restore()
    }
}

// ============================================================================ dock capsule

/** Three-way reading-mode switch: icons only, a coloured thumb that slides with a little spring. */
class ModeCapsule(ctx: Context) : View(ctx) {
    var onSelect: ((Int) -> Unit)? = null

    private val dens = ctx.resources.displayMetrics.density
    private val glyph = ModeGlyphPainter()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x14FFFFFF }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dens
        color = UiTheme.strokeSoft
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pad = 3f * dens

    private var selected = 0
    private var pos = 0f
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = false
        contentDescription = "Reading mode: ${ModeUi.names[0]}"
    }

    /** Bounds of one icon slot in this view's own coordinates (used by the guided tour). */
    fun segmentBounds(i: Int): RectF {
        val segW = (width - 2f * pad) / 3f
        return RectF(pad + segW * i, pad, pad + segW * (i + 1), height - pad)
    }

    /** Returns true when the selection actually changed. */
    fun setSelectedIndex(i: Int, animate: Boolean): Boolean {
        val target = i.coerceIn(0, 2)
        if (target == selected) return false
        selected = target
        contentDescription = "Reading mode: ${ModeUi.names[target]}"
        anim?.cancel()
        if (animate && isAttachedToWindow && width > 0) {
            anim = ValueAnimator.ofFloat(pos, target.toFloat()).apply {
                duration = 380L
                interpolator = OvershootInterpolator(1.5f)
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            pos = target.toFloat()
            invalidate()
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((84f * dens).toInt(), widthMeasureSpec),
            resolveSize((38f * dens).toInt(), heightMeasureSpec)
        )
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        pos = selected.toFloat()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, track)
        val half = trackStroke.strokeWidth / 2f
        rect.set(half, half, w - half, h - half)
        c.drawRoundRect(rect, r - half, r - half, trackStroke)

        val segW = (w - 2f * pad) / 3f
        val th = h - 2f * pad
        val left = pad + pos * segW
        rect.set(left, pad, left + segW, pad + th)

        val a = pos.toInt().coerceIn(0, 1)
        val t = pos - a
        val c0 = ModeUi.lerp(ModeUi.start(a), ModeUi.start(a + 1), t)
        val c1 = ModeUi.lerp(ModeUi.end(a), ModeUi.end(a + 1), t)
        thumb.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, c0, c1, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, th / 2f, th / 2f, thumb)

        for (i in 0..2) {
            val cx = pad + segW * (i + 0.5f)
            val d = min(1f, abs(pos - i))
            glyph.draw(c, i, cx, h / 2f, 17f * dens, ModeUi.lerp(Color.WHITE, UiTheme.iconIdle, d))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val segW = (width - 2f * pad) / 3f
                val i = ((e.x - pad) / segW).toInt().coerceIn(0, 2)
                performClick()
                onSelect?.invoke(i)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

// ============================================================================ centre-of-page animation

/**
 * Plays a short "scanner" style animation in the middle of the page when the reading mode changes:
 *  - Novel: a page writes itself line by line and gets a bookmark ribbon
 *  - Scan:  corner brackets snap onto a document while a light beam sweeps down and back
 *  - Live:  a pulsing dot sends out broadcast ripples
 * It never takes touches, so the page underneath stays usable.
 */
class ModeSwitchOverlay(ctx: Context) : View(ctx) {
    private val dens = ctx.resources.displayMetrics.density
    private var mode = 0
    private var t = 0f
    private var anim: ValueAnimator? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 17f * dens
        letterSpacing = 0.08f
        color = Color.WHITE
    }
    private val rect = RectF()
    private val path = Path()

    private var glowShader: RadialGradient? = null
    private var glowMode = -1
    private var glowSize = 0f

    private val beamShader: LinearGradient by lazy {
        val col = ModeUi.mid(1)
        LinearGradient(
            0f, -14f * dens, 0f, 14f * dens,
            intArrayOf(ModeUi.withAlpha(col, 0), ModeUi.withAlpha(col, 0x99), ModeUi.withAlpha(col, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        isHapticFeedbackEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = 14f * dens
    }

    fun play(m: Int) {
        mode = m.coerceIn(0, 2)
        anim?.cancel()
        t = 0f
        visibility = VISIBLE
        bringToFront()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) visibility = GONE
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((230f * dens).toInt(), widthMeasureSpec),
            resolveSize((230f * dens).toInt(), heightMeasureSpec)
        )
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        visibility = GONE
        super.onDetachedFromWindow()
    }

    private fun easeOut(x: Float): Float {
        val k = 1f - x.coerceIn(0f, 1f)
        return 1f - k * k * k
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val env = when {
            t < 0.08f -> t / 0.08f
            t > 0.82f -> (1f - t) / 0.18f
            else -> 1f
        }.coerceIn(0f, 1f)
        val grow = if (t < 0.12f) easeOut(t / 0.12f) else 1f
        val sc = 0.9f + 0.1f * grow + (if (t > 0.82f) 0.04f * ((t - 0.82f) / 0.18f) else 0f)

        c.saveLayerAlpha(0f, 0f, w, h, (env * 255f).toInt())
        c.scale(sc, sc, cx, cy)

        val col = ModeUi.mid(mode)
        val cardW = 170f * dens

        // soft coloured glow behind the card
        if (glowShader == null || glowMode != mode || glowSize != cardW) {
            glowShader = RadialGradient(
                cx, cy, cardW * 0.9f,
                ModeUi.withAlpha(col, 0x55), ModeUi.withAlpha(col, 0), Shader.TileMode.CLAMP
            )
            glowMode = mode
            glowSize = cardW
        }
        p.shader = glowShader
        c.drawCircle(cx, cy, cardW * 0.9f, p)
        p.shader = null

        // glass card
        rect.set(cx - cardW / 2f, cy - cardW / 2f, cx + cardW / 2f, cy + cardW / 2f)
        p.color = UiTheme.sheet
        p.alpha = 0xEE
        c.drawRoundRect(rect, 30f * dens, 30f * dens, p)
        p.alpha = 255
        st.color = 0x33FFFFFF
        st.strokeWidth = 1f * dens
        c.drawRoundRect(rect, 30f * dens, 30f * dens, st)

        // document geometry shared by Novel and Scan
        val dw = 70f * dens
        val dh = 92f * dens
        val dl = cx - dw / 2f
        val dt = cy - 12f * dens - dh / 2f

        when (mode) {
            0 -> drawNovel(c, col, dl, dt, dw, dh)
            1 -> drawScan(c, col, dl, dt, dw, dh)
            else -> drawLive(c, col, cx, cy - 12f * dens)
        }

        c.drawText(ModeUi.names[mode], cx, cy + 70f * dens, txt)
        c.restore()
    }

    private fun drawNovel(c: Canvas, col: Int, dl: Float, dt: Float, dw: Float, dh: Float) {
        rect.set(dl, dt, dl + dw, dt + dh)
        p.color = 0x10FFFFFF
        c.drawRoundRect(rect, 9f * dens, 9f * dens, p)
        st.color = col
        st.strokeWidth = 2.2f * dens
        c.drawRoundRect(rect, 9f * dens, 9f * dens, st)

        st.strokeWidth = 3f * dens
        st.color = ModeUi.withAlpha(col, 0xE6)
        for (i in 0..5) {
            val pr = easeOut((t - 0.10f - i * 0.09f) / 0.22f)
            val len = (dw - 24f * dens) * (if (i == 5) 0.6f else 1f)
            val y = dt + 22f * dens + i * 11.5f * dens
            val x0 = dl + 12f * dens
            c.drawLine(x0, y, x0 + len * pr, y, st)
        }

        val rp = easeOut((t - 0.62f) / 0.16f)
        if (rp > 0.02f) {
            val rx = dl + dw - 20f * dens
            val rw = 11f * dens
            val rh = 26f * dens * rp
            path.reset()
            path.moveTo(rx, dt)
            path.lineTo(rx + rw, dt)
            path.lineTo(rx + rw, dt + rh)
            path.lineTo(rx + rw / 2f, dt + max(0f, rh - 5f * dens))
            path.lineTo(rx, dt + rh)
            path.close()
            p.color = col
            c.drawPath(path, p)
        }
    }

    private fun drawScan(c: Canvas, col: Int, dl: Float, dt: Float, dw: Float, dh: Float) {
        // beam position: down then back up
        val beamP = ((t - 0.14f) / 0.66f).coerceIn(0f, 1f)
        val phase = if (beamP < 0.5f) beamP * 2f else (1f - beamP) * 2f
        val smooth = phase * phase * (3f - 2f * phase)
        val beamY = dt + dh * smooth

        rect.set(dl, dt, dl + dw, dt + dh)
        p.color = 0x0CFFFFFF
        c.drawRoundRect(rect, 9f * dens, 9f * dens, p)
        st.color = 0x40FFFFFF
        st.strokeWidth = 2f * dens
        c.drawRoundRect(rect, 9f * dens, 9f * dens, st)

        // text lines get brighter once the beam has passed them
        st.strokeWidth = 3f * dens
        for (i in 0..4) {
            val y = dt + 18f * dens + i * 14f * dens
            val len = (dw - 24f * dens) * (if (i == 4) 0.55f else 1f)
            st.color = ModeUi.withAlpha(col, if (y < beamY) 0xBF else 0x40)
            val x0 = dl + 12f * dens
            c.drawLine(x0, y, x0 + len, y, st)
        }

        // scanned area tint
        val tintBottom = max(dt + 2f * dens, beamY)
        p.color = ModeUi.withAlpha(col, 0x22)
        c.drawRect(dl + 2f * dens, dt + 2f * dens, dl + dw - 2f * dens, tintBottom, p)

        // the beam itself
        c.save()
        c.translate(0f, beamY)
        p.shader = beamShader
        c.drawRect(dl - 6f * dens, -14f * dens, dl + dw + 6f * dens, 14f * dens, p)
        p.shader = null
        c.restore()
        st.color = col
        st.strokeWidth = 2.4f * dens
        c.drawLine(dl - 6f * dens, beamY, dl + dw + 6f * dens, beamY, st)

        // corner brackets snap in from outside
        val e = (1f - easeOut(t / 0.2f)) * 14f * dens
        val bl = 18f * dens
        val l = dl - 6f * dens - e
        val tp = dt - 6f * dens - e
        val r = dl + dw + 6f * dens + e
        val b = dt + dh + 6f * dens + e
        st.strokeWidth = 3.6f * dens
        st.color = col
        path.reset()
        path.moveTo(l, tp + bl); path.lineTo(l, tp); path.lineTo(l + bl, tp)
        path.moveTo(r - bl, tp); path.lineTo(r, tp); path.lineTo(r, tp + bl)
        path.moveTo(l, b - bl); path.lineTo(l, b); path.lineTo(l + bl, b)
        path.moveTo(r - bl, b); path.lineTo(r, b); path.lineTo(r, b - bl)
        c.drawPath(path, st)
    }

    private fun drawLive(c: Canvas, col: Int, cx: Float, cy: Float) {
        val pulse = 1f + 0.18f * sin(t * 6.2831855f * 2f)
        p.color = col
        c.drawCircle(cx, cy, 7f * dens * pulse, p)
        st.strokeWidth = 2.6f * dens
        for (k in 0..2) {
            var ph = t * 1.5f - k * 0.34f
            ph -= floor(ph)
            val rr = 12f * dens + ph * 50f * dens
            st.color = ModeUi.withAlpha(col, ((1f - ph) * 0.85f * 255f).toInt())
            c.drawCircle(cx, cy, rr, st)
        }
    }
}
