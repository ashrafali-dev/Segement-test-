package com.ashraf.novelstudio

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class ReaderActivity : Activity() {
    private var list: List<Tr> = emptyList()
    private var idx = 0
    private var fs = 18f
    private var theme = 0
    private lateinit var root: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var countTv: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var capsule: LinearLayout
    private lateinit var readBar: ProgressBar

    // themes: 0 dark, 1 sepia, 2 white
    private val bg = intArrayOf(0xFF07080D.toInt(), 0xFFF4ECD8.toInt(), 0xFFFFFFFF.toInt())
    private val fg = intArrayOf(0xFFDDDDDD.toInt(), 0xFF3B2F1E.toInt(), 0xFF111111.toInt())
    private val dim = intArrayOf(0xFF8B93A7.toInt(), 0xFF8A7B5C.toInt(), 0xFF777777.toInt())
    private val card = intArrayOf(0xFF14181F.toInt(), 0x33FFFFFF, 0x11000000)
    private val pill = intArrayOf(0xF21E232E.toInt(), 0xE63B2F1E.toInt(), 0xE6111111.toInt())
    private val accent = UiTheme.accent
    private var novel = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pillBg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun btn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        novel = intent.getStringExtra("novel") ?: ""
        val id = intent.getLongExtra("id", -1L)
        list = Store.chapters(this, novel)
        if (list.isEmpty()) { finish(); return }
        idx = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        fs = Prefs.get(this, "fs", "18").toFloatOrNull() ?: 18f
        theme = Prefs.get(this, "rtheme", "0").toIntOrNull() ?: 0

        // header: chapter title + counter chip
        titleTv = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), dp(10), dp(14), dp(0))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        countTv = TextView(this).apply {
            textSize = 11.5f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp(10), dp(8), dp(10), 0) }
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleTv)
            addView(countTv)
        }

        body = TextView(this).apply {
            setPadding(dp(20), dp(14), dp(20), dp(110))
            setLineSpacing(0f, 1.45f)
            setTextIsSelectable(true)
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
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x22FFFFFF)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP
            )
        }

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }

        // floating control capsule: prev, font, theme, next
        capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dp(12).toFloat()
            setPadding(dp(6), 0, dp(6), 0)
            addView(btn("◀") { go(-1) })
            addView(btn("A−") { setFont(fs - 1f) })
            addView(btn("A+") { setFont(fs + 1f) })
            addView(btn("🌙") { theme = (theme + 1) % 3; Prefs.put(this@ReaderActivity, "rtheme", theme.toString()); applyTheme() })
            addView(btn("▶") { go(1) })
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root = FrameLayout(this).apply {
            addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(readBar)
            addView(capsule, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).also { it.setMargins(0, 0, 0, dp(18)) })
        }
        setContentView(root)
        applyTheme()
        show()
    }

    private fun setFont(v: Float) {
        fs = v.coerceIn(12f, 34f)
        Prefs.put(this, "fs", fs.toString())
        body.textSize = fs
    }

    private fun applyTheme() {
        root.setBackgroundColor(bg[theme])
        body.setTextColor(fg[theme])
        titleTv.setTextColor(fg[theme])
        header.setBackgroundColor(card[theme])
        countTv.setTextColor(if (theme == 0) accent else fg[theme])
        countTv.background = pillBg(if (theme == 0) 0xFF1E232E.toInt() else 0x22000000, 14)
        capsule.background = pillBg(pill[theme], 25)
        for (i in 0 until capsule.childCount) {
            val b = capsule.getChildAt(i) as TextView
            b.setTextColor(if (theme == 0) 0xFFC7D0E2.toInt() else 0xFFF2ECE0.toInt())
        }
        body.textSize = fs
    }

    private fun go(d: Int) {
        val n = idx + d
        if (n in list.indices) { idx = n; show() }
    }

    private fun show() {
        val t = list[idx]
        titleTv.text = "$novel  •  ${t.label()}"
        countTv.text = "${idx + 1}/${list.size}"
        readBar.progress = (((idx + 1).toFloat() / list.size) * 100).toInt()
        body.text = Store.read(this, t.id)
        scroll.post { scroll.scrollTo(0, 0) }
        Prefs.put(this, "last_$novel", t.id.toString())
    }
}
