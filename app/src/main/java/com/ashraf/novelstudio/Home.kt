package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
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

// ============================================================================ home screen

/**
 * Full-screen home: a Continue-reading card and recent pages for returning users, site tiles for new users,
 * and a "search or paste a link" bar pinned at the bottom. It swallows touches so the browser below stays idle.
 */
class HomeView(private val ctx: Context) : FrameLayout(ctx) {
    var onOpen: (String) -> Unit = {}
    var onSearch: (String) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onHub: (Hub.Part) -> Unit = {}

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT
    private val bold: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val scroll = ScrollView(ctx)
    private val content = LinearLayout(ctx)
    private val input = EditText(ctx)
    private val searchIcon = ImageView(ctx)
    private var iconIsUrl = false
    private var token = 0
    private var riseIndex = 0

    private val palette = intArrayOf(
        0xFF7F77DD.toInt(), 0xFF1D9E75.toInt(), 0xFFD4537E.toInt(), 0xFFBA7517.toInt(),
        0xFF378ADD.toInt(), 0xFF639922.toInt(), 0xFFD85A30.toInt(), 0xFF534AB7.toInt()
    )

    init {
        isClickable = true
        content.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.setPadding(0, 0, 0, ctx.dpi(92))
        scroll.addView(content, ViewGroup.LayoutParams(mp, wc))
        addView(scroll, FrameLayout.LayoutParams(mp, mp))
        addView(
            buildSearchBar(),
            FrameLayout.LayoutParams(mp, ctx.dpi(54)).also {
                it.gravity = Gravity.BOTTOM
                it.setMargins(ctx.dpi(12), 0, ctx.dpi(12), ctx.dpi(14))
            }
        )
    }

    // ---------------------------------------------------------------- search bar

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http") || (!t.contains(' ') && t.contains('.'))
    }

    private fun refreshIcon(text: String) {
        val url = looksLikeUrl(text)
        if (url == iconIsUrl) return
        iconIsUrl = url
        searchIcon.setImageDrawable(IconDrawable(if (url) Ic.GLOBE else Ic.SEARCH, UiTheme.faint))
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        hideKeyboard()
        input.setText("")
        onSearch(t)
    }

    fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        input.clearFocus()
    }

    private fun buildSearchBar(): View {
        searchIcon.setImageDrawable(IconDrawable(Ic.SEARCH, UiTheme.faint))
        input.apply {
            hint = "Search or paste a link"
            setHintTextColor(UiTheme.faint)
            setTextColor(UiTheme.text)
            textSize = 15f
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
        }
        val go = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Ic.FORWARD, Color.WHITE))
            setPadding(ctx.dpi(11), ctx.dpi(11), ctx.dpi(11), ctx.dpi(11))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(UiTheme.accentA, UiTheme.accentB)).apply {
                shape = GradientDrawable.OVAL
            }
            isClickable = true
            contentDescription = "Go"
            setOnClickListener { submit() }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(16), 0, ctx.dpi(6), 0)
            background = Glass.solid(ctx, UiTheme.sheet, 27, UiTheme.stroke)
            elevation = ctx.dpf(10)
            addView(searchIcon, LinearLayout.LayoutParams(ctx.dpi(20), ctx.dpi(20)).also { it.marginEnd = ctx.dpi(10) })
            addView(input, LinearLayout.LayoutParams(0, wc, 1f))
            addView(go, LinearLayout.LayoutParams(ctx.dpi(42), ctx.dpi(42)))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun colorFor(s: String): Int = palette[Math.abs(s.hashCode() % palette.size)]

    private fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

    private fun ago(ms: Long): String {
        if (ms <= 0L) return ""
        val m = (System.currentTimeMillis() - ms) / 60000L
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 48 * 60 -> "Yesterday"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    private fun <T : View> rise(v: T): T {
        v.alpha = 0f
        v.translationY = ctx.dpf(12)
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(riseIndex++ * 55L)
            .setDuration(380L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return v
    }

    private fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnClickListener { onClick() }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
            false
        }
    }

    private fun label(t: String): TextView = TextView(ctx).apply {
        text = t
        textSize = 12f
        letterSpacing = 0.04f
        setTextColor(UiTheme.faint)
        typeface = bold
        setPadding(ctx.dpi(18), ctx.dpi(16), ctx.dpi(18), ctx.dpi(8))
    }

    /** Website icon (fetched favicon on a light tile); until it arrives, a coloured tile with the site's initial. */
    private fun siteIcon(site: HomeSite, sizeDp: Int, radiusDp: Int, textSp: Float): View {
        val size = ctx.dpi(sizeDp)
        val frame = FrameLayout(ctx)
        val base = colorFor(site.name)
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(base, ModeUi.lerp(base, Color.BLACK, 0.3f))
        ).apply { cornerRadius = ctx.dpf(radiusDp) }
        val letter = TextView(ctx).apply {
            text = site.name.take(1).uppercase()
            setTextColor(Color.WHITE)
            textSize = textSp
            typeface = bold
            gravity = Gravity.CENTER
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            val p = (size * 0.2f).toInt()
            setPadding(p, p, p, p)
        }
        frame.addView(letter, FrameLayout.LayoutParams(mp, mp))
        frame.addView(img, FrameLayout.LayoutParams(mp, mp))
        val mine = token
        val host = hostOf(site.url)
        if (host != null) {
            Favicons.load(ctx, host) { bmp ->
                if (bmp != null && mine == token) {
                    frame.background = GradientDrawable().apply {
                        setColor(0xFFF4F6FB.toInt())
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

    // ---------------------------------------------------------------- pieces

    private fun header(): View {
        val title = TextView(ctx).apply {
            text = "Novel Studio"
            textSize = 22f
            typeface = bold
            setTextColor(UiTheme.text)
        }
        val menu = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Ic.DOTS, UiTheme.iconIdle))
            setPadding(ctx.dpi(10), ctx.dpi(10), ctx.dpi(10), ctx.dpi(10))
            background = Glass.solid(ctx, 0x14FFFFFF, 20, UiTheme.strokeSoft)
            contentDescription = "Menu"
        }
        pressable(menu) { onMenu() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(18), ctx.dpi(14), ctx.dpi(14), ctx.dpi(4))
            addView(title, LinearLayout.LayoutParams(0, wc, 1f))
            addView(menu, LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(40)))
        }
    }

    private fun welcome(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dpi(18), ctx.dpi(10), ctx.dpi(18), ctx.dpi(2))
        addView(TextView(ctx).apply {
            text = "Welcome"
            textSize = 24f
            typeface = bold
            setTextColor(UiTheme.text)
        })
        addView(TextView(ctx).apply {
            text = "Pick a site to start reading, or search below."
            textSize = 14f
            setTextColor(UiTheme.dim)
            setPadding(0, ctx.dpi(4), 0, 0)
        })
    }

    private fun tile(site: HomeSite): View {
        val name = TextView(ctx).apply {
            text = site.name
            textSize = 12f
            setTextColor(UiTheme.dim)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.dpi(2), ctx.dpi(7), ctx.dpi(2), 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(6))
            contentDescription = site.name
            addView(siteIcon(site, 54, 17, 21f), LinearLayout.LayoutParams(ctx.dpi(54), ctx.dpi(54)))
            addView(name, LinearLayout.LayoutParams(mp, wc))
        }
        pressable(box) { onOpen(site.url) }
        return box
    }

    private fun grid(sites: List<HomeSite>): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
        }
        sites.chunked(3).forEach { rowSites ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowSites.forEach { row.addView(tile(it), LinearLayout.LayoutParams(0, wc, 1f)) }
            repeat(3 - rowSites.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            col.addView(row, LinearLayout.LayoutParams(mp, wc))
        }
        return col
    }

    private fun chip(site: HomeSite): View {
        val name = TextView(ctx).apply {
            text = site.name
            textSize = 13f
            setTextColor(UiTheme.text)
            maxLines = 1
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(6), ctx.dpi(6), ctx.dpi(12), ctx.dpi(6))
            background = Glass.solid(ctx, 0x14FFFFFF, 20, UiTheme.strokeSoft)
            contentDescription = site.name
            addView(siteIcon(site, 24, 8, 12f), LinearLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24)).also { it.marginEnd = ctx.dpi(8) })
            addView(name)
        }
        pressable(box) { onOpen(site.url) }
        return box
    }

    private fun chips(sites: List<HomeSite>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(14), 0, ctx.dpi(14), ctx.dpi(4))
        }
        sites.forEach { row.addView(chip(it), LinearLayout.LayoutParams(wc, wc).also { lp -> lp.marginEnd = ctx.dpi(8) }) }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(row)
        }
    }

    private fun cover(title: String, wDp: Int, hDp: Int, radiusDp: Int, textSp: Float): View {
        val base = colorFor(title)
        return TextView(ctx).apply {
            text = title.trim().take(1).uppercase()
            textSize = textSp
            typeface = bold
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(base, ModeUi.lerp(base, Color.BLACK, 0.3f))
            ).apply { cornerRadius = ctx.dpf(radiusDp) }
            layoutParams = LinearLayout.LayoutParams(ctx.dpi(wDp), ctx.dpi(hDp))
        }
    }

    private fun meta(r: HomeRecent): String {
        val t = ago(r.timeMs)
        return if (t.isEmpty()) r.site else "${r.site} \u00B7 $t"
    }

    private fun hero(r: HomeRecent): View {
        val tag = TextView(ctx).apply {
            text = "Continue reading"
            textSize = 11f
            setTextColor(UiTheme.faint)
        }
        val title = TextView(ctx).apply {
            text = r.title
            textSize = 16f
            typeface = bold
            setTextColor(UiTheme.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ctx.dpi(3), 0, ctx.dpi(2))
        }
        val sub = TextView(ctx).apply {
            text = meta(r)
            textSize = 12f
            setTextColor(UiTheme.dim)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val base = colorFor(r.title)
        val btn = TextView(ctx).apply {
            text = "Continue  \u203A"
            textSize = 13f
            typeface = bold
            setTextColor(Color.WHITE)
            setPadding(ctx.dpi(14), ctx.dpi(7), ctx.dpi(14), ctx.dpi(7))
            background = GradientDrawable().apply {
                setColor(base)
                cornerRadius = ctx.dpf(18)
            }
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(tag)
            addView(title)
            addView(sub)
            addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
            addView(btn, LinearLayout.LayoutParams(wc, wc))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(14), ctx.dpi(14), ctx.dpi(14), ctx.dpi(14))
            background = Glass.solid(ctx, 0x14FFFFFF, 18, UiTheme.strokeSoft)
            addView(cover(r.title, 68, 90, 12, 30f), LinearLayout.LayoutParams(ctx.dpi(68), ctx.dpi(90)).also { it.marginEnd = ctx.dpi(14) })
            addView(info, LinearLayout.LayoutParams(0, ctx.dpi(90), 1f))
        }
        pressable(card) { onOpen(r.url) }
        return card
    }

    private fun recentRow(r: HomeRecent): View {
        val title = TextView(ctx).apply {
            text = r.title
            textSize = 14f
            typeface = bold
            setTextColor(UiTheme.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            text = meta(r)
            textSize = 12f
            setTextColor(UiTheme.faint)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(sub)
        }
        val chev = ImageView(ctx).apply { setImageDrawable(IconDrawable(Ic.CHEVRON, UiTheme.faint)) }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(18), ctx.dpi(10), ctx.dpi(14), ctx.dpi(10))
            addView(cover(r.title, 40, 40, 12, 17f), LinearLayout.LayoutParams(ctx.dpi(40), ctx.dpi(40)).also { it.marginEnd = ctx.dpi(12) })
            addView(texts, LinearLayout.LayoutParams(0, wc, 1f))
            addView(chev, LinearLayout.LayoutParams(ctx.dpi(18), ctx.dpi(18)))
        }
        pressable(row) { onOpen(r.url) }
        return row
    }

    // ---------------------------------------------------------------- hub (Webtoon | Novel)

    private fun hubButton(part: Hub.Part, primary: Boolean): View {
        val t = TextView(ctx).apply {
            text = part.title
            textSize = 15f
            typeface = bold
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else UiTheme.text)
            background = if (primary)
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(UiTheme.accentA, UiTheme.accentB)).apply { cornerRadius = ctx.dpf(22) }
            else Glass.solid(ctx, UiTheme.sheet, 22, UiTheme.stroke)
        }
        pressable(t) { onHub(part) }
        return t
    }

    private fun hubBar(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.dpi(14), ctx.dpi(6), ctx.dpi(14), ctx.dpi(4))
        addView(hubButton(Hub.Part.WEBTOON, false), LinearLayout.LayoutParams(0, ctx.dpi(44), 1f).also { it.marginEnd = ctx.dpi(8) })
        addView(hubButton(Hub.Part.NOVEL, false), LinearLayout.LayoutParams(0, ctx.dpi(44), 1f))
    }

    // ---------------------------------------------------------------- public

    fun render(recents: List<HomeRecent>, novelSites: List<HomeSite>, comicSites: List<HomeSite>) {
        token++
        riseIndex = 0
        content.removeAllViews()
        content.addView(header(), LinearLayout.LayoutParams(mp, wc))
        content.addView(hubBar(), LinearLayout.LayoutParams(mp, wc))
        if (recents.isEmpty()) {
            content.addView(rise(welcome()), LinearLayout.LayoutParams(mp, wc))
            content.addView(rise(label("Novel sites")), LinearLayout.LayoutParams(mp, wc))
            content.addView(rise(grid(novelSites)), LinearLayout.LayoutParams(mp, wc))
            if (comicSites.isNotEmpty()) {
                content.addView(rise(label("Webtoons and comics")), LinearLayout.LayoutParams(mp, wc))
                content.addView(rise(grid(comicSites)), LinearLayout.LayoutParams(mp, wc))
            }
        } else {
            content.addView(
                rise(hero(recents.first())),
                LinearLayout.LayoutParams(mp, wc).also { it.setMargins(ctx.dpi(14), ctx.dpi(8), ctx.dpi(14), ctx.dpi(4)) }
            )
            if (recents.size > 1) {
                content.addView(rise(label("Recently read")), LinearLayout.LayoutParams(mp, wc))
                recents.drop(1).forEach { content.addView(rise(recentRow(it)), LinearLayout.LayoutParams(mp, wc)) }
            }
            content.addView(rise(label("Sites")), LinearLayout.LayoutParams(mp, wc))
            content.addView(rise(chips(novelSites + comicSites)), LinearLayout.LayoutParams(mp, wc))
        }
        content.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dpi(14)))
        scroll.scrollTo(0, 0)
    }
}
