package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Short-lived, opt-in image replacements used only by the Web Mode text remover.
 *
 * The source page's <img> is left untouched. WebModeWriter draws a pointer-transparent cleaned copy
 * over the same rectangle; this avoids breaking lazy loaders, srcset, clicks, or the site's own JS.
 * A marker query keeps the request on the source image's origin, which is friendlier to page CSPs.
 */
internal object WebModeTextRemovalCache {
    private const val MARKER = "__ns_cleaned"
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 80

    private data class Entry(val file: File, val mime: String, val bytes: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var usedBytes = 0L
    /** Per source image: keys of the artwork (soft) bubbles whose lettering the remover really erased. */
    private val erasedSoft = LinkedHashMap<String, Set<String>>()

    /** Stable id of a bubble by its (rounded) position; the writer looks it up with the same function. */
    fun bubbleKey(b: WmBubble): String =
        "${Math.round(b.cx * 1000f)}:${Math.round(b.cy * 1000f)}:${Math.round(b.w * 1000f)}:${Math.round(b.h * 1000f)}"

    @Synchronized
    fun setErased(sourceUrl: String, keys: Set<String>) {
        erasedSoft[key(sourceUrl)] = keys
        while (erasedSoft.size > 200) erasedSoft.remove(erasedSoft.keys.first())
    }

    @Synchronized
    fun isErased(sourceUrl: String, bubbleKey: String): Boolean =
        erasedSoft[key(sourceUrl)]?.contains(bubbleKey) == true
    private var root: File? = null

    @Synchronized
    fun store(context: Context, sourceUrl: String, encodedImage: ByteArray, mime: String = "image/webp"): Boolean {
        val key = key(sourceUrl)
        if (key.isBlank() || encodedImage.isEmpty() || encodedImage.size > 18 * 1024 * 1024) return false
        return try {
            val dir = directory(context)
            val digest = sha256(key)
            val ext = if (mime.equals("image/png", true)) "png" else "webp"
            val target = File(dir, "$digest.$ext")
            val part = File(dir, "$digest.part")
            FileOutputStream(part).use { it.write(encodedImage) }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return false
            }
            entries.remove(key)?.let { usedBytes -= it.bytes; if (it.file != target) it.file.delete() }
            entries[key] = Entry(target, mime, target.length())
            usedBytes += target.length()
            evict()
            entries[key]?.file?.isFile == true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun has(sourceUrl: String): Boolean {
        val entry = entries[key(sourceUrl)] ?: return false
        if (entry.file.isFile && entry.file.length() == entry.bytes) return true
        entries.remove(key(sourceUrl))
        usedBytes = entries.values.sumOf { it.bytes }
        return false
    }

    @Synchronized
    fun urlFor(sourceUrl: String): String? {
        if (!has(sourceUrl)) return null
        val raw = sourceUrl.substringBefore('#')
        if (Uri.parse(raw).getQueryParameter(MARKER) == "1") return raw
        val sep = when {
            '?' !in raw -> "?"
            raw.endsWith("?") || raw.endsWith("&") -> ""
            else -> "&"
        }
        return raw + sep + MARKER + "=1"
    }

    /** Called by NovelClient.shouldInterceptRequest; non-remover requests always fall through. */
    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.getQueryParameter(MARKER) != "1") return null
        val source = stripMarker(uri.toString())
        val entry = synchronized(this) {
            val e = entries[key(source)] ?: return null
            if (!e.file.isFile || e.file.length() != e.bytes) {
                entries.remove(key(source))
                usedBytes = entries.values.sumOf { it.bytes }
                return null
            }
            e
        }
        return try {
            WebResourceResponse(entry.mime, null, 200, "OK",
                mapOf("Cache-Control" to "no-store, max-age=0", "Access-Control-Allow-Origin" to "*"),
                FileInputStream(entry.file))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        entries.values.forEach { runCatching { it.file.delete() } }
        entries.clear()
        erasedSoft.clear()
        usedBytes = 0L
        val dir = root ?: File(context.cacheDir, "wm_text_removed")
        runCatching { if (dir.exists()) dir.deleteRecursively() }
        root = null
    }

    private fun directory(context: Context): File {
        val current = root
        if (current != null && current.isDirectory) return current
        return File(context.cacheDir, "wm_text_removed").also {
            if (!it.exists() && !it.mkdirs()) error("Cannot create text-removal cache")
            root = it
        }
    }

    private fun evict() {
        val iterator = entries.entries.iterator()
        while ((usedBytes > MAX_BYTES || entries.size > MAX_ENTRIES) && iterator.hasNext()) {
            val e = iterator.next().value
            usedBytes -= e.bytes
            runCatching { e.file.delete() }
            iterator.remove()
        }
    }

    private fun key(url: String): String = stripMarker(url).substringBefore('#').trim()

    private fun stripMarker(raw: String): String {
        val noFragment = raw.substringBefore('#')
        val q = noFragment.indexOf('?')
        if (q < 0) return noFragment
        val base = noFragment.substring(0, q)
        val params = noFragment.substring(q + 1).split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != MARKER }
        return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
