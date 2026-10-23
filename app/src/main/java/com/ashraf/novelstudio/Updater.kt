package com.ashraf.novelstudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update from GitHub Releases. Publish a release whose tag is the new version (v1.6.4) and attach the APK.
 * Set [DEFAULT_REPO] to "owner/name" once; until then the app asks for it the first time.
 */
object Updater {
    const val DEFAULT_REPO = ""

    class Info(val version: String, val notes: String, val apkUrl: String)

    fun repo(c: Context): String = Prefs.get(c, "updateRepo", DEFAULT_REPO).trim()

    fun currentVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun main(block: () -> Unit) { Handler(Looper.getMainLooper()).post(block) }

    /** [cb]: (info, null) = update available, (null, null) = up to date, (null, message) = problem. Main thread. */
    fun check(c: Context, cb: (Info?, String?) -> Unit) {
        val repo = repo(c)
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(repo)) { cb(null, "Set the GitHub repository first (owner/name)"); return }
        val local = currentVersion(c)
        Thread {
            var info: Info? = null
            var err: String? = null
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "NovelStudio-Updater")
                if (conn.responseCode == 404) err = "No release found in $repo"
                else if (conn.responseCode != 200) err = "GitHub answered " + conn.responseCode
                else {
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = o.optString("tag_name")
                    val assets = o.optJSONArray("assets")
                    var apk = ""
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a.optString("browser_download_url"); break }
                    }
                    if (isNewer(tag, local)) {
                        if (apk.isEmpty()) err = "Release $tag has no .apk attached"
                        else info = Info(tag.removePrefix("v"), o.optString("body").take(900), apk)
                    }
                }
            } catch (e: Exception) { err = "Could not check: " + (e.message ?: "network error") }
            main { cb(info, err) }
        }.start()
    }

    fun download(c: Context, info: Info, onProgress: (Int) -> Unit, cb: (File?, String?) -> Unit) {
        val app = c.applicationContext
        Thread {
            var out: File? = null
            var err: String? = null
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "NovelStudio-${info.version}.apk")
                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                val total = conn.contentLength.toLong()
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    f.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 10 == 0) { lastPct = pct; main { onProgress(pct) } }
                            }
                        }
                    }
                }
                if (f.length() < 100_000) err = "Download looks incomplete" else out = f
            } catch (e: Exception) { err = "Download failed: " + (e.message ?: "network error") }
            main { cb(out, err) }
        }.start()
    }

    /** Opens the system installer. Returns false when the user must first allow installs from this app. */
    fun install(a: Activity, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return false
        }
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        a.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
