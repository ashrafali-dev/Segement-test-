package com.ashraf.novelstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small per-chapter glossary for webtoons (Scanning and Real-time): hero name, character names, places,
 * pronouns (he/she). It is stored per page and added to the prompt only while that chapter is translated.
 */
object ChapterGlossary {
    val TYPES = listOf("Hero name", "Character name", "Place name", "Pronoun (he / she)", "Other term")

    class Entry(val type: String, val source: String, val value: String)

    private fun key(pageKey: String) = "chgl:$pageKey"

    fun list(c: Context, pageKey: String): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(Prefs.get(c, key(pageKey), "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Entry(o.optString("t"), o.optString("s"), o.optString("v")))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun save(c: Context, pageKey: String, items: List<Entry>) {
        val a = JSONArray()
        for (e in items) a.put(JSONObject().put("t", e.type).put("s", e.source).put("v", e.value))
        Prefs.put(c, key(pageKey), if (items.isEmpty()) "" else a.toString())
    }

    fun add(c: Context, pageKey: String, e: Entry) {
        val items = list(c, pageKey)
        items.removeAll { it.type == e.type && it.source.equals(e.source, ignoreCase = true) }
        items.add(e)
        save(c, pageKey, items)
    }

    fun remove(c: Context, pageKey: String, index: Int) {
        val items = list(c, pageKey)
        if (index in items.indices) { items.removeAt(index); save(c, pageKey, items) }
    }

    /** Text block for the prompt; empty when this chapter has no entries. */
    fun block(c: Context, pageKey: String): String {
        val items = list(c, pageKey)
        if (items.isEmpty()) return ""
        val sb = StringBuilder("Chapter glossary (always use exactly these names and pronouns in this chapter):\n")
        for (e in items) {
            val line = when (e.type) {
                "Pronoun (he / she)" -> "- ${e.source} uses the pronoun: ${e.value}"
                else -> "- ${e.type}: ${e.source} → ${e.value}"
            }
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
