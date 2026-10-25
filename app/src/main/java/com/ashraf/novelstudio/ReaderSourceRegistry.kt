package com.ashraf.novelstudio

/**
 * Registry for URL detection and capability lookup. It is deliberately separate from the
 * existing SiteRegistry, whose SiteAdapter contract extracts translated text from a live WebView.
 *
 * The registration hook supports in-process adapter modules. It does not load arbitrary downloaded
 * APK/JAR code; a user-facing plugin installer needs a versioned format, trust checks, and an explicit
 * permission model before it can safely be added.
 */
internal class ReaderSourceRegistry(
    initialAdapters: Collection<ReaderSourceAdapter> = BuiltInReaderSourceAdapters.all()
) {
    private val lock = Any()
    private val builtInIds = initialAdapters.map { it.id }.toSet()
    private val adaptersById = LinkedHashMap<String, ReaderSourceAdapter>()

    init {
        initialAdapters.forEach { adapter ->
            require(isValid(adapter)) { "Invalid Reader source adapter: " + adapter.id }
            require(!adaptersById.containsKey(adapter.id)) { "Duplicate Reader source adapter: " + adapter.id }
            adaptersById[adapter.id] = adapter
        }
    }

    /**
     * Registers an adapter supplied by a trusted in-process module.
     * Returns false for invalid metadata, duplicate IDs, or an exact domain collision.
     */
    fun registerAdapter(adapter: ReaderSourceAdapter): Boolean = synchronized(lock) {
        if (!isValid(adapter) || adaptersById.containsKey(adapter.id)) return@synchronized false

        val newDomains = adapter.domains.mapNotNull { ReaderSourceUrl.normalizeDomain(it) }.toSet()
        val collides = adaptersById.values.any { current ->
            current.domains.any { domain ->
                ReaderSourceUrl.normalizeDomain(domain)?.let { it in newDomains } == true
            }
        }
        if (collides) return@synchronized false

        adaptersById[adapter.id] = adapter
        true
    }

    /** Built-in definitions cannot be removed through the extension-registration API. */
    fun removeAdapter(id: String): Boolean = synchronized(lock) {
        if (id in builtInIds) return@synchronized false
        adaptersById.remove(id) != null
    }

    fun allAdapters(): List<ReaderSourceAdapter> = synchronized(lock) {
        adaptersById.values.toList()
    }

    /** Returns a source match for any recognized domain, including non-chapter pages. */
    fun find(url: String?): ReaderSourceMatch? {
        val parsed = ReaderSourceUrl.parse(url) ?: return null
        val candidates = synchronized(lock) { adaptersById.values.toList() }

        var winner: ReaderSourceAdapter? = null
        var winnerSpecificity = -1
        for (adapter in candidates) {
            val specificity = adapter.domains
                .mapNotNull { domain ->
                    if (parsed.matchesDomain(domain)) ReaderSourceUrl.normalizeDomain(domain)?.length else null
                }
                .maxOrNull() ?: continue

            // Prefer the most specific registered domain (e.g. reader.example.com over example.com).
            if (specificity > winnerSpecificity) {
                winner = adapter
                winnerSpecificity = specificity
            }
        }

        val adapter = winner ?: return null
        return ReaderSourceMatch(
            adapterId = adapter.id,
            displayName = adapter.displayName,
            host = parsed.host,
            url = parsed.original,
            pageType = adapter.classify(parsed),
            contentKind = adapter.contentKind,
            capabilities = adapter.capabilities
        )
    }

    fun findChapter(url: String?): ReaderSourceMatch? =
        find(url)?.takeIf { it.isChapterPage }

    /** Returns only chapter matches whose adapter explicitly declares native Reader support ready. */
    fun findReaderReadyChapter(url: String?): ReaderSourceMatch? =
        findChapter(url)?.takeIf { it.canOfferReaderView }

    private fun isValid(adapter: ReaderSourceAdapter): Boolean {
        if (!adapter.id.matches(Regex("""^[a-z0-9][a-z0-9._-]{0,63}$"""))) return false
        if (adapter.displayName.isBlank() || adapter.domains.isEmpty()) return false
        return adapter.domains.all { ReaderSourceUrl.normalizeDomain(it) != null }
    }
}

internal object ReaderSources {
    val registry = ReaderSourceRegistry()
}
