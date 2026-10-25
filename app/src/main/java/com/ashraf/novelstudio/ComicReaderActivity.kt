package com.ashraf.novelstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Clean image-only comic reader. OCR and chatbot requests run through MainActivity's existing engines. */
internal class ComicReaderActivity : Activity(), ComicSourceReaderListener {
    companion object {
        const val EXTRA_CHAPTER = "comic_reader_chapter_json"
    }

    private lateinit var chapter: ImageChapter
    private lateinit var root: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var translateButton: Button
    private lateinit var originalButton: Button
    private lateinit var modeButton: Button

    private var selectedMode = "scanning"
    private var readerReady = false
    private var realtimeScrollPending = false
    private val realtimeHandler = Handler(Looper.getMainLooper())
    private val realtimeScanRunnable = Runnable {
        if (selectedMode == "realtime" && readerReady) {
            if (busy) realtimeScrollPending = true else startTranslation()
        }
    }

    private var busy = false
    private var showingTranslation = false
    private var lastRawTranslation = ""
    private var lastLayout: WebModeLayout? = null
    private var lastAligned: List<String?>? = null

    override val sourceUrl: String
        get() = chapter.url

    override val sourceWebView: WebView
        get() = webView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_CHAPTER).orEmpty()
        chapter = ImageChapter.fromJson(raw) ?: run {
            finish()
            return
        }
        selectedMode = normalizeMode(ReaderViewBridge.currentReadModeKey())
        ReaderViewBridge.attachComicReader(this)
        buildUi()
        refreshModeButton()
        showChapter()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101116.toInt())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF1A1C24.toInt())
        }
        val back = button("‹") { finish() }.apply {
            textSize = 26f
            minWidth = dp(42)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE7E8EF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            maxLines = 2
        }
        statusView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF9AA0B2.toInt())
            maxLines = 1
        }
        heading.addView(titleView)
        heading.addView(statusView)
        top.addView(back)
        top.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeButton = button("Mode: Scan") { showTranslationModeDialog() }.apply {
            textSize = 11f
            minWidth = dp(82)
        }
        top.addView(modeButton)
        top.addView(button("Source") { finish() }.apply { textSize = 12f; minWidth = dp(54) })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(UiTheme.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x223A4050)
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        webView = WebView(this).apply {
            setBackgroundColor(0xFF101116.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    readerReady = true
                    if (selectedMode == "realtime") scheduleRealtimeScan(800L)
                }
            }
        }
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY && readerReady && selectedMode == "realtime") {
                scheduleRealtimeScan(500L)
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setBackgroundColor(0xFF1A1C24.toInt())
        }
        originalButton = button("Original") { toggleTranslation() }
        translateButton = button("OCR + Translate") { startTranslation() }
        bottom.addView(originalButton, LinearLayout.LayoutParams(0, dp(46), 0.8f))
        bottom.addView(translateButton, LinearLayout.LayoutParams(0, dp(46), 1.4f))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
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

    private fun normalizeMode(mode: String): String = when (mode.trim().lowercase()) {
        "novel" -> "novel"
        "realtime", "real-time", "rt" -> "realtime"
        else -> "scanning"
    }

    private fun modeDescription(): String = when (selectedMode) {
        "novel" -> "Novel mode"
        "realtime" -> "Real-time · visible page"
        else -> "Scanning · full chapter"
    }

    private fun refreshModeButton() {
        if (!::modeButton.isInitialized) return
        modeButton.text = when (selectedMode) {
            "novel" -> "Mode: Novel"
            "realtime" -> "Mode: Real-time"
            else -> "Mode: Scan"
        }
        modeButton.contentDescription = "Translation mode: ${modeDescription()}"
    }

    private fun showTranslationModeDialog() {
        val keys = arrayOf("novel", "scanning", "realtime")
        val labels = arrayOf(
            "Novel — OCR chapter, use Novel prompt",
            "WebToon Scanning — OCR the full chapter",
            "WebToon Real-time — OCR visible page automatically while scrolling"
        )
        AlertDialog.Builder(this)
            .setTitle("Translation mode")
            .setSingleChoiceItems(labels, keys.indexOf(selectedMode).coerceAtLeast(0)) { dialog, which ->
                selectedMode = keys[which]
                val applied = ReaderViewBridge.selectReadMode(selectedMode)
                refreshModeButton()
                statusView.text = "${chapter.pageUrls.size} pages · ${modeDescription()}"
                if (selectedMode == "realtime" && readerReady) {
                    scheduleRealtimeScan(350L)
                } else {
                    realtimeHandler.removeCallbacks(realtimeScanRunnable)
                    realtimeScrollPending = false
                }
                if (!applied) {
                    Toast.makeText(this, "Could not update the app reading mode.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleRealtimeScan(delayMs: Long) {
        if (selectedMode != "realtime" || !readerReady || isFinishing || isDestroyed) return
        realtimeHandler.removeCallbacks(realtimeScanRunnable)
        realtimeHandler.postDelayed(realtimeScanRunnable, delayMs.coerceAtLeast(150L))
    }

    private fun resumeRealtimeIfPending() {
        if (selectedMode == "realtime" && realtimeScrollPending) {
            realtimeScrollPending = false
            scheduleRealtimeScan(450L)
        }
    }

    private fun showChapter() {
        titleView.text = chapter.title
        statusView.text = buildList {
            add(chapter.novel)
            if (chapter.number.isNotBlank()) add("Episode ${chapter.number}")
            add("${chapter.pageUrls.size} pages")
            add(modeDescription())
        }.joinToString(" · ")
        webView.loadDataWithBaseURL(
            chapter.url,
            readerHtml(chapter),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun readerHtml(item: ImageChapter): String {
        val pages = item.pageUrls.joinToString("\n") { url ->
            "<img class=\"ns-comic-page\" src=\"${escapeHtml(url)}\" alt=\"\" loading=\"lazy\" draggable=\"false\">"
        }
        return """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=4">
<meta name="color-scheme" content="dark">
<style>
html,body{margin:0;padding:0;background:#101116;color:#e7e8ef}
body{font-family:Arial,sans-serif}
#ns-comic-reader{width:100%;max-width:1100px;margin:0 auto;padding:0 0 24px;box-sizing:border-box}
.ns-comic-page{display:block;width:100%;height:auto;max-width:100%;margin:0 auto 4px;object-fit:contain;user-select:none;-webkit-user-drag:none}
</style>
<script>
window.__nsSourceUrl=${JSONObject.quote(item.url)};
window.__nsSourceTitle=${JSONObject.quote(item.title)};
window.__nsSourceNovel=${JSONObject.quote(item.novel)};
window.__nsSourceNumber=${JSONObject.quote(item.number)};
window.__nsSourceNext=${JSONObject.quote(item.next.orEmpty())};
window.__nsSourcePrev=${JSONObject.quote(item.prev.orEmpty())};
</script>
</head>
<body><main id="ns-comic-reader">$pages</main></body>
</html>
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun startTranslation() {
        if (busy) return
        val visibleOnly = selectedMode == "realtime"
        busy = true
        translateButton.isEnabled = false
        modeButton.isEnabled = false
        translateButton.text = if (visibleOnly) "Scanning visible page…" else "Preparing OCR…"
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusView.text = if (visibleOnly) "Detecting text on the visible page…" else "Scanning chapter pages…"
        if (!ReaderViewBridge.requestComicTranslation(webView, chapter, visibleOnly)) {
            busy = false
            translateButton.isEnabled = true
            modeButton.isEnabled = true
            translateButton.text = "Retry OCR + Translate"
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            statusView.text = "${chapter.pageUrls.size} pages · ${modeDescription()}"
            Toast.makeText(this, "Could not start chapter OCR. Reopen the chapter and try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleTranslation() {
        if (busy) return
        if (lastRawTranslation.isBlank()) {
            Toast.makeText(this, "Translate the chapter first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (showingTranslation) {
            showingTranslation = false
            webView.evaluateJavascript(
                "(function(){var o=document.getElementById('__ns_wm_overlay');if(o)o.remove();window.__nsShown=0;return 'ok';})()",
                null
            )
            originalButton.text = "View translation"
        } else {
            applyOverlay()
        }
    }

    private fun applyOverlay() {
        val layout = lastLayout ?: WebModeLayoutStore.getScan(chapter.url)
        val aligned = lastAligned ?: layout?.let { WebModeLayout.align(lastRawTranslation, it) }
        if (layout == null || aligned == null || aligned.none { !it.isNullOrBlank() }) {
            Toast.makeText(this, "Could not match translation to the comic bubbles.", Toast.LENGTH_LONG).show()
            return
        }
        lastLayout = layout
        lastAligned = aligned
        val script = WebModeWriter.bubbleScript(
            layout,
            aligned,
            Prefs.fontFile(this),
            chapter.url.substringBefore('#')
        )
        webView.evaluateJavascript(script) { raw ->
            if (raw != null && raw.contains("ok")) {
                showingTranslation = true
                originalButton.text = "Original"
            } else {
                Toast.makeText(this, "Translation could not be placed on the page.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onOcrProgress(sourceUrl: String, stage: String, percent: Int) {
        if (!sameSource(sourceUrl)) return
        busy = true
        progressBar.isIndeterminate = false
        progressBar.progress = percent.coerceIn(0, 100)
        progressBar.visibility = View.VISIBLE
        translateButton.text = if (stage.contains("chatbot", true) || stage.contains("translation", true)) {
            "Translating…"
        } else {
            "OCR ${percent.coerceIn(0, 100)}%"
        }
        modeButton.isEnabled = false
        statusView.text = stage.take(90)
    }

    override fun onImageTranslationStarted(sourceUrl: String) {
        if (!sameSource(sourceUrl)) return
        busy = true
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        translateButton.text = "Translating…"
        modeButton.isEnabled = false
        statusView.text = "Sending recognized text to chatbot…"
    }

    override fun onImageTranslationCompleted(sourceUrl: String, rawTranslation: String) {
        if (!sameSource(sourceUrl)) return
        busy = false
        progressBar.visibility = View.GONE
        progressBar.isIndeterminate = false
        translateButton.isEnabled = true
        modeButton.isEnabled = true
        translateButton.text = if (selectedMode == "realtime") "Scan visible page" else "Translate again"
        lastRawTranslation = rawTranslation
        lastLayout = WebModeLayoutStore.getScan(chapter.url) ?: WebModeLayoutStore.get(this, chapter.url)
        lastAligned = lastLayout?.let { WebModeLayout.align(rawTranslation, it) }
        if (lastLayout == null || lastAligned == null || lastAligned?.none { !it.isNullOrBlank() } != false) {
            statusView.text = "OCR complete, but the translation could not be aligned to the bubbles."
            Toast.makeText(this, "Translation saved, but could not be placed on this page.", Toast.LENGTH_LONG).show()
            resumeRealtimeIfPending()
            return
        }
        applyOverlay()
        statusView.text = if (selectedMode == "realtime") {
            "Visible page translated · scroll to detect the next page"
        } else {
            "Translation ready · ${chapter.pageUrls.size} pages"
        }
        Toast.makeText(this, "Comic translation ready", Toast.LENGTH_SHORT).show()
        resumeRealtimeIfPending()
    }

    override fun onImageTranslationFailed(sourceUrl: String, message: String) {
        if (!sameSource(sourceUrl)) return
        busy = false
        progressBar.visibility = View.GONE
        progressBar.isIndeterminate = false
        translateButton.isEnabled = true
        modeButton.isEnabled = true
        translateButton.text = "Retry OCR + Translate"
        statusView.text = message.take(90)
        Toast.makeText(this, message.take(180), Toast.LENGTH_LONG).show()
        resumeRealtimeIfPending()
    }

    private fun sameSource(url: String): Boolean =
        url.trim().substringBefore('#') == chapter.url.trim().substringBefore('#')

    override fun onDestroy() {
        realtimeHandler.removeCallbacksAndMessages(null)
        ReaderViewBridge.detachComicReader(this)
        try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
