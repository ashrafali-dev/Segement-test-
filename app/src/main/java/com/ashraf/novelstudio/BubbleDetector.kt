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

    /** Pinned to one revision of the repo, so the bytes behind this URL can never change under the hash below. */
    const val MODEL_URL =
        "https://huggingface.co/Sundowner123/comic-text-and-bubble-detector/resolve/0264ac4c32ca4d5668119fe89b54caf3a7946eb3/detector.onnx"
    const val MODEL_SHA256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"
    const val MODEL_BYTES = 11_120_765L
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

    fun modelFile(c: Context): File = File(File(c.applicationContext.filesDir, "models/detection"), "bubble_detector.onnx")

    fun isInstalled(c: Context): Boolean = modelFile(c).let { it.isFile && it.length() == MODEL_BYTES }

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
                isInstalled(c) -> "installed (" + String.format("%.1f", MODEL_BYTES / 1048576.0) + " MB, checked)"
                else -> "not installed (download ≈ 11 MB)"
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
                if (total > 0 && total != MODEL_BYTES) throw IllegalStateException("Unexpected file size ($total bytes)")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > MODEL_BYTES) throw IllegalStateException("File is larger than expected")
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            val pct = (read * 100 / MODEL_BYTES).toInt().coerceIn(0, 100)
                            if (pct != downloadPct) { downloadPct = pct; onProgress(pct) }
                        }
                        output.fd.sync()
                    }
                }
                if (read != MODEL_BYTES) throw IllegalStateException("Incomplete download ($read of $MODEL_BYTES bytes)")
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != MODEL_SHA256) throw IllegalStateException("Checksum mismatch — the file is not the expected model")
                // Prove the model runs on THIS phone before accepting it.
                val problem = selfTest(part)
                if (problem != null) throw IllegalStateException(problem)
                release()
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    if (target.length() != MODEL_BYTES) throw IllegalStateException("Could not save the model")
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
            val blank = IntArray(BubbleDetectorCore.SIZE * BubbleDetectorCore.SIZE) { 0xFFFFFFFF.toInt() }
            loaded.run(blank, intArrayOf(0, 0, 640, 640))   // throws when shapes/types are not as expected
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
            val tiles = BubbleDetectorCore.capTiles(BubbleDetectorCore.planTiles(bmp.width, bmp.height))
            val all = ArrayList<BubbleDetectorCore.Det>()
            val px = IntArray(BubbleDetectorCore.SIZE * BubbleDetectorCore.SIZE)
            for (t in tiles) {
                val part = Bitmap.createBitmap(bmp, t[0], t[1], t[2] - t[0], t[3] - t[1])
                val scaled = Bitmap.createScaledBitmap(part, BubbleDetectorCore.SIZE, BubbleDetectorCore.SIZE, true)
                try {
                    scaled.getPixels(px, 0, BubbleDetectorCore.SIZE, 0, 0, BubbleDetectorCore.SIZE, BubbleDetectorCore.SIZE)
                } finally {
                    if (scaled !== part) scaled.recycle()
                    if (part !== bmp) part.recycle()
                }
                all.addAll(s.run(px, t))
            }
            val out = BubbleDetectorCore.merge(all)
            Log.i(TAG, "tiles=${tiles.size} detections=${out.size} image=${bmp.width}x${bmp.height} ms=${SystemClock.elapsedRealtime() - t0}")
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

    /** One loaded ONNX session. Input/output names and types are read from the model, not assumed. */
    private class Loaded(file: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val ses: OrtSession
        private val imageName: String
        private val sizesName: String?
        private val sizesType: OnnxJavaType
        private val side: Int
        private val labelsIdx: Int
        private val boxesIdx: Int
        private val scoresIdx: Int
        private val chw = FloatArray(3 * BubbleDetectorCore.SIZE * BubbleDetectorCore.SIZE)

        init {
            ses = createSession(file)
            val ins = ses.inputInfo
            var img: String? = null
            var sz: String? = null
            var szType = OnnxJavaType.INT64
            var sd = BubbleDetectorCore.SIZE
            for ((name, info) in ins) {
                val ti = info.info as? TensorInfo ?: continue
                if (ti.shape.size == 4) {
                    img = name
                    val h = ti.shape[2]; val w = ti.shape[3]
                    if (h > 0 && w > 0) {
                        require(h == w) { "unsupported non-square input ${w}x$h" }
                        sd = h.toInt()
                    }
                } else if (ti.shape.size == 2) {
                    sz = name; szType = ti.type
                }
            }
            require(img != null) { "model has no [N,3,H,W] image input" }
            require(sd == BubbleDetectorCore.SIZE) { "model input is ${sd}px, expected ${BubbleDetectorCore.SIZE}px" }
            imageName = img; sizesName = sz; sizesType = szType; side = sd

            // Outputs: by name first, then by shape/type (labels [N,K] int, boxes [N,K,4] float, scores [N,K] float).
            val outInfo = ses.outputInfo
            val names = ses.outputNames.toList()
            var li = -1; var bi = -1; var si = -1
            for ((i, n) in names.withIndex()) {
                val ti = outInfo[n]?.info as? TensorInfo ?: continue
                val ln = n.lowercase()
                when {
                    ti.shape.size == 3 && ti.shape[2] == 4L -> bi = i
                    "label" in ln || "class" in ln -> li = i
                    "score" in ln || "conf" in ln -> si = i
                }
            }
            if (li < 0 || si < 0) {
                for ((i, n) in names.withIndex()) {
                    val ti = outInfo[n]?.info as? TensorInfo ?: continue
                    if (ti.shape.size != 2) continue
                    val isInt = ti.type == OnnxJavaType.INT64 || ti.type == OnnxJavaType.INT32
                    if (isInt && li < 0) li = i else if (!isInt && si < 0) si = i
                }
            }
            require(li >= 0 && bi >= 0 && si >= 0) { "outputs are not labels/boxes/scores (found: ${names.joinToString()})" }
            labelsIdx = li; boxesIdx = bi; scoresIdx = si
        }

        private fun createSession(file: File): OrtSession {
            try {
                val o = OrtSession.SessionOptions()
                o.setIntraOpNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)))
                return env.createSession(file.absolutePath, o)
            } catch (first: Throwable) {
                // Some phones/ORT builds reject an optimisation pass on quantised graphs: retry with optimisations off.
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

        /** Runs one 640x640 ARGB tile that was cut from page window [tile]; returns detections in page pixels. */
        fun run(argb: IntArray, tile: IntArray): List<BubbleDetectorCore.Det> {
            BubbleDetectorCore.fillChw(argb, side, chw)
            var tImg: OnnxTensor? = null
            var tSize: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                tImg = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, side.toLong(), side.toLong()))
                val feed = HashMap<String, OnnxTensor>()
                feed[imageName] = tImg
                if (sizesName != null) {
                    // (640, 640): boxes come back in model space and BubbleDetectorCore.decode scales them to the tile.
                    tSize = if (sizesType == OnnxJavaType.FLOAT)
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(side.toFloat(), side.toFloat())), longArrayOf(1, 2))
                    else
                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(side.toLong(), side.toLong())), longArrayOf(1, 2))
                    feed[sizesName] = tSize
                }
                result = ses.run(feed)
                val labels = toFloats(result.get(labelsIdx) as OnnxTensor)
                val boxes = toFloats(result.get(boxesIdx) as OnnxTensor)
                val scores = toFloats(result.get(scoresIdx) as OnnxTensor)
                require(boxes.size == labels.size * 4 && scores.size == labels.size) {
                    "unexpected output sizes labels=${labels.size} boxes=${boxes.size} scores=${scores.size}"
                }
                return BubbleDetectorCore.decode(labels, boxes, scores, labels.size, tile)
            } finally {
                try { result?.close() } catch (_: Throwable) {}
                try { tImg?.close() } catch (_: Throwable) {}
                try { tSize?.close() } catch (_: Throwable) {}
            }
        }

        private fun toFloats(t: OnnxTensor): FloatArray {
            val ti = t.info
            return when (ti.type) {
                OnnxJavaType.FLOAT -> {
                    val b = t.floatBuffer
                    FloatArray(b.remaining()).also { b.get(it) }
                }
                OnnxJavaType.INT64 -> {
                    val b = t.longBuffer
                    FloatArray(b.remaining()).also { out -> for (i in out.indices) out[i] = b.get(i).toFloat() }
                }
                OnnxJavaType.INT32 -> {
                    val b = t.intBuffer
                    FloatArray(b.remaining()).also { out -> for (i in out.indices) out[i] = b.get(i).toFloat() }
                }
                else -> throw IllegalStateException("unsupported output type ${ti.type}")
            }
        }

        override fun close() {
            try { ses.close() } catch (_: Throwable) {}
        }
    }
}
