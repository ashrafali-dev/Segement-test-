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
 * Koharu YOLO26s instance-segmentation ONNX model, downloaded to app-private storage on request.
 * The pinned dynamic-shape export emits four classes and prototype masks. Input size is reduced on low-memory
 * phones to control activation RAM; download size and SHA-256 are validated before the model is activated.
 */
internal object BubbleDetector {
    private const val TAG = "NsDetector"
    const val MODEL_URL =
        "https://huggingface.co/ShiniShiho/koharu-yolo26s-onnx/resolve/e8d0110da6281b93ee6f6509c741931c49ace9fe/koharu-yolo26s.onnx"
    const val MODEL_SHA256 = "25a0b06c4ce7ee7c247068a6b3633f98d0f5e913c26ef1cbd06bec3407e914f4"
    const val MODEL_BYTES = 41_604_574L
    private const val MAX_MODEL_BYTES = 64L * 1024L * 1024L
    private const val MIN_MODEL_BYTES = 39L * 1024L * 1024L
    private const val DISPLAY_MODEL_MB = 41.6
    private const val AUTO_MIN_RAM_MB = 2048L
    private const val MODEL_FILE_NAME = "koharu_yolo26s.onnx"
    private const val SELF_TEST_INPUT_SIZE = 256
    private const val LOW_MEMORY_INPUT_SIZE = 512
    private const val MID_MEMORY_INPUT_SIZE = 640
    private const val HIGH_MEMORY_INPUT_SIZE = 768
    private const val EMERGENCY_INPUT_SIZE = 320
    private const val PRESSURE_INPUT_SIZE = 256

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
    @Volatile private var forceLowMemoryUntilMs = 0L
    @Volatile private var pressureInputSize = PRESSURE_INPUT_SIZE
    @Volatile private var trimReleaseQueued = false

    fun modelFile(c: Context): File = File(File(c.applicationContext.filesDir, "models/detection"), MODEL_FILE_NAME)
    private fun legacyModelFile(c: Context): File =
        File(File(c.applicationContext.filesDir, "models/detection"), "bubble_detector.onnx")

    @Synchronized
    fun isInstalled(c: Context): Boolean {
        val file = modelFile(c)
        if (!file.isFile || file.length() != MODEL_BYTES || file.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
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

    /**
     * Koharu's activation memory scales with the square of the input size. Keep 4 GB phones on 512,
     * scale up only when both total and currently available RAM give it room.
     */
    private data class RamSnapshot(
        val totalMb: Long,
        val availableBytes: Long,
        val thresholdBytes: Long,
        val lowMemory: Boolean,
        val lowRamDevice: Boolean
    )

    private fun ramSnapshot(c: Context): RamSnapshot {
        val totalMb = DeviceTier.totalRamMb(c)
        return try {
            val manager = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            RamSnapshot(totalMb, info.availMem, info.threshold, info.lowMemory, manager.isLowRamDevice)
        } catch (_: Throwable) {
            RamSnapshot(totalMb, 0L, 0L, true, totalMb in 1..4600L)
        }
    }

    /**
     * The A13-class phone keeps the browser/WebView and OCR alive beside ONNX. On low-RAM devices,
     * smaller inputs and an unoptimised single-thread CPU session avoid a second activation-memory spike.
     */
    private fun recommendedInputSize(c: Context): Int {
        if (SystemClock.elapsedRealtime() < forceLowMemoryUntilMs) return pressureInputSize
        val ram = ramSnapshot(c)
        val lowHardware = ram.lowRamDevice || ram.totalMb in 1..4600L
        if (lowHardware) {
            return when {
                ram.lowMemory || ram.availableBytes < 192L * 1024L * 1024L -> SELF_TEST_INPUT_SIZE
                ram.availableBytes < 384L * 1024L * 1024L -> EMERGENCY_INPUT_SIZE
                else -> LOW_MEMORY_INPUT_SIZE.coerceAtMost(384)
            }
        }
        return when {
            ram.lowMemory || ram.availableBytes < 256L * 1024L * 1024L -> EMERGENCY_INPUT_SIZE
            ram.totalMb >= 8192L && ram.availableBytes >= 800L * 1024L * 1024L -> HIGH_MEMORY_INPUT_SIZE
            ram.totalMb >= 6144L && ram.availableBytes >= 512L * 1024L * 1024L -> MID_MEMORY_INPUT_SIZE
            ram.availableBytes >= 320L * 1024L * 1024L -> LOW_MEMORY_INPUT_SIZE
            ram.availableBytes >= 192L * 1024L * 1024L -> 384
            else -> SELF_TEST_INPUT_SIZE
        }
    }

    private fun shouldUseLowMemorySession(c: Context): Boolean {
        val ram = ramSnapshot(c)
        return SystemClock.elapsedRealtime() < forceLowMemoryUntilMs ||
            ram.lowRamDevice || ram.totalMb in 1..4600L || ram.lowMemory ||
            ram.availableBytes < 512L * 1024L * 1024L
    }

    /**
     * Android calls this on the UI thread under global memory pressure. Never wait here: force a small
     * next inference immediately and release native ONNX session memory on a background thread after any
     * current inference leaves the process-wide gate.
     */
    fun onMemoryPressure(level: Int) {
        val now = SystemClock.elapsedRealtime()
        // RUNNING_LOW (10) is common on the A13 while WebView is open. Forcing 256px for every
        // low-pressure callback makes Japanese vertical text too tiny for useful segmentation.
        // Use a stride-aligned 320px input at level 10; reserve 256px for CRITICAL (15+) pressure.
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                pressureInputSize = PRESSURE_INPUT_SIZE
                forceLowMemoryUntilMs = now + 60_000L
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                pressureInputSize = 320
                forceLowMemoryUntilMs = now + 30_000L
            }
            else -> return
        }
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || trimReleaseQueued) return
        trimReleaseQueued = true
        Thread({
            try {
                OnnxHeavyGate.lock.lock()
                try { release() } finally { OnnxHeavyGate.lock.unlock() }
            } catch (t: Throwable) {
                Log.w(TAG, "session trim failed after memory pressure", t)
            } finally {
                trimReleaseQueued = false
            }
        }, "ns-koharu-memory-trim").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
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
            isDownloading() && downloadPercent() >= 100 -> "download complete · validating at low memory…"
            isDownloading() -> "downloading… $downloadPct%"
            isInstalled(c) -> "installed (${DISPLAY_MODEL_MB} MB, SHA-256 checked)"
            else -> "not installed (download ≈ ${DISPLAY_MODEL_MB} MB)"
        }
        val sb = StringBuilder()
        sb.append("Model: ").append(state).append('\n')
        sb.append("Mode: ").append(mode(c)).append(" (auto = on with ")
            .append(AUTO_MIN_RAM_MB / 1024.0).append("+ GB RAM; this phone: ").append(phoneRam).append(" GB)\n")
        sb.append("Input: ").append(recommendedInputSize(c)).append("px (adaptive to available RAM)\n")
        sb.append("Classes: frame · dialogue_text · balloon · onomatopoeia_text\n")
        sb.append("Right now: ").append(if (enabled(c)) "ACTIVE" else "not active — bubble-fit fallback is used")
        if (lastError.isNotBlank()) sb.append("\nLast problem: ").append(lastError)
        return sb.toString()
    }

    fun delete(c: Context) {
        release()
        try { modelFile(c).delete() } catch (_: Throwable) {}
        try { File(modelFile(c).path + ".part").delete() } catch (_: Throwable) {}
        try { File(modelFile(c).path + ".part.meta").delete() } catch (_: Throwable) {}
        try { legacyModelFile(c).delete() } catch (_: Throwable) {}
        try { File(legacyModelFile(c).path + ".part").delete() } catch (_: Throwable) {}
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
            val partMeta = File(part.path + ".meta")
            // Bind resumable bytes to this exact model revision. A previous Koharu export may have
            // used the same .part filename but a different URL/hash/size; never concatenate those bytes.
            val thisModelStamp = "$MODEL_URL\n$MODEL_SHA256\n$MODEL_BYTES"
            var keepPartial = true
            try {
                target.parentFile?.let {
                    if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create the model folder")
                }

                // Retain partial bytes after a transient interruption. If Android kills the process
                // while validating a complete file, verify and reuse that file on the next attempt.
                var existingBytes = if (part.isFile) part.length() else 0L
                if (existingBytes < 0L || existingBytes > MODEL_BYTES) {
                    try { part.delete() } catch (_: Throwable) {}
                    existingBytes = 0L
                }
                if (existingBytes == MODEL_BYTES &&
                    !sha256(part).equals(MODEL_SHA256, ignoreCase = true)) {
                    try { part.delete() } catch (_: Throwable) {}
                    try { partMeta.delete() } catch (_: Throwable) {}
                    existingBytes = 0L
                }
                if (existingBytes in 1 until MODEL_BYTES) {
                    val oldStamp = try { if (partMeta.isFile) partMeta.readText(Charsets.UTF_8) else "" }
                        catch (_: Throwable) { "" }
                    if (oldStamp != thisModelStamp) {
                        Log.i(TAG, "Discarding Koharu partial from an older/different model revision")
                        try { part.delete() } catch (_: Throwable) {}
                        try { partMeta.delete() } catch (_: Throwable) {}
                        existingBytes = 0L
                    }
                }
                if (existingBytes < MODEL_BYTES) {
                    // Persist source identity before any bytes are appended; after a process kill this
                    // lets the next attempt safely resume only the same model artifact.
                    partMeta.writeText(thisModelStamp, Charsets.UTF_8)
                }
                Log.i(TAG, "Koharu download start: resumableBytes=$existingBytes of $MODEL_BYTES")

                if (existingBytes < MODEL_BYTES) {
                    val remainingBytes = MODEL_BYTES - existingBytes
                    val freeBytes = android.os.StatFs(
                        target.parentFile?.absolutePath ?: app.filesDir.absolutePath
                    ).availableBytes
                    if (freeBytes < remainingBytes + 2L * 1024L * 1024L) {
                        throw IllegalStateException(
                            "Need ${(remainingBytes + 2L * 1024L * 1024L) / (1024 * 1024)} MB free to resume Koharu; " +
                                "available ${freeBytes / (1024 * 1024)} MB"
                        )
                    }

                    downloadPct = ((existingBytes * 100L) / MODEL_BYTES).toInt().coerceIn(0, 99)
                    onProgress(downloadPct)
                    conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 180000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "NovelStudio-Koharu/1")
                        if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                    val code = conn.responseCode
                    var append = existingBytes > 0L && code == HttpURLConnection.HTTP_PARTIAL
                    if (existingBytes == 0L && code != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("Server answered HTTP $code")
                    }
                    if (existingBytes > 0L && code != HttpURLConnection.HTTP_PARTIAL &&
                        code != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("Resume server answered HTTP $code")
                    }
                    // If a CDN ignores Range and responds 200, restart at byte zero, not append.
                    if (existingBytes > 0L && code == HttpURLConnection.HTTP_OK) {
                        append = false
                        existingBytes = 0L
                    }
                    val expectedResponseBytes = MODEL_BYTES - existingBytes
                    val responseBytes = conn.contentLengthLong
                    if (responseBytes > 0L && responseBytes != expectedResponseBytes) {
                        keepPartial = false
                        throw IllegalStateException(
                            "Unexpected Koharu response size: $responseBytes bytes; expected $expectedResponseBytes"
                        )
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    if (append && existingBytes > 0L) {
                        BufferedInputStream(FileInputStream(part)).use { old ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = old.read(buf)
                                if (n < 0) break
                                digest.update(buf, 0, n)
                            }
                        }
                    }
                    var receivedThisRequest = 0L
                    BufferedInputStream(conn.inputStream).use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                receivedThisRequest += n
                                val absoluteRead = existingBytes + receivedThisRequest
                                if (absoluteRead > MODEL_BYTES || absoluteRead > MAX_MODEL_BYTES) {
                                    keepPartial = false
                                    throw IllegalStateException("Koharu download exceeded the expected 41.6 MB size")
                                }
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                val pct = (absoluteRead * 100L / MODEL_BYTES).toInt().coerceIn(0, 99)
                                if (pct != downloadPct) {
                                    downloadPct = pct
                                    onProgress(pct)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (part.length() != MODEL_BYTES) {
                        throw IllegalStateException(
                            "Download interrupted at ${part.length()} / $MODEL_BYTES bytes. Retry to resume."
                        )
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!hex.equals(MODEL_SHA256, ignoreCase = true)) {
                        keepPartial = false
                        throw IllegalStateException("Koharu SHA-256 mismatch — refusing to activate this file")
                    }
                }

                Log.i(TAG, "Koharu bytes/checksum verified: ${part.length()} bytes; starting low-memory self-test")
                // Previously 75% was the last visible toast: inference/validation ran after the last
                // downloaded byte without any visible state. Now the UI can show that validation starts.
                downloadPct = 100
                onProgress(100)
                selfTest(part)?.let { throw IllegalStateException(it) }
                Log.i(TAG, "Koharu low-memory self-test passed")

                release()
                if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace previous detector model")
                if (!part.renameTo(target)) {
                    if (part.length() != MODEL_BYTES ||
                        !sha256(part).equals(MODEL_SHA256, ignoreCase = true)) {
                        throw IllegalStateException("Verified Koharu file was lost before installation")
                    }
                    throw IllegalStateException("Could not atomically install Koharu; verified download kept for retry")
                }
                keepPartial = false
                try { legacyModelFile(app).delete() } catch (_: Throwable) {}
                try { File(legacyModelFile(app).path + ".part").delete() } catch (_: Throwable) {}
                verifiedStamp = target.lastModified()
                verifiedInstalled = true
                sessionStamp = 0L
                broken = false
                lastError = ""
                downloadPct = 100
            } catch (e: Throwable) {
                error = (e.message ?: e.javaClass.simpleName).take(200)
                lastError = error
                Log.w(TAG, "Koharu download/install failed: $error", e)
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
                if (!keepPartial) {
                    try { if (part.exists()) part.delete() } catch (_: Throwable) {}
                    try { if (partMeta.exists()) partMeta.delete() } catch (_: Throwable) {}
                }
                downloading.set(false)
                try { onDone(error) } catch (callbackError: Throwable) {
                    Log.w(TAG, "Koharu completion callback failed", callbackError)
                }
            }
        }.apply { isDaemon = true; name = "ns-koharu-download" }.start()
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
            // Validate the graph at the lowest supported dynamic input. Reader WebViews are still
            // resident during download, so 512px inference can spike native RAM on 4 GB devices.
            val size = SELF_TEST_INPUT_SIZE
            loaded = Loaded(file, lowMemorySession = true)
            val blank = IntArray(size * size) { 0xFF727272.toInt() }
            loaded.run(
                blank, intArrayOf(0, 0, size, size),
                KoharuSegCore.Letterbox(1f, 1f, 0, 0, size, size), size, size
            )
            null
        } catch (e: Throwable) {
            "Koharu validation failed: ${(e.message ?: e.javaClass.simpleName).take(140)}"
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
                loaded = Loaded(file, lowMemorySession = shouldUseLowMemorySession(c))
                session = loaded
                sessionStamp = file.lastModified()
            }
            val t0 = SystemClock.elapsedRealtime()
            val tiles = BubbleDetectorCore.capTiles(BubbleDetectorCore.planTiles(bmp.width, bmp.height))
            var inputSize = recommendedInputSize(c)
            val all: List<BubbleDetectorCore.Det> = try {
                inferTiles(loaded, bmp, tiles, inputSize)
            } catch (oom: OutOfMemoryError) {
                // Dynamic Koharu supports smaller stride-aligned inputs. Retry the full page at a lower
                // resolution after releasing the first session instead of permanently failing this scan.
                val retrySize = when {
                    inputSize > LOW_MEMORY_INPUT_SIZE -> LOW_MEMORY_INPUT_SIZE
                    inputSize > EMERGENCY_INPUT_SIZE -> EMERGENCY_INPUT_SIZE
                    else -> throw oom
                }
                lastInfo = "retrying at ${retrySize}px after memory pressure"
                Log.w(TAG, "Koharu OOM at ${inputSize}px; retrying at ${retrySize}px")
                release()
                session = null
                sessionStamp = 0L
                System.gc()
                val retryLoaded = Loaded(file, lowMemorySession = true)
                session = retryLoaded
                sessionStamp = file.lastModified()
                inputSize = retrySize
                inferTiles(retryLoaded, bmp, tiles, inputSize)
            }
            val out = BubbleDetectorCore.merge(all)
            val elapsed = SystemClock.elapsedRealtime() - t0
            val balloons = out.count { it.label == BubbleDetectorCore.CLS_BUBBLE }
            val textRegions = out.count { it.label == BubbleDetectorCore.CLS_TEXT_BUBBLE }
            val freeText = out.count { it.label == BubbleDetectorCore.CLS_TEXT_FREE }
            Log.i(TAG, "Koharu tiles=${tiles.size} input=${inputSize} regions=${out.size} balloons=$balloons dialogueText=$textRegions sfx=$freeText image=${bmp.width}x${bmp.height} ms=$elapsed")
            lastError = ""
            lastInfo = "$balloons balloons · ${textRegions + freeText} text regions · ${elapsed}ms @${inputSize}px"
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

    private fun inferTiles(
        loaded: Loaded,
        bmp: Bitmap,
        tiles: List<IntArray>,
        inputSize: Int
    ): List<BubbleDetectorCore.Det> {
        val all = ArrayList<BubbleDetectorCore.Det>()
        for (tile in tiles) {
            if (bmp.isRecycled) throw IllegalStateException("source bitmap was recycled during detection")
            val prepared = prepareTile(bmp, tile, inputSize)
            all.addAll(loaded.run(
                prepared.pixels, tile, prepared.letterbox, prepared.inputWidth, prepared.inputHeight
            ))
        }
        return all
    }

    private data class PreparedTile(
        val pixels: IntArray,
        val inputWidth: Int,
        val inputHeight: Int,
        val letterbox: KoharuSegCore.Letterbox
    )

    /**
     * Preserve the tile aspect ratio and round only the padded canvas dimensions to stride 32. Koharu's
     * dynamic ONNX accepts rectangular tensors; this avoids wasting memory on large square side bars.
     */
    private fun prepareTile(source: Bitmap, tile: IntArray, longSide: Int): PreparedTile {
        check(!source.isRecycled) { "source bitmap was recycled before createBitmap" }
        val x = tile[0].coerceIn(0, source.width - 1)
        val y = tile[1].coerceIn(0, source.height - 1)
        val w = (tile[2] - x).coerceIn(1, source.width - x)
        val h = (tile[3] - y).coerceIn(1, source.height - y)
        val nominal = min(longSide.toFloat() / w, longSide.toFloat() / h)
        val resizedW = (w * nominal).roundToInt().coerceIn(1, longSide)
        val resizedH = (h * nominal).roundToInt().coerceIn(1, longSide)
        val inputWidth = ((resizedW + KoharuSegCore.STRIDE - 1) / KoharuSegCore.STRIDE) * KoharuSegCore.STRIDE
        val inputHeight = ((resizedH + KoharuSegCore.STRIDE - 1) / KoharuSegCore.STRIDE) * KoharuSegCore.STRIDE
        val scaleX = resizedW.toFloat() / w
        val scaleY = resizedH.toFloat() / h
        val padX = (inputWidth - resizedW) / 2
        val padY = (inputHeight - resizedH) / 2

        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            crop = Bitmap.createBitmap(source, x, y, w, h)
            scaled = Bitmap.createScaledBitmap(crop, resizedW, resizedH, true)
            val canvas = IntArray(inputWidth * inputHeight) { 0xFF727272.toInt() }
            scaled.getPixels(canvas, padY * inputWidth + padX, inputWidth, 0, 0, resizedW, resizedH)
            return PreparedTile(
                canvas, inputWidth, inputHeight,
                KoharuSegCore.Letterbox(scaleX, scaleY, padX, padY, resizedW, resizedH)
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

    private class Loaded(file: File, lowMemorySession: Boolean = false) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val ses: OrtSession
        private val imageName: String
        private val predictionName: String
        private val prototypeName: String
        private var chwPixels = 0
        private var chw = FloatArray(0)

        init {
            ses = createSession(file, lowMemorySession)
            try {
                var img: String? = null
                for ((name, info) in ses.inputInfo) {
                    val ti = info.info as? TensorInfo ?: continue
                    if (ti.shape.size == 4 && ti.shape[1] == 3L &&
                        (ti.shape[2] <= 0L || ti.shape[2] % KoharuSegCore.STRIDE == 0L) &&
                        (ti.shape[3] <= 0L || ti.shape[3] % KoharuSegCore.STRIDE == 0L)) {
                        img = name
                        break
                    }
                }
                require(img != null) { "Koharu input must be [1,3,H,W] with stride-32 spatial dimensions" }
                imageName = img

                var pred: String? = null
                var proto: String? = null
                val outputShapes = ArrayList<String>(ses.outputInfo.size)
                for ((name, node) in ses.outputInfo) {
                    val ti = node.info as? TensorInfo
                    val shape = ti?.shape
                    outputShapes.add("$name=${shape?.contentToString() ?: node.info.javaClass.simpleName}")
                    if (shape != null && shape.size == 3 &&
                        (shape[2] == KoharuSegCore.ROW_SIZE.toLong() || shape[2] <= 0L)) pred = name
                    if (shape != null && shape.size == 4 &&
                        (shape[1] == KoharuSegCore.PROTO_CHANNELS.toLong() || shape[1] <= 0L) &&
                        (shape[2] <= 0L || shape[2] % 4L == 0L) &&
                        (shape[3] <= 0L || shape[3] % 4L == 0L)) proto = name
                }
                // The pinned Koharu export documents output0=instances and output1=mask prototypes.
                // Prefer the model's explicit names if ONNX Runtime reports symbolic/unknown dimensions
                // that prevent shape-based discovery; the low-memory self-test validates actual tensors.
                if (pred == null && "output0" in ses.outputInfo) pred = "output0"
                if (proto == null && "output1" in ses.outputInfo) proto = "output1"
                require(pred != null && proto != null) {
                    "Koharu output contract mismatch (${outputShapes.joinToString()}); expected output0 [1,N,38], output1 [1,32,H/4,W/4]"
                }
                predictionName = pred
                prototypeName = proto
            } catch (e: Throwable) {
                try { ses.close() } catch (_: Throwable) {}
                throw e
            }
        }

        private fun createSession(file: File, lowMemory: Boolean): OrtSession {
            if (lowMemory) {
                val options = OrtSession.SessionOptions()
                options.setIntraOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                return env.createSession(file.absolutePath, options)
            }
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

        fun run(
            argb: IntArray,
            tile: IntArray,
            letterbox: KoharuSegCore.Letterbox,
            inputWidth: Int,
            inputHeight: Int
        ): List<BubbleDetectorCore.Det> {
            require(KoharuSegCore.validInputShape(inputWidth, inputHeight)) {
                "invalid Koharu run input ${inputWidth}x${inputHeight}; dimensions must be stride-${KoharuSegCore.STRIDE}-aligned and at least ${KoharuSegCore.MIN_INPUT_SIZE}px"
            }
            check(argb.size >= inputWidth * inputHeight) { "letterboxed Koharu canvas is too small" }
            val pixels = inputWidth * inputHeight
            if (chwPixels != pixels) {
                chw = FloatArray(3 * pixels)
                chwPixels = pixels
            }
            BubbleDetectorCore.fillChw(argb, inputWidth, inputHeight, chw)
            var input: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                input = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(chw),
                    longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
                )
                result = ses.run(mapOf(imageName to input))
                val predValue = result.get(predictionName).orElse(null)
                val pred = predValue as? OnnxTensor
                    ?: throw IllegalStateException("Koharu output0 is not a tensor")
                val protoValue = result.get(prototypeName).orElse(null)
                val proto = protoValue as? OnnxTensor
                    ?: throw IllegalStateException("Koharu output1 is not a tensor")
                val predShape = pred.info.shape
                val normalizedPredictions = KoharuSegCore.normalizePredictionRows(toFloats(pred), predShape)
                val protoShape = proto.info.shape
                require(
                    protoShape.size == 4 &&
                        protoShape[0] == 1L &&
                        protoShape[1] == KoharuSegCore.PROTO_CHANNELS.toLong() &&
                        protoShape[2] == (inputHeight / 4).toLong() &&
                        protoShape[3] == (inputWidth / 4).toLong()
                ) {
                    "Koharu output1 shape ${protoShape.contentToString()} is invalid for input ${inputWidth}x${inputHeight}; " +
                        "expected [1,32,${inputHeight / 4},${inputWidth / 4}] (outputs: output0=${predShape.contentToString()}, output1=${protoShape.contentToString()})"
                }
                val protoHeight = protoShape[2].toInt()
                val protoWidth = protoShape[3].toInt()
                return KoharuSegCore.decode(
                    normalizedPredictions, toFloats(proto), protoWidth, protoHeight,
                    inputWidth, inputHeight, tile, letterbox
                )
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
