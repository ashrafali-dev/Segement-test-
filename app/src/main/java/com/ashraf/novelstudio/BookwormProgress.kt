package com.ashraf.novelstudio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * "Bookworm" translation progress.
 *
 * A tiny bookworm with reading glasses eats its way along a line of foreign text
 * (the dashed part). Everything behind it turns into solid, glowing, translated text.
 * At the end of the line waits a little book. At 100% the worm pops, a shimmer sweeps
 * the line, the book lights up and a butterfly flutters out of it.
 *
 * Pure Canvas, no allocations in onDraw. The only infinite animation (the crawl) runs
 * ONLY while [running] is true and the view is actually visible.
 *
 * [progress] (0..100) is the single source of truth. The worm's head is ALWAYS at
 * min(smoothed, progress): it can only catch up to the real value, never run ahead of it.
 * Its crawl gait is only played while it is really moving forward; when the percentage
 * stands still it rests (breathing, blinking, antenna sway) instead of treadmilling.
 */
class BookwormProgress(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    /** Real translation percentage. The worm position is derived from this only. */
    var progress: Int = 0
        set(v) {
            val t = v.coerceIn(0, 100)
            if (t == field) return
            field = t
            retarget(t)
        }

    /** True while a translation job is actually running (drives the crawl animation). */
    var running: Boolean = false
        set(v) {
            if (v == field) return
            field = v
            syncTick()
        }

    private var shown = 0f          // smoothed 0..100 actually drawn
    private var celeb = -1f         // -1 = no celebration, 0..1 = playing, 1 = done
    private var phase = 0f          // 0..1 crawl cycle (time)
    private var gait = 0f           // 0 = resting, 1 = crawling (eased)
    private var fpx = 0f
    private var fpy = 0f
    private var finishAt = 0L
    private var windowVisible = true

    private var moveAnim: ValueAnimator? = null
    private var celebAnim: ValueAnimator? = null
    private var tick: ValueAnimator? = null

    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x4DCFD5FF
        strokeWidth = dp(2.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val upper = Path().apply {
        moveTo(0.5f, -0.5f)
        cubicTo(2f, -7.5f, 7.5f, -8.8f, 8.2f, -4.6f)
        cubicTo(8.6f, -1.6f, 4f, 0.2f, 0.5f, 0.4f)
        close()
    }
    private val lower = Path().apply {
        moveTo(0.4f, 0.6f)
        cubicTo(4.2f, 0.4f, 6.6f, 2.6f, 5.4f, 5.4f)
        cubicTo(4.4f, 7.4f, 1.4f, 6.4f, 0.4f, 3.8f)
        close()
    }
    private val xs = FloatArray(4)
    private val ys = FloatArray(4)
    private val rs = FloatArray(4)

    private val railStart get() = dp(19f)

    /** How long the host should keep the card on screen so the finale can play out. */
    fun holdMs(): Long =
        if (progress == 100) (finishAt - SystemClock.uptimeMillis()).coerceIn(0L, 2600L) else 0L

    // ------------------------------------------------------------------ progress smoothing
    private fun retarget(t: Int) {
        moveAnim?.cancel(); moveAnim = null
        if (t.toFloat() < shown) {
            // new job / reset: snap, never animate backwards
            celebAnim?.cancel(); celebAnim = null
            celeb = -1f
            finishAt = 0L
            shown = t.toFloat()
            invalidate()
            return
        }
        val from = shown
        val dur = (420f + (t - from) * 11f).coerceAtMost(1500f).toLong()
        if (t == 100) finishAt = SystemClock.uptimeMillis() + dur + CELEB_MS + LINGER_MS
        moveAnim = ValueAnimator.ofFloat(from, t.toFloat()).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            if (t == 100) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(a: Animator) { cancelled = true }
                    override fun onAnimationEnd(a: Animator) { if (!cancelled) startCelebration() }
                })
            }
            start()
        }
    }

    private fun startCelebration() {
        celebAnim?.cancel()
        celeb = 0f
        celebAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CELEB_MS
            interpolator = LinearInterpolator()
            addUpdateListener { celeb = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ------------------------------------------------------------------ crawl ticker
    private fun syncTick() {
        val should = running && isShown && windowVisible
        if (should) {
            if (tick == null) {
                tick = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else if (tick != null) {
            tick?.cancel(); tick = null
            phase = 0f
            gait = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncTick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tick?.cancel(); tick = null; phase = 0f; gait = 0f
        moveAnim?.cancel(); moveAnim = null
        celebAnim?.cancel(); celebAnim = null
        shown = progress.toFloat()
        celeb = if (progress == 100) 1f else -1f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncTick()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        syncTick()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val trackEnd = w - dp(3f) - dp(12f) - dp(3f)
        lit.shader = LinearGradient(railStart, 0f, trackEnd, 0f, ACC_A, ACC_B, Shader.TileMode.CLAMP)
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val now = SystemClock.uptimeMillis()

        val trackY = h - dp(10f)
        val bookW = dp(12f)
        val bookH = dp(15f)
        val bookR = w - dp(3f)
        val bookL = bookR - bookW
        val bookB = trackY + dp(2.5f)
        val bookT = bookB - bookH
        val bookCx = (bookL + bookR) / 2f
        val bookCy = (bookT + bookB) / 2f
        val trackEnd = bookL - dp(3f)
        val x0 = dp(23f)
        val x1 = bookL - dp(8f)
        val c = celeb
        val rail = railStart

        // The head can never be ahead of the real percentage.
        val shownNow = min(shown, progress.toFloat())
        val f = shownNow / 100f
        val headX = x0 + (x1 - x0) * f

        // gait: crawl only while actually advancing, rest otherwise
        val moving = c < 0f && shownNow < progress.toFloat() - 0.05f
        gait += ((if (moving) 1f else 0f) - gait) * 0.18f
        if (gait < 0.003f) gait = 0f
        val alive = tick != null
        val ph = phase * 2f * PI.toFloat()
        val idle = (now % 2600L) / 2600f * 2f * PI.toFloat()
        val blink = alive && c < 0f && (now % 3400L) < 130L

        // ---- translated (lit) line, behind the worm
        val baseEnd = headX - dp(2f)
        val litEnd = if (c >= 0f) baseEnd + (trackEnd - baseEnd) * seg(c, 0.1f, 0.4f) else baseEnd
        if (litEnd - rail > 0.5f) {
            lit.strokeWidth = dp(7f); lit.alpha = 52
            canvas.drawLine(rail, trackY, litEnd, trackY, lit)
            lit.strokeWidth = dp(3f); lit.alpha = 255
            canvas.drawLine(rail, trackY, litEnd, trackY, lit)
        }

        // ---- untranslated text ahead of the worm. Anchored to the line (never drifts):
        // the worm simply eats it as the real percentage grows.
        if (c < 0.4f) {
            val clipL = if (c >= 0f) litEnd else headX + dp(2f)
            if (trackEnd > clipL) {
                canvas.save()
                canvas.clipRect(clipL, 0f, trackEnd, h)
                canvas.drawLine(rail, trackY, trackEnd, trackY, dash)
                canvas.restore()
            }
        }

        // ---- quarter markers (little page numbers)
        for (m in 1..3) {
            val mx = x0 + (x1 - x0) * (m * 0.25f)
            fill.color = if (shownNow >= m * 25f) Color.WHITE.withA(0.85f) else Color.WHITE.withA(0.18f)
            canvas.drawCircle(mx, trackY + dp(5.5f), dp(1.2f), fill)
        }

        // ---- stardust + crumbs, only while the worm is really advancing
        if (alive && c < 0f && gait > 0.05f) {
            for (k in 0..2) {
                val t = (phase + k / 3f) % 1f
                val px = headX - dp(20f) - k * dp(11f)
                if (px > rail + dp(2f)) {
                    fill.color = SPARK_SOFT.withA((1f - t) * 0.8f * gait)
                    canvas.drawCircle(px, trackY - dp(5f) - t * dp(12f), dp(1.4f) * (1f - t * 0.5f), fill)
                }
                val cx = headX + dp(5f) + t * dp(6f)
                fill.color = SPARK_SOFT.withA((1f - t) * 0.6f * gait)
                canvas.drawCircle(cx, trackY - dp(3f) - t * dp(6f) - k * dp(0.6f), dp(1f), fill)
            }
        }

        // ---- the worm
        val s = if (c >= 0f) 1f - seg(c, 0f, 0.18f) else 1f
        if (s > 0.02f) {
            val hop = if (c >= 0f) sin(seg(c, 0f, 0.14f) * PI.toFloat()) * dp(4f) else 0f
            val breath = if (alive) sin(idle) * dp(0.35f) * (1f - gait) else 0f
            var x = headX
            for (i in 0 until 4) {
                if (i > 0) x -= dp(5.2f) * (1f + 0.22f * gait * sin(ph - i * 0.8f))
                val r = (if (i == 0) 4.2f else 3.5f - (i - 1) * 0.3f) * d * s
                rs[i] = r
                xs[i] = x
                ys[i] = trackY - r - dp(0.8f) - hop - breath * (1f + i * 0.3f) -
                    max(0f, sin(ph - i * 0.9f)) * dp(2.2f) * s * gait
            }
            for (i in 3 downTo 0) {
                fill.color = WORM[i]
                canvas.drawCircle(xs[i], ys[i], rs[i], fill)
                fill.color = 0x55FFFFFF
                canvas.drawCircle(xs[i] - rs[i] * 0.3f, ys[i] - rs[i] * 0.35f, rs[i] * 0.35f, fill)
            }
            if (s > 0.5f) {
                val hx = xs[0]
                val hy = ys[0]
                val hr = rs[0]
                val sway = (if (alive) sin(idle * 1.5f) else 0f) * dp(1.1f)
                line.color = WORM[0]
                line.strokeWidth = dp(1f)
                canvas.drawLine(hx + dp(1f), hy - hr + dp(0.6f), hx + dp(3.2f) + sway, hy - hr - dp(3f), line)
                fill.color = WORM[0]
                canvas.drawCircle(hx + dp(3.2f) + sway, hy - hr - dp(3f), dp(1.1f), fill)
                val lx = hx + dp(1.6f)
                val ly = hy - dp(0.3f)
                line.color = INK
                line.strokeWidth = dp(0.9f)
                canvas.drawLine(lx - dp(2.2f), ly, hx - hr + dp(0.8f), ly - dp(0.6f), line)
                fill.color = 0xF2FFFFFF.toInt()
                canvas.drawCircle(lx, ly, dp(2.2f), fill)
                canvas.drawCircle(lx, ly, dp(2.2f), line)
                if (blink) {
                    canvas.drawLine(lx - dp(0.9f), ly + dp(0.3f), lx + dp(1.4f), ly + dp(0.3f), line)
                } else {
                    fill.color = INK
                    canvas.drawCircle(lx + dp(0.5f), ly + dp(0.1f), dp(0.95f), fill)
                }
            }
        }

        // ---- ripples from the book
        if (c >= 0f && c < 1f) {
            for (k in 0..1) {
                val v = seg(c, 0.4f + k * 0.1f, 0.75f + k * 0.1f)
                if (v > 0f && v < 1f) {
                    line.color = ACC_A.withA((1f - v) * 0.6f)
                    line.strokeWidth = dp(1.3f) * (1f - v * 0.5f)
                    canvas.drawCircle(bookCx, bookCy, dp(7f) + v * dp(24f), line)
                }
            }
        }

        // ---- the book at the end of the line
        val bl = if (c >= 0f) 1f else 0.4f + 0.6f * seg(shownNow, 55f, 100f)
        val g = if (c >= 0f) sin(seg(c, 0.34f, 0.8f) * PI.toFloat()) else 0f
        if (g > 0.01f) {
            for (k in 0..2) {
                fill.color = ACC_B.withA(g * (0.26f - k * 0.07f))
                canvas.drawCircle(bookCx, bookCy, dp(9f) + k * dp(4.5f) + g * dp(4f), fill)
            }
        }
        val bb = seg(c, 0.38f, 0.78f)
        val pop = if (c >= 0f) 1f + 0.2f * sin(bb * PI.toFloat()) * (1f - bb) else 1f
        val squash = if (c >= 0f) 0.08f * sin(seg(c, 0.3f, 0.4f) * PI.toFloat()) else 0f
        canvas.save()
        canvas.scale(pop, pop - squash, bookCx, bookB)
        fill.color = mix(BOOK_DIM, ACC_B, bl)
        rect.set(bookL, bookT, bookR, bookB)
        canvas.drawRoundRect(rect, dp(2.2f), dp(2.2f), fill)
        fill.color = 0x33000000
        rect.set(bookL, bookT, bookL + dp(2.4f), bookB)
        canvas.drawRoundRect(rect, dp(1.2f), dp(1.2f), fill)
        val ck = if (c >= 0f) seg(c, 0.72f, 0.95f) else 0f
        line.color = Color.WHITE.withA((0.15f + 0.45f * bl) * (1f - ck))
        line.strokeWidth = dp(1f)
        if (ck < 1f) {
            canvas.drawLine(bookL + dp(4.4f), bookT + dp(5f), bookR - dp(2.4f), bookT + dp(5f), line)
            canvas.drawLine(bookL + dp(4.4f), bookT + dp(7.6f), bookR - dp(4.8f), bookT + dp(7.6f), line)
        }
        if (ck > 0f) {
            val p1x = bookL + dp(3.6f); val p1y = bookT + dp(8.8f)
            val p2x = bookL + dp(5.6f); val p2y = bookT + dp(10.8f)
            val p3x = bookL + dp(9.6f); val p3y = bookT + dp(5.8f)
            val k1 = seg(ck, 0f, 0.4f)
            val k2 = seg(ck, 0.4f, 1f)
            line.color = Color.WHITE.withA(0.95f)
            line.strokeWidth = dp(1.5f)
            canvas.drawLine(p1x, p1y, p1x + (p2x - p1x) * k1, p1y + (p2y - p1y) * k1, line)
            if (k2 > 0f) canvas.drawLine(p2x, p2y, p2x + (p3x - p2x) * k2, p2y + (p3y - p2y) * k2, line)
        }
        fill.color = mix(0xFF6B6F99.toInt(), AMBER, bl)
        rect.set(bookR - dp(4.4f), bookT, bookR - dp(2.6f), bookT + dp(5.4f))
        canvas.drawRect(rect, fill)
        canvas.restore()

        // ---- finale
        if (c >= 0f && c < 1f) {
            burst(canvas, x1, trackY - dp(5f), seg(c, 0f, 0.22f), 6, dp(11f), dp(2f), 0.3f)

            val su = seg(c, 0f, 0.36f)
            if (su > 0.001f && su < 0.999f) {
                val sx = rail + (x1 - rail) * (su * su * (3f - 2f * su))
                for (k in 0..3) {
                    val px = sx - k * dp(5f)
                    if (px > rail) {
                        fill.color = Color.WHITE.withA((1f - k * 0.25f) * 0.9f)
                        canvas.drawCircle(px, trackY, dp(2.4f) - k * dp(0.45f), fill)
                    }
                }
            }

            burst(canvas, bookCx, bookCy, seg(c, 0.42f, 0.8f), 8, dp(12f), dp(1.9f), 0.4f)

            val u = seg(c, 0.42f, 1f)
            if (u > 0f) {
                val aMain = seg(u, 0f, 0.08f) * (1f - seg(u, 0.82f, 1f))
                // glitter trail along the flight path
                for (k in 6 downTo 1) {
                    val uk = u - k * 0.05f
                    if (uk > 0f) {
                        flight(uk, bookCx, bookT)
                        val fade = (1f - k / 7f) * 0.75f * aMain
                        fill.color = SPARK[k % 4].withA(fade)
                        canvas.drawCircle(fpx, fpy, dp(1.7f) * (1f - k / 9f), fill)
                        if (k % 2 == 0) {
                            line.color = Color.WHITE.withA(fade)
                            line.strokeWidth = dp(0.8f)
                            val a = dp(2.6f) * (1f - k / 9f)
                            canvas.drawLine(fpx - a, fpy, fpx + a, fpy, line)
                            canvas.drawLine(fpx, fpy - a, fpx, fpy + a, line)
                        }
                    }
                }
                flight(min(u + 0.02f, 1f), bookCx, bookT)
                val nx = fpx
                val ny = fpy
                flight(u, bookCx, bookT)
                val bank = atan2(ny - fpy, fpx - nx)
                val rot = (-14f + bank * 0.45f * 57.2958f).coerceIn(-34f, 24f)
                val sc = (0.35f + 0.65f * seg(u, 0f, 0.2f)) * (1f + 0.15f * sin(seg(u, 0f, 0.35f) * PI.toFloat()))
                val theta = u * PI.toFloat() * (18f - 8f * u)
                val flap = 0.2f + 0.8f * abs(cos(theta))
                butterfly(canvas, fpx, fpy, sc * 0.9f, flap, rot, aMain)
            }
        }
    }

    private fun flight(u: Float, ox: Float, oy: Float) {
        val e = 1f - (1f - u) * (1f - u)
        fpx = ox - e * dp(78f)
        fpy = oy - dp(3f) - sin(u * PI.toFloat()) * dp(8f) +
            sin(u * PI.toFloat() * 3f) * dp(2.2f) * (1f - u)
    }

    private fun butterfly(canvas: Canvas, x: Float, y: Float, sc: Float, flap: Float, rot: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rot)
        canvas.scale(sc * d, sc * d)
        if (alpha <= 0.01f) return
        for (k in 0..1) {
            val sgn = if (k == 0) -1f else 1f
            canvas.save()
            canvas.scale(sgn * flap, 1f)
            fill.color = ACC_A.withA(alpha * 0.92f)
            canvas.drawPath(lower, fill)
            fill.color = ACC_B.withA(alpha * 0.96f)
            canvas.drawPath(upper, fill)
            line.color = Color.WHITE.withA(alpha * 0.4f)
            line.strokeWidth = 0.45f
            canvas.drawPath(upper, line)
            canvas.drawPath(lower, line)
            fill.color = Color.WHITE.withA(alpha * 0.55f)
            canvas.drawCircle(5.2f, -4.4f, 1.25f, fill)
            canvas.drawCircle(3.4f, 3.7f, 0.85f, fill)
            canvas.restore()
        }
        line.color = INK.withA(alpha)
        line.strokeWidth = 1.5f
        canvas.drawLine(0f, -3f, 0f, 4.2f, line)
        fill.color = INK.withA(alpha)
        canvas.drawCircle(0f, -3.7f, 1.3f, fill)
        line.strokeWidth = 0.6f
        canvas.drawLine(-0.3f, -4.7f, -2.3f, -7.2f, line)
        canvas.drawLine(0.3f, -4.7f, 2.3f, -7.2f, line)
        canvas.restore()
    }

    private fun burst(canvas: Canvas, cx: Float, cy: Float, u: Float, n: Int, dist: Float, r: Float, rot: Float) {
        if (u <= 0f || u >= 1f) return
        val e = 1f - (1f - u) * (1f - u)
        for (k in 0 until n) {
            val a = rot + k * (2f * PI.toFloat() / n)
            fill.color = SPARK[k % 4].withA(1f - u)
            canvas.drawCircle(cx + cos(a) * dist * e, cy + sin(a) * dist * e, r * (1f - u * 0.6f), fill)
        }
    }

    private fun seg(c: Float, a: Float, b: Float) = ((c - a) / (b - a)).coerceIn(0f, 1f)

    private fun Int.withA(a: Float): Int = (this and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private fun mix(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    private companion object {
        const val CELEB_MS = 2300L
        const val LINGER_MS = 350L
        val ACC_A = 0xFF7B8CFF.toInt()
        val ACC_B = 0xFFB27CFF.toInt()
        val AMBER = 0xFFFFC27E.toInt()
        val INK = 0xFF1E2133.toInt()
        val BOOK_DIM = 0xFF343A63.toInt()
        val SPARK_SOFT = 0xFFCFD5FF.toInt()
        val WORM = intArrayOf(0xFFFFD79A.toInt(), 0xFFFFC27E.toInt(), 0xFFFFAE78.toInt(), 0xFFF59A78.toInt())
        val SPARK = intArrayOf(0xFFFFFFFF.toInt(), AMBER, ACC_B, ACC_A)
    }
}