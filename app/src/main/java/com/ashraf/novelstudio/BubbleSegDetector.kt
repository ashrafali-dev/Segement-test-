package com.ashraf.novelstudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OPTIONAL second model that only does ONE job: give the speech balloons the main detector (BubbleDetector, RT-DETR)
 * found their real outline (a mask). It never finds lettering, never replaces the main detector, and is skipped
 * whenever it could cost memory or time the reader needs:
 *   - it is only called from BubbleDetector.detect, which the on-screen real-time path never starts (read-ahead only);
 *   - skipped when the main detector saw no balloon, when free Java heap < [MIN_FREE_HEAP_MB], or when Android reports low memory;
 *   - the session (and its 12 MB input buffer) is released [IDLE_RELEASE_MS] after the last use.
 * Every decision is written to the OCR report (tag DETECT_SRC) so it is visible why masks were or were not added.
 *
 * Model: mednasserallah/manga109-segmentation-bubble-onnx (YOLO11n-seg, Apache-2.0 weights; training data terms: see NOTICE).
 */
internal object BubbleSegDetector {
    private const val TAG = "NsSegDetector"
    const val MODEL_URL =
        "https://huggingface.co/mednasserallah/manga109-segmentation-bubble-onnx/resolve/main/manga109_segmentation_bubble_1024.onnx"
    const val MODEL_SHA256 = "593cae61f4c9ffc773fee34d55c28069390d19415c24dc04600f983311618ea3"
    private const val MIN_BYTES = 5_000_000L
    private const val MAX_BYTES = 40_000_000L
    private const val APPROX_BYTES = 12_400_000L
    private const val MIN_FREE_HEAP_MB = 70L
    /**
     * Free the mask model quickly after the last use: memory pressure levels 10-15 (192 MB devices) arrive within
     * seconds of a scan, and a released session is a few MB of off-heap arena plus the 12 MB input buffer.
     * Kept <= 2000 ms (Defect E) — a cheap download is far better than a WebView renderer kill.
     */
    private const val IDLE_RELEASE_MS = 2_000L

    @Volatile var lastError: String = ""
        private set
    /** Why masks were / were not produced by the latest call (shown in the status pill and the report). */
    @Volatile var lastInfo: String = "not run yet"
        private set

    private val downloading = AtomicBoolean(false)
    @Volatile private var downloadPct = 0
    private var session: Loaded? = null
    private var sessionStamp = 0L
    @Volatile private var retryAtMs = 0L
    @Volatile private var failCount = 0
    @Volatile private var installedAt = 0L
    @Volatile private var installedVal = false
    private val idleHandler = Handler(Looper.getMainLooper())
    /**
     * ONNX session close is not free (it tears down native arenas; field log: UI FREEZE 4001 ms in
     * release()). It must never run on the main looper. The idle runnable only HANDS release() to a
     * background thread and posts the diagnostic back; the main thread never waits for it.
     */
    private val releaseExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ns-maskmodel-release").apply { isDaemon = true }
        }

    private val idleRelease = Runnable {
        try {
            releaseExecutor.execute {
                try { release() } catch (_: Throwable) {}
                OcrDiag.log("DETECT_SRC", "mask model released (idle, background)")
            }
        } catch (_: Throwable) {
            // Rejected executor (process shutting down): nothing left to free.
        }
    }

    fun modelFile(c: Context): File = File(File(c.applicationContext.filesDir, "models/detection"), "bubble_seg_1024.onnx")

    fun isInstalled(c: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - installedAt < 4000L) return installedVal
        installedVal = modelFile(c).let { it.isFile && it.length() in MIN_BYTES..MAX_BYTES }
        installedAt = now
        return installedVal
    }

    fun isDownloading(): Boolean = downloading.get()
    fun downloadPercent(): Int = downloadPct

    /** "auto" (default: on when installed) | "on" | "off". */
    fun mode(c: Context): String = Prefs.get(c, "wmSegMode", "auto")
    fun setMode(c: Context, m: String) {
        Prefs.put(c, "wmSegMode", m)
        failCount = 0; retryAtMs = 0L
        if (m == "off") release()
    }

    fun enabled(c: Context): Boolean =
        mode(c) != "off" && isInstalled(c) && SystemClock.elapsedRealtime() >= retryAtMs

    fun status(c: Context): String {
        val sb = StringBuilder()
        sb.append("Mask model: ").append(
            when {
                isDownloading() -> "downloading… $downloadPct%"
                isInstalled(c) -> "installed (" + String.format("%.1f", modelFile(c).length() / 1048576.0) + " MB, checked)"
                else -> "not installed (download ≈ 12 MB)"
            }
        ).append('\n')
        sb.append("Mode: ").append(mode(c)).append(" · Right now: ").append(if (enabled(c)) "ACTIVE" else "not active").append('\n')
        sb.append("Last run: ").append(lastInfo)
        if (lastError.isNotBlank()) sb.append("\nLast problem: ").append(lastError)
        return sb.toString()
    }

    fun delete(c: Context) {
        release()
        try { modelFile(c).delete() } catch (_: Throwable) {}
        try { File(modelFile(c).path + ".part").delete() } catch (_: Throwable) {}
        failCount = 0; retryAtMs = 0L; installedAt = 0L; lastError = ""
    }

    @Synchronized
    fun release() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    // ---- download (size window + exact SHA-256 + a real test run before the file is accepted) -------------------

    fun download(c: Context, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        if (!downloading.compareAndSet(false, true)) { onDone("Download is already running"); return }
        val app = c.applicationContext
        Thread {
            var error: String? = null
            var conn: HttpURLConnection? = null
            val target = modelFile(app)
            val part = File(target.path + ".part")
            try {
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create the model folder") }
                part.delete()
                downloadPct = 0
                conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 45000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "NovelStudio-MaskModel/1")
                }
                val code = conn.responseCode
                if (code != 200) throw IllegalStateException("Server answered HTTP $code")
                val total = conn.contentLengthLong
                if (total > 0 && total !in MIN_BYTES..MAX_BYTES) throw IllegalStateException("Unexpected file size ($total bytes)")
                val expected = if (total > 0) total else APPROX_BYTES
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > MAX_BYTES) throw IllegalStateException("File is larger than expected")
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            val pct = (read * 100 / expected).toInt().coerceIn(0, 100)
                            if (pct != downloadPct) { downloadPct = pct; onProgress(pct) }
                        }
                        output.fd.sync()
                    }
                }
                if (read < MIN_BYTES) throw IllegalStateException("Incomplete download ($read bytes)")
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != MODEL_SHA256) throw IllegalStateException("Checksum mismatch — the file is not the expected model")
                val problem = selfTest(part)
                if (problem != null) throw IllegalStateException(problem)
                release()
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    if (target.length() < MIN_BYTES) throw IllegalStateException("Could not save the model")
                }
                failCount = 0; retryAtMs = 0L; installedAt = 0L; lastError = ""
            } catch (e: Throwable) {
                error = (e.message ?: e.javaClass.simpleName).take(200)
                lastError = error
                Log.w(TAG, "download failed: $error")
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
                try { if (part.exists()) part.delete() } catch (_: Throwable) {}
                downloading.set(false)
                onDone(error)
            }
        }.apply { isDaemon = true; name = "ns-maskmodel-download" }.start()
    }

    private fun selfTest(file: File): String? {
        var loaded: Loaded? = null
        return try {
            loaded = Loaded(file)
            val blank = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            blank.eraseColor(0xFFFFFFFF.toInt())
            try { loaded.run(blank) } finally { blank.recycle() }
            null
        } catch (e: Throwable) {
            "Model did not run on this phone: " + (e.message ?: e.javaClass.simpleName).take(140)
        } finally {
            try { loaded?.close() } catch (_: Throwable) {}
        }
    }

    // ---- masks -------------------------------------------------------------------------------------------------

    /**
     * Balloon masks for [bmp] in page pixels, or null when skipped / failed (the reason is in [lastInfo] and the OCR
     * report). Called with OnnxHeavyGate already held by BubbleDetector, so it never runs next to the AI painter.
     */
    @Synchronized
    fun detect(c: Context, bmp: Bitmap): List<BubbleSegCore.Seg>? {
        if (mode(c) == "off") { lastInfo = "off"; return null }
        if (!isInstalled(c)) { lastInfo = "not installed"; return null }
        if (SystemClock.elapsedRealtime() < retryAtMs) { lastInfo = "cooling down after an error"; return null }
        // Memory guard: realtime must never be pushed into an OOM / WebView kill by this optional model.
        val rt = Runtime.getRuntime()
        val freeHeapMb = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / 1048576L
        val am = c.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        try { am?.getMemoryInfo(mi) } catch (_: Throwable) {}
        if (freeHeapMb < MIN_FREE_HEAP_MB || mi.lowMemory) {
            lastInfo = "skipped: low memory (free heap ${freeHeapMb}MB${if (mi.lowMemory) ", system low-memory" else ""})"
            OcrDiag.log("DETECT_SRC", "mask model $lastInfo")
            return null
        }
        return try {
            val file = modelFile(c)
            var s = session
            val t0 = SystemClock.elapsedRealtime()
            if (s == null || sessionStamp != file.lastModified()) {
                try { s?.close() } catch (_: Throwable) {}
                s = Loaded(file); session = s; sessionStamp = file.lastModified()
            }
            val out = s.run(bmp)
            failCount = 0
            lastError = ""
            lastInfo = "${out.size} balloon masks in ${SystemClock.elapsedRealtime() - t0}ms"
            OcrDiag.log("DETECT_SRC", "mask model ran: $lastInfo (free heap ${freeHeapMb}MB)")
            idleHandler.removeCallbacks(idleRelease)
            idleHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
            out
        } catch (e: Throwable) {
            lastError = (e.message ?: e.javaClass.simpleName).take(160)
            if (lastError.contains("recycled", ignoreCase = true)) { lastInfo = "skipped: picture was released"; return null }
            failCount++
            retryAtMs = if (failCount >= 3) Long.MAX_VALUE else SystemClock.elapsedRealtime() + 30_000L * failCount
            lastInfo = "FAILED ($failCount): $lastError"
            OcrDiag.log("DETECT_SRC", "mask model $lastInfo")
            release()
            null
        }
    }

    /** One loaded ONNX session (YOLO11-seg). Names are read from the model, not assumed. */
    private class Loaded(file: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val ses: OrtSession
        private val imageName: String
        private val protoIdx: Int
        private val detIdx: Int
        // Off-heap: the 12 MB input tensor must not count against the app's Java heap.
        private val chw = java.nio.ByteBuffer.allocateDirect(4 * 3 * BubbleSegCore.SIZE * BubbleSegCore.SIZE)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()

        init {
            ses = createSession(file)
            var img: String? = null
            for ((name, info) in ses.inputInfo) {
                val ti = info.info as? TensorInfo ?: continue
                if (ti.shape.size == 4) {
                    img = name
                    val h = ti.shape[2]; val w = ti.shape[3]
                    if (h > 0 && w > 0) require(h.toInt() == BubbleSegCore.SIZE && w.toInt() == BubbleSegCore.SIZE) { "model input is ${w}x$h, expected ${BubbleSegCore.SIZE}" }
                }
            }
            require(img != null) { "model has no [N,3,H,W] image input" }
            imageName = img
            var d = -1; var p = -1
            for ((i, n) in ses.outputNames.toList().withIndex()) {
                val ti = ses.outputInfo[n]?.info as? TensorInfo ?: continue
                if (ti.shape.size == 3) d = i else if (ti.shape.size == 4) p = i
            }
            require(d >= 0 && p >= 0) { "outputs are not (detections, prototypes): ${ses.outputNames.joinToString()}" }
            detIdx = d; protoIdx = p
        }

        private fun createSession(file: File): OrtSession {
            try {
                val o = OrtSession.SessionOptions()
                o.setIntraOpNumThreads(2)
                try { o.setCPUArenaAllocator(false) } catch (_: Throwable) {}
                try { o.setMemoryPatternOptimization(false) } catch (_: Throwable) {}
                return env.createSession(file.absolutePath, o)
            } catch (first: Throwable) {
                try {
                    val o = OrtSession.SessionOptions()
                    o.setIntraOpNumThreads(2)
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    return env.createSession(file.absolutePath, o)
                } catch (_: Throwable) { throw first }
            }
        }

        fun run(bmp: Bitmap): List<BubbleSegCore.Seg> {
            val w = bmp.width; val h = bmp.height
            val lb = BubbleSegCore.letterbox(w, h)
            val scaled = if (lb.newW == w && lb.newH == h) bmp else Bitmap.createScaledBitmap(bmp, lb.newW, lb.newH, true)
            val px = IntArray(lb.newW * lb.newH)
            try { scaled.getPixels(px, 0, lb.newW, 0, 0, lb.newW, lb.newH) } finally { if (scaled !== bmp) scaled.recycle() }
            BubbleSegCore.fillChw(px, lb, chw)
            chw.rewind()
            var tImg: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                tImg = OnnxTensor.createTensor(env, chw, longArrayOf(1, 3, BubbleSegCore.SIZE.toLong(), BubbleSegCore.SIZE.toLong()))
                result = ses.run(java.util.Collections.singletonMap(imageName, tImg))
                val dT = result.get(detIdx) as OnnxTensor
                val pT = result.get(protoIdx) as OnnxTensor
                val dShape = dT.info.shape
                require(dShape.size == 3 && dShape[1] == 37L) { "unexpected detection shape ${dShape.joinToString("x")}" }
                // Read straight from the tensors' buffers: no copy of the 8 MB prototypes on the Java heap.
                return BubbleSegCore.decode(dT.floatBuffer, pT.floatBuffer, dShape[2].toInt(), lb, w, h, 0, 0)
            } finally {
                try { result?.close() } catch (_: Throwable) {}
                try { tImg?.close() } catch (_: Throwable) {}
            }
        }

        override fun close() { try { ses.close() } catch (_: Throwable) {} }
    }
}
