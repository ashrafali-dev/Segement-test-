package com.ashraf.novelstudio

/** The kind of page a source adapter can identify from a URL. */
internal enum class ReaderSourcePageType {
    HOME,
    SEARCH_RESULTS,
    SERIES,
    CHAPTER,
    OTHER
}

/** Content shape expected from a future chapter extractor. */
internal enum class ReaderContentKind {
    TEXT,
    IMAGE_PAGES,
    MIXED,
    UNKNOWN
}

/**
 * Describes what an adapter can actually do. A detected source is not automatically
 * Reader View-ready: nativeReaderReady stays false until extraction and reader hand-off
 * have been implemented and tested for that source.
 */
internal data class ReaderSourceCapabilities(
    val textExtraction: Boolean = false,
    val imagePageExtraction: Boolean = false,
    val chapterNavigation: Boolean = false,
    val nativeReaderReady: Boolean = false
)

internal data class ReaderSourceMatch(
    val adapterId: String,
    val displayName: String,
    val host: String,
    val url: String,
    val pageType: ReaderSourcePageType,
    val contentKind: ReaderContentKind,
    val capabilities: ReaderSourceCapabilities
) {
    val isChapterPage: Boolean
        get() = pageType == ReaderSourcePageType.CHAPTER

    /** Used by the future UI to avoid showing a Reader View action prematurely. */
    val canOfferReaderView: Boolean
        get() = isChapterPage &&
            capabilities.nativeReaderReady &&
            (capabilities.textExtraction || capabilities.imagePageExtraction)
}

/**
 * Metadata and URL classification for a Reader source.
 *
 * Implementations should be small and deterministic. Network access and extraction belong
 * in later source-specific components, not in this URL classifier.
 */
internal interface ReaderSourceAdapter {
    val id: String
    val displayName: String
    val domains: Set<String>
    val contentKind: ReaderContentKind
    val capabilities: ReaderSourceCapabilities

    fun classify(url: ReaderSourceUrl): ReaderSourcePageType
}
