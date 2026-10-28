package com.ashraf.novelstudio

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Toon Sensei design tokens (replaces "Aurora Glass" for every NEW view).
 *
 * Rules that this file enforces for the new UI:
 *  - no gradient, blur, glow or shadow: tone + 1dp hairlines only
 *  - radius scale is fixed: 8 / 14 / 16..18 / 20 / 24 / 999(pill)
 *  - only 400 and 500 weights (600 for the wordmark and the tab-count digit)
 *  - touch targets at least 44dp
 *
 * Old screens still use UiTheme / Glass / Sheets until they are migrated; keeping this file
 * separate is what lets the new UI be built without touching them.
 */
object Tokens {

    // ------------------------------------------------------------------ accent swatches
    /** Six user-selectable accents. Only the accent changes with the swatch. */
    val ACCENTS = intArrayOf(
        0xFF9C3D2E.toInt(), // terracotta (default)
        0xFF2F6B5E.toInt(), // deep green
        0xFF3D5A80.toInt(), // slate blue
        0xFF8A5A12.toInt(), // ochre
        0xFF6B4E8C.toInt(), // plum
        0xFF1F1D1A.toInt()  // ink
    )
    const val DEFAULT_ACCENT_INDEX = 0

    // ------------------------------------------------------------------ palette
    private const val PAPER_BG = 0xFFF4F1EA.toInt()
    private const val PAPER_CARD = 0xFFFBFAF6.toInt()
    private const val PAPER_INK = 0xFF1F1D1A.toInt()
    private const val PAPER_DIM = 0xFF6B665E.toInt()
    private const val PAPER_LINE = 0xFFE3DED3.toInt()
    private const val PAPER_CHIP = 0xFFE9E4D8.toInt()
    private const val PAPER_ON_ACCENT = 0xFFFFFFFF.toInt()

    private const val CHARCOAL_BG = 0xFF1C1B19.toInt()
    private const val CHARCOAL_CARD = 0xFF262522.toInt()
    private const val CHARCOAL_INK = 0xFFE8E4DA.toInt()
    private const val CHARCOAL_DIM = 0xFF9A958A.toInt()
    private const val CHARCOAL_LINE = 0xFF363430.toInt()
    private const val CHARCOAL_CHIP = 0xFF302E2A.toInt()
    private const val CHARCOAL_ON_ACCENT = 0xFF1C1B19.toInt()

    /** Dark theme accents are the same hues, lightened a little so they read on charcoal. */
    private val DARK_ACCENTS = intArrayOf(
        0xFFC9AE7C.toInt(), // warm sand (default dark)
        0xFF7FB3A3.toInt(),
        0xFF8FAACB.toInt(),
        0xFFC7A05A.toInt(),
        0xFFA48BC2.toInt(),
        0xFFE8E4DA.toInt()
    )

    // status colours (theme-independent semantics, tuned for both themes)
    private const val OK_LIGHT = 0xFF3F6B4B.toInt()
    private const val OK_TEXT_LIGHT = 0xFF2F5A3B.toInt()
    private const val OK_TINT_LIGHT = 0xFFE3EDE5.toInt()
    private const val WARN_TEXT_LIGHT = 0xFF5E3F0B.toInt()
    private const val WARN_TINT_LIGHT = 0xFFF5ECD8.toInt()
    private const val WARN_BORDER_LIGHT = 0xFFE3CFA3.toInt()
    private const val WARN_RING = 0xFFB7791F.toInt()
    private const val OK_DARK = 0xFF7FA98A.toInt()
    private const val OK_TEXT_DARK = 0xFFB9D6C1.toInt()
    private const val OK_TINT_DARK = 0xFF24312A.toInt()
    private const val WARN_TEXT_DARK = 0xFFF0D9A8.toInt()
    private const val WARN_TINT_DARK = 0xFF332B1C.toInt()
    private const val WARN_BORDER_DARK = 0xFF5A4A2A.toInt()

    /** True when the resolved theme is Charcoal. */
    fun isDark(c: Context): Boolean {
        val mode = Prefs.get(c, "uiTheme", "paper")
        if (mode == "charcoal") return true
        if (mode == "paper") return false
        // "system" (or anything unknown): follow the device.
        return (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** 0..5, clamped. Reads the user's swatch. */
    fun accentIndex(c: Context): Int =
        Prefs.get(c, "uiAccent", DEFAULT_ACCENT_INDEX.toString()).toIntOrNull()
            ?.coerceIn(0, ACCENTS.size - 1) ?: DEFAULT_ACCENT_INDEX

    fun setAccentIndex(c: Context, idx: Int) =
        Prefs.put(c, "uiAccent", idx.coerceIn(0, ACCENTS.size - 1).toString())

    fun setTheme(c: Context, mode: String) = Prefs.put(c, "uiTheme", mode)

    // ------------------------------------------------------------------ resolved palette
    /** A resolved colour set for one screen build. Build once per screen, not per frame. */
    class P(c: Context) {
        val dark: Boolean = isDark(c)
        val accentIdx: Int = accentIndex(c)
        val bg: Int = if (dark) CHARCOAL_BG else PAPER_BG
        val card: Int = if (dark) CHARCOAL_CARD else PAPER_CARD
        val ink: Int = if (dark) CHARCOAL_INK else PAPER_INK
        val dim: Int = if (dark) CHARCOAL_DIM else PAPER_DIM
        val line: Int = if (dark) CHARCOAL_LINE else PAPER_LINE
        val chipBg: Int = if (dark) CHARCOAL_CHIP else PAPER_CHIP
        val accent: Int = if (dark) DARK_ACCENTS[accentIdx] else ACCENTS[accentIdx]
        val onAccent: Int = if (dark) CHARCOAL_ON_ACCENT else PAPER_ON_ACCENT
        val accentTint: Int = blend(card, accent, if (dark) 0.22f else 0.14f)
        /** Text drawn on an accent-tint surface (Stop button, progress chip). */
        val onAccentTint: Int = if (dark) accent else darken(accent, 0.55f)
        val scrim: Int = if (dark) 0x59000000 else 0x571F1D1A // ~34%

        val ok: Int = if (dark) OK_DARK else OK_LIGHT
        val okText: Int = if (dark) OK_TEXT_DARK else OK_TEXT_LIGHT
        val okTint: Int = if (dark) OK_TINT_DARK else OK_TINT_LIGHT
        val warnText: Int = if (dark) WARN_TEXT_DARK else WARN_TEXT_LIGHT
        val warnTint: Int = if (dark) WARN_TINT_DARK else WARN_TINT_LIGHT
        val warnBorder: Int = if (dark) WARN_BORDER_DARK else WARN_BORDER_LIGHT
        val warnRing: Int = WARN_RING

        /** Slightly stronger hairline for selected outlines. */
        val lineStrong: Int = if (dark) 0xFF4A4742.toInt() else 0xFFCFC8BA.toInt()
    }

    fun palette(c: Context) = P(c)

    // ------------------------------------------------------------------ type
    const val SP_MICRO = 11f
    const val SP_TINY = 12f
    const val SP_SECONDARY = 13f
    const val SP_BODY_SMALL = 14f
    const val SP_BODY = 15f
    const val SP_TITLE = 17f
    const val SP_SCREEN = 24f
    const val SP_WORDMARK_SMALL = 26f
    const val SP_WORDMARK = 30f

    /** Inter 400 (body). Falls back to the system sans-serif if the asset is missing. */
    fun regular(c: Context): Typeface = font(c, "inter_regular", false)

    /** Inter 500 (titles, buttons, emphasis). */
    fun medium(c: Context): Typeface = font(c, "inter_medium", true)

    /** Shippori Mincho 600: wordmark only. */
    fun wordmark(c: Context): Typeface = font(c, "shippori_wordmark", true)

    /** Noto Serif Bengali: app-owned Bengali text (glossary, future preview). */
    fun bengali(c: Context): Typeface = font(c, "noto_serif_bengali", false)

    fun bengaliMedium(c: Context): Typeface = font(c, "noto_serif_bengali_medium", true)

    private val typeCache = HashMap<String, Typeface>()

    private fun font(c: Context, name: String, boldFallback: Boolean): Typeface {
        typeCache[name]?.let { return it }
        val t = try {
            Typeface.createFromAsset(c.assets, "fonts/$name.ttf")
        } catch (_: Exception) {
            // Missing asset must never crash a screen: fall back to a system face of similar weight.
            Typeface.create(
                Typeface.create("sans-serif", Typeface.NORMAL),
                if (boldFallback) Typeface.BOLD else Typeface.NORMAL
            )
        }
        typeCache[name] = t
        return t
    }

    /** Drop cached faces after a config change (they are cheap to rebuild). */
    fun clearTypeCache() = typeCache.clear()

    // ------------------------------------------------------------------ shape helpers
    const val R_TINY = 8
    const val R_FIELD = 14
    const val R_ROW = 16
    const val R_CARD = 18
    const val R_BIG_CARD = 20
    const val R_SHEET = 24
    const val R_PILL = 999

    private fun px(c: Context, dp: Int) = (dp * c.resources.displayMetrics.density + 0.5f).toInt()

    /** Flat filled shape with an optional 1dp hairline. */
    fun shape(c: Context, fill: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = if (radiusDp >= R_PILL) px(c, 999) / 2f else px(c, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(px(c, strokeDp).coerceAtLeast(1), strokeColor)
        }

    /** Pill shape (fully rounded). */
    fun pill(c: Context, fill: Int, strokeDp: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = px(c, 999).toFloat()
            if (strokeDp > 0) setStroke(px(c, strokeDp).coerceAtLeast(1), strokeColor)
        }

    /** Shape with different top/bottom radii (bottom sheets). */
    fun sheetShape(c: Context, fill: Int, topDp: Int = R_SHEET): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadii = floatArrayOf(
                px(c, topDp).toFloat(), px(c, topDp).toFloat(),
                px(c, topDp).toFloat(), px(c, topDp).toFloat(),
                0f, 0f, 0f, 0f
            )
        }

    // ------------------------------------------------------------------ motion
    /** 0 when the system "remove animations" (animator duration scale 0) is on. */
    fun motionMs(c: Context, ms: Long): Long {
        val scale = try {
            android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        } catch (_: Exception) { 1f }
        return if (scale <= 0f) 0L else ms
    }

    /** Fade a view in (user-action motion only: 150-200 ms, ease-out, no overshoot). */
    fun fadeIn(v: View, c: Context, ms: Long = 170L, fromTranslationDp: Int = 0) {
        v.animate().cancel()
        val dur = motionMs(c, ms)
        if (fromTranslationDp == 0 || dur == 0L) {
            v.alpha = 1f
            v.translationY = 0f
            return
        }
        v.alpha = 0f
        v.translationY = fromTranslationDp * c.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(dur)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** Fade a view out and optionally make it GONE at the end. */
    fun fadeOut(v: View, c: Context, ms: Long = 150L, gone: Boolean = true) {
        val dur = motionMs(c, ms)
        v.animate().cancel()
        if (dur == 0L) {
            if (gone) v.visibility = View.GONE
            return
        }
        v.animate().alpha(0f).setDuration(dur)
            .withEndAction { if (gone) v.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------ colour maths
    fun blend(base: Int, over: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val r = (Color.red(base) * (1 - a) + Color.red(over) * a).toInt()
        val g = (Color.green(base) * (1 - a) + Color.green(over) * a).toInt()
        val b = (Color.blue(base) * (1 - a) + Color.blue(over) * a).toInt()
        return Color.rgb(r, g, b)
    }

    fun darken(color: Int, factor: Float): Int =
        Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )

    /** WCAG relative luminance, 0..1. */
    fun luminance(color: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(color)) + 0.7152 * ch(Color.green(color)) + 0.0722 * ch(Color.blue(color))
    }

    /** WCAG contrast ratio between two opaque colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
