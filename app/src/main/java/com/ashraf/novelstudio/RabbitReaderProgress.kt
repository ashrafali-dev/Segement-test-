package com.ashraf.novelstudio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Rabbit Reader" translation progress.
 *
 * A small rabbit with reading glasses sits and reads an open book while the chapter is
 * being translated. There is no bar: the real percentage floats above its head, and the
 * book itself shows the progress (pages piled on the left grow, pages on the right shrink).
 *
 * [progress] (0..100) is the single source of truth. The number above the rabbit counts
 * smoothly up to it and can never be ahead of it. The rabbit only reacts: it reads, blinks,
 * flicks an ear, adjusts its glasses and turns pages at calm, slightly random intervals
 * (page turns get more frequent as progress grows). At 100% it turns the last page, looks
 * up, adjusts its glasses, smiles, closes the book and the number glows.
 *
 * Pure Canvas, no allocations in onDraw. The only infinite animation (a frame ticker) runs
 * ONLY while [running] is true and the view is actually visible.
 */
class RabbitReaderProgress(context: Context) : View(context) {

    /** Real translation percentage. Everything drawn is derived from this. */
    var progress: Int = 0
        set(v) {
            val t = v.coerceIn(0, 100)
            if (t == field) return
            field = t
            retarget(t)
        }

    /** True while a translation job is actually running (drives the reading animation). */
    var running: Boolean = false
        set(v) {
            if (v == field) return
            field = v
            if (v) startOpen()
            syncTick()
        }

    private var shown = 0f          // smoothed 0..100 (never above progress)
    private var celeb = -1f         // -1 = no finale, 0..1 = playing, 1 = finished
    private var openAmt = 0f        // 0 = closed book, 1 = open
    private var finishAt = 0L
    private var windowVisible = true

    private var moveAnim: ValueAnimator? = null
    private var celebAnim: ValueAnimator? = null
    private var openAnim: ValueAnimator? = null
    private var tick: ValueAnimator? = null

    // occasional actions (glasses / page turn / ear flick)
    private val rng = Random(7)
    private var actType = 0
    private var actStart = 0L
    private var actDur = 0L
    private var nextAt = 0L
    private var lastAct = 0
    private var flickSide = 1f
    private var leftSeed = 11
    private var rightSeed = 23
    private var backSeed = 37
    private var newRightSeed = 41

    private var pctInt = -1
    private var pctText = "0%"

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 11.5f
    }
    private val rect = RectF()
    private val tail = Path()

    /** How long the host should keep the card on screen so the finale can play out. */
    fun holdMs(): Long =
        if (progress == 100) (finishAt - SystemClock.uptimeMillis()).coerceIn(0L, 4200L) else 0L

    // ------------------------------------------------------------------ progress smoothing
    private fun retarget(t: Int) {
        moveAnim?.cancel(); moveAnim = null
        if (t.toFloat() < shown) {
            // new job / reset: snap, then (re)open the book
            celebAnim?.cancel(); celebAnim = null
            openAnim?.cancel(); openAnim = null
            celeb = -1f
            finishAt = 0L
            shown = t.toFloat()
            openAmt = 0f
            actType = 0
            nextAt = 0L
            if (running) startOpen()
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

    private fun startOpen() {
        if (openAmt >= 1f || openAnim != null || celeb >= 0f) return
        openAnim = ValueAnimator.ofFloat(openAmt, 1f).apply {
            duration = 750
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { openAmt = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { openAnim = null }
            })
            start()
        }
    }

    private fun startCelebration() {
        if (actType == ACT_PAGE) { leftSeed = backSeed; rightSeed = newRightSeed }
        actType = 0
        backSeed = rng.nextInt(1000) + 1
        newRightSeed = rng.nextInt(1000) + 1
        celebAnim?.cancel()
        celeb = 0f
        celebAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CELEB_MS
            interpolator = LinearInterpolator()
            addUpdateListener { celeb = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ------------------------------------------------------------------ frame ticker
    private fun syncTick() {
        val should = running && isShown && windowVisible
        if (should) {
            if (tick == null) {
                tick = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { invalidate() }
                    start()
                }
            }
        } else if (tick != null) {
            tick?.cancel(); tick = null
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncTick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tick?.cancel(); tick = null
        moveAnim?.cancel(); moveAnim = null
        celebAnim?.cancel(); celebAnim = null
        if (openAnim != null) openAmt = 1f
        openAnim?.cancel(); openAnim = null
        actType = 0
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

    // ------------------------------------------------------------------ occasional actions
    private fun pickAction(p: Float): Int {
        val bump = (1f - abs(p - 0.4f) / 0.25f).coerceIn(0f, 1f)
        val wPage = if (p < 0.1f) 0f else 0.35f + 0.55f * p
        val wGlass = 0.22f + 0.55f * bump
        val wEar = 0.18f
        val r = rng.nextFloat() * (wPage + wGlass + wEar)
        return if (r < wPage) ACT_PAGE else if (r < wPage + wGlass) ACT_GLASS else ACT_EAR
    }

    private fun updateActions(now: Long, p: Float) {
        if (actType != 0) {
            if (now - actStart >= actDur) {
                if (actType == ACT_PAGE) { leftSeed = backSeed; rightSeed = newRightSeed }
                lastAct = actType
                actType = 0
                nextAt = now + (lerp(5200f, 2600f, p) + rng.nextFloat() * 1800f).toLong()
            }
            return
        }
        if (nextAt == 0L) {
            nextAt = now + 1800L + (rng.nextFloat() * 1200f).toLong()
            return
        }
        if (now < nextAt) return
        var pick = pickAction(p)
        if (pick == lastAct && pick != ACT_EAR) pick = pickAction(p)
        actType = pick
        actStart = now
        actDur = when (pick) {
            ACT_PAGE -> 1700L
            ACT_GLASS -> 1600L
            else -> 520L
        }
        if (pick == ACT_PAGE) {
            backSeed = rng.nextInt(1000) + 1
            newRightSeed = rng.nextInt(1000) + 1
        }
        if (pick == ACT_EAR) flickSide = if (rng.nextBoolean()) 1f else -1f
    }

    private fun flipTheta(u: Float): Float =
        if (u <= 0.24f) 0f else ease(seg(u, 0.24f, 0.7f)) * PI.toFloat()

    // ------------------------------------------------------------------ drawing
    // Everything below is drawn in "design units": 92 units tall, x = 0 is the centre.
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val c = celeb
        val live = tick != null
        val lf = if (live || c >= 0f) 1f else 0f
        val p = min(shown, progress.toFloat()) / 100f
        val o = if (c >= 0f) 1f - ease(seg(c, 0.56f, 0.78f)) else openAmt

        if (live && c < 0f && o >= 0.99f) updateActions(now, p)

        // ---- which action is playing
        var theta = 0f
        var turnU = -1f
        var glassU = -1f
        var flickU = -1f
        if (c >= 0f) {
            if (c < 0.26f) { turnU = c / 0.26f; theta = flipTheta(turnU) }
            if (c >= 0.34f && c <= 0.6f) glassU = seg(c, 0.34f, 0.6f)
        } else if (live && actType != 0) {
            val u = ((now - actStart).toFloat() / actDur).coerceIn(0f, 1f)
            when (actType) {
                ACT_PAGE -> { turnU = u; theta = flipTheta(u) }
                ACT_GLASS -> glassU = u
                else -> flickU = u
            }
        }
        val finished = c >= 0.26f
        val leftS = if (finished) backSeed else leftSeed
        val rightS = if (finished || theta > 0.001f) newRightSeed else rightSeed

        val gRaise = if (glassU >= 0f) ease(seg(glassU, 0f, 0.3f)) - ease(seg(glassU, 0.62f, 0.92f)) else 0f
        val gPush = if (glassU >= 0f) sin(seg(glassU, 0.3f, 0.6f) * PI.toFloat()) else 0f
        val lookUp = if (c >= 0f) ease(seg(c, 0.18f, 0.36f)) else 0f
        val rd = (if (c >= 0f) 1f else ease(o)) * (1f - lookUp) * (1f - 0.9f * gRaise)
        val happy = if (c >= 0f) ease(seg(c, 0.5f, 0.65f)) else 0f
        val perk = if (c >= 0f) ease(seg(c, 0.5f, 0.62f)) * (1f - 0.65f * ease(seg(c, 0.8f, 1f))) else 0f
        val glow = if (c < 0f) 0f else if (c >= 1f) 0.35f else 0.35f + 0.65f * sin(seg(c, 0.5f, 0.9f) * PI.toFloat())

        // reading: eyes sweep a line, then snap back
        val scanP = lerp(2600f, 1500f, p).toLong()
        val lt = (now % scanP).toFloat() / scanP
        var scanX = (if (lt < 0.88f) lerp(-1.1f, 1.1f, lt / 0.88f) else lerp(1.1f, -1.1f, seg(lt, 0.88f, 1f))) * rd * lf
        if (turnU >= 0f) scanX += 0.9f * seg(turnU, 0.05f, 0.3f) * (1f - seg(turnU, 0.7f, 0.95f))

        var blink = 0f
        if (live && c < 0.5f) {
            val b1 = now % 4300L
            if (b1 < 150L) blink = sin(PI.toFloat() * b1 / 150f)
            val b2 = (now + 2100L) % 6900L
            if (b2 < 150L) blink = max(blink, sin(PI.toFloat() * b2 / 150f))
        }

        val headRot = (osc(now, 5200L) * 0.9f + gRaise * 2f) * lf
        val headDy = 0.8f * rd + osc(now, 3400L, 0.8f) * 0.3f * lf
        val br = 1f + 0.012f * osc(now, 3400L) * lf
        val bookDy = osc(now, 3400L, 0.4f) * 0.35f * lf
        val bubDy = osc(now, 2800L) * 0.7f * lf

        val pi = Math.round(shown).coerceIn(0, 100)
        if (pi != pctInt) { pctInt = pi; pctText = "$pi%" }

        val s = h / 92f
        canvas.save()
        canvas.translate(w / 2f, 0f)
        canvas.scale(s, s)

        // ---- cozy glow behind the scene
        for (k in 0..2) {
            fill.color = ACC_B.withA(0.04f + k * 0.012f + glow * 0.04f)
            canvas.drawCircle(0f, 60f, 44f - k * 11f, fill)
        }

        // ---- body
        canvas.save()
        canvas.scale(1f, br, 0f, 86f)
        fill.color = FUR_SHADE
        rect.set(-19.4f, 55.4f, 19.4f, 87f)
        canvas.drawOval(rect, fill)
        fill.color = FUR
        rect.set(-18.5f, 56f, 18.5f, 86f)
        canvas.drawOval(rect, fill)
        fill.color = FUR_SHADE.withA(0.6f)
        rect.set(-11f, 58f, 11f, 66f)
        canvas.drawOval(rect, fill)
        canvas.restore()

        // ---- head (ears, face, glasses)
        canvas.save()
        canvas.translate(0f, headDy)
        canvas.rotate(headRot, 0f, 62f)
        for (k in 0..1) {
            val sd = if (k == 0) -1f else 1f
            var ang = sd * (10f - 8.5f * perk) + osc(now, 4300L, sd * 1.3f) * 1.6f * lf
            if (flickU >= 0f && sd == flickSide) ang += sin(flickU * PI.toFloat() * 3f) * (1f - flickU) * 9f * sd
            canvas.save()
            canvas.translate(sd * 6.2f, 44f)
            canvas.rotate(ang)
            fill.color = FUR_SHADE
            rect.set(-4.4f, -20.2f, 4.4f, 0.6f)
            canvas.drawOval(rect, fill)
            fill.color = FUR
            rect.set(-3.8f, -19.6f, 3.8f, 0f)
            canvas.drawOval(rect, fill)
            fill.color = INNER.withA(0.85f)
            rect.set(-2f, -17f, 2f, -2.5f)
            canvas.drawOval(rect, fill)
            canvas.restore()
        }
        fill.color = FUR_SHADE
        rect.set(-13.8f, 40.1f, 13.8f, 63.4f)
        canvas.drawOval(rect, fill)
        fill.color = FUR
        rect.set(-13.2f, 40.6f, 13.2f, 62.8f)
        canvas.drawOval(rect, fill)
        fill.color = 0xFFE48FBA.toInt().withA(0.28f)
        canvas.drawCircle(-8.4f, 56.4f, 2.4f, fill)
        canvas.drawCircle(8.4f, 56.4f, 2.4f, fill)
        fill.color = Color.WHITE.withA(0.5f)
        rect.set(-4.6f, 53f, 4.6f, 59.4f)
        canvas.drawOval(rect, fill)
        fill.color = NOSE
        rect.set(-1.5f, 53.5f, 1.5f, 55.7f)
        canvas.drawOval(rect, fill)

        // mouth: tiny "w" while reading, a soft smile when happy
        line.strokeWidth = 0.6f
        if (happy < 0.9f) {
            line.color = MOUTH.withA((1f - happy) * 0.85f)
            rect.set(-2.3f, 55.2f, 0f, 57.4f)
            canvas.drawArc(rect, 0f, 180f, false, line)
            rect.set(0f, 55.2f, 2.3f, 57.4f)
            canvas.drawArc(rect, 0f, 180f, false, line)
        }
        if (happy > 0.1f) {
            line.color = MOUTH.withA(happy)
            rect.set(-2.8f, 54.4f, 2.8f, 58.6f)
            canvas.drawArc(rect, 15f, 150f, false, line)
        }

        // eyes
        val eyeOpen = (1f - blink * 0.92f) * (1f - min(1f, happy * 2.2f))
        for (k in 0..1) {
            val sd = if (k == 0) -1f else 1f
            val ex = sd * 5.2f + scanX
            val ey = 51.2f + rd * 1.0f
            if (eyeOpen > 0.05f) {
                fill.color = INK
                rect.set(ex - 1.55f, ey - 1.9f * eyeOpen, ex + 1.55f, ey + 1.9f * eyeOpen)
                canvas.drawOval(rect, fill)
                if (eyeOpen > 0.5f) {
                    fill.color = Color.WHITE.withA(0.9f)
                    canvas.drawCircle(ex + 0.5f, ey - 0.7f, 0.5f, fill)
                }
            }
            if (happy > 0.35f) {
                line.color = INK.withA(seg(happy, 0.35f, 0.7f))
                line.strokeWidth = 1f
                rect.set(ex - 2f, 51.2f - 1.6f, ex + 2f, 51.2f + 1.6f)
                canvas.drawArc(rect, 200f, 140f, false, line)
            }
        }

        // reading glasses (they hop a little when pushed up)
        canvas.save()
        canvas.translate(0f, -gPush * 0.9f)
        canvas.rotate(gPush * 2f, 0f, 51f)
        fill.color = Color.WHITE.withA(0.07f)
        canvas.drawCircle(-5.2f, 51.2f, 4.4f, fill)
        canvas.drawCircle(5.2f, 51.2f, 4.4f, fill)
        line.color = GLASS
        line.strokeWidth = 1f
        canvas.drawCircle(-5.2f, 51.2f, 4.4f, line)
        canvas.drawCircle(5.2f, 51.2f, 4.4f, line)
        canvas.drawLine(-0.9f, 50.6f, 0.9f, 50.6f, line)
        canvas.drawLine(-9.6f, 50.4f, -12.8f, 49.6f, line)
        canvas.drawLine(9.6f, 50.4f, 12.8f, 49.6f, line)
        line.color = Color.WHITE.withA(0.55f)
        line.strokeWidth = 0.6f
        canvas.drawLine(-7.6f, 48.8f, -6.6f, 48f, line)
        canvas.drawLine(2.8f, 48.8f, 3.8f, 48f, line)
        canvas.restore()
        canvas.restore()

        // ---- book
        val sy = 86f
        val pw = 21f
        val ph = 15f
        canvas.save()
        canvas.translate(0f, bookDy)
        val closedA = if (o < 1f) 1f - ease(seg(o, 0.05f, 0.55f)) else 0f
        if (o > 0.01f) {
            val tR = 0.3f + 3.2f * (1f - p)
            val tL = 0.3f + 3.2f * p
            drawPage(canvas, -1f, o, tL, leftS, pw, ph, sy)
            drawPage(canvas, 1f, o, tR, rightS, pw, ph, sy)
            if (theta > 0.001f) drawSheet(canvas, o, theta, rightSeed, backSeed, pw, ph, sy)
            line.color = Color.BLACK.withA(0.3f)
            line.strokeWidth = 0.8f
            canvas.drawLine(0f, sy - ph, 0f, sy, line)
        }
        if (closedA > 0.01f) {
            fill.color = COVER.withA(closedA)
            rect.set(-11.5f, sy - 17f, 11.5f, sy + 1f)
            canvas.drawRoundRect(rect, 2.2f, 2.2f, fill)
            fill.color = Color.BLACK.withA(0.2f * closedA)
            rect.set(-11.5f, sy - 17f, -9f, sy + 1f)
            canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
            line.color = Color.WHITE.withA(0.5f * closedA)
            line.strokeWidth = 1f
            canvas.drawLine(-5.5f, sy - 11f, 6.5f, sy - 11f, line)
            canvas.drawLine(-5.5f, sy - 7.6f, 2.5f, sy - 7.6f, line)
            fill.color = AMBER.withA(closedA)
            rect.set(5f, sy - 17f, 7.2f, sy - 10f)
            canvas.drawRect(rect, fill)
        }
        canvas.restore()

        // ---- arms and paws (on top of the book)
        val restX = lerp(10f, 16.5f, o)
        val restY = lerp(80f, 82f, o) + bookDy
        var rx = restX
        var ry = restY
        if (turnU >= 0f) {
            val tc = min(theta, 0.55f * PI.toFloat())
            val tpx = cos(tc) * pw * 0.92f
            val tpy = 73.5f - sin(tc) * 4.5f
            val k = ease(seg(turnU, 0f, 0.22f)) * (1f - ease(seg(turnU, 0.72f, 1f)))
            rx = lerp(restX, tpx, k)
            ry = lerp(restY, tpy, k)
        }
        if (glassU >= 0f && gRaise > 0f) {
            rx = lerp(restX, 9.2f - gPush * 0.5f, gRaise) + sin(gRaise * PI.toFloat()) * 3.5f
            ry = lerp(restY, 50.2f - gPush * 0.6f, gRaise)
        }
        drawArm(canvas, -12.5f, 64f, -restX, restY)
        drawArm(canvas, 12.5f, 64f, rx, ry)

        // ---- sparkles + tiny page bits at the finish
        if (c >= 0f && c < 1f) {
            for (k in 0..5) {
                val sv = seg(c, 0.58f + k * 0.04f, 0.93f + k * 0.01f)
                if (sv <= 0f || sv >= 1f) continue
                val x = SPX[k]
                val y = 68f - sv * 26f - (k % 2) * 6f
                val a = sin(sv * PI.toFloat()) * 0.9f
                if (k % 2 == 0) {
                    val r = 1.6f + (k % 3) * 0.5f
                    line.color = (if (k % 4 == 0) Color.WHITE else ACC_B).withA(a)
                    line.strokeWidth = 0.8f
                    canvas.drawLine(x - r, y, x + r, y, line)
                    canvas.drawLine(x, y - r, x, y + r, line)
                } else {
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate(sv * 220f)
                    fill.color = PAGE.withA(a)
                    rect.set(-1.3f, -0.9f, 1.3f, 0.9f)
                    canvas.drawRect(rect, fill)
                    canvas.restore()
                }
            }
        }

        // ---- percentage bubble above the head
        val tw = text.measureText(pctText)
        val bw = max(34f, tw + 16f)
        val by0 = 2f + bubDy
        val by1 = by0 + 16f
        if (glow > 0.01f) {
            for (k in 1..2) {
                fill.color = ACC_B.withA(glow * (0.2f - k * 0.06f))
                rect.set(-bw / 2f - k * 2f, by0 - k * 2f, bw / 2f + k * 2f, by1 + k * 2f)
                canvas.drawRoundRect(rect, 8f + k * 2f, 8f + k * 2f, fill)
            }
        }
        fill.color = mix(ACC_A.withA(0.2f), ACC_B.withA(0.5f), glow)
        rect.set(-bw / 2f, by0, bw / 2f, by1)
        canvas.drawRoundRect(rect, 8f, 8f, fill)
        tail.reset()
        tail.moveTo(-3f, by1 - 0.4f)
        tail.lineTo(0f, by1 + 4f)
        tail.lineTo(3f, by1 - 0.4f)
        tail.close()
        canvas.drawPath(tail, fill)
        line.color = mix(ACC_A.withA(0.6f), SPARK_SOFT.withA(0.95f), glow)
        line.strokeWidth = 0.8f
        canvas.drawRoundRect(rect, 8f, 8f, line)
        text.color = mix(SPARK_SOFT, Color.WHITE, glow)
        canvas.drawText(pctText, 0f, by0 + 11.8f, text)

        canvas.restore()
    }

    private fun drawPage(canvas: Canvas, side: Float, o: Float, thick: Float, seed: Int, pw: Float, ph: Float, sy: Float) {
        canvas.save()
        canvas.translate(0f, sy)
        canvas.scale(side * o, 1f)
        canvas.rotate(-5f)
        fill.color = COVER
        rect.set(-1.8f, -ph - 1.5f, pw + 1.5f, thick + 1.5f)
        canvas.drawRoundRect(rect, 2f, 2f, fill)
        fill.color = PAGE_EDGE
        rect.set(0.3f, 0f, pw + 0.3f, thick + 0.6f)
        canvas.drawRoundRect(rect, 1f, 1f, fill)
        if (thick > 1.2f) {
            line.color = LINE.withA(0.4f)
            line.strokeWidth = 0.4f
            canvas.drawLine(0.8f, thick * 0.5f + 0.3f, pw - 0.2f, thick * 0.5f + 0.3f, line)
        }
        fill.color = PAGE
        rect.set(0f, -ph, pw, 0f)
        canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
        fill.color = 0x14000000
        rect.set(0f, -ph, 3.2f, 0f)
        canvas.drawRect(rect, fill)
        pageLines(canvas, seed, pw, ph)
        canvas.restore()
    }

    private fun drawSheet(canvas: Canvas, o: Float, theta: Float, frontSeed: Int, backSeed: Int, pw: Float, ph: Float, sy: Float) {
        val cs = cos(theta)
        if (abs(cs) < 0.02f) return
        canvas.save()
        canvas.translate(0f, sy)
        canvas.scale(o * cs, 1f)
        canvas.rotate(-5f - sin(theta) * 9f)
        fill.color = PAGE
        rect.set(0f, -ph, pw, 0f)
        canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
        fill.color = Color.BLACK.withA(0.16f * sin(theta))
        canvas.drawRoundRect(rect, 1.2f, 1.2f, fill)
        pageLines(canvas, if (cs >= 0f) frontSeed else backSeed, pw, ph)
        canvas.restore()
    }

    private fun pageLines(canvas: Canvas, seed: Int, pw: Float, ph: Float) {
        line.color = LINE.withA(0.55f)
        line.strokeWidth = 0.7f
        for (i in 0..5) {
            val hv = (((seed * 31 + i) * -1640531535) ushr 16) and 0xFF
            var len = 0.55f + hv / 255f * 0.45f
            if (i == 5) len *= 0.6f
            val y = -ph + 3f + i * 2.05f
            canvas.drawLine(2.8f, y, 2.8f + len * (pw - 5.4f), y, line)
        }
    }

    private fun drawArm(canvas: Canvas, sx: Float, sy: Float, px: Float, py: Float) {
        line.color = FUR_SHADE
        line.strokeWidth = 7f
        canvas.drawLine(sx, sy, px, py, line)
        line.color = FUR
        line.strokeWidth = 5.6f
        canvas.drawLine(sx, sy, px, py, line)
        fill.color = FUR_SHADE
        rect.set(px - 3.9f, py - 3.5f, px + 3.9f, py + 3.5f)
        canvas.drawOval(rect, fill)
        fill.color = FUR
        rect.set(px - 3.4f, py - 3f, px + 3.4f, py + 3f)
        canvas.drawOval(rect, fill)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun ease(t: Float) = t * t * (3f - 2f * t)

    private fun seg(c: Float, a: Float, b: Float) = ((c - a) / (b - a)).coerceIn(0f, 1f)

    private fun osc(now: Long, period: Long, off: Float = 0f): Float =
        sin(2f * PI.toFloat() * ((now % period).toFloat() / period) + off)

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
        const val CELEB_MS = 2900L
        const val LINGER_MS = 900L
        const val ACT_PAGE = 1
        const val ACT_GLASS = 2
        const val ACT_EAR = 3
        val ACC_A = 0xFF7B8CFF.toInt()
        val ACC_B = 0xFFB27CFF.toInt()
        val AMBER = 0xFFFFC27E.toInt()
        val GLASS = 0xFFFFC27E.toInt()
        val INK = 0xFF1E2133.toInt()
        val FUR = 0xFFEDEAFB.toInt()
        val FUR_SHADE = 0xFFC9C3EA.toInt()
        val INNER = 0xFFE9BDE8.toInt()
        val NOSE = 0xFFE48FBA.toInt()
        val MOUTH = 0xFFB07A9E.toInt()
        val COVER = 0xFF5A49C4.toInt()
        val PAGE = 0xFFF7F4FF.toInt()
        val PAGE_EDGE = 0xFFD9D4F3.toInt()
        val LINE = 0xFF8F88CC.toInt()
        val SPARK_SOFT = 0xFFCFD5FF.toInt()
        val SPX = floatArrayOf(-23f, -16f, -9f, 11f, 18f, 25f)
    }
}