package com.ashraf.novelstudio

import java.lang.ref.WeakReference

/**
 * Coordinates the independent source-reader screen with MainActivity's existing chatbot queue.
 * It does not own translation logic; all requests are handed to the current MainActivity.
 */
internal interface SourceReaderListener {
    val sourceUrl: String
    fun onTranslationStarted(sourceUrl: String)
    fun onTranslationCompleted(sourceUrl: String, translatedText: String)
    fun onTranslationFailed(sourceUrl: String, message: String)
}

internal object ReaderViewBridge {
    private var mainActivity = WeakReference<MainActivity>(null)
    private var reader = WeakReference<SourceReaderListener>(null)

    @Synchronized
    fun attachMain(activity: MainActivity) {
        mainActivity = WeakReference(activity)
    }

    @Synchronized
    fun detachMain(activity: MainActivity) {
        if (mainActivity.get() === activity) mainActivity.clear()
    }

    @Synchronized
    fun attachReader(listener: SourceReaderListener) {
        reader = WeakReference(listener)
    }

    @Synchronized
    fun detachReader(listener: SourceReaderListener) {
        if (reader.get() === listener) reader.clear()
    }

    fun requestTranslation(chapter: Chapter): Boolean =
        mainActivity.get()?.requestReaderTranslation(chapter) ?: false

    fun translationStarted(url: String) {
        val target = matchingReader(url) ?: return
        target.onTranslationStarted(url)
    }

    fun translationCompleted(url: String, translatedText: String): Boolean {
        val target = matchingReader(url) ?: return false
        target.onTranslationCompleted(url, translatedText)
        return true
    }

    fun translationFailed(url: String, message: String) {
        val target = matchingReader(url) ?: return
        target.onTranslationFailed(url, message)
    }

    private fun matchingReader(url: String): SourceReaderListener? {
        val target = reader.get() ?: return null
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
