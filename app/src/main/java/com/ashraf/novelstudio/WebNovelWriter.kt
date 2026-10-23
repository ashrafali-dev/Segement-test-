package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray

internal class WebNovelWriter {
    fun write(
        webView: WebView,
        chapter: Chapter,
        translatedText: String,
        fontFile: String,
        fontSizePx: Int,
        pageKey: String,
        preserveParagraphs: Boolean,
        callback: (Boolean) -> Unit
    ) {
        val sel = chapter.contentSel.ifBlank { SiteProfiles.selector(webView.context, chapter.url, "content") }
        val paras = translatedText
            .let { splitInternalMarkedTranslation(it, preserveParagraphs) }

        webView.evaluateJavascript(
            Js.apply(sel, JSONArray(paras).toString(), fontFile, fontSizePx, pageKey)
        ) { result ->
            callback(result != null && result.contains("ok"))
        }
    }

    // Kept here so the WebNovel writer owns the paragraph-alignment decision.
    private fun splitInternalMarkedTranslation(text: String, preserveParagraphs: Boolean): List<String> {
        val marker = Regex("""\[\d{3,}\]\s*""")
        val marked = marker.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (marked.size > 1) return marked

        return if (preserveParagraphs) {
            text.split(Regex("""\n\s*\n|\n+"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } else {
            text.split(Regex("""\n\s*\n"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
