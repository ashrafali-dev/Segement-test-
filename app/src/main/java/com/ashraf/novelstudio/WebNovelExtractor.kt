package com.ashraf.novelstudio

import android.net.Uri
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal class WebNovelExtractor(private val context: android.content.Context) {

    fun extract(webView: WebView, attempt: Int = 0, callback: (Chapter?) -> Unit) {
        val url = webView.url.orEmpty()
        if (url.isBlank()) {
            callback(null)
            return
        }

        val contentSel = SiteProfiles.selector(context, url, "content")
        val titleSel = SiteProfiles.selector(context, url, "title")
        val nextSel = SiteProfiles.selector(context, url, "next")
        val prevSel = SiteProfiles.selector(context, url, "prev")

        webView.evaluateJavascript(
            Js.fastExtract(contentSel, titleSel, nextSel, prevSel)
        ) { raw ->
            val payload = decode(raw)
            if (payload.isBlank()) {
                fallback(webView, url, contentSel, titleSel, nextSel, prevSel, callback)
                return@evaluateJavascript
            }

            try {
                val o = JSONObject(payload)
                if (!o.optBoolean("ok", false)) {
                    fallback(webView, url, contentSel, titleSel, nextSel, prevSel, callback)
                    return@evaluateJavascript
                }

                val title = o.optString("title", "").trim()
                val body = o.optString("text", "").trim()
                val count = o.optInt("count", 0)
                if (body.length < 40 || count < 1 ||
                    (title.isNotBlank() && body == title)) {
                    if (attempt < 8) {
                        webView.postDelayed({
                            extract(webView, attempt + 1, callback)
                        }, 120L)
                    } else {
                        fallback(webView, url, contentSel, titleSel, nextSel, prevSel, callback)
                    }
                    return@evaluateJavascript
                }

                val cleanTitle = clean(title)
                val cleanBody = clean(body)
                val fullText = if (cleanTitle.isNotEmpty() && cleanBody.startsWith(cleanTitle)) {
                    cleanBody
                } else if (cleanTitle.isNotEmpty()) {
                    cleanTitle + "\n\n" + cleanBody
                } else cleanBody

                val next = o.optJSONObject("next")?.optString("href", "")
                    ?.takeIf { it.isNotBlank() }
                val prev = o.optJSONObject("prev")?.optString("href", "")
                    ?.takeIf { it.isNotBlank() }

                val novel = o.optString("novel", "").trim().ifBlank {
                    url.substringAfter("://").substringBefore('/').removePrefix("www.")
                }

                val number = Regex(
                    "\\b(?:chapter|chap|ch|episode|ep)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)",
                    RegexOption.IGNORE_CASE
                ).find(cleanTitle)?.groupValues?.getOrNull(1)
                    ?: Regex("(\\d{1,5})").find(cleanTitle)?.value.orEmpty()

                val chapter = Chapter(
                    cleanTitle.ifBlank { o.optString("pageTitle", "").trim() },
                    fullText,
                    next,
                    prev,
                    url,
                    novel,
                    number,
                    o.optString("contentSel", ""),
                    o.optString("titleSel", ""),
                    o.optJSONObject("next")?.optString("selector", "").orEmpty(),
                    o.optJSONObject("prev")?.optString("selector", "").orEmpty()
                )
                SiteProfiles.remember(context, chapter)
                callback(chapter)
            } catch (_: Exception) {
                fallback(webView, url, contentSel, titleSel, nextSel, prevSel, callback)
            }
        }
    }

    private fun fallback(
        webView: WebView,
        url: String,
        contentSel: String,
        titleSel: String,
        nextSel: String,
        prevSel: String,
        callback: (Chapter?) -> Unit
    ) {
        webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = decode(raw)
            if (html.isBlank()) {
                callback(null)
                return@evaluateJavascript
            }
            Thread {
                val chapter = try {
                    val doc = Jsoup.parse(html, url)
                    Extractor.extract(doc, url, contentSel, titleSel, nextSel, prevSel)
                } catch (_: Exception) {
                    null
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (chapter != null) SiteProfiles.remember(context, chapter)
                    callback(chapter)
                }
            }.start()
        }
    }

    private fun decode(raw: String?): String = try {
        if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0)
    } catch (_: Exception) { "" }

    private fun clean(s: String): String = s
        .replace("\u00a0", " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" ?\\n ?"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

internal class WebNovelSystem(private val context: android.content.Context) : SiteAdapter {
    private val navigator = WebNovelNavigator()
    private val extractor = WebNovelExtractor(context)
    private val writer = WebNovelWriter()

    override val id: String = "webnovel"

    override val capabilities = SiteCapabilities(
        extraction = true,
        nextChapter = true,
        previousChapter = true,
        translationInsert = true,
        htmlPreserve = true,
        autoNext = true,
        chapterNumberDetection = true
    )

    override fun matches(url: String?): Boolean = navigator.isWebNovel(url)

    override fun extract(webView: WebView, attempt: Int, callback: (Chapter?) -> Unit) =
        extractor.extract(webView, attempt, callback)

    override fun catalogUrl(chapterUrl: String): String? =
        navigator.catalogUrl(chapterUrl)

    override fun catalogPickScript(direction: String, title: String, sourceUrl: String): String =
        navigator.catalogPickScript(direction, title, sourceUrl)

    override fun prefetchTarget(chapterUrl: String): String? =
        navigator.prefetchTarget(chapterUrl)

    override fun writeTranslation(
        webView: WebView,
        chapter: Chapter,
        translatedText: String,
        fontFile: String,
        fontSizePx: Int,
        pageKey: String,
        callback: (Boolean) -> Unit
    ) {
        val preserve = chapter.url.contains("webnovel.com/", ignoreCase = true)
        writer.write(
            webView, chapter, translatedText, fontFile, fontSizePx, pageKey, preserve, callback
        )
    }

    fun isWebNovel(url: String?): Boolean = matches(url)
}
