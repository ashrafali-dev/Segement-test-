package com.ashraf.novelstudio

import java.security.MessageDigest

/**
 * Bounded LRU of Google Lens text blocks keyed by panel content (Defect F storage, Defect B's DEDUPE_HIT).
 *
 * Key: the md5 of the panel's IMAGE BYTES — the very hash the scanner already computes for DEDUPE. The engine
 * sees decoded pixels instead of bytes, so it registers the same entry through [putByPixels], which computes a
 * byte digest from the decoded pixels ([pixelDigest]) and links it to a pixel-signature key ([signatureOf]).
 * Both ways of asking therefore hit the same entry.
 *
 * Geometry is stored in PANEL pixels together with the panel size it was produced at, so a hit can never be
 * placed using another panel's scale. The map is tiny on purpose (15 entries on a 192 MB device).
 */
internal object LensCache {
    const val MAX_ENTRIES = 15
    private const val GRID = 32

    data class Entry(val blocks: List<OcrBlock>, val panelW: Int, val panelH: Int, val script: OcrScript)

    private val map = object : LinkedHashMap<String, Entry>(MAX_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_ENTRIES
    }
    private val aliases = HashMap<String, String>()

    /** 32x32 luma grid of a bitmap as a compact digest string (used when only pixels exist). */
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

    /** md5 of the RGB bytes of the 32x32 signature grid: the engine-side twin of the scanner's md5(bytes). */
    fun pixelDigest(signature: String): String {
        val bytes = ByteArray(signature.length * 3)
        var i = 0
        for (c in signature) {
            val l = when (c) {
                'A' -> 240; 'B' -> 208; 'C' -> 176; 'D' -> 144; 'E' -> 112; 'F' -> 80; 'G' -> 48; else -> 16
            }
            bytes[i++] = l.toByte(); bytes[i++] = l.toByte(); bytes[i++] = l.toByte()
        }
        return MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } + ":" + bytes.size
    }

    @Synchronized
    fun get(key: String, script: OcrScript): List<OcrBlock>? {
        // Resolve directly: calling the @Synchronized get(key) here also re-locked the monitor (reentrant, but it
        // made the alias path read like two lookups).
        val e = map[aliases[key] ?: key] ?: return null
        return if (e.script == script) e.blocks else null
    }

    @Synchronized
    fun get(key: String): Entry? = map[aliases[key] ?: key]

    /** Put by raw key (scanner: md5 of the panel bytes, or a pixel digest). */
    @Synchronized
    fun put(key: String, entry: Entry) {
        if (entry.blocks.isEmpty() || entry.panelW <= 0 || entry.panelH <= 0) return
        map[aliases[key] ?: key] = entry
    }

    /** Put by pixels: registers both the pixel digest and the signature so either lookup hits. */
    @Synchronized
    fun putByPixels(signature: String, entry: Entry) {
        if (entry.blocks.isEmpty() || entry.panelW <= 0 || entry.panelH <= 0) return
        val digest = pixelDigest(signature)
        link(digest, signature)
        link(signature, signature)
        map[signature] = Entry(entry.blocks, entry.panelW, entry.panelH, entry.script)
    }

    /** Register [rawKey] as an alias of [key] so a lookup by either finds the entry. */
    @Synchronized
    fun alias(rawKey: String, key: String) = link(rawKey, key)

    private fun link(rawKey: String, key: String) {
        if (aliases.size > MAX_ENTRIES * 6) aliases.clear()
        aliases[rawKey] = key
    }

    @Synchronized
    fun clear() { map.clear(); aliases.clear() }

    @Synchronized
    fun size(): Int = map.size
}
