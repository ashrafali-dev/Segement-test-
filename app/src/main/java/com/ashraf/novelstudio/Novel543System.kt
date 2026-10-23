package com.ashraf.novelstudio

import android.content.Context
import android.webkit.WebView

internal class Novel543System(private val context: Context) : SiteAdapter {
    private val navigator = Novel543Navigator()
    private val extractor = Novel543Extractor(context)
    private val writer = Novel543Writer()
    override val id: String = "novel543"
    override val capabilities = SiteCapabilities(extraction=true, nextChapter=true, previousChapter=true, translationInsert=true, htmlPreserve=true, autoNext=true, chapterNumberDetection=true)
    override fun matches(url: String?): Boolean = navigator.matches(url)
    override fun extract(webView: WebView, attempt: Int, callback: (Chapter?) -> Unit) = extractor.extract(webView, attempt, callback)
    override fun catalogUrl(chapterUrl: String): String? = navigator.catalogUrl(chapterUrl)
    override fun catalogPickScript(direction: String, title: String, sourceUrl: String): String = navigator.catalogPickScript(direction, title, sourceUrl)
    override fun prefetchTarget(chapterUrl: String): String? = navigator.prefetchTarget(chapterUrl)
    override fun writeTranslation(webView: WebView, chapter: Chapter, translatedText: String, fontFile: String, fontSizePx: Int, pageKey: String, callback: (Boolean) -> Unit) =
        writer.write(webView, chapter, translatedText, fontFile, fontSizePx, pageKey, callback)
    fun pickAdjacent(webView: WebView, direction: String, callback: (String?) -> Unit) = navigator.pickAdjacent(webView, direction, callback)
}
