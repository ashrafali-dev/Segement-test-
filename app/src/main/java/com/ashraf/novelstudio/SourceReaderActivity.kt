package com.ashraf.novelstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Clean text reader for chapters successfully extracted by a built-in source adapter.
 * The original browser page stays alive in MainActivity. Translation requests are sent through
 * ReaderViewBridge to MainActivity's existing chatbot pipeline and returned here.
 */
internal class SourceReaderActivity : Activity(), SourceReaderListener {
    companion object {
        const val EXTRA_TITLE = "source_reader_title"
        const val EXTRA_TEXT = "source_reader_text"
        const val EXTRA_URL = "source_reader_url"
        const val EXTRA_NOVEL = "source_reader_novel"
        const val EXTRA_NUMBER = "source_reader_number"
        const val EXTRA_NEXT = "source_reader_next"
        const val EXTRA_PREV = "source_reader_prev"
        const val EXTRA_CONTENT_SELECTOR = "source_reader_content_selector"
        const val EXTRA_TITLE_SELECTOR = "source_reader_title_selector"
        const val EXTRA_NEXT_SELECTOR = "source_reader_next_selector"
        const val EXTRA_PREV_SELECTOR = "source_reader_prev_selector"
    }

    override val sourceUrl: String
        get() = chapter.url

    private lateinit var chapter: Chapter
    private lateinit var root: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var translateButton: Button
    private lateinit var sourceButton: Button
    private lateinit var progress: ProgressBar

    private var sourceText = ""
    private var translatedText: String? = null
    private var showingTranslation = false
    private var translating = false
    private var fontSize = 18f
    private var darkTheme = true

    private val bgDark = 0xFF101116.toInt()
    private val surfaceDark = 0xFF1A1C24.toInt()
    private val textDark = 0xFFE7E8EF.toInt()
    private val mutedDark = 0xFF9AA0B2.toInt()
    private val accent = 0xFF9A83FF.toInt()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chapter = Chapter(
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            next = intent.getStringExtra(EXTRA_NEXT)?.takeIf { it.isNotBlank() },
            prev = intent.getStringExtra(EXTRA_PREV)?.takeIf { it.isNotBlank() },
            url = intent.getStringExtra(EXTRA_URL).orEmpty(),
            novel = intent.getStringExtra(EXTRA_NOVEL).orEmpty(),
            number = intent.getStringExtra(EXTRA_NUMBER).orEmpty(),
            contentSel = intent.getStringExtra(EXTRA_CONTENT_SELECTOR).orEmpty(),
            titleSel = intent.getStringExtra(EXTRA_TITLE_SELECTOR).orEmpty(),
            nextSel = intent.getStringExtra(EXTRA_NEXT_SELECTOR).orEmpty(),
            prevSel = intent.getStringExtra(EXTRA_PREV_SELECTOR).orEmpty()
        )
        if (chapter.url.isBlank() || chapter.text.isBlank()) {
            finish()
            return
        }

        sourceText = chapter.text
        fontSize = Prefs.get(this, "sourceReaderFontSize", "18").toFloatOrNull()?.coerceIn(13f, 32f) ?: 18f
        translatedText = intent.getStringExtra("source_reader_translation")?.takeIf { it.isNotBlank() }
        if (translatedText == null) {
            val saved = Store.find(this, chapter)
            val raw = saved?.let { Store.readRaw(this, it.id) }.orEmpty()
            cleanTranslation(raw).takeIf { it.length >= 30 }?.let { translatedText = it }
        }
        showingTranslation = translatedText != null
        ReaderViewBridge.attachReader(this)
        buildUi()
        renderText()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (darkTheme) bgDark else 0xFFF8F5EE.toInt())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setBackgroundColor(if (darkTheme) surfaceDark else 0xFFFFFFFF.toInt())
        }
        val back = button("‹") {
            finish()
        }.apply { textSize = 28f; minWidth = dp(42) }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2
        }
        subtitleView = TextView(this).apply { textSize = 11f }
        heading.addView(titleView)
        heading.addView(subtitleView)
        top.addView(back)
        top.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val originalTop = button("Web") { finish() }.apply { textSize = 12f; minWidth = dp(54) }
        top.addView(originalTop)
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        bodyView = TextView(this).apply {
            textSize = fontSize
            setLineSpacing(dp(3).toFloat(), 1.35f)
            setPadding(dp(22), dp(22), dp(22), dp(32))
            setTextIsSelectable(true)
        }
        scrollView.addView(bodyView, ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setBackgroundColor(if (darkTheme) surfaceDark else 0xFFFFFFFF.toInt())
        }
        controls.addView(button("A−") { setFont(fontSize - 1f) })
        controls.addView(button("A+") { setFont(fontSize + 1f) })
        sourceButton = button(if (showingTranslation) "Original" else "Source") { toggleSource() }
        controls.addView(sourceButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        translateButton = button(if (showingTranslation) "Translated ✓" else "Translate") { requestTranslation() }
        controls.addView(translateButton, LinearLayout.LayoutParams(0, dp(44), 1.35f))
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        applyTheme()
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minHeight = dp(42)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { action() }
        }

    private fun setFont(size: Float) {
        fontSize = size.coerceIn(13f, 32f)
        Prefs.put(this, "sourceReaderFontSize", fontSize.toString())
        bodyView.textSize = fontSize
    }

    private fun renderText() {
        titleView.text = chapter.title.ifBlank { chapter.novel }
        val sourceName = runCatching { android.net.Uri.parse(chapter.url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        subtitleView.text = listOf(chapter.novel, chapter.number.takeIf { it.isNotBlank() }?.let { "Chapter $it" }, sourceName)
            .filter { !it.isNullOrBlank() }.joinToString(" · ")
        bodyView.text = if (showingTranslation) translatedText.orEmpty() else sourceText
        sourceButton.text = if (showingTranslation) "Original" else "Source"
        translateButton.text = when {
            translating -> "Translating…"
            showingTranslation -> "Translated ✓"
            else -> "Translate"
        }
        translateButton.isEnabled = !translating
        scrollView.post { scrollView.scrollTo(0, 0) }
        applyTheme()
    }

    private fun applyTheme() {
        root.setBackgroundColor(if (darkTheme) bgDark else 0xFFF8F5EE.toInt())
        val fg = if (darkTheme) textDark else 0xFF25211C.toInt()
        val muted = if (darkTheme) mutedDark else 0xFF756E63.toInt()
        val surface = if (darkTheme) surfaceDark else 0xFFFFFFFF.toInt()
        bodyView.setTextColor(fg)
        titleView.setTextColor(fg)
        subtitleView.setTextColor(muted)
    }

    private fun toggleSource() {
        if (translating) return
        if (translatedText == null) {
            Toast.makeText(this, "Translate this chapter first to switch versions", Toast.LENGTH_SHORT).show()
            return
        }
        showingTranslation = !showingTranslation
        renderText()
    }

    private fun requestTranslation() {
        if (translating) return
        translatedText?.let {
            showingTranslation = true
            renderText()
            return
        }
        translating = true
        renderText()
        if (!ReaderViewBridge.requestTranslation(chapter)) {
            translating = false
            renderText()
            AlertDialog.Builder(this)
                .setMessage("The browser session is no longer available. Return to the source page and open Reader View again.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onTranslationStarted(sourceUrl: String) {
        if (sameSource(sourceUrl)) {
            translating = true
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            renderText()
        }
    }

    override fun onTranslationCompleted(sourceUrl: String, translatedText: String) {
        if (!sameSource(sourceUrl)) return
        this.translatedText = translatedText
        showingTranslation = true
        translating = false
        progress.visibility = View.GONE
        renderText()
        Toast.makeText(this, "Translation ready", Toast.LENGTH_SHORT).show()
    }

    override fun onTranslationFailed(sourceUrl: String, message: String) {
        if (!sameSource(sourceUrl)) return
        translating = false
        progress.visibility = View.GONE
        renderText()
        Toast.makeText(this, message.take(160), Toast.LENGTH_LONG).show()
    }

    private fun sameSource(url: String): Boolean =
        url.trim().substringBefore('#') == chapter.url.trim().substringBefore('#')

    private fun cleanTranslation(raw: String): String =
        raw.replace(Regex("(?m)^\\[\\d{3,}\\]\\s*"), "").trim()

    override fun onDestroy() {
        ReaderViewBridge.detachReader(this)
        super.onDestroy()
    }
}
