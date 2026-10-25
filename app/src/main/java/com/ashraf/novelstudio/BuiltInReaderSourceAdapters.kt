package com.ashraf.novelstudio

import java.util.Locale

/**
 * First-phase source definitions for domains already handled by Novel Studio's text extractor.
 *
 * These entries only identify a source and conservatively classify its URL. They do not claim
 * that a native Reader View extractor exists yet; that is tracked by nativeReaderReady=false.
 */
internal object BuiltInReaderSourceAdapters {
    fun all(): List<ReaderSourceAdapter> = listOf(
        RuleBasedReaderSourceAdapter(
            id = "webnovel",
            displayName = "WebNovel",
            domains = setOf("webnovel.com"),
            contentKind = ReaderContentKind.TEXT,
            capabilities = ReaderSourceCapabilities(
                textExtraction = true,
                chapterNavigation = true
            )
        ) { url ->
            commonPageType(url) ?: run {
                val segments = url.pathSegments.map { it.lowercase(Locale.ROOT) }
                when {
                    segments.firstOrNull() == "book" &&
                        (segments.lastOrNull() == "catalog" || segments.lastOrNull() == "catalogue") ->
                        ReaderSourcePageType.SERIES
                    segments.firstOrNull() == "book" && segments.size == 2 ->
                        ReaderSourcePageType.SERIES
                    segments.firstOrNull() == "book" && segments.size >= 3 &&
                        isChapterSegment(segments.last()) ->
                        ReaderSourcePageType.CHAPTER
                    else -> ReaderSourcePageType.OTHER
                }
            }
        },
        RuleBasedReaderSourceAdapter(
            id = "qidian",
            displayName = "Qidian",
            domains = setOf("qidian.com"),
            contentKind = ReaderContentKind.TEXT,
            capabilities = ReaderSourceCapabilities(
                textExtraction = true,
                chapterNavigation = true
            )
        ) { url ->
            commonPageType(url) ?: run {
                val segments = url.pathSegments.map { it.lowercase(Locale.ROOT) }
                val chapterIndex = segments.indexOf("chapter")
                when {
                    chapterIndex >= 0 && segments.size > chapterIndex + 1 ->
                        ReaderSourcePageType.CHAPTER
                    segments.firstOrNull()?.let { it == "book" || it == "books" || it == "bookdetail" } == true ->
                        ReaderSourcePageType.SERIES
                    segments.isEmpty() -> ReaderSourcePageType.HOME
                    else -> ReaderSourcePageType.OTHER
                }
            }
        },
        RuleBasedReaderSourceAdapter(
            id = "wtr-lab",
            displayName = "WTR-LAB",
            domains = setOf("wtr-lab.com"),
            contentKind = ReaderContentKind.TEXT,
            capabilities = ReaderSourceCapabilities(
                textExtraction = true,
                chapterNavigation = true
            )
        ) { url ->
            commonPageType(url) ?: run {
                val segments = url.pathSegments.map { it.lowercase(Locale.ROOT) }
                when {
                    segments.any { Regex("""^chapter-\d+$""").matches(it) } ->
                        ReaderSourcePageType.CHAPTER
                    segments.isEmpty() -> ReaderSourcePageType.HOME
                    else -> ReaderSourcePageType.OTHER
                }
            }
        },
        RuleBasedReaderSourceAdapter(
            id = "novel543",
            displayName = "Novel543",
            domains = setOf("novel543.com"),
            contentKind = ReaderContentKind.TEXT,
            capabilities = ReaderSourceCapabilities(
                textExtraction = true,
                chapterNavigation = true
            )
        ) { url ->
            commonPageType(url) ?: run {
                val segments = url.pathSegments.map { it.lowercase(Locale.ROOT) }
                val first = segments.firstOrNull().orEmpty()
                val last = segments.lastOrNull().orEmpty()
                when {
                    first.isBlank() -> ReaderSourcePageType.HOME
                    first == "search" -> ReaderSourcePageType.SEARCH_RESULTS
                    first.matches(Regex("""^\d+$""")) && last == "dir" ->
                        ReaderSourcePageType.SERIES
                    first.matches(Regex("""^\d+$""")) && segments.size >= 2 &&
                        (last.matches(Regex("""^\d+(?:\.html?)?$""")) ||
                            last.endsWith(".html") || last.endsWith(".htm")) ->
                        ReaderSourcePageType.CHAPTER
                    first.matches(Regex("""^\d+$""")) -> ReaderSourcePageType.SERIES
                    else -> ReaderSourcePageType.OTHER
                }
            }
        }
    )

    private fun commonPageType(url: ReaderSourceUrl): ReaderSourcePageType? {
        val segments = url.pathSegments.map { it.lowercase(Locale.ROOT) }
        if (segments.isEmpty()) return ReaderSourcePageType.HOME
        if (segments.first() in SEARCH_PATHS) return ReaderSourcePageType.SEARCH_RESULTS
        return null
    }

    private fun isChapterSegment(segment: String): Boolean {
        val normalized = segment.lowercase(Locale.ROOT)
        return Regex("""^(?:chapter|chap|ch|episode|ep)[-_]?\d+(?:[-_].*)?$""").matches(normalized) ||
            Regex("""^\d{1,12}$""").matches(normalized)
    }

    private val SEARCH_PATHS = setOf(
        "search", "search-result", "search-results", "searchlist", "search-list", "searchbook"
    )

    private class RuleBasedReaderSourceAdapter(
        override val id: String,
        override val displayName: String,
        override val domains: Set<String>,
        override val contentKind: ReaderContentKind,
        override val capabilities: ReaderSourceCapabilities,
        private val classifier: (ReaderSourceUrl) -> ReaderSourcePageType
    ) : ReaderSourceAdapter {
        override fun classify(url: ReaderSourceUrl): ReaderSourcePageType = classifier(url)
    }
}
