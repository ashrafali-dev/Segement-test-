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
import android.util.TypedValue
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
 * Novel Studio design system ("Calm").
 *
 * A quiet, low-chrome language built on one accent per reading mode. Everything shared between screens
 * lives here: colour roles (dark + light), the type scale, Canvas-drawn icons (no emoji in the chrome),
 * surfaces/ripples, buttons, the progress bar, the extract button, the snackbar, in-app confirm dialogs
 * and the sheet building blocks.
 *
 * Colour is accessed through **roles**, not raw palette values: screens ask for [UiTheme.role] and get a
 * colour that already resolves for the active theme (dark by default). Raw [UiTheme] fields remain as the
 * dark palette so older call sites keep working; new code should use roles.
 *
 * Rules kept along the way:
 *  - no text is drawn over the WebView content (translated text must never be covered by app chrome):
 *    notifications and sheets live inside the bottom dock row instead of floating over the page;
 *  - the WebView overlays written by Js.kt / WebModeWriter.kt (translated bubble text, its gradients and
 *    colours) are untouched — only the app's own Android chrome is restyled;
 *  - every tappable control is at least [Touch.min] dp.
 */
object UiTheme {
    val bg = 0xFF09090B.toInt()
    val surface = 0xFF111114.toInt()
    val surfaceRaised = 0xFF17171B.toInt()
    val sheet = 0xFF131317.toInt()
    val text = 0xFFF1F1F3.toInt()
    val dim = 0xFFA0A0AA.toInt()
    val faint = 0xFF6E6E77.toInt()
    val iconIdle = 0xFFC4C4CC.toInt()
    val accentA = 0xFF8B93FF.toInt()
    val accentB = 0xFFB49BFF.toInt()
    val accent = 0xFF9AA0FF.toInt()
    val ok = 0xFF51D29B.toInt()
    val danger = 0xFFF17289.toInt()
    val warn = 0xFFF0B55C.toInt()
    /** Text/glyph colour that sits on top of a [ok] / [warn] / [danger] filled surface. */
    val onOk = 0xFF062B1D.toInt()
    val onDanger = 0xFF2B0810.toInt()
    val onWarn = 0xFF2B1D05.toInt()
    val onAccent = 0xFF08081A.toInt()
    const val stroke = 0x1AFFFFFF
    const val strokeSoft = 0x0EFFFFFF
    const val hairline = 0x12FFFFFF
    val scrim = 0xB3000000.toInt()

    /** Reading-mode accent pairs (Novel, Scan, Live). */
    val modeStart = intArrayOf(accentA, 0xFF3FD3A6.toInt(), 0xFFFF8FA8.toInt())
    val modeEnd = intArrayOf(accentB, 0xFF1FA37C.toInt(), 0xFFE0627F.toInt())

    fun start(i: Int): Int = modeStart[(i + 3) % 3]
    fun end(i: Int): Int = modeEnd[(i + 3) % 3]
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

    /**
     * Multiplies [c] toward black ([k] in 0..1) or toward white ([k] negative). Used by the light theme
     * so one palette yields two schemes without floating separate mirror tables that drift apart.
     */
    fun shade(c: Int, k: Float): Int {
        val t = k.coerceIn(-1f, 1f)
        fun ch(v: Int): Int = if (t >= 0f) (v * (1f - t)).toInt() else {
            (v + (255 - v) * -t).toInt()
        }
        return Color.argb(Color.alpha(c), ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    /** Chroma-preserving darkening with a slight desaturation, for the second colour of a cover gradient. */
    fun deepen(c: Int, k: Float): Int {
        val t = k.coerceIn(0f, 1f)
        val r = (Color.red(c) * (1f - t)).toInt()
        val g = (Color.green(c) * (1f - t)).toInt()
        val b = (Color.blue(c) * (1f - t)).toInt()
        return Color.argb(Color.alpha(c), r, g, b)
    }

    /** Contrast (WCAG 2.1) of [fg] on [bg]; both opaque. 4.5 is the body-copy floor, 3.0 for large text. */
    fun contrast(fg: Int, bg: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val l1 = 0.2126 * lin(Color.red(fg)) + 0.7152 * lin(Color.green(fg)) + 0.0722 * lin(Color.blue(fg))
        val l2 = 0.2126 * lin(Color.red(bg)) + 0.7152 * lin(Color.green(bg)) + 0.0722 * lin(Color.blue(bg))
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    // ------------------------------------------------------------------ roles

    /** Semantic colour roles. A screen asks for a role and gets the active theme's colour. */
    enum class Role {
        BG, SURFACE, SURFACE_RAISED, SHEET,
        TEXT, DIM, FAINT, ICON,
        ACCENT, ACCENT_START, ACCENT_END, ON_ACCENT,
        OK, ON_OK, WARN, ON_WARN, DANGER, ON_DANGER,
        STROKE, STROKE_SOFT, HAIRLINE, SCRIM,
        FIELD, FIELD_FOCUSED, CONTROL, CONTROL_PRESSED,
        GLASS_TOP, GLASS_BOTTOM
    }

    /**
     * Active scheme. Dark is the default and the original identity. Light ("Paper") is opt-in from
     * Settings › Appearance; the WebViews keep their own dark-mode handling, so only app chrome switches.
     */
    @Volatile var dark: Boolean = true

    private val light: Map<Role, Int> = mapOf(
        Role.BG to 0xFFF5F5F8.toInt(),
        Role.SURFACE to 0xFFFFFFFF.toInt(),
        Role.SURFACE_RAISED to 0xFFEDEDF2.toInt(),
        Role.SHEET to 0xFFFBFBFC.toInt(),
        Role.TEXT to 0xFF15151A.toInt(),
        Role.DIM to 0xFF565661.toInt(),
        Role.FAINT to 0xFF86868F.toInt(),
        Role.ICON to 0xFF3B3B45.toInt(),
        Role.ACCENT to 0xFF5A61E8.toInt(),
        Role.ACCENT_START to 0xFF5A61E8.toInt(),
        Role.ACCENT_END to 0xFF7A4FE0.toInt(),
        Role.ON_ACCENT to 0xFFFFFFFF.toInt(),
        Role.OK to 0xFF12855B.toInt(),
        Role.ON_OK to 0xFFFFFFFF.toInt(),
        Role.WARN to 0xFF9A6207.toInt(),
        Role.ON_WARN to 0xFFFFFFFF.toInt(),
        Role.DANGER to 0xFFC4263F.toInt(),
        Role.ON_DANGER to 0xFFFFFFFF.toInt(),
        Role.STROKE to 0x1A000000,
        Role.STROKE_SOFT to 0x0F000000,
        Role.HAIRLINE to 0x14000000,
        Role.SCRIM to 0x66000000,
        Role.FIELD to 0xFFF0F0F4.toInt(),
        Role.FIELD_FOCUSED to 0xFFFFFFFF.toInt(),
        Role.CONTROL to 0xFFECECF0.toInt(),
        Role.CONTROL_PRESSED to 0xFFE0E0E6.toInt(),
        Role.GLASS_TOP to 0xFCFFFFFF.toInt(),
        Role.GLASS_BOTTOM to 0xFCF7F7FA.toInt()
    )

    fun role(r: Role): Int {
        if (dark) return when (r) {
            Role.BG -> bg
            Role.SURFACE -> surface
            Role.SURFACE_RAISED -> surfaceRaised
            Role.SHEET -> sheet
            Role.TEXT -> text
            Role.DIM -> dim
            Role.FAINT -> faint
            Role.ICON -> iconIdle
            Role.ACCENT -> accent
            Role.ACCENT_START -> accentA
            Role.ACCENT_END -> accentB
            Role.ON_ACCENT -> onAccent
            Role.OK -> ok
            Role.ON_OK -> onOk
            Role.WARN -> warn
            Role.ON_WARN -> onWarn
            Role.DANGER -> danger
            Role.ON_DANGER -> onDanger
            Role.STROKE -> stroke
            Role.STROKE_SOFT -> strokeSoft
            Role.HAIRLINE -> hairline
            Role.SCRIM -> scrim
            Role.FIELD -> 0x0DFFFFFF
            Role.FIELD_FOCUSED -> 0x1AFFFFFF
            Role.CONTROL -> 0x0FFFFFFF
            Role.CONTROL_PRESSED -> 0x1FFFFFFF
            Role.GLASS_TOP -> 0xF217171C.toInt()
            Role.GLASS_BOTTOM -> 0xF2131317.toInt()
        }
        return light[r] ?: bg
    }

    /** Accent pair for a reading mode (0 Novel, 1 Scan, 2 Live), resolved for the active theme. */
    fun modeStart(i: Int): Int = if (dark) start(i) else role(Role.ACCENT_START)
    fun modeEnd(i: Int): Int = if (dark) end(i) else role(Role.ACCENT_END)
}

/** Type scale, in sp. One family, a few weights, generous line spacing for long-form text. */
object Type {
    fun sans(weight: Int = Typeface.NORMAL): Typeface = when (weight) {
        Typeface.BOLD -> Typeface.create("sans-serif-medium", Typeface.BOLD)
        Typeface.ITALIC -> Typeface.create("sans-serif", Typeface.ITALIC)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
    fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun bold(): Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

    const val display = 28f
    const val title = 21f
    const val heading = 17f
    const val body = 15f
    const val label = 13f
    const val caption = 12f
    const val overline = 11f
}

/** 4dp grid. */
object Space {
    const val xs = 4
    const val s = 8
    const val m = 12
    const val l = 16
    const val xl = 20
    const val xxl = 28
    const val radiusS = 10
    const val radiusM = 16
    const val radiusL = 22
    const val radiusPill = 100
}

/** Touch-target floor (Material: 48dp). */
object Touch {
    const val min = 48
    const val minCompact = 44
}

internal fun Context.dpi(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
internal fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

internal fun Context.sp(v: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics)

// ============================================================================ icons

enum class Ic {
    BACK, FORWARD, RELOAD, HOME, GRID, DOTS, PLUS, CLOSE, SHARE, SEARCH, BOLT, BOOKMARK, BOOK,
    CHAT, SPLIT, SPARK, SAVE, STOP, GLOBE, CHECK, LOCK, CLOCK, SLIDERS, MOON, SHIELD, TEXT, CHEVRON,
    DOWNLOAD, TRASH, EDIT, MORE_VERTICAL, INFO, ALERT, REFRESH_DOT, LIBRARY, FILTER, ARROW_UP, LAYERS,
    SCAN, LIVE, SUN, PIN, CLEAR, UPLOAD, MINUS, FONT_UP, FONT_DOWN, PALETTE, TUNE, EXTENSIONS
}

/** 24x24 stroke icons, drawn at any size. 1.7-unit stroke, round caps, optically balanced. */
class IconDrawable(private val ic: Ic, private var color: Int, private val strokeUnits: Float = 1.7f) : Drawable() {
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
            Ic.CHEVRON -> poly(canvas, 9.5f, 6f, 15f, 12f, 9.5f, 18f)
            Ic.ARROW_UP -> { line(canvas, 12f, 19f, 12f, 5.5f); poly(canvas, 6.5f, 11f, 12f, 5.5f, 17.5f, 11f) }
            Ic.RELOAD, Ic.REFRESH_DOT -> {
                rect.set(5f, 5f, 19f, 19f)
                canvas.drawArc(rect, -40f, 290f, false, stroke)
                val th = Math.toRadians(250.0)
                val tx = 12f + 7f * Math.cos(th).toFloat()
                val ty = 12f + 7f * Math.sin(th).toFloat()
                arrowHead(canvas, tx, ty, -Math.sin(th).toFloat(), Math.cos(th).toFloat(), 3.8f)
            }
            Ic.HOME -> {
                poly(canvas, 4f, 11f, 12f, 4f, 20f, 11f)
                poly(canvas, 6.5f, 9.6f, 6.5f, 20f, 17.5f, 20f, 17.5f, 9.6f)
            }
            Ic.GRID -> {
                rect.set(4.5f, 4.5f, 10.5f, 10.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
                rect.set(13.5f, 4.5f, 19.5f, 10.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
                rect.set(4.5f, 13.5f, 10.5f, 19.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
                rect.set(13.5f, 13.5f, 19.5f, 19.5f); canvas.drawRoundRect(rect, 1.8f, 1.8f, stroke)
            }
            Ic.DOTS -> {
                canvas.drawCircle(5.5f, 12f, 1.45f, fill)
                canvas.drawCircle(12f, 12f, 1.45f, fill)
                canvas.drawCircle(18.5f, 12f, 1.45f, fill)
            }
            Ic.MORE_VERTICAL -> {
                canvas.drawCircle(12f, 5.5f, 1.45f, fill)
                canvas.drawCircle(12f, 12f, 1.45f, fill)
                canvas.drawCircle(12f, 18.5f, 1.45f, fill)
            }
            Ic.PLUS -> { line(canvas, 12f, 5.5f, 12f, 18.5f); line(canvas, 5.5f, 12f, 18.5f, 12f) }
            Ic.MINUS -> line(canvas, 5.5f, 12f, 18.5f, 12f)
            Ic.CLOSE -> { line(canvas, 6.5f, 6.5f, 17.5f, 17.5f); line(canvas, 17.5f, 6.5f, 6.5f, 17.5f) }
            Ic.SHARE -> {
                poly(canvas, 6f, 11f, 6f, 19.5f, 18f, 19.5f, 18f, 11f)
                line(canvas, 12f, 4.5f, 12f, 15f)
                poly(canvas, 8.5f, 8f, 12f, 4.5f, 15.5f, 8f)
            }
            Ic.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 5.8f, stroke)
                line(canvas, 15f, 15f, 20f, 20f)
            }
            Ic.BOLT -> {
                path.reset()
                path.moveTo(13.2f, 2.8f); path.lineTo(4.8f, 13.6f); path.lineTo(10.8f, 13.6f)
                path.lineTo(10.2f, 21.2f); path.lineTo(19.2f, 10.2f); path.lineTo(13.2f, 10.2f); path.close()
                canvas.drawPath(path, fill)
            }
            Ic.BOOKMARK -> poly(canvas, 7f, 4.2f, 17f, 4.2f, 17f, 20f, 12f, 16.2f, 7f, 20f, close = true)
            Ic.BOOK -> {
                path.reset()
                path.moveTo(12f, 6.2f)
                path.cubicTo(9f, 4.2f, 6f, 4.2f, 3.8f, 5.2f)
                path.lineTo(3.8f, 18.8f)
                path.cubicTo(6f, 18f, 9f, 18f, 12f, 19.8f)
                path.cubicTo(15f, 18f, 18f, 18f, 20.2f, 18.8f)
                path.lineTo(20.2f, 5.2f)
                path.cubicTo(18f, 4.2f, 15f, 4.2f, 12f, 6.2f)
                path.close()
                canvas.drawPath(path, stroke)
                line(canvas, 12f, 6.2f, 12f, 19.6f)
            }
            Ic.LIBRARY -> {
                rect.set(4f, 4.5f, 10f, 19.5f); canvas.drawRoundRect(rect, 1.6f, 1.6f, stroke)
                rect.set(12.5f, 4.5f, 17f, 19.5f); canvas.drawRoundRect(rect, 1.6f, 1.6f, stroke)
                line(canvas, 19.4f, 5.4f, 21.4f, 18.8f)
            }
            Ic.EXTENSIONS -> {
                // puzzle piece: one real extension
                path.reset()
                path.moveTo(9f, 4.5f)
                path.lineTo(15f, 4.5f)
                path.lineTo(15f, 7.5f)
                path.cubicTo(15f, 6.9f, 15.5f, 6.5f, 16.2f, 6.5f)
                path.cubicTo(17.2f, 6.5f, 17.9f, 7.4f, 17.9f, 8.7f)
                path.cubicTo(17.9f, 10f, 17.2f, 10.9f, 16.2f, 10.9f)
                path.cubicTo(15.5f, 10.9f, 15f, 10.5f, 15f, 9.9f)
                path.lineTo(15f, 12.5f)
                path.lineTo(19.5f, 12.5f)
                path.lineTo(19.5f, 19.5f)
                path.lineTo(4.5f, 19.5f)
                path.lineTo(4.5f, 12.5f)
                path.lineTo(8.5f, 12.5f)
                path.lineTo(8.5f, 10.2f)
                path.cubicTo(8.5f, 10.8f, 8f, 11.2f, 7.3f, 11.2f)
                path.cubicTo(6.3f, 11.2f, 5.6f, 10.3f, 5.6f, 9f)
                path.cubicTo(5.6f, 7.7f, 6.3f, 6.8f, 7.3f, 6.8f)
                path.cubicTo(8f, 6.8f, 8.5f, 7.2f, 8.5f, 7.8f)
                path.lineTo(9f, 7.8f)
                path.close()
                canvas.drawPath(path, stroke)
            }
            Ic.CHAT -> {
                rect.set(4f, 5f, 20f, 16f); canvas.drawRoundRect(rect, 4f, 4f, stroke)
                poly(canvas, 9f, 16f, 9f, 20f, 13.5f, 16f)
            }
            Ic.SPLIT -> {
                rect.set(4f, 5f, 20f, 19f); canvas.drawRoundRect(rect, 4f, 4f, stroke)
                line(canvas, 12f, 5f, 12f, 19f)
            }
            Ic.SPARK -> {
                path.reset()
                path.moveTo(12f, 3f); path.lineTo(13.8f, 10.2f); path.lineTo(21f, 12f); path.lineTo(13.8f, 13.8f)
                path.lineTo(12f, 21f); path.lineTo(10.2f, 13.8f); path.lineTo(3f, 12f); path.lineTo(10.2f, 10.2f); path.close()
                canvas.drawPath(path, fill)
            }
            Ic.SCAN -> {
                poly(canvas, 4f, 9f, 4f, 4f, 9f, 4f)
                poly(canvas, 15f, 4f, 20f, 4f, 20f, 9f)
                poly(canvas, 4f, 15f, 4f, 20f, 9f, 20f)
                poly(canvas, 15f, 20f, 20f, 20f, 20f, 15f)
                line(canvas, 3f, 12f, 21f, 12f)
            }
            Ic.LIVE -> {
                canvas.drawCircle(12f, 12f, 2f, fill)
                rect.set(6.5f, 6.5f, 17.5f, 17.5f); canvas.drawArc(rect, -42f, 84f, false, stroke); canvas.drawArc(rect, 138f, 84f, false, stroke)
                rect.set(3.5f, 3.5f, 20.5f, 20.5f); canvas.drawArc(rect, -40f, 80f, false, stroke); canvas.drawArc(rect, 140f, 80f, false, stroke)
            }
            Ic.SAVE, Ic.DOWNLOAD -> {
                line(canvas, 12f, 4f, 12f, 15f)
                poly(canvas, 8f, 11f, 12f, 15f, 16f, 11f)
                poly(canvas, 5f, 16f, 5f, 20f, 19f, 20f, 19f, 16f)
            }
            Ic.UPLOAD -> {
                line(canvas, 12f, 20f, 12f, 9f)
                poly(canvas, 8f, 13f, 12f, 9f, 16f, 13f)
                poly(canvas, 5f, 4f, 5f, 8f, 19f, 8f, 19f, 4f)
            }
            Ic.STOP -> {
                rect.set(7.5f, 7.5f, 16.5f, 16.5f)
                canvas.drawRoundRect(rect, 2.2f, 2.2f, fill)
            }
            Ic.GLOBE -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                line(canvas, 4f, 12f, 20f, 12f)
                rect.set(8f, 4f, 16f, 20f); canvas.drawOval(rect, stroke)
            }
            Ic.CHECK -> poly(canvas, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Ic.LOCK -> {
                rect.set(6f, 11f, 18f, 20f); canvas.drawRoundRect(rect, 2.5f, 2.5f, stroke)
                rect.set(8.5f, 4.5f, 15.5f, 14f); canvas.drawArc(rect, 180f, 180f, false, stroke)
            }
            Ic.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                poly(canvas, 12f, 7.5f, 12f, 12f, 15.3f, 13.8f)
            }
            Ic.SLIDERS -> {
                line(canvas, 4f, 7f, 20f, 7f); line(canvas, 4f, 12f, 20f, 12f); line(canvas, 4f, 17f, 20f, 17f)
                val bg = UiTheme.role(UiTheme.Role.SHEET)
                fill.color = bg
                canvas.drawCircle(9f, 7f, 3.2f, fill); canvas.drawCircle(15f, 12f, 3.2f, fill); canvas.drawCircle(8f, 17f, 3.2f, fill)
                fill.color = color
                canvas.drawCircle(9f, 7f, 3.2f, stroke); canvas.drawCircle(15f, 12f, 3.2f, stroke); canvas.drawCircle(8f, 17f, 3.2f, stroke)
            }
            Ic.MOON -> {
                val outer = Path().apply { addCircle(12f, 12f, 8.2f, Path.Direction.CW) }
                val cut = Path().apply { addCircle(16.2f, 8.6f, 7f, Path.Direction.CW) }
                outer.op(cut, Path.Op.DIFFERENCE)
                canvas.drawPath(outer, fill)
            }
            Ic.SUN -> {
                canvas.drawCircle(12f, 12f, 4f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                    line(canvas, 12f + ca * 6.6f, 12f + sa * 6.6f, 12f + ca * 8.8f, 12f + sa * 8.8f)
                }
            }
            Ic.SHIELD -> {
                path.reset()
                path.moveTo(12f, 3f); path.lineTo(19f, 6f); path.lineTo(19f, 12f)
                path.cubicTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
                path.cubicTo(8f, 19.5f, 5f, 16.5f, 5f, 12f)
                path.lineTo(5f, 6f); path.close()
                canvas.drawPath(path, stroke)
            }
            Ic.TEXT, Ic.FONT_UP, Ic.FONT_DOWN -> {
                val tf = Typeface.create("sans-serif-medium", Typeface.BOLD)
                fill.typeface = tf
                fill.textAlign = Paint.Align.LEFT
                fill.textSize = if (ic == Ic.TEXT) 13.5f else 11f
                canvas.drawText("A", if (ic == Ic.TEXT) 3f else 2f, if (ic == Ic.TEXT) 16.8f else 16f, fill)
                if (ic == Ic.TEXT) {
                    fill.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    fill.textAlign = Paint.Align.RIGHT
                    fill.textSize = 9.5f
                    canvas.drawText("a", 21f, 16.8f, fill)
                } else {
                    fill.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    fill.textSize = 13f
                    if (ic == Ic.FONT_UP) {
                        line(canvas, 17f, 10f, 17f, 20f); line(canvas, 14f, 13f, 17f, 10f); line(canvas, 20f, 13f, 17f, 10f)
                    } else {
                        line(canvas, 17f, 10f, 17f, 20f); line(canvas, 14f, 17f, 17f, 20f); line(canvas, 20f, 17f, 17f, 20f)
                    }
                }
                fill.textAlign = Paint.Align.LEFT
            }
            Ic.TRASH -> {
                line(canvas, 4.5f, 7f, 19.5f, 7f)
                poly(canvas, 9.5f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 7f)
                poly(canvas, 6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f)
                line(canvas, 10.5f, 10.5f, 10.8f, 16.5f)
                line(canvas, 13.5f, 10.5f, 13.2f, 16.5f)
            }
            Ic.EDIT -> {
                poly(canvas, 4.5f, 19.5f, 5.5f, 15f, 15.5f, 5f, 19f, 8.5f, 9f, 18.5f, 4.5f, 19.5f, close = true)
                line(canvas, 13f, 7.5f, 16.5f, 11f)
            }
            Ic.INFO -> {
                canvas.drawCircle(12f, 12f, 8.5f, stroke)
                line(canvas, 12f, 11f, 12f, 16.5f)
                canvas.drawCircle(12f, 7.8f, 1f, fill)
            }
            Ic.ALERT -> {
                poly(canvas, 12f, 4f, 21f, 19.5f, 3f, 19.5f, close = true)
                line(canvas, 12f, 10f, 12f, 14f)
                canvas.drawCircle(12f, 16.6f, 0.9f, fill)
            }
            Ic.FILTER -> {
                line(canvas, 4f, 6.5f, 20f, 6.5f)
                line(canvas, 7f, 12f, 17f, 12f)
                line(canvas, 10f, 17.5f, 14f, 17.5f)
            }
            Ic.LAYERS -> {
                poly(canvas, 12f, 3.5f, 21f, 8f, 12f, 12.5f, 3f, 8f, close = true)
                poly(canvas, 3f, 12f, 12f, 16.5f, 21f, 12f)
                poly(canvas, 3f, 16f, 12f, 20.5f, 21f, 16f)
            }
            Ic.PIN -> {
                canvas.drawCircle(12f, 9f, 5f, stroke)
                line(canvas, 12f, 14f, 12f, 21f)
            }
            Ic.PALETTE -> {
                rect.set(3.5f, 4f, 20.5f, 19f)
                canvas.drawRoundRect(rect, 7.5f, 7.5f, stroke)
                canvas.drawCircle(9f, 9.5f, 1.5f, fill)
                canvas.drawCircle(15f, 9.5f, 1.5f, fill)
                poly(canvas, 8.5f, 15f, 15.5f, 15f)
            }
            Ic.TUNE -> {
                canvas.drawCircle(12f, 12f, 3.2f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0 + 22.5)
                    val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                    line(canvas, 12f + ca * 5.6f, 12f + sa * 5.6f, 12f + ca * 7.6f, 12f + sa * 7.6f)
                }
            }
            Ic.CLEAR -> {
                canvas.drawCircle(12f, 12f, 8.5f, stroke)
                line(canvas, 8.5f, 8.5f, 15.5f, 15.5f)
                line(canvas, 15.5f, 8.5f, 8.5f, 15.5f)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { stroke.colorFilter = cf; fill.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ============================================================================ surfaces

/**
 * Physical-pixel insets for the window. MainActivity is a plain [Activity] (no AppCompat), so the bars
 * draw behind the system status/navigation bars and every top-level view asks this for its padding.
 */
object Insets {
    var top = 0
    var bottom = 0
    var left = 0
    var right = 0

    @Suppress("DEPRECATION")
    fun capture(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = activity.window.decorView.rootWindowInsets ?: return
            val bars = i.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right
        } else {
            val dm = activity.resources.displayMetrics
            val r = android.graphics.Rect()
            activity.window.decorView.getWindowVisibleDisplayFrame(r)
            top = r.top.coerceAtLeast(0)
            bottom = (dm.heightPixels - r.bottom).coerceAtLeast(0)
            left = 0; right = 0
        }
    }
}

object Glass {
    /** Quiet translucent panel with a hairline edge; the base surface of floating bars and cards. */
    fun panel(c: Context, radiusDp: Number, strong: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = c.dpf(radiusDp)
            setColor(if (strong) UiTheme.role(UiTheme.Role.GLASS_TOP) else UiTheme.role(UiTheme.Role.SURFACE))
            setStroke(c.dpi(1), if (strong) UiTheme.role(UiTheme.Role.STROKE) else UiTheme.role(UiTheme.Role.STROKE_SOFT))
        }

    /** Floating bar (top reader pill, dock): subtle vertical tonal shift, hairline edge. */
    fun floating(c: Context, radiusDp: Number): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(UiTheme.role(UiTheme.Role.GLASS_TOP), UiTheme.role(UiTheme.Role.GLASS_BOTTOM))).apply {
            cornerRadius = c.dpf(radiusDp)
            setStroke(c.dpi(1), UiTheme.role(UiTheme.Role.STROKE))
        }

    fun field(c: Context, radiusDp: Number, focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = c.dpf(radiusDp)
            setColor(UiTheme.role(if (focused) UiTheme.Role.FIELD_FOCUSED else UiTheme.Role.FIELD))
            setStroke(c.dpi(if (focused) 1.5f else 1f), if (focused) UiTheme.role(UiTheme.Role.ACCENT) else UiTheme.role(UiTheme.Role.STROKE_SOFT))
        }

    fun accent(c: Context, radiusDp: Number, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT): GradientDrawable =
        GradientDrawable(orientation, intArrayOf(UiTheme.role(UiTheme.Role.ACCENT_START), UiTheme.role(UiTheme.Role.ACCENT_END))).apply {
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
    fun ripple(c: Context, content: Drawable?, radiusDp: Number? = null, rippleColor: Int = 0x26FFFFFF): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            if (radiusDp == null) shape = GradientDrawable.OVAL else cornerRadius = c.dpf(radiusDp)
        }
        val tint = if (UiTheme.dark) rippleColor else ((rippleColor and 0x00FFFFFF) or (0x14000000))
        return RippleDrawable(ColorStateList.valueOf(tint), content, mask)
    }
}

/**
 * One button, five variants. All variants share the same height, radius, type and ripple; only the
 * surface changes, so a row of mixed buttons still lines up.
 */
object Buttons {
    enum class Style { FILLED, TONAL, OUTLINED, TEXT, DANGER }

    fun make(
        c: Context, label: CharSequence, style: Style = Style.TONAL,
        icon: Ic? = null, heightDp: Int = 44, onClick: () -> Unit
    ): android.widget.TextView {
        val radius = Space.radiusM
        return android.widget.TextView(c).apply {
            text = label
            textSize = Type.body
            typeface = Type.medium()
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            minimumHeight = c.dpi(heightDp)
            setPadding(c.dpi(16), 0, c.dpi(16), 0)
            val (fg, bg) = when (style) {
                Style.FILLED -> UiTheme.role(UiTheme.Role.ON_ACCENT) to Glass.accent(c, radius)
                Style.TONAL -> UiTheme.role(UiTheme.Role.TEXT) to Glass.solid(c, UiTheme.role(UiTheme.Role.CONTROL), radius, UiTheme.role(UiTheme.Role.STROKE_SOFT))
                Style.OUTLINED -> UiTheme.role(UiTheme.Role.TEXT) to Glass.solid(c, Color.TRANSPARENT, radius, UiTheme.role(UiTheme.Role.STROKE))
                Style.TEXT -> UiTheme.role(UiTheme.Role.ACCENT) to null
                Style.DANGER -> UiTheme.role(UiTheme.Role.ON_DANGER) to Glass.solid(c, UiTheme.role(UiTheme.Role.DANGER), radius)
            }
            setTextColor(fg)
            background = Glass.ripple(c, bg, radius)
            if (icon != null) {
                val d = IconDrawable(icon, fg)
                d.setBounds(0, 0, c.dpi(18), c.dpi(18))
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = c.dpi(8)
            }
            setOnClickListener { onClick() }
        }
    }
}

/** Square icon button, 44dp minimum touch target with a round ripple. */
internal fun Context.iconButton(ic: Ic, desc: String, sizeDp: Int = 20, boxDp: Int = 44, color: Int = UiTheme.role(UiTheme.Role.ICON), onClick: () -> Unit): ImageView =
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

/** App-wide fade-in for newly shown content. */
fun View.fadeIn(duration: Long = 200L, delay: Long = 0L) {
    alpha = 0f
    animate().alpha(1f).setDuration(duration).setStartDelay(delay).start()
}

// ============================================================================ progress bar

class GlowBar(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.role(UiTheme.Role.STROKE) }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0f
    private var target = 0
    private var phase = 0f
    private var move: ValueAnimator? = null
    private val shimmer = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
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
                duration = 380
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
        fillP.shader = LinearGradient(0f, 0f, maxOf(fw, 1f), 0f,
            UiTheme.role(UiTheme.Role.ACCENT_START), UiTheme.role(UiTheme.Role.ACCENT_END), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, fw, h, r, r, fillP)
        val bw = w * 0.26f
        val x = -bw + (fw + bw) * phase
        shine.shader = LinearGradient(x, 0f, x + bw, 0f, intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        val save = c.save()
        c.clipRect(0f, 0f, fw, h)
        c.drawRoundRect(x, 0f, x + bw, h, r, r, shine)
        c.restoreToCount(save)
    }
}

// ============================================================================ extract button

/**
 * The centre button of the dock: a quiet disc with a bolt, ringed by 24 ticks that show progress / activity.
 * A ring means work: determinate (filled ticks) whenever the app knows the percentage, a single sweeping
 * head otherwise. The small light on the rim is the live activity indicator fed by real task state.
 */
class ExtractButton(ctx: Context) : View(ctx) {
    /** -1 = no ring, 0 = busy (sweeping), 1..100 = determinate */
    var ringProgress: Int = -1
        set(v) { field = v; invalidate() }
    var autoOn: Boolean = true
        set(v) { field = v; invalidate() }
    var modeIndex: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private val dens = ctx.resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = UiTheme.role(UiTheme.Role.STROKE); strokeWidth = 1f * dens
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = IconDrawable(Ic.BOLT, Color.WHITE)
    private var rot = 0f

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private var activityIndicatorColor: Int? = null
    private var activityIndicatorBlinking = false
    private var activityIndicatorAlpha = 1f
    private var activityIndicatorLabel = ""
    private val activityIndicatorPulse = ValueAnimator.ofFloat(1f, 0.25f).apply {
        duration = 620L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            activityIndicatorAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { rot = it.animatedValue as Float; invalidate() }
    }

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
        val s = (68f * dens).toInt()
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.94f else 1f).scaleY(if (pressed) 0.94f else 1f).setDuration(110).start()
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
        val comet = ringProgress == 0 || (ringProgress < 0 && activityIndicatorBlinking)
        if (comet && !spin.isStarted) spin.start()
        if (!comet && spin.isStarted) spin.cancel()

        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val mStart = UiTheme.modeStart(modeIndex)
        val mEnd = UiTheme.modeEnd(modeIndex)

        // soft mode-tinted glow under the disc
        glow.shader = RadialGradient(cx, cy, r, intArrayOf(ModeUi.withAlpha(mStart, 0x30), ModeUi.withAlpha(mStart, 0)), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, glow)
        glow.shader = null

        val bubR = r - 2f * dens
        bubble.color = UiTheme.role(UiTheme.Role.SURFACE_RAISED)
        c.drawCircle(cx, cy, bubR, bubble)
        c.drawCircle(cx, cy, bubR, bubbleStroke)

        val n = 24
        val tOuter = bubR - 3f * dens
        val tInner = tOuter - 4.5f * dens
        tick.strokeWidth = 2f * dens
        val active = ringProgress >= 0 || activityIndicatorBlinking
        val tickColor = if (active) (activityIndicatorColor ?: mStart) else ModeUi.withAlpha(mStart, 0x99)
        val head = ((rot / 360f) * n).toInt() % n
        val litCount = if (ringProgress > 0) (n * ringProgress / 100f).toInt() else -1
        for (i in 0 until n) {
            var a = if (active) 0.18f else 0.26f
            if (comet) {
                val behind = (head - i + n) % n
                if (behind < 5) a = 1f - behind * 0.17f
            } else if (litCount >= 0 && i < litCount) {
                a = 1f
            }
            tick.color = ModeUi.withAlpha(tickColor, (a * 255f).toInt())
            val ang = Math.toRadians(i * 360.0 / n - 90.0)
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            c.drawLine(cx + ca * tInner, cy + sa * tInner, cx + ca * tOuter, cy + sa * tOuter, tick)
        }

        val br = tInner - 4f * dens
        disc.shader = LinearGradient(cx - br, cy - br, cx + br, cy + br, mStart, mEnd, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, br, disc)
        val s = (br * 0.98f).toInt()
        bolt.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
        bolt.draw(c)

        // activity light on the bubble's top-right edge
        val bx = cx + (bubR - 1f * dens) * 0.707f
        val by = cy - (bubR - 1f * dens) * 0.707f
        badge.color = UiTheme.role(UiTheme.Role.BG)
        c.drawCircle(bx, by, 6f * dens, badge)
        badge.color = activityIndicatorColor ?: if (autoOn) UiTheme.role(UiTheme.Role.OK) else UiTheme.role(UiTheme.Role.FAINT)
        badge.alpha = (255f * if (activityIndicatorBlinking) activityIndicatorAlpha else 1f).toInt().coerceIn(0, 255)
        c.drawCircle(bx, by, 4f * dens, badge)
        badge.alpha = 255
    }
}

// ============================================================================ snackbar / notifications

/**
 * A single in-app notification strip. Lives INSIDE the bottom dock row (replacing the dock's controls while
 * visible), so it never covers the WebView, the translated text or the reader controls. Distinct states and
 * matching icons make success / failure / progress unmistakable. Multiline messages wrap to at most three
 * lines; long ones get a real dismiss control instead of being clipped. A tap or ✕ dismisses it.
 */
class Snackbar(ctx: Context) : android.widget.FrameLayout(ctx) {
    enum class Kind { INFO, SUCCESS, ERROR, WARN, PROGRESS }

    private val accentBar = View(ctx)
    private val icon = ImageView(ctx)
    private val label = android.widget.TextView(ctx)
    private val action = android.widget.TextView(ctx)
    private val close = ImageView(ctx)
    private var hideAt: Runnable? = null
    private var onAction: (() -> Unit)? = null

    var shown: Boolean = false
        private set

    init {
        visibility = GONE
        minimumHeight = ctx.dpi(Touch.minCompact)
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(14), ctx.dpi(8), ctx.dpi(4), ctx.dpi(8))
        }
        icon.setPadding(0, ctx.dpi(2), 0, ctx.dpi(2))
        label.apply {
            textSize = Type.label
            typeface = Type.medium()
            setTextColor(UiTheme.role(UiTheme.Role.TEXT))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(ctx.dpf(2), 1f)
        }
        action.apply {
            textSize = Type.label
            typeface = Type.bold()
            setTextColor(UiTheme.role(UiTheme.Role.ACCENT))
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(10), ctx.dpi(8), ctx.dpi(11), ctx.dpi(8))
            minimumHeight = ctx.dpi(Touch.minCompact)
            isClickable = true
            visibility = GONE
        }
        close.apply {
            setImageDrawable(IconDrawable(Ic.CLOSE, UiTheme.role(UiTheme.Role.DIM), 1.6f))
            val p = ctx.dpi(12)
            setPadding(p, p, p, p)
            background = Glass.ripple(ctx, null)
            contentDescription = "Dismiss"
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            setOnClickListener { dismiss() }
        }
        row.addView(icon, android.widget.LinearLayout.LayoutParams(ctx.dpi(18), ctx.dpi(18)).also { it.marginEnd = ctx.dpi(11) })
        row.addView(label, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(action, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(close, android.widget.LinearLayout.LayoutParams(ctx.dpi(Touch.min), ctx.dpi(Touch.min)))
        addView(row, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        accentBar.setBackground(Glass.solid(ctx, UiTheme.role(UiTheme.Role.ACCENT), 2))
        addView(accentBar, android.widget.FrameLayout.LayoutParams(ctx.dpi(3), ctx.dpi(22), Gravity.START or Gravity.CENTER_VERTICAL).also { it.marginStart = ctx.dpi(5) })
        background = Glass.panel(ctx, Space.radiusM, strong = true)
        elevation = ctx.dpf(10)
        isClickable = true
        setOnClickListener { dismiss() }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        label.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun show(message: String, kind: Kind = Kind.INFO, actionLabel: String? = null, onAction: (() -> Unit)? = null, autoHideMs: Long = 0L) {
        hideAt?.let { (parent as? View)?.removeCallbacks(it) }
        visibility = VISIBLE
        this.onAction = onAction
        shown = true
        label.text = message
        close.contentDescription = if (actionLabel != null) "Dismiss notification" else "Dismiss"
        val (color, glyph) = when (kind) {
            Kind.SUCCESS -> UiTheme.role(UiTheme.Role.OK) to Ic.CHECK
            Kind.ERROR -> UiTheme.role(UiTheme.Role.DANGER) to Ic.ALERT
            Kind.WARN -> UiTheme.role(UiTheme.Role.WARN) to Ic.ALERT
            Kind.PROGRESS -> UiTheme.role(UiTheme.Role.ACCENT) to Ic.REFRESH_DOT
            Kind.INFO -> UiTheme.role(UiTheme.Role.ACCENT) to Ic.INFO
        }
        accentBar.setBackground(Glass.solid(context, color, 2))
        icon.setImageDrawable(IconDrawable(glyph, color))
        if (actionLabel != null && onAction != null) {
            action.text = actionLabel
            action.visibility = View.VISIBLE
            action.contentDescription = actionLabel
            action.setOnClickListener { this.onAction?.invoke(); dismiss() }
        } else action.visibility = View.GONE
        // Multi-line messages keep the explicit dismiss control even when the strip auto-hides, so an
        // edge-of-screen message can always be cleared by hand.
        val long = message.length > 90
        close.visibility = if (autoHideMs > 0L && !long) View.GONE else View.VISIBLE
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        alpha = 0f
        translationY = context.dpf(6)
        animate().alpha(1f).translationY(0f).setDuration(170).start()
        if (autoHideMs > 0L) {
            val r = Runnable { dismiss() }
            hideAt = r
            postDelayed(r, autoHideMs)
        }
        // Announce the state, not just the text, for screen-reader users.
        contentDescription = when (kind) {
            Kind.SUCCESS -> "Success. $message"
            Kind.ERROR -> "Error. $message"
            Kind.WARN -> "Warning. $message"
            Kind.PROGRESS -> "In progress. $message"
            Kind.INFO -> message
        }
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        animate().alpha(0f).translationY(context.dpf(6)).setDuration(140).withEndAction {
            visibility = GONE
            setLayerType(View.LAYER_TYPE_NONE, null)
        }.start()
    }

    companion object {
        /** @param durationMs how long to stay; 0 = sticky until dismissed. */
        fun show(activity: Activity, message: String, kind: Kind = Kind.INFO, actionLabel: String? = null, onAction: (() -> Unit)? = null, durationMs: Long = 0L) {
            activity.runOnUiThread {
                val host = SnackbarHost.of(activity) ?: run {
                    android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                host.show(message, kind, actionLabel, onAction, durationMs)
            }
        }
    }
}

/** How MainActivity gives the shared snackbar its place in the dock row. */
interface SnackbarHost {
    fun show(message: String, kind: Snackbar.Kind, actionLabel: String?, onAction: (() -> Unit)?, autoHideMs: Long)
    val isSnackbarVisible: Boolean

    companion object {
        private val map = HashMap<Int, SnackbarHost>()
        fun register(activity: Activity, host: SnackbarHost) { map[activity.hashCode()] = host }
        fun unregister(activity: Activity) { map.remove(activity.hashCode()) }
        fun of(activity: Activity): SnackbarHost? = map[activity.hashCode()]
    }
}

// ============================================================================ sheets

object Sheets {
    /** Small informational dialog used for non-blocking onboarding and scan tips. */
    @Suppress("UNUSED_PARAMETER")
    fun info(activity: Activity, title: String, message: CharSequence, icon: Ic? = null): Dialog =
        android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()
            .also { it.show() }

    /** A borderless dialog hosting [content]; bottom-anchored, translucent, blurs the app behind it on Android 12+. */
    fun create(activity: Activity, content: View, fullScreen: Boolean = false, bottom: Boolean = true, dim: Float = 0.6f): Dialog {
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
            w.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    if (activity.windowManager.isCrossWindowBlurEnabled) {
                        w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        val lp = w.attributes
                        lp.blurBehindRadius = 30
                        w.attributes = lp
                    }
                } catch (_: Throwable) {}
            }
            if (!fullScreen) {
                // Keep the sheet clear of the navigation bar (edge-to-edge window, transparent bars).
                (content as? ViewGroup)?.let { vg ->
                    if (vg.paddingBottom < Insets.bottom) vg.setPadding(vg.paddingLeft, vg.paddingTop, vg.paddingRight, Insets.bottom + activity.dpi(8))
                }
            }
        }
        d.setOnShowListener { slideUp(content) }
        return d
    }

    fun slideUp(v: View) {
        v.alpha = 0f
        v.post {
            v.translationY = v.height * 0.18f
            v.animate().translationY(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Rounded top-corner surface for sheets. */
    fun sheetBackground(c: Context, topRadiusDp: Number = 26): GradientDrawable {
        val r = c.dpf(topRadiusDp)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(UiTheme.role(UiTheme.Role.SHEET), UiTheme.shade(UiTheme.role(UiTheme.Role.SHEET), if (UiTheme.dark) 0.12f else 0.03f))
        ).apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(c.dpi(1), UiTheme.role(UiTheme.Role.STROKE))
        }
    }

    /** The grab handle at the top of every sheet. */
    fun handle(c: Context): View = View(c).apply {
        background = Glass.solid(c, UiTheme.role(UiTheme.Role.FAINT), 2)
        layoutParams = android.widget.LinearLayout.LayoutParams(c.dpi(40), c.dpi(4)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.bottomMargin = c.dpi(12)
        }
    }

    /** Sheet section title: overline, uppercase, tracked. */
    fun sectionLabel(c: Context, t: String): android.widget.TextView = android.widget.TextView(c).apply {
        text = t
        textSize = Type.overline
        typeface = Type.bold()
        letterSpacing = 0.12f
        setTextColor(UiTheme.role(UiTheme.Role.FAINT))
        setPadding(c.dpi(6), c.dpi(16), c.dpi(6), c.dpi(7))
    }

    /**
     * In-app confirmation built on the same design system instead of the platform AlertDialog.
     * [danger] tints the confirm button for destructive actions.
     */
    fun confirm(
        activity: Activity,
        title: String,
        message: CharSequence,
        confirm: CharSequence = "OK",
        cancel: CharSequence? = "Cancel",
        danger: Boolean = false,
        onConfirm: () -> Unit
    ): Dialog {
        val c = activity
        val col = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(c.dpi(22), c.dpi(22), c.dpi(22), c.dpi(18))
            background = Glass.panel(c, Space.radiusL, strong = true)
        }
        col.addView(android.widget.TextView(c).apply {
            text = title
            textSize = Type.heading
            typeface = Type.bold()
            setTextColor(UiTheme.role(UiTheme.Role.TEXT))
        })
        if (message.isNotEmpty()) col.addView(android.widget.TextView(c).apply {
            text = message
            textSize = Type.body
            setTextColor(UiTheme.role(UiTheme.Role.DIM))
            setLineSpacing(c.dpf(3), 1f)
            setPadding(0, c.dpi(8), 0, 0)
            setTextIsSelectable(true)
        })
        val dlg = Sheets.create(c, col, bottom = false, dim = 0.55f)
        val row = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, c.dpi(20), 0, 0)
        }
        fun btn(text: CharSequence, style: Buttons.Style, onClick: () -> Unit) =
            Buttons.make(c, text, style, heightDp = 44) { onClick() }.also {
                it.minimumWidth = c.dpi(96)
            }
        if (cancel != null) row.addView(btn(cancel, Buttons.Style.TEXT) { dlg.dismiss() },
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = c.dpi(8) })
        row.addView(btn(confirm, if (danger) Buttons.Style.DANGER else Buttons.Style.FILLED) { dlg.dismiss(); onConfirm() },
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(row, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        val maxW = (c.resources.displayMetrics.widthPixels * 0.9f).toInt()
        dlg.window?.setLayout(maxW.coerceAtMost(c.dpi(420)), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
        return dlg
    }
}

/** ScrollView that never grows past [maxHeightPx] (sheets keep a gap above them). */
class MaxHeightScroll(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}
