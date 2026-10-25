package com.ashraf.novelstudio

import android.content.Context

/**
 * Result of matching a validated browser URL to an installed source adapter.
 *
 * This only confirms that the domain is known. Callers must still run [SiteAdapter.extract]
 * and verify the returned [Chapter] before offering the Reader View action, because a
 * supported domain also contains home, search, catalog, login and other non-chapter pages.
 */
internal data class ReaderSourceMatch(
    val adapter: SiteAdapter,
    val validatedUrl: String
)

internal object SiteRegistry {
    private fun adapters(context: Context): List<SiteAdapter> = listOf(
        WebNovelSystem(context),
        WtrLabSystem(context),
        QidianSystem(context),
        Novel543System(context)
    )

    fun find(context: Context, url: String?): SiteAdapter? =
        adapters(context).firstOrNull { it.matches(url) }

    /**
     * Safe entry point for the future browser-to-reader hand-off.
     *
     * A null result means the URL is not a valid HTTP(S) URL or no built-in adapter
     * recognizes its domain. The caller should keep normal WebView behavior in that case.
     */
    fun resolve(context: Context, url: String?): ReaderSourceMatch? {
        val validatedUrl = ReaderSourceUrl.validate(url) ?: return null
        val adapter = find(context, validatedUrl) ?: return null
        return ReaderSourceMatch(adapter = adapter, validatedUrl = validatedUrl)
    }
}
