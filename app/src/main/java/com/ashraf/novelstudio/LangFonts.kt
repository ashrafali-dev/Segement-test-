package com.ashraf.novelstudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A clean, readable font for the language the user translates INTO. Nothing is bundled in the APK:
 * the font file is fetched once from Google Fonts (Noto family) and kept in the app's private storage.
 * Languages that the phone already draws well with its own fonts (Latin, Cyrillic, Greek, CJK ...) need no download.
 * Bengali keeps its three bundled fonts.
 */
object LangFonts {
    /** Language -> Google Fonts family. Edit this table to use another family. */
    private val FAMILY = mapOf(
        "Hindi" to "Noto Sans Devanagari", "Marathi" to "Noto Sans Devanagari", "Nepali" to "Noto Sans Devanagari",
        "Arabic" to "Noto Naskh Arabic", "Persian" to "Noto Naskh Arabic", "Pashto" to "Noto Naskh Arabic",
        "Sindhi" to "Noto Naskh Arabic", "Uyghur" to "Noto Naskh Arabic", "Kurdish" to "Noto Naskh Arabic",
        "Urdu" to "Noto Nastaliq Urdu",
        "Hebrew" to "Noto Sans Hebrew", "Yiddish" to "Noto Sans Hebrew",
        "Thai" to "Noto Sans Thai", "Lao" to "Noto Sans Lao", "Khmer" to "Noto Sans Khmer",
        "Burmese" to "Noto Sans Myanmar", "Tamil" to "Noto Sans Tamil", "Telugu" to "Noto Sans Telugu",
        "Kannada" to "Noto Sans Kannada", "Malayalam" to "Noto Sans Malayalam", "Gujarati" to "Noto Sans Gujarati",
        "Punjabi" to "Noto Sans Gurmukhi", "Sinhala" to "Noto Sans Sinhala", "Odia" to "Noto Sans Oriya",
        "Assamese" to "Noto Sans Bengali", "Georgian" to "Noto Sans Georgian", "Armenian" to "Noto Sans Armenian",
        "Amharic" to "Noto Sans Ethiopic", "Tigrinya" to "Noto Sans Ethiopic"
    )

    fun familyFor(lang: String): String? = FAMILY[lang]

    private fun slug(family: String) = family.lowercase().replace(' ', '_')

    fun file(c: Context, slug: String): File = File(File(c.filesDir, "fonts"), "$slug.ttf")

    fun installed(c: Context, lang: String): Boolean {
        val fam = FAMILY[lang] ?: return false
        val f = file(c, slug(fam))
        return f.isFile && f.length() > 10_000
    }

    /** Font name handed to the page writers: "dl_<slug>" when downloaded, otherwise empty (the page's own font). */
    fun nameFor(c: Context, lang: String): String {
        val fam = FAMILY[lang] ?: return ""
        return if (installed(c, lang)) "dl_" + slug(fam) else ""
    }

    /** Downloads the font for [lang] if it is not there yet. [onDone] runs on the main thread. */
    fun ensure(c: Context, lang: String, onDone: (Boolean, String) -> Unit) {
        val fam = FAMILY[lang]
        if (fam == null) { onDone(true, "System font"); return }
        if (installed(c, lang)) { onDone(true, fam); return }
        val app = c.applicationContext
        Thread {
            var ok = false
            try {
                // An old browser identity makes Google Fonts answer with plain .ttf links.
                val cssConn = URL("https://fonts.googleapis.com/css?family=" + URLEncoder.encode(fam, "UTF-8")).openConnection() as HttpURLConnection
                cssConn.connectTimeout = 15_000
                cssConn.readTimeout = 20_000
                cssConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 2.2; en-us; Nexus One Build/FRF91) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1")
                val css = cssConn.inputStream.bufferedReader().use { it.readText() }
                val link = Regex("url\\((https?://[^)]+\\.ttf)\\)").find(css)?.groupValues?.get(1)
                    ?: Regex("url\\((https?://[^)]+)\\)").find(css)?.groupValues?.get(1)
                if (link != null) {
                    val dir = File(app.filesDir, "fonts").apply { mkdirs() }
                    val tmp = File(dir, slug(fam) + ".part")
                    val conn = URL(link).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                    if (tmp.length() in 10_001..25_000_000L) {
                        val dest = file(app, slug(fam))
                        dest.delete()
                        ok = tmp.renameTo(dest)
                    } else tmp.delete()
                }
            } catch (_: Exception) { ok = false }
            Handler(Looper.getMainLooper()).post { onDone(ok, fam) }
        }.start()
    }
}
