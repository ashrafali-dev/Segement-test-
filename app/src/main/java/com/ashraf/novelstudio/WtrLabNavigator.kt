package com.ashraf.novelstudio

import android.net.Uri

internal class WtrLabNavigator {
    fun isWtrLab(url: String?): Boolean {
        val host = runCatching { Uri.parse(url.orEmpty()).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "wtr-lab.com" || host.endsWith(".wtr-lab.com")
    }

    fun nextFromUrl(chapterUrl: String): String? = adjacent(chapterUrl, 1)
    fun prevFromUrl(chapterUrl: String): String? = adjacent(chapterUrl, -1)
    fun prefetchTarget(chapterUrl: String): String? = nextFromUrl(chapterUrl)

    /** Same book/slug, explicit chapter number. WTR keeps the URL unchanged while its
     *  SPA swaps chapters, so the number must come from the page, not the URL. */
    fun chapterUrl(url: String, number: Long): String? = runCatching {
        if (number < 1) return@runCatching null
        val u = Uri.parse(url)
        val m = Regex("^(.*?/chapter-)(\\d+)/?$", RegexOption.IGNORE_CASE).find(u.path.orEmpty())
            ?: return@runCatching null
        u.buildUpon().path(m.groupValues[1] + number).clearQuery().fragment(null).build().toString()
    }.getOrNull()

    private fun adjacent(url: String, delta: Long): String? = runCatching {
        val u = Uri.parse(url)
        val path = u.path.orEmpty()
        val m = Regex("^(.*?/chapter-)(\\d+)/?$", RegexOption.IGNORE_CASE).find(path)
            ?: return@runCatching null
        val n = m.groupValues[2].toLongOrNull() ?: return@runCatching null
        if (n + delta < 1) return@runCatching null
        u.buildUpon().path(m.groupValues[1] + (n + delta)).build().toString()
    }.getOrNull()
}
