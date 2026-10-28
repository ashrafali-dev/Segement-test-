package com.ashraf.novelstudio

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One recent page, for Home. */
data class HomeRecent(val title: String, val url: String, val site: String, val timeMs: Long)

/** A site chip ("WTR Lab", "Qidian", ...). */
data class HomeSite(val name: String, val url: String)

/**
 * Home, per the approved board: mascot, wordmark, one big search/paste pill, wrapped mode chips,
 * a caption describing the selected mode, an optional updates strip, then a two-item dock
 * (Library / Menu).
 *
 * The public API is unchanged from the old Home (`render`, `hideKeyboard`, onOpen/onSearch/onMenu/
 * onHub) so every caller in MainActivity keeps working.
 */
class HomeView(private val ctx: Context) : FrameLayout(ctx) {
    var onOpen: (String) -> Unit = {}
    var onSearch: (String) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onHub: (Hub.Part) -> Unit = {}
    /** Tapping a mode chip; MainActivity opens the mode start page or switches mode. */
    var onMode: (String) -> Unit = {}

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT
    private val p = Tokens.palette(ctx)

    private val scroll = ScrollView(ctx)
    private val content = LinearLayout(ctx)
    private val input = EditText(ctx)
    private val searchIcon = ImageView(ctx)
    private var iconIsUrl = false
    private var token = 0

    /** Mode whose caption/chip is highlighted. Set by MainActivity before each render. */
    private var selectedMode = "novel"

    private fun dpx(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    init {
        isClickable = true
        setBackgroundColor(p.bg)
        content.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.setPadding(0, 0, 0, dpx(16))
        scroll.addView(content, ViewGroup.LayoutParams(mp, wc))
        addView(scroll, FrameLayout.LayoutParams(mp, mp))
        // Home dock: Library + Menu, at the bottom where the thumb is.
        addView(
            homeDock(),
            FrameLayout.LayoutParams(mp, wc).also { it.gravity = Gravity.BOTTOM }
        )
    }

    fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        input.clearFocus()
    }

    // ------------------------------------------------------------------ search pill
    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        hideKeyboard()
        input.setText("")
        onSearch(t)
    }

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http") || (!t.contains(' ') && t.contains('.'))
    }

    private fun refreshIcon(text: String) {
        val url = looksLikeUrl(text)
        if (url == iconIsUrl) return
        iconIsUrl = url
        searchIcon.setImageDrawable(IconDrawable(if (url) Ic.GLOBE else Ic.SEARCH, p.dim, 1.7f))
    }

    private fun searchPill(wide: Boolean): View {
        searchIcon.setImageDrawable(IconDrawable(Ic.SEARCH, p.dim, 1.7f))
        input.apply {
            hint = Strings.SEARCH_HINT
            setHintTextColor(p.dim)
            setTextColor(p.ink)
            textSize = Tokens.SP_BODY_SMALL
            typeface = Tokens.regular(ctx)
            background = null
            setSingleLine(true)
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(0, 0, 0, 0)
            minHeight = 0
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { submit(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refreshIcon(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(20), 0, dpx(12), 0)
            background = Tokens.pill(ctx, p.card, 1, p.line)
            addView(searchIcon, LinearLayout.LayoutParams(dpx(20), dpx(20)).also { it.marginEnd = dpx(12) })
            addView(input, LinearLayout.LayoutParams(0, wc, 1f))
        }
    }

    // ------------------------------------------------------------------ home dock
    private fun homeDock(): View {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(View(ctx).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(mp, dpx(1)))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpx(20), dpx(6), dpx(20), dpx(16))
            setBackgroundColor(p.bg)
        }
        row.addView(dockItem(Ic.LIBRARY, Strings.LIBRARY) { openLibrary() }, LinearLayout.LayoutParams(dpx(96), dpx(52)))
        row.addView(dockItem(Ic.LIST, Strings.MENU) { onMenu() }, LinearLayout.LayoutParams(dpx(96), dpx(52)))
        column.addView(row, LinearLayout.LayoutParams(mp, wc))
        return column
    }

    /** Library lives in the menu today; the dock entry opens it through the same call. */
    private fun openLibrary() {
        // The existing Library screen is reached from the menu; keep one path, not two.
        onMenu()
    }

    private fun dockItem(ic: Ic, label: String, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageDrawable(IconDrawable(ic, p.dim, 1.7f))
                setPadding(dpx(1), dpx(1), dpx(1), dpx(1))
            }, LinearLayout.LayoutParams(dpx(22), dpx(22)))
            addView(TextView(ctx).apply {
                text = label
                textSize = Tokens.SP_MICRO
                typeface = Tokens.regular(ctx)
                setTextColor(p.dim)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(wc, wc).also { it.topMargin = dpx(3) })
        }

    // ------------------------------------------------------------------ render
    fun render(recents: List<HomeRecent>, novelSites: List<HomeSite>, comicSites: List<HomeSite>) {
        token++
        content.removeAllViews()

        // mascot + wordmark
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpx(22), dpx(44), dpx(22), 0)
        }
        head.addView(ImageView(ctx).apply {
            setImageDrawable(MascotDrawable(p.dark))
            contentDescription = "Toon Sensei"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dpx(84), dpx(84)))
        head.addView(TextView(ctx).apply {
            text = Strings.WORDMARK
            textSize = Tokens.SP_WORDMARK_SMALL
            typeface = Tokens.wordmark(ctx)
            setTextColor(p.ink)
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(wc, wc).also { it.topMargin = dpx(6); it.bottomMargin = dpx(16) })

        // search pill
        head.addView(searchPill(true), LinearLayout.LayoutParams(mp, dpx(54)))

        // mode chips
        head.addView(modeChips(), LinearLayout.LayoutParams(mp, wc).also { it.topMargin = dpx(16) })

        // caption for the selected mode
        head.addView(TextView(ctx).apply {
            text = captionFor(selectedMode)
            textSize = Tokens.SP_SECONDARY
            typeface = Tokens.regular(ctx)
            setTextColor(p.dim)
            gravity = Gravity.CENTER
            setPadding(dpx(8), dpx(12), dpx(8), 0)
        }, LinearLayout.LayoutParams(mp, wc))

        content.addView(head, LinearLayout.LayoutParams(mp, wc))

        // recents (kept from the old Home, restyled): only real pages, never site front pages
        if (recents.isNotEmpty()) {
            content.addView(sectionLabel("Recent"), LinearLayout.LayoutParams(mp, wc))
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Tokens.shape(ctx, p.card, Tokens.R_CARD)
            }
            recents.take(4).forEachIndexed { i, r ->
                card.addView(recentRow(r, i < minOf(recents.size, 4) - 1), LinearLayout.LayoutParams(mp, wc))
            }
            content.addView(card, LinearLayout.LayoutParams(mp, wc).also {
                it.setMargins(dpx(22), 0, dpx(22), 0)
            })
        }

        // supported sites, as quiet chips
        val sites = novelSites + comicSites
        if (sites.isNotEmpty()) {
            content.addView(sectionLabel("Sites"), LinearLayout.LayoutParams(mp, wc))
            content.addView(siteChips(sites), LinearLayout.LayoutParams(mp, wc))
        }

        scroll.scrollTo(0, 0)
    }

    /** Called by MainActivity so the highlighted chip matches the reader mode. */
    fun setSelectedMode(mode: String) {
        selectedMode = mode
    }

    private fun captionFor(mode: String): String = when (mode) {
        "scan" -> "Reads every panel of the chapter and writes the translation onto the page."
        "realtime" -> "Translates new panels as you scroll."
        "lite" -> "Reads one screen when you tap. Light on battery and RAM."
        else -> "Reads the chapter text and translates it on the page."
    }

    private fun modeChips(): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun chip(label: String, key: String, icon: Ic, soon: Boolean = false): TextView {
            val selected = key == selectedMode && !soon
            return TextView(ctx).apply {
                text = label
                textSize = Tokens.SP_BODY_SMALL
                typeface = if (selected) Tokens.medium(ctx) else Tokens.regular(ctx)
                gravity = Gravity.CENTER
                setPadding(dpx(16), 0, dpx(16), 0)
                compoundDrawablePadding = dpx(8)
                includeFontPadding = false
                minHeight = dpx(44)
                val fg = if (selected) p.onAccent else p.ink
                setTextColor(fg)
                setCompoundDrawablesRelativeWithIntrinsicBounds(IconDrawable(icon, fg, 1.7f), null, null, null)
                background = if (selected) Tokens.pill(ctx, p.ink) else Tokens.pill(ctx, p.card, 1, p.line)
                contentDescription = if (soon) "$label, coming soon" else label
                if (soon) alpha = 0.75f
                setOnClickListener { if (soon) onMode("soon") else onMode(key) }
            }
        }
        // two rows so the five chips always fit a narrow phone without clipping
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row1.addView(chip(Strings.CHIP_NOVEL, "novel", Ic.BOOK), LinearLayout.LayoutParams(wc, dpx(44)))
        row1.addView(chip(Strings.CHIP_SCANNING, "scan", Ic.ZOOM), LinearLayout.LayoutParams(wc, dpx(44)).also { it.marginStart = dpx(8) })
        row1.addView(chip(Strings.CHIP_REALTIME, "realtime", Ic.PLAY), LinearLayout.LayoutParams(wc, dpx(44)).also { it.marginStart = dpx(8) })
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row2.addView(chip(Strings.CHIP_LITE, "lite", Ic.LINK), LinearLayout.LayoutParams(wc, dpx(44)))
        row2.addView(chip(Strings.LINK_TRANSLATE, "soon", Ic.LINK, soon = true), LinearLayout.LayoutParams(wc, dpx(44)).also { it.marginStart = dpx(8) })
        wrap.addView(row1, LinearLayout.LayoutParams(mp, wc))
        wrap.addView(row2, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = dpx(8) })
        return wrap
    }

    private fun sectionLabel(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = Tokens.SP_SECONDARY
        typeface = Tokens.medium(ctx)
        setTextColor(p.dim)
        setPadding(dpx(22), dpx(24), dpx(22), dpx(8))
    }

    private fun recentRow(r: HomeRecent, divider: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpx(16), dpx(10), dpx(16), dpx(10))
        contentDescription = "${r.title}, ${r.site}"
        setOnClickListener { onOpen(r.url) }
        // cover tile with the title's first character, like the board
        addView(TextView(ctx).apply {
            text = r.title.trim().take(1).ifBlank { "?" }
            textSize = 18f
            typeface = Tokens.bengali(ctx)
            setTextColor(0xFF3D3832.toInt())
            gravity = Gravity.CENTER
            background = Tokens.shape(ctx, 0xFFCDBBA7.toInt(), 6)
        }, LinearLayout.LayoutParams(dpx(40), dpx(54)).also { it.marginEnd = dpx(14) })
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            text = r.title
            textSize = Tokens.SP_BODY
            typeface = Tokens.medium(ctx)
            setTextColor(p.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        box.addView(TextView(ctx).apply {
            text = r.site
            textSize = Tokens.SP_SECONDARY
            setTextColor(p.dim)
            maxLines = 1
        })
        addView(box, LinearLayout.LayoutParams(0, wc, 1f))
        addView(ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Ic.FORWARD, p.dim, 1.7f))
        }, LinearLayout.LayoutParams(dpx(16), dpx(16)))
        if (divider) addView(View(ctx).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(1, 1))
    }

    private fun siteChips(sites: List<HomeSite>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpx(22), 0, dpx(22), 0)
        var row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        var used = 0
        sites.take(8).forEach { s ->
            val chip = TextView(ctx).apply {
                text = s.name
                textSize = Tokens.SP_BODY_SMALL
                setTextColor(p.ink)
                gravity = Gravity.CENTER
                setPadding(dpx(18), 0, dpx(18), 0)
                background = Tokens.pill(ctx, p.card, 1, p.line)
                contentDescription = s.name
                setOnClickListener { onOpen(s.url) }
            }
            // wrap after three chips so nothing is cut off on a narrow screen
            if (used == 3) {
                addView(row, LinearLayout.LayoutParams(mp, wc))
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
                used = 0
            }
            row.addView(chip, LinearLayout.LayoutParams(wc, dpx(44)).also { if (used > 0) it.marginStart = dpx(10) })
            used++
        }
        if (row.childCount > 0) addView(row, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = dpx(10) })
    }
}
