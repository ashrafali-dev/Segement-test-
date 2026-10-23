package com.ashraf.novelstudio

import android.content.Context
import org.json.JSONObject

/**
 * Learns, per website, what works for reading a comic page and remembers it on the phone:
 *  - are image downloads allowed, and how long do they take?  (download route)
 *  - how long does reading the screen's own pixels take?       (screen route)
 *  - which script is the text in (Japanese / Chinese / Korean / Latin)?
 * Real-time reading then starts with the route that has been working, instead of trying everything in turn.
 * The download route is re-checked every 15th page, because a site can change.
 */
object SiteLearner {
    private const val KEY = "siteLearn"
    private const val MAX_HOSTS = 60

    private class P(
        var dOk: Int = 0, var dFail: Int = 0, var dMs: Long = 0L,
        var sOk: Int = 0, var sMs: Long = 0L,
        var uses: Int = 0, var script: String = "", var seen: Long = 0L
    )

    @Synchronized private fun load(c: Context): JSONObject =
        try { JSONObject(Prefs.get(c, KEY, "{}")) } catch (_: Exception) { JSONObject() }

    @Synchronized private fun read(c: Context, host: String): P? {
        val o = load(c).optJSONObject(host) ?: return null
        return P(o.optInt("dOk"), o.optInt("dFail"), o.optLong("dMs"), o.optInt("sOk"), o.optLong("sMs"),
            o.optInt("uses"), o.optString("script"), o.optLong("seen"))
    }

    @Synchronized private fun write(c: Context, host: String, p: P) {
        if (host.isBlank()) return
        val all = load(c)
        p.seen = System.currentTimeMillis()
        all.put(host, JSONObject().put("dOk", p.dOk).put("dFail", p.dFail).put("dMs", p.dMs)
            .put("sOk", p.sOk).put("sMs", p.sMs).put("uses", p.uses).put("script", p.script).put("seen", p.seen))
        if (all.length() > MAX_HOSTS) {
            var oldest: String? = null
            var t = Long.MAX_VALUE
            val it = all.keys()
            while (it.hasNext()) { val k = it.next(); val s = all.optJSONObject(k)?.optLong("seen") ?: 0L; if (s < t) { t = s; oldest = k } }
            oldest?.let { all.remove(it) }
        }
        Prefs.put(c, KEY, all.toString())
    }

    private fun blocked(p: P) = p.dFail >= 2 && p.dFail >= p.dOk
    private fun screenFaster(p: P) = p.dOk >= 2 && p.sOk >= 2 && p.sMs * 10 < p.dMs * 7

    /** True when this site should be read straight from the screen (no download attempt). Counts as one use. */
    fun preferScreen(c: Context, host: String): Boolean {
        val p = read(c, host) ?: return false
        p.uses++
        write(c, host, p)
        if (!(blocked(p) || screenFaster(p))) return false
        return p.uses % 15 != 0          // every 15th page: try the download route again
    }

    /** Same answer without counting a use (read-ahead asks this). */
    fun screenPreferred(c: Context, host: String): Boolean {
        val p = read(c, host) ?: return false
        return blocked(p) || screenFaster(p)
    }

    fun recordDownload(c: Context, host: String, ok: Boolean, ms: Long) {
        if (host.isBlank()) return
        val p = read(c, host) ?: P()
        if (ok) { p.dMs = if (p.dOk == 0) ms else (p.dMs * 3 + ms) / 4; p.dOk++ } else p.dFail++
        write(c, host, p)
    }

    fun recordScreen(c: Context, host: String, ms: Long) {
        if (host.isBlank()) return
        val p = read(c, host) ?: P()
        p.sMs = if (p.sOk == 0) ms else (p.sMs * 3 + ms) / 4
        p.sOk++
        write(c, host, p)
    }

    fun script(c: Context, host: String): String? = read(c, host)?.script?.takeIf { it.isNotBlank() }

    fun saveScript(c: Context, host: String, script: String) {
        if (host.isBlank()) return
        val p = read(c, host) ?: P()
        if (p.script != script) { p.script = script; write(c, host, p) }
    }

    /** Plain-words report for the menu: what was learned about this site, and why the app behaves as it does. */
    fun explain(c: Context, host: String): String {
        val p = read(c, host) ?: return "Nothing learned about $host yet. Read a few pages in Real-time mode and the app will learn."
        fun s(ms: Long) = String.format("%.1f s", ms / 1000.0)
        val sb = StringBuilder("Site: $host\n\n")
        sb.append("Image downloads: ${p.dOk} worked, ${p.dFail} failed")
        if (p.dOk > 0) sb.append(" (about ${s(p.dMs)} each)")
        sb.append("\nScreen reading: ${p.sOk} worked")
        if (p.sOk > 0) sb.append(" (about ${s(p.sMs)} each)")
        sb.append("\nText type: ").append(p.script.ifBlank { "not known yet" }.lowercase())
        sb.append("\n\nNow using: ")
        sb.append(when {
            blocked(p) -> "screen reading. This site keeps refusing image downloads, so waiting for them only wastes time."
            screenFaster(p) -> "screen reading. It is clearly faster than downloading here."
            else -> "image downloads first, screen reading as the backup."
        })
        sb.append("\nThe download route is tried again every 15th page in case the site changed.")
        return sb.toString()
    }
}
