package com.ashraf.novelstudio

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OPTIONAL speech-bubble / lettering detector (ONNX Runtime, 11 MB). It finds three kinds of boxes on a page:
 * `bubble`, `text_bubble` (lettering inside a bubble) and `text_free` (SFX / narration on artwork). The extractor uses
 * them to (1) never join lines of two different bubbles into one sentence and (2) know which lettering is free text.
 *
 * Model: Sundowner123/comic-text-and-bubble-detector, detector.onnx = int8 copy of ogkalu/comic-text-and-bubble-detector
 * (RT-DETR-v2). Apache-2.0, see THIRD_PARTY_LICENSES/BUBBLE_DETECTOR_NOTICE.md. Nothing is bundled in the APK: the model
 * is downloaded on request into app-private storage and ONLY accepted after
 *   1. the exact size and SHA-256 match, and
 *   2. ONNX Runtime really loads it on this phone and a test run on a blank picture returns the expected outputs.
 * A file that fails any of these is deleted and the reason is shown in the menu. Every runtime failure returns null, and
 * the app then simply keeps using the pixel-based bubble finder.
 */
internal object BubbleDetector {
    private const val TAG = "NsDetector"

    /**
     * YOLO11n-seg speech-bubble model (ONNX export of huyvux3005/manga109-segmentation-bubble by mednasserallah).
     * Apache-2.0 weights; the training data (MS92/MangaSegmentation, Manga109) has its own terms, see NOTICE.txt.
     * The file is only accepted when its SHA-256 matches, so the (unpinned) branch URL cannot change the bytes.
     */
    const val MODEL_URL =
        "https://huggingface.co/mednasserallah/manga109-segmentation-bubble-onnx/resolve/main/manga109_segmentation_bubble_1024.onnx"
    const val MODEL_SHA256 = "593cae61f4c9ffc773fee34d55c28069390d19415c24dc04600f983311618ea3"
    private const val MIN_BYTES = 5_000_000L
    private const val MAX_BYTES = 40_000_000L
    private const val APPROX_BYTES = 12_400_000L
    private const val AUTO_MIN_RAM_MB = 3500L

    @Volatile var lastError: String = ""
        private set

    /** Result of the latest run, shown in the status pill ("6 found · 840ms"). */
    @Volatile var lastInfo: String = ""
        private set

    private val downloading = AtomicBoolean(false)
    @Volatile private var downloadPct = 0
    private var session: Loaded? = null
    private var sessionStamp = 0L
    /** Set after a load/run failure so a broken model is not retried on every page; cleared on download/delete/mode change. */
    @Volatile private var broken = false

    fun modelFile(c: Context): File = File(File(c.applicationContext.filesDir, "models/detection"), "bubble_seg_1024.onnx")

    fun isInstalled(c: Context): Boolean = modelFile(c).let { it.isFile && it.length() in MIN_BYTES..MAX_BYTES }

    fun isDownloading(): Boolean = downloading.get()
    fun downloadPercent(): Int = downloadPct

    /** "auto" (default: on when the model is installed and the phone has enough RAM) | "on" | "off". */
    fun mode(c: Context): String = Prefs.get(c, "wmDetectorMode", "auto")

    fun setMode(c: Context, m: String) {
        Prefs.put(c, "wmDetectorMode", m)
        broken = false
        if (m == "off") release()
    }

    fun enabled(c: Context): Boolean {
        if (broken || !isInstalled(c)) return false
        return when (mode(c)) {
            "on" -> true
            "off" -> false
            else -> DeviceTier.totalRamMb(c) >= AUTO_MIN_RAM_MB
        }
    }

    fun status(c: Context): String {
        val sb = StringBuilder()
        sb.append("Model: ").append(
            when {
                isDownloading() -> "downloading… $downloadPct%"
                isInstalled(c) -> "installed (" + String.format("%.1f", modelFile(c).length() / 1048576.0) + " MB, segmentation, checked)"
                else -> "not installed (download ≈ 12 MB)"
            }
        ).append('\n')
        sb.append("Mode: ").append(mode(c)).append(" (auto = on with ").append(AUTO_MIN_RAM_MB / 1024.0)
            .append("+ GB RAM; this phone: ").append(String.format("%.1f", DeviceTier.totalRamMb(c) / 1024.0)).append(" GB)\n")
        sb.append("Right now: ").append(if (enabled(c)) "ACTIVE" else "not active — the pixel-based bubble finder is used")
        if (lastError.isNotBlank()) sb.append("\nLast problem: ").append(lastError)
        return sb.toString()
    }

    fun delete(c: Context) {
        release()
        try { modelFile(c).delete() } catch (_: Throwable) {}
        try { File(modelFile(c).path + ".part").delete() } catch (_: Throwable) {}
        broken = false
        lastError = ""
    }

    @Synchronized
    fun release() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    // ---- download ----------------------------------------------------------------------------------------------

    /** Downloads on a background thread. [onDone] gets null on success, otherwise a short, human-readable reason. */
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
                    setRequestProperty("User-Agent", "NovelStudio-Detector/1")
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
                // Prove the model runs on THIS phone before accepting it.
                val problem = selfTest(part)
                if (problem != null) throw IllegalStateException(problem)
                release()
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    if (target.length() < MIN_BYTES) throw IllegalStateException("Could not save the model")
                }
                broken = false
                lastError = ""
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
        }.apply { isDaemon = true; name = "ns-detector-download" }.start()
    }

    /** Loads [file] and runs it once on a blank picture. Returns null when everything is as expected, else a reason. */
    private fun selfTest(file: File): String? {
        var loaded: Loaded? = null
        return try {
            loaded = Loaded(file)
            val blank = IntArray(256 * 256) { 0xFFFFFFFF.toInt() }
            loaded.run(blank, 256, 256, 0, 0)   // throws when shapes/types are not as expected
            null
        } catch (e: Throwable) {
            "Model did not run on this phone: " + (e.message ?: e.javaClass.simpleName).take(140)
        } finally {
            try { loaded?.close() } catch (_: Throwable) {}
        }
    }

    // ---- detection ---------------------------------------------------------------------------------------------

    /** Detections for [bmp] in page pixels, or null when the detector is off/unavailable. Never throws. */
    fun detect(c: Context, bmp: Bitmap): List<BubbleDetectorCore.Det>? {
        if (!enabled(c)) return null
        OnnxHeavyGate.lock.lock()
        try { return detectLocked(c, bmp) } finally { OnnxHeavyGate.lock.unlock() }
    }

    @Synchronized
    private fun detectLocked(c: Context, bmp: Bitmap): List<BubbleDetectorCore.Det>? {
        if (!enabled(c)) return null
        // Speech bubbles do not live in banners, thumbnails or thin strips; one model run costs ~1.9 s on a mid-range phone.
        if (bmp.width < 200 || bmp.height < 200 || bmp.width > bmp.height * 2.2f) {
            lastInfo = "skipped (small/banner ${bmp.width}x${bmp.height})"
            return null
        }
        return try {
            val file = modelFile(c)
            var s = session
            if (s == null || sessionStamp != file.lastModified()) {
                try { s?.close() } catch (_: Throwable) {}
                s = Loaded(file)
                session = s
                sessionStamp = file.lastModified()
            }
            val t0 = SystemClock.elapsedRealtime()
            // One letter-boxed run over the whole slice: a webtoon slice keeps its aspect ratio, nothing is stretched,
            // and one run replaces the old multi-tile loop.
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val out = s.run(px, bmp.width, bmp.height, 0, 0)
            Log.i(TAG, "balloons=${out.size} image=${bmp.width}x${bmp.height} ms=${SystemClock.elapsedRealtime() - t0}")
            lastError = ""
            lastInfo = "${out.size} found · ${SystemClock.elapsedRealtime() - t0}ms"
            out
        } catch (e: Throwable) {
            lastError = (e.message ?: e.javaClass.simpleName).take(160)
            Log.w(TAG, "detector failed: $lastError")
            broken = true
            release()
            null
        }
    }

    /** One loaded ONNX session (YOLO11-seg). Input/output names are read from the model, not assumed. */
    private class Loaded(file: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val ses: OrtSession
        private val imageName: String
        private val protoIdx: Int
        private val detIdx: Int
        private val chw = FloatArray(3 * BubbleSegCore.SIZE * BubbleSegCore.SIZE)

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
            // output0 = rank 3 (1, 37, anchors); output1 = rank 4 (1, 32, 256, 256)
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
                o.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)))
                return env.createSession(file.absolutePath, o)
            } catch (first: Throwable) {
                try {
                    val o = OrtSession.SessionOptions()
                    o.setIntraOpNumThreads(2)
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    return env.createSession(file.absolutePath, o)
                } catch (_: Throwable) {
                    throw first
                }
            }
        }

        /** Runs the letter-boxed page [argb] (w x h); returns balloons in page pixels (+ origin). */
        fun run(argb: IntArray, w: Int, h: Int, originX: Int, originY: Int): List<BubbleDetectorCore.Det> {
            val lb = BubbleSegCore.letterbox(w, h)
            BubbleSegCore.fillChw(argb, w, h, lb, chw)
            var tImg: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                tImg = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, BubbleSegCore.SIZE.toLong(), BubbleSegCore.SIZE.toLong()))
                result = ses.run(java.util.Collections.singletonMap(imageName, tImg))
                val dT = result.get(detIdx) as OnnxTensor
                val pT = result.get(protoIdx) as OnnxTensor
                val dShape = dT.info.shape
                require(dShape.size == 3 && dShape[1] == 37L) { "unexpected detection shape ${dShape.joinToString("x")}" }
                val anchors = dShape[2].toInt()
                val db = dT.floatBuffer; val pb = pT.floatBuffer
                val d = FloatArray(db.remaining()).also { db.get(it) }
                val p = FloatArray(pb.remaining()).also { pb.get(it) }
                return BubbleSegCore.decode(d, p, anchors, lb, w, h, originX, originY).map {
                    BubbleDetectorCore.Det(BubbleDetectorCore.CLS_BUBBLE, it.score, it.l, it.t, it.r, it.b, it)
                }
            } finally {
                try { result?.close() } catch (_: Throwable) {}
                try { tImg?.close() } catch (_: Throwable) {}
            }
        }

        override fun close() {
            try { ses.close() } catch (_: Throwable) {}
        }
    }
}
