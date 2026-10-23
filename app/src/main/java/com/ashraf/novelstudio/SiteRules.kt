package com.ashraf.novelstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-site rules the user adds from the menu (☰ → সাইট সেটিং).
 *  - compat : read the whole rendered page (slower but works on JS-heavy sites like webnovel)
 *  - noAds  : do not run the ad blocker on this site
 *  - content/next/prev : optional CSS selectors that override auto-detection
 * A rule for "example.com" also covers m.example.com, www.example.com, ...
 */
object SiteRules {
    data class Rule(
        val host: String,
        val compat: Boolean = true,
        val noAds: Boolean = true,
        val content: String = "",
        val next: String = "",
        val prev: String = ""
    )

    private const val KEY = "siteRules"
    @Volatile private var cache: List<Rule>? = null

    fun hostOf(url: String): String {
        val raw = if (url.contains("://")) url.substringAfter("://") else url
        return raw.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':').trim().lowercase().removePrefix("www.")
    }

    fun all(c: Context): List<Rule> {
        cache?.let { return it }
        val list = try {
            val a = JSONArray(Prefs.get(c, KEY, "[]"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Rule(
                    o.optString("host"), o.optBoolean("compat", true), o.optBoolean("noAds", true),
                    o.optString("content"), o.optString("next"), o.optString("prev")
                )
            }.filter { it.host.isNotEmpty() }
        } catch (e: Exception) { emptyList() }
        cache = list
        return list
    }

    fun save(c: Context, list: List<Rule>) {
        val a = JSONArray()
        for (r in list) a.put(
            JSONObject().put("host", r.host).put("compat", r.compat).put("noAds", r.noAds)
                .put("content", r.content).put("next", r.next).put("prev", r.prev)
        )
        Prefs.put(c, KEY, a.toString())
        cache = list
        sync(c)
    }

    fun upsert(c: Context, r: Rule) {
        save(c, all(c).filter { it.host != r.host } + r)
    }

    fun remove(c: Context, host: String) {
        save(c, all(c).filter { it.host != host })
    }

    private fun matches(rule: String, host: String) = host == rule || host.endsWith(".$rule")

    fun find(c: Context, url: String): Rule? {
        val h = hostOf(url)
        if (h.isEmpty()) return null
        return all(c).firstOrNull { matches(it.host, h) }
    }

    fun compat(c: Context, url: String): Boolean = find(c, url)?.compat == true

    fun selector(c: Context, url: String, field: String): String {
        val r = find(c, url) ?: return ""
        return when (field) {
            "content" -> r.content
            "next" -> r.next
            "prev" -> r.prev
            else -> ""
        }
    }

    /** Push the ad-block exemptions to AdBlock. Call at startup and after every change. */
    fun sync(c: Context) {
        AdBlock.extraExempt = all(c).filter { it.noAds }.map { it.host }.toSet()
    }
}
