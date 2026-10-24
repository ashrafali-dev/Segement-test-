package com.ashraf.novelstudio

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class HomeRecent(val title: String, val url: String, val site: String, val timeMs: Long)
data class HomeSite(val name: String, val url: String)

/** "Home" / "Library" / "Updates" / "Sources" — the same view renders each. */
enum class HomeTab { HOME, LIBRARY, UPDATES, SOURCES }

// ============================================================================ favicons

/**
 * Website icons for the home screen. Memory cache + files/favicons on disk, fetched off the main thread.
 * Tries Google's icon service first (sharp 128px icons for almost every site), then the site's own /favicon.ico.
 */
object Favicons {
    private val mem = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val failed = HashSet<String>()

    fun load(ctx: Context, host: String, cb: (Bitmap?) -> Unit) {
        val key = host.lowercase()
        val hit = mem.get(key)
        if (hit != null) { cb(hit); return }
        if (key in failed) { cb(null); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = fromDisk(app, key) ?: download(app, key)
            ui.post {
                if (bmp != null) mem.put(key, bmp) else failed.add(key)
                cb(bmp)
            }
        }
    }

    private fun file(app: Context, key: String): File {
        val dir = File(app.filesDir, "favicons")
        dir.mkdirs()
        return File(dir, key.replace(Regex("[^a-z0-9.-]"), "_") + ".png")
    }

    private fun fromDisk(app: Context, key: String): Bitmap? = try {
        val f = file(app, key)
        if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.path) else null
    } catch (_: Exception) {
        null
    }

    private fun download(app: Context, key: String): Bitmap? {
        val urls = listOf(
            "https://www.google.com/s2/favicons?sz=128&domain=$key",
            "https://$key/favicon.ico"
        )
        for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (c.responseCode != 200) { c.disconnect(); continue }
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (bmp.width < 8) continue
                try {
                    file(app, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (_: Exception) {}
                return bmp
            } catch (_: Exception) {}
        }
        return null
    }
}

// ============================================================================ shared pieces

/** Deterministic, muted cover colour per title (a calm palette, not a rainbow). */
internal object Covers {
    private val palette = intArrayOf(
        0xFF6D74C9.toInt(), 0xFF3E8E7E.toInt(), 0xFFB0607D.toInt(), 0xFF9A7742.toInt(),
        0xFF4A7FAE.toInt(), 0xFF6E8C4C.toInt(), 0xFFB0664C.toInt(), 0xFF6A5FA8.toInt()
    )
    fun colorFor(s: String): Int = palette[Math.abs(s.hashCode() % palette.size)]

    /**
     * Book cover: tinted gradient, one initial, a hairline, and a thin "spine" so a grid of covers reads as
     * books rather than coloured tiles. [wDp]/[hDp] are the box; the caller sets layout params.
     */
    fun view(ctx: Context, title: String, wDp: Int, hDp: Int, radiusDp: Int, textSp: Float): View {
        val base = colorFor(title)
        val box = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(UiTheme.shade(base, if (UiTheme.dark) 0f else -0.35f), UiTheme.deepen(base, if (UiTheme.dark) 0.26f else 0.1f))
            ).apply { cornerRadius = ctx.dpf(radiusDp) }
            contentDescription = title
        }
        box.addView(View(ctx).apply { background = Glass.solid(ctx, 0x22FFFFFF, 0) },
            FrameLayout.LayoutParams(ctx.dpi(3), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).also { it.leftMargin = ctx.dpi(1) })
        box.addView(TextView(ctx).apply {
            text = title.trim().take(1).uppercase().ifEmpty { "N" }
            textSize = textSp
            typeface = Type.bold()
            setTextColor(0xE6FFFFFF.toInt())
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(View(ctx).apply { background = Glass.solid(ctx, 0x00000000, radiusDp, 0x24FFFFFF) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.layoutParams = LinearLayout.LayoutParams(ctx.dpi(wDp), ctx.dpi(hDp))
        return box
    }

    fun ago(ms: Long): String {
        if (ms <= 0L) return ""
        val m = (System.currentTimeMillis() - ms) / 60000L
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 48 * 60 -> "yesterday"
            m < 24 * 60 * 30 -> "${m / (24 * 60)} days ago"
            else -> "${m / (24 * 60 * 30)} months ago"
        }
    }

    /** Short day stamp for update rows ("Today", "Yesterday", "3 days ago", then a date). */
    fun dayStamp(ms: Long): String {
        if (ms <= 0L) return ""
        val days = (System.currentTimeMillis() - ms) / (24L * 60L * 60L * 1000L)
        return when {
            days <= 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "$days days ago"
            else -> {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
                val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                "${months[cal.get(java.util.Calendar.MONTH)]} ${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
            }
        }
    }
}

internal fun View.pressEffect() {
    setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        }
        false
    }
}

internal fun View.clickable(onClick: () -> Unit): View {
    isClickable = true
    setOnClickListener { onClick() }
    pressEffect()
    return this
}

private enum class LibSection { ALL, BOOKS, DOWNLOADED }
private enum class LibSort { RECENT, TITLE, CHAPTERS }

class HomeView(private val ctx: Context) : FrameLayout(ctx) {
    var onOpen: (String) -> Unit = {}
    var onSearch: (String) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onHub: (Hub.Part) -> Unit = {}
    var onOpenUpdates: () -> Unit = {}
    var onOpenSources: () -> Unit = {}

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT
    private val scroll = ScrollView(ctx)
    private val content = LinearLayout(ctx)
    private val topBar = LinearLayout(ctx)
    private val backBtn = ImageView(ctx)
    private val titleView = TextView(ctx)
    private val menuBtn = ImageView(ctx)
    private val input = EditText(ctx)
    private val searchIcon = ImageView(ctx)
    private val searchField = LinearLayout(ctx)

    private var iconIsUrl = false
    private var token = 0
    private var riseIndex = 0
    private var tab = HomeTab.HOME

    private var recents: List<HomeRecent> = emptyList()
    private var novelSites: List<HomeSite> = emptyList()
    private var comicSites: List<HomeSite> = emptyList()
    private var shelves: List<LibraryModel.Shelf> = emptyList()
    private var updates: List<LibraryModel.Update> = emptyList()
    private var sources: List<LibraryModel.Ext> = emptyList()

    // Library state (lives across re-renders so a tab switch keeps the user's filter).
    private var libSection = LibSection.ALL
    private var libSort = LibSort.RECENT
    private var libQuery = ""

    init {
        isClickable = true
        content.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.setPadding(0, 0, 0, ctx.dpi(16))
        scroll.addView(content, ViewGroup.LayoutParams(mp, wc))

        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(ctx.dpi(4), ctx.dpi(4), ctx.dpi(4), ctx.dpi(2))
        backBtn.setImageDrawable(IconDrawable(Ic.BACK, UiTheme.role(UiTheme.Role.ICON), 1.9f))
        backBtn.setPadding(ctx.dpi(12), ctx.dpi(12), ctx.dpi(12), ctx.dpi(12))
        backBtn.background = Glass.ripple(ctx, null)
        backBtn.contentDescription = "Back"
        backBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        backBtn.visibility = View.GONE
        backBtn.setOnClickListener { resetToHome() }
        titleView.textSize = Type.title
        titleView.typeface = Type.bold()
        titleView.setTextColor(UiTheme.role(UiTheme.Role.TEXT))
        titleView.setPadding(ctx.dpi(6), 0, 0, 0)
        menuBtn.setImageDrawable(IconDrawable(Ic.DOTS, UiTheme.role(UiTheme.Role.ICON)))
        menuBtn.setPadding(ctx.dpi(12), ctx.dpi(12), ctx.dpi(12), ctx.dpi(12))
        menuBtn.background = Glass.ripple(ctx, null)
        menuBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        menuBtn.contentDescription = "Menu"
        menuBtn.setOnClickListener { onMenu() }
        topBar.addView(backBtn, LinearLayout.LayoutParams(ctx.dpi(48), ctx.dpi(48)))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, wc, 1f))

        topBar.addView(menuBtn, LinearLayout.LayoutParams(ctx.dpi(48), ctx.dpi(48)))
        buildSearchBar()

        addView(scroll, LayoutParams(mp, mp))
        addView(topBar, LayoutParams(mp, wc, Gravity.TOP))
    }

    private fun buildSearchBar() {
        searchIcon.setImageDrawable(IconDrawable(Ic.SEARCH, UiTheme.role(UiTheme.Role.FAINT)))
        input.apply {
            hint = "Search the web or paste a link"
            setHintTextColor(UiTheme.role(UiTheme.Role.FAINT))
            setTextColor(UiTheme.role(UiTheme.Role.TEXT))
            textSize = Type.body
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(0, 0, 0, 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { submit(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refreshIcon(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            onFocusChangeListener = View.OnFocusChangeListener { _, has -> searchField.background = Glass.field(ctx, 26, has) }
        }
        val closeBtn = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Ic.CLOSE, UiTheme.role(UiTheme.Role.DIM), 1.8f))
            val p = ctx.dpi(12)
            setPadding(p, p, p, p)
            background = Glass.ripple(ctx, null)
            contentDescription = "Close search"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { closeSearch() }
        }
        val go = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Ic.FORWARD, UiTheme.role(UiTheme.Role.ON_ACCENT), 2.2f))
            val p = ctx.dpi(11)
            setPadding(p, p, p, p)
            background = Glass.ripple(ctx, Glass.accent(ctx, 100), null)
            isClickable = true
            contentDescription = "Go"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { submit() }
        }
        searchField.orientation = LinearLayout.HORIZONTAL
        searchField.gravity = Gravity.CENTER_VERTICAL
        searchField.setPadding(ctx.dpi(14), 0, ctx.dpi(6), 0)
        searchField.background = Glass.field(ctx, 26, false)
        searchField.addView(searchIcon, LinearLayout.LayoutParams(ctx.dpi(18), ctx.dpi(18)).also { it.marginEnd = ctx.dpi(10) })
        searchField.addView(input, LinearLayout.LayoutParams(0, wc, 1f))
        searchField.addView(closeBtn, LinearLayout.LayoutParams(ctx.dpi(44), ctx.dpi(44)))
        searchField.addView(go, LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(40)))
    }

    private fun closeSearch() {
        hideKeyboard()
        if (tab == HomeTab.LIBRARY && libQuery.isNotEmpty()) { libQuery = ""; rerender() }
    }

    /** The system back button; true when home consumed it. */
    fun handleBack(): Boolean = when {
        tab != HomeTab.HOME -> { resetToHome(); true }
        else -> false
    }

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http") || (!t.contains(' ') && t.contains('.'))
    }

    private fun refreshIcon(text: String) {
        val url = looksLikeUrl(text)
        if (url == iconIsUrl) return
        iconIsUrl = url
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        hideKeyboard()
        input.setText("")
        closeSearch()
        onSearch(t)
    }

    fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        input.clearFocus()
    }

    // ---------------------------------------------------------------- helpers

    private fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

    private fun rise(v: View): View {
        v.alpha = 0f
        v.translationY = ctx.dpf(10)
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((riseIndex++ * 40L).coerceAtMost(240L))
            .setDuration(300L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return v
    }

    private fun sectionLabel(t: String, action: String? = null, onClick: (() -> Unit)? = null): View {
        val title = TextView(ctx).apply {
            text = t
            textSize = Type.overline
            letterSpacing = 0.1f
            setTextColor(UiTheme.role(UiTheme.Role.FAINT))
            typeface = Type.bold()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(18), ctx.dpi(20), ctx.dpi(6))
            addView(title, LinearLayout.LayoutParams(0, wc, 1f))
        }
        if (action != null && onClick != null) row.addView(TextView(ctx).apply {
            text = action
            textSize = Type.label
            typeface = Type.medium()
            setTextColor(UiTheme.role(UiTheme.Role.ACCENT))
            setPadding(ctx.dpi(10), ctx.dpi(10), 0, ctx.dpi(10))
            minimumHeight = ctx.dpi(Touch.minCompact)
            gravity = Gravity.CENTER_VERTICAL
        }.also { it.clickable { onClick() } })
        return row
    }

    private fun siteIcon(site: HomeSite, sizeDp: Int, radiusDp: Int, textSp: Float): View {
        val size = ctx.dpi(sizeDp)
        val frame = FrameLayout(ctx)
        val base = Covers.colorFor(site.name)
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(base, ModeUi.lerp(base, Color.BLACK, 0.25f))
        ).apply { cornerRadius = ctx.dpf(radiusDp) }
        val letter = TextView(ctx).apply {
            text = site.name.take(1).uppercase()
            setTextColor(Color.WHITE)
            textSize = textSp
            typeface = Type.bold()
            gravity = Gravity.CENTER
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            val p = (size * 0.2f).toInt()
            setPadding(p, p, p, p)
        }
        frame.addView(letter, LayoutParams(mp, mp))
        frame.addView(img, LayoutParams(mp, mp))
        val mine = token
        val host = hostOf(site.url)
        if (host != null) {
            Favicons.load(ctx, host) { bmp ->
                if (bmp != null && mine == token) {
                    frame.background = GradientDrawable().apply {
                        setColor(0xFFF4F4F6.toInt())
                        cornerRadius = ctx.dpf(radiusDp)
                    }
                    img.setImageBitmap(bmp)
                    img.visibility = View.VISIBLE
                    letter.visibility = View.GONE
                }
            }
        }
        return frame
    }

    private fun siteTile(site: HomeSite): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ctx.dpi(8), 0, ctx.dpi(6))
            contentDescription = site.name
            addView(siteIcon(site, 52, 15, 20f), LinearLayout.LayoutParams(ctx.dpi(52), ctx.dpi(52)))
            addView(TextView(ctx).apply {
                text = site.name
                textSize = Type.caption
                setTextColor(UiTheme.role(UiTheme.Role.DIM))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ctx.dpi(2), ctx.dpi(8), ctx.dpi(2), 0)
            }, LinearLayout.LayoutParams(mp, wc))
            clickable { onOpen(site.url) }
        }
    }

    private fun siteGrid(sites: List<HomeSite>): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
        }
        sites.chunked(4).forEach { rowSites ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowSites.forEach { row.addView(siteTile(it), LinearLayout.LayoutParams(0, wc, 1f)) }
            repeat(4 - rowSites.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            col.addView(row, LinearLayout.LayoutParams(mp, wc))
        }
        return col
    }

    private fun chip(site: HomeSite): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(6), ctx.dpi(6), ctx.dpi(14), ctx.dpi(6))
            minimumHeight = ctx.dpi(Touch.minCompact)
            background = Glass.ripple(ctx, Glass.solid(ctx, UiTheme.role(UiTheme.Role.CONTROL), 20, UiTheme.role(UiTheme.Role.STROKE_SOFT)), 20)
            contentDescription = site.name
            addView(siteIcon(site, 24, 8, 12f), LinearLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24)).also { it.marginEnd = ctx.dpi(8) })
            addView(TextView(ctx).apply {
                text = site.name
                textSize = Type.label
                setTextColor(UiTheme.role(UiTheme.Role.TEXT))
                maxLines = 1
            })
            clickable { onOpen(site.url) }
        }
    }

    private fun pad(v: View, h: Int = 16, vp: Int = 5): View = FrameLayout(ctx).apply {
        setPadding(ctx.dpi(h), ctx.dpi(vp), ctx.dpi(h), ctx.dpi(vp))
        addView(v, LayoutParams(mp, wc))
    }

    /** Horizontal chip scroller with consistent spacing; used by every filter row. */
    private fun hScroll(inner: View): View = object : HorizontalScrollView(ctx) {
        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            addView(inner)
        }
    }

    // ---------------------------------------------------------------- cards

    /** Flat row card with hairline edge; the caller decides what happens on tap. */
    private fun rowCard(inner: View, radius: Int = 18, onTap: (() -> Unit)? = null): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dpi(64)
        setPadding(ctx.dpi(14), ctx.dpi(12), ctx.dpi(14), ctx.dpi(12))
        background = Glass.ripple(ctx, Glass.solid(ctx, UiTheme.role(UiTheme.Role.SURFACE), radius, UiTheme.role(UiTheme.Role.STROKE_SOFT)), radius)
        addView(inner, LinearLayout.LayoutParams(0, wc, 1f))
        if (onTap != null) { pressEffect(); setOnClickListener { onTap() } }
    }

    private fun valueBadge(t: String, fg: Int, bg: Int): TextView = TextView(ctx).apply {
        text = t
        textSize = Type.overline
        typeface = Type.bold()
        setTextColor(fg)
        setPadding(ctx.dpi(8), ctx.dpi(3), ctx.dpi(8), ctx.dpi(3))
        background = Glass.solid(ctx, bg, 9)
    }

    /** Small status dot + label, used for extension state (On / Off / Built-in / Learned). */
    private fun statusPill(label: String, color: Int, filled: Boolean): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dpi(8), ctx.dpi(4), ctx.dpi(10), ctx.dpi(4))
        background = Glass.solid(
            ctx,
            if (filled) UiTheme.withAlpha(color, 0x24) else UiTheme.role(UiTheme.Role.CONTROL),
            100,
            if (filled) UiTheme.withAlpha(color, 0x55) else UiTheme.role(UiTheme.Role.STROKE_SOFT)
        )
        addView(View(ctx).apply { background = Glass.oval(color) },
            LinearLayout.LayoutParams(ctx.dpi(6), ctx.dpi(6)).also { it.marginEnd = ctx.dpi(6) })
        addView(TextView(ctx).apply {
            text = label
            textSize = Type.overline
            typeface = Type.bold()
            setTextColor(if (filled) color else UiTheme.role(UiTheme.Role.DIM))
        })
    }

    private fun shelfCard(s: LibraryModel.Shelf): View {
        val newCount = updates.firstOrNull { it.novel == s.name }?.newCount ?: 0
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Covers.view(ctx, s.name, 44, 60, 8, 19f), LinearLayout.LayoutParams(ctx.dpi(44), ctx.dpi(60)).also { it.marginEnd = ctx.dpi(14) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = s.name
                    textSize = Type.body
                    typeface = Type.bold()
                    setTextColor(UiTheme.role(UiTheme.Role.TEXT))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = LibraryModel.shelfMeta(s)
                    textSize = Type.caption
                    setTextColor(UiTheme.role(UiTheme.Role.DIM))
                    setPadding(0, ctx.dpi(3), 0, 0)
                })
                if (newCount > 0) addView(statusPill("$newCount new", UiTheme.role(UiTheme.Role.OK), true),
                    LinearLayout.LayoutParams(wc, wc).also { it.topMargin = ctx.dpi(7) })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.CHEVRON, UiTheme.role(UiTheme.Role.FAINT))) },
                LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)))
        }
        return rowCard(info) { showShelf(s) }
    }

    private fun updateRow(u: LibraryModel.Update): View {
        val meta = listOfNotNull(u.sourceHost.ifBlank { null }, "${u.chapters} ch", Covers.dayStamp(u.at).ifBlank { null }).joinToString(" · ")
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Covers.view(ctx, u.novel, 34, 46, 7, 15f), LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(46)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = u.novel
                    textSize = Type.body
                    typeface = Type.medium()
                    setTextColor(UiTheme.role(UiTheme.Role.TEXT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = if (u.lastChapter.isNotBlank()) u.lastChapter + "  ·  " + meta else meta
                    textSize = Type.caption
                    setTextColor(UiTheme.role(UiTheme.Role.FAINT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ctx.dpi(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            if (u.newCount > 0) addView(statusPill("${u.newCount} new", UiTheme.role(UiTheme.Role.OK), true))
        }
        return rowCard(info, 16) { openShelfByName(u.novel) }
    }

    private fun sourceRow(e: LibraryModel.Ext, on: Boolean): View {
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(ctx).apply {
                background = Glass.oval(UiTheme.role(UiTheme.Role.CONTROL))
                addView(ImageView(ctx).apply {
                    setImageDrawable(IconDrawable(if (e.builtIn) Ic.BOLT else Ic.GLOBE, if (on) UiTheme.role(UiTheme.Role.ACCENT) else UiTheme.role(UiTheme.Role.FAINT)))
                }, LayoutParams(ctx.dpi(18), ctx.dpi(18), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ctx.dpi(38), ctx.dpi(38)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = e.name
                    textSize = Type.body
                    typeface = Type.medium()
                    setTextColor(if (on) UiTheme.role(UiTheme.Role.TEXT) else UiTheme.role(UiTheme.Role.FAINT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = e.kind
                    textSize = Type.caption
                    setTextColor(UiTheme.role(UiTheme.Role.FAINT))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ctx.dpi(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            val pill = if (e.builtIn) statusPill("Built-in", UiTheme.role(UiTheme.Role.DIM), false)
            else statusPill(if (on) "On" else "Off", if (on) UiTheme.role(UiTheme.Role.OK) else UiTheme.role(UiTheme.Role.FAINT), on)
            if (!e.builtIn) {
                pill.isClickable = true
                pill.contentDescription = "${e.name}: ${if (on) "on, tap to turn off" else "off, tap to turn on"}"
                pill.minimumHeight = ctx.dpi(Touch.minCompact)
                pill.setOnClickListener {
                    LibraryModel.setEnabled(ctx, e.id, !on)
                    rerender()
                }
            }
            addView(pill)
        }
        return rowCard(info, 16) { e.url?.let { u -> onOpen(u) } }
    }

    private fun emptyCard(title: String, body: String, action: String?, icon: Ic = Ic.LIBRARY, onClick: (() -> Unit)? = null): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dpi(20), ctx.dpi(22), ctx.dpi(20), ctx.dpi(20))
            background = Glass.solid(ctx, UiTheme.role(UiTheme.Role.SURFACE), 18, UiTheme.role(UiTheme.Role.STROKE_SOFT))
            addView(FrameLayout(ctx).apply {
                background = Glass.oval(UiTheme.withAlpha(UiTheme.role(UiTheme.Role.ACCENT), 0x1F))
                addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(icon, UiTheme.role(UiTheme.Role.ACCENT))) },
                    LayoutParams(ctx.dpi(22), ctx.dpi(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ctx.dpi(48), ctx.dpi(48)))
            addView(TextView(ctx).apply {
                text = title
                textSize = Type.heading
                typeface = Type.bold()
                gravity = Gravity.CENTER
                setTextColor(UiTheme.role(UiTheme.Role.TEXT))
            }, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = ctx.dpi(12) })
            addView(TextView(ctx).apply {
                text = body
                textSize = Type.label
                gravity = Gravity.CENTER
                setLineSpacing(ctx.dpf(3), 1f)
                setTextColor(UiTheme.role(UiTheme.Role.DIM))
            }, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = ctx.dpi(6) })
            if (action != null && onClick != null) {
                val b = Buttons.make(ctx, action, Buttons.Style.FILLED, heightDp = 44) { onClick() }
                addView(b, LinearLayout.LayoutParams(wc, ctx.dpi(44)).also { it.topMargin = ctx.dpi(14) })
            }
        }
        return col
    }

    // ---------------------------------------------------------------- sections

    fun render(
        recents: List<HomeRecent>,
        novelSites: List<HomeSite>,
        comicSites: List<HomeSite>,
        shelves: List<LibraryModel.Shelf>,
        updates: List<LibraryModel.Update>,
        sources: List<LibraryModel.Ext>
    ) {
        token++
        this.recents = recents
        this.novelSites = novelSites
        this.comicSites = comicSites
        this.shelves = shelves
        this.updates = updates
        this.sources = sources
        rerender()
    }

    private fun openTab(t: HomeTab) { tab = t; rerender() }
    private fun resetToHome() { tab = HomeTab.HOME; libQuery = ""; rerender() }

    private fun openShelfByName(name: String) {
        val s = shelves.firstOrNull { it.name == name }
        if (s != null) showShelf(s) else rerender()
    }

    private fun showShelf(s: LibraryModel.Shelf) {
        val activity = context as? android.app.Activity ?: return
        val chapters = LibraryModel.shelfChapters(ctx, s.name)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(18), ctx.dpi(10), ctx.dpi(18), ctx.dpi(16))
            background = Sheets.sheetBackground(ctx)
        }
        box.addView(Sheets.handle(ctx))
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Covers.view(ctx, s.name, 56, 78, 10, 24f), LinearLayout.LayoutParams(ctx.dpi(56), ctx.dpi(78)).also { it.marginEnd = ctx.dpi(14) })
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = s.name
                textSize = Type.heading
                typeface = Type.bold()
                setTextColor(UiTheme.role(UiTheme.Role.TEXT))
                maxLines = 2
            })
            addView(TextView(ctx).apply {
                text = LibraryModel.shelfMeta(s)
                textSize = Type.caption
                setTextColor(UiTheme.role(UiTheme.Role.DIM))
                setPadding(0, ctx.dpi(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, wc, 1f))
        box.addView(head)
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dpi(16), 0, ctx.dpi(6)) }
        fun act(label: String, style: Buttons.Style, onClick: () -> Unit) = Buttons.make(ctx, label, style, heightDp = 42) { onClick() }
        lateinit var detail: Dialog
        actions.addView(act("Read ${chapters.firstOrNull()?.number?.let { "Ch $it" } ?: "latest"}", Buttons.Style.FILLED) {
            chapters.firstOrNull()?.let {
                LibraryModel.recordRead(ctx, s.name, it.id)
                detail.dismiss()
                ctx.startActivity(android.content.Intent(ctx, ReaderActivity::class.java).putExtra("novel", s.name).putExtra("id", it.id))
            }
        }, LinearLayout.LayoutParams(0, ctx.dpi(42), 1f).also { it.marginEnd = ctx.dpi(8) })
        actions.addView(act("More", Buttons.Style.TONAL) { detail.dismiss(); onShelfMore(s) },
            LinearLayout.LayoutParams(0, ctx.dpi(42), 1f))
        box.addView(actions, LinearLayout.LayoutParams(mp, wc))
        box.addView(TextView(ctx).apply {
            text = "CHAPTERS"
            textSize = Type.overline
            letterSpacing = 0.1f
            typeface = Type.bold()
            setTextColor(UiTheme.role(UiTheme.Role.FAINT))
            setPadding(ctx.dpi(4), ctx.dpi(16), ctx.dpi(4), ctx.dpi(4))
        })
        chapters.forEach { tr ->
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dpi(Touch.minCompact)
                setPadding(ctx.dpi(4), ctx.dpi(11), ctx.dpi(4), ctx.dpi(11))
                addView(TextView(ctx).apply {
                    text = tr.label()
                    textSize = Type.body
                    setTextColor(UiTheme.role(UiTheme.Role.TEXT))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, wc, 1f))
                addView(TextView(ctx).apply {
                    text = Covers.ago(tr.id)
                    textSize = Type.overline
                    setTextColor(UiTheme.role(UiTheme.Role.FAINT))
                })
                isClickable = true
                setOnClickListener {
                    LibraryModel.recordRead(ctx, s.name, tr.id)
                    detail.dismiss()
                    ctx.startActivity(android.content.Intent(ctx, ReaderActivity::class.java).putExtra("novel", s.name).putExtra("id", tr.id))
                }
            }, LinearLayout.LayoutParams(mp, wc))
        }
        val scroll = MaxHeightScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.8f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box, ViewGroup.LayoutParams(mp, wc))
        }
        detail = Sheets.create(activity, scroll)
        detail.show()
    }

    /** Hook filled in by MainActivity (rename / delete need activity-scoped helpers). */
    lateinit var onShelfMore: (LibraryModel.Shelf) -> Unit

    private fun rerender() {
        riseIndex = 0
        content.removeAllViews()
        when (tab) {
            HomeTab.LIBRARY -> bodyLibrary()
            HomeTab.UPDATES -> bodyUpdates()
            HomeTab.SOURCES -> bodySources()
            else -> bodyHome()
        }
        titleView.text = when (tab) {
            HomeTab.LIBRARY -> "Library"
            HomeTab.UPDATES -> "Updates"
            HomeTab.SOURCES -> "Sources"
            else -> "Novel Studio"
        }
        backBtn.visibility = if (tab == HomeTab.HOME) View.GONE else View.VISIBLE
    }

    private fun bodyHome() {
        content.addView(header(), LinearLayout.LayoutParams(mp, wc))
        content.addView(libraryCards(), LinearLayout.LayoutParams(mp, wc))
        val fresh = updates.firstOrNull { it.newCount > 0 }
        if (fresh != null) content.addView(pad(updatesBanner(fresh)), LinearLayout.LayoutParams(mp, wc))
        if (recents.isNotEmpty()) {
            content.addView(rise(sectionLabel("CONTINUE READING")), LinearLayout.LayoutParams(mp, wc))
            recents.take(3).forEach { content.addView(rise(recentRow(it)), LinearLayout.LayoutParams(mp, wc)) }
        }
        content.addView(
            rise(sectionLabel(if (recents.isEmpty()) "START READING" else "MORE SITES", "See all", { openTab(HomeTab.SOURCES) })),
            LinearLayout.LayoutParams(mp, wc)
        )
        content.addView(rise(siteGrid((novelSites + comicSites).distinctBy { it.url }.take(8))), LinearLayout.LayoutParams(mp, wc))
        if (recents.isEmpty() && comicSites.isNotEmpty()) {
            content.addView(rise(sectionLabel("COMICS")), LinearLayout.LayoutParams(mp, wc))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(8)) }
            comicSites.forEach { row.addView(chip(it), LinearLayout.LayoutParams(wc, wc).also { lp -> lp.marginEnd = ctx.dpi(8) }) }
            content.addView(rise(hScroll(row)), LinearLayout.LayoutParams(mp, wc))
        }
        content.addView(rise(sectionLabel("HUBS", "Webtoon", { onHub(Hub.Part.WEBTOON) })), LinearLayout.LayoutParams(mp, wc))
        content.addView(rise(hubRow()), LinearLayout.LayoutParams(mp, wc))
    }

    // ---------------------------------------------------------------- library

    private fun filterPill(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = Type.label
        typeface = if (selected) Type.bold() else Type.medium()
        gravity = Gravity.CENTER
        minimumHeight = ctx.dpi(40)
        setPadding(ctx.dpi(14), 0, ctx.dpi(14), 0)
        setTextColor(if (selected) UiTheme.role(UiTheme.Role.ON_ACCENT) else UiTheme.role(UiTheme.Role.DIM))
        background = Glass.ripple(
            ctx,
            if (selected) Glass.solid(ctx, UiTheme.role(UiTheme.Role.ACCENT), 100)
            else Glass.solid(ctx, UiTheme.role(UiTheme.Role.SURFACE), 100, UiTheme.role(UiTheme.Role.STROKE_SOFT)),
            100
        )
        contentDescription = if (selected) "$label, selected" else label
        setOnClickListener { onClick() }
    }

    private fun filterRow(): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dpi(16), 0, ctx.dpi(16), 0) }
        val items = listOf(
            LibSection.ALL to "All",
            LibSection.BOOKS to "Novels",
            LibSection.DOWNLOADED to "Downloaded",
        )
        items.forEach { (sec, label) ->
            row.addView(filterPill(label, libSection == sec) { libSection = sec; rerender() },
                LinearLayout.LayoutParams(wc, ctx.dpi(40)).also { it.marginEnd = ctx.dpi(8) })
        }
        return hScroll(row)
    }

    private fun libSections(): List<Pair<String, List<LibraryModel.Shelf>>> {
        val q = libQuery.trim()
        fun match(s: LibraryModel.Shelf) = q.isEmpty() || s.name.contains(q, true) ||
            LibraryModel.shelfChapters(ctx, s.name).any { it.label().contains(q, true) }
        fun sorted(l: List<LibraryModel.Shelf>) = when (libSort) {
            LibSort.RECENT -> l.sortedByDescending { it.lastSavedAt }
            LibSort.TITLE -> l.sortedBy { it.name.lowercase() }
            LibSort.CHAPTERS -> l.sortedByDescending { it.chapters }
        }
        val all = shelves.filter { match(it) }
        return listOfNotNull(
            ("Books" to sorted(all)).takeIf { it.second.isNotEmpty() }
        )
    }

    private fun bodyLibrary() {
        val total = shelves.sumOf { it.chapters }
        content.addView(pad(TextView(ctx).apply {
            text = if (shelves.isEmpty()) "No saved books yet." else "${shelves.size} book" + (if (shelves.size == 1) "" else "s") + " · $total chapter" + (if (total == 1) "" else "s")
            textSize = Type.label
            setTextColor(UiTheme.role(UiTheme.Role.DIM))
            setPadding(ctx.dpi(4), ctx.dpi(8), ctx.dpi(4), ctx.dpi(4))
        }), LinearLayout.LayoutParams(mp, wc))
        if (shelves.isEmpty()) {
            content.addView(pad(emptyCard(
                "Nothing saved yet",
                "Translations you save while reading are kept here — with chapter list, export and rename.",
                "Browse sources"
            ) { openTab(HomeTab.SOURCES) }), LinearLayout.LayoutParams(mp, wc))
            return
        }
        content.addView(filterRow(), LinearLayout.LayoutParams(mp, wc))
        // search + sort inside the library
        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dpi(16), ctx.dpi(10), ctx.dpi(16), 0) }
        val field = EditText(ctx).apply {
            hint = "Filter books or chapters"
            setSingleLine()
            setText(libQuery)
            textSize = Type.body
            setTextColor(UiTheme.role(UiTheme.Role.TEXT))
            setHintTextColor(UiTheme.role(UiTheme.Role.FAINT))
            setPadding(ctx.dpi(14), 0, ctx.dpi(14), 0)
            minimumHeight = ctx.dpi(44)
            background = Glass.field(ctx, 100, false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString().orEmpty()
                    if (v == libQuery) return
                    libQuery = v
                    rebuildLibraryBody()
                }
            })
        }
        tools.addView(field, LinearLayout.LayoutParams(0, ctx.dpi(44), 1f).also { it.marginEnd = ctx.dpi(8) })
        val sortLabel = when (libSort) {
            LibSort.RECENT -> "Recent"
            LibSort.TITLE -> "A–Z"
            LibSort.CHAPTERS -> "Chapters"
        }
        tools.addView(Buttons.make(ctx, sortLabel, Buttons.Style.TONAL, Ic.FILTER, 44) {
            libSort = when (libSort) {
                LibSort.RECENT -> LibSort.TITLE
                LibSort.TITLE -> LibSort.CHAPTERS
                LibSort.CHAPTERS -> LibSort.RECENT
            }
            rerender()
        }, LinearLayout.LayoutParams(wc, ctx.dpi(44)))
        content.addView(tools, LinearLayout.LayoutParams(mp, wc))
        libBody = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(libBody!!, LinearLayout.LayoutParams(mp, wc))
        rebuildLibraryBody()
    }

    private var libBody: LinearLayout? = null

    /** Re-renders only the book list while typing (keeps the field focused and the keyboard open). */
    private fun rebuildLibraryBody() {
        val body = libBody ?: return
        body.removeAllViews()
        val q = libQuery.trim()
        if (q.isNotEmpty() && shelves.none { s -> s.name.contains(q, true) || LibraryModel.shelfChapters(ctx, s.name).any { it.label().contains(q, true) } }) {
            body.addView(pad(emptyCard("No match", "No saved book or chapter contains \"$q\".", null, Ic.SEARCH), ctx.dpi(16), ctx.dpi(10)),
                LinearLayout.LayoutParams(mp, wc))
            return
        }
        for ((title, books) in libSections()) {
            body.addView(rise(sectionLabel(title)), LinearLayout.LayoutParams(mp, wc))
            books.forEach { body.addView(pad(rise(shelfCard(it))), LinearLayout.LayoutParams(mp, wc)) }
        }
        if (libSection == LibSection.ALL && shelves.isNotEmpty() && libSections().isEmpty()) {
            body.addView(pad(emptyCard("Nothing here yet", "No book in this section has a saved translation yet.", "See all books", Ic.LIBRARY) {
                libSection = LibSection.ALL; rerender()
            }), LinearLayout.LayoutParams(mp, wc))
        }
    }

    private fun bodyUpdates() {
        val fresh = updates.filter { it.newCount > 0 }
        if (updates.isEmpty()) {
            content.addView(pad(emptyCard(
                "No updates yet",
                "Books appear here once chapters are translated after the chapter you last opened.",
                "Browse sources"
                , Ic.REFRESH_DOT) { openTab(HomeTab.SOURCES) }))
            return
        }
        content.addView(pad(TextView(ctx).apply {
            text = if (fresh.isEmpty()) "Everything is read up to date." else "${fresh.sumOf { it.newCount }} new chapter" +
                (if (fresh.sumOf { it.newCount } == 1) "" else "s") + " across ${fresh.size} book" + (if (fresh.size == 1) "" else "s")
            textSize = Type.label
            setTextColor(UiTheme.role(UiTheme.Role.DIM))
            setPadding(ctx.dpi(4), ctx.dpi(8), ctx.dpi(4), ctx.dpi(4))
        }), LinearLayout.LayoutParams(mp, wc))
        if (fresh.isNotEmpty()) {
            content.addView(rise(sectionLabel("NEW CHAPTERS", "Mark seen", {
                LibraryModel.updates(ctx, markSeen = true)
                refreshFromActivity()
            })), LinearLayout.LayoutParams(mp, wc))
            fresh.forEach { content.addView(pad(rise(updateRow(it))), LinearLayout.LayoutParams(mp, wc)) }
        }
        content.addView(rise(sectionLabel("ALL BOOKS")), LinearLayout.LayoutParams(mp, wc))
        updates.forEach { content.addView(pad(rise(updateRow(it))), LinearLayout.LayoutParams(mp, wc)) }
        content.addView(pad(emptyCard(
            "Full update history",
            "Opens the library manager, where updates are marked as seen.",
            "Open library manager"
            , Ic.CLOCK) { onOpenUpdates() }))
    }

    /** Re-pulls the Library model after a change made here (e.g. mark seen). */
    private var onRefresh: (() -> Unit)? = null
    fun setRefreshHook(f: () -> Unit) { onRefresh = f }
    private fun refreshFromActivity() { onRefresh?.invoke() }

    private fun bodySources() {
        val enabled = sources.filter { LibraryModel.enabled(ctx, it) }
        val off = sources.filter { !LibraryModel.enabled(ctx, it) }
        // ---- extensions summary
        val builtIn = sources.count { it.builtIn }
        val learned = sources.count { !it.builtIn && it.kind.startsWith("Learned") }
        val summary = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(18), ctx.dpi(14), ctx.dpi(18), ctx.dpi(14))
            background = Glass.solid(ctx, UiTheme.role(UiTheme.Role.SURFACE), 18, UiTheme.role(UiTheme.Role.STROKE_SOFT))
            addView(TextView(ctx).apply {
                text = "Extensions & sources"
                textSize = Type.heading
                typeface = Type.bold()
                setTextColor(UiTheme.role(UiTheme.Role.TEXT))
            })
            addView(TextView(ctx).apply {
                text = "$builtIn built-in · $learned learned on this device · ${enabled.size} active"
                textSize = Type.caption
                setTextColor(UiTheme.role(UiTheme.Role.DIM))
                setPadding(0, ctx.dpi(4), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = "Built-in adapters have real extraction code. Other sites read \"Learned on this device\" only after the app has learned how to read them — a source is never shown as working before that."
                textSize = Type.label
                setTextColor(UiTheme.role(UiTheme.Role.DIM))
                setLineSpacing(ctx.dpf(3), 1f)
                setPadding(0, ctx.dpi(8), 0, 0)
            })
        }
        content.addView(pad(summary), LinearLayout.LayoutParams(mp, wc))
        if (enabled.isNotEmpty()) {
            content.addView(rise(sectionLabel("ACTIVE (${enabled.size})")), LinearLayout.LayoutParams(mp, wc))
            enabled.forEach { content.addView(pad(rise(sourceRow(it, true))), LinearLayout.LayoutParams(mp, wc)) }
        }
        if (off.isNotEmpty()) {
            content.addView(rise(sectionLabel("AVAILABLE (${off.size})")), LinearLayout.LayoutParams(mp, wc))
            off.forEach { content.addView(pad(rise(sourceRow(it, false))), LinearLayout.LayoutParams(mp, wc)) }
        }
        content.addView(pad(emptyCard(
            "Source manager",
            "Full list with built-in status, learned details and switches.",
            "Open source manager"
            , Ic.EXTENSIONS) { onOpenSources() }))
    }

    // ---------------------------------------------------------------- home header etc.

    private fun header(): View {
        val hello = TextView(ctx).apply {
            text = "Read, translated."
            textSize = Type.display
            typeface = Type.bold()
            setTextColor(UiTheme.role(UiTheme.Role.TEXT))
            letterSpacing = -0.02f
        }
        val newBooks = updates.count { it.newCount > 0 }
        val sub = TextView(ctx).apply {
            text = when {
                newBooks > 0 -> "$newBooks book" + (if (newBooks == 1) "" else "s") + " with new chapters"
                shelves.isNotEmpty() -> "Pick up where you left off"
                recents.isNotEmpty() -> "Continue reading, or start something new"
                else -> "Novels, webtoons and comics — with AI translation"
            }
            textSize = Type.label
            setTextColor(UiTheme.role(UiTheme.Role.DIM))
            setPadding(0, ctx.dpi(4), 0, 0)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(10), ctx.dpi(20), ctx.dpi(8))
            addView(rise(hello))
            addView(rise(sub))
        }
    }

    private fun libraryCards(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(14), ctx.dpi(6), ctx.dpi(14), ctx.dpi(4))
        }
        fun cardView(ic: Ic, title: String, sub: String, accent: Boolean, onClick: () -> Unit): View {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dpi(14), ctx.dpi(13), ctx.dpi(14), ctx.dpi(13))
                background = Glass.ripple(ctx, Glass.solid(ctx, UiTheme.role(UiTheme.Role.SURFACE), 18, UiTheme.role(UiTheme.Role.STROKE_SOFT)), 18)
                contentDescription = "$title, $sub"
                minimumHeight = ctx.dpi(100)
                addView(FrameLayout(ctx).apply {
                    background = Glass.oval(UiTheme.withAlpha(if (accent) UiTheme.role(UiTheme.Role.OK) else UiTheme.role(UiTheme.Role.ACCENT), 0x1F))
                    addView(ImageView(ctx).apply {
                        setImageDrawable(IconDrawable(ic, if (accent) UiTheme.role(UiTheme.Role.OK) else UiTheme.role(UiTheme.Role.ACCENT)))
                    }, LayoutParams(ctx.dpi(17), ctx.dpi(17), Gravity.CENTER))
                }, LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)))
                addView(TextView(ctx).apply {
                    text = sub
                    textSize = Type.heading
                    typeface = Type.bold()
                    setTextColor(if (accent) UiTheme.role(UiTheme.Role.OK) else UiTheme.role(UiTheme.Role.TEXT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(mp, wc).also { it.topMargin = ctx.dpi(10) })
                addView(TextView(ctx).apply {
                    text = title
                    textSize = Type.overline
                    setTextColor(UiTheme.role(UiTheme.Role.FAINT))
                })
                setOnClickListener { onClick() }
            }
        }
        row.addView(cardView(Ic.LIBRARY, "Library", shelves.size.toString() + " book" + (if (shelves.size == 1) "" else "s"), false) { openTab(HomeTab.LIBRARY) },
            LinearLayout.LayoutParams(0, ctx.dpi(104), 1f).also { it.marginEnd = ctx.dpi(10) })
        val newBooks = updates.count { it.newCount > 0 }
        row.addView(cardView(Ic.REFRESH_DOT, "Updates", if (newBooks > 0) "$newBooks new" else "Up to date", newBooks > 0) { openTab(HomeTab.UPDATES) },
            LinearLayout.LayoutParams(0, ctx.dpi(104), 1f))
        return row
    }

    private fun updatesBanner(u: LibraryModel.Update): View {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Covers.view(ctx, u.novel, 34, 34, 17, 15f), LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "${u.newCount} new chapter" + (if (u.newCount == 1) "" else "s")
                    textSize = Type.label
                    typeface = Type.medium()
                    setTextColor(UiTheme.role(UiTheme.Role.TEXT))
                })
                addView(TextView(ctx).apply {
                    text = u.novel
                    textSize = Type.overline
                    setTextColor(UiTheme.role(UiTheme.Role.OK))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.CHEVRON, UiTheme.role(UiTheme.Role.DIM))) },
                LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = ctx.dpi(Touch.minCompact)
            setPadding(ctx.dpi(14), ctx.dpi(12), ctx.dpi(14), ctx.dpi(12))
            background = Glass.ripple(ctx, Glass.solid(ctx, UiTheme.withAlpha(UiTheme.role(UiTheme.Role.OK), 0x12), 18,
                UiTheme.withAlpha(UiTheme.role(UiTheme.Role.OK), 0x3D)), 18)
            addView(inner, LinearLayout.LayoutParams(0, wc, 1f))
            pressEffect()
            setOnClickListener { openTab(HomeTab.UPDATES) }
        }
    }

    private fun recentRow(r: HomeRecent): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dpi(Touch.minCompact)
            setPadding(ctx.dpi(20), ctx.dpi(8), ctx.dpi(16), ctx.dpi(8))
            contentDescription = r.title
            addView(Covers.view(ctx, r.title, 34, 34, 17, 15f), LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)).also { it.marginEnd = ctx.dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = r.title
                    textSize = Type.body
                    typeface = Type.medium()
                    setTextColor(UiTheme.role(UiTheme.Role.TEXT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = listOfNotNull(r.site, Covers.ago(r.timeMs).ifBlank { null }).joinToString(" · ")
                    textSize = Type.caption
                    setTextColor(UiTheme.role(UiTheme.Role.FAINT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ctx.dpi(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wc, 1f))
            addView(ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.CHEVRON, UiTheme.role(UiTheme.Role.FAINT))) },
                LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)))
            clickable { onOpen(r.url) }
        }
    }

    private fun hubRow(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(10))
        fun part(name: String, ic: Ic, p: Hub.Part) {
            val b = Buttons.make(ctx, name, Buttons.Style.TONAL, ic, 46) { onHub(p) }
            addView(b, LinearLayout.LayoutParams(0, ctx.dpi(46), 1f).also { it.marginEnd = ctx.dpi(8) })
        }
        part("Webtoon hub", Ic.LAYERS, Hub.Part.WEBTOON)
        part("Novel hub", Ic.BOOK, Hub.Part.NOVEL)
    }
}
