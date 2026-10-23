package com.ashraf.novelstudio

import android.content.Context
import org.json.JSONObject

/**
 * Remembers the DOM "map" learned from each novel website.
 * The profile is keyed by hostname, so it survives app restarts and
 * can be reused for every novel/chapter on the same site.
 */
object SiteProfiles {
    private const val KEY = "siteProfiles"

    private fun hostOf(url: String): String {
        val raw = url.substringAfter("://", "")
        return raw.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            .removePrefix("www.").lowercase()
    }

    private fun all(c: Context): JSONObject {
        return try { JSONObject(Prefs.get(c, KEY, "{}")) } catch (_: Exception) { JSONObject() }
    }

    private fun saveAll(c: Context, o: JSONObject) {
        Prefs.put(c, KEY, o.toString())
    }

    fun selector(c: Context, url: String, field: String): String {
        SiteRules.selector(c, url, field).let { if (it.isNotBlank()) return it }   // user override wins
        val host = hostOf(url)
        if (host.isEmpty()) return ""
        return try { all(c).optJSONObject(host)?.optString(field, "") ?: "" }
        catch (_: Exception) { "" }
    }

    fun remember(c: Context, ch: Chapter) {
        val host = hostOf(ch.url)
        if (host.isEmpty()) return
        val root = all(c)
        val old = root.optJSONObject(host) ?: JSONObject()
        if (ch.contentSel.isNotBlank()) old.put("content", ch.contentSel)
        if (ch.titleSel.isNotBlank()) old.put("title", ch.titleSel)
        if (ch.nextSel.isNotBlank()) old.put("next", ch.nextSel)
        if (ch.prevSel.isNotBlank()) old.put("prev", ch.prevSel)
        old.put("learned", true)
        old.put("url", ch.url)
        root.put(host, old)
        saveAll(c, root)
    }
}
