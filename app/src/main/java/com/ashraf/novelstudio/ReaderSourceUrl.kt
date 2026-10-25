package com.ashraf.novelstudio

import java.net.URI

/**
 * URL validation shared by the future Reader View hand-off and source registry.
 *
 * This deliberately does not decide whether a page is a chapter. A supported domain
 * may also serve home, search, login, and catalog pages; the adapter must successfully
 * extract a Chapter before the app offers "Open in Reader View".
 */
internal object ReaderSourceUrl {
    /**
     * Returns the trimmed HTTP(S) URL when it is syntactically safe to pass to a
     * source adapter, or null for unsupported schemes / malformed URLs.
     *
     * Query and fragment are retained because some sites use them for chapter identity
     * or SPA routing. This function performs validation only; it does not fetch content.
     */
    fun validate(rawUrl: String?): String? {
        val candidate = rawUrl?.trim().orEmpty()
        if (candidate.isEmpty()) return null

        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
            candidate
        } catch (_: Exception) {
            null
        }
    }

    fun host(rawUrl: String?): String? {
        val validated = validate(rawUrl) ?: return null
        return runCatching { URI(validated).host?.lowercase()?.removePrefix("www.") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
