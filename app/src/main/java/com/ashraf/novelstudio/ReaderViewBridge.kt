package com.ashraf.novelstudio

import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Coordinates native readers with MainActivity's existing source and chatbot pipelines.
 * Translation engines stay owned by MainActivity; reader screens only present the result.
 */
internal interface SourceReaderListener {
    val sourceUrl: String
    fun onTranslationStarted(sourceUrl: String)
    fun onTranslationCompleted(sourceUrl: String, translatedText: String)
    fun onTranslationFailed(sourceUrl: String, message: String)
}

internal interface ComicSourceReaderListener {
    val sourceUrl: String
    val sourceWebView: WebView
    fun onOcrProgress(sourceUrl: String, stage: String, percent: Int)
    fun onImageTranslationStarted(sourceUrl: String)
    /** The raw marked reply is passed so it can be aligned to the OCR bubble geometry. */
    fun onImageTranslationCompleted(sourceUrl: String, rawTranslation: String)
    fun onImageTranslationFailed(sourceUrl: String, message: String)
}

internal object ReaderViewBridge {
    private var mainActivity: WeakReference<MainActivity>? = null
    private var reader: WeakReference<SourceReaderListener>? = null
    private var comicReader: WeakReference<ComicSourceReaderListener>? = null

    @Synchronized
    fun attachMain(activity: MainActivity) {
        mainActivity = WeakReference(activity)
    }

    @Synchronized
    fun detachMain(activity: MainActivity) {
        if (mainActivity?.get() === activity) {
            mainActivity?.clear()
            mainActivity = null
        }
    }

    @Synchronized
    fun attachReader(listener: SourceReaderListener) {
        reader = WeakReference(listener)
    }

    @Synchronized
    fun detachReader(listener: SourceReaderListener) {
        if (reader?.get() === listener) {
            reader?.clear()
            reader = null
        }
    }

    @Synchronized
    fun attachComicReader(listener: ComicSourceReaderListener) {
        comicReader = WeakReference(listener)
    }

    @Synchronized
    fun detachComicReader(listener: ComicSourceReaderListener) {
        if (comicReader?.get() === listener) {
            val closedUrl = listener.sourceUrl
            comicReader?.clear()
            comicReader = null
            mainActivity?.get()?.onComicReaderClosed(closedUrl)
        }
    }

    fun hasComicReader(): Boolean = comicReader?.get() != null

    fun requestTranslation(chapter: Chapter): Boolean =
        mainActivity?.get()?.requestReaderTranslation(chapter) ?: false

    fun requestComicTranslation(webView: WebView, chapter: ImageChapter): Boolean =
        mainActivity?.get()?.requestComicReaderTranslation(webView, chapter) ?: false

    fun isActiveComicReader(webView: WebView, url: String): Boolean {
        val target = comicReader?.get() ?: return false
        return target.sourceWebView === webView && comparableUrl(target.sourceUrl) == comparableUrl(url)
    }

    fun comicOcrProgress(url: String, stage: String, percent: Int) {
        matchingComicReader(url)?.onOcrProgress(url, stage, percent)
    }

    fun comicTranslationStarted(url: String) {
        matchingComicReader(url)?.onImageTranslationStarted(url)
    }

    fun comicTranslationCompleted(url: String, rawTranslation: String): Boolean {
        val target = matchingComicReader(url) ?: return false
        target.onImageTranslationCompleted(url, rawTranslation)
        return true
    }

    fun comicTranslationFailed(url: String, message: String) {
        matchingComicReader(url)?.onImageTranslationFailed(url, message)
    }

    fun translationStarted(url: String) {
        matchingReader(url)?.onTranslationStarted(url)
    }

    fun translationCompleted(url: String, translatedText: String): Boolean {
        val target = matchingReader(url) ?: return false
        target.onTranslationCompleted(url, translatedText)
        return true
    }

    fun translationFailed(url: String, message: String) {
        matchingReader(url)?.onTranslationFailed(url, message)
    }

    private fun matchingReader(url: String): SourceReaderListener? {
        val target = reader?.get() ?: return null
        return target.takeIf { comparableUrl(it.sourceUrl) == comparableUrl(url) }
    }

    private fun matchingComicReader(url: String): ComicSourceReaderListener? {
        val target = comicReader?.get() ?: return null
        return target.takeIf { comparableUrl(it.sourceUrl) == comparableUrl(url) }
    }

    private fun comparableUrl(raw: String): String =
        raw.trim().substringBefore('#').let {
            runCatching {
                val uri = java.net.URI(it)
                buildString {
                    append(uri.scheme?.lowercase().orEmpty())
                    append("://")
                    append(uri.host?.lowercase().orEmpty())
                    append(uri.rawPath.orEmpty())
                    uri.rawQuery?.let { query -> append('?').append(query) }
                }
            }.getOrDefault(it)
        }
}
