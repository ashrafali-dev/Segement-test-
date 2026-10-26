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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optional YOLO11-nano balloon instance-segmentation model, downloaded to app-private storage on request.
 * A pinned revision and SHA-256 protect against changed or corrupt model downloads.
 *
 * This model has one class (balloon); it does not classify SFX, free text, panels, or text-inside-bubble boxes.
 * Compact instance masks are used for point-in-balloon checks; pixel fitting remains available as a fallback.
 */
internal object BubbleDetector {
    private const val TAG = "NsDetector"
    const val MODEL_URL =
        "https://huggingface.co/mednasserallah/manga109-segmentation-bubble-onnx/resolve/6839b4ea9d95be14f5bbd21ac675d92a7da95ed0/manga109_segmentation_bubble_1024.onnx"
    const val MODEL_SHA256 = "593cae61f4c9ffc773fee34d55c28069390d19415c24dc04600f983311618ea3"

    // Koharu candidate path only. Deliberately NOT used by the active downloader/session yet.
    // Its output contract is [1,300,38] + [1,32,256,256], unlike MangaBubbleSegCore's
    // current [1,37,21504] + [1,32,256,256]; swapping MODEL_URL alone would break inference.
    const val KOHARU_MODEL_URL =
        "https://huggingface.co/Liiesl/bubble-segment-onnx/resolve/846d268/koharu-yolo26s-seg.onnx"
    private const val MAX_MODEL_BYTES = 16L * 1024L * 1024L
    private const val MIN_MODEL_BYTES = 10L * 1024L * 1024L
    private const val DISPLAY_MODEL_MB = 11.8
    private const val AUTO_MIN_RAM_MB = 2048L
    private const val MODEL_FILE_NAME = "bubble_detector.onnx"

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastInfo: String = ""
        private set
    private val downloading = AtomicBoolean(false)
    @Volatile private var downloadPct = 0
    @Volatile private var verifiedStamp = Long.MIN_VALUE
    @Volatile private var verifiedInstalled = false
    @Volatile private var broken = false
    private var session: Loaded? = null
    private var sessionStamp = 0L

    fun modelFile(c: Context): File = File(File(c.applicationContext.filesDir, "models/detection"), MODEL_FILE_NAME)

    @Synchronized
    fun isInstalled(c: Context): Boolean {
        val file = modelFile(c)
        if (!file.isFile || file.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            verifiedStamp = Long.MIN_VALUE
            verifiedInstalled = false
            return false
        }
        val stamp = file.lastModified()
        if (stamp == verifiedStamp) return verifiedInstalled
        val valid = try { sha256(file).equals(MODEL_SHA256, ignoreCase = true) } catch (_: Throwable) { false }
        verifiedStamp = stamp
        verifiedInstalled = valid
        if (!valid) lastError = "Model checksum mismatch; download the model again"
        return valid
    }

    fun isDownloading(): Boolean = downloading.get()
    fun downloadPercent(): Int = downloadPct
    fun mode(c: Context): String = Prefs.get(c, "wmDetectorMode", "auto")

    fun setMode(c: Context, m: String) {
        Prefs.put(c, "wmDetectorMode", m)
        broken = false
        if (m == "off") release()
    }

    fun enabled(c: Context): Boolean = disabledReason(c) == null

    fun disabledReason(c: Context): String? {
        if (broken) return "the model failed to load earlier"
        if (!isInstalled(c)) return "model not installed or checksum invalid"
        return when (mode(c)) {
            "on" -> null
            "off" -> "switched off in settings"
            else -> if (DeviceTier.totalRamMb(c) >= AUTO_MIN_RAM_MB) null
                else "auto: needs ${AUTO_MIN_RAM_MB / 1024} GB RAM"
        }
    }

    fun status(c: Context): String {
        val phoneRam = String.format("%.1f", DeviceTier.totalRamMb(c) / 1024.0)
        val state = when {
            isDownloading() -> "downloading… $downloadPct%"
            isInstalled(c) -> "installed (${DISPLAY_MODEL_MB} MB, SHA-256 checked)"
            else -> "not installed (download ≈ ${DISPLAY_MODEL_MB} MB)"
        }
        val sb = StringBuilder()
        sb.append("Model: ").append(state).append('\n')
        sb.append("Mode: ").append(mode(c)).append(" (auto = on with ")
            .append(AUTO_MIN_RAM_MB / 1024.0).append("+ GB RAM; this phone: ").append(phoneRam).append(" GB)\n")
        sb.append("Right now: ").append(if (enabled(c)) "ACTIVE" else "not active — bubble-fit fallback is used")
        if (lastError.isNotBlank()) sb.append("\nLast problem: ").append(lastError)
        return sb.toString()
    }

    fun delete(c: Context) {
        release()
        try { modelFile(c).delete() } catch (_: Throwable) {}
        try { File(modelFile(c).path + ".part").delete() } catch (_: Throwable) {}
        verifiedStamp = Long.MIN_VALUE
        verifiedInstalled = false
        broken = false
        lastError = ""
        lastInfo = ""
    }

    @Synchronized
    fun release() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        sessionStamp = 0L
    }

    fun download(c: Context, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        if (!downloading.compareAndSet(false, true)) {
            onDone("Download is already running")
            return
        }
        val app = c.applicationContext
        Thread {
            var error: String? = null
            var conn: HttpURLConnection? = null
            val target = modelFile(app)
            val part = File(target.path + ".part")
            try {
                target.parentFile?.let {
                    if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create the model folder")
                }
                if (part.exists()) part.delete()
                downloadPct = 0
                conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "NovelStudio-BubbleSeg/1")
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("Server answered HTTP $code")
                val total = conn.contentLengthLong
                if (total > MAX_MODEL_BYTES) throw IllegalStateException("Model file is too large ($total bytes)")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > MAX_MODEL_BYTES) throw IllegalStateException("Model file exceeded 16 MB safety limit")
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 99)
                                else (read * 100 / MAX_MODEL_BYTES).toInt().coerceIn(0, 99)
                            if (pct != downloadPct) {
                                downloadPct = pct
                                onProgress(pct)
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (read < MIN_MODEL_BYTES) throw IllegalStateException("Downloaded model is too small ($read bytes)")
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(MODEL_SHA256, ignoreCase = true)) {
                    throw IllegalStateException("Checksum mismatch — the file is not the expected balloon model")
                }
                selfTest(part)?.let { throw IllegalStateException(it) }

                release()
                if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace previous detector model")
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    if (!sha256(target).equals(MODEL_SHA256, ignoreCase = true)) {
                        throw IllegalStateException("Could not save the verified model")
                    }
                    part.delete()
                }
                verifiedStamp = target.lastModified()
                verifiedInstalled = true
                sessionStamp = 0L
                broken = false
                lastError = ""
                downloadPct = 100
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
        }.apply { isDaemon = true; name = "ns-bubble-seg-download" }.start()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun selfTest(file: File): String? {
        var loaded: Loaded? = null
        return try {
            loaded = Loaded(file)
            val blank = IntArray(MangaBubbleSegCore.SIZE * MangaBubbleSegCore.SIZE) { 0xFF727272.toInt() }
            loaded.run(
                blank, intArrayOf(0, 0, MangaBubbleSegCore.SIZE, MangaBubbleSegCore.SIZE),
                MangaBubbleSegCore.Letterbox(1f, 1f, 0, 0, MangaBubbleSegCore.SIZE, MangaBubbleSegCore.SIZE)
            )
            null
        } catch (e: Throwable) {
            "Model did not run on this phone: ${(e.message ?: e.javaClass.simpleName).take(140)}"
        } finally {
            try { loaded?.close() } catch (_: Throwable) {}
        }
    }

    fun detect(c: Context, bmp: Bitmap): List<BubbleDetectorCore.Det>? {
        if (!enabled(c)) return null
        if (bmp.isRecycled) {
            lastError = "source bitmap was already recycled"
            return null
        }
        OnnxHeavyGate.lock.lock()
        try { return detectLocked(c, bmp) } finally { OnnxHeavyGate.lock.unlock() }
    }

    @Synchronized
    private fun detectLocked(c: Context, bmp: Bitmap): List<BubbleDetectorCore.Det>? {
        if (!enabled(c)) return null
        if (bmp.isRecycled) {
            lastError = "source bitmap was recycled before detection"
            return null
        }
        if (bmp.width < 200 || bmp.height < 200) {
            lastInfo = "skipped (small ${bmp.width}x${bmp.height})"
            return null
        }
        return try {
            val file = modelFile(c)
            var loaded = session
            if (loaded == null || sessionStamp != file.lastModified()) {
                try { loaded?.close() } catch (_: Throwable) {}
                loaded = Loaded(file)
                session = loaded
                sessionStamp = file.lastModified()
            }
            val t0 = SystemClock.elapsedRealtime()
            val tiles = BubbleDetectorCore.capTiles(BubbleDetectorCore.planTiles(bmp.width, bmp.height))
            val all = ArrayList<BubbleDetectorCore.Det>()
            for (tile in tiles) {
                if (bmp.isRecycled) throw IllegalStateException("source bitmap was recycled during detection")
                val prepared = prepareTile(bmp, tile)
                all.addAll(loaded.run(prepared.pixels, tile, prepared.letterbox))
            }
            val out = BubbleDetectorCore.merge(all)
            val elapsed = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "YOLO11-seg tiles=${tiles.size} balloons=${out.size} image=${bmp.width}x${bmp.height} ms=$elapsed")
            lastError = ""
            lastInfo = "${out.size} balloons · ${elapsed}ms"
            out
        } catch (e: OutOfMemoryError) {
            lastError = "Not enough memory for balloon segmentation"
            lastInfo = "memory limit"
            Log.w(TAG, lastError, e)
            broken = false
            release()
            null
        } catch (e: Throwable) {
            lastError = (e.message ?: e.javaClass.simpleName).take(160)
            lastInfo = "failed"
            Log.w(TAG, "balloon segmentation failed: $lastError")
            broken = true
            release()
            null
        }
    }

    private data class PreparedTile(val pixels: IntArray, val letterbox: MangaBubbleSegCore.Letterbox)

    private fun prepareTile(source: Bitmap, tile: IntArray): PreparedTile {
        check(!source.isRecycled) { "source bitmap was recycled before createBitmap" }
        val x = tile[0].coerceIn(0, source.width - 1)
        val y = tile[1].coerceIn(0, source.height - 1)
        val w = (tile[2] - x).coerceIn(1, source.width - x)
        val h = (tile[3] - y).coerceIn(1, source.height - y)
        val nominal = min(MangaBubbleSegCore.SIZE.toFloat() / w, MangaBubbleSegCore.SIZE.toFloat() / h)
        val resizedW = (w * nominal).roundToInt().coerceIn(1, MangaBubbleSegCore.SIZE)
        val resizedH = (h * nominal).roundToInt().coerceIn(1, MangaBubbleSegCore.SIZE)
        val scaleX = resizedW.toFloat() / w
        val scaleY = resizedH.toFloat() / h
        val padX = (MangaBubbleSegCore.SIZE - resizedW) / 2
        val padY = (MangaBubbleSegCore.SIZE - resizedH) / 2

        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            crop = Bitmap.createBitmap(source, x, y, w, h)
            scaled = Bitmap.createScaledBitmap(crop, resizedW, resizedH, true)
            val canvas = IntArray(MangaBubbleSegCore.SIZE * MangaBubbleSegCore.SIZE) { 0xFF727272.toInt() }
            scaled.getPixels(canvas, padY * MangaBubbleSegCore.SIZE + padX, MangaBubbleSegCore.SIZE, 0, 0, resizedW, resizedH)
            return PreparedTile(
                canvas, MangaBubbleSegCore.Letterbox(scaleX, scaleY, padX, padY, resizedW, resizedH)
            )
        } finally {
            val sc = scaled
            val cp = crop
            if (sc != null && sc !== cp && sc !== source && !sc.isRecycled) {
                try { sc.recycle() } catch (_: Throwable) {}
            }
            if (cp != null && cp !== source && !cp.isRecycled) {
                try { cp.recycle() } catch (_: Throwable) {}
            }
        }
    }

    private class Loaded(file: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val ses: OrtSession
        private val imageName: String
        private val predictionName: String
        private val prototypeName: String
        private val chw = FloatArray(3 * MangaBubbleSegCore.SIZE * MangaBubbleSegCore.SIZE)

        init {
            ses = createSession(file)
            try {
                var img: String? = null
                for ((name, info) in ses.inputInfo) {
                    val ti = info.info as? TensorInfo ?: continue
                    if (ti.shape.size == 4 && ti.shape[1] == 3L &&
                        ti.shape[2] == MangaBubbleSegCore.SIZE.toLong() &&
                        ti.shape[3] == MangaBubbleSegCore.SIZE.toLong()) {
                        img = name
                        break
                    }
                }
                require(img != null) { "model input is not [1,3,1024,1024]" }
                imageName = img

                var pred: String? = null
                var proto: String? = null
                for ((name, info) in ses.outputInfo) {
                    val ti = info.info as? TensorInfo ?: continue
                    val shape = ti.shape
                    if (shape.size == 3 && shape[1] == MangaBubbleSegCore.CHANNELS.toLong() &&
                        shape[2] == MangaBubbleSegCore.ANCHORS.toLong()) pred = name
                    if (shape.size == 4 && shape[1] == MangaBubbleSegCore.PROTO_CHANNELS.toLong() &&
                        shape[2] == MangaBubbleSegCore.PROTO_SIZE.toLong() &&
                        shape[3] == MangaBubbleSegCore.PROTO_SIZE.toLong()) proto = name
                }
                require(pred != null && proto != null) {
                    "model outputs do not match YOLO segmentation contract (found ${ses.outputInfo.keys})"
                }
                predictionName = pred
                prototypeName = proto
            } catch (e: Throwable) {
                try { ses.close() } catch (_: Throwable) {}
                throw e
            }
        }

        private fun createSession(file: File): OrtSession {
            try {
                val options = OrtSession.SessionOptions()
                options.setIntraOpNumThreads(2)
                return env.createSession(file.absolutePath, options)
            } catch (first: Throwable) {
                try {
                    val options = OrtSession.SessionOptions()
                    options.setIntraOpNumThreads(1)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    return env.createSession(file.absolutePath, options)
                } catch (_: Throwable) {
                    throw first
                }
            }
        }

        fun run(argb: IntArray, tile: IntArray, letterbox: MangaBubbleSegCore.Letterbox): List<BubbleDetectorCore.Det> {
            check(argb.size >= MangaBubbleSegCore.SIZE * MangaBubbleSegCore.SIZE) { "letterboxed canvas is too small" }
            BubbleDetectorCore.fillChw(argb, MangaBubbleSegCore.SIZE, chw)
            var input: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                input = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(chw),
                    longArrayOf(1, 3, MangaBubbleSegCore.SIZE.toLong(), MangaBubbleSegCore.SIZE.toLong())
                )
                result = ses.run(mapOf(imageName to input))
                // Result.get(name) returns Optional<OnnxValue>; casting the Optional itself always fails.
                val predValue = result.get(predictionName).orElse(null)
                val pred = predValue as? OnnxTensor
                    ?: throw IllegalStateException("prediction output is not a tensor")
                val protoValue = result.get(prototypeName).orElse(null)
                val proto = protoValue as? OnnxTensor
                    ?: throw IllegalStateException("prototype output is not a tensor")
                return MangaBubbleSegCore.decode(toFloats(pred), toFloats(proto), tile, letterbox)
            } finally {
                try { result?.close() } catch (_: Throwable) {}
                try { input?.close() } catch (_: Throwable) {}
            }
        }

        private fun toFloats(tensor: OnnxTensor): FloatArray {
            require(tensor.info.type == OnnxJavaType.FLOAT) { "expected float output, got ${tensor.info.type}" }
            val buffer = tensor.floatBuffer
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }

        override fun close() {
            try { ses.close() } catch (_: Throwable) {}
        }
    }
}
