package com.ashraf.novelstudio.japanesemanga

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * Process-wide, headless host of the downloaded Japanese manga OCR model (PP-OCRv6_manga on
 * ONNX Runtime Web). The regular scan pipeline calls [recognize] from its worker threads, so the
 * dedicated model is used automatically whenever it is installed - no separate screen needed.
 *
 * - One hidden WebView, created on first use and freed again after [IDLE_RELEASE_MS] without work.
 * - Calls are serialised; the model runs one image at a time.
 * - If the runtime fails, it is switched off for [COOL_DOWN_MS] so the app falls back to ML Kit
 *   instead of waiting for a dead WebView on every panel.
 */
internal object JapaneseMangaRuntime {
    private const val TAG = "NovelStudioManga"
    private const val PAGE = "https://appassets.androidplatform.net/assets/japanese_manga_engine.html"
    private const val INPUT_URL = "https://appassets.androidplatform.net/manga-engine/input/"
    private const val READY_TIMEOUT_S = 90L
    private const val OCR_TIMEOUT_S = 150L
    private const val IDLE_RELEASE_MS = 180_000L
    private const val COOL_DOWN_MS = 120_000L
    private const val MAX_PIXELS = 9_000_000L

    class Output(val json: String, val scale: Float)

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var json: String? = null
        @Volatile var error: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val ocrLock = ReentrantLock()
    private val pending = ConcurrentHashMap<String, Pending>()
    private var appContext: Context? = null
    private var manager: JapaneseMangaModelManager? = null
    private var loader: JapaneseMangaAssetLoader? = null
    private var view: WebView? = null

    @Volatile private var readyLatch = CountDownLatch(1)
    @Volatile private var runtimeError: String? = null
    @Volatile private var lastUse = 0L
    @Volatile private var inFlight = 0
    @Volatile private var installedCache = false
    @Volatile private var installedCheckedAt = 0L
    @Volatile private var disabledUntil = 0L

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val m = JapaneseMangaModelManager(app)
        manager = m
        loader = JapaneseMangaAssetLoader(app, m)
        // Leftovers from a process that died mid-scan.
        try { File(m.root, "input").listFiles()?.forEach { if (it.name.startsWith("rt-")) it.delete() } } catch (_: Exception) {}
    }

    /** True when the engine files are installed and the runtime has not just failed. */
    fun isAvailable(): Boolean {
        val m = manager ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now < disabledUntil) return false
        if (now - installedCheckedAt > 3000L) {
            installedCache = try { m.isInstalled() } catch (_: Exception) { false }
            installedCheckedAt = now
        }
        return installedCache
    }

    /** Call after install/delete so the next check re-reads the files. */
    fun invalidate() {
        installedCheckedAt = 0L
        disabledUntil = 0L
    }

    /** Frees the WebView (e.g. before the engine files are deleted). Safe to call from any thread. */
    fun release() {
        invalidate()
        main.post {
            if (ocrLock.tryLock()) {
                try { destroyViewOnMain() } finally { ocrLock.unlock() }
            }
        }
    }

    /**
     * Blocking. Runs the dedicated model over [bitmap] and returns its raw JSON (pixel coordinates of
     * the bitmap that was scanned, divided by [Output.scale] when the picture had to be shrunk).
     * Throws on any failure so the caller can fall back to another engine.
     */
    fun recognize(bitmap: Bitmap): Output {
        val m = manager ?: throw IOException("manga runtime not initialised")
        return ocrLock.withLock {
            inFlight++
            try {
                lastUse = SystemClock.elapsedRealtime()
                ensureReady()

                val dir = File(m.root, "input")
                if (!dir.exists() && !dir.mkdirs()) throw IOException("input folder unavailable")
                val id = "rt-" + UUID.randomUUID().toString()
                val file = File(dir, "$id.jpg")

                var work = bitmap
                var scale = 1f
                val pixels = bitmap.width.toLong() * bitmap.height.toLong()
                if (pixels > MAX_PIXELS) {
                    scale = sqrt(MAX_PIXELS.toDouble() / pixels.toDouble()).toFloat()
                    work = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true
                    )
                }
                val pend = Pending()
                pending[id] = pend
                try {
                    FileOutputStream(file).use {
                        if (!work.compress(Bitmap.CompressFormat.JPEG, 92, it)) throw IOException("JPEG encode failed")
                    }
                    val js = "window.runJapaneseMangaOcr(" + JSONObject.quote(INPUT_URL + id + ".jpg") +
                        "," + JSONObject.quote(id) + ")"
                    main.post { view?.evaluateJavascript(js, null) }
                    if (!pend.latch.await(OCR_TIMEOUT_S, TimeUnit.SECONDS)) {
                        fail("manga OCR timeout")
                        throw IOException("manga OCR timeout")
                    }
                    pend.error?.let { throw IOException(it) }
                    Output(pend.json ?: throw IOException("empty manga OCR result"), scale)
                } finally {
                    pending.remove(id)
                    file.delete()
                    if (work !== bitmap) work.recycle()
                }
            } finally {
                inFlight--
                lastUse = SystemClock.elapsedRealtime()
                scheduleIdleRelease()
            }
        }
    }

    // ------------------------------------------------------------------ WebView lifecycle

    private fun ensureReady() {
        if (view == null) startView()
        if (!readyLatch.await(READY_TIMEOUT_S, TimeUnit.SECONDS)) {
            fail("manga runtime load timeout")
            throw IOException("manga runtime load timeout")
        }
        runtimeError?.let {
            fail(it)
            throw IOException(it)
        }
    }

    private fun startView() {
        val ctx = appContext ?: throw IOException("manga runtime not initialised")
        runtimeError = null
        readyLatch = CountDownLatch(1)
        val created = CountDownLatch(1)
        main.post {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.addJavascriptInterface(Bridge(), "NsJapaneseMangaBridge")
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val uri = request?.url ?: return super.shouldInterceptRequest(v, request)
                        return if (uri.host == "appassets.androidplatform.net") loader?.intercept(uri)
                        else WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return true
                        return uri.host != "appassets.androidplatform.net" && uri.scheme != "about"
                    }
                }
                view = wv
                wv.loadUrl(PAGE)
            } catch (e: Throwable) {
                runtimeError = e.message ?: e.javaClass.simpleName
                readyLatch.countDown()
            } finally {
                created.countDown()
            }
        }
        created.await(10, TimeUnit.SECONDS)
    }

    /** Must run on the main thread. */
    private fun destroyViewOnMain() {
        view?.let { v ->
            try {
                v.stopLoading()
                v.loadUrl("about:blank")
                v.removeJavascriptInterface("NsJapaneseMangaBridge")
                v.destroy()
            } catch (_: Throwable) { }
        }
        view = null
        readyLatch = CountDownLatch(1)
    }

    private fun fail(reason: String) {
        Log.w(TAG, "Manga runtime disabled for a while: $reason")
        disabledUntil = SystemClock.elapsedRealtime() + COOL_DOWN_MS
        main.post { destroyViewOnMain() }
    }

    private val idleRunnable = Runnable {
        if (view != null) {
            val idle = SystemClock.elapsedRealtime() - lastUse
            if (inFlight == 0 && idle >= IDLE_RELEASE_MS && ocrLock.tryLock()) {
                try { destroyViewOnMain() } finally { ocrLock.unlock() }
            } else {
                scheduleIdleRelease()
            }
        }
    }

    private fun scheduleIdleRelease() {
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, IDLE_RELEASE_MS)
    }

    // ------------------------------------------------------------------ JS bridge

    internal fun signalReady() { readyLatch.countDown() }
    internal fun signalRuntimeError(message: String) { runtimeError = message; readyLatch.countDown() }
    internal fun signalResult(id: String, json: String) { pending[id]?.let { it.json = json; it.latch.countDown() } }
    internal fun signalOcrError(id: String, message: String) { pending[id]?.let { it.error = message; it.latch.countDown() } }

    private class Bridge {
        @JavascriptInterface fun onReady() { JapaneseMangaRuntime.signalReady() }
        @JavascriptInterface fun onRuntimeError(message: String) { JapaneseMangaRuntime.signalRuntimeError(message) }
        @JavascriptInterface fun onOcrResult(id: String, json: String) { JapaneseMangaRuntime.signalResult(id, json) }
        @JavascriptInterface fun onOcrError(id: String, message: String) { JapaneseMangaRuntime.signalOcrError(id, message) }
    }
}
