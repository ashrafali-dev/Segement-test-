package com.ashraf.novelstudio

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Tee" for the images the reader page loads.
 *
 * Image-OCR modes (Web Mode scan, AI real-time) used to download every panel a SECOND time with a plain
 * HttpURLConnection. Many sites refuse that second request (hotlink protection, wrong Referer, CORS,
 * Cloudflare), which showed up as "Found 47 images but none could be downloaded" and made every
 * non-WebToon site slow (long timeouts per panel) or fail.
 *
 * This is the approach used by reader apps that wrap a WebView: the request the WebView itself makes is
 * fetched ONCE with the exact headers the WebView chose (User-Agent, Referer, Accept, client hints) plus its
 * cookies. The bytes are handed back to the page (so the image renders normally) AND kept in a small disk
 * cache. [WebModeExtractor] then reads the panel from this cache: no second download, nothing for the CDN to
 * block, and the OCR can start the moment the page has loaded the image.
 *
 * Anything unusual (non-200, non-image reply, huge file, range request, blocked host) returns null, so the
 * WebView simply loads the image itself exactly as before. The tee can never make a page worse.
 */
internal object ImageTap {
    private const val TAG = "NsImageTap"
    private const val MAX_BYTES = 14 * 1024 * 1024
    private const val MAX_DISK_BYTES = 160L * 1024 * 1024
    private const val MAX_AGE_MS = 20 * 60_000L
    private const val HOST_BLOCK_MS = 3 * 60_000L
    private const val HOST_FAIL_LIMIT = 3

    @Volatile private var dir: File? = null
    private val hostFails = ConcurrentHashMap<String, Int>()
    private val hostBlockedUntil = ConcurrentHashMap<String, Long>()
    private val stores = AtomicInteger(0)

    private val SKIP_RESPONSE_HEADERS = setOf(
        "content-length", "content-encoding", "transfer-encoding", "connection", "keep-alive",
        "set-cookie", "set-cookie2", "content-security-policy", "x-frame-options"
    )
    private val SKIP_REQUEST_HEADERS = setOf("range", "accept-encoding", "host", "connection", "if-range")
    private val IMAGE_EXT = Regex(".*\\.(jpe?g|png|webp|avif|jfif)$")

    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            val d = File(context.applicationContext.cacheDir, "ns_img_tap")
            try { d.mkdirs() } catch (_: Exception) {}
            dir = d
            prune(0L)   // drop leftovers of earlier sessions
        }
    }

    private fun keyOf(url: String): String {
        val clean = url.substringBefore('#')
        val md = MessageDigest.getInstance("SHA-1").digest(clean.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }

    /** Bytes the WebView already downloaded for [url], or null. */
    fun get(url: String): ByteArray? {
        val d = dir ?: return null
        return try {
            val f = File(d, keyOf(url))
            if (!f.isFile) return null
            if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) { f.delete(); return null }
            val b = f.readBytes()
            if (b.size < 64) null else b
        } catch (_: Throwable) { null }
    }

    private fun store(url: String, bytes: ByteArray) {
        val d = dir ?: return
        try {
            val f = File(d, keyOf(url))
            val tmp = File(d, f.name + ".tmp" + Thread.currentThread().id)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            if (stores.incrementAndGet() % 25 == 0) prune(MAX_DISK_BYTES)
        } catch (_: Throwable) { }
    }

    /** limit == 0: delete everything older than MAX_AGE_MS; otherwise also trim to 70 % of the limit, oldest first. */
    private fun prune(limit: Long) {
        val d = dir ?: return
        try {
            val files = d.listFiles() ?: return
            val now = System.currentTimeMillis()
            var total = 0L
            val keep = ArrayList<File>()
            for (f in files) {
                if (now - f.lastModified() > MAX_AGE_MS || f.name.contains(".tmp")) { f.delete(); continue }
                total += f.length(); keep.add(f)
            }
            if (limit > 0L && total > limit) {
                keep.sortBy { it.lastModified() }
                val target = (limit * 0.7).toLong()
                for (f in keep) {
                    if (total <= target) break
                    total -= f.length(); f.delete()
                }
            }
        } catch (_: Throwable) { }
    }

    private fun noteFail(host: String) {
        val n = (hostFails[host] ?: 0) + 1
        hostFails[host] = n
        if (n >= HOST_FAIL_LIMIT) {
            hostBlockedUntil[host] = System.currentTimeMillis() + HOST_BLOCK_MS
            hostFails[host] = 0
            Log.w(TAG, "tee disabled for $host for a few minutes (the WebView loads these images itself)")
        }
    }

    /**
     * Called from WebViewClient.shouldInterceptRequest (a WebView IO thread). Returns a response carrying the
     * image bytes, or null to let the WebView load the request itself.
     */
    fun intercept(context: Context, r: WebResourceRequest): WebResourceResponse? {
        try {
            if (r.isForMainFrame) return null
            if (!r.method.equals("GET", true)) return null
            val uri = r.url ?: return null
            val scheme = uri.scheme ?: return null
            if (!scheme.equals("http", true) && !scheme.equals("https", true)) return null
            val host = uri.host ?: return null
            val headers = r.requestHeaders ?: return null
            if (headers.keys.any { it.equals("Range", true) }) return null
            val path = (uri.path ?: "").lowercase()
            if (path.endsWith(".svg") || path.endsWith(".gif") || path.endsWith(".ico")) return null
            val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
            val looksImage = (accept.contains("image/", true) && !accept.contains("text/html", true)) ||
                IMAGE_EXT.matches(path)
            if (!looksImage) return null
            val blocked = hostBlockedUntil[host]
            if (blocked != null) {
                if (System.currentTimeMillis() < blocked) return null
                hostBlockedUntil.remove(host)
            }
            init(context)

            val url = uri.toString()
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                for ((k, v) in headers) {
                    if (k.lowercase() in SKIP_REQUEST_HEADERS) continue
                    try { c.setRequestProperty(k, v) } catch (_: Exception) { }
                }
                c.setRequestProperty("Accept-Encoding", "identity")
                if (headers.keys.none { it.equals("Cookie", true) }) {
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
                }
                val code = c.responseCode
                if (code != 200) { noteFail(host); return null }
                val ctype = c.contentType.orEmpty()
                // Challenge / error pages are HTML: let the browser handle those itself.
                if (!ctype.startsWith("image/", true) && !ctype.startsWith("application/octet-stream", true)) {
                    noteFail(host); return null
                }
                if (c.contentLengthLong > MAX_BYTES) return null
                val out = java.io.ByteArrayOutputStream(maxOf(32 * 1024, c.contentLength))
                val buf = ByteArray(16 * 1024)
                var total = 0
                c.inputStream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return null
                        out.write(buf, 0, n)
                    }
                }
                val bytes = out.toByteArray()
                if (bytes.size < 64) { noteFail(host); return null }
                hostFails[host] = 0
                store(url, bytes)

                val mime = ctype.substringBefore(';').trim().ifEmpty { "image/jpeg" }
                val respHeaders = HashMap<String, String>()
                for ((k, v) in c.headerFields) {
                    if (k == null || v.isNullOrEmpty()) continue
                    if (k.lowercase() in SKIP_RESPONSE_HEADERS) continue
                    respHeaders[k] = v.joinToString(", ")
                }
                return WebResourceResponse(mime, null, 200, "OK", respHeaders, ByteArrayInputStream(bytes))
            } finally {
                try { c.disconnect() } catch (_: Exception) { }
            }
        } catch (_: Throwable) {
            val h = try { r.url?.host } catch (_: Throwable) { null }
            if (h != null) noteFail(h)
            return null
        }
    }
}
