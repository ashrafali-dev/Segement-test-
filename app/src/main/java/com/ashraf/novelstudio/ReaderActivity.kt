package com.ashraf.novelstudio

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Offline novel reader ("Calm"): a quiet header with the book and chapter, the translated text at a
 * comfortable measure, and one floating capsule for navigation and reading settings. Nothing else
 * competes with the text; the capsule hides while the reader is scrolling (System "Auto-hide" style)
 * and comes back on the next tap.
 *
 * The three appearances (Night / Sepia / Paper) are picked from the capsule's settings sheet; the
 * readability rules of each appearance are enforced with real contrast, not tinted text.
 */
class ReaderActivity : Activity() {
    private var list: List<Tr> = emptyList()
    private var idx = 0
    private var fs = 18f
    private var theme = 0
    private lateinit var root: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var backBtn: ImageView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var countTv: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var capsule: LinearLayout
    private lateinit var readBar: ProgressBar
    private lateinit var topBar: LinearLayout
    private lateinit var navPrev: TextView
    private lateinit var navNext: TextView
    private lateinit var settingsBtn: TextView
    private var chromeHidden = false
    private var novel = ""

    // Appearance per index: 0 Night, 1 Sepia, 2 Paper.
    private val bg = intArrayOf(0xFF0B0B0E.toInt(), 0xFFF4ECD8.toInt(), 0xFFFBFBFD.toInt())
    private val fg = intArrayOf(0xFFDCDDE2.toInt(), 0xFF2E2518.toInt(), 0xFF19191E.toInt())
    private val dim = intArrayOf(0xFF9A9AA3.toInt(), 0xFF6B5D45.toInt(), 0xFF5C5C66.toInt())
    private val rule = intArrayOf(0x14FFFFFF, 0x1A3B2F1E, 0x14000000)
    private val headerBg = intArrayOf(0xFF121216.toInt(), 0xFFEFE5CE.toInt(), 0xFFF3F3F6.toInt())
    private val capsuleBg = intArrayOf(0xF218181D.toInt(), 0xF2FFF9EC.toInt(), 0xF2FFFFFF.toInt())
    private val accent = UiTheme.accent

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        novel = intent.getStringExtra("novel") ?: ""
        val id = intent.getLongExtra("id", -1L)
        list = Store.chapters(this, novel)
        if (list.isEmpty()) { finish(); return }
        idx = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        fs = Prefs.get(this, "fs", "18").toFloatOrNull() ?: 18f
        theme = Prefs.get(this, "rtheme", "0").toIntOrNull()?.coerceIn(0, 2) ?: 0

        // ---- header: back, book + chapter, position chip
        backBtn = ImageView(this).apply {
            setImageDrawable(IconDrawable(Ic.BACK, fg[theme], 1.9f))
            val p = dp(11)
            setPadding(p, p, p, p)
            background = Glass.ripple(this@ReaderActivity, null)
            contentDescription = "Back to library"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { finish() }
        }
        titleTv = TextView(this).apply {
            textSize = Type.label
            typeface = Type.bold()
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subTv = TextView(this).apply {
            textSize = Type.overline
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        countTv = TextView(this).apply {
            textSize = Type.caption
            typeface = Type.medium()
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@ReaderActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleTv)
                addView(subTv)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(2) })
            addView(countTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(8); it.marginEnd = dp(8)
            })
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        body = TextView(this).apply {
            setLineSpacing(dp(4).toFloat(), 1.42f)
            setTextIsSelectable(true)
            // A readable measure on tablets: never wider than ~68 characters.
            maxWidth = dp(680)
        }
        Prefs.fontFile(this).takeIf { it.isNotEmpty() }?.let {
            try {
                body.typeface = if (it.startsWith("dl_")) Typeface.createFromFile(LangFonts.file(this, it.removePrefix("dl_")))
                else Typeface.createFromAsset(assets, "fonts/$it.ttf")
            } catch (e: Exception) { }
        }

        readBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(rule[theme])
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP)
        }

        val bodyWrap = FrameLayout(this).apply {
            addView(body, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(bodyWrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- floating capsule: prev, reading settings, next
        navPrev = TextView(this).apply {
            text = "◀"
            textSize = 15f
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            contentDescription = "Previous chapter"
            isClickable = true
            setOnClickListener { go(-1) }
        }
        navNext = TextView(this).apply {
            text = "▶"
            textSize = 15f
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            contentDescription = "Next chapter"
            isClickable = true
            setOnClickListener { go(1) }
        }
        settingsBtn = TextView(this).apply {
            text = "Aa"
            textSize = Type.body
            typeface = Type.bold()
            gravity = Gravity.CENTER
            minimumWidth = dp(52)
            minimumHeight = dp(48)
            contentDescription = "Reading settings"
            isClickable = true
            setOnClickListener { showSettings() }
        }
        capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dp(12).toFloat()
            setPadding(dp(6), 0, dp(6), 0)
            addView(navPrev)
            addView(settingsBtn, LinearLayout.LayoutParams(dp(52), dp(48)).also { it.marginStart = dp(4); it.marginEnd = dp(4) })
            addView(navNext)
        }

        // ---- edge-to-edge
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false) else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        @Suppress("DEPRECATION") window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION") window.navigationBarColor = android.graphics.Color.TRANSPARENT
        Insets.capture(this)
        topBar.setPadding(dp(6), Insets.top + dp(2), dp(10), dp(4))
        scroll.setPadding(dp(22), dp(18), dp(22), Insets.bottom + dp(104))

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root = FrameLayout(this).apply {
            addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(readBar)
            addView(capsule, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).also { it.setMargins(0, 0, 0, Insets.bottom + dp(16)) })
        }
        setContentView(root)
        applyTheme()
        render()

        // Immersive reading: the capsule steps out of the way while the text scrolls.
        scroll.viewTreeObserver.addOnScrollChangedListener { hideChrome() }
        scroll.setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) revealChrome()
            false
        }
    }

    private fun hideChrome() {
        if (chromeHidden) return
        chromeHidden = true
        capsule.animate().translationY(dp(72).toFloat()).alpha(0f).setDuration(200).start()
    }

    private fun revealChrome() {
        if (!chromeHidden) return
        chromeHidden = false
        capsule.animate().translationY(0f).alpha(1f).setDuration(180).start()
    }

    private fun setFont(v: Float) {
        fs = v.coerceIn(12f, 34f)
        Prefs.put(this, "fs", fs.toString())
        body.textSize = fs
    }

    private fun applyTheme() {
        root.setBackgroundColor(bg[theme])
        body.setTextColor(fg[theme])
        body.textSize = fs
        titleTv.setTextColor(fg[theme])
        subTv.setTextColor(dim[theme])
        header.setBackgroundColor(headerBg[theme])
        topBar.setBackgroundColor(headerBg[theme])
        backBtn.setImageDrawable(IconDrawable(Ic.BACK, fg[theme], 1.9f))
        // chip: always readable — accent on a tinted surface, never accent text on accent
        countTv.setTextColor(if (theme == 0) accent else fg[theme])
        countTv.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (theme == 0) UiTheme.surfaceRaised else 0x1A000000)
            setStroke(dp(1), rule[theme])
        }
        capsule.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(capsuleBg[theme])
            setStroke(dp(1), if (theme == 0) UiTheme.stroke else 0x22000000)
        }
        for (b in listOf(navPrev, navNext, settingsBtn)) {
            b.setTextColor(if (theme == 0) 0xFFD6D8E2.toInt() else fg[theme])
            b.background = Glass.ripple(this, null)
        }
        settingsBtn.background = Glass.ripple(this, GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (theme == 0) 0x1FFFFFFF else 0x14000000)
        }, 18)
        readBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(rule[theme])
    }

    private fun go(d: Int) {
        val n = idx + d
        if (n in list.indices) { idx = n; render() }
    }

    /** Reading settings: size, line spacing, appearance. All of it persists. */
    private fun showSettings() {
        val c = this
        val sheet = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            background = Sheets.sheetBackground(c)
        }
        sheet.addView(Sheets.handle(c))
        sheet.addView(TextView(c).apply {
            text = "Reading settings"
            textSize = Type.heading
            typeface = Type.bold()
            setTextColor(UiTheme.role(UiTheme.Role.TEXT))
            setPadding(dp(4), 0, dp(4), dp(12))
        })
        val preview = TextView(c).apply {
            text = "The quick brown fox jumps over the lazy dog."
            textSize = fs
            setLineSpacing(dp(4).toFloat(), 1.4f)
            setTextColor(fg[theme])
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Glass.solid(c, bg[theme], 14, rule[theme])
        }
        sheet.addView(preview)
        // size
        val sizeRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sizeRow.addView(TextView(c).apply {
            text = "Text size"
            textSize = Type.body
            setTextColor(UiTheme.role(UiTheme.Role.TEXT))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sizeLabel = TextView(c).apply {
            text = "${fs.toInt()}"
            textSize = Type.label
            typeface = Type.medium()
            setTextColor(UiTheme.role(UiTheme.Role.DIM))
            gravity = Gravity.CENTER
            minimumWidth = dp(40)
        }
        sizeRow.addView(Buttons.make(c, "", Buttons.Style.TONAL, Ic.MINUS, 44) { setFont(fs - 1f); preview.textSize = fs; sizeLabel.text = "${fs.toInt()}" },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(dp(44), dp(44)))
        sizeRow.addView(Buttons.make(c, "", Buttons.Style.TONAL, Ic.PLUS, 44) { setFont(fs + 1f); preview.textSize = fs; sizeLabel.text = "${fs.toInt()}" },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        sheet.addView(sizeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        // appearance
        val appearance = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        val names = arrayOf("Night", "Sepia", "Paper")
        names.forEachIndexed { i, n ->
            val swatch = TextView(c).apply {
                text = n
                textSize = Type.label
                typeface = if (i == theme) Type.bold() else Type.medium()
                gravity = Gravity.CENTER
                setTextColor(fg[i])
                minimumHeight = dp(52)
                isClickable = true
                background = Glass.ripple(c, GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(bg[i])
                    setStroke(dp(if (i == theme) 2 else 1), if (i == theme) accent else rule[i])
                }, 12)
                setOnClickListener {
                    theme = i
                    Prefs.put(c, "rtheme", theme.toString())
                    applyTheme()
                    preview.setTextColor(fg[theme])
                    preview.background = Glass.solid(c, bg[theme], 14, rule[theme])
                    styleSwatches(appearance)
                }
            }
            appearance.addView(swatch, LinearLayout.LayoutParams(0, dp(52), 1f).also {
                it.marginStart = if (i == 0) 0 else dp(8); it.marginEnd = if (i == 2) 0 else dp(8)
            })
        }
        val done = Buttons.make(c, "Done", Buttons.Style.FILLED, heightDp = 46) { }
        val dlg = Sheets.create(c, sheet)
        done.setOnClickListener { dlg.dismiss(); revealChrome() }
        sheet.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).also { it.topMargin = dp(14) })
        dlg.show()
    }

    /** Selected appearance gets a 2dp accent ring and bold label; the rest a hairline. */
    private fun styleSwatches(row: LinearLayout) {
        for (k in 0 until row.childCount) {
            val v = row.getChildAt(k) as? TextView ?: continue
            v.typeface = if (k == theme) Type.bold() else Type.medium()
            v.background = Glass.ripple(this, GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(bg[k])
                setStroke(dp(if (k == theme) 2 else 1), if (k == theme) accent else rule[k])
            }, 12)
        }
    }

    private fun render() {
        val t = list[idx]
        titleTv.text = novel
        subTv.text = t.label()
        countTv.text = "${idx + 1}/${list.size}"
        readBar.progress = (((idx + 1).toFloat() / list.size) * 100).toInt()
        body.text = Store.read(this, t.id)
        scroll.post { scroll.scrollTo(0, 0) }
        Prefs.put(this, "last_$novel", t.id.toString())
        val first = idx == 0
        val last = idx == list.size - 1
        navPrev.alpha = if (first) 0.35f else 1f
        navNext.alpha = if (last) 0.35f else 1f
        navPrev.isEnabled = !first
        navNext.isEnabled = !last
    }
}
