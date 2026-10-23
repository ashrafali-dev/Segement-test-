package com.ashraf.novelstudio

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home-screen launcher/downloader for the standalone comic and novel readers.
 * When installed, the app opens normally. Otherwise it resolves the latest official
 * GitHub release, selects this phone's ABI APK, and downloads it to Downloads.
 */
object Hub {
    enum class Part(val title: String, val packages: List<String>) {
        WEBTOON("Taihon", listOf("app.taihon", "app.taihon.foss", "app.taihon.dev", "app.taihon.debug")),
        NOVEL("LNReader", listOf("com.rajarsheechatterjee.LNReader"))
    }

    private data class ApkAsset(val name: String, val url: String)

    private fun abi(): String? = Build.SUPPORTED_ABIS.firstOrNull {
        it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" || it == "x86"
    }

    fun installedPackage(ctx: Context, part: Part): String? =
        part.packages.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    fun open(activity: Activity, part: Part) {
        val pkg = installedPackage(activity, part)
        if (pkg != null) {
            val launch = activity.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                try { activity.startActivity(launch); return }
                catch (_: Exception) { /* Try the downloader below. */ }
            }
        }

        Toast.makeText(activity, "Finding the latest ${part.title} APK…", Toast.LENGTH_SHORT).show()
        Thread {
            val asset = runCatching { latestAsset(part) }.getOrNull() ?: fallbackAsset(part, abi())
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                try {
                    val request = DownloadManager.Request(Uri.parse(asset.url))
                        .setTitle("${part.title} APK")
                        .setDescription("Downloading the official ${part.title} release")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, asset.name)
                    val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    Toast.makeText(activity, "⬇️ ${part.title} APK download started. Check notifications.", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    // Last-resort fallback: open the official release page in the browser.
                    val releases = if (part == Part.WEBTOON)
                        "https://github.com/Saud-97/taihon/releases/latest"
                    else "https://github.com/lnreader/lnreader/releases/latest"
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releases))) }
                    Toast.makeText(activity, "Could not start direct download; opened the official releases page.", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; name = "hub-apk-download-link" }.start()
    }

    private fun latestAsset(part: Part): ApkAsset {
        val api = when (part) {
            Part.WEBTOON -> "https://api.github.com/repos/Saud-97/taihon/releases/latest"
            Part.NOVEL -> "https://api.github.com/repos/lnreader/lnreader/releases/latest"
        }
        val connection = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NovelStudio-Hub/1")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub release lookup HTTP $code")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = release.optString("tag_name")
            val assets = release.optJSONArray("assets") ?: throw IllegalStateException("No release assets")
            val abi = abi()
            val apkAssets = (0 until assets.length()).mapNotNull { i ->
                val item = assets.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name")
                val url = item.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://github.com/")) ApkAsset(name, url) else null
            }
            if (part == Part.WEBTOON) {
                // Taihon names its split APKs taihon-<abi>-<version>.apk; the non-FOSS
                // taihon-<version>.apk asset is the universal build.
                val split = abi?.let { wanted -> apkAssets.firstOrNull { it.name.startsWith("taihon-$wanted-") } }
                if (split != null) return split
                return apkAssets.firstOrNull { it.name == "taihon-$tag.apk" }
                    ?: throw IllegalStateException("Taihon APK not found for release $tag")
            }

            // LNReader release filenames include both the tag and ABI. If no matching ABI exists,
            // use its official universal APK.
            val split = abi?.let { wanted ->
                apkAssets.firstOrNull { it.name.equals("LNReader-$tag-$wanted.apk", ignoreCase = true) }
            }
            if (split != null) return split
            return apkAssets.firstOrNull { it.name.equals("LNReader-$tag-universal.apk", ignoreCase = true) }
                ?: throw IllegalStateException("LNReader APK not found for release $tag")
        } finally {
            connection.disconnect()
        }
    }

    /** Works if GitHub's release API is temporarily unavailable; URLs verified against current releases. */
    private fun fallbackAsset(part: Part, abi: String?): ApkAsset {
        val url = when (part) {
            Part.WEBTOON -> when (abi) {
                "arm64-v8a" -> "https://github.com/Saud-97/taihon/releases/download/v0.20.4/taihon-arm64-v8a-v0.20.4.apk"
                "armeabi-v7a" -> "https://github.com/Saud-97/taihon/releases/download/v0.20.4/taihon-armeabi-v7a-v0.20.4.apk"
                "x86" -> "https://github.com/Saud-97/taihon/releases/download/v0.20.4/taihon-x86-v0.20.4.apk"
                "x86_64" -> "https://github.com/Saud-97/taihon/releases/download/v0.20.4/taihon-x86_64-v0.20.4.apk"
                else -> "https://github.com/Saud-97/taihon/releases/download/v0.20.4/taihon-v0.20.4.apk"
            }
            Part.NOVEL -> when (abi) {
                "arm64-v8a" -> "https://github.com/lnreader/lnreader/releases/download/v2.1.4/LNReader-v2.1.4-arm64-v8a.apk"
                "armeabi-v7a" -> "https://github.com/lnreader/lnreader/releases/download/v2.1.4/LNReader-v2.1.4-armeabi-v7a.apk"
                "x86" -> "https://github.com/lnreader/lnreader/releases/download/v2.1.4/LNReader-v2.1.4-x86.apk"
                "x86_64" -> "https://github.com/lnreader/lnreader/releases/download/v2.1.4/LNReader-v2.1.4-x86_64.apk"
                else -> "https://github.com/lnreader/lnreader/releases/download/v2.1.4/LNReader-v2.1.4-universal.apk"
            }
        }
        return ApkAsset(url.substringAfterLast('/'), url)
    }

    /** novelstudio://open?url=<encoded http(s) link> -> the link, or null. */
    fun urlFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "novelstudio" || data.host != "open") return null
        val value = data.getQueryParameter("url")?.trim() ?: return null
        return value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
