package com.ashraf.novelstudio

import android.content.Context
import java.io.File

/**
 * Read-only view of the saved-translation library that the Home screen renders.
 *
 * Everything here is derived from [Store] (the offline translations already on disk) plus the
 * site shortcut list, so it adds no second source of truth: a saved chapter shows up as a shelf,
 * "updates" mean chapters saved since the book was last opened, and "sources" are the built-in
 * shortcuts with their on/off switch from [Prefs].
 */
object LibraryModel {

    data class Shelf(val name: String, val chapters: Int, val lastSavedAt: Long)

    data class Update(val novel: String, val chapters: Int, val newCount: Int, val lastChapter: String, val at: Long, val sourceHost: String)

    data class Ext(val id: String, val name: String, val url: String?, val kind: String, val builtIn: Boolean)

    private const val SEEN_PREFIX = "libSeen:"

    fun shelves(c: Context): List<Shelf> {
        val books = Store.list(c).groupBy { it.novel }
        return books.map { (name, list) ->
            val newest = list.maxOf { savedAt(c, it) }
            Shelf(name, list.size, newest)
        }.sortedByDescending { it.lastSavedAt }
    }

    fun updates(c: Context, markSeen: Boolean = false): List<Update> {
        val out = ArrayList<Update>()
        Store.list(c).groupBy { it.novel }.forEach { (name, list) ->
            val seen = Prefs.get(c, SEEN_PREFIX + name).toLongOrNull() ?: 0L
            val sorted = list.sortedByDescending { savedAt(c, it) }
            val newest = sorted.firstOrNull() ?: return@forEach
            val at = savedAt(c, newest)
            val fresh = sorted.count { savedAt(c, it) > seen && seen > 0L }
            out.add(Update(name, list.size, if (seen == 0L) 0 else fresh, newest.label(), at, hostOf(newest.url)))
            if (markSeen && seen > 0L) seenNow(c, name, at)
        }
        return out.sortedByDescending { it.at }
    }

    /** Marks the book as opened now, so the next [updates] call counts only chapters saved after this. */
    fun markSeen(c: Context, novel: String) = seenNow(c, novel, System.currentTimeMillis())

    private fun seenNow(c: Context, novel: String, at: Long) {
        val old = Prefs.get(c, SEEN_PREFIX + novel).toLongOrNull() ?: 0L
        if (at > old) Prefs.put(c, SEEN_PREFIX + novel, at.toString())
    }

    fun shelfChapters(c: Context, novel: String): List<Tr> = Store.chapters(c, novel)

    fun recordRead(c: Context, novel: String, id: Long) = markSeen(c, novel)

    fun shelfMeta(s: Shelf): String =
        "${s.chapters} chapter" + (if (s.chapters == 1) "" else "s") +
            (if (s.lastSavedAt > 0L) " · saved " + ago(s.lastSavedAt) else "")

    fun sources(c: Context): List<Ext> {
        val builtIn = SITE_NAMES.map { (name, url) -> Ext(name, name, url, "Built-in site", true) }
        val learned = Store.list(c).map { hostOf(it.url) }.filter { h -> h.isNotBlank() && builtIn.none { hostOf(it.url ?: "") == h } }
            .distinct()
            .map { Ext(it, it, "https://$it/", "Learned from a saved book", false) }
        return builtIn + learned
    }

    fun enabled(c: Context, e: Ext): Boolean = Prefs.bool(c, "srcOff:" + e.id, false).not()

    fun setEnabled(c: Context, id: String, on: Boolean) = Prefs.putBool(c, "srcOff:$id", !on)

    private fun savedAt(c: Context, tr: Tr): Long = runCatching {
        File(c.filesDir, "tr/" + tr.id + ".json").lastModified()
    }.getOrDefault(0L)

    private fun hostOf(url: String): String = try {
        android.net.Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")
    } catch (_: Exception) {
        ""
    }

    private fun ago(ms: Long): String {
        val d = System.currentTimeMillis() - ms
        if (d < 0) return "just now"
        val min = d / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 60 * 24 -> "${min / 60} h ago"
            min < 60 * 24 * 7 -> "${min / (60 * 24)} d ago"
            else -> "${min / (60 * 24 * 7)} w ago"
        }
    }

    private val SITE_NAMES = listOf(
        "WTR-LAB" to "https://wtr-lab.com/",
        "WebNovel" to "https://www.webnovel.com/",
        "NovelBin" to "https://novelbin.com/",
        "BoxNovel" to "https://boxnovel.com/",
        "AsianNovel" to "https://www.asianovel.com/",
        "NovelFull" to "https://novelfull.net/",
        "LightNovelPub" to "https://lightnovelpub.com/",
        "NovelNext" to "https://novelnext.com/",
        "ReadNovelFull" to "https://readnovelfull.com/"
    )
}
