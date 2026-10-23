package com.ashraf.novelstudio

import android.webkit.WebView

internal interface SiteAdapter {
    val id: String
    val capabilities: SiteCapabilities
    fun matches(url: String?): Boolean
    fun extract(webView: WebView, attempt: Int = 0, callback: (Chapter?) -> Unit)
    fun catalogUrl(chapterUrl: String): String?
    fun catalogPickScript(direction: String, title: String, sourceUrl: String): String
    fun prefetchTarget(chapterUrl: String): String?
    fun writeTranslation(
        webView: WebView,
        chapter: Chapter,
        translatedText: String,
        fontFile: String,
        fontSizePx: Int,
        pageKey: String,
        callback: (Boolean) -> Unit
    )
}
