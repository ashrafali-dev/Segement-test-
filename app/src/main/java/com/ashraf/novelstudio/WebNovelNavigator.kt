package com.ashraf.novelstudio

import android.net.Uri

internal class WebNovelNavigator {
    fun isWebNovel(url: String?): Boolean {
        val host = runCatching { Uri.parse(url.orEmpty()).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "webnovel.com" || host.endsWith(".webnovel.com")
    }

    fun catalogUrl(chapterUrl: String): String? {
        return runCatching {
            val u = Uri.parse(chapterUrl)
            val path = u.path.orEmpty()
            val book = Regex("^(/book/[^/]+)", RegexOption.IGNORE_CASE).find(path)?.value
                ?: return@runCatching null
            if (u.scheme.isNullOrBlank() || u.authority.isNullOrBlank()) return@runCatching null
            u.scheme + "://" + u.authority + book + "/catalog"
        }.getOrNull()
    }

    fun catalogPickScript(direction: String, title: String, sourceUrl: String): String =
        Js.webNovelPickCatalog(direction, title, sourceUrl)

    fun prefetchTarget(chapterUrl: String): String? = catalogUrl(chapterUrl)
}
