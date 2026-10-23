package com.ashraf.novelstudio

import android.content.Context

object Glossary {
    private var cachedRaw = ""
    private var cachedEntries: List<GlossaryMatcher.Entry> = emptyList()
    /** How many glossary terms went into the last prompt (shown in a toast). */
    @Volatile var lastMatchCount = 0

    @Synchronized
    private fun entries(c: Context): List<GlossaryMatcher.Entry> {
        val raw = GlossaryStore.raw(c)
        if (raw == cachedRaw) return cachedEntries
        cachedEntries = GlossaryMatcher.parse(raw)
        cachedRaw = raw
        return cachedEntries
    }

    /** Traditional and Simplified Chinese match each other; see [ZhNorm]. */
    fun matches(c: Context, chapter: String): List<Pair<String, String>> =
        GlossaryMatcher.match(entries(c), chapter)

    fun block(c: Context, chapter: String): String {
        val m = matches(c, chapter)
        lastMatchCount = m.size
        if (m.isEmpty()) return ""
        return "[Glossary — use these translations consistently]\n" +
            m.joinToString("\n") { "${it.first} => ${it.second}" }
    }

    fun block(chapter: String): String = ""

    val count: Int
        get() = cachedEntries.sumOf { it.aliases.size }

    fun read(c: Context): String = GlossaryStore.raw(c)

    fun write(c: Context, text: String) {
        GlossaryStore.save(c, text)
        cachedRaw = ""
        cachedEntries = emptyList()
        entries(c)
    }

    fun append(c: Context, text: String) {
        val add = text.trim()
        if (add.isEmpty()) return
        val old = GlossaryStore.raw(c).trim()
        write(c, if (old.isEmpty()) add else "$old\n$add")
    }

    fun reload(c: Context) {
        cachedRaw = ""
        cachedEntries = emptyList()
        entries(c)
    }
}
