package com.ashraf.novelstudio.manualextract

/**
 * Small bounded LRU for translations produced by manual extracts. Keyed by a stable hash of the recognised
 * source text (plus the script) so re-tapping the same bubble is instant and costs no request.
 */
internal object ManualExtractCache {
    private class Entry(val translated: String, val atMs: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > ManualExtractConfig.TRANSLATION_CACHE_ENTRIES
    }

    private const val TTL_MS = 10 * 60_000L

    fun key(sourceText: String, scriptName: String): String =
        scriptName + "|" + sourceText.trim().replace(Regex("\\s+"), " ")

    @Synchronized
    fun get(key: String): String? {
        val e = map[key] ?: return null
        if (android.os.SystemClock.elapsedRealtime() - e.atMs > TTL_MS) { map.remove(key); return null }
        return e.translated
    }

    @Synchronized
    fun put(key: String, translated: String) {
        if (translated.isBlank()) return
        map[key] = Entry(translated, android.os.SystemClock.elapsedRealtime())
    }

    @Synchronized
    fun clear() { map.clear() }
}
