package com.ashraf.novelstudio.japanesemanga

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ashraf.novelstudio.Prefs
import com.ashraf.novelstudio.WmBubble
import com.ashraf.novelstudio.WebModeTextRemover
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/** Optional, isolated test screen. It does not call or modify the app's existing OCR/Webtoon pipeline. */
class JapaneseMangaEngineActivity : Activity() {
    private lateinit var manager: JapaneseMangaModelManager
    private lateinit var assetLoader: JapaneseMangaAssetLoader
    private lateinit var page: WebView
    private lateinit var runtime: WebView
    private lateinit var status: TextView
    private lateinit var resultView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var installButton: Button
    private lateinit var deleteButton: Button
    private lateinit var scanButton: Button
    private lateinit var cancelButton: Button
    private lateinit var translateButton: Button
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "japanese-manga-image").apply { isDaemon = true } }
    @Volatile private var scanning = false
    private var runtimeReady = false
    private var lastResultJson = ""
    private var scanPageUrl = ""
    private var scannedPanels = JSONArray()
    private data class ImageSource(val url: String, val top: Double, val width: Int, val height: Int)
    private data class PanelImage(val sourceUrl: String, val localUrl: String, val top: Double, val sourceWidth: Int, val sourceHeight: Int)
    private var scanWhenReady = false
    private var requestId = 0
    private var images = emptyList<PanelImage>()
    private var pending = emptyMap<String, PanelImage>() // OCR request ID -> panel metadata
    private var index = 0
    private var accumulated = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JapaneseMangaRuntime.init(this)
        JapaneseMangaRuntime.release()
        manager = JapaneseMangaModelManager(this)
        assetLoader = JapaneseMangaAssetLoader(this, manager)
        buildUi()
        setupPage()
        setupRuntime()
        val firstUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        if (firstUrl.startsWith("https://") || firstUrl.startsWith("http://")) page.loadUrl(firstUrl)
        else status.text = "প্রথমে NovelStudio-তে একটি মাঙ্গা পেজ খুলে Menu → Japanese Manga Engine নির্বাচন করো।"
        controls()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            setBackgroundColor(0xFF10131C.toInt())
        }
        root.addView(TextView(this).apply {
            text = "日本語 Manga Engine"
            textSize = 20f
            setTextColor(0xFFF2F4FA.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFBEC7D8.toInt())
            setPadding(0, dp(2), 0, dp(7))
            text = "ঐচ্ছিক মাঙ্গা OCR ইঞ্জিন"
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        installButton = button("ডাউনলোড (~38 MB)") { confirmInstall() }
        deleteButton = button("ইঞ্জিন মুছুন") { confirmDelete() }
        row1.addView(installButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        row1.addView(deleteButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanButton = button("এই পেজ OCR স্ক্যান") { scanPage() }
        cancelButton = button("বাতিল") { cancelWork() }
        row2.addView(scanButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { topMargin = dp(5); rightMargin = dp(4) })
        row2.addView(cancelButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { topMargin = dp(5); leftMargin = dp(4) })
        root.addView(row2)
        translateButton = button("অনুবাদ করে বাবলে বসাও") { returnMangaResult() }
        translateButton.isEnabled = false
        root.addView(translateButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(5) })
        page = WebView(this).apply { setBackgroundColor(0xFF171C28.toInt()) }
        root.addView(page, LinearLayout.LayoutParams(-1, 0, 0.9f).apply { topMargin = dp(7); bottomMargin = dp(5) })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        resultView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE0E6F0.toInt())
            setPadding(dp(7), dp(8), dp(7), dp(12))
            text = "OCR ফল এখানে দেখাবে। প্রথমে ইঞ্জিন ডাউনলোড করে পেজ স্ক্যান করো।\n\nডাউনলোড করা থাকলে Realtime/Scan মোড জাপানিজ মাঙ্গায় এই মডেল নিজেই ব্যবহার করে; এই স্ক্রিন শুধু ডাউনলোড/মোছা ও টেস্টের জন্য।"
        }
        scroll.addView(resultView, android.widget.FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.1f))
        runtime = WebView(this).apply {
            alpha = 0.01f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(0xFF10131C.toInt())
        }
        root.addView(runtime, LinearLayout.LayoutParams(dp(1), dp(1)))
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { action() }
    }

    private fun setupPage() {
        page.settings.javaScriptEnabled = true
        page.settings.domStorageEnabled = true
        page.settings.loadsImagesAutomatically = true
        page.settings.useWideViewPort = true
        page.settings.loadWithOverviewMode = true
        page.settings.setSupportZoom(true)
        page.settings.builtInZoomControls = true
        page.settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, true)
        page.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                controls()
            }
        }
    }

    private fun setupRuntime() {
        runtime.settings.javaScriptEnabled = true
        runtime.settings.allowFileAccess = false
        runtime.settings.allowContentAccess = false
        runtime.addJavascriptInterface(Bridge(), "NsJapaneseMangaBridge")
        runtime.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                return if (uri.host == "appassets.androidplatform.net") assetLoader.intercept(uri)
                else WebResourceResponse("text/plain", "UTF-8", null)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return uri.host != "appassets.androidplatform.net" && uri.scheme != "about"
            }
        }
        loadRuntime()
    }

    private fun loadRuntime() {
        runtimeReady = false
        runtime.loadUrl("https://appassets.androidplatform.net/assets/japanese_manga_engine.html")
    }

    private inner class Bridge {
        @JavascriptInterface fun onReady() { main.post {
            runtimeReady = true
            if (scanWhenReady && !isFinishing && !isDestroyed) { scanWhenReady = false; scanPage() }
            else controls()
        } }
        @JavascriptInterface fun onRuntimeError(message: String) { main.post {
            if (manager.isInstalled()) status.text = "OCR runtime error: " + message.take(180)
            if (scanWhenReady) { scanWhenReady = false; controls() }
        } }
        @JavascriptInterface fun onOcrResult(id: String, json: String) { main.post {
            try { acceptOcr(id, json) } catch (e: Exception) { finishScan("OCR result read error: " + (e.message ?: "invalid result")) }
        } }
        @JavascriptInterface fun onOcrError(id: String, message: String) { main.post {
            finishScan("OCR failed\n" + message.take(400))
        } }
    }

    private fun controls() {
        val installed = manager.isInstalled()
        installButton.isEnabled = !manager.isDownloading() && !scanning && !scanWhenReady
        deleteButton.isEnabled = manager.hasFiles() && !manager.isDownloading() && !scanning && !scanWhenReady
        scanButton.isEnabled = installed && page.url?.startsWith("http") == true && !scanning && !scanWhenReady
        cancelButton.isEnabled = manager.isDownloading() || scanning || scanWhenReady
        translateButton.isEnabled = lastResultJson.isNotBlank() && !scanning
    }

    private fun confirmInstall() {
        if (manager.isInstalled()) { Toast.makeText(this, "ইঞ্জিন আগেই ইনস্টল আছে", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Japanese Manga Engine")
            .setMessage("আনুমানিক ৩৮ MB মডেল ও OCR runtime ডাউনলোড হবে। সব ফাইল অ্যাপের ব্যক্তিগত স্টোরেজে থাকবে এবং পরে মুছে ফেলা যাবে। PP-OCRv6_manga v0.2 (Apache-2.0), ONNX Runtime Web (MIT)।")
            .setNegativeButton("পরে", null)
            .setPositiveButton("ডাউনলোড") { _, _ -> install() }
            .show()
    }

    private fun install() {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = "OCR ইঞ্জিন ডাউনলোড হচ্ছে…"
        controls()
        manager.download(onProgress = { p -> main.post {
            status.text = "${p.index}/${p.count}  ${p.name} · ${p.read / (1024 * 1024)} MB"
            if (p.total > 0) { progress.isIndeterminate = false; progress.progress = (100 * p.read / p.total).toInt().coerceIn(0, 100) }
        } }, onComplete = { result -> main.post {
            if (isFinishing || isDestroyed) return@post
            progress.visibility = View.GONE
            when (result) {
                JapaneseMangaModelManager.Result.Success -> {
                    JapaneseMangaRuntime.invalidate()
                    resultView.text = "✅ Japanese Manga Engine ইনস্টল হয়েছে। এখন থেকে Realtime/Scan মোডে জাপানিজ মাঙ্গায় এই মডেল নিজে থেকেই ব্যবহার হবে; অন্য ভাষায় আগের OCR-ই চলবে।"
                    loadRuntime()
                    status.text = "ইঞ্জিন ইনস্টল সম্পন্ন। OCR runtime প্রস্তুত হচ্ছে…"
                }
                JapaneseMangaModelManager.Result.Cancelled -> status.text = "ডাউনলোড বাতিল করা হয়েছে।"
                is JapaneseMangaModelManager.Result.Failure -> status.text = "ডাউনলোড ব্যর্থ: " + result.message
            }
            controls()
        } })
    }

    private fun confirmDelete() {
        if (manager.isDownloading() || scanning) return
        AlertDialog.Builder(this).setTitle("ইঞ্জিন মুছে ফেলবে?")
            .setMessage("ডাউনলোড করা OCR মডেল, runtime, dictionary ও সাময়িক ছবি মুছে যাবে। NovelStudio-র বাকি ফিচার/ডেটা অক্ষত থাকবে।")
            .setNegativeButton("না", null)
            .setPositiveButton("মুছে ফেলো") { _, _ ->
                JapaneseMangaRuntime.release()
                val ok = manager.deleteEngine()
                JapaneseMangaRuntime.invalidate()
                if (ok) {
                    runtime.stopLoading()
                    runtime.loadUrl("about:blank")
                    resultView.text = "ইঞ্জিন মুছে ফেলা হয়েছে। চাইলে পরে আবার ডাউনলোড করতে পারো।"
                    status.text = "ইঞ্জিন মুছে ফেলা হয়েছে।"
                } else status.text = "ইঞ্জিন মুছে ফেলা যায়নি।"
                controls()
            }.show()
    }

    private fun scanPage() {
        if (!manager.isInstalled()) { confirmInstall(); return }
        if (!page.url.orEmpty().startsWith("http")) { Toast.makeText(this, "আগে মাঙ্গা পেজ খোলো", Toast.LENGTH_SHORT).show(); return }
        if (scanning) return
        if (!runtimeReady) {
            scanWhenReady = true
            status.text = "OCR model/runtime লোড হচ্ছে; প্রস্তুত হলেই স্ক্যান শুরু হবে।"
            controls()
            loadRuntime()
            return
        }
        scanning = true
        accumulated = ""
        index = 0
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        resultView.text = "পেজের ছবি খোঁজা হচ্ছে…"
        controls()
        val pageUrl = page.url.orEmpty()
        scanPageUrl = pageUrl
        lastResultJson = ""
        scannedPanels = JSONArray()
        translateButton.isEnabled = false
        val js = "(function(){return JSON.stringify({imgs:Array.from(document.images).map(function(i){var r=i.getBoundingClientRect();return {src:i.currentSrc||i.src||'',top:r.top+scrollY,w:r.width,h:r.height,nw:i.naturalWidth||0,nh:i.naturalHeight||0};}).filter(function(i){return /^https?:/i.test(i.src)&&i.nw>=160&&i.nh>=120&&i.w>=60&&i.h>=60;}).sort(function(a,b){return a.top-b.top;}).filter(function(v,i,a){return a.findIndex(function(x){return x.src===v.src;})===i;}).slice(0,2)})})()"
        page.evaluateJavascript(js) { raw ->
            try {
                val result = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                val arr = result.optJSONArray("imgs") ?: JSONArray()
                val sources = ArrayList<ImageSource>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val src = item.optString("src").takeIf { it.startsWith("http") } ?: continue
                    sources.add(ImageSource(src, item.optDouble("top", 0.0), item.optInt("nw"), item.optInt("nh")))
                }
                if (sources.isEmpty()) { finishScan("এই পেজে স্ক্যান করার মতো ছবি পাওয়া যায়নি।"); return@evaluateJavascript }
                val ua = page.settings.userAgentString
                worker.execute {
                    val saved = ArrayList<PanelImage>()
                    val failures = ArrayList<String>()
                    for (source in sources) {
                        if (!scanning) break
                        try {
                            val local = "https://appassets.androidplatform.net/manga-engine/" + downloadImage(source.url, pageUrl, ua)
                            saved.add(PanelImage(source.url, local, source.top, source.width, source.height))
                        } catch (e: Exception) { failures.add(Uri.parse(source.url).host.orEmpty() + ": " + (e.message ?: "download failed")) }
                    }
                    main.post {
                        if (!scanning) { saved.forEach { deleteLocal(it.localUrl) }; return@post }
                        images = saved
                        accumulated = if (failures.isEmpty()) "" else "কিছু ছবি ডাউনলোড হয়নি: ${failures.joinToString("; ")}\n\n"
                        if (images.isEmpty()) finishScan("পেজের ছবি ডাউনলোড করা যায়নি।\n" + accumulated)
                        else nextImage()
                    }
                }
            } catch (e: Exception) { finishScan("পেজ স্ক্যান করা যায়নি: " + (e.message ?: "invalid page data")) }
        }
    }

    private fun nextImage() {
        if (!scanning) return
        if (index >= images.size) {
            lastResultJson = if (scannedPanels.length() > 0) {
                JSONObject().put("pageUrl", scanPageUrl).put("panels", scannedPanels).toString()
            } else ""
            val finalText = accumulated + "\n\n✅ OCR সম্পন্ন। ফল ঠিক থাকলে ‘অনুবাদ করে বাবলে বসাও’ চাপো।"
            finishScan(finalText)
            status.text = "✅ OCR স্ক্যান সম্পন্ন।"
            return
        }
        val panel = images[index]
        requestId++
        val id = "jm-" + requestId + "-" + UUID.randomUUID().toString()
        pending = pending + (id to panel)
        status.text = "${index + 1}/${images.size} ছবি OCR হচ্ছে…"
        runtime.evaluateJavascript("window.runJapaneseMangaOcr(${JSONObject.quote(panel.localUrl)},${JSONObject.quote(id)})", null)
    }

    private fun acceptOcr(id: String, raw: String) {
        if (!scanning) return
        val panel = pending[id] ?: return
        pending = pending - id
        val json = JSONObject(raw)
        val w = json.optInt("width"); val h = json.optInt("height")
        val regions = json.optJSONArray("regions") ?: JSONArray()
        val removalBubbles = if (Prefs.bool(this, "wmTextRemoval", false)) {
            mangaRemovalBubbles(panel.sourceUrl, regions, w, h)
        } else emptyList()

        // Image decode, pixel masking, and WebP encoding must not run on the UI thread.
        val shouldAttempt = removalBubbles.isNotEmpty() &&
            panel.sourceWidth == w && panel.sourceHeight == h &&
            panel.sourceWidth <= 4096 && panel.sourceHeight <= 4096 &&
            panel.sourceWidth.toLong() * panel.sourceHeight.toLong() <= MAX_CLEAN_PIXELS
        if (!shouldAttempt) {
            deleteLocal(panel.localUrl)
            commitOcrResult(panel, w, h, regions)
            return
        }
        worker.execute {
            try {
                val localPath = Uri.parse(panel.localUrl).path.orEmpty().removePrefix("/manga-engine/")
                val originalPath = localPath.removeSuffix(".jpg") + ".original"
                // Never substitute the recompressed OCR JPEG into the page; if original retention failed,
                // simply skip cleaning for this panel.
                val file = manager.resolve(originalPath)?.takeIf { it.isFile }
                if (file != null && file.length() <= MAX_IMAGE_BYTES) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth == panel.sourceWidth && bounds.outHeight == panel.sourceHeight &&
                        bounds.outWidth == w && bounds.outHeight == h) {
                        WebModeTextRemover.cleanAndCache(this@JapaneseMangaEngineActivity,
                            panel.sourceUrl, file.readBytes(), removalBubbles)
                    }
                }
            } catch (_: OutOfMemoryError) {
                // OCR output remains usable even if the optional cleaner cannot fit in memory.
            } catch (_: Exception) {
                // Text removal is best-effort; one failed image must never abort chapter OCR.
            } finally {
                main.post {
                    deleteLocal(panel.localUrl)
                    if (!isFinishing && !isDestroyed && scanning) commitOcrResult(panel, w, h, regions)
                }
            }
        }
    }

    private fun mangaRemovalBubbles(src: String, regions: JSONArray, width: Int, height: Int): List<WmBubble> {
        if (width <= 0 || height <= 0) return emptyList()
        val out = ArrayList<WmBubble>()
        for (i in 0 until minOf(regions.length(), 500)) {
            val r = regions.optJSONObject(i) ?: continue
            val text = r.optString("text").trim()
            val x = r.optInt("x", -1)
            val y = r.optInt("y", -1)
            val rw = r.optInt("w", 0)
            val rh = r.optInt("h", 0)
            if (text.isEmpty() || x < 0 || y < 0 || rw < 4 || rh < 4 || x >= width || y >= height) continue
            val bw = minOf(rw, width - x)
            val bh = minOf(rh, height - y)
            val fg = r.optInt("fg", 0xFF171717.toInt())
            out.add(WmBubble(
                src = src,
                cx = ((x + bw / 2f) / width).coerceIn(0f, 1f),
                cy = ((y + bh / 2f) / height).coerceIn(0f, 1f),
                w = (bw.toFloat() / width).coerceIn(0.003f, 1f),
                h = (bh.toFloat() / height).coerceIn(0.003f, 1f),
                angle = 0f,
                font = (minOf(bw, bh).toFloat() / width).coerceIn(0.004f, 0.12f),
                bg = r.optInt("bg", 0xFFFFFFFF.toInt()),
                fg = fg,
                fg2 = fg,
                soft = r.optBoolean("soft", false),
                sourceText = text
            ))
        }
        return out
    }

    private fun commitOcrResult(panel: PanelImage, w: Int, h: Int, regions: JSONArray) {
        if (!scanning) return
        scannedPanels.put(JSONObject()
            .put("src", panel.sourceUrl).put("top", panel.top)
            .put("sourceWidth", panel.sourceWidth).put("sourceHeight", panel.sourceHeight)
            .put("width", w).put("height", h).put("regions", regions))
        val sb = StringBuilder(accumulated)
        sb.append("\n━━━━━━━━━━━━━━━━━━\nছবি ").append(index + 1).append(" · ").append(w).append("×").append(h)
            .append("\nরিজিয়ন: ").append(regions.length()).append("\n\n")
        for (i in 0 until regions.length()) {
            val r = regions.optJSONObject(i) ?: continue
            val text = r.optString("text").trim()
            if (text.isEmpty()) continue
            sb.append("[").append(r.optInt("order").toString().padStart(3, '0')).append("] ").append(text).append('\n')
            sb.append("   বক্স: x=").append(r.optInt("x")).append(" y=").append(r.optInt("y"))
                .append(" w=").append(r.optInt("w")).append(" h=").append(r.optInt("h")).append('\n')
        }
        accumulated = sb.toString()
        index++
        nextImage()
    }

    private fun downloadImage(src: String, referer: String, userAgent: String): String {
        val dir = File(manager.root, "input")
        if (!dir.exists() && !dir.mkdirs()) error("image folder unavailable")
        val id = UUID.randomUUID().toString()
        val raw = File(dir, id + ".download")
        val target = File(dir, id + ".jpg")
        val original = File(dir, id + ".original")
        var success = false
        try {
            val conn = URL(src).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20000; conn.readTimeout = 35000; conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Referer", referer)
                CookieManager.getInstance().getCookie(src)?.let { conn.setRequestProperty("Cookie", it) }
                if (conn.responseCode !in 200..299) error("image HTTP ${conn.responseCode}")
                if (conn.contentLengthLong > MAX_IMAGE_BYTES) error("image exceeds size limit")
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(raw).use { out ->
                        val b = ByteArray(32768); var total = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted || !scanning) throw InterruptedException("scan cancelled")
                            val n = input.read(b); if (n < 0) break
                            total += n; if (total > MAX_IMAGE_BYTES) error("image exceeds size limit")
                            out.write(b, 0, n)
                        }
                    }
                }
            } finally { conn.disconnect() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("unsupported image")
            var sample = 1
            while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 ||
                (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
            val bitmap = BitmapFactory.decodeFile(raw.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("image decode failed")
            try { FileOutputStream(target).use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)) error("JPEG conversion failed") } }
            finally { bitmap.recycle() }
            // Keep the original compressed source only while this optional remover is enabled and
            // the frame is small enough to be cleaned safely. The JPEG used by OCR stays separate.
            if (Prefs.bool(this, "wmTextRemoval", false) &&
                bounds.outWidth <= 4096 && bounds.outHeight <= 4096 &&
                bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_CLEAN_PIXELS) {
                // If preserving the original fails, fall back to OCR only; the cleaner won't use the
                // lower-quality temporary JPEG unless its dimensions/geometry were checked above.
                raw.renameTo(original)
            }
            success = true
            return "input/" + target.name
        } finally {
            raw.delete()
            if (!success) {
                target.delete()
                original.delete()
            }
        }
    }

    private fun deleteLocal(url: String) {
        val path = Uri.parse(url).path.orEmpty().removePrefix("/manga-engine/")
        manager.resolve(path)?.delete()
        if (path.endsWith(".jpg")) manager.resolve(path.removeSuffix(".jpg") + ".original")?.delete()
    }

    private fun finishScan(text: String) {
        images.forEach { deleteLocal(it.localUrl) }
        pending.values.forEach { deleteLocal(it.localUrl) }
        images = emptyList()
        pending = emptyMap()
        scanning = false
        progress.visibility = View.GONE
        resultView.text = text
        if (text.contains("বাতিল", ignoreCase = true)) {
            lastResultJson = ""
            translateButton.isEnabled = false
        } else if (lastResultJson.isBlank() && scannedPanels.length() > 0) {
            lastResultJson = JSONObject().put("pageUrl", scanPageUrl).put("panels", scannedPanels).toString()
        }
        status.text = text.lineSequence().firstOrNull()?.take(180)?.ifBlank { "স্ক্যান সম্পন্ন" } ?: "স্ক্যান সম্পন্ন"
        controls()
    }

    private fun returnMangaResult() {
        if (lastResultJson.isBlank() || scanning) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_JSON, lastResultJson)
            .putExtra(EXTRA_RESULT_URL, scanPageUrl))
        finish()
    }

    private fun cancelWork() {
        if (manager.isDownloading()) {
            manager.cancel()
            status.text = "ডাউনলোড বাতিল হচ্ছে…"
        } else if (scanning || scanWhenReady) {
            scanWhenReady = false
            finishScan("স্ক্যান বাতিল করা হয়েছে।")
            status.text = "স্ক্যান বাতিল করা হয়েছে।"
        }
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    override fun onBackPressed() {
        if (scanning || scanWhenReady || manager.isDownloading()) cancelWork() else super.onBackPressed()
    }

    override fun onDestroy() {
        scanning = false
        images.forEach { deleteLocal(it.localUrl) }
        pending.values.forEach { deleteLocal(it.localUrl) }
        manager.shutdown()
        worker.shutdownNow()
        runtime.removeJavascriptInterface("NsJapaneseMangaBridge")
        runtime.stopLoading(); page.stopLoading()
        runtime.destroy(); page.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAGE_URL = "com.ashraf.novelstudio.japanesemanga.PAGE_URL"
        const val EXTRA_RESULT_JSON = "com.ashraf.novelstudio.japanesemanga.RESULT_JSON"
        const val EXTRA_RESULT_URL = "com.ashraf.novelstudio.japanesemanga.RESULT_URL"
        const val REQUEST_CODE = 43
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_PIXELS = 10L * 1024 * 1024
        private const val MAX_CLEAN_PIXELS = 3_000_000L
    }
}