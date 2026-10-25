package com.ashraf.novelstudio

import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Parsed HTTP(S) URL used by the Smart Reader source registry.
 *
 * This is independent of Android Uri so URL detection can be unit-tested on the JVM.
 * The host is lower-case, IDN-normalized, and has a leading www. removed.
 */
internal data class ReaderSourceUrl private constructor(
    val original: String,
    val scheme: String,
    val host: String,
    val path: String,
    val query: String
) {
    val pathSegments: List<String>
        get() = path.split('/').filter { it.isNotBlank() }

    fun matchesDomain(domain: String): Boolean {
        val normalizedDomain = normalizeDomain(domain) ?: return false
        return host == normalizedDomain || host.endsWith("." + normalizedDomain)
    }

    companion object {
        fun parse(rawUrl: String?): ReaderSourceUrl? {
            val candidate = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val uri = try {
                URI(candidate)
            } catch (_: Exception) {
                return null
            }

            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null

            val rawHost = uri.host ?: return null
            val host = normalizeDomain(rawHost) ?: return null
            if (host.isBlank()) return null

            val path = uri.rawPath.orEmpty().trimEnd('/').ifEmpty { "/" }
            return ReaderSourceUrl(
                original = candidate,
                scheme = scheme,
                host = host,
                path = path,
                query = uri.rawQuery.orEmpty()
            )
        }

        internal fun normalizeDomain(value: String): String? {
            val raw = value.trim().trimEnd('.').lowercase(Locale.ROOT).removePrefix("www.")
            if (raw.isBlank() || raw.contains('/') || raw.contains(':') || raw.any { it.isWhitespace() }) {
                return null
            }
            return try {
                IDN.toASCII(raw).lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
