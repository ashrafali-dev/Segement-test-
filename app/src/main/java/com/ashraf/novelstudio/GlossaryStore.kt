package com.ashraf.novelstudio

import android.content.Context

/**
 * Persistent user glossary storage.
 * Matching is handled by Glossary.kt.
 */
object GlossaryStore {
    private const val PREF = "glossary"
    private const val KEY = "raw"

    data class Entry(val aliases: List<String>, val translation: String)

    fun raw(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun save(c: Context, text: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, text).apply()
    }

    fun entries(c: Context): List<Entry> = parse(raw(c))

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (line0 in text.lineSequence()) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pos = line.indexOf("=>").let { if (it >= 0) it else line.indexOf('=') }
            if (pos <= 0) continue
            val left = line.substring(0, pos).trim()
            val right = line.substring(if (line.startsWith("=>", pos)) pos + 2 else pos + 1).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            val aliases = left.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.trim().lowercase() }
            if (aliases.isNotEmpty()) out += Entry(aliases, right)
        }
        return out
    }
}
