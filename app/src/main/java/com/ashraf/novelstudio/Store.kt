package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Tr(val id: Long, val novel: String, val number: String, val title: String, val url: String) {
    val sortKey: Double get() = number.toDoubleOrNull() ?: Double.MAX_VALUE
    fun label(): String = (if (number.isNotEmpty()) "Ch $number — " else "") + title
}

data class Bookmark(val name: String, val url: String, var lastUrl: String, var lastTitle: String)

data class HistoryEntry(val url: String, val title: String, val time: Long)

object Store {
    // ---------- offline library (saved translations) ----------
    // Exact source-page identity. Novel543 can split one logical chapter
    // across /..._<chapter>.html, /..._<chapter>_2.html, etc.
    // Translation lookup must never confuse those pages.
    private fun pageKey(url: String): String {
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
            if (host == "novel543.com" || host.endsWith(".novel543.com")) {
                val book = u.pathSegments.firstOrNull().orEmpty()
                val file = u.pathSegments.getOrNull(1).orEmpty()
                val m = Regex("^(\\d+)_(\\d+)(?:_(\\d+))?\\.html?$").find(file)
                if (book.matches(Regex("\\d{7,}")) && m != null) {
                    val part = m.groupValues.getOrNull(3).orEmpty().ifBlank { "1" }
                    return host + "/" + book + "/" + m.groupValues[1] + "_" + m.groupValues[2] + ":part" + part
                }
            }
            // Canonicalize mobile/desktop host aliases so a prefetched chapter
            // saved from m.webtoons.com also matches the visible www.webtoons.com page.
            val canonicalHost = host
            val canonicalPath = u.encodedPath.orEmpty().trimEnd('/')
            val query = u.encodedQuery?.let { "?$it" }.orEmpty()
            canonicalHost + canonicalPath + query
        } catch (_: Exception) {
            try {
                val u = Uri.parse(url)
                val host = (u.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
                host + u.encodedPath.orEmpty().trimEnd('/') + (u.encodedQuery?.let { "?$it" } ?: "")
            } catch (_: Exception) { url.substringBefore("#").trimEnd('/') }
        }
    }


    private fun idx(c: Context) = File(c.filesDir, "translations.json")
    private fun dir(c: Context): File { val d = File(c.filesDir, "tr"); d.mkdirs(); return d }

    fun list(c: Context): List<Tr> {
        val f = idx(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Tr(o.getLong("id"), o.optString("novel", "Unknown"), o.optString("number"), o.optString("title"), o.optString("url"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeIdx(c: Context, items: List<Tr>) {
        val a = JSONArray()
        for (t in items) {
            a.put(JSONObject().put("id", t.id).put("novel", t.novel).put("number", t.number).put("title", t.title).put("url", t.url))
        }
        idx(c).writeText(a.toString())
    }

    fun novelNames(c: Context): List<String> =
        list(c).sortedByDescending { it.id }.map { it.novel }.distinct()

    fun chapters(c: Context, novel: String): List<Tr> =
        list(c).filter { it.novel == novel }.sortedWith(compareBy<Tr>({ it.sortKey }, { it.id }))

    // Stable source-book identity. Chapter number alone must never match across stories.
    private fun bookKey(url: String): String {
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
            val parts = u.pathSegments
            when {
                host.endsWith("webnovel.com") -> {
                    val i = parts.indexOfFirst { it.equals("book", true) }
                    if (i >= 0 && parts.size > i + 1) host + "/book/" + parts[i + 1].lowercase() else ""
                }
                host == "novel543.com" || host.endsWith(".novel543.com") -> {
                    val id = parts.firstOrNull().orEmpty()
                    if (id.matches(Regex("\\d{7,}"))) host + "/" + id else ""
                }
                else -> {
                    val markers = setOf("novel", "book", "series", "story", "fiction")
                    val i = parts.indexOfFirst { it.lowercase() in markers }
                    if (i >= 0 && parts.size > i + 1 && parts[i + 1].isNotBlank())
                        host + "/" + parts[i].lowercase() + "/" + parts[i + 1].lowercase() else ""
                }
            }
        } catch (_: Exception) { "" }
    }

    fun save(c: Context, ch: Chapter?, text: String): Tr {
        val raw = (ch?.novel ?: "").ifBlank { "Unknown" }
        val novel = Prefs.get(c, "alias_$raw", raw)
        val number = ch?.number ?: ""
        val title = ch?.title ?: "Translation"
        val url = ch?.url ?: ""
        val items = list(c).toMutableList()
        val normTitle = title.trim().lowercase().replace(Regex("\\s+"), " ")
        // Same-URL readers (notably WebNovel) use one reader URL for many
        // chapters. Number/title identity must therefore win over URL identity.
        // Exact page identity wins. A split chapter must have one saved
        // translation per source page, even when the detected chapter number/title
        // is identical across all parts.
        val exactPage = if (url.isNotEmpty()) pageKey(url) else ""
        val exactPageMatch = if (exactPage.isNotEmpty()) {
            items.firstOrNull { pageKey(it.url) == exactPage }
        } else null
        val bk = bookKey(url)
        val isNovel543 = Uri.parse(url).host?.lowercase()?.let { it == "novel543.com" || it.endsWith(".novel543.com") } == true
        val sameBookMatch = if (exactPageMatch == null && bk.isNotEmpty() && !isNovel543) {
            items.firstOrNull {
                number.isNotEmpty() && it.number == number && bookKey(it.url) == bk
            } ?: items.firstOrNull {
                normTitle.isNotEmpty() && bookKey(it.url) == bk &&
                    it.title.trim().lowercase().replace(Regex("\\s+"), " ") == normTitle
            }
        } else null
        val existing = exactPageMatch ?: sameBookMatch ?: items.firstOrNull {
            // Without a stable book ID, only exact URL + title is safe.
            url.isNotEmpty() && it.url == url &&
                (normTitle.isEmpty() || it.title.trim().lowercase().replace(Regex("\\s+"), " ") == normTitle)
        }
        val id = existing?.id ?: System.currentTimeMillis()
        File(dir(c), "$id.txt").writeText(text)
        val tr = Tr(id, novel, number, title, url)
        if (existing == null) items.add(tr) else items[items.indexOf(existing)] = tr
        writeIdx(c, items)
        return tr
    }

    // Match by book + chapter identity first. WebNovel can reuse a reader URL
    // across chapters, so URL alone is never a safe chapter key.
    fun find(c: Context, ch: Chapter): Tr? {
        val raw = ch.novel.ifBlank { "Unknown" }
        val novel = Prefs.get(c, "alias_$raw", raw)
        val normTitle = ch.title.trim().lowercase().replace(Regex("\\s+"), " ")
        val items = list(c)
        // Exact source page identity is the first and safest lookup.
        val exactPage = if (ch.url.isNotEmpty()) pageKey(ch.url) else ""
        val exactPageMatch = if (exactPage.isNotEmpty()) {
            items.firstOrNull { pageKey(it.url) == exactPage }
        } else null
        if (exactPageMatch != null) return exactPageMatch

        val bk = bookKey(ch.url)
        val isNovel543 = Uri.parse(ch.url).host?.lowercase()?.let { it == "novel543.com" || it.endsWith(".novel543.com") } == true
        val sameBookMatch = if (bk.isNotEmpty() && !isNovel543) {
            items.firstOrNull {
                ch.number.isNotEmpty() && it.number == ch.number && bookKey(it.url) == bk
            } ?: items.firstOrNull {
                normTitle.isNotEmpty() && bookKey(it.url) == bk &&
                    it.title.trim().lowercase().replace(Regex("\\s+"), " ") == normTitle
            }
        } else null
        return sameBookMatch ?: items.firstOrNull {
            // No reliable book identity: never match by chapter number or title alone.
            ch.url.isNotEmpty() && it.url == ch.url &&
                (normTitle.isEmpty() || it.title.trim().lowercase().replace(Regex("\\s+"), " ") == normTitle)
        }
    }

    fun readRaw(c: Context, id: Long): String =
        try { File(dir(c), "$id.txt").readText() } catch (_: Exception) { "" }

    // Internal [NNN] paragraph markers stay in storage for alignment, but are
    // hidden from library reading and exports.
    fun read(c: Context, id: Long): String =
        readRaw(c, id).replace(Regex("(?m)^\\s*\\[\\d{3,}\\]\\s*"), "").trim()

    fun delete(c: Context, id: Long) {
        File(dir(c), "$id.txt").delete()
        writeIdx(c, list(c).filter { it.id != id })
    }

    fun deleteNovel(c: Context, novel: String) {
        for (t in list(c)) if (t.novel == novel) File(dir(c), "${t.id}.txt").delete()
        writeIdx(c, list(c).filter { it.novel != novel })
    }

    fun rename(c: Context, old: String, new: String) {
        if (new.isBlank() || new == old) return
        writeIdx(c, list(c).map { if (it.novel == old) it.copy(novel = new) else it })
        Prefs.put(c, "alias_$old", new)   // future chapters saved under the old detected name go to the new name
    }

    fun export(c: Context, novel: String?): String {
        val names = if (novel != null) listOf(novel) else novelNames(c)
        return names.joinToString("\n\n########################\n\n") { n ->
            "📖 $n\n\n" + chapters(c, n).joinToString("\n\n==========\n\n") { it.label() + "\n\n" + read(c, it.id) }
        }
    }

    // ---------- browser history ----------
    fun history(c: Context): List<HistoryEntry> {
        val raw = Prefs.get(c, "browserHistory")
        if (raw.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                HistoryEntry(o.optString("url"), o.optString("title"), o.optLong("time"))
            }.sortedByDescending { it.time }
        } catch (_: Exception) { emptyList() }
    }

    fun addHistory(c: Context, url: String, title: String) {
        if (!url.startsWith("http")) return
        val host = Uri.parse(url).host ?: return
        if (host.isBlank()) return
        val old = history(c).filter { it.url != url }.take(99).toMutableList()
        old.add(0, HistoryEntry(url, title.ifBlank { host }, System.currentTimeMillis()))
        val a = JSONArray()
        old.forEach { h ->
            a.put(JSONObject().put("url", h.url).put("title", h.title).put("time", h.time))
        }
        Prefs.put(c, "browserHistory", a.toString())
    }

    fun clearHistory(c: Context) {
        Prefs.put(c, "browserHistory", "[]")
    }

    // ---------- bookmarks of novel sites ----------
    fun bookmarks(c: Context): MutableList<Bookmark> {
        val s = Prefs.get(c, "lib")
        if (s.isEmpty()) return mutableListOf()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Bookmark(o.getString("name"), o.getString("url"), o.optString("lastUrl"), o.optString("lastTitle"))
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveBookmarks(c: Context, l: List<Bookmark>) {
        val a = JSONArray()
        for (n in l) a.put(JSONObject().put("name", n.name).put("url", n.url).put("lastUrl", n.lastUrl).put("lastTitle", n.lastTitle))
        Prefs.put(c, "lib", a.toString())
    }

    fun addBookmark(c: Context, n: Bookmark) {
        val l = bookmarks(c)
        l.removeAll { it.url == n.url }
        l.add(0, n)
        saveBookmarks(c, l)
    }

    fun removeBookmark(c: Context, i: Int) {
        val l = bookmarks(c)
        if (i in l.indices) { l.removeAt(i); saveBookmarks(c, l) }
    }

    fun touchBookmark(c: Context, ch: Chapter) {
        val host = AdBlock.root(Uri.parse(ch.url).host ?: return)
        val l = bookmarks(c)
        val n = l.firstOrNull { AdBlock.root(Uri.parse(it.url).host ?: "") == host } ?: return
        n.lastUrl = ch.url
        n.lastTitle = ch.title
        saveBookmarks(c, l)
    }
}
