package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Google Lens as an OCR engine. It ONLY reads the text in the picture: the request carries no
 * translate filter and nothing here knows about languages to translate into. Translation stays the
 * job of NovelStudio's chatbot; this class returns the same [OcrBlock]s every other engine returns.
 *
 * Transport: the (undocumented, unofficial) Chromium Lens upload endpoint, see [LensProtocol].
 * Because it is not a supported API this engine never trusts it blindly:
 *  - every failure (offline, DNS, reset, timeout, 4xx/5xx, unparseable or "no objects" reply) is thrown
 *    as [OcrUnavailableException]; [RoutingOcrEngine] then reads the page with ML Kit instead, and an
 *    unusable Lens reply is never reported as a successful scan;
 *  - a circuit breaker stops hammering a dead endpoint: after repeated transient failures Lens is
 *    skipped for a short while, after a rejected key / changed protocol for a long while, so scans
 *    do not stall on timeouts and the user is not spammed with errors.
 *
 * Network hardening (the two failures this was written against):
 *  - `UnknownHostException` (EAI_NODATA / "No address associated with hostname"): connectivity is
 *    checked first (fail fast when offline); the request is retried with backoff and ALTERNATES between
 *    a connection bound to the active [Network] (its own DNS) and the process default route, because a
 *    stale default network / VPN / Private-DNS edge case can fail one path while the other works.
 *  - `SocketException: Socket closed` / connection reset / EOF: stream a fixed-length body and
 *    fully drain successful responses so Android can reuse a healthy keep-alive connection, matching
 *    the pooled-client strategy in chrome-lens-py. Only partial/error responses are disconnected and retried.
 *    A real thread interrupt is NOT retried (it is a cancellation, not a network fault).
 */
internal class LensOcrEngine(context: Context) : OcrEngine {

    private val app: Context = context.applicationContext
    private val random = SecureRandom()

    // Concurrent foreground/read-ahead Lens requests must keep their diagnostics request-local.
    private val lastHttpStatus = ThreadLocal<Int>()
    private val lastHttpMeta = ThreadLocal<String>()

    private fun currentHttpStatus(): Int = lastHttpStatus.get() ?: 0
    private fun currentHttpMeta(): String = lastHttpMeta.get() ?: "no HTTP response yet"

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)
    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script, fastMode = true)
    // A region crop is the user's own selection (manual extract / area select): never second-guess it with the
    // realtime non-bubble heuristic, which threw away narrow or small Japanese bubbles before they were sent.
    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        read(bitmap, script, skipBubblePrefilter = true)

    /** Force a genuine Lens request for manual Extract / Rescan instead of replaying cached OCR. */
    fun recognizeFresh(bitmap: Bitmap, script: OcrScript, fastMode: Boolean = false): OcrResult =
        read(bitmap, script, fastMode = fastMode, bypassCache = true)

    /**
     * The router can request a fallback pass after a partial read. Return the cached Lens result for
     * the same bitmap (read() hits its image cache) rather than reporting an artificial empty result,
     * which incorrectly changes the actual-engine status to ML Kit.
     */
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)

    override fun close() { synchronized(cache) { cache.clear() } }

    // ---------------------------------------------------------------------------------------------

    private fun read(
        bitmap: Bitmap, script: OcrScript, fastMode: Boolean = false, bypassCache: Boolean = false,
        skipBubblePrefilter: Boolean = false
    ): OcrResult {
        if (bitmap.isRecycled) throw OcrUnavailableException("Google Lens: bitmap was recycled")
        if (bitmap.width < MIN_SIDE || bitmap.height < MIN_SIDE) return OcrResult(emptyList())

        // Skip crops that are never dialogue: tiny UI/logo tiles and near-square boxes. Speech bubbles are
        // wide, tall, or at least sizable; the logs showed 220x202, 96x54, 111x63 … being uploaded 8 times
        // each and always coming back empty. This runs before prepare()/upload, so it is free.
        // A user-selected region (recognizeRegion) skips this: narrow Japanese bubbles are legitimate.
        if (!skipBubblePrefilter && looksLikeNonBubble(bitmap.width, bitmap.height)) {
            Log.i(TAG, "PRE_FILTER skip ${bitmap.width}x${bitmap.height} (not a text bubble)")
            return OcrResult(emptyList())
        }

        val t0 = SystemClock.elapsedRealtime()
        val prepared = prepare(bitmap)
        val prepareMs = SystemClock.elapsedRealtime() - t0
        val keyStart = SystemClock.elapsedRealtime()
        val key = fnv64(prepared.jpeg) xor (prepared.w.toLong() shl 32) xor prepared.h.toLong()
        // Region-stable key: the JPEG hash changes with compression noise between identical repeat crops,
        // so a short negative cache on (size + coarse pixel signature) stops the same empty region being
        // re-uploaded on every scroll frame even when the encoder output differs by a byte.
        val regionKey = fnv64("${bitmap.width}x${bitmap.height}:${coarseSignature(bitmap)}".toByteArray())

        val negative = if (bypassCache) false else recentlyEmpty(regionKey)
        if (negative) {
            Log.i(TAG, "NEG_CACHE skip region=${bitmap.width}x${bitmap.height} (empty within ${EMPTY_CACHE_TTL_MS}ms)")
            return OcrResult(emptyList())
        }

        // Lens detects the image language itself. This cache is mainly for external callers that repeat one bitmap.
        val cachedLines = if (bypassCache) null else cached(key)
        val keyCacheMs = SystemClock.elapsedRealtime() - keyStart
        if (bypassCache) Log.i(TAG, "CACHE_BYPASS imageId=${java.lang.Long.toHexString(key)} reason=manual_extract")
        if (cachedLines != null) {
            val cachedLetters = cachedLines.sumOf { block -> block.text.count { ch -> ch.isLetterOrDigit() } }
            val result = finish(cachedLines, bitmap, script)
            val totalMs = SystemClock.elapsedRealtime() - t0
            val diagnostic = "LENS_CACHE_HIT imageId=${java.lang.Long.toHexString(key)} script=${script.name} " +
                "image=${bitmap.width}x${bitmap.height} cachedLines=${cachedLines.size} cachedLetters=$cachedLetters " +
                "timing=prepare:${prepareMs}ms,keyCache:${keyCacheMs}ms,post:${(totalMs - prepareMs - keyCacheMs).coerceAtLeast(0L)}ms,total:${totalMs}ms"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.i(TAG, diagnostic)
            Log.i(TAG, "TIMING imageId=${java.lang.Long.toHexString(key)} cacheHit=true prepareMs=$prepareMs keyCacheMs=$keyCacheMs totalMs=$totalMs")
            return result
        }

        Breaker.check()
        val payloadStart = SystemClock.elapsedRealtime()
        val payload = LensProtocol.buildObjectsRequest(
            jpeg = prepared.jpeg, width = prepared.w, height = prepared.h,
            language = languageHint(), region = regionHint(), timeZone = zoneHint(),
            uuid = random.nextLong() and Long.MAX_VALUE,
            timeUsec = System.currentTimeMillis() * 1000L,
        )
        val payloadBuildMs = SystemClock.elapsedRealtime() - payloadStart

        Log.i(TAG, "request begin imageId=${java.lang.Long.toHexString(key)} script=${script.name} source=${bitmap.width}x${bitmap.height} " +
            "upload=${prepared.w}x${prepared.h} jpegBytes=${prepared.jpeg.size} payloadBytes=${payload.size} " +
            "endpoint=${endpoint().substringAfter("://").substringBefore("/")}")
        var networkMs = 0L
        val networkStart = SystemClock.elapsedRealtime()
        val body = try {
            post(payload, fastMode).also { networkMs = SystemClock.elapsedRealtime() - networkStart }
        } catch (e: OcrUnavailableException) {
            networkMs = SystemClock.elapsedRealtime() - networkStart
            val message = e.message ?: e.javaClass.simpleName
            // netInfo() can fail fast before the transport-level retry logic sees an offline state.
            if (message.contains("no internet connection", ignoreCase = true)) {
                Breaker.transient("no internet connection")
            }
            val diagnostic = "request failed; networkMs=$networkMs; totalMs=${SystemClock.elapsedRealtime() - t0}; http=${currentHttpStatus()}; ${currentHttpMeta()}; error=$message"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.e(TAG, diagnostic)
            throw e
        }

        var parseMs = 0L
        val parseStart = SystemClock.elapsedRealtime()
        val response = try {
            LensProtocol.parseResponse(body).also { parseMs = SystemClock.elapsedRealtime() - parseStart }
        } catch (e: LensProtocol.ProtocolException) {
            parseMs = SystemClock.elapsedRealtime() - parseStart
            val diagnostic = "parse failed; parseMs=$parseMs; totalMs=${SystemClock.elapsedRealtime() - t0}; http=${currentHttpStatus()}; ${currentHttpMeta()}; replyBytes=${body.size}; " +
                "reason=${e.message ?: "unknown"}"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.e(TAG, diagnostic)
            // 200 OK but not a Lens reply (captive portal, changed protocol, ...): do not trust, do not retry.
            Breaker.permanent("unreadable reply")
            throw OcrUnavailableException("Google Lens: unreadable reply (${e.message})", e)
        }
        val paragraphCount = response.paragraphs.size
        val lineCount = response.paragraphs.sumOf { it.lines.size }
        val nonEmptyLineCount = response.paragraphs.sumOf { p -> p.lines.count { it.text.isNotBlank() } }
        val textChars = response.paragraphs.sumOf { p -> p.lines.sumOf { it.text.length } }
        val lineBoxes = response.paragraphs.sumOf { p -> p.lines.count { it.box != null } }
        val paragraphBoxes = response.paragraphs.count { it.box != null }
        if (response.serverError != 0) {
            val diagnostic = "server error; http=${currentHttpStatus()}; ${currentHttpMeta()}; replyBytes=${body.size}; " +
                "objects=${response.valid}; serverError=${response.serverError}; paragraphs=$paragraphCount lines=$lineCount"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.w(TAG, diagnostic)
            Breaker.transient("server error ${response.serverError}")
            throw OcrUnavailableException("Google Lens: server error ${response.serverError}")
        }
        if (!response.valid) {
            val diagnostic = "missing objects_response; http=${currentHttpStatus()}; ${currentHttpMeta()}; replyBytes=${body.size}; " +
                "objects=false; paragraphs=$paragraphCount lines=$lineCount"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.w(TAG, diagnostic)
            Breaker.transient("reply without objects")
            throw OcrUnavailableException("Google Lens: reply without text layer")
        }

        val mapStart = SystemClock.elapsedRealtime()
        val allBlocks = LensProtocol.toBlocks(response, bitmap.width, bitmap.height, prepared.w, prepared.h)
        // Text WITHOUT a position is not a complete OCR result: it can be neither placed in the right bubble nor
        // audited, and caching it would freeze the gap. Report it as a failure (not "no text found") and do not cache.
        val positionlessLines = response.paragraphs.sumOf { p ->
            p.lines.count { it.text.isNotBlank() && it.box == null && p.box == null && !OcrPost.isSiteMark(it.text) }
        }
        if (allBlocks.isEmpty() && positionlessLines > 0) {
            val diagnostic = "TEXT_WITHOUT_POSITIONS http=${currentHttpStatus()}; paragraphs=$paragraphCount lines=$lineCount " +
                "positionlessLines=$positionlessLines lineBoxes=$lineBoxes paragraphBoxes=$paragraphBoxes shape=${response.shape}"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.w(TAG, diagnostic)
            throw OcrUnavailableException("Google Lens: text without positions")
        }
        val lines = allBlocks.filterNot { OcrPost.isNoiseLens(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val mapMs = SystemClock.elapsedRealtime() - mapStart
        val totalMs = SystemClock.elapsedRealtime() - t0
        val timing = "timing=prepare:${prepareMs}ms,cache:${keyCacheMs}ms,payload:${payloadBuildMs}ms," +
            "http:${networkMs}ms,parse:${parseMs}ms,map:${mapMs}ms,total:${totalMs}ms"
        val diagnostic = "imageId=${java.lang.Long.toHexString(key)}; fastMode=$fastMode; http=${currentHttpStatus()}; ${currentHttpMeta()}; " +
            "replyBytes=${body.size}; objects=${response.valid}; " +
            "shape=${response.shape}; paragraphs=$paragraphCount lines=$lineCount nonEmptyLines=$nonEmptyLineCount " +
            "textChars=$textChars lineBoxes=$lineBoxes paragraphBoxes=$paragraphBoxes " +
            "blocksBeforeNoise=${allBlocks.size} blocksAfterNoise=${lines.size} positionlessLines=$positionlessLines " +
            "script=${script.name} upload=${prepared.w}x${prepared.h}; $timing"
        OcrEngineStatus.setLensDiagnostic(diagnostic)
        if (lines.isEmpty()) Log.w(TAG, "NO_TEXT $diagnostic") else Log.i(TAG, "PARSED $diagnostic")
        Breaker.success()
        remember(key, lines)
        // Remember the empty region so the same crop is not re-uploaded on the next scroll frame.
        if (lines.isEmpty()) rememberEmptyRegion(regionKey) else forgetEmptyRegion(regionKey)
        Log.i(TAG, "ok lines=${lines.size} sent=${prepared.w}x${prepared.h} bytes=${prepared.jpeg.size} ms=$totalMs")
        Log.i(TAG, "TIMING imageId=${java.lang.Long.toHexString(key)} cacheHit=false prepareMs=$prepareMs keyCacheMs=$keyCacheMs " +
            "payloadBuildMs=$payloadBuildMs networkMs=$networkMs parseMs=$parseMs mapMs=$mapMs totalMs=$totalMs")
        val mergeStart = SystemClock.elapsedRealtime()
        val finished = finish(lines, bitmap, script)
        OcrDiag.log("LENS_MERGE", "mergeNeighbours ms=${SystemClock.elapsedRealtime() - mergeStart} blocksIn=${lines.size} blocksOut=${finished.blocks.size} " +
            "| whole read() total=${SystemClock.elapsedRealtime() - t0}ms of which network=${networkMs}ms")
        return finished
    }

    /**
     * Lens blocks are already whole paragraphs (see LensProtocol.toBlocks). The old second geometric merge
     * here ran again inside WebModeExtractor.readSlice and could only do harm (double pixel sampling, and it
     * could join across a paragraph boundary Lens had drawn on purpose), so the lines pass through untouched.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun finish(lines: List<OcrBlock>, bitmap: Bitmap, script: OcrScript): OcrResult = OcrResult(lines)

    // ---- image preparation ---------------------------------------------------------------------

    private class Prepared(val jpeg: ByteArray, val w: Int, val h: Int)

    private fun prepare(src: Bitmap): Prepared {
        var work: Bitmap = src
        var owned: Bitmap? = null
        try {
            // Match chrome-lens-py's proven Lens image preparation: only flatten images that
            // contain REAL transparency (ARGB_8888 / PixelCopy bitmaps often have alpha enabled but
            // every pixel is opaque). This avoids an unnecessary full-frame Canvas copy on every scan.
            val alpha = inspectTransparency(src)
            if (alpha.hasTransparency) {
                // Lens's server composites transparent uploads onto black. Pick the contrasting
                // background from actual opaque-pixel luminance so light text is not lost on white.
                val background = if (alpha.meanLuma < 128) Color.WHITE else Color.BLACK
                val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(background); drawBitmap(src, 0f, 0f, null) }
                owned = flat
                work = flat
            }
            val area = work.width.toLong() * work.height.toLong()
            if (area > MAX_AREA && (work.width > MAX_DIMENSION || work.height > MAX_DIMENSION)) {
                // Match Chromium Lens image preparation: retain native dimensions for ordinary
                // phone screenshots; only resize when the image exceeds the area AND 1600px bounds.
                val k = minOf(MAX_DIMENSION.toDouble() / work.width, MAX_DIMENSION.toDouble() / work.height)
                val sw = (work.width * k).toInt().coerceAtLeast(1)
                val sh = (work.height * k).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(work, sw, sh, true)
                if (scaled !== work) {
                    val old = owned
                    owned = scaled
                    work = scaled
                    if (old != null && old !== src && !old.isRecycled) old.recycle()
                }
            }
            var jpeg = encode(work, JPEG_QUALITY)
            if (jpeg.size > MAX_UPLOAD_BYTES) jpeg = encode(work, 25)
            var shrinkAttempt = 0
            while (jpeg.size > MAX_UPLOAD_BYTES && shrinkAttempt < 3 &&
                work.width > MIN_SIDE * 2 && work.height > MIN_SIDE * 2) {
                val sw = (work.width * 0.75f).toInt().coerceAtLeast(MIN_SIDE)
                val sh = (work.height * 0.75f).toInt().coerceAtLeast(MIN_SIDE)
                val scaled = Bitmap.createScaledBitmap(work, sw, sh, true)
                if (scaled !== work) {
                    val old = owned
                    owned = scaled
                    work = scaled
                    if (old != null && old !== src && old !== scaled && !old.isRecycled) old.recycle()
                }
                jpeg = encode(work, 25)
                shrinkAttempt++
            }
            if (jpeg.size > MAX_UPLOAD_BYTES) jpeg = encode(work, 15)
            if (jpeg.size > MAX_UPLOAD_BYTES) throw OcrUnavailableException("Google Lens: picture too large after JPEG compression and resize")
            return Prepared(jpeg, work.width, work.height)
        } finally {
            val o = owned
            if (o != null && o !== src && !o.isRecycled) o.recycle()
        }
    }

    private class AlphaInfo(val hasTransparency: Boolean, val meanLuma: Int)

    /**
     * Port of chrome-lens-py's has_meaningful_alpha/_contrasting_background logic.
     * A full pixel scan is only needed when Bitmap reports alpha support. Rows are
     * chunked to avoid allocating a full-screen IntArray alongside the image bitmap.
     */
    private fun inspectTransparency(bitmap: Bitmap): AlphaInfo {
        if (!bitmap.hasAlpha()) return AlphaInfo(false, 255)
        // A sparse 64x64 grid finds any transparency that matters (a whole transparent area is hit by dozens of
        // samples) in ~4k getPixel calls instead of copying every pixel of a multi-megapixel screenshot.
        val w = bitmap.width; val h = bitmap.height
        val stepX = maxOf(1, w / 64); val stepY = maxOf(1, h / 64)
        var transparent = false
        var luminanceSum = 0L
        var opaqueCount = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = pixel ushr 24
                if (alpha < 255) transparent = true
                if (alpha > 128) {
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    luminanceSum += (299L * red + 587L * green + 114L * blue) / 1000L
                    opaqueCount++
                }
                x += stepX
            }
            y += stepY
        }
        val mean = if (opaqueCount == 0L) 0 else (luminanceSum / opaqueCount).toInt()
        return AlphaInfo(transparent, mean)
    }

    private fun encode(b: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(maxOf(32 * 1024, b.width * b.height / 8))
        if (!b.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw OcrUnavailableException("Google Lens: JPEG encode failed")
        return out.toByteArray()
    }

    private fun fnv64(data: ByteArray): Long {
        var h = -3750763034362895579L   // FNV-1a 64-bit offset basis (0xcbf29ce484222325)
        for (b in data) { h = h xor (b.toLong() and 0xFF); h *= 0x100000001b3L }
        return h
    }

    // Auto keeps Lens's own detector; a pinned OCR language is also sent in locale_context as a hint.
    // This changes recognition context only—Lens still returns source text and never translates it.
    private fun languageHint(): String = when (Prefs.get(app, "ocrLang", "auto").lowercase(Locale.ROOT)) {
        "en", "ko", "ja", "zh" -> Prefs.get(app, "ocrLang", "auto").lowercase(Locale.ROOT)
        else -> ""
    }

    private fun regionHint(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    private fun zoneHint(): String = TimeZone.getDefault().id.takeIf { it.isNotBlank() } ?: "America/New_York"

    // ---- result cache --------------------------------------------------------------------------

    private class Cached(val at: Long, val lines: List<OcrBlock>)

    private val cache = object : LinkedHashMap<Long, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cached>?): Boolean = size > CACHE_SIZE
    }

    private fun cached(key: Long): List<OcrBlock>? = synchronized(cache) {
        val c = cache[key] ?: return@synchronized null
        // A blank Lens response should not suppress another attempt for the full positive-cache TTL.
        // Keep a brief negative-cache window to avoid hammering Lens for every identical live frame,
        // then let a later scan retry the same image in case Lens's response was transiently incomplete.
        val ttl = if (c.lines.isEmpty()) EMPTY_CACHE_TTL_MS else CACHE_TTL_MS
        if (SystemClock.elapsedRealtime() - c.at > ttl) { cache.remove(key); null } else c.lines
    }

    private fun remember(key: Long, lines: List<OcrBlock>) {
        synchronized(cache) { cache[key] = Cached(SystemClock.elapsedRealtime(), lines) }
    }

    // ---- non-bubble pre-filter + region negative cache -----------------------------------------

    /** A crop smaller/squarer than this can never be a speech bubble per the observed UI/logo tiles. */
    private fun looksLikeNonBubble(w: Int, h: Int): Boolean {
        val area = w.toLong() * h.toLong()
        if (area < MIN_BUBBLE_AREA) return true
        val longSide = maxOf(w, h).toFloat()
        val shortSide = minOf(w, h).toFloat().coerceAtLeast(1f)
        return longSide / shortSide < MIN_BUBBLE_ASPECT
    }

    /** Cheap, compression-stable fingerprint: 8x8 binary luma sampled on a coarse grid. */
    private fun coarseSignature(bmp: Bitmap): Long {
        val px = IntArray(8 * 8)
        val stepX = (bmp.width / 8).coerceAtLeast(1)
        val stepY = (bmp.height / 8).coerceAtLeast(1)
        for (gy in 0 until 8) for (gx in 0 until 8) {
            val x = (gx * stepX).coerceAtMost(bmp.width - 1)
            val y = (gy * stepY).coerceAtMost(bmp.height - 1)
            val c = bmp.getPixel(x, y)
            val luma = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 151 + (c and 0xFF) * 28) shr 8
            // One bit per cell (above/below mid-grey) keeps it stable against tiny colour/JPEG shifts.
            px[gy * 8 + gx] = if (luma >= 128) 1 else 0
        }
        return fnv64(px.joinToString("").toByteArray())
    }

    private val emptyRegions = object : LinkedHashMap<Long, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > NEG_CACHE_SIZE
    }

    private fun recentlyEmpty(regionKey: Long): Boolean = synchronized(emptyRegions) {
        val at = emptyRegions[regionKey] ?: return@synchronized false
        if (SystemClock.elapsedRealtime() - at > EMPTY_CACHE_TTL_MS) { emptyRegions.remove(regionKey); false } else true
    }

    private fun rememberEmptyRegion(regionKey: Long) {
        synchronized(emptyRegions) { emptyRegions[regionKey] = SystemClock.elapsedRealtime() }
    }

    private fun forgetEmptyRegion(regionKey: Long) {
        synchronized(emptyRegions) { emptyRegions.remove(regionKey) }
    }

    // ---- transport -----------------------------------------------------------------------------

    private class NetInfo(val online: Boolean, val network: Network?)

    /** Asks the OS first so "no connection" fails in microseconds instead of after a DNS timeout. */
    private fun netInfo(): NetInfo {
        val cm = try { app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager } catch (_: Throwable) { null }
            ?: return NetInfo(true, null)
        return try {
            val n = cm.activeNetwork ?: return NetInfo(false, null)
            val caps = cm.getNetworkCapabilities(n) ?: return NetInfo(false, null)
            NetInfo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), n)
        } catch (_: SecurityException) {
            NetInfo(true, null)       // cannot ask: just try the request
        } catch (_: RuntimeException) {
            NetInfo(true, null)
        }
    }

    private fun endpoint(): String {
        val custom = Prefs.get(app, "lensEndpoint", "").trim()
        return if (custom.startsWith("https://")) custom else DEFAULT_ENDPOINT
    }

    private fun apiKey(): String = Prefs.get(app, "lensApiKey", "").trim().ifEmpty { DEFAULT_API_KEY }

    private class HttpStatus(val code: Int, val retryAfterMs: Long) : IOException("HTTP $code")

    /** State of ONE HTTP attempt; written by the worker thread, read by the caller. */
    private class Attempt {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var body: ByteArray? = null
        @Volatile var status = 0
        @Volatile var meta = "no HTTP response yet"
    }

    private fun publish(a: Attempt) { lastHttpStatus.set(a.status); lastHttpMeta.set(a.meta) }

    /**
     * Serialises the *start* of requests so a burst is not mistaken for abuse.
     *
     * Kept separate from [REQUEST_SLOTS] on purpose: sleeping inside a permit wasted a third of the
     * concurrency, which is exactly why a full scan got slower after the slot cap was introduced.
     * Returns false when the wait would exceed the caller's remaining deadline.
     */
    private fun awaitStartTurn(maxWaitMs: Long): Boolean {
        // Reserve the next start slot under a real class-level lock, then sleep OUTSIDE it. Sleeping inside
        // the lock would serialise every Lens request behind one another — that is what made a 145-panel
        // scan take 238 s. (A per-call `object : Any() {}` as the monitor, as an earlier version had, locked
        // nothing at all.)
        val nextStart: Long
        synchronized(START_LOCK) {
            val now = SystemClock.elapsedRealtime()
            nextStart = maxOf(now, lastStartAt + START_GAP_MS)
            if (nextStart - now >= maxWaitMs) return false
            lastStartAt = nextStart
        }
        val sleep = nextStart - SystemClock.elapsedRealtime()
        if (sleep > 0L) {
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    /** One logical request: bounded retries inside one overall deadline. Throws [OcrUnavailableException] only. */
    private fun post(payload: ByteArray, fastMode: Boolean): ByteArray {
        lastHttpStatus.set(0)
        lastHttpMeta.set("no HTTP response yet")
        val deadlineMs = if (fastMode) FAST_TOTAL_DEADLINE_MS else TOTAL_DEADLINE_MS
        val attemptLimit = if (fastMode) FAST_MAX_ATTEMPTS else MAX_ATTEMPTS
        val minimumAttemptMs = if (fastMode) FAST_MIN_ATTEMPT_MS else MIN_ATTEMPT_MS
        val hedgeAfterMs = if (fastMode) FAST_HEDGE_MS else HEDGE_MS
        val connectMs = if (fastMode) FAST_CONNECT_MS else CONNECT_MS
        val started = SystemClock.elapsedRealtime()
        var last: Exception? = null
        for (attempt in 0 until attemptLimit) {
            if (Thread.currentThread().isInterrupted) throw OcrUnavailableException("Google Lens: cancelled")
            val remaining = deadlineMs - (SystemClock.elapsedRealtime() - started)
            if (remaining < minimumAttemptMs) break
            val info = netInfo()
            if (!info.online) {
                // Not counted by the breaker: going offline is cheap to detect, and the moment the connection
                // returns Lens must work again instead of staying paused.
                throw OcrUnavailableException("Google Lens: no internet connection")
            }
            var waitMs = BACKOFF_MS shl attempt
            // Space request starts BEFORE taking a slot. Holding a slot while sleeping for the gap wasted
            // one of the three concurrency permits on every panel, which is what made a full scan slow.
            if (!awaitStartTurn(remaining)) throw OcrUnavailableException("Google Lens: cancelled")
            // Cap concurrent requests: a scan firing many panels at once is exactly what earns an HTTP 429,
            // and one 429 then costs more time than the parallelism ever saved.
            if (!REQUEST_SLOTS.tryAcquire(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw OcrUnavailableException("Google Lens: request slot timeout")
            }
            try {
                return race(payload, info, remaining, connectMs, hedgeAfterMs, minimumAttemptMs, firstBound = attempt % 2 == 0)
            } catch (e: HttpStatus) {
                last = e
                if (e.code !in RETRYABLE_STATUS) {
                    // 400/401/403/404...: wrong key, blocked or changed endpoint. Retrying cannot help.
                    Breaker.permanent("HTTP ${e.code}")
                    throw OcrUnavailableException("Google Lens: HTTP ${e.code} (endpoint or key rejected)", e)
                }
                if (e.retryAfterMs > 0) waitMs = maxOf(waitMs, e.retryAfterMs)
            } catch (e: SocketTimeoutException) {
                // A timed-out Lens request opens the process-wide network circuit immediately; do not
                // retry the same dead route inside this panel's deadline.
                Breaker.transient("timeout")
                throw OcrUnavailableException("Google Lens: timeout", e)
            } catch (e: InterruptedIOException) {
                Thread.currentThread().interrupt()    // genuine cancellation: not a network fault, do not retry
                throw OcrUnavailableException("Google Lens: cancelled", e)
            } catch (e: SSLException) {
                if (isCertificateProblem(e)) {
                    Breaker.permanent("TLS certificate")
                    throw OcrUnavailableException("Google Lens: TLS certificate problem (${describe(e)})", e)
                }
                last = e
            } catch (e: IOException) {
                if (e is UnknownHostException) {
                    // race() has already tried the alternate network route. Retrying the same unresolved
                    // host in the outer loop only burns the rest of the live-scan deadline without new data.
                    val why = describe(e)
                    Breaker.transient("DNS $why")
                    throw OcrUnavailableException("Google Lens: DNS resolution failed ($why)", e)
                }
                last = e    // ConnectException, SocketException ("Socket closed"), EOF, ...
            } finally {
                // Always hand the wire back, whatever happened above (thrown or not).
                REQUEST_SLOTS.release()
            }
            val jitter = (random.nextInt(120)).toLong()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (attempt == attemptLimit - 1 || elapsed + waitMs + jitter + minimumAttemptMs > deadlineMs) break
            try { Thread.sleep(waitMs + jitter) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OcrUnavailableException("Google Lens: cancelled", e)
            }
        }
        val why = last?.let { describe(it) } ?: "timed out"
        Breaker.transient(why)
        throw OcrUnavailableException("Google Lens: network failure ($why)", last)
    }

    /**
     * Hedged request. The first attempt starts at once. If it has not answered after [hedgeAfterMs] - or has
     * already failed - the SAME request is started on the other network path (active-network DNS vs process
     * default route), and whichever answers first wins; the loser is cut. Before, a stalled connection burned
     * the whole 6.5 s realtime budget with no second try, which is what made scans randomly "very late".
     */
    private fun race(
        payload: ByteArray, info: NetInfo, budgetMs: Long, connectMs: Long,
        hedgeAfterMs: Long, minimumAttemptMs: Long, firstBound: Boolean
    ): ByteArray {
        val started = SystemClock.elapsedRealtime()
        val cs = ExecutorCompletionService<Attempt>(HTTP_POOL)
        val attempts = ArrayList<Attempt>(2)
        val futures = ArrayList<Future<Attempt>>(2)
        val firstUsesBound = firstBound && info.network != null
        fun launch(bound: Boolean) {
            val a = Attempt()
            val left = (budgetMs - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(minimumAttemptMs)
            attempts.add(a)
            futures.add(cs.submit(Callable { a.body = postOnce(payload, info.network, bound, left, connectMs, a); a }))
        }
        launch(firstUsesBound)
        var running = 1
        var hedged = false
        var firstError: Throwable? = null
        var winner: Attempt? = null
        try {
            while (running > 0) {
                val sinceStart = SystemClock.elapsedRealtime() - started
                val waitMs = if (!hedged) (hedgeAfterMs - sinceStart).coerceAtLeast(1L)
                             else (budgetMs - sinceStart + 300L).coerceAtLeast(1L)
                val done = cs.poll(waitMs, TimeUnit.MILLISECONDS)
                if (done == null) {
                    if (!hedged && budgetMs - sinceStart >= minimumAttemptMs) {
                        hedged = true; running++
                        launch(!firstUsesBound && info.network != null)
                        continue
                    }
                    break   // budget exhausted
                }
                running--
                try {
                    val a = done.get()
                    winner = a
                    publish(a)
                    return a.body ?: throw IOException("empty Lens reply")
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    if (firstError == null) firstError = cause
                    if (cause is HttpStatus && cause.code !in RETRYABLE_STATUS) throw cause
                    // Primary failed fast: do not wait for a backoff, try the other path right now.
                    if (!hedged && budgetMs - (SystemClock.elapsedRealtime() - started) >= minimumAttemptMs) {
                        hedged = true; running++
                        launch(!firstUsesBound && info.network != null)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } finally {
            if (winner == null) attempts.lastOrNull()?.let { publish(it) }
            // Cut everything that did not deliver; keep the winner's socket for keep-alive reuse.
            for (a in attempts) if (a !== winner && a.body == null) try { a.conn?.disconnect() } catch (_: Throwable) {}
            for (f in futures) f.cancel(true)
        }
        val err = firstError
        if (err is IOException) throw err
        if (err != null) throw IOException(err.javaClass.simpleName + ": " + (err.message ?: ""), err)
        throw SocketTimeoutException("no reply within ${budgetMs}ms")
    }

    private fun postOnce(payload: ByteArray, network: Network?, bound: Boolean, budgetMs: Long, connectMs: Long, a: Attempt): ByteArray {
        val url = URL(endpoint())
        if (!url.protocol.equals("https", ignoreCase = true)) throw IOException("refusing non-HTTPS endpoint")
        val raw = if (bound && network != null) network.openConnection(url) else url.openConnection()
        val c = raw as HttpURLConnection
        a.conn = c
        var responseFullyRead = false
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.doInput = true
            c.useCaches = false
            c.instanceFollowRedirects = false          // a redirect of a protobuf POST means "not the real endpoint"
            c.connectTimeout = minOf(connectMs, budgetMs).toInt()
            c.readTimeout = minOf(READ_MS, budgetMs).toInt()
            c.setFixedLengthStreamingMode(payload.size)
            c.setRequestProperty("Content-Type", "application/x-protobuf")
            c.setRequestProperty("X-Goog-Api-Key", apiKey())
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Sec-Fetch-Mode", "no-cors")
            c.setRequestProperty("Sec-Fetch-Dest", "empty")
            // Repeated Webtoon scans should reuse a healthy TCP/TLS connection, like the owocr Lens client.
            c.setRequestProperty("Connection", "keep-alive")
            // No Accept-Encoding header on purpose: then HttpURLConnection negotiates gzip AND decodes it itself.
            c.outputStream.use { it.write(payload); it.flush() }
            val code = c.responseCode
            a.status = code
            a.meta = "contentType=${c.contentType ?: "unknown"} encoding=${c.contentEncoding ?: "none"} " +
                "headerLength=${c.getHeaderField("Content-Length") ?: "unknown"} requestBytes=${payload.size} " +
                "path=${if (bound && network != null) "bound" else "default"}"
            if (code !in 200..299) {
                Log.w(TAG, "HTTP_REJECTED status=$code ${a.meta}")
                val retryAfter = (c.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L).coerceIn(0L, 10L) * 1000L
                throw HttpStatus(code, retryAfter)
            }
            // Read to the end BEFORE disconnect(): disconnecting under a pending read is what produces
            // "Socket closed".
            val reply = c.inputStream.use { ins ->
                val out = ByteArrayOutputStream(16 * 1024)
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Lens reply too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            responseFullyRead = true
            Log.i(TAG, "HTTP_RESPONSE status=$code ${a.meta} replyBytes=${reply.size}")
            lastWarmAt = SystemClock.elapsedRealtime()   // a real request just proved the connection is warm
            return reply
        } finally {
            // Preserve successful keep-alive sockets; discard partial/error responses before retrying.
            if (!responseFullyRead) try { c.disconnect() } catch (_: Throwable) {}
        }
    }

    // ---- warm-up --------------------------------------------------------------------------------

    /**
     * Opens (and parks in the keep-alive pool) the DNS + TCP + TLS connection to the Lens host on both
     * network paths, so the first real scan skips ~3 round trips of handshake. Fire-and-forget: errors,
     * a 404/405 reply and "offline" are all fine - the only goal is a warm socket.
     */
    private fun warmNow() {
        val info = netInfo()
        if (!info.online) return
        val paths = if (info.network != null) listOf(true, false) else listOf(false)
        for (bound in paths) {
            try {
                val url = URL(endpoint())
                if (!url.protocol.equals("https", ignoreCase = true)) return
                val raw = if (bound) info.network!!.openConnection(url) else url.openConnection()
                val c = raw as HttpURLConnection
                try {
                    c.requestMethod = "GET"
                    c.useCaches = false
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 4_000
                    c.readTimeout = 4_000
                    c.setRequestProperty("User-Agent", USER_AGENT)
                    c.setRequestProperty("Connection", "keep-alive")
                    val code = c.responseCode
                    // Drain fully so the socket returns to the pool instead of being closed.
                    (if (code in 200..299) c.inputStream else c.errorStream)?.use { ins ->
                        val buf = ByteArray(4096)
                        while (ins.read(buf) >= 0) { /* discard */ }
                    }
                    Log.i(TAG, "WARM ok path=${if (bound) "bound" else "default"} status=$code")
                } catch (t: Throwable) {
                    Log.i(TAG, "WARM skipped path=${if (bound) "bound" else "default"}: ${t.javaClass.simpleName}")
                }
            } catch (_: Throwable) {}
        }
    }

    private fun isCertificateProblem(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) { if (t is CertificateException) return true; t = t.cause }
        return false
    }

    /** "UnknownHostException (GaiException: android_getaddrinfo failed: EAI_NODATA ...)" - no recognised text involved. */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.take(8).last()
        val head = e.javaClass.simpleName
        val tail = (root.message ?: e.message ?: "").replace(Regex("\\s+"), " ").trim().take(110)
        val rootName = if (root !== e) root.javaClass.simpleName + (if (tail.isNotEmpty()) ": " else "") else ""
        return head + (if (tail.isNotEmpty() || rootName.isNotEmpty()) " ($rootName$tail)" else "")
    }

    // ---- circuit breaker (process-wide, so recreating the engine does not forget a dead endpoint) ------

    private object Breaker {
        private var openUntil = 0L
        private var reason = ""
        private var transientFails = 0
        private var halfOpenProbeInFlight = false

        @Synchronized fun check() {
            val now = SystemClock.elapsedRealtime()
            if (openUntil > now) {
                OcrDiag.log("LENS_SKIP", "reason=circuit_open fallback=mlkit remainingMs=${openUntil - now}")
                throw OcrUnavailableException("Google Lens: paused after $reason")
            }
            // After the cooldown, permit one probe only. All concurrent callers continue through ML Kit.
            if (openUntil > 0L) {
                if (halfOpenProbeInFlight) {
                    OcrDiag.log("LENS_SKIP", "reason=circuit_open fallback=mlkit probe=busy")
                    throw OcrUnavailableException("Google Lens: paused after $reason")
                }
                halfOpenProbeInFlight = true
                OcrDiag.log("LENS_CIRCUIT_PROBE", "reason=$reason halfOpen=true")
            }
        }

        @Synchronized fun success() {
            transientFails = 0
            openUntil = 0L
            halfOpenProbeInFlight = false
            reason = ""
        }

        /** A probe after a cooldown must be an actual OCR request, never a background preconnect. */
        @Synchronized fun shouldSkipWarmUp(): Boolean = openUntil > 0L || halfOpenProbeInFlight

        @Synchronized fun status(): String {
            val left = openUntil - SystemClock.elapsedRealtime()
            return if (left > 0) "OPEN (Lens paused for ${left / 1000}s more) reason=$reason"
            else "closed (Lens allowed) transientFails=$transientFails lastReason=${reason.ifBlank { "-" }}"
        }

        @Synchronized fun transient(why: String) {
            val now = SystemClock.elapsedRealtime()
            val wasHalfOpenProbe = halfOpenProbeInFlight
            reason = why
            halfOpenProbeInFlight = false
            val low = why.lowercase(Locale.US)
            val networkReason = when {
                "dns" in low || "unknownhost" in low || "unknown host" in low -> "dns_failed"
                "no internet" in low || "offline" in low -> "no_internet"
                "timeout" in low || "timed out" in low || "sockettimeout" in low -> "timeout"
                else -> ""
            }
            if (networkReason.isNotEmpty()) {
                val cooldownMs = if (wasHalfOpenProbe) 60_000L else 30_000L
                openUntil = now + cooldownMs
                transientFails = 0
                OcrDiag.log(
                    "LENS_CIRCUIT_OPEN",
                    "reason=$networkReason cooldownSec=${cooldownMs / 1000} halfOpenProbeFailed=$wasHalfOpenProbe"
                )
                Log.w(TAG, "pausing Lens ${cooldownMs / 1000}s: $networkReason ($why)")
                return
            }
            if (++transientFails >= TRANSIENT_TRIP) {
                transientFails = 0
                openUntil = now + TRANSIENT_PAUSE_MS
                OcrDiag.log("LENS_CIRCUIT_OPEN", "reason=transient_failures cooldownSec=${TRANSIENT_PAUSE_MS / 1000}")
                Log.w(TAG, "pausing Lens ${TRANSIENT_PAUSE_MS / 1000}s: $why")
            }
        }

        @Synchronized fun permanent(why: String) {
            reason = why
            transientFails = 0
            halfOpenProbeInFlight = false
            openUntil = SystemClock.elapsedRealtime() + PERMANENT_PAUSE_MS
            Log.w(TAG, "pausing Lens ${PERMANENT_PAUSE_MS / 60000} min: $why")
        }
    }

    companion object {
        private const val TAG = "NovelStudioLens"

        private val HTTP_POOL = Executors.newCachedThreadPool { r ->
            Thread(r, "ns-lens-http").apply { isDaemon = true }
        }

        @Volatile private var lastWarmAt = 0L

        /**
         * Pre-connects to Lens in the background (DNS + TLS). Cheap to call often: it does nothing when a
         * request or a previous warm-up touched the connection within [WARM_INTERVAL_MS].
         */
        fun warmUp(context: Context) {
            if (Breaker.shouldSkipWarmUp()) {
                OcrDiag.log("LENS_SKIP", "reason=circuit_open fallback=mlkit warmup=true")
                return
            }
            val now = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (lastWarmAt != 0L && now - lastWarmAt < WARM_INTERVAL_MS) return
                lastWarmAt = now
            }
            val app = context.applicationContext
            HTTP_POOL.execute {
                try { LensOcrEngine(app).warmNow() } catch (_: Throwable) {}
            }
        }

        /** For the OCR log: is the circuit breaker currently skipping Lens? */
        fun breakerStatus(): String = Breaker.status()

        /** Chromium's Lens overlay upload. Unofficial for third-party apps: may change or be blocked anytime. */
        const val DEFAULT_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"

        /**
         * The public key embedded in Chromium's Lens overlay (identical in all four reference projects).
         * It is Google's, not ours; it can be rotated or revoked. Override with Prefs "lensApiKey".
         */
        private const val DEFAULT_API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val MIN_SIDE = 16
        private const val MAX_AREA = 1_500_000L
        private const val MAX_DIMENSION = 1600
        private const val JPEG_QUALITY = 40
        private const val MAX_UPLOAD_BYTES = 3_500_000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

        // Realtime screenshot reading is latency-sensitive; a slow endpoint must not stall the reader.
        private const val FAST_TOTAL_DEADLINE_MS = 4_000L
        private const val FAST_MIN_ATTEMPT_MS = 650L
        private const val FAST_MAX_ATTEMPTS = 2
        private const val CONNECT_MS = 7_000L
        private const val FAST_CONNECT_MS = 1_800L
        // Start the same request on the other network path if no answer after this long (see race()).
        private const val FAST_HEDGE_MS = 900L
        private const val HEDGE_MS = 3_000L
        // Warm sockets stay in the pool ~5 min; refresh a little before that.
        private const val WARM_INTERVAL_MS = 150_000L
        private const val READ_MS = 15_000L
        private const val TOTAL_DEADLINE_MS = 22_000L
        private const val MIN_ATTEMPT_MS = 2_500L
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MS = 400L
        private val RETRYABLE_STATUS = setOf(429, 500, 502, 503, 504)

        /**
         * Requests actually on the wire at once, across every caller.
         *
         * A full scan overlaps panels for speed; without this cap that burst is what produces HTTP 429 in the
         * first place. Three is the value that has kept Lens answering on the test device.
         */
        private val REQUEST_SLOTS = java.util.concurrent.Semaphore(7)
        /** Spacing between request starts so a burst is not mistaken for abuse. */
        private val START_GAP_MS = 120L
        /** Guards the reservation of the next request start; the sleep itself happens outside it. */
        private val START_LOCK = Any()
        @Volatile private var lastStartAt = 0L

        // Two timeouts in a row used to switch Lens off for 45 s, which looked like whole panels being skipped.
        private const val TRANSIENT_TRIP = 3
        private const val TRANSIENT_PAUSE_MS = 15_000L
        private const val PERMANENT_PAUSE_MS = 10 * 60_000L

        private const val CACHE_SIZE = 32
        private const val CACHE_TTL_MS = 3 * 60_000L
        private const val EMPTY_CACHE_TTL_MS = 20_000L
        // Non-bubble pre-filter + region negative cache (see read()).
        private const val MIN_BUBBLE_AREA = 300L * 300L   // ~90k px: below this it is a UI/logo tile, not a bubble
        private const val MIN_BUBBLE_ASPECT = 1.2f        // bubbles are never ~1:1 square
        private const val NEG_CACHE_SIZE = 64
    }
}
