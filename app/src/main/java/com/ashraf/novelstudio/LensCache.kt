package com.ashraf.novelstudio

/**
 * Bounded LRU of Google Lens reads keyed by panel content (Defect F, used by Defect B's DEDUPE_HIT).
 *
 * A panel is identified by the MD5 of its image bytes — exactly the hash the scanner already computes for
 * DEDUPE. The engine sees the decoded pixels instead of the bytes, so the cache key is a 32x32 luma grid
 * ([signatureOf]) and [alias] links the scanner's raw digest to it. Storage keeps geometry in PANEL pixels
 * together with the panel size it was produced at: a hit can never be scaled with another panel's dimensions.
 * The map is tiny on purpose (15 entries on a 192 MB device).
 */
internal object LensCache {
    const val MAX_ENTRIES = 15
    private const val GRID = 32

    data class Entry(val blocks: List<OcrBlock>, val panelW: Int, val panelH: Int, val script: OcrScript)

    private val map = object : LinkedHashMap<String, Entry>(MAX_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_ENTRIES
    }
    private val aliases = HashMap<String, String>()

    /** 32x32 luma grid of a bitmap, written as a compact digest string. Pure integer work. */
    fun signatureOf(width: Int, height: Int, lumaAt: (x: Int, y: Int) -> Int): String {
        val sb = StringBuilder(GRID * GRID)
        for (gy in 0 until GRID) {
            val y = if (height <= 1) 0 else (gy * (height - 1)) / (GRID - 1)
            for (gx in 0 until GRID) {
                val x = if (width <= 1) 0 else (gx * (width - 1)) / (GRID - 1)
                val l = lumaAt(x, y)
                sb.append(if (l >= 224) 'A' else if (l >= 192) 'B' else if (l >= 160) 'C' else if (l >= 128) 'D'
                    else if (l >= 96) 'E' else if (l >= 64) 'F' else if (l >= 32) 'G' else 'H')
            }
        }
        return sb.toString()
    }

    @Synchronized
    fun get(signature: String, script: OcrScript): List<OcrBlock>? {
        val key = aliases[signature] ?: signature
        val e = map[key] ?: return null
        return if (e.script == script) e.blocks else null
    }

    @Synchronized
    fun get(signature: String): Entry? = map[aliases[signature] ?: signature]

    @Synchronized
    fun put(signature: String, entry: Entry) {
        if (entry.blocks.isEmpty() || entry.panelW <= 0 || entry.panelH <= 0) return
        val key = aliases[signature] ?: signature
        map[key] = entry
    }

    /** Register a raw-byte digest as an alias of [signature] (so both lookups work). */
    @Synchronized
    fun alias(rawDigest: String, signature: String) {
        if (aliases.size > MAX_ENTRIES * 4) aliases.clear()
        aliases[rawDigest] = signature
    }

    @Synchronized
    fun clear() { map.clear(); aliases.clear() }

    @Synchronized
    fun size(): Int = map.size
}
