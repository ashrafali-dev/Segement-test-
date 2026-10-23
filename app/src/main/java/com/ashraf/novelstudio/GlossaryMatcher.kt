package com.ashraf.novelstudio

import java.text.Normalizer
import java.util.Locale

/**
 * Pure (no Android) glossary parsing + matching, so it can be tested on its own.
 * Matching is script-insensitive (Traditional == Simplified) via [ZhNorm.fold], and works on
 * folded text with plain indexOf: no Regex is built per term.
 */
object GlossaryMatcher {
    class Entry(val aliases: List<String>, val translation: String)

    private fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

    // A Latin-like letter/digit (below the CJK blocks). "art" must not match inside "party",
    // but a term may sit right next to Chinese characters.
    private fun latinLike(ch: Char): Boolean = ch.isLetterOrDigit() && ch.code < 0x2E80

    fun parse(raw: String): List<Entry> {
        val map = LinkedHashMap<String, Pair<String, String>>()
        for (line0 in raw.lineSequence()) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pos = line.indexOf("=>").let { if (it >= 0) it else line.indexOf('=') }
            if (pos <= 0) continue
            val left = line.substring(0, pos).trim()
            val right = line.substring(if (line.startsWith("=>", pos)) pos + 2 else pos + 1).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            val aliases = left.split('|').map { it.trim() }
                .filter { it.isNotEmpty() }.distinctBy { ZhNorm.fold(key(it)) }
            // Same term written in both scripts is one entry: key on the folded form.
            for (alias in aliases) map[ZhNorm.fold(key(alias))] = alias to right
        }
        return map.values.groupBy { it.second }.values
            .map { group -> Entry(group.map { it.first }, group.first().second) }
    }

    /** Terms of [entries] that occur in [chapter]: (text as written in the chapter, translation). */
    fun match(entries: List<Entry>, chapter: String): List<Pair<String, String>> {
        if (chapter.isBlank()) return emptyList()
        val folded = ZhNorm.fold(chapter)
        class Hit(val start: Int, val end: Int, val translation: String)
        val hits = ArrayList<Hit>()
        for (entry in entries) {
            for (alias in entry.aliases) {
                val fa = ZhNorm.fold(alias)
                if (fa.isBlank()) continue
                val needL = latinLike(fa.first())   // boundary only where the term itself starts/ends Latin-like
                val needR = latinLike(fa.last())
                var from = 0
                while (true) {
                    val idx = folded.indexOf(fa, from)
                    if (idx < 0) break
                    from = idx + 1
                    val end = idx + fa.length
                    if (needL && idx > 0 && latinLike(chapter[idx - 1])) continue
                    if (needR && end < chapter.length && latinLike(chapter[end])) continue
                    hits += Hit(idx, end, entry.translation)
                }
            }
        }
        hits.sortWith(compareBy<Hit>({ it.start }, { -(it.end - it.start) }))
        // leftmost-longest, no overlaps
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        var lastEnd = 0
        for (h in hits) {
            if (h.start < lastEnd) continue
            lastEnd = h.end
            val surface = chapter.substring(h.start, h.end)
            if (seen.add(ZhNorm.fold(surface))) out += surface to h.translation
        }
        return out
    }
}
