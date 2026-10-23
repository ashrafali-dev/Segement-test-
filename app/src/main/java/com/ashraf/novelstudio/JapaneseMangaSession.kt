package com.ashraf.novelstudio

import java.security.MessageDigest

/**
 * Marks only the OCR-derived Chapter instance handled by the optional Japanese manga engine.
 * The text fingerprint prevents the flag leaking to another chapter on an SPA that reuses a URL.
 */
internal object JapaneseMangaSession {
    private val activePages = LinkedHashMap<String, String>()

    private fun key(url: String): String = WebModeLogic.normalizeUrl(url)

    private fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Synchronized
    fun register(chapter: Chapter) {
        val k = key(chapter.url)
        if (k.isNotBlank()) activePages[k] = fingerprint(chapter.text)
        while (activePages.size > 16) activePages.remove(activePages.keys.first())
    }

    @Synchronized
    fun isManaged(chapter: Chapter): Boolean =
        activePages[key(chapter.url)] == fingerprint(chapter.text)

    @Synchronized
    fun clear(url: String) {
        activePages.remove(key(url))
    }
}
