package com.ashraf.novelstudio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 56dp browser bar: Back, a 40dp URL pill (reload icon that becomes a stop "x" while loading),
 * a tab counter, and More. Emits callbacks only; navigation stays in MainActivity.
 *
 * The bar never hides (owner's decision), so no scroll logic lives here.
 */
class TopBar(context: Context) : LinearLayout(context) {

    var onBack: () -> Unit = {}
    var onUrlTap: () -> Unit = {}
    var onReloadOrStop: () -> Unit = {}
    var onTabs: () -> Unit = {}
    var onMore: () -> Unit = {}

    private val p = Tokens.palette(context)

    private val urlText: TextView
    private val reloadIcon: ImageView
    private val tabCount: TextView
    private var lastUrlShown = ""
    private var loading = false

    /** 2dp progress line under the bar (accent on the hairline colour). */
    val progress: LoadLine

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpx(6), 0, dpx(6), 0)
        setBackgroundColor(p.bg)
        contentDescription = "Browser bar"

        val back = action(Ic.BACK, "Back") { onBack() }

        urlText = TextView(context).apply {
            text = ""
            setTextColor(p.dim)
            textSize = Tokens.SP_BODY_SMALL
            typeface = Tokens.regular(context)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onUrlTap() }
        }

        reloadIcon = ImageView(context).apply {
            setImageDrawable(IconDrawable(Ic.RELOAD, p.ink, 1.8f))
            setPadding(dpx(10), dpx(10), dpx(10), dpx(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = Strings.RELOAD
            setOnClickListener { onReloadOrStop() }
        }

        val pill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Tokens.pill(context, p.chipBg)
            setPadding(dpx(16), 0, dpx(4), 0)
            addView(urlText, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(reloadIcon, LinearLayout.LayoutParams(dpx(40), dpx(40)))
        }

        tabCount = TextView(context).apply {
            text = "1"
            setTextColor(p.ink)
            textSize = Tokens.SP_TINY
            typeface = android.graphics.Typeface.create(Tokens.medium(context), android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = Tokens.shape(context, 0x00000000, Tokens.R_TINY, 2, p.ink)
            contentDescription = Strings.OPEN_TABS
            setOnClickListener { onTabs() }
        }

        val more = action(Ic.DOTS, Strings.MORE) { onMore() }

        addView(back, LinearLayout.LayoutParams(dpx(44), dpx(44)))
        addView(pill, LinearLayout.LayoutParams(0, dpx(40), 1f).also { it.marginStart = dpx(2); it.marginEnd = dpx(4) })
        addView(tabCount, LinearLayout.LayoutParams(dpx(26), dpx(26)).also { it.marginEnd = dpx(6) })
        addView(more, LinearLayout.LayoutParams(dpx(44), dpx(44)))

        progress = LoadLine(context)
    }

    /** Root for MainActivity: the bar plus its hairline and the 2dp progress line. */
    fun rootWithProgress(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(this@TopBar, LinearLayout.LayoutParams(MATCH, dpx(56)))
        addView(View(context).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(MATCH, 1))
        addView(progress, LinearLayout.LayoutParams(MATCH, dpx(2)))
    }

    fun setUrl(url: String) {
        val shown = shortUrl(url)
        if (shown == lastUrlShown) return
        lastUrlShown = shown
        urlText.text = shown
        urlText.contentDescription = "Address: $shown"
    }

    fun setLoading(isLoading: Boolean) {
        if (loading == isLoading) return
        loading = isLoading
        reloadIcon.setImageDrawable(IconDrawable(if (isLoading) Ic.CLOSE else Ic.RELOAD, p.ink, 1.8f))
        reloadIcon.contentDescription = if (isLoading) Strings.STOP_LOADING else Strings.RELOAD
        progress.setLoading(isLoading)
    }

    fun setTabCount(n: Int) {
        val s = n.toString()
        if (tabCount.text.toString() != s) tabCount.text = s
        tabCount.contentDescription = "Tabs, $n"
    }

    /** Flash the counter accent-filled for ~1.5 s when a tab opens in the background (no message). */
    fun flashTabCount(n: Int) {
        setTabCount(n)
        tabCount.setTextColor(p.onAccent)
        tabCount.background = Tokens.shape(context, p.accent, Tokens.R_TINY, 2, p.accent)
        tabCount.postDelayed({
            tabCount.setTextColor(p.ink)
            tabCount.background = Tokens.shape(context, 0x00000000, Tokens.R_TINY, 2, p.ink)
        }, 1500L)
    }

    private fun shortUrl(url: String): String {
        if (url.isBlank()) return ""
        var u = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        u = u.removeSuffix("/")
        return if (u.length > 48) u.take(45) + "…" else u
    }

    private fun action(ic: Ic, desc: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageDrawable(IconDrawable(ic, p.ink, 1.8f))
            setPadding(dpx(11), dpx(11), dpx(11), dpx(11))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    private fun dpx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
    private val MATCH = LayoutParams.MATCH_PARENT

    /**
     * A thin page-load line. While loading it moves forward slowly toward 90% (browser-like,
     * not a fake chatbot progress) and snaps to 100% on finish. Values change at most every 250 ms.
     */
    inner class LoadLine(c: Context) : View(c) {
        private var frac = 0f
        private var running = false
        private val p = Tokens.palette(c)
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                frac = (frac + 0.06f).coerceAtMost(0.9f)
                invalidate()
                postDelayed(this, 250L)
            }
        }

        init {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }

        fun setLoading(on: Boolean) {
            if (running == on) return
            running = on
            removeCallbacks(tick)
            if (on) {
                frac = 0.08f
                visibility = View.VISIBLE
                postDelayed(tick, 250L)
            } else {
                frac = 1f
                invalidate()
                postDelayed({ visibility = View.INVISIBLE; frac = 0f; invalidate() }, 220L)
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (frac <= 0f) return
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = p.accent
            canvas.drawRect(0f, 0f, width * frac, height.toFloat(), paint)
        }
    }
}
