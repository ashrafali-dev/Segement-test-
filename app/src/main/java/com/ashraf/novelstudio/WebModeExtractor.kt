package com.ashraf.novelstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class WebModeError(val label: String) {
    NO_IMAGES("No chapter images found on this page"),
    IMAGES_NOT_LOADED("Chapter images were found but could not be loaded"),
    DECODE_FAILED("Chapter images could not be decoded"),
    OCR_EMPTY("OCR found no text in the chapter images"),
    OCR_MODEL_UNAVAILABLE("The OCR model is unavailable"),
    CHATBOT_SUBMIT_FAILED("Chatbot submission failed"),
    CHATBOT_INCOMPLETE("Chatbot response incomplete"),
    NAVIGATION_INVALIDATED("Chapter navigation invalidated the job"),
    TIMEOUT("Web Mode timed out")
}

/** Internal counters for one Web Mode job. Never holds cookies, keys or page text. */
internal class WebModeDiagnostics(val jobId: Int, val host: String) {
    @Volatile var discovered = 0
    @Volatile var duplicates = 0
    @Volatile var downloaded = 0
    @Volatile var decoded = 0
    @Volatile var ocrOk = 0
    @Volatile var ocrEmpty = 0
    @Volatile var failed = 0
    @Volatile var chars = 0
    @Volatile var cancelled = false
    @Volatile var timedOut = false
    @Volatile var error: WebModeError? = null
    fun summary(): String =
        "job=$jobId host=$host discovered=$discovered dup=$duplicates downloaded=$downloaded decoded=$decoded " +
            "ocrOk=$ocrOk ocrEmpty=$ocrEmpty failed=$failed chars=$chars cancelled=$cancelled " +
            "timedOut=$timedOut error=${error?.name ?: "none"}"
}

internal sealed class WebModeOutcome {
    class Success(val chapter: Chapter, val diag: WebModeDiagnostics, val failedPanels: Int) : WebModeOutcome()
    class Failure(val error: WebModeError, val message: String, val diag: WebModeDiagnostics) : WebModeOutcome()
}

/** A normalized crop rectangle (fractions of the original image, not screen pixels). */
internal data class WebModeOcrRegion(val x: Float, val y: Float, val w: Float, val h: Float)

/** Full-quality OCR result for one upcoming image panel, including stable bubble geometry. */
internal data class WebModeAheadPanel(
    val src: String,
    val top: Int,
    val text: String,
    val bubbles: List<WmBubble>
)

internal class WebModeRequest(
    val webView: WebView,
    /** When true, OCR only images intersecting the current viewport and never scrolls the reader. */
    val visibleOnly: Boolean = false,
    /** False once the user navigated away / started another action: the job stops and reports nothing useful. */
    val isValid: () -> Boolean,
    /** Force extract: do not download images; OCR what the WebView actually shows while it is scrolled. */
    val force: Boolean = false,
    val onProgress: (stage: String, pct: Int) -> Unit,
    val onDone: (WebModeOutcome) -> Unit,
    /**
     * > 0: "read ahead" job for real-time mode. It looks at the next [ahead] images BELOW the screen,
     * downloads and OCRs them quietly (full-quality OCR, no scrolling) and keeps the result in a small
     * cache. When such a panel later reaches the screen, the real-time job takes the text from the
     * cache instead of waiting for download + OCR. [onDone] is not used for these jobs.
     */
    val ahead: Int = 0,
    /** Called on the main thread with successfully OCRed upcoming panels, for translation prefetch. */
    val onAheadPanelsReady: ((List<WebModeAheadPanel>) -> Unit)? = null,
    val onAheadDone: (() -> Unit)? = null,
    /** When set, ignore OCR caches and re-read only this source image. */
    val forceOcrSrc: String? = null,
    /** Normalized rectangle to OCR within forceOcrSrc. */
    val forceOcrRegion: WebModeOcrRegion? = null,
    /**
     * Real-time mode: inventory all image URLs currently present in the DOM (up to the page's
     * bounded candidate limit), then process only the next ordered batch. Images not in the DOM
     * yet, or whose URL download/OCR fails, are left to the existing viewport OCR fallback.
     */
    val fullChapterLinks: Boolean = false,
    /** True while another pane (full-screen chatbot) is drawn over this WebView: capture from the view itself. */
    val isOccluded: () -> Boolean = { false }
)

/**
 * OCR-based chapter extraction for image chapters (Webtoon-style readers).
 * Reads the images of the page that is ALREADY loaded in [WebModeRequest.webView], so the real
 * session, lazy loaders and cookies are used. Produces an ordinary [Chapter]; the existing
 * chatbot pipeline takes it from there.
 */
internal class WebModeExtractor(
    private val handler: Handler,
    private val userAgent: String,
    private val engine: OcrEngine = RoutingOcrEngine()
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ns-webmode") }
    // Read-ahead (real-time mode): its own low-priority thread, so it never queues in front of the job
    // for the panel the reader is looking at.
    private val aheadWorker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ns-webmode-ahead").apply { priority = 3 }   // was MIN_PRIORITY (1): read-ahead OCR must keep up
    }
    private val aheadCache = object : LinkedHashMap<String, PanelOcr>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PanelOcr>?): Boolean = size > 60
    }
    private val aheadDone: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Panels the read-ahead gave up on (no text, too small, or the download failed twice). They must not
    // keep occupying the look-ahead window, otherwise art-only panels would stop it from moving forward.
    private val aheadBlank: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val aheadFails = ConcurrentHashMap<String, Int>()
    private val jobIds = AtomicInteger(0)
    private val bridge = PageBridge()
    private val bridged = java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())

    /** Returns a handle; call [Handle.cancel] to stop the job. Must be called on the main thread. */
    fun start(req: WebModeRequest): Handle {
        val job = Job(req, jobIds.incrementAndGet())
        if (req.ahead > 0) job.beginAhead() else job.begin()
        return job
    }

    interface Handle { fun cancel() }

    fun shutdown() {
        worker.shutdownNow()
        aheadWorker.shutdownNow()
        engine.close()
    }

    // ---------------------------------------------------------------- page bridge (blob:/data:/CORS fetches)
    internal class Pending { val latch = CountDownLatch(1); @Volatile var data: String? = null; @Volatile var err: String? = null }

    internal inner class PageBridge {
        val pending = ConcurrentHashMap<String, Pending>()

        @JavascriptInterface
        fun ok(id: String, dataUrl: String) {
            pending[id]?.let { it.data = dataUrl; it.latch.countDown() }
        }

        @JavascriptInterface
        fun fail(id: String, msg: String) {
            pending[id]?.let { it.err = msg.take(120); it.latch.countDown() }
        }
    }

    private fun ensureBridge(wv: WebView) {
        if (bridged.add(wv)) wv.addJavascriptInterface(bridge, "NsWmBridge")
    }

    // ---------------------------------------------------------------- job
    private inner class Job(val req: WebModeRequest, val id: Int) : Handle {
        private val cancelled = AtomicBoolean(false)
        private val startedAt = SystemClock.elapsedRealtime()
        private val wv = req.webView
        // Preserve panels seen during full-chapter scrolling; virtualized readers recycle DOM nodes.
        private val discoveredDuringScroll = LinkedHashMap<String, PanelRef>()
        // Read on the main thread (the job is created there); the worker thread must never ask the WebView.
        private val startUrl = wv.url.orEmpty()
        val diag = WebModeDiagnostics(id, hostOf(startUrl))

        /**
         * req.isValid() may read WebView state (e.g. the current URL), and a WebView may only be touched on
         * the main thread. The worker thread calls alive() constantly, so it reads a cached answer that is
         * refreshed on the main thread; on the main thread the real check runs directly.
         */
        @Volatile private var validCache = true
        @Volatile private var lastValidityCheckAtMs = SystemClock.elapsedRealtime()
        private val validityCheckQueued = AtomicBoolean(false)
        private fun alive(): Boolean {
            if (cancelled.get()) return false
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                validCache = req.isValid()
                lastValidityCheckAtMs = SystemClock.elapsedRealtime()
                return validCache
            }
            // Never queue a UI/WebView validity check on every OCR loop. Large panel scans can
            // call alive() many times, and flooding the main thread delays visible-page work.
            val now = SystemClock.elapsedRealtime()
            if (now - lastValidityCheckAtMs >= VALIDITY_CHECK_INTERVAL_MS &&
                validityCheckQueued.compareAndSet(false, true)) {
                handler.post {
                    try {
                        if (!cancelled.get()) {
                            validCache = req.isValid()
                            lastValidityCheckAtMs = SystemClock.elapsedRealtime()
                        }
                    } catch (_: Throwable) {
                        validCache = false
                    } finally {
                        validityCheckQueued.set(false)
                    }
                }
            }
            return validCache
        }
        private fun elapsed() = SystemClock.elapsedRealtime() - startedAt
        private fun onMain(block: () -> Unit) { handler.post(block) }

        override fun cancel() { cancelled.set(true); diag.cancelled = true }

        private fun progress(stage: String, pct: Int) = onMain {
            if (alive()) req.onProgress(stage, pct.coerceIn(0, 99))
        }

        @Volatile private var fellBackToForce = false
        /** How this job really read the page: dom (image URLs), canvas (exported canvas pixels) or screen (screenshot). */
        @Volatile private var how = "dom"

        private fun fail(e: WebModeError, msg: String = e.label) {
            // Realtime viewport mode must also recover when DOM discovery reports NO_IMAGES:
            // canvas-based readers, CSS backgrounds, and image wrappers may be visible even when
            // the normal panel collector returns an empty list. Capture the viewport once and run
            // the existing Lens OCR on those detected screen regions instead of retrying chapter text.
            // processForce() does not scroll when visibleOnly=true, so this remains safe for realtime mode.
            val visibleNoImages = req.visibleOnly && e == WebModeError.NO_IMAGES
            // Images downloaded fine but held no readable text (ads / canvas pages): read the screen instead.
            val visibleEmpty = req.visibleOnly && e == WebModeError.OCR_EMPTY
            OcrDiag.log("FAIL", "${e.name}: $msg | willFallBack=${!fellBackToForce && (visibleNoImages || visibleEmpty || e == WebModeError.IMAGES_NOT_LOADED || e == WebModeError.DECODE_FAILED)} | ${diag.summary()}")
            if (!fellBackToForce && req.ahead <= 0 && req.forceOcrSrc.isNullOrBlank() &&
                (visibleNoImages || visibleEmpty || e == WebModeError.IMAGES_NOT_LOADED || e == WebModeError.DECODE_FAILED)) {
                // Full-chapter Web Mode scan too: when image downloads fail, read what WebView draws.
                fellBackToForce = true
                diag.error = null
                Log.i(TAG, "OCR falling back to viewport capture after $e: ${diag.summary()}")
                worker.execute { runCatching { processForce() }.onFailure {
                    fail(WebModeError.DECODE_FAILED, "Viewport capture fallback crashed: ${it.javaClass.simpleName}")
                } }
                return
            }
            diag.error = e
            Log.w(TAG, diag.summary())
            OcrDiag.jobDone(diag.host, diag.summary(), false, how, elapsed(), "${e.name}: $msg")
            onMain { req.onDone(WebModeOutcome.Failure(e, msg, diag)) }
        }

        fun begin() {
            ensureBridge(wv)
            OcrDiag.log("JOB_BEGIN", "id=$id host=${diag.host} url=${startUrl.take(160)} visibleOnly=${req.visibleOnly} force=${req.force} " +
                "ahead=${req.ahead} forceSrc=${!req.forceOcrSrc.isNullOrBlank()} engine=${Prefs.get(wv.context, "ocrEngine", "mlkit")}")
            progress("Discovering chapter panels", 1)
            if (req.force && !req.visibleOnly) {
                worker.execute { runCatching { processForce() }.onFailure {
                    Log.w(TAG, "force job $id crashed: ${it.javaClass.simpleName}")
                    fail(WebModeError.DECODE_FAILED, "Force extract crashed: ${it.javaClass.simpleName}")
                } }
                return
            }
            if (req.visibleOnly) finishDiscovery() else tick(0, -1, 0)
        }

        // ---- read-ahead (real-time): OCR the next images below the screen, without scrolling anything
        fun beginAhead() {
            try {
                if (SiteLearner.screenPreferred(wv.context, hostOf(wv.url.orEmpty()))) { aheadFinished(); return }
                ensureBridge(wv)
                val aheadN = req.ahead.coerceIn(1, 12)
                // Look at up to 3x the batch size below the screen; the first aheadN panels that are still
                // undone are read. As panels finish, the window moves on by itself (it used to stop after
                // the first N, until the reader scrolled).
                // Full-chapter link inventory is bounded at 600 candidates; OCR remains
                // sequential and is still limited to aheadN images per batch.
                val aheadWindow = if (req.fullChapterLinks) 600 else (aheadN * 3).coerceAtMost(30)
                val js = wrap(
                    LIB + JS_COLLECT_AHEAD
                        .replace("__N__", aheadWindow.toString())
                        .replace("__ALL__", req.fullChapterLinks.toString())
                )
                wv.evaluateJavascript(js) { raw ->
                    if (!alive()) { aheadFinished(); return@evaluateJavascript }
                    val o = parseJson(raw)
                    val arr = o?.optJSONArray("imgs")
                    if (o == null || arr == null || arr.length() == 0) { aheadFinished(); return@evaluateJavascript }
                    val pageUrl = o.optString("url", wv.url.orEmpty())
                    val lang = o.optString("lang")
                    val refs = ArrayList<PanelRef>()
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val s = p.optString("s")
                        if (s.isBlank()) continue
                        val nk = WebModeLogic.normalizeUrl(s)
                        if (aheadDone.contains(nk) || aheadBlank.contains(nk)) continue
                        refs.add(PanelRef(s, p.optInt("t"), p.optInt("l"), p.optInt("w"), p.optInt("h")))
                    }
                    if (req.fullChapterLinks) refs.sortBy { it.top }
                    while (refs.size > aheadN) refs.removeAt(refs.size - 1)
                    if (refs.isEmpty()) { aheadFinished(); return@evaluateJavascript }
                    aheadWorker.execute {
                        OcrGate.markBackgroundThread()
                        try { runCatching { processAhead(refs, pageUrl, lang) } } finally { aheadFinished() }
                    }
                }
            } catch (_: Throwable) { aheadFinished() }
        }

        private fun aheadFinished() { onMain { req.onAheadDone?.invoke() } }

        private fun processAhead(refs: List<PanelRef>, pageUrl: String, lang: String) {
            val script = newScript(lang, pageUrl)
            // Full-chapter inventory may legitimately contain hundreds of panels. Do not
            // reset its seen-set midway through the chapter or the batcher would start from panel 1 again.
            if (aheadDone.size > 5000) { aheadDone.clear(); aheadBlank.clear(); aheadFails.clear() }
            val total = refs.size.coerceAtLeast(1)
            var cachedCount = 0
            fun emitReady(panel: WebModeAheadPanel) {
                // Emit each completed OCR result immediately so translation can overlap the
                // remaining image downloads/OCR instead of waiting for all nine panels.
                onMain { if (alive()) req.onAheadPanelsReady?.invoke(listOf(panel)) }
            }
            progress("Read-ahead OCR: preparing ${refs.size} upcoming panels", 0)
            for ((index, p) in refs.withIndex()) {
                if (!alive()) return
                val key = WebModeLogic.normalizeUrl(p.src)
                // Deliver cached OCR too. A previous successful OCR must not be invisible to the translation cache.
                val cached = synchronized(aheadCache) { aheadCache[key] }
                if (cached != null) {
                    emitReady(WebModeAheadPanel(p.src, p.top, cached.text, cached.bubbles))
                    cachedCount++
                    progress("Read-ahead OCR: cache ready $cachedCount/$total", cachedCount * 99 / total)
                    continue
                }
                // A previous cache eviction must not leave a URL permanently marked as done.
                aheadDone.remove(key)
                try {
                    progress("Read-ahead OCR: loading image ${index + 1}/$total", index * 99 / total)
                    val bytes = fetchWithRetry(p, pageUrl) ?: run {
                        if ((aheadFails.merge(key, 1) { x, y -> x + y } ?: 1) >= 2) aheadBlank.add(key)
                        null
                    } ?: continue
                    // Full-quality OCR (not the fast one-pass reading): this runs in the background.
                    progress("Read-ahead OCR: reading text ${index + 1}/$total", index * 99 / total)
                    val po = ocrBytes(bytes, script, p.src, fastMode = true) ?: run { aheadBlank.add(key); null } ?: continue
                    // Do not poison the read-ahead cache with an empty OCR result; the visible scan
                    // gets another attempt when the panel reaches the viewport.
                    if (po.text.isBlank()) {
                        // Skipped by read-ahead from now on (the visible scan still reads it when it arrives).
                        aheadBlank.add(key)
                        progress("Read-ahead OCR: no text; skipping panel", index * 99 / total)
                        continue
                    }
                    synchronized(aheadCache) { aheadCache[key] = po }
                    aheadDone.add(key)
                    emitReady(WebModeAheadPanel(p.src, p.top, po.text, po.bubbles))
                    cachedCount++
                    progress("Read-ahead OCR: cached panel $cachedCount/$total", cachedCount * 99 / total)
                } catch (_: OcrUnavailableException) {
                    progress("Read-ahead OCR: OCR engine unavailable", index * 99 / total)
                    break
                } catch (_: Exception) {
                    progress("Read-ahead OCR: panel ${index + 1} failed; continuing", index * 99 / total)
                } catch (_: OutOfMemoryError) {
                    progress("Read-ahead OCR: memory limit hit; stopping safely", index * 99 / total)
                    break
                }
            }
            if (alive()) progress("Read-ahead OCR complete: ${cachedCount}/$total panels cached", 99)
        }
        // ---- phase 1: scroll the real page so lazy loaders fire, until the image set stops changing
        private fun tick(n: Int, lastCount: Int, stable: Int) {
            if (!alive()) { restoreScroll(); fail(WebModeError.NAVIGATION_INVALIDATED); return }
            // User-set "scan capture interval" (menu): wait this long after each scroll step so slow
            // lazy loaders can set their image URLs. 0 = automatic (the fast built-in cadence).
            val userStepMs = scanStepMs()
            if (elapsed() > DISCOVERY_MAX_MS + userStepMs * 150L || n > MAX_TICKS) { finishDiscovery(); return }
            // Read the current viewport before moving; virtualized readers can recycle the first panels on scroll.
            // Then use overlapping steps so lazy-loaded panels are less likely to be skipped.
            // Nothing has reacted to the last few scroll steps (images already in the page, no lazy loader):
            // there is nothing to wait for, so steps get bigger and the wait shrinks (see settleThen).
            val stepSize = if (n == 0 && lastCount < 0) "0" else if (quietStreak >= 3) "0.95" else if (lastCount >= 8) "0.85" else "0.70"
            wv.evaluateJavascript(wrap(LIB + JS_STEP.replace("__STEP__", stepSize))) { raw ->
                if (!alive()) { restoreScroll(); fail(WebModeError.NAVIGATION_INVALIDATED); return@evaluateJavascript }
                val o = parseJson(raw)
                if (o == null) {
                    handler.postDelayed({ tick(n + 1, lastCount, 0) }, 500L)
                    return@evaluateJavascript
                }
                val count = o.optInt("n", 0)
                val pending = o.optInt("p", 0)
                val bottom = o.optBoolean("bottom", false)
                // Many sites (ads, trackers, long-polling) never reach readyState 'complete'; waiting for it
                // made scanning crawl until the 120 s discovery limit. 'interactive' is enough after 4 s.
                val rs = o.optString("ready")
                val ready = rs == "complete" || (rs == "interactive" && elapsed() > 4000L)
                // Collect panels at every scroll position, not just the final viewport.
                o.optJSONArray("imgs")?.let { seenImgs ->
                    for (si in 0 until minOf(seenImgs.length(), MAX_PANELS)) {
                        val item = seenImgs.optJSONObject(si) ?: continue
                        val src = item.optString("s").trim()
                        if (src.isBlank()) continue
                        val panel = PanelRef(src, item.optInt("t"), item.optInt("l"), item.optInt("w"), item.optInt("h"))
                        val key = WebModeLogic.normalizeUrl(src).ifBlank { src }
                        val old = discoveredDuringScroll[key]
                        if (old == null || panel.w * panel.h > old.w * old.h) {
                            discoveredDuringScroll[key] = panel
                        }
                    }
                }
                // Never treat a long list of currently exposed URLs as proof that a lazy chapter
                // is complete. Sites often expose only the first 30–32 panels and add the rest on scroll.
                // Keep walking to the real bottom while preserving every panel seen along the way.
                var st = if (bottom && ready && count == lastCount) stable + 1 else 0
                // A reader that has not drawn anything yet gets a longer grace period.
                if (count == 0 && elapsed() < EMPTY_GRACE_MS) st = 0
                progress("Discovering panels: ${discoveredDuringScroll.size} collected · $pending loading", 1 + minOf(8, n / 4))
                if ((st >= 3 && pending == 0) || st >= 6) finishDiscovery()
                else {
                    // Many ordinary IMG-based readers can scroll faster once the image list is visible.
                    // Keep a slower cadence for unknown/virtualized readers so their lazy loaders can react.
                    val autoWaitMs = when {
                        bottom -> 220L
                        count >= 8 -> 90L
                        count >= 3 -> 120L
                        else -> 250L
                    }
                    val waitMs = if (userStepMs > 0L) userStepMs else autoWaitMs
                    // Event-based: leave early once the page's images reacted and went quiet, wait the full
                    // interval if nothing happened, and linger (bounded) while visible images are still loading.
                    settleThen(waitMs, o.optInt("m", 0), o.optInt("l", 0)) { tick(n + 1, count, st) }
                }
            }
        }

        private var slowTicks = 0
        private var capHits = 0
        private var quietStreak = 0

        private fun scanFastFirst(): Boolean = Prefs.get(wv.context, "scanFastFirst", "1") == "1"

        /** OCR language: the menu can pin it (English / Korean / Japanese / Chinese); "Auto" = guess from the page. */
        private fun newScript(lang: String, url: String): ScriptState {
            val pinned = when (Prefs.get(wv.context, "ocrLang", "auto")) {
                "en" -> OcrScript.LATIN
                "ko" -> OcrScript.KOREAN
                "ja" -> OcrScript.JAPANESE
                "zh" -> OcrScript.CHINESE
                else -> null
            }
            if (pinned == null) {
                val learned = SiteLearner.script(wv.context, hostOf(url))
                    ?.let { n -> OcrScript.values().firstOrNull { it.name == n } }
                // Only the starting guess: the usual checks still run if the page turns out to be in another script.
                return ScriptState(learned ?: WebModeLogic.pickScript(lang, url))
            }
            return ScriptState(pinned).also { it.locked = true; it.fastFallbackBudget = 0; it.fallbackBudget = 0 }
        }

        private fun scanHost(): String = try {
            android.net.Uri.parse(wv.url.orEmpty()).host.orEmpty().removePrefix("www.")
        } catch (_: Exception) { "" }

        /** Menu value if set; otherwise the interval learned for this site; otherwise 0 (automatic). */
        private fun scanStepMs(): Long {
            val global = (Prefs.get(wv.context, "scanStepMs", "0").toLongOrNull() ?: 0L).coerceIn(0L, 5_000L)
            if (global > 0L) return global
            val host = scanHost()
            if (host.isBlank()) return 0L
            return (Prefs.get(wv.context, "scanStepHost:$host", "0").toLongOrNull() ?: 0L).coerceIn(0L, 5_000L)
        }

        /** A site that kept needing extra time during this scan gets a longer default next time (Auto mode only). */
        private fun learnScanInterval() {
            try {
                val global = (Prefs.get(wv.context, "scanStepMs", "0").toLongOrNull() ?: 0L)
                val host = scanHost()
                if (global > 0L || host.isBlank() || slowTicks < 3) return
                val presets = longArrayOf(150L, 300L, 500L, 800L, 1_200L, 2_000L)
                val cur = scanStepMs()
                val next = presets.firstOrNull { it > cur } ?: return
                Prefs.put(wv.context, "scanStepHost:$host", next.toString())
            } catch (_: Exception) { }
        }

        private fun settleThen(configuredMs: Long, startMut: Int, startLoad: Int, next: () -> Unit) {
            // After 3 quiet steps in a row the page clearly does not load anything on scroll: do not wait the
            // full interval any more (slow sites keep their long wait because they are never quiet).
            val baseMs = if (quietStreak >= 3) Math.min(configuredMs, 70L) else configuredMs
            val t0 = android.os.SystemClock.uptimeMillis()
            val extra = if (capHits >= 3) 0L else 1_200L
            val hardCap = baseMs + extra
            var lastM = startMut
            var lastL = startLoad
            var activity = false
            var lastAct = t0
            var counted = false
            fun probe() {
                if (!alive()) { restoreScroll(); fail(WebModeError.NAVIGATION_INVALIDATED); return }
                wv.evaluateJavascript(wrap(JS_SETTLE)) { raw ->
                    if (!alive()) { restoreScroll(); fail(WebModeError.NAVIGATION_INVALIDATED); return@evaluateJavascript }
                    val now = android.os.SystemClock.uptimeMillis()
                    val el = now - t0
                    val o = parseJson(raw)
                    if (o == null) {
                        handler.postDelayed({ next() }, (baseMs - el).coerceAtLeast(0L))
                        return@evaluateJavascript
                    }
                    val m = o.optInt("m", 0); val l = o.optInt("l", 0); val p = o.optInt("p", 0)
                    if (m != lastM || l != lastL) { activity = true; lastAct = now; lastM = m; lastL = l }
                    val quiet = now - lastAct >= 100L
                    if (el >= baseMs && !counted && (p > 0 || (activity && !quiet))) { counted = true; slowTicks++ }
                    when {
                        el >= hardCap -> { quietStreak = 0; if (p > 0 || (activity && !quiet)) capHits++; next() }
                        activity && quiet && p == 0 -> { quietStreak = 0; next() }
                        el >= baseMs && p == 0 && !activity -> { quietStreak++; next() }
                        else -> handler.postDelayed({ probe() }, 60L)
                    }
                }
            }
            handler.postDelayed({ probe() }, 50L)
        }

        private fun restoreScroll() {
            try { wv.evaluateJavascript(wrap(LIB + JS_RESTORE), null) } catch (_: Exception) {}
        }

        private fun finishDiscovery() {
            learnScanInterval()
            val collectScript = if (req.visibleOnly) JS_COLLECT_VISIBLE else JS_COLLECT
            wv.evaluateJavascript(wrap(LIB + collectScript)) { raw ->
                restoreScroll()
                if (!alive()) { fail(WebModeError.NAVIGATION_INVALIDATED); return@evaluateJavascript }
                val o = parseJson(raw)
                if (o == null) { fail(WebModeError.NO_IMAGES); return@evaluateJavascript }
                lastDiscoveryDebug = o.optString("dbg")
                OcrDiag.log("DISCOVERY", "visibleOnly=${req.visibleOnly} $lastDiscoveryDebug")
                val arr = o.optJSONArray("imgs") ?: JSONArray()
                // Merge scroll history with the final DOM snapshot for complete scans on recycled readers.
                val merged = LinkedHashMap<String, PanelRef>()
                for ((key, panel) in discoveredDuringScroll) merged[key] = panel
                var rawPanelCount = merged.size
                for (i in 0 until minOf(arr.length(), MAX_PANELS)) {
                    val p = arr.optJSONObject(i) ?: continue
                    val s = p.optString("s").trim()
                    if (s.isBlank()) continue
                    // Banner/ad slides (e.g. shonenjumpplus /public/link-slot-series) are not manga pages.
                    if (s.contains("/link-slot-", ignoreCase = true)) continue
                    val panel = PanelRef(s, p.optInt("t"), p.optInt("l"), p.optInt("w"), p.optInt("h"))
                    val key = WebModeLogic.normalizeUrl(s).ifBlank { s }
                    val old = merged[key]
                    if (old == null) { merged[key] = panel; rawPanelCount++ }
                    else if (panel.w * panel.h > old.w * old.h) merged[key] = panel
                }
                val refs = merged.values.toList()
                val allPanels = WebModeLogic.dedupeAndOrder(refs)
                val targetSrc = req.forceOcrSrc
                val panels = if (targetSrc.isNullOrBlank()) allPanels else allPanels.filter {
                    WebModeLogic.normalizeUrl(it.src) == WebModeLogic.normalizeUrl(targetSrc)
                }
                diag.discovered = panels.size
                diag.duplicates = (rawPanelCount - allPanels.size).coerceAtLeast(0)
                OcrDiag.log("PANELS", "raw=$rawPanelCount afterDedupe=${allPanels.size} used=${panels.size} :: " +
                    panels.take(10).joinToString(" | ") { (if (it.src.startsWith("data:")) "CANVAS" else hostOf(it.src).ifBlank { it.src.take(10) }) + " ${it.w}x${it.h}@${it.top}" })
                if (panels.isEmpty()) {
                    if (req.visibleOnly && targetSrc.isNullOrBlank()) {
                        // Some manga readers paint panels in canvas/iframes or expose unusable image URLs.
                        // If DOM URL discovery finds no panels, OCR the WebView rendered pixels instead.
                        Log.i(TAG, "visible discovery empty; falling back to viewport screenshot OCR; " + lastDiscoveryDebug)
                        worker.execute {
                            runCatching { processForce() }.onFailure {
                                Log.w(TAG, "visible screenshot fallback crashed: ${crashInfo(it)}", it)
                                fail(WebModeError.DECODE_FAILED, "Screen OCR failed: ${crashInfo(it)}")
                            }
                        }
                    } else {
                        fail(WebModeError.NO_IMAGES, if (targetSrc.isNullOrBlank()) WebModeError.NO_IMAGES.label
                            else "Selected image could not be found on the page")
                    }
                    return@evaluateJavascript
                }
                val meta = PageMeta(
                    url = o.optString("url", wv.url.orEmpty()),
                    lang = o.optString("lang"),
                    title = o.optString("title").trim().take(200),
                    novel = o.optString("novel").trim().take(120),
                    next = o.optString("next").takeIf { it.startsWith("http") },
                    prev = o.optString("prev").takeIf { it.startsWith("http") }
                )
                worker.execute { runCatching { process(panels, meta) }.onFailure {
                    Log.w(TAG, "job $id crashed: ${crashInfo(it)}", it)
                    fail(WebModeError.DECODE_FAILED, "Web Mode crashed: ${crashInfo(it)}")
                } }
            }
        }

        /** "RuntimeException: message @File.kt:123" - enough to find the failing line from a screenshot. */
        private fun crashInfo(t: Throwable): String {
            val root = generateSequence(t) { it.cause }.last()
            val frame = root.stackTrace.firstOrNull { it.className.startsWith("com.ashraf") }
                ?: root.stackTrace.firstOrNull()
            val msg = (root.message ?: t.message).orEmpty().replace('\n', ' ').take(90)
            return root.javaClass.simpleName + (if (msg.isNotBlank()) ": $msg" else "") +
                (frame?.let { " @${it.fileName}:${it.lineNumber}" } ?: "")
        }

        // ---- phase 2 (worker thread): fetch -> decode -> OCR, bounded memory and network concurrency
        private var fetchPool: ExecutorService? = null
        private var lensOcrPool: ExecutorService? = null

        private fun process(panels: List<PanelRef>, meta: PageMeta) {
            try { processInner(panels, meta) } finally {
                try { fetchPool?.shutdownNow() } catch (_: Throwable) {}
                fetchPool = null
                try { lensOcrPool?.shutdownNow() } catch (_: Throwable) {}
                lensOcrPool = null
            }
        }

        private fun processInner(panels: List<PanelRef>, meta: PageMeta) {
            val total = panels.size
            val liveView = req.visibleOnly && req.forceOcrSrc.isNullOrBlank()
            val hasCanvas = panels.any { it.src.startsWith("data:", true) }
            if (hasCanvas) how = "canvas"
            OcrDiag.log("PROCESS", "panels=$total liveView=$liveView canvasPanels=${panels.count { it.src.startsWith("data:", true) }} script=${WebModeLogic.pickScript(meta.lang, meta.url).name} lang='${meta.lang}'")
            if (liveView && !hasCanvas && lastDiscoveryDebug.contains("canvasErr=SecurityError") &&
                Regex("(\\d+) canvas").find(lastDiscoveryDebug)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } == true) {
                // Pixel-locked canvas reader: downloads can only return unrelated images, so go straight to the screen.
                fail(WebModeError.IMAGES_NOT_LOADED, "Canvas reader: reading the screen directly")
                return
            }
            if (liveView && !hasCanvas) {
                // This site already refused our image downloads a moment ago: read the screen's own pixels at once
                // instead of waiting for every panel to time out again.
                if (SiteLearner.preferScreen(wv.context, hostOf(meta.url))) {
                    progress("Learned: reading this site straight from the screen", 3)
                    fail(WebModeError.IMAGES_NOT_LOADED, "Learned for this site: image downloads fail, reading the screen directly")
                    return
                }
            }
            val script = newScript(meta.lang, meta.url)
            // Lens is remote/network-bound: visible panels must not wait for the previous panel's
            // Lens response. Warm the engine safely first, then fetch and OCR a small viewport
            // concurrently. All local-model scans remain serial exactly as before.
            val lensParallel = liveView && req.ahead <= 0 && total >= 1 &&
                Prefs.get(wv.context, "ocrEngine", "mlkit") == "lens"
            if (lensParallel) {
                (engine as? RoutingOcrEngine)?.let {
                    it.configure(wv.context)
                    it.prepareForConcurrentLens(script.current)
                }
            }
            val pool: ExecutorService? = if (lensParallel || (total > 1 && !req.visibleOnly)) {
                val threads = if (lensParallel) minOf(3, total) else FETCH_THREADS
                Executors.newFixedThreadPool(threads) { r -> Thread(r, "ns-webmode-fetch") }
            } else null
            fetchPool = pool
            val ocrPool: ExecutorService? = if (lensParallel) {
                Executors.newFixedThreadPool(minOf(3, total)) { r -> Thread(r, "ns-webmode-lens-ocr") }
            } else null
            lensOcrPool = ocrPool
            val futures = arrayOfNulls<java.util.concurrent.Future<ByteArray?>>(total)
            val lensOcrFutures = arrayOfNulls<java.util.concurrent.Future<LensPanelOcrAttempt>>(total)
            val panelFetchStartedAt = LongArray(total)
            fun submitFetch(i: Int) {
                if (pool == null || i < 0 || i >= total || futures[i] != null) return
                futures[i] = pool.submit(java.util.concurrent.Callable<ByteArray?> {
                    try { fetchWithRetry(panels[i], meta.url) } catch (_: Throwable) { null }
                })
            }
            for (k in 0 until minOf(FETCH_AHEAD, total)) submitFetch(k)
            val texts = arrayOfNulls<String>(total)
            val layouts = arrayOfNulls<List<WmBubble>>(total)
            val seen = HashSet<String>()
            var fetchFailed = 0
            var decodeFailed = 0
            var lastCrash: String? = null
            for ((i, p) in panels.withIndex()) {
                if (!alive()) { onMain { req.onDone(WebModeOutcome.Failure(WebModeError.NAVIGATION_INVALIDATED, WebModeError.NAVIGATION_INVALIDATED.label, diag)) }; return }
                if (elapsed() > JOB_MAX_MS) { diag.timedOut = true; fail(WebModeError.TIMEOUT); return }
                if (req.visibleOnly && req.forceOcrSrc.isNullOrBlank()) {
                    // Real-time: this panel may already have been read ahead (download + full OCR done).
                    val hit = synchronized(aheadCache) { aheadCache[WebModeLogic.normalizeUrl(p.src)] }
                    if (hit != null) {
                        diag.downloaded++; diag.decoded++
                        val ct = hit.text
                        if (ct.isBlank()) diag.ocrEmpty++ else { diag.ocrOk++; diag.chars += ct.length }
                        texts[i] = ct
                        layouts[i] = hit.bubbles
                        continue
                    }
                }
                val tFetch = SystemClock.elapsedRealtime()
                progress("Loading image ${i + 1}/$total", 10 + i * 89 / total)
                submitFetch(i)
                submitFetch(i + FETCH_AHEAD)
                val bytes = if (pool != null) {
                    val fetchWaitMs = when {
                        lensParallel -> LENS_IMAGE_FETCH_WAIT_MS
                        req.visibleOnly -> VISIBLE_FETCH_WAIT_MS
                        else -> (JOB_MAX_MS - elapsed()).coerceAtLeast(1L)
                    }
                    try { futures[i]?.get(fetchWaitMs, TimeUnit.MILLISECONDS) }
                    catch (_: Throwable) { futures[i]?.cancel(true); null }
                } else fetchWithRetry(p, meta.url)
                futures[i] = null
                OcrDiag.log("FETCH", "panel ${i + 1}/$total ${if (bytes == null) "FAILED" else "${bytes.size}B"} in ${SystemClock.elapsedRealtime() - tFetch}ms ${if (p.src.startsWith("data:", true)) "(canvas export)" else p.src.take(70)}")
                if (bytes == null) {
                    fetchFailed++; diag.failed++
                    // The first panels all refuse to download (hotlink / CORS / TLS block): do not burn
                    // minutes on the other panels, switch to reading the WebView's own pixels right away.
                    // Real-time sees only 1-3 panels, so the old "4 failures" rule never fired and EVERY visible panel
                    // went through the whole timeout ladder (roughly 25 s each) before the screen was read instead.
                    if (diag.downloaded == 0 && (liveView || (fetchFailed >= EARLY_ABORT_FAILS && total > fetchFailed))) {
                        if (liveView) SiteLearner.recordDownload(wv.context, hostOf(meta.url), false, SystemClock.elapsedRealtime() - tFetch)
                        if (liveView) {
                            // The WebView can display images that a second HTTP request cannot fetch.
                            // Read its already-rendered viewport instead of giving up on the visible page.
                            Log.i(TAG, "visible image fetch failed; falling back to viewport screenshot OCR")
                            processForce()
                        } else {
                            fail(WebModeError.IMAGES_NOT_LOADED, "Found ${diag.discovered} images but none could be downloaded")
                        }
                        return
                    }
                    continue
                }
                if (!seen.add(md5(bytes))) { diag.duplicates++; continue }
                diag.downloaded++
                panelFetchStartedAt[i] = tFetch
                if (lensParallel) {
                    // Lens is a remote OCR request; submit each visible panel now and collect in source
                    // order below. Up to three requests overlap, so their network time is shared rather
                    // than added serially. The downstream chapter/chatbot batch remains unchanged.
                    progress("Google Lens OCR ${i + 1}/$total", 10 + i * 89 / total)
                    val panelScript = ScriptState(script.current)
                    lensOcrFutures[i] = ocrPool!!.submit(java.util.concurrent.Callable {
                        try {
                            LensPanelOcrAttempt(ocrBytes(bytes, panelScript, p.src, fastMode = true), null)
                        } catch (e: OcrUnavailableException) {
                            LensPanelOcrAttempt(null, e)
                        } catch (e: DecodeException) {
                            LensPanelOcrAttempt(null, e)
                        } catch (e: OutOfMemoryError) {
                            LensPanelOcrAttempt(null, e)
                        } catch (e: RuntimeException) {
                            LensPanelOcrAttempt(null, e)
                        }
                    })
                    continue
                }
                try {
                    progress("OCR model / reading text ${i + 1}/$total", 10 + i * 89 / total)
                    val targeted = !req.forceOcrSrc.isNullOrBlank()
                    // Scope separation is intentional: full-chapter scans use thorough OCR;
                    // only explicitly visible real-time requests use the low-latency fast pass.
                    val fastPass = !targeted && req.visibleOnly
                    val po = if (targeted && req.forceOcrRegion != null) {
                        // Some Android codecs throw while decoding a selected region. Retry with a
                        // bounded downsampled bitmap rather than aborting manual OCR.
                        try {
                            ocrBytes(bytes, script, p.src, fastMode = false, crop = req.forceOcrRegion)
                        } catch (cropError: RuntimeException) {
                            Log.w(TAG, "Force OCR crop failed; retrying bitmap crop: ${cropError.javaClass.simpleName}: ${cropError.message}")
                            ocrBytes(bytes, script, p.src, fastMode = false, crop = req.forceOcrRegion, cropFallback = true)
                        }
                    } else {
                        ocrBytes(bytes, script, p.src, fastMode = fastPass)
                    }
                    diag.decoded++
                    if (po == null) continue                      // too small to be a panel: ignored, not an error
                    val t = po.text
                    if (t.isBlank()) diag.ocrEmpty++ else { diag.ocrOk++; diag.chars += t.length }
                    texts[i] = t
                    layouts[i] = po.bubbles
                    if (liveView && t.isNotBlank()) SiteLearner.recordDownload(wv.context, hostOf(meta.url), true, SystemClock.elapsedRealtime() - tFetch)
                } catch (e: OcrUnavailableException) {
                    fail(WebModeError.OCR_MODEL_UNAVAILABLE, e.message ?: WebModeError.OCR_MODEL_UNAVAILABLE.label)
                    return
                } catch (e: DecodeException) {
                    decodeFailed++; diag.failed++
                } catch (e: OutOfMemoryError) {
                    decodeFailed++; diag.failed++
                    lastCrash = "OutOfMemoryError"
                } catch (e: RuntimeException) {
                    // One unreadable panel must not abort the whole scan; remember why for the message.
                    decodeFailed++; diag.failed++
                    lastCrash = crashInfo(e)
                    Log.w(TAG, "panel OCR crashed: $lastCrash", e)
                }
            }

            if (lensParallel) {
                val batchStartedAt = SystemClock.elapsedRealtime()
                var submitted = 0
                for (i in lensOcrFutures.indices) {
                    val future = lensOcrFutures[i] ?: continue
                    submitted++
                    if (!alive()) {
                        future.cancel(true)
                        continue
                    }
                    // The Lens request itself has a 6.5 s deadline. Bound the caller too:
                    // a stuck worker/engine setup must not inherit the chapter's 12-minute timeout.
                    val attempt = try {
                        future.get(SCREEN_LENS_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        future.cancel(true)
                        LensPanelOcrAttempt(null, e)
                    }
                    val error = attempt.error
                    when (error) {
                        is OcrUnavailableException -> {
                            fail(WebModeError.OCR_MODEL_UNAVAILABLE, error.message ?: WebModeError.OCR_MODEL_UNAVAILABLE.label)
                            return
                        }
                        is DecodeException -> { decodeFailed++; diag.failed++; lastCrash = crashInfo(error) }
                        is OutOfMemoryError -> { decodeFailed++; diag.failed++; lastCrash = "OutOfMemoryError" }
                        is Exception -> { decodeFailed++; diag.failed++; lastCrash = crashInfo(error) }
                        is Error -> { decodeFailed++; diag.failed++; lastCrash = crashInfo(error) }
                        null -> {
                            diag.decoded++
                            val po = attempt.panel ?: continue
                            val t = po.text
                            if (t.isBlank()) diag.ocrEmpty++ else { diag.ocrOk++; diag.chars += t.length }
                            texts[i] = t
                            layouts[i] = po.bubbles
                            if (liveView && t.isNotBlank()) {
                                SiteLearner.recordDownload(wv.context, hostOf(meta.url),
                                    true, SystemClock.elapsedRealtime() - panelFetchStartedAt[i])
                            }
                        }
                    }
                }
                Log.i(TAG, "LENS_VISIBLE_PARALLEL panels=$total submitted=$submitted " +
                    "batchWaitMs=${SystemClock.elapsedRealtime() - batchStartedAt} decoded=${diag.decoded} " +
                    "chars=${diag.chars} imageHost=${hostOf(meta.url)}")
            }
            if (!alive()) { onMain { req.onDone(WebModeOutcome.Failure(WebModeError.NAVIGATION_INVALIDATED, WebModeError.NAVIGATION_INVALIDATED.label, diag)) }; return }
            if (script.locked) SiteLearner.saveScript(wv.context, hostOf(meta.url), script.current.name)
            progress("Collecting recognized text", 99)
            when {
                diag.downloaded == 0 -> { fail(WebModeError.IMAGES_NOT_LOADED, "Found ${diag.discovered} images but none could be downloaded"); return }
                diag.decoded == 0 -> {
                    fail(WebModeError.DECODE_FAILED, "Downloaded ${diag.downloaded} images but none could be decoded" +
                        (lastCrash?.let { " ($it)" } ?: ""))
                    return
                }
                diag.chars == 0 -> { fail(WebModeError.OCR_EMPTY, "Scanned ${diag.decoded} images; OCR found no text"); return }
                WebModeLogic.tooManyFailures(total, diag.failed) -> {
                    fail(WebModeError.IMAGES_NOT_LOADED, "${diag.failed} of $total panels failed; the chapter would be incomplete")
                    return
                }
            }
            val title = meta.title.ifBlank { hostOf(meta.url) }
            val panelTexts = texts.map { it.orEmpty() }
            // In live mode the title is metadata, not an OCR paragraph. Leaving it in the
            // source stream shifts every [NNN] marker and makes the first bubble unmatchable.
            val text = if (req.visibleOnly) WebModeLogic.assemble("", panelTexts)
                else WebModeLogic.assemble(title, panelTexts)
            // Keep every OCR block's exact source text beside its geometry. The main thread can then
            // deduplicate bubbles across overlapping viewports and map each reply to its own box.
            val allBubbles = ArrayList<WmBubble>()
            for (l in layouts) if (l != null) allBubbles.addAll(l)
            val titleOffset = if (req.visibleOnly || title.isBlank()) 0 else 1
            val scanLayout = WebModeLayout(titleOffset, allBubbles)
            if (!req.forceOcrSrc.isNullOrBlank() && req.forceOcrRegion != null) {
                // Deliberate Force OCR must replace geometry in the selected region instead of
                // letting the ordinary merge logic keep stale text/boxes from an earlier pass.
                WebModeLayoutStore.replaceRegion(wv.context, meta.url, req.forceOcrSrc, req.forceOcrRegion, scanLayout)
            } else {
                WebModeLayoutStore.put(wv.context, meta.url, scanLayout)
            }
            val ch = Chapter(
                title = title,
                text = text,
                next = meta.next,
                prev = meta.prev,
                url = meta.url,
                novel = meta.novel.ifBlank { hostOf(meta.url).removePrefix("www.") },
                number = WebModeLogic.episodeNumber(title, meta.url),
                contentSel = ""
            )
            Log.i(TAG, diag.summary())
            val failedPanels = diag.failed
            OcrDiag.jobDone(diag.host, diag.summary(), true, how, elapsed(), "")
            onMain { req.onDone(WebModeOutcome.Success(ch, diag, failedPanels)) }
        }

        // ---- FORCE mode: no image is downloaded. The page is scrolled from the top and the WebView's own
        // pixels (exactly what the user sees) are OCR'd per image. Works for blob:/encrypted/CORS/hot-link images.
        private fun <T> evalWait(
            js: String,
            timeoutMs: Long = if (req.visibleOnly) 1_200L else 20_000L,
            parse: (String?) -> T
        ): T {
            val latch = CountDownLatch(1)
            var res: String? = null
            handler.post {
                try { wv.evaluateJavascript(js) { r -> res = r; latch.countDown() } }
                catch (_: Exception) { latch.countDown() }
            }
            if (!latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "page JS response timeout visibleOnly=${req.visibleOnly} waitMs=$timeoutMs")
            }
            return parse(res)
        }

        private fun isBlank(b: Bitmap): Boolean {
            val first = b.getPixel(b.width / 2, b.height / 2)
            var differs = 0
            for (yi in 1..12) for (xi in 1..8) {
                if (b.getPixel(b.width * xi / 9, b.height * yi / 13) != first) differs++
            }
            return differs < 3
        }

        /** Capture the visible WebView from the hardware-composited window surface. */
        private fun captureByPixelCopy(allowWindowFallback: Boolean = true): Bitmap? {
            if (android.os.Build.VERSION.SDK_INT < 26) return null
            var act: android.app.Activity? = null
            var c: android.content.Context? = wv.context
            while (c is android.content.ContextWrapper) {
                if (c is android.app.Activity) { act = c; break }
                c = c.baseContext
            }
            val win = act?.window ?: return null
            val latch = CountDownLatch(1)
            var out: Bitmap? = null
            handler.post {
                val loc = IntArray(2)
                val vw = wv.width
                val vh = wv.height
                try {
                    wv.getLocationInWindow(loc)
                    if (vw <= 0 || vh <= 0 || !wv.isAttachedToWindow) {
                        Log.w(TAG, "PixelCopy skipped: WebView has no visible bounds")
                        latch.countDown()
                        return@post
                    }

                    // First request only the WebView rectangle. Some Android/OEM windows return
                    // an empty crop even though the WebView is visible, so retry from the whole
                    // window surface and crop in app-window coordinates.
                    fun requestFullWindow() {
                        val decor = win.decorView
                        val dw = decor.width
                        val dh = decor.height
                        if (dw <= 0 || dh <= 0) {
                            Log.w(TAG, "PixelCopy full-window fallback has empty decor bounds")
                            latch.countDown()
                            return
                        }
                        val full = try {
                            Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                        } catch (_: Throwable) {
                            latch.countDown()
                            return
                        }
                        try {
                            android.view.PixelCopy.request(win, null, full, { code ->
                                try {
                                    if (code == android.view.PixelCopy.SUCCESS) {
                                        val right = loc[0] + vw
                                        val bottom = loc[1] + vh
                                        if (loc[0] >= 0 && loc[1] >= 0 &&
                                            right <= full.width && bottom <= full.height) {
                                            val crop = Bitmap.createBitmap(full, loc[0], loc[1], vw, vh)
                                            if (!isBlank(crop)) out = crop else crop.recycle()
                                        }
                                    }
                                    if (out !== full) full.recycle()
                                    if (out == null) {
                                        Log.w(TAG, "PixelCopy full-window fallback failed: result=$code, " +
                                            "window=${dw}x${dh}, view=${vw}x${vh}, " +
                                            "at=${loc[0]},${loc[1]}")
                                    }
                                } catch (e: Throwable) {
                                    if (!full.isRecycled && out !== full) full.recycle()
                                    Log.w(TAG, "PixelCopy full-window crop failed: ${e.javaClass.simpleName}")
                                } finally {
                                    latch.countDown()
                                }
                            }, handler)
                        } catch (e: Throwable) {
                            if (!full.isRecycled) full.recycle()
                            Log.w(TAG, "PixelCopy full-window request failed: ${e.javaClass.simpleName}")
                            latch.countDown()
                        }
                    }

                    val tile = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
                    try {
                        android.view.PixelCopy.request(
                            win, Rect(loc[0], loc[1], loc[0] + vw, loc[1] + vh), tile,
                            { code ->
                                if (code == android.view.PixelCopy.SUCCESS && !isBlank(tile)) {
                                    out = tile
                                    latch.countDown()
                                } else {
                                    tile.recycle()
                                    Log.w(TAG, "PixelCopy WebView-crop failed/blank: result=$code")
                                    // A full-window crop depends on native/window coordinates and can
                                    // include the chatbot strip or system/app controls during split resize.
                                    // Keep that fallback for manual full-page OCR only, never realtime OCR.
                                    if (allowWindowFallback) requestFullWindow() else latch.countDown()
                                }
                            }, handler
                        )
                    } catch (e: Throwable) {
                        tile.recycle()
                        Log.w(TAG, "PixelCopy WebView-crop request failed: ${e.javaClass.simpleName}")
                        if (allowWindowFallback) requestFullWindow() else latch.countDown()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "PixelCopy setup failed: ${e.javaClass.simpleName}")
                    latch.countDown()
                }
            }
            latch.await(if (req.visibleOnly) 1_500L else 12_000L, TimeUnit.MILLISECONDS)
            return out
        }

        private fun captureView(): Bitmap? {
            // WebView.draw() while a hardware-composited page is live can flash on some devices.
            // Realtime recovery uses PixelCopy on the visible WebView rectangle only: no off-screen
            // draw and no full-window fallback that could include chatbot controls or native toasts.
            if (!req.visibleOnly || req.isOccluded()) {
                val a = captureByDraw()
                if (a != null && !isBlank(a)) return a
                a?.recycle()
                if (req.isOccluded()) {
                    // draw() is blank for canvas/hardware-composited readers (e.g. shonenjumpplus).
                    // Take the WebView rectangle with PixelCopy and paint every native overlay
                    // (chat pane, buttons) over with the surrounding colour so only page pixels remain.
                    val pc = captureByPixelCopy(allowWindowFallback = false)
                    if (pc != null && !isBlank(pc)) {
                        try { maskNativeOverlays(pc) } catch (_: Throwable) {}
                        return pc
                    }
                    pc?.recycle()
                    forceBlank++
                    return null
                }
            }
            val b = captureByPixelCopy(allowWindowFallback = !req.visibleOnly)
            if (b != null && !isBlank(b)) {
                // PixelCopy copies that rectangle of the WINDOW, so everything drawn above the WebView (status pill,
                // URL bar, buttons, toasts) is in the bitmap too. Lens read that UI text, the chatbot translated it
                // ("Reading the page" -> Bengali chip) and it was pasted onto the page. Mask it in realtime as well;
                // the view-tree walk is one cheap main-thread hop.
                try { maskNativeOverlays(b) } catch (_: Throwable) {}
                return b
            }
            b?.recycle()
            forceBlank++
            Log.w(TAG, "Visible screen capture returned blank after safe capture paths; " +
                "visibleOnly=${req.visibleOnly}, view=${wv.width}x${wv.height}, " +
                "attached=${wv.isAttachedToWindow}, hardware=${wv.isHardwareAccelerated}")
            return null
        }

        /**
         * Fingerprint the original pixels of a tainted Shonen Jump+ canvas from the clean PixelCopy bitmap.
         * This runs before OCR, so a page swap is detectable even if Lens is offline or OCR returns no text.
         * Sample points are element-relative (not viewport-relative); clipped points are marked '?' and
         * ignored by the comparison, so normal scrolling does not by itself count as a new manga page.
         */
        private fun observeCanvasPixelFingerprints(bmp: Bitmap, info: JSONObject, url: String) {
            val host = hostOf(url).lowercase().removePrefix("www.")
            if (host != "shonenjumpplus.com" && !host.endsWith(".shonenjumpplus.com")) return
            val viewportWidth = info.optDouble("vw", 0.0).toFloat()
            if (viewportWidth <= 0f) return
            val scale = bmp.width / viewportWidth
            val imgs = info.optJSONArray("imgs") ?: return
            val grid = floatArrayOf(0.12f, 0.23f, 0.34f, 0.45f, 0.55f, 0.66f, 0.77f, 0.88f)

            for (i in 0 until imgs.length()) {
                val p = imgs.optJSONObject(i) ?: continue
                val src = p.optString("s")
                if (!src.startsWith("ns-canvas:")) continue
                val left = p.optDouble("vl", Double.NaN).toFloat() * scale
                val top = p.optDouble("vt", Double.NaN).toFloat() * scale
                val width = p.optDouble("fw", 0.0).toFloat() * scale
                val height = p.optDouble("fh", 0.0).toFloat() * scale
                if (!left.isFinite() || !top.isFinite() || width < 120f || height < 120f) continue

                val signature = StringBuilder(grid.size * grid.size)
                for (fy in grid) for (fx in grid) {
                    val px = (left + fx * width).toInt()
                    val py = (top + fy * height).toInt()
                    if (px < 0 || px >= bmp.width || py < 0 || py >= bmp.height) {
                        signature.append('?')
                    } else {
                        val c = bmp.getPixel(px, py)
                        val r = android.graphics.Color.red(c)
                        val g = android.graphics.Color.green(c)
                        val b = android.graphics.Color.blue(c)
                        val tone = ((r * 299 + g * 587 + b * 114) / 1000 / 32).coerceIn(0, 7)
                        signature.append(('0'.code + tone).toChar())
                    }
                }
                try {
                    WebModeLayoutStore.observeCanvasFrame(wv.context, url, src, signature.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Canvas frame fingerprint failed: ${e.javaClass.simpleName}")
                }
            }
        }

        /**
         * Native views that are drawn ABOVE the WebView (floating panels, buttons, chips, toasts) end up in a
         * window screenshot. Collect their bounds (WebView-local pixels) and flood them with the colour just
         * outside, so no recogniser can read app UI text or mistake a button edge for lettering.
         */
        private fun maskNativeOverlays(bmp: Bitmap) {
            val rects = ArrayList<Rect>()
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    val root = wv.rootView
                    val wl = IntArray(2)
                    wv.getLocationInWindow(wl)
                    val wvArea = (wv.width.toLong() * wv.height).coerceAtLeast(1L)
                    var passed = false
                    val tmp = IntArray(2)
                    fun isAncestor(g: ViewGroup, target: View): Boolean {
                        var p = target.parent
                        while (p != null) { if (p === g) return true; p = p.parent }
                        return false
                    }
                    fun add(v: View) {
                        v.getLocationInWindow(tmp)
                        val r = Rect(tmp[0] - wl[0], tmp[1] - wl[1], tmp[0] - wl[0] + v.width, tmp[1] - wl[1] + v.height)
                        if (!r.intersect(0, 0, bmp.width, bmp.height)) return
                        if (r.width() < 4 || r.height() < 4) return
                        // a full-screen layer is a container, not a button: masking it would blank the page
                        if (r.width().toLong() * r.height() > wvArea * 0.7) return
                        rects.add(r)
                    }
                    fun walk(v: View) {
                        if (v === wv) { passed = true; return }
                        if (v.visibility != View.VISIBLE || v.alpha <= 0.02f || v.width <= 0 || v.height <= 0) return
                        if (v is ViewGroup) {
                            if (isAncestor(v, wv)) { for (i in 0 until v.childCount) walk(v.getChildAt(i)); return }
                            if (passed && v.background != null) { add(v); return }
                            for (i in 0 until v.childCount) walk(v.getChildAt(i))
                        } else if (passed) {
                            add(v)
                        }
                    }
                    if (root != null) walk(root)
                } catch (_: Throwable) {
                } finally { latch.countDown() }
            }
            latch.await(2, TimeUnit.SECONDS)
            if (rects.isEmpty()) return
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            for (r in rects) {
                // colour from the pixels just OUTSIDE the rectangle (page / bubble background)
                val xs = intArrayOf(r.left - 2, r.centerX(), r.right + 1)
                val ys = intArrayOf(r.top - 2, r.centerY(), r.bottom + 1)
                var rr = 0; var gg = 0; var bb = 0; var n = 0
                for (x in xs) for (y in ys) {
                    if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
                    if (x in r.left until r.right && y in r.top until r.bottom) continue
                    val c = bmp.getPixel(x, y)
                    rr += (c shr 16) and 255; gg += (c shr 8) and 255; bb += c and 255; n++
                }
                paint.color = if (n == 0) android.graphics.Color.WHITE
                    else android.graphics.Color.rgb(rr / n, gg / n, bb / n)
                canvas.drawRect(r, paint)
            }
        }

        private fun captureByDraw(): Bitmap? {
            val latch = CountDownLatch(1)
            var out: Bitmap? = null
            handler.post {
                try {
                    val w = wv.width; val h = wv.height
                    if (w > 0 && h > 0) {
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        wv.draw(android.graphics.Canvas(b))
                        out = b
                    }
                } catch (_: Throwable) {
                } finally { latch.countDown() }
            }
            latch.await(if (req.visibleOnly) 1_500L else 20_000L, TimeUnit.MILLISECONDS)
            return out
        }

        @Volatile private var forceBlank = 0
        @Volatile private var forceStepNo = 0
        /** Once whole-screen OCR worked in a Force scan, later screens skip the useless DOM-crop attempt. */
        @Volatile private var screenModeOn = false
        private val screenSeen = HashSet<String>()
        @Volatile private var forceShots = 0

        private fun forceFail(e: WebModeError, msg: String = e.label) {
            evalWait(wrap(LIB + JS_RESTORE)) { null }
            // Visible-only recovery must not toggle the translation overlay. Hiding it
            // for compositor capture caused the brief black flash during realtime OCR.
            if (!req.visibleOnly) evalWait(wrap(JS_FORCE_SHOW)) { null }
            fail(e, msg)
        }

        private fun processForce() {
            val forceStart = SystemClock.elapsedRealtime()
            fellBackToForce = true   // never run the screenshot path twice for one job
            how = "screen"
            OcrDiag.log("FORCE_BEGIN", "visibleOnly=${req.visibleOnly} engine=${Prefs.get(wv.context, "ocrEngine", "mlkit")}")
            // A viewport-only recovery needs chapter metadata, not a full-page inventory. Defer
            // candidate collection until JS_FORCE_INFO so CSS-background panels are not lost to
            // cands()' 1200 ms background-probe throttle between these two calls.
            val collectBody = if (req.visibleOnly) {
                JS_COLLECT.replaceFirst(
                    "var sc=scroller(),c=cands(sc,true);",
                    "var sc=scroller(),c=[];"
                )
            } else JS_COLLECT
            val mo = evalWait(wrap(LIB + collectBody)) { parseJson(it) }
            if (mo == null) { fail(WebModeError.NO_IMAGES); return }
            val meta = PageMeta(
                url = mo.optString("url", startUrl),
                lang = mo.optString("lang"),
                title = mo.optString("title").trim().take(200),
                novel = mo.optString("novel").trim().take(120),
                next = mo.optString("next").takeIf { it.startsWith("http") },
                prev = mo.optString("prev").takeIf { it.startsWith("http") }
            )
            val script = newScript(meta.lang, meta.url)
            val accs = LinkedHashMap<String, ForceAcc>()
            // In visible-only recovery, collect image candidates exactly once and include CSS
            // backgrounds; full-chapter Force Extract retains its original throttled scan.
            val forceInfoScript = if (req.visibleOnly) {
                JS_FORCE_INFO.replaceFirst(
                    "var sc=scroller(),c=cands(sc,undefined,true,true),vw=",
                    "var sc=scroller(),c=cands(sc,true,true,true),vw="
                )
            } else JS_FORCE_INFO
            // Hide the overlay only for full-chapter screenshot extraction. Realtime visible-only
            // fallback leaves the reader DOM untouched to avoid on-screen flicker.
            if (!req.visibleOnly) evalWait(wrap(JS_FORCE_HIDE)) { null }
            if (!req.visibleOnly) evalWait(wrap(LIB + JS_FORCE_TOP)) { null }
            var stable = 0
            var driftRetries = 0
            var steps = 0
            while (true) {
                if (!alive()) { forceFail(WebModeError.NAVIGATION_INVALIDATED); return }
                if (elapsed() > JOB_MAX_MS) { diag.timedOut = true; forceFail(WebModeError.TIMEOUT); return }
                if (steps > MAX_TICKS) break
                try { Thread.sleep(if (steps == 0) 450L else 350L) } catch (_: InterruptedException) { return }
                val info = evalWait(wrap(LIB + forceInfoScript)) { parseJson(it) }
                val blankBefore = forceBlank
                val host = hostOf(meta.url).lowercase().removePrefix("www.")
                val hideOverlayForShot = req.visibleOnly &&
                    (host == "shonenjumpplus.com" || host.endsWith(".shonenjumpplus.com"))
                if (hideOverlayForShot) {
                    evalWait(wrap(JS_TEMP_HIDE_OVERLAY)) { null }
                    // Wait for the visibility change to reach the WebView surface before PixelCopy.
                    try { Thread.sleep(70L) } catch (_: InterruptedException) {
                        evalWait(wrap(JS_TEMP_SHOW_OVERLAY)) { null }
                        return
                    }
                }
                var bmp: Bitmap? = null
                var blankTry = 0
                try {
                    bmp = captureView()
                    // A single blank PixelCopy happens while the WebView surface is repainting (just after scroll,
                    // page switch or a renderer restart). Retry a few times instead of failing the whole job.
                    while (bmp == null && blankTry < 3 && alive() && !req.isOccluded()) {
                        blankTry++
                        try { Thread.sleep(220L * blankTry) } catch (_: InterruptedException) { return }
                        bmp = captureView()
                    }
                    if (bmp == null && req.visibleOnly && !req.isOccluded() && alive()) {
                        // Last resort: draw the WebView into a bitmap (may flash on some devices, but better than nothing).
                        val d = captureByDraw()
                        if (d != null && !isBlank(d)) bmp = d else d?.recycle()
                    }
                } finally {
                    if (hideOverlayForShot) evalWait(wrap(JS_TEMP_SHOW_OVERLAY)) { null }
                }
                if (bmp != null) {
                    forceBlank = blankBefore   // a retry succeeded: this screen was not blank after all
                    if (hideOverlayForShot && info != null) {
                        try { observeCanvasPixelFingerprints(bmp, info, meta.url) }
                        catch (e: Exception) { Log.w(TAG, "Canvas fingerprint failed: ${e.javaClass.simpleName}") }
                    }
                }
                // The crop rectangles in [info] were measured BEFORE the screenshot (plus up to ~1.3 s of blank-capture
                // retries). If the page moved in between, every bubble fraction would be computed against the wrong
                // image offset and the translation would land on the wrong bubble. Re-measure (one tiny script) and
                // take the screenshot again instead of placing text from a shifted frame.
                if (bmp != null && req.visibleOnly && info != null && info.has("sy") && driftRetries < 2) {
                    val probe = evalWait(wrap(LIB + JS_SCROLL_PROBE)) { parseJson(it) }
                    if (probe != null &&
                        (Math.abs(probe.optDouble("y") - info.optDouble("sy")) > 1.0 ||
                            Math.abs(probe.optDouble("h") - info.optDouble("sh")) > 2.0)) {
                        driftRetries++
                        OcrDiag.log("FORCE_STEP", "page moved during capture (dy=${probe.optDouble("y") - info.optDouble("sy")}) -> recapture $driftRetries/2")
                        bmp.recycle()
                        continue
                    }
                }
                forceShots++
                forceStepNo = steps
                OcrDiag.log("FORCE_STEP", "step=$steps jsInfo=${info != null} domCandidates=${info?.optJSONArray("imgs")?.length() ?: -1} " +
                    "bitmap=${bmp?.let { "${it.width}x${it.height}" } ?: "NULL(blank capture)"} blankSoFar=$forceBlank retries=$blankTry")
                if (bmp != null) {
                    // JS discovery can time out on canvas/cross-origin iframe readers even while the
                    // WebView has perfectly visible pixels. A viewport-only scan can still OCR that bitmap.
                    val scanInfo = info ?: if (req.visibleOnly) {
                        Log.w(TAG, "JS_FORCE_INFO returned no data; OCRing the visible WebView bitmap directly")
                        JSONObject().put("vw", bmp.width.toFloat()).put("imgs", JSONArray())
                    } else null
                    if (scanInfo != null) {
                        try { forceOcr(bmp, scanInfo, script, accs) }
                        catch (e: OcrUnavailableException) {
                            forceFail(WebModeError.OCR_MODEL_UNAVAILABLE, e.message ?: WebModeError.OCR_MODEL_UNAVAILABLE.label); return
                        } finally { bmp.recycle() }
                    } else bmp.recycle()
                }
                progress("Reading screen ${steps + 1} (${accs.size} images)", 5 + minOf(90, steps * 3))
                if (req.visibleOnly) break
                val bottom = evalWait(wrap(LIB + JS_FORCE_SCROLL)) { parseJson(it)?.optBoolean("bottom", true) ?: true }
                stable = if (bottom) stable + 1 else 0
                if (stable >= 2) break
                steps++
            }
            evalWait(wrap(LIB + JS_RESTORE)) { null }
            if (!req.visibleOnly) evalWait(wrap(JS_FORCE_SHOW)) { null }
            if (!alive()) { fail(WebModeError.NAVIGATION_INVALIDATED); return }

            val panels = accs.values.sortedBy { it.top }
            diag.discovered = accs.size
            diag.downloaded = accs.size
            diag.decoded = accs.size
            val panelTexts = panels.map { it.texts.joinToString("\n\n") }
            diag.chars = panelTexts.sumOf { it.length }
            diag.ocrOk = panelTexts.count { it.isNotBlank() }
            diag.ocrEmpty = panelTexts.size - diag.ocrOk
            when {
                forceBlank > 0 && accs.isEmpty() && forceShots == forceBlank -> {
                    fail(WebModeError.DECODE_FAILED, "Force extract: every screen capture came back blank"); return }
                accs.isEmpty() -> { fail(WebModeError.NO_IMAGES, "Force extract: no loaded image on screen (screens=$forceShots)"); return }
                diag.chars == 0 -> { fail(WebModeError.OCR_EMPTY, "Force extract: ${accs.size} images, screens=$forceShots, blank=$forceBlank; OCR found no text"); return }
            }
            val title = meta.title.ifBlank { hostOf(meta.url) }
            val text = if (req.visibleOnly) WebModeLogic.assemble("", panelTexts) else WebModeLogic.assemble(title, panelTexts)
            val allBubbles = ArrayList<WmBubble>()
            for (p in panels) allBubbles.addAll(p.bubbles)
            val titleOffset = if (req.visibleOnly || title.isBlank()) 0 else 1
            WebModeLayoutStore.put(wv.context, meta.url, WebModeLayout(titleOffset, allBubbles))
            val ch = Chapter(
                title = title, text = text, next = meta.next, prev = meta.prev, url = meta.url,
                novel = meta.novel.ifBlank { hostOf(meta.url).removePrefix("www.") },
                number = WebModeLogic.episodeNumber(title, meta.url), contentSel = ""
            )
            Log.i(TAG, "force " + diag.summary())
            OcrDiag.jobDone(diag.host, diag.summary(), true, "screen", elapsed(), "")
            if (req.visibleOnly) SiteLearner.recordScreen(wv.context, hostOf(meta.url), SystemClock.elapsedRealtime() - forceStart)
            onMain { req.onDone(WebModeOutcome.Success(ch, diag, 0)) }
        }

        /** OCR the on-screen part of every loaded image in one screenshot of the WebView. */
        private fun forceOcr(bmp: Bitmap, info: JSONObject, st: ScriptState, accs: MutableMap<String, ForceAcc>) {
            data class PendingCrop(
                val bitmap: Bitmap,
                val src: String,
                val acc: ForceAcc,
                val offX: Int,
                val offY: Int,
                val fullW: Int,
                val fullH: Int,
                val first: Boolean,
                val last: Boolean
            )

            val textBefore = accs.values.sumOf { it.texts.size }
            // Some manga readers render the visible page into canvas/iframe layers, while DOM
            // discovery reports only ads/thumbs or unusable image wrappers. When no trustworthy
            // crop exists, send the captured WebView pixels directly to the selected OCR engine
            // (Google Lens remains the same engine when selected) instead of returning NO_IMAGES.
            fun readWholeViewport(): Boolean {
                if (!alive()) return false
                val t0 = SystemClock.elapsedRealtime()
                val result = try {
                    recognize(bmp, st, fastMode = req.visibleOnly)
                } catch (e: OcrUnavailableException) {
                    OcrDiag.log("SCREEN_OCR", "engine unavailable: ${e.message}")
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "whole-viewport OCR failed: ${e.javaClass.simpleName}", e)
                    OcrDiag.log("SCREEN_OCR", "crashed ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                    return false
                }
                // Lens returns whole paragraphs from a precise recogniser: use its lenient filter, not the ML Kit one.
                val lensOn = (engine as? RoutingOcrEngine)?.usesRemoteTextDetection() == true
                val noisy: (OcrBlock) -> Boolean = { b -> if (lensOn) OcrPost.isNoiseLens(b) else OcrPost.isNoise(b) }
                val dropOwnBengali = Langs.target(wv.context) == "Bengali"
                val blocks = result.blocks
                    .filter { it.text.isNotBlank() && !noisy(it) &&
                        !(dropOwnBengali && OcrPost.isMostlyBengaliScript(it.text)) }
                    .sortedWith(compareBy<OcrBlock>({ it.top }, { it.left }))
                val letters = blocks.sumOf { block -> block.text.count { ch -> ch.isLetterOrDigit() } }
                OcrDiag.log("SCREEN_OCR", "step=$forceStepNo ${bmp.width}x${bmp.height} ms=${SystemClock.elapsedRealtime() - t0} " +
                    "rawBlocks=${result.blocks.size} afterNoiseFilter=${blocks.size} letters=$letters " +
                    "droppedAsNoise=[${result.blocks.filter { noisy(it) }.take(3).joinToString(" / ") { OcrDiag.snip(it.text, 14) }}] " +
                    "first=[${OcrDiag.snip(blocks.firstOrNull()?.text)}]")
                if (blocks.isEmpty() || letters < 2) {
                    Log.w(TAG, "SCREEN_OCR_FALLBACK_EMPTY size=${bmp.width}x${bmp.height} blocks=${blocks.size} letters=$letters route=${Prefs.get(wv.context, "ocrEngine", "mlkit")}")
                    return false
                }
                val texts = blocks.map { it.text.trim() }.filter { it.isNotBlank() }
                if (req.visibleOnly) {
                    // No image-to-DOM mapping exists for canvas-only pages: keep this as a text-only chapter;
                    // MainActivity already has a geometry-free text submission path.
                    accs.clear()
                    val acc = ForceAcc()
                    acc.top = 0
                    acc.texts.addAll(texts)
                    accs[SCREEN_OCR_SOURCE] = acc
                } else {
                    // Full-chapter Force: keep every screen, but skip lines already read on the part that
                    // overlaps the previous screen.
                    screenModeOn = true
                    val fresh = texts.filter { t -> screenSeen.add(t.filter { ch -> ch.isLetterOrDigit() }) }
                    if (fresh.isNotEmpty()) {
                        val acc = ForceAcc()
                        acc.top = forceStepNo * 10_000
                        acc.texts.addAll(fresh)
                        accs[SCREEN_OCR_SOURCE + "#" + forceStepNo] = acc
                    }
                }
                Log.i(TAG, "SCREEN_OCR_FALLBACK_OK size=${bmp.width}x${bmp.height} blocks=${blocks.size} letters=$letters route=${Prefs.get(wv.context, "ocrEngine", "mlkit")}")
                return true
            }

            val vw = info.optDouble("vw", 0.0).toFloat()
            if (vw <= 0f) {
                if (!readWholeViewport()) Log.w(TAG, "screen OCR skipped: viewport width missing")
                return
            }
            val scale = bmp.width / vw // device pixels per CSS pixel
            val arr = info.optJSONArray("imgs")
            if (arr == null || arr.length() == 0) {
                if (!readWholeViewport()) Log.w(TAG, "screen OCR skipped: no DOM crop candidates")
                return
            }
            val pending = ArrayList<PendingCrop>()
            var canvasCrops = 0

            for (i in 0 until arr.length()) {
                if (!alive()) break
                val p = arr.optJSONObject(i) ?: continue
                val src = p.optString("s")
                if (src.isBlank()) continue
                val isCanvasPage = src.startsWith("ns-canvas:")
                val l = p.optDouble("vl").toFloat() * scale
                val t = p.optDouble("vt").toFloat() * scale
                val fw = p.optDouble("fw").toFloat() * scale
                val fh = p.optDouble("fh").toFloat() * scale
                if (fw < 40f || fh < 40f) continue
                val x0 = Math.max(0, Math.round(l))
                val y0 = Math.max(0, Math.round(t))
                val x1 = Math.min(bmp.width, Math.round(l + fw))
                val y1 = Math.min(bmp.height, Math.round(t + fh))
                if (x1 - x0 < 40 || y1 - y0 < 40) continue
                val crop = try { Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0) }
                    catch (_: Throwable) { continue }
                val acc = accs.getOrPut(src) { ForceAcc() }
                acc.top = p.optInt("t")
                if (isCanvasPage) canvasCrops++
                pending.add(
                    PendingCrop(
                        bitmap = crop,
                        src = src,
                        acc = acc,
                        offX = Math.max(0f, x0 - l).toInt(),
                        offY = Math.max(0f, y0 - t).toInt(),
                        fullW = fw.toInt().coerceAtLeast(1),
                        fullH = fh.toInt().coerceAtLeast(1),
                        first = (y0 - t) <= 3f,
                        last = (t + fh) <= bmp.height + 3f
                    )
                )
            }
            if (pending.isEmpty()) {
                if (!readWholeViewport()) Log.w(TAG, "screen OCR fallback found no readable text; candidates=${arr.length()}")
                return
            }

            // The fallback screenshot often contains multiple visible webtoon panels. Sequential Lens
            // uploads multiply network latency by the number of panels, so use a bounded concurrent fan-out
            // for Lens only; local ONNX/ML Kit models keep their existing serialized inference gate.
            val routing = engine as? RoutingOcrEngine
            val useParallelLens = req.visibleOnly &&
                Prefs.get(wv.context, "ocrEngine", "mlkit") == "lens" &&
                pending.size > 1 && routing != null &&
                runCatching {
                    routing.configure(wv.context)
                    routing.prepareForConcurrentLens(st.current)
                    routing.usesRemoteTextDetection()
                }.getOrDefault(false)

            try {
                if (useParallelLens) {
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(3, pending.size))
                    try {
                        val completion = java.util.concurrent.ExecutorCompletionService<Pair<Int, OcrResult?>>(pool)
                        val futures = ArrayList<java.util.concurrent.Future<Pair<Int, OcrResult?>>>()
                        for ((index, task) in pending.withIndex()) {
                            futures.add(completion.submit(java.util.concurrent.Callable {
                                val result = try {
                                    engine.recognizeFast(task.bitmap, st.current)
                                } catch (e: Throwable) {
                                    Log.w(TAG, "parallel Lens viewport OCR failed for crop $index: ${e.javaClass.simpleName}")
                                    null
                                }
                                index to result
                            }))
                        }
                        val results = arrayOfNulls<OcrResult>(pending.size)
                        val deadline = SystemClock.elapsedRealtime() + SCREEN_LENS_DEADLINE_MS
                        var finished = 0
                        while (finished < pending.size) {
                            val remaining = deadline - SystemClock.elapsedRealtime()
                            if (remaining <= 0L) break
                            val future = try { completion.poll(remaining, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            } ?: break
                            try {
                                val (index, result) = future.get()
                                if (index in results.indices) results[index] = result
                            } catch (_: Throwable) {
                            }
                            finished++
                        }
                        futures.forEach { if (!it.isDone) it.cancel(true) }
                        for (index in pending.indices) {
                            if (!alive()) break
                            val task = pending[index]
                            val recognized = results[index]
                            if (recognized == null) {
                                Log.w(TAG, "viewport Lens crop skipped after shared ${SCREEN_LENS_DEADLINE_MS}ms deadline index=$index")
                                continue
                            }
                            readSlice(
                                task.bitmap, st, task.offY, 1, task.fullW, task.fullH, task.src,
                                task.acc.texts, task.acc.bubbles, task.first, task.last, task.offX,
                                fastMode = true, preRecognized = recognized
                            )
                        }
                    } finally {
                        pool.shutdownNow()
                    }
                } else {
                    for (task in pending) {
                        if (!alive()) break
                        readSlice(
                            task.bitmap, st, task.offY, 1, task.fullW, task.fullH, task.src,
                            task.acc.texts, task.acc.bubbles, task.first, task.last, task.offX,
                            fastMode = true
                        )
                    }
                }
            } finally {
                pending.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            }
            // A candidate list can contain visible ads or wrappers rather than the displayed comic.
            // If none of those crops yielded text, give the full viewport one fast OCR pass.
            val noNewText = accs.values.sumOf { it.texts.size } == textBefore
            val noTextAtAll = accs.values.none { acc -> acc.texts.any { it.isNotBlank() } }
            OcrDiag.log("CROP_OCR", "step=$forceStepNo crops=${pending.size} newText=${!noNewText} textAnywhere=${!noTextAtAll} screenMode=$screenModeOn")
            // A canvas page WAS cropped (the real page the user reads) but has no text (picture-only page):
            // reading the whole screen now would only return header / banner / app-UI noise as "chapter text".
            if (canvasCrops > 0 && noNewText) {
                OcrDiag.log("CROP_OCR", "canvas page cropped x$canvasCrops but has no new text; whole-screen OCR skipped (would only read page chrome)")
                return
            }
            if (noNewText && (req.visibleOnly || screenModeOn || noTextAtAll)) {
                if (!readWholeViewport()) Log.w(TAG, "screen OCR fallback found no readable text after crop OCR")
            }
        }

        // ---- fetching: HTTP with the WebView's cookies; blob:/data: and refused requests go through the page itself
        private fun fetchWithRetry(p: PanelRef, pageUrl: String): ByteArray? {
            if (!alive()) return null
            val u = p.src
            // Local / page-owned sources never need HTTP retry loops.
            if (u.startsWith("data:", true)) return try { decodeDataUrl(u) } catch (_: Exception) { null }
            if (u.startsWith("blob:", true)) return try { fetchViaPage(u, timeoutMs = 7000L) } catch (_: Exception) { null }
            // The WebView already downloaded this exact image for display (same headers, cookies, TLS):
            // reuse those bytes. No second download, so hotlink/CORS/Cloudflare rules cannot block it.
            ImageTap.get(u)?.let { return it }

            if (req.ahead > 0) {
                // Read-ahead panels are often BELOW the viewport and are not loaded by the page yet.
                // Starting with page.fetch() made each image wait up to 8 s before any fallback.
                // Download directly first (cookies + Referer included); use the page only for
                // hotlink/CORS-sensitive hosts, then retry transient errors once.
                return try {
                    httpGet(u, pageUrl, medium = true)
                } catch (e: IOException) {
                    if (!alive()) return null
                    val msg = e.message.orEmpty()
                    val definitive = Regex("http\\s+(401|403|404|410|451)").containsMatchIn(msg)
                    try {
                        fetchViaPage(u, timeoutMs = 4500L)
                    } catch (_: Exception) {
                        if (!alive() || definitive) null
                        else try { httpGet(u, pageUrl, medium = true) } catch (_: Exception) { null }
                    }
                } catch (_: Exception) { null }
            }
            if (req.visibleOnly) {
                // Keep foreground real-time OCR latency-sensitive: short HTTP first, then the browser
                // cache/canvas path for image URLs protected by hotlink/CORS rules.
                return try {
                    httpGet(u, pageUrl, quick = true)
                } catch (e: IOException) {
                    if (!alive()) return null
                    // One page-side attempt, then give up: the caller switches to reading the screen itself.
                    try { fetchViaPage(u, timeoutMs = 2500L) } catch (_: Exception) { null }
                } catch (_: Exception) { null }
            }
            // A failed image used to spend up to three rounds of 15–25 s HTTP timeouts plus
            // a 25 s page-fetch timeout per round. One bad panel could therefore stall a
            // 97-panel chapter for over a minute before OCR even began. Keep each attempt bounded.
            return try {
                httpGet(u, pageUrl, quick = true)
            } catch (directError: IOException) {
                if (!alive()) return null
                try {
                    // The loaded page can often fetch a CDN image with its own cookies/session.
                    fetchViaPage(u, timeoutMs = 6500L)
                } catch (_: Exception) {
                    if (!alive()) return null
                    val msg = directError.message.orEmpty()
                    val definitiveHttpFailure = Regex("http\\s+(401|403|404|410|451)").containsMatchIn(msg)
                    // Only retry a transient network failure once; retrying denied/missing URLs
                    // cannot help and used to dominate chapter scan time.
                    if (definitiveHttpFailure) null
                    else try { httpGet(u, pageUrl, quick = true) } catch (_: Exception) { null }
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Hotlink-protected CDNs answer 401/403 for the wrong Referer. Try the page URL, then the page's
         * origin ("https://site/"), then no Referer at all, before giving up on direct HTTP.
         */
        private fun httpGet(url: String, referer: String, quick: Boolean = false, medium: Boolean = false): ByteArray {
            try {
                return httpGetOnce(url, referer, quick, medium)
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                if (!Regex("http\\s+(401|403)").containsMatchIn(msg)) throw e
                val origin = try {
                    val u = URL(referer)
                    u.protocol + "://" + u.authority + "/"
                } catch (_: Exception) { "" }
                val alternatives = ArrayList<String>()
                if (origin.isNotBlank() && origin != referer) alternatives.add(origin)
                alternatives.add("")
                var last: IOException = e
                for (alt in alternatives) {
                    try { return httpGetOnce(url, alt, quick, medium) } catch (e2: IOException) { last = e2 }
                }
                throw last
            }
        }

        private fun httpGetOnce(url: String, referer: String, quick: Boolean = false, medium: Boolean = false): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = if (quick) 2500 else if (medium) 6000 else 15000
                c.readTimeout = if (quick) 3500 else if (medium) 12000 else 25000
                c.instanceFollowRedirects = true
                // A CDN may reuse a panel URL while replacing its bytes in a live reader.
                // Disable HTTP caching so same-URL OCR rechecks see current pixels.
                c.useCaches = false
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("Pragma", "no-cache")
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                c.setRequestProperty("Accept-Encoding", "identity")
                // Look like the browser's own <img> sub-request.
                c.setRequestProperty("Sec-Fetch-Dest", "image")
                c.setRequestProperty("Sec-Fetch-Mode", "no-cors")
                c.setRequestProperty("Sec-Fetch-Site", "cross-site")
                if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
                val code = c.responseCode
                if (code !in 200..299) throw IOException("http $code")
                if (c.contentLengthLong > MAX_BYTES) throw IOException("too large")
                return c.inputStream.use { ins ->
                    val out = java.io.ByteArrayOutputStream(maxOf(32 * 1024, c.contentLength))
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("too large")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }

        private fun decodeDataUrl(u: String): ByteArray {
            val comma = u.indexOf(',')
            if (comma < 0) throw IOException("bad data url")
            val head = u.substring(0, comma)
            val body = u.substring(comma + 1)
            return if (head.contains(";base64", true)) Base64.decode(body, Base64.DEFAULT)
            else java.net.URLDecoder.decode(body, "UTF-8").toByteArray(Charsets.ISO_8859_1)
        }

        /** fetch() inside the page: works for blob: URLs and for same-origin / CORS-enabled images. */
        private fun fetchViaPage(u: String, timeoutMs: Long = 25000L): ByteArray {
            val rid = UUID.randomUUID().toString()
            val pending = Pending()
            bridge.pending[rid] = pending
            try {
                val js = "(function(id,u){" +
                    "function viaCanvas(){try{var im=null,L=document.images;" +
                    "for(var i=0;i<L.length;i++){if((L[i].currentSrc||L[i].src)===u){im=L[i];break;}}" +
                    "if(!im||!im.naturalWidth)throw new Error('no loaded img');" +
                    "var c=document.createElement('canvas');c.width=im.naturalWidth;c.height=im.naturalHeight;" +
                    "c.getContext('2d').drawImage(im,0,0);" +
                    "NsWmBridge.ok(id,c.toDataURL('image/jpeg',0.92));}catch(e){NsWmBridge.fail(id,String(e));}}" +
                    "try{fetch(u,{credentials:'include'}).then(function(r){" +
                    "if(!r.ok)throw new Error('http '+r.status);return r.blob();}).then(function(b){" +
                    "var fr=new FileReader();fr.onload=function(){NsWmBridge.ok(id,String(fr.result));};" +
                    "fr.onerror=function(){viaCanvas();};fr.readAsDataURL(b);})" +
                    ".catch(function(e){viaCanvas();});}catch(e){viaCanvas();}" +
                    "return 'started';})(" + JSONObject.quote(rid) + "," + JSONObject.quote(u) + ")"
                onMain { wv.evaluateJavascript(js, null) }
                if (!pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("page fetch timeout")
                val d = pending.data ?: throw IOException(pending.err ?: "page fetch failed")
                val bytes = decodeDataUrl(d)
                if (bytes.size > MAX_BYTES) throw IOException("too large")
                return bytes
            } finally {
                bridge.pending.remove(rid)
            }
        }

        // ---- decode + OCR. Returns null for images too small to be a panel; no bubbles for a panel without text.
        private fun ocrBytes(
            bytes: ByteArray, st: ScriptState, src: String,
            fastMode: Boolean = false, crop: WebModeOcrRegion? = null,
            cropFallback: Boolean = false
        ): PanelOcr? {
            // The hidden manga WebView is unrelated to Google Lens/ML Kit. Do not initialise it for every Chinese/English panel.
            if (Prefs.get(wv.context, "ocrEngine", "mlkit") == "manga") {
                com.ashraf.novelstudio.japanesemanga.JapaneseMangaRuntime.init(wv.context)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) throw DecodeException()
            if (w < MIN_W || h < MIN_H) return null
            // Quality must not depend on whether the panel arrived via prefetch or visible scanning.
            // The engine tiles/enlarges within its own pixel budget; retain native small-letter detail.
            val sample = when { w > 5000 -> 4; w > 2400 -> 2; else -> 1 }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val texts = ArrayList<String>()
            val bubbles = ArrayList<WmBubble>()
            fun finishResult(): PanelOcr {
                // Cleaning is opt-in and limited to whole-image OCR. A force-OCR crop has a different
                // coordinate system and must never be used to rewrite the underlying source image.
                if (crop == null && Prefs.bool(wv.context, "wmTextRemoval", true) &&
                    (src.startsWith("https://", true) || src.startsWith("http://", true))) {
                    try {
                        WebModeTextRemover.cleanAndCache(wv.context, src, bytes, bubbles)
                    } catch (_: OutOfMemoryError) {
                        // Optional cleanup must never turn a successful OCR result into a failed panel.
                    } catch (_: Exception) {
                        // If cleanup is uncertain, retain the original panel and normal overlay.
                    }
                }
                return PanelOcr(texts.joinToString("\n\n"), bubbles)
            }
            if (crop != null) {
                // Decode the requested rectangle. If the region decoder is unsupported or throws,
                // use a bounded downsampled bitmap and crop that instead.
                val left = (crop.x.coerceIn(0f, 0.999f) * w).toInt().coerceIn(0, w - 1)
                val top = (crop.y.coerceIn(0f, 0.999f) * h).toInt().coerceIn(0, h - 1)
                val right = ((crop.x + crop.w).coerceIn(0.001f, 1f) * w).toInt().coerceIn(left + 1, w)
                val bottom = ((crop.y + crop.h).coerceIn(0.001f, 1f) * h).toInt().coerceIn(top + 1, h)
                var bmp: Bitmap? = null
                if (!cropFallback) {
                    @Suppress("DEPRECATION")
                    val region = try { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) } catch (_: Exception) { null }
                    if (region != null) {
                        try { bmp = try { region.decodeRegion(Rect(left, top, right, bottom), opts) } catch (_: RuntimeException) { null } }
                        finally { region.recycle() }
                    }
                }
                var originX = left
                var originY = top
                if (bmp == null) {
                    val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw DecodeException()
                    var cropSharesFull = false
                    try {
                        val scaledLeft = (left.toFloat() / sample).toInt().coerceIn(0, full.width - 1)
                        val scaledTop = (top.toFloat() / sample).toInt().coerceIn(0, full.height - 1)
                        val scaledRight = (right.toFloat() / sample).toInt().coerceIn(scaledLeft + 1, full.width)
                        val scaledBottom = (bottom.toFloat() / sample).toInt().coerceIn(scaledTop + 1, full.height)
                        bmp = Bitmap.createBitmap(full, scaledLeft, scaledTop, scaledRight - scaledLeft, scaledBottom - scaledTop)
                        cropSharesFull = bmp === full
                        originX = scaledLeft * sample
                        originY = scaledTop * sample
                    } catch (_: RuntimeException) {
                        throw DecodeException()
                    } finally { if (!cropSharesFull) full.recycle() }
                }
                val cropped = bmp ?: throw DecodeException()
                try {
                    if (cropped.width < 16 || cropped.height < 16) throw DecodeException()
                    readSlice(cropped, st, originY, sample, w, h, src, texts, bubbles,
                        firstSlice = true, lastSlice = true, x0 = originX, fastMode = false)
                } finally { cropped.recycle() }
                return finishResult()
            }
            if (h <= WebModeLogic.SLICE_H) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw DecodeException()
                try { readSlice(bmp, st, 0, sample, w, h, src, texts, bubbles, true, true, fastMode = fastMode) } finally { bmp.recycle() }
            } else {
                @Suppress("DEPRECATION")
                val region = (try { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) } catch (_: Exception) { null })
                    ?: throw DecodeException()
                try {
                    val sl = WebModeLogic.slices(h)
                    for ((idx, yy) in sl.withIndex()) {
                        val (y0, y1) = yy
                        if (!alive()) break
                        val bmp = region.decodeRegion(Rect(0, y0, w, y1), opts) ?: throw DecodeException()
                        try {
                            if (bmp.width >= 32 && bmp.height >= 32)
                                readSlice(bmp, st, y0, sample, w, h, src, texts, bubbles, idx == 0, idx == sl.size - 1, fastMode = fastMode)
                        } finally { bmp.recycle() }
                    }
                } finally { region.recycle() }
            }
            return finishResult()
        }

        /**
         * OCR one slice and describe every block: its text (one line = one paragraph for the chatbot)
         * and where it sat on the full image. [texts] and [out] stay index-aligned.
         */
        private fun readSlice(
            bmp: Bitmap, st: ScriptState, y0: Int, sample: Int, fullW: Int, fullH: Int,
            src: String, texts: MutableList<String>, out: MutableList<WmBubble>,
            firstSlice: Boolean, lastSlice: Boolean, x0: Int = 0, fastMode: Boolean = false,
            preRecognized: OcrResult? = null
        ) {
            val tSlice = android.os.SystemClock.elapsedRealtime()
            val outBefore = out.size
            var groupMs = 0L
            var ocrMs = 0L
            var lineCount = 0
            try {
            // Audit raw lines BEFORE joining paragraphs. A merged paragraph rectangle hides skipped
            // middle rows from the ink detector. Never discard an edge reading on the assumption that
            // another slice will find it; the fuller overlapping reading can upgrade it below.
            val tOcr = android.os.SystemClock.elapsedRealtime()
            // Our own Bengali overlay can be inside a screen capture: never treat it as source text.
            val dropOwnBengali = Langs.target(wv.context) == "Bengali"
            val blocks = OcrPost.mergeNeighbours(
                (preRecognized ?: recognize(bmp, st, fastMode)).blocks.filter {
                    it.text.isNotBlank() && !(dropOwnBengali && OcrPost.isMostlyBengaliScript(it.text))
                }, bmp)
            ocrMs = android.os.SystemClock.elapsedRealtime() - tOcr
            lineCount = blocks.size
            if (blocks.isEmpty()) return
            val rects = blocks.map { WmBubbleStyle.rectOf(it, bmp.width, bmp.height) }
            val k = sample.toFloat()

            // A flat speech bubble can contain lettering that the first pass missed.
            // Do this check for LONG blocks too: a long partial read is still partial.
            // Keep the recognised text and geometry aligned with every remaining block.
            val refinedText = arrayOfNulls<String>(blocks.size)
            val refinedBox = arrayOfNulls<IntArray>(blocks.size)
            val refinedFont = arrayOfNulls<Float>(blocks.size)
            val consumed = BooleanArray(blocks.size)
            // Cache interiors so multi-line text in the same balloon triggers only one retry.
            val triedInteriors = HashSet<String>()

            // Lines stacked under each other (touching ranges, centred or left aligned) are ONE sentence.
            // The merged text is placed in the union box of its lines.
            fun joinAdjacentLines(i: Int, pool: List<Int>) {
                fun aligned(a: OcrBlock, b: OcrBlock): Boolean = BubbleGrouping.aligned(a, b)
                // Non-transitive grouping + image evidence (outline between two lines) -- see BubbleGrouping.groups.
                val tg = android.os.SystemClock.elapsedRealtime()
                val grp = BubbleGrouping.groupContaining(blocks, i, pool.filter { !consumed[it] }) { x, y ->
                    OcrPost.outlineBetween(bmp, x, y)
                }.toCollection(LinkedHashSet<Int>())
                groupMs += android.os.SystemClock.elapsedRealtime() - tg
                if (grp.size < 2) return
                val ordered = grp.sortedWith(compareBy({ blocks[it].cy }, { blocks[it].cx }))
                val text = BubbleGrouping.joinLines(ordered.map { blocks[it].text.replace(NEWLINES, " ") })
                if (text.isBlank()) return
                var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var bt = Int.MIN_VALUE
                for (j in ordered) {
                    val rc = rects[j]
                    l = Math.min(l, rc[0]); t = Math.min(t, rc[1]); r = Math.max(r, rc[2]); bt = Math.max(bt, rc[3])
                }
                val fonts = ordered.map { blocks[it].fontPx }.filter { it > 0f }.sorted()
                val pad = Math.max(4, ((if (fonts.isEmpty()) 12f else fonts[fonts.size / 2]) * 0.25f).toInt())
                refinedText[i] = text
                refinedBox[i] = intArrayOf(
                    (l - pad).coerceAtLeast(0), (t - pad).coerceAtLeast(0),
                    (r + pad).coerceAtMost(bmp.width), (bt + pad).coerceAtMost(bmp.height)
                )
                refinedFont[i] = if (fonts.isEmpty()) null else fonts[fonts.size / 2]
                for (j in grp) if (j != i) consumed[j] = true
            }

            // ONE closed speech bubble = ONE unit. Every line that sits inside the same outline is joined
            // (before, lines were only joined if a strict "same paragraph" test passed for ALL of them, so a
            // skipped middle line left "IN OTHER" and "THE MOST BEAUTIFUL MAN..." as two separate texts and the
            // chatbot translated fragments). Lettering the recogniser skipped inside the bubble is then
            // read again line by line from the bubble's own pixels.
            for (i in blocks.indices) {
                if (consumed[i]) continue
                val b = blocks[i]
                if (b.vertical || Math.abs(OcrPost.usableTilt(b.angle)) >= 6f) continue

                val ownFont = if (b.fontPx > 0f) b.fontPx else b.h
                // The exhaustive leftover-ink scan is only useful during a thorough chapter pass.
                // In realtime mode, passing a band collector forces bubbleInterior to scan every pixel
                // again and compare it against every OCR box; a tall panel with many bubbles can stall.
                // Without a collector the helper returns immediately after finding the bubble's fill.
                val bands: MutableList<IntArray>? = if (fastMode) null else ArrayList()
                // Realtime screens optimise for latency: flood-filling up to 1.2M pixels around
                // every OCR block repeatedly was the dominant freeze on long/mobile Webtoon panels.
                // Keep this expensive interior/leftover audit only for thorough chapter extraction.
                val inter: IntArray? = if (fastMode) null else try {
                    WmBubbleStyle.bubbleInterior(bmp, rects[i], rects, requireLeftover = false,
                        bandsOut = bands, minBand = Math.max(6, (ownFont * 0.45f).toInt()))
                } catch (_: Exception) { null }
                if (inter == null) {
                    // No closed outline found (tail leaks into the artwork, thick outline ...). Lines that sit
                    // right under each other are still one sentence: never send them as separate fragments.
                    joinAdjacentLines(i, blocks.indices.filter { !consumed[it] })
                    continue
                }
                val regionKey = "${inter[0]}:${inter[1]}:${inter[2]}:${inter[3]}"
                if (!triedInteriors.add(regionKey)) continue

                val inside = blocks.indices.filter { j ->
                    val cxj = blocks[j].cx
                    val cyj = blocks[j].cy
                    cxj >= inter[0] && cxj < inter[2] && cyj >= inter[1] && cyj < inter[3]
                }
                if (i !in inside) continue
                val members = inside.filter { !consumed[it] }
                if (i !in members) continue
                if (members.size < 2 && bands.isNullOrEmpty()) continue   // a lone, fully read line needs nothing

                // The closed speech-bubble outline is stronger evidence than line spacing/alignment:
                // every OCR row whose centre lies inside this same interior belongs to ONE bubble.
                // Splitting these rows back into sentence-like groups creates several Bengali overlays
                // inside one Korean balloon (the exact failure seen with tall/multiline webtoon balloons).
                // Keep the outline as the grouping boundary and translate its complete contents together.
                val ordered = members.sortedWith(compareBy({ blocks[it].cy }, { blocks[it].cx }))
                val fonts = ordered.map { blocks[it].fontPx }.filter { it > 0f }.sorted()
                val medFont = if (fonts.isEmpty()) ownFont else fonts[fonts.size / 2]
                val lines = ordered.mapTo(ArrayList<BubbleLine>()) {
                    BubbleLine(blocks[it].cy, blocks[it].text.replace(NEWLINES, " ").trim())
                }
                // Recover missed bubble rows only during a thorough scan; doing up to three extra
                // OCR inferences for every visible bubble made real-time reading feel frozen.
                if (bands?.isNotEmpty() == true && !fastMode) recoverBubbleLines(bmp, st, inter, bands, lines, medFont)
                val joinedText = lines.sortedBy { it.cy }.joinToString(" ") { it.text }
                    .replace(Regex("\\s+"), " ").trim()
                if (joinedText.isBlank()) continue
                refinedText[i] = joinedText
                refinedBox[i] = inter
                refinedFont[i] = medFont
                for (j in members) if (j != i) consumed[j] = true
            }

            for ((i, b) in blocks.withIndex()) {
                if (consumed[i]) continue
                val tilt = OcrPost.usableTilt(b.angle)          // never upside down, never sideways
                val rotated = Math.abs(tilt) >= 6f
                val others = rects.filterIndexed { j, _ -> j != i }
                val s = WmBubbleStyle.analyse(bmp, rects[i], others, expand = !rotated)
                var cx: Float; var cy: Float; var bw: Float; var bh: Float
                // Wipe the old lettering COMPLETELY: OCR boxes are tight, but outlines, glow, shadows and
                // descenders reach past them. Pad by a share of the line height (more on artwork).
                val lineH = (if (b.fontPx > 0f) b.fontPx else minOf(b.w, b.h)).coerceAtLeast(8f)
                val pad = lineH * (if (s.soft) 0.34f else 0.2f) + 3f
                if (rotated) {
                    cx = b.cx; cy = b.cy; bw = b.w * 1.1f + pad * 2f; bh = b.h * 1.1f + pad * 2f
                } else {
                    // flat bubbles already grew to their interior (kept inside the outline); only pad the text-sized ones
                    val grown = (s.r - s.l) > (rects[i][2] - rects[i][0]) + 8 || (s.b - s.t) > (rects[i][3] - rects[i][1]) + 8
                    val p = if (grown) 2f else pad
                    cx = (s.l + s.r) / 2f; cy = (s.t + s.b) / 2f; bw = (s.r - s.l) + p * 2f; bh = (s.b - s.t) + p * 2f
                }
                val text = (refinedText[i] ?: b.text).replace(NEWLINES, " ").trim()
                val rb = refinedBox[i]
                if (rb != null) {
                    // Refined text bounds, kept inside the bubble outline.
                    cx = (rb[0] + rb[2]) / 2f; cy = (rb[1] + rb[3]) / 2f
                    bw = (rb[2] - rb[0]) - 4f; bh = (rb[3] - rb[1]) - 4f
                }
                val nb = WmBubble(
                    src = src,
                    cx = (x0 + cx * k) / fullW, cy = (y0 + cy * k) / fullH,
                    w = bw * k / fullW, h = bh * k / fullH,
                    angle = if (rotated) tilt else 0f,
                    font = (refinedFont[i] ?: b.fontPx) * k / fullW,
                    bg = s.bg, fg = s.fg, fg2 = s.fg2, soft = s.soft, stroke = s.stroke,
                    sourceText = text
                )
                // Same text seen by two overlapping slices: keep the bigger box once.
                var dup = -1
                for (j in out.indices) {
                    if (out[j].src == src && sameSpot(out[j], nb)) { dup = j; break }
                }
                if (dup >= 0) {
                    if (sameOcrText(texts[dup], text)) {
                        val priorKey = WebModeLayout.normalizeOcrText(texts[dup])
                        val freshKey = WebModeLayout.normalizeOcrText(text)
                        // Text completeness wins over box area. A full reading can have a tighter box.
                        if (freshKey.length > priorKey.length ||
                            (freshKey == priorKey && nb.w * nb.h > out[dup].w * out[dup].h * 1.15f)) {
                            out[dup] = nb; texts[dup] = text
                        }
                    } else {
                        // DIFFERENT text on the same spot: two lines of one speech bubble that OCR did not
                        // join (both boxes grew to the same bubble interior). Dropping one lost a whole
                        // line (e.g. the first line stayed untranslated). Keep both, top to bottom: blocks
                        // arrive in reading order, so the new one is the lower line.
                        val merged = OcrLineFusion.joinSliceText(texts[dup], text)
                        val prior = out[dup]
                        val l = minOf(prior.cx - prior.w / 2, nb.cx - nb.w / 2)
                        val r = maxOf(prior.cx + prior.w / 2, nb.cx + nb.w / 2)
                        val t = minOf(prior.cy - prior.h / 2, nb.cy - nb.h / 2)
                        val bt = maxOf(prior.cy + prior.h / 2, nb.cy + nb.h / 2)
                        // A union covers the recovered lower rows too; choosing just the bigger box
                        // can leave the other slice's tail visibly untranslated.
                        out[dup] = prior.copy(sourceText = merged, cx = (l + r) / 2, cy = (t + bt) / 2,
                            w = r - l, h = bt - t)
                        texts[dup] = merged
                    }
                    continue
                }
                texts.add(text)
                out.add(nb)
            }
            } finally {
                // Structured diagnostics: counts and timings only, never recognised text.
                Log.i(TAG, "slice job=$id mode=${if (fastMode) "fast" else "full"} ocrMs=$ocrMs groupingMs=$groupMs " +
                    "sliceTotalMs=${android.os.SystemClock.elapsedRealtime() - tSlice} lines=$lineCount " +
                    "groups=${out.size - outBefore} image=${bmp.width}x${bmp.height}")
            }
        }

        /**
         * Lettering inside a closed bubble that the recogniser skipped ([bands] = rows of ink no block explains).
         * Each band is cut out of the picture as a thin full-width strip, enlarged and read on its own: a single
         * line on a clean bubble is the easiest case for the recogniser. A longer reading of a row that was
         * only partly read replaces that fragment; anything else is added as a new line of the bubble.
         */
        private fun recoverBubbleLines(
            bmp: Bitmap, st: ScriptState, inter: IntArray, bands: List<IntArray>,
            lines: MutableList<BubbleLine>, font: Float
        ) {
            fun letters(t: String) = t.count { it.isLetterOrDigit() }
            var budget = 3
            for (band in bands) {
                if (budget <= 0 || !alive()) break
                budget--
                val pad = Math.max(3, ((band[1] - band[0]) * 0.2f).toInt())
                val x0 = inter[0].coerceIn(0, bmp.width - 1)
                val x1 = inter[2].coerceIn(x0 + 8, bmp.width)
                val y0 = (band[0] - pad).coerceIn(0, bmp.height - 1)
                val y1 = (band[1] + pad).coerceIn(y0 + 8, bmp.height)
                var strip: Bitmap? = null
                var up: Bitmap? = null
                try {
                    strip = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
                    val f = Math.max(1f, Math.min(Math.min(4f, Math.max(2f, 96f / strip.height)), 2400f / strip.width))
                    up = Bitmap.createScaledBitmap(strip, (strip.width * f).toInt(), (strip.height * f).toInt(), true)
                    val got = engine.recognizeFast(up, st.current).blocks
                        .filterNot { OcrPost.isNoise(it) }
                        .sortedWith(compareBy({ it.cy }, { it.cx }))
                    for (g in got) {
                        val text = g.text.replace(NEWLINES, " ").trim()
                        if (letters(text) < 1) continue
                        val gcy = y0 + g.cy / f
                        val same = lines.firstOrNull { Math.abs(it.cy - gcy) <= Math.max(font, 8f) * 0.7f }
                        if (same != null) {
                            // same row: keep the fuller reading, never duplicate it
                            if (letters(text) > letters(same.text) && OcrLineFusion.agreement(same.text, text) >= 0.8f)
                                same.text = text
                            continue
                        }
                        lines.add(BubbleLine(gcy, text))
                    }
                } catch (_: Exception) {
                } catch (_: OutOfMemoryError) {
                } finally {
                    if (up != null && up !== strip && !up.isRecycled) up.recycle()
                    if (strip != null && strip !== bmp && !strip.isRecycled) strip.recycle()
                }
            }
        }

        /** True when two OCR readings are (nearly) the same text; false when they are different lines. */
        private fun sameOcrText(a: String, b: String): Boolean {
            val x = WebModeLayout.normalizeOcrText(a)
            val y = WebModeLayout.normalizeOcrText(b)
            if (x.isEmpty() || y.isEmpty() || x == y || x.contains(y) || y.contains(x)) return true
            // A 60% bigram match also matches DIFFERENT neighbouring lines and used to drop them.
            // Only near-identical long readings qualify as duplicates.
            return minOf(x.length, y.length) >= 12 &&
                minOf(x.length, y.length).toFloat() / maxOf(x.length, y.length) >= 0.90f &&
                OcrLineFusion.agreement(x, y) >= 0.94f
        }

        private fun likelySameBubbleLine(a: OcrBlock, b: OcrBlock, maxGapFonts: Float = 2.0f, loose: Boolean = false): Boolean =
            BubbleGrouping.likelySameBubbleLine(a, b, maxGapFonts, loose)

        private fun sameSpot(a: WmBubble, b: WmBubble): Boolean {
            val x = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
            val y = Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2)
            if (x <= 0f || y <= 0f) return false
            val small = Math.min(a.w * a.h, b.w * b.h)
            return small > 0f && x * y / small > 0.5f
        }

        /**
         * Normal OCR, then a second look at any line-shaped ink the first pass left unread
         * (see [OcrRescan]). Any failure keeps the normal result untouched.
         */
        private fun recognize(bmp: Bitmap, st: ScriptState, fastMode: Boolean = false): OcrResult {
            val t0 = SystemClock.elapsedRealtime()
            return try {
                recognizeInner(bmp, st, fastMode).also { r ->
                    val letters = r.blocks.sumOf { b -> b.text.count { ch -> ch.isLetterOrDigit() } }
                    OcrDiag.log("OCR", "script=${st.current.name} fast=$fastMode img=${bmp.width}x${bmp.height} ms=${SystemClock.elapsedRealtime() - t0} " +
                        "engine=${OcrEngineStatus.label.ifBlank { Prefs.get(wv.context, "ocrEngine", "mlkit") }} blocks=${r.blocks.size} letters=$letters first=[${OcrDiag.snip(r.blocks.firstOrNull()?.text)}]")
                }
            } catch (e: Throwable) {
                OcrDiag.log("OCR_ERR", "script=${st.current.name} ms=${SystemClock.elapsedRealtime() - t0} ${e.javaClass.simpleName}: ${e.message?.take(140)}")
                throw e
            }
        }

        private fun recognizeInner(bmp: Bitmap, st: ScriptState, fastMode: Boolean = false): OcrResult {
            val routing = engine as? RoutingOcrEngine
            routing?.configure(wv.context)
            val lensSelected = routing?.usesRemoteTextDetection() == true
            val core = recognizeCore(bmp, st, fastMode)
            // Lens is a single-pass remote OCR engine, including when it returns no text. Never send
            // the same image through a second audit request; it only adds another upload/cache encode.
            if (lensSelected) {
                // Selective, speed-neutral completeness audit (see OcrRescan.augmentRemote): free when nothing is
                // uncovered, otherwise only the suspicious crops are re-read, in parallel and under a deadline.
                if (!Prefs.bool(wv.context, "lensAudit", true) || core.blocks.isEmpty() && fastMode) return core
                return try { OcrRescan.augmentRemote(engine, bmp, core, st.current, if (fastMode) 1200L else 2500L) }
                catch (_: Exception) { core } catch (_: OutOfMemoryError) { core }
            }
            // Real-time/visible reading must return promptly. The optional uncovered-ink audit can
            // launch several additional recognizer calls per slice; the thorough chapter scan keeps it.
            if (fastMode || !Prefs.bool(wv.context, "ocrRescan", true)) return core
            // Lens already read the whole panel; skip the extra network-backed rescan when it returned text.
            if (routing?.lastRouteUsedLens() == true) return core
            // The Japanese manga engine has its own detector; do not run it once per crop.
            if (st.current == OcrScript.JAPANESE &&
                com.ashraf.novelstudio.japanesemanga.JapaneseMangaRuntime.isAvailable()) return core
            return try {
                OcrRescan.augment(wv.context, engine, bmp, core, st.current, fastMode)
            } catch (_: Exception) {
                core
            } catch (_: OutOfMemoryError) {
                core
            }
        }

        private fun recognizeCore(bmp: Bitmap, st: ScriptState, fastMode: Boolean = false): OcrResult {
            // Google Lens auto-detects the text language and returns the layout/geometry in one response.
            // Do exactly one remote OCR pass. The generic sparse-result retry and language-fallback logic
            // below is for local recognisers; here it merely re-encodes/re-uploads the same bitmap.
            if ((engine as? RoutingOcrEngine)?.usesRemoteTextDetection() == true) {
                val lensResult = if (fastMode) engine.recognizeFast(bmp, st.current) else engine.recognize(bmp, st.current)
                if (lensResult.text.count { it.isLetterOrDigit() } >= 2) st.locked = true
                return lensResult
            }
            // Real-time (fastMode) uses the light local path; thorough chapter scans may use more views.
            val first = if (fastMode) engine.recognizeFast(bmp, st.current)
            else if (st.current == OcrScript.LATIN) engine.recognize(bmp, st.current)
            else if (scanFastFirst()) {
                // Chapter scan: one quick pass first. Only a panel that gave (almost) no text gets the slow
                // multi-pass engine; bubbles that still hide ink are re-read one by one afterwards.
                val quick = engine.recognizeFast(bmp, st.current)
                if (quick.text.count { it.isLetterOrDigit() } >= 6) quick else engine.recognize(bmp, st.current)
            } else engine.recognize(bmp, st.current)
            // A good OCR result stays on the fast path. Retry only if the first pass found no text,
            // or returned just one/two tiny fragments where a full panel is likely still unread.
            // Fast/realtime must not start a second heavy local pass just because the first found little text.
            // RoutingOcrEngine already falls back to ML Kit when a selected local model fails or returns no useful text.
            val result = if (!fastMode && needsOcrRetry(bmp, first)) {
                val retry = try { engine.recognizeFallback(bmp, st.current) } catch (_: Exception) {
                    OcrResult(emptyList())
                }
                combineOcrResults(first, retry)
            } else first

            val resultLetters = result.text.count { it.isLetterOrDigit() }
            // Real-time is latency-sensitive. A short but readable result should not trigger three
            // extra language models on every slice; this caused multi-second pauses on English
            // scanlation sites with short dialogue and sound effects.
            if (resultLetters >= 2) {
                if (fastMode && resultLetters < 18) {
                    val alternate = tryRealtimeFallback(
                        bmp, st, minimumLetters = 2, baselineLetters = resultLetters
                    )
                    if (alternate != null) return alternate
                }
                st.locked = true
                return result
            }
            if (fastMode) {
                // Try other languages across the scan, but cap the total number of extra recognizer calls.
                // Each failed language is remembered so a bad first guess cannot block other scripts forever.
                val alternate = tryRealtimeFallback(bmp, st, minimumLetters = 2)
                if (alternate != null) return alternate
                return result
            }
            if (!st.locked && st.fallbackBudget > 0) {
                st.fallbackBudget--
                for (script in WebModeLogic.fallbackScripts(st.current)) {
                    val alternate = try { engine.recognize(bmp, script) }
                        catch (_: Exception) { OcrResult(emptyList()) }
                    if (alternate.text.count { it.isLetterOrDigit() } >= (if (st.current == OcrScript.LATIN) 10 else 2)) {
                        st.current = script
                        st.locked = true
                        return alternate
                    }
                }
            }
            return result
        }

        private fun tryRealtimeFallback(
            bmp: Bitmap,
            st: ScriptState,
            minimumLetters: Int,
            baselineLetters: Int = 0
        ): OcrResult? {
            if (st.fastFallbackBudget <= 0) return null
            val script = WebModeLogic.realtimeFallbackScripts(st.current)
                .firstOrNull { it !in st.fastFallbackScriptsTried } ?: return null
            st.fastFallbackScriptsTried.add(script)
            st.fastFallbackBudget--

            val alternate = try { engine.recognizeFast(bmp, script) }
                catch (_: Exception) { OcrResult(emptyList()) }
                catch (_: OutOfMemoryError) { OcrResult(emptyList()) }
            val letters = alternate.text.count { it.isLetterOrDigit() }
            val required = if (baselineLetters > 0) maxOf(minimumLetters, baselineLetters + 5) else minimumLetters
            if (letters < required) {
                Log.i(TAG, "realtime fallback script=${script.name} letters=$letters accepted=false budget=${st.fastFallbackBudget}")
                return null
            }
            st.current = script
            st.locked = true
            Log.i(TAG, "realtime fallback script=${script.name} letters=$letters accepted=true")
            return alternate
        }

        private fun needsOcrRetry(bmp: Bitmap, result: OcrResult): Boolean {
            if (result.blocks.isEmpty()) return true
            val letters = result.text.count { it.isLetterOrDigit() }
            if (letters >= 24 || result.blocks.size > 2) return false
            val covered = result.blocks.sumOf { (it.w * it.h).toDouble().coerceAtLeast(0.0) }
            val coverage = covered / (bmp.width.toDouble() * bmp.height.toDouble()).coerceAtLeast(1.0)
            return coverage < 0.12
        }

        private fun combineOcrResults(first: OcrResult, retry: OcrResult): OcrResult {
            if (retry.blocks.isEmpty()) return first
            val all = first.blocks.toMutableList()
            OcrLineFusion.addAll(all, retry.blocks.filterNot { OcrPost.isNoise(it) })
            return OcrResult(all)
        }
    }

    private class ScriptState(var current: OcrScript) {
        var locked = false
        var fallbackBudget = 3
        val fastFallbackScriptsTried = HashSet<OcrScript>()
        var fastFallbackBudget = 3
    }
    private class BubbleLine(var cy: Float, var text: String)
    private class DecodeException : Exception()
    private class ForceAcc { var top = 0; val texts = ArrayList<String>(); val bubbles = ArrayList<WmBubble>() }
    private class PanelOcr(val text: String, val bubbles: List<WmBubble>)
    private class LensPanelOcrAttempt(val panel: PanelOcr?, val error: Throwable?)
    private class PageMeta(val url: String, val lang: String, val title: String, val novel: String, val next: String?, val prev: String?)

    companion object {
        /** Last live-discovery summary (img/iframe/canvas counts); shown when no panels were found. */
        @Volatile var lastDiscoveryDebug: String = ""
        private const val TAG = "NsWebMode"
        private val NEWLINES = Regex("\\s*\\n\\s*")
        private const val MAX_PANELS = 400
        private const val MAX_TICKS = 400
        private const val MAX_BYTES = 25 * 1024 * 1024
        private const val FETCH_THREADS = 4
        private const val FETCH_AHEAD = 4
        private const val EARLY_ABORT_FAILS = 4
        private const val DISCOVERY_MAX_MS = 120_000L
        private const val EMPTY_GRACE_MS = 8_000L
        // If a live Lens panel's URL is not immediately readable, stop waiting and OCR the pixels already onscreen.
        private const val LENS_IMAGE_FETCH_WAIT_MS = 900L
        private const val VISIBLE_FETCH_WAIT_MS = 7_000L
        private const val SCREEN_LENS_DEADLINE_MS = 7_000L
        private const val JOB_MAX_MS = 12 * 60_000L
        private const val VALIDITY_CHECK_INTERVAL_MS = 250L
        private const val MIN_W = 120
        private const val MIN_H = 60
        private const val SCREEN_OCR_SOURCE = "__ns_screen_ocr__"

        private fun hostOf(u: String): String =
            u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':')

        private fun md5(b: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) } + ":" + b.size

        private fun parseJson(raw: String?): JSONObject? = try {
            if (raw == null || raw == "null") null
            else JSONObject(JSONArray("[$raw]").getString(0))
        } catch (_: Exception) { null }

        private fun wrap(body: String) = "(function(){try{$body}catch(e){return JSON.stringify({err:String(e)});}})()"

        // ---------------- JavaScript run inside the real page (no dollar signs: this is a Kotlin string)
        private const val LIB = """
var BAD=/avatar|logo|sprite|emoji|profile|badge|pixel|tracking|advert|\/ads?|[_\-.]ads?[_\-.]|spinner/i;
var PH=/placeholder|blank\.|spacer|loading|lazy[-_.]?(img|load)|1x1|transparen|pixel\.gif/i;
var LAZY=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-echo','data-actualsrc','data-orig-src','data-pc-src','data-original-src','data-src-original','data-image','data-img','data-bg','data-background','data-background-image','data-ks-lazyload','data-cfsrc','data-defer-src'];
var de=document.scrollingElement||document.documentElement;
function absUrl(s){try{return s?new URL(String(s).trim(),document.baseURI).href:'';}catch(e){return '';}}
function goodUrl(s){s=String(s||'').trim();return !!s&&!/^data:image\/(gif|svg)/i.test(s)&&!PH.test(s)&&!/^about:blank/i.test(s);}
function pickSrcset(ss){
  if(!ss)return '';
  var best='',bestScore=-1,parts=String(ss).split(',');
  for(var i=0;i<parts.length;i++){
    var p=parts[i].trim().split(/\s+/),u=p[0]||'',d=p[1]||'1x',n=parseFloat(d)||1;
    var score=/w$/i.test(d)?n:n*10000;
    if(goodUrl(u)&&score>bestScore){best=u;bestScore=score;}
  }
  return best;
}
function imageSrc(im){
  var primary=im.currentSrc||im.getAttribute('src')||im.src||'';
  var lazy='';
  for(var i=0;i<LAZY.length;i++){var v=im.getAttribute&&im.getAttribute(LAZY[i]);if(goodUrl(v)){lazy=v;break;}}
  var set=pickSrcset((im.getAttribute&&im.getAttribute('data-srcset'))||(im.getAttribute&&im.getAttribute('srcset'))||'');
  if(lazy&&(!goodUrl(primary)||PH.test(primary)||((im.naturalWidth||0)>0&&(im.naturalWidth||0)<8)))primary=lazy;
  if(!goodUrl(primary))primary=set||lazy;
  return absUrl(primary);
}
function allNodes(limit){
  var out=[],roots=[document],seenRoots=[];
  while(roots.length&&out.length<limit){
    var root=roots.shift();if(!root||seenRoots.indexOf(root)>=0)continue;seenRoots.push(root);
    var list=root.querySelectorAll?root.querySelectorAll('*'):[];
    for(var i=0;i<list.length&&out.length<limit;i++){var el=list[i];out.push(el);if(el.shadowRoot)roots.push(el.shadowRoot);}
  }
  return out;
}
function scroller(){
  var vw=window.innerWidth||de.clientWidth||360,vh=window.innerHeight||800;
  var minW=Math.min(vw*0.48,300),minH=Math.min(vh*0.24,150);
  function dimensions(e){
    if(!e||!e.isConnected)return null;
    var d=(e.scrollHeight||0)-(e.clientHeight||0);
    if(d<=200)return null;
    var o='';try{o=getComputedStyle(e).overflowY||'';}catch(ignore){}
    if(o!=='auto'&&o!=='scroll'&&o!=='overlay')return null;
    var r=e.getBoundingClientRect();
    if(r.width<minW||r.height<minH||r.right<vw*0.12||r.left>vw*0.88)return null;
    return {d:d,r:r};
  }
  // Discard the old "largest scroll pane wins" cache once; it could point at a sidebar/ad.
  if(window.__nsWmScrollerVersion!==2){
    window.__nsWmScroller=null;
    window.__nsWmScrollerVersion=2;
  }
  var cached=window.__nsWmScroller;
  if(cached){
    if(dimensions(cached))return cached;
    window.__nsWmScroller=null;
  }
  // Count panel images for all ancestors in one pass. Doing a descendant query for every
  // candidate container made discovery quadratic and could freeze long comic pages.
  var countByEl=new Map(),imgs=document.images,imgLimit=Math.min(imgs.length,1200);
  for(var i=0;i<imgLimit;i++){
    var im=imgs[i],src=imageSrc(im);if(!src)continue;
    var imMeta=(im.className&&im.className.baseVal!==undefined?im.className.baseVal:im.className||'')+' '+(im.id||'')+' '+(im.alt||'');
    if(BAD.test(imMeta))continue;
    var ir=im.getBoundingClientRect(),nw=im.naturalWidth||0,nh=im.naturalHeight||0;
    if(!((ir.width>=100&&ir.height>=60)||(nw>=300&&nh>=120)))continue;
    if(im.closest&&im.closest('header,footer,nav,aside,[class*=comment],[class*=recommend],[class*=related],[id*=comment]')&&!(ir.width>=360&&ir.height>=240))continue;
    var node=im.parentElement,depth=0;
    while(node&&node!==document.documentElement&&depth<40){
      countByEl.set(node,(countByEl.get(node)||0)+1);
      node=node.parentElement;depth++;
    }
  }
  function panelCount(e){return countByEl.get(e)||0;}
  var els=document.querySelectorAll('div,main,section,article');
  var best=null,bestScore=-Infinity,fallback=null,fallbackScore=-Infinity;
  var limit=Math.min(els.length,1600);
  for(var j=0;j<limit;j++){
    var e=els[j],info=dimensions(e);if(!info)continue;
    var meta=(e.className&&e.className.baseVal!==undefined?e.className.baseVal:e.className||'')+' '+(e.id||'')+' '+(e.getAttribute('aria-label')||'');
    if(/(^|\s)(sidebar|side-bar|recommend|related|comment|advert|ad-container|banner)(\s|$)/i.test(meta))continue;
    var semantic=/comic|chapter|reader|viewer|episode|webtoon|toon|panel|content|scroll/i.test(meta);
    var count=panelCount(e);
    var widthScore=Math.min(info.r.width/vw,1)*10000;
    var heightScore=Math.min(info.d,100000)/100;
    var score=count*1000000+(semantic?50000:0)+widthScore+heightScore;
    if(score>fallbackScore){fallback=e;fallbackScore=score;}
    if(count>0||semantic){
      if(score>bestScore){best=e;bestScore=score;}
    }
  }
  // Image-heavy readers win; CSS-background/canvas readers use a wide semantic reader container.
  var chosen=best||fallback;
  if(chosen)window.__nsWmScroller=chosen;
  return chosen;
}
function cands(sc, includeBackgrounds, forceCanvas, canvasPlaceholders){
  var out=[],seen={},imgs=document.images,wy=window.pageYOffset||de.scrollTop||0,sr=sc?sc.getBoundingClientRect():null;
  // Big on-screen canvases = the page the user is really reading (Shonen Jump+, Comic-Walker...). An <img> that sits
  // under/inside such a canvas (promo banners, link-slot ads, hidden preloads) is NOT what the user sees.
  var pageCv=[];
  try{
    var pcs=document.querySelectorAll('canvas'),pvh=window.innerHeight||800;
    for(var pi=0;pi<pcs.length&&pi<16;pi++){
      var pr0=pcs[pi].getBoundingClientRect();
      if(pr0.width<300||pr0.height<300||pr0.bottom<=0||pr0.top>=pvh)continue;
      pageCv.push({l:pr0.left,t:pr0.top,r:pr0.right,b:pr0.bottom,a:pr0.width*pr0.height,el:pcs[pi]});
    }
  }catch(ig0){}
  function hiddenOrCovered(im,r){
    try{
      var cs0=getComputedStyle(im);
      if(cs0&&(cs0.visibility==='hidden'||cs0.display==='none'||parseFloat(cs0.opacity||'1')<0.05))return true;
    }catch(ig1){}
    var ia=r.width*r.height;
    if(ia>0)for(var k=0;k<pageCv.length;k++){
      var c0=pageCv[k];
      var ox=Math.min(r.right,c0.r)-Math.max(r.left,c0.l),oy=Math.min(r.bottom,c0.b)-Math.max(r.top,c0.t);
      if(ox<=0||oy<=0)continue;
      var ov=ox*oy;
      if(ov>=0.7*Math.min(ia,c0.a)&&c0.a<=2*ia&&c0.a>=0.5*ia&&!(c0.el.contains&&c0.el.contains(im)))return true;
    }
    try{
      var cx0=Math.round(r.left+r.width/2),cy0=Math.round(r.top+r.height/2);
      if(cx0>=0&&cy0>=0&&cx0<(window.innerWidth||0)&&cy0<(window.innerHeight||0)){
        var tp=document.elementFromPoint(cx0,cy0);
        if(tp&&String(tp.tagName).toLowerCase()==='canvas'&&!(im.contains&&im.contains(tp)))return true;
      }
    }catch(ig2){}
    return false;
  }
  for(var i=0;i<imgs.length;i++){
    var im=imgs[i],src=imageSrc(im);
    if(!src)continue;
    if(/\/link-slot-|\/(?:banner|banners|promotion|promo)\//i.test(src))continue;
    var r=im.getBoundingClientRect(),nw=im.naturalWidth||0,nh=im.naturalHeight||0;
    if(pageCv.length&&hiddenOrCovered(im,r))continue;
    if(!((r.width>=100&&r.height>=60)||(nw>=300&&nh>=120))){
      // A lazy image that has not loaded yet is often collapsed to 0x0: use its wrapper's box instead,
      // otherwise the panel is not found at all until the page is reloaded.
      var pe=im.parentElement,pr=pe?pe.getBoundingClientRect():null;
      if(!(pr&&pr.width>=100&&pr.height>=60&&(!im.complete||nw===0)))continue;
      r=pr;
    }
    var meta=(im.className&&im.className.baseVal!==undefined?im.className.baseVal:im.className||'')+' '+(im.id||'')+' '+(im.alt||'');
    if(BAD.test(meta))continue;
    // Small thumbnails inside comment/related blocks are not panels, but a big image is one even if the
    // reader sits inside such a container.
    if(im.closest&&im.closest('header,footer,nav,aside,[class*=comment],[class*=recommend],[class*=related],[id*=comment]')&&!(r.width>=360&&r.height>=240))continue;
    if(seen[src])continue;seen[src]=1;
    var inScroller=!!(sc&&sc.contains(im));var top=inScroller?(r.top-sr.top+sc.scrollTop):(r.top+wy);
    out.push({s:src,t:Math.round(top),l:Math.round(r.left),w:Math.round(r.width),h:Math.round(r.height),d:(im.complete&&nw>0)?1:0,fw:r.width,fh:r.height,vl:r.left,vt:r.top,vb:r.bottom});
  }
  // Readers inside a same-origin <iframe>.
  var frs=document.querySelectorAll('iframe');
  for(var fi=0;fi<frs.length&&fi<8;fi++){
    var fd=null,fb=frs[fi].getBoundingClientRect();
    try{fd=frs[fi].contentDocument;}catch(ignore){fd=null;}
    if(!fd||!fd.images)continue;
    for(var fk=0;fk<fd.images.length;fk++){
      var fim=fd.images[fk],fsrc=fim.currentSrc||fim.src||'';
      if(!goodUrl(fsrc)||seen[fsrc])continue;
      var fr=fim.getBoundingClientRect(),fnw=fim.naturalWidth||0,fnh=fim.naturalHeight||0;
      if(!((fr.width>=100&&fr.height>=60)||(fnw>=300&&fnh>=120)))continue;
      seen[fsrc]=1;
      var ft=fb.top+fr.top,fl=fb.left+fr.left;
      var fit=sc&&sc.contains(frs[fi])?ft-sr.top+sc.scrollTop:ft+wy;
      out.push({s:fsrc,t:Math.round(fit),l:Math.round(fl),w:Math.round(fr.width),h:Math.round(fr.height),d:(fim.complete&&fnw>0)?1:0,fw:fr.width,fh:fr.height,vl:fl,vt:ft,vb:ft+fr.height});
    }
  }
  // Some pages use SVG <image> or videos with posters.
  var extra=document.querySelectorAll('svg image,video[poster]');
  for(var ei=0;ei<extra.length;ei++){
    var xe=extra[ei],xr=xe.getBoundingClientRect(),xs='';
    if(xr.width<100||xr.height<60)continue;
    if(String(xe.tagName).toLowerCase()==='video')xs=xe.getAttribute('poster')||'';
    else xs=(xe.href&&xe.href.baseVal)||xe.getAttribute('href')||xe.getAttribute('xlink:href')||'';
    xs=absUrl(xs);if(!xs||seen[xs])continue;
    var xmeta=(xe.className&&xe.className.baseVal!==undefined?xe.className.baseVal:xe.className||'')+' '+(xe.id||'');
    if(BAD.test(xmeta))continue;
    seen[xs]=1;
    var svgInside=!!(sc&&sc.contains(xe));var xt=svgInside?(xr.top-sr.top+sc.scrollTop):(xr.top+wy);
    out.push({s:xs,t:Math.round(xt),l:Math.round(xr.left),w:Math.round(xr.width),h:Math.round(xr.height),d:1,fw:xr.width,fh:xr.height,vl:xr.left,vt:xr.top,vb:xr.bottom});
  }
  // Some readers mix normal IMG panels with CSS-background panels. Rate-limit the expensive
  // style sweep for callers that do not specify a mode; full-chapter final collection opts in.
  var doBackgrounds=includeBackgrounds;
  if(doBackgrounds===undefined){
    var bgNow=Date.now();
    doBackgrounds=bgNow-(window.__nsWmLastBgProbeAt||0)>=1200;
    if(doBackgrounds)window.__nsWmLastBgProbeAt=bgNow;
  }else if(doBackgrounds){
    window.__nsWmLastBgProbeAt=Date.now();
  }
  // Canvas-drawn readers (Shonen Jump+, Comic-Walker style viewers): the page never exposes an <img> for the
  // real pages, so export the canvas pixels themselves. A tainted (cross-origin) canvas throws on export: that is
  // recorded in window.__nsWmCanvasErr and the screenshot fallback covers it.
  if(doBackgrounds===true||forceCanvas===true){
    var cvs=document.querySelectorAll('canvas'),vhh=window.innerHeight||800,cvDone=0;
    for(var ci=0;ci<cvs.length&&ci<16&&cvDone<4;ci++){
      var cv=cvs[ci],cr=cv.getBoundingClientRect();
      if(cr.width<150||cr.height<150||cv.width<150||cv.height<150)continue;
      if(cr.bottom<=0||cr.top>=vhh)continue;
      var cs=null;try{cs=getComputedStyle(cv);}catch(ig){}
      if(cs&&(cs.visibility==='hidden'||cs.display==='none'||parseFloat(cs.opacity||'1')<0.05))continue;
      var cmeta=(cv.className&&cv.className.baseVal!==undefined?cv.className.baseVal:cv.className||'')+' '+(cv.id||'');
      if(BAD.test(cmeta))continue;
      var cu='';
      try{
        var ck=Math.min(1,1600/Math.max(cv.width,cv.height)),tw=Math.max(1,Math.round(cv.width*ck)),th=Math.max(1,Math.round(cv.height*ck));
        var tc=document.createElement('canvas');tc.width=tw;tc.height=th;
        var tx=tc.getContext('2d');tx.fillStyle='#fff';tx.fillRect(0,0,tw,th);tx.drawImage(cv,0,0,tw,th);
        cu=tc.toDataURL('image/jpeg',0.85);
        window.__nsWmCanvasErr='';
      }catch(ex){
        window.__nsWmCanvasErr=String((ex&&ex.name)||ex);cu='';
        // Tainted canvas: pixels cannot be exported, but the screenshot can still be cropped to this canvas' box.
        // Only the screenshot path (canvasPlaceholders) uses this; discovery by URL must not see fake sources.
        if(canvasPlaceholders===true&&cvDone<4){
          var pkey='ph:'+Math.round(cr.left)+','+Math.round(cr.top)+','+Math.round(cr.width)+'x'+Math.round(cr.height);
          if(!seen[pkey]){
            seen[pkey]=1;cvDone++;
            var pin=!!(sc&&sc.contains(cv));var ptop=pin?(cr.top-sr.top+sc.scrollTop):(cr.top+wy);
            out.push({s:'ns-canvas:'+Math.round(cr.left)+','+Math.round(ptop)+','+Math.round(cr.width)+'x'+Math.round(cr.height),t:Math.round(ptop),l:Math.round(cr.left),w:Math.round(cr.width),h:Math.round(cr.height),d:1,fw:cr.width,fh:cr.height,vl:cr.left,vt:cr.top,vb:cr.bottom,cv:2});
          }
        }
      }
      if(!cu||cu.length<2500)continue;
      var ckey=cu.length+':'+cu.slice(-48)+cu.slice(2000,2048);
      if(seen[ckey])continue;seen[ckey]=1;cvDone++;
      var cin=!!(sc&&sc.contains(cv));var ctop=cin?(cr.top-sr.top+sc.scrollTop):(cr.top+wy);
      out.push({s:cu,t:Math.round(ctop),l:Math.round(cr.left),w:Math.round(cr.width),h:Math.round(cr.height),d:1,fw:cr.width,fh:cr.height,vl:cr.left,vt:cr.top,vb:cr.bottom,cv:1});
    }
  }
  if(doBackgrounds===true){
    // Keep the CSS/canvas fallback bounded; the caller rate-limits this DOM/style sweep.
    var els=allNodes(4000);
    for(var j=0;j<els.length;j++){
      var el=els[j],r=el.getBoundingClientRect();
      if(r.width<80||r.height<50)continue;
      var meta=(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'')+' '+(el.id||'')+' '+(el.getAttribute('aria-label')||'');
      if(BAD.test(meta))continue;
      if(el.closest&&el.closest('header,footer,nav,aside,[class*=comment],[class*=recommend],[class*=related],[id*=comment]'))continue;
      var bg='';try{bg=getComputedStyle(el).backgroundImage||'';}catch(ignore){}
      if(!bg||bg==='none'){
        for(var a=0;a<LAZY.length;a++){var av=el.getAttribute&&el.getAttribute(LAZY[a]);if(goodUrl(av)){bg='url("'+av+'")';break;}}
        if(!bg||bg==='none'){
          var ds=el.getAttribute&&el.getAttribute('data-srcset');
          if(ds){var dss=pickSrcset(ds);if(dss)bg='url("'+dss+'")';}
        }
      }
      if(!bg||bg==='none')continue;
      var rx=/url\((?:"|')?([^"')]+)(?:"|')?\)/g,m;
      while((m=rx.exec(bg))!==null){
        var s=absUrl((m[1]||'').trim());
        if(!s||!goodUrl(s)||seen[s])continue;
        seen[s]=1;
        var inScroller=!!(sc&&sc.contains(el));var top=inScroller?(r.top-sr.top+sc.scrollTop):(r.top+wy);
        out.push({s:s,t:Math.round(top),l:Math.round(r.left),w:Math.round(r.width),h:Math.round(r.height),d:1,fw:r.width,fh:r.height,vl:r.left,vt:r.top,vb:r.bottom});
      }
    }
  
  }
  return out;
}
"""

        private const val JS_STEP = """
if(!window.__nsWmObs){
  window.__nsWmObs=1;window.__nsWmMut=0;window.__nsWmLoad=0;
  try{
    // Activity signals for the scan: an <img> being added, an image attribute changing, an image finishing its load.
    new MutationObserver(function(ms){
      for(var i=0;i<ms.length;i++){var m=ms[i];
        if(m.type==='attributes'){window.__nsWmMut++;continue;}
        for(var j=0;j<m.addedNodes.length;j++){var nd=m.addedNodes[j];
          if(nd.nodeType===1&&(nd.tagName==='IMG'||(nd.querySelector&&nd.querySelector('img')))){window.__nsWmMut++;break;}}
      }
    }).observe(document.documentElement,{subtree:true,childList:true,attributes:true,
      attributeFilter:['src','srcset','data-src','data-original','data-lazy-src','data-lazy','data-url']});
    window.addEventListener('load',function(e){if(e&&e.target&&e.target.tagName==='IMG')window.__nsWmLoad++;},true);
  }catch(e){}
}
var sc=window.__nsWmUseDocumentScroll?null:scroller(),vh=window.innerHeight||800,before,after,hh,bottom,step=__STEP__;
// Sites with CSS "scroll-behavior: smooth" animate every programmatic scroll (about half a second per step,
// and the position read right after still shows the OLD place). Force instant jumps for the scan.
try{
  if(window.__nsWmSB===undefined){
    window.__nsWmSB=[de.style.scrollBehavior||'',document.body?(document.body.style.scrollBehavior||''):''];
    de.style.setProperty('scroll-behavior','auto','important');
    if(document.body)document.body.style.setProperty('scroll-behavior','auto','important');
  }
}catch(e){}
var jump=function(el,dy){try{el.scrollBy({top:dy,left:0,behavior:'instant'});}catch(e){el.scrollTop=(el.scrollTop||0)+dy;}};
if(sc){
  if(window.__nsWmS===undefined)window.__nsWmS=sc.scrollTop;
  before=sc.scrollTop;vh=sc.clientHeight;jump(sc,Math.round(vh*step));after=sc.scrollTop;hh=sc.scrollHeight;
  // If the selected inner pane refuses to move before its real bottom, fall back to document
  // scrolling rather than falsely claiming that discovery reached the end.
  if(after===before&&before+vh<hh-4){
    var docBefore=window.pageYOffset||de.scrollTop||0;
    if(window.__nsWmY===undefined)window.__nsWmY=docBefore;
    jump(window,Math.round((window.innerHeight||vh)*step));
    var docAfter=window.pageYOffset||de.scrollTop||0;
    if(docAfter!==docBefore){
      window.__nsWmUseDocumentScroll=true;
      sc=null;before=docBefore;after=docAfter;vh=window.innerHeight||vh;
      hh=Math.max(de.scrollHeight||0,document.body?document.body.scrollHeight:0);
    }
  }
}else{
  before=window.pageYOffset||de.scrollTop||0;
  if(window.__nsWmY===undefined)window.__nsWmY=before;
  jump(window,Math.round(vh*step));after=window.pageYOffset||de.scrollTop||0;
  hh=Math.max(de.scrollHeight||0,document.body?document.body.scrollHeight:0);
}
bottom=(after+vh>=hh-4);
var now=Date.now(),probeBg=now-(window.__nsWmStepBgProbeAt||0)>=1200;
if(probeBg)window.__nsWmStepBgProbeAt=now;
var c=cands(sc,probeBg),p=0;
for(var i=0;i<c.length;i++)if(!c[i].d)p++;
var scanRefs=c.slice(0,600).map(function(x){return {s:x.s,t:x.t,l:x.l,w:x.w,h:x.h};});
return JSON.stringify({n:c.length,p:p,bottom:bottom,ready:document.readyState,imgs:scanRefs,m:window.__nsWmMut||0,l:window.__nsWmLoad||0});
"""

        // Cheap probe used between scroll steps: activity counters + images near the viewport still loading.
        private const val JS_SETTLE = """
var vhh=window.innerHeight||800,p=0,ims=document.images;
for(var i=0;i<ims.length;i++){var im=ims[i];
  if(!(im.currentSrc||im.src)||im.complete)continue;
  var r=im.getBoundingClientRect();
  if(r.bottom>-vhh*0.5&&r.top<vhh*1.5)p++;
}
return JSON.stringify({m:window.__nsWmMut||0,l:window.__nsWmLoad||0,p:p});
"""

        private const val JS_FORCE_INFO = """
var sc=scroller(),c=cands(sc,undefined,true,true),vw=window.innerWidth||de.clientWidth||0,vh=window.innerHeight||0,o=[];
for(var i=0;i<c.length;i++){var p=c[i];
  if(p.d&&p.vb>0&&p.vt<vh&&(p.vl+p.fw)>0&&p.vl<vw)o.push(p);}
return JSON.stringify({vw:vw,vh:vh,sy:(sc?sc.scrollTop:(window.pageYOffset||de.scrollTop||0)),sh:(sc?sc.scrollHeight:(de.scrollHeight||0)),imgs:o});
"""

        /** Tiny re-measure used right after a screenshot: did the page move while PixelCopy was running? */
        private const val JS_SCROLL_PROBE = """
var sc=scroller();
return JSON.stringify({y:(sc?sc.scrollTop:(window.pageYOffset||de.scrollTop||0)),h:(sc?sc.scrollHeight:(de.scrollHeight||0))});
"""

        private const val JS_FORCE_SCROLL = """
var sc=scroller(),vh=window.innerHeight||800,before,after,hh,bottom;
if(sc){
  if(window.__nsWmS===undefined)window.__nsWmS=sc.scrollTop;
  before=sc.scrollTop;vh=sc.clientHeight;sc.scrollTop=before+Math.round(vh*0.70);after=sc.scrollTop;hh=sc.scrollHeight;
}else{
  before=window.pageYOffset||de.scrollTop||0;
  if(window.__nsWmY===undefined)window.__nsWmY=before;
  window.scrollBy(0,Math.round(vh*0.70));after=window.pageYOffset||de.scrollTop||0;
  hh=Math.max(de.scrollHeight||0,document.body?document.body.scrollHeight:0);
}
bottom=(after+vh>=hh-4)||after===before;
return JSON.stringify({bottom:bottom});
"""

        private const val JS_FORCE_TOP = """
var sc=scroller();
if(sc){if(window.__nsWmS===undefined)window.__nsWmS=sc.scrollTop;sc.scrollTop=0;}
else{if(window.__nsWmY===undefined)window.__nsWmY=window.pageYOffset||de.scrollTop||0;window.scrollTo(0,0);}
return '1';
"""

        
        // Hide only the app's own overlay while a canvas screenshot is captured for OCR. Reference
        // counting makes overlapping visible-screen jobs safe; the existing overlay is restored as soon
        // as PixelCopy finishes, before OCR/network work begins.
        private const val JS_TEMP_HIDE_OVERLAY = """
var e=document.getElementById('__ns_wm_overlay');
window.__nsTempOverlayHideCount=(window.__nsTempOverlayHideCount||0)+1;
if(e&&window.__nsTempOverlayHideCount===1){window.__nsTempOverlayPriorVisibility=e.style.visibility;e.style.visibility='hidden';}
return '1';
"""

        private const val JS_TEMP_SHOW_OVERLAY = """
var n=Math.max(0,(window.__nsTempOverlayHideCount||0)-1);window.__nsTempOverlayHideCount=n;
var e=document.getElementById('__ns_wm_overlay');
if(e&&n===0){e.style.visibility=window.__nsTempOverlayPriorVisibility||'';delete window.__nsTempOverlayPriorVisibility;}
return '1';
"""

        private const val JS_FORCE_HIDE = """
var e=document.getElementById('__ns_wm_overlay');if(e)e.style.visibility='hidden';return '1';
"""

        private const val JS_FORCE_SHOW = """
var e=document.getElementById('__ns_wm_overlay');if(e)e.style.visibility='';return '1';
"""

        private const val JS_RESTORE = """
var sc=scroller();
if(sc&&window.__nsWmS!==undefined)sc.scrollTop=window.__nsWmS;
if(window.__nsWmY!==undefined){try{window.scrollTo({top:window.__nsWmY,left:0,behavior:'instant'});}catch(e){window.scrollTo(0,window.__nsWmY);}}
try{
  if(window.__nsWmSB!==undefined){
    de.style.scrollBehavior=window.__nsWmSB[0];if(document.body)document.body.style.scrollBehavior=window.__nsWmSB[1];
    delete window.__nsWmSB;
  }
}catch(e){}
try{delete window.__nsWmY;delete window.__nsWmS;delete window.__nsWmUseDocumentScroll;}catch(e){}
return '1';
"""

        private const val JS_COLLECT_VISIBLE = """
var sc=scroller(),c=cands(sc,undefined,true);
var top=sc?(sc.scrollTop||0):(window.pageYOffset||de.scrollTop||0);
var height=sc?(sc.clientHeight||window.innerHeight||800):(window.innerHeight||800);
var bottom=top+height;
var vpH=window.innerHeight||800;c=c.filter(function(p){return p.vb>0&&p.vt<vpH;});
function q(s){var e=document.querySelector(s);return e?(e.textContent||'').trim():'';}
function link(kind){
  var re=kind==='next'?/next|\\u4e0b\\u4e00|\\ub2e4\\uc74c|\\u6b21\\u306e|\\u6b21\\u3078|\\u6b21\\u7ae0/i:/prev(?!iew)|\\u4e0a\\u4e00|\\uc774\\uc804|\\u524d\\u306e|\\u524d\\u3078|\\u524d\\u7ae0/i;
  var cur=location.href.split('#')[0],cand=[];
  var el=document.querySelector('link[rel='+kind+'],a[rel='+kind+']');
  if(el&&el.href)cand.push(el.href);
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){var a=as[i],cls=(a.className&&a.className.baseVal!==undefined?a.className.baseVal:a.className||'')+' '+(a.id||'')+' '+(a.getAttribute('aria-label')||'')+' '+(a.title||''),t=(a.textContent||'').trim();if(/disabled/i.test(cls)||a.getAttribute('aria-disabled')==='true')continue;if((t.length<=30&&re.test(t))||re.test(cls))cand.push(a.href);}
  var root=function(h){return h.split('.').slice(-2).join('.');};
  for(var j=0;j<cand.length;j++){var h=cand[j];if(h&&/^https?:/i.test(h)&&h.split('#')[0]!==cur){try{if(root(new URL(h).hostname)===root(location.hostname))return h;}catch(e){}}}
  return '';}
var og=document.querySelector('meta[property="og:title"]');
var title=q('.subj_episode')||q('h1')||(og&&og.content)||document.title||'';
var novel=q('.detail_header .subj')||q('a.subj')||q('.series-title')||q('.comic-title')||'';
var xf=0,ifs=document.querySelectorAll('iframe');for(var xi=0;xi<ifs.length;xi++){try{if(!ifs[xi].contentDocument)xf++;}catch(e0){xf++;}}
var ncv=0,cvl=[];var cvs0=document.querySelectorAll('canvas');for(var cj=0;cj<cvs0.length&&cj<8;cj++){var rr=cvs0[cj].getBoundingClientRect();cvl.push(cvs0[cj].width+'x'+cvs0[cj].height+'@'+Math.round(rr.top)+'/'+Math.round(rr.width)+'x'+Math.round(rr.height));}
var cands2=c.map(function(x){return (x.cv?'CANVAS ':'')+x.w+'x'+x.h+' '+String(x.s).slice(0,60);}).slice(0,12);
var dbg=document.images.length+' img, '+ifs.length+' iframe('+xf+' cross-origin), '+cvs0.length+' canvas, '+c.length+' found | canvasErr='+(window.__nsWmCanvasErr||'-')+' | canvases=['+cvl.join(', ')+'] | cands=['+cands2.join(' ;; ')+'] | vp='+(window.innerWidth||0)+'x'+(window.innerHeight||0)+' | ready='+document.readyState;
return JSON.stringify({lang:document.documentElement.lang||'',url:location.href,title:title,novel:novel,next:link('next'),prev:link('prev'),imgs:c.slice(0,600),dbg:dbg});
"""

        // Real-time read-ahead: the next images that are completely below the screen (nothing is scrolled).
        private const val JS_COLLECT_AHEAD = """
var sc=scroller(),c=cands(sc);
var vpH=window.innerHeight||800,vpBottom=vpH;
var all=__ALL__;
// "Full chapter links" means we may inspect every URL already present in the DOM, not that
// we should OCR from panel 1 again. Upcoming-panel OCR must always start strictly below the
// reader's current viewport, otherwise its answers arrive late and duplicate the foreground OCR.
if(sc){
  var sr=sc.getBoundingClientRect();
  vpBottom=Math.max(sr.top,Math.min(vpH,sr.bottom));
}
// Panels below the viewport, PLUS panels that straddle its bottom/top edge: a bubble cut by the screen edge is
// only half visible, so the visible scan reads half of it. The whole picture is read here instead.
c=c.filter(function(p){var pb=p.vt+(p.h||0);return p.vt>=vpBottom-2||(p.vt<vpBottom-2&&pb>vpBottom-2)||(p.vt<0&&pb>0);});
// Use the page's document-space top for a stable reading order across batches.
c.sort(function(a,b){return all?(a.t-b.t):(a.vt-b.vt);});
return JSON.stringify({lang:document.documentElement.lang||'',url:location.href,imgs:c.slice(0,__N__)});
"""

        private const val JS_COLLECT = """
var sc=scroller(),c=cands(sc,true);
function q(s){var e=document.querySelector(s);return e?(e.textContent||'').trim():'';}
function link(kind){
  var re=kind==='next'?/next|\u4e0b\u4e00|\ub2e4\uc74c|\u6b21\u306e|\u6b21\u3078|\u6b21\u7ae0/i:/prev(?!iew)|\u4e0a\u4e00|\uc774\uc804|\u524d\u306e|\u524d\u3078|\u524d\u7ae0/i;
  var cur=location.href.split('#')[0],cand=[];
  var el=document.querySelector('link[rel='+kind+'],a[rel='+kind+']');
  if(el&&el.href)cand.push(el.href);
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){var a=as[i];
    var cls=(a.className&&a.className.baseVal!==undefined?a.className.baseVal:a.className||'')+' '+(a.id||'')+' '+(a.getAttribute('aria-label')||'')+' '+(a.title||'');
    var t=(a.textContent||'').trim();
    if(/disabled/i.test(cls)||a.getAttribute('aria-disabled')==='true')continue;
    if((t.length<=30&&re.test(t))||re.test(cls))cand.push(a.href);}
  var root=function(h){return h.split('.').slice(-2).join('.');};
  for(var j=0;j<cand.length;j++){var h=cand[j];
    if(h&&/^https?:/i.test(h)&&h.split('#')[0]!==cur){try{if(root(new URL(h).hostname)===root(location.hostname))return h;}catch(e){}}}
  return '';}
var og=document.querySelector('meta[property="og:title"]');
var title=q('.subj_episode')||q('h1')||(og&&og.content)||document.title||'';
var novel=q('.detail_header .subj')||q('a.subj')||q('.series-title')||q('.comic-title')||'';
return JSON.stringify({lang:document.documentElement.lang||'',url:location.href,title:title,novel:novel,next:link('next'),prev:link('prev'),imgs:c.slice(0,600)});
"""
    }
}
