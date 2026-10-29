package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional MI-GAN (LiteRT/TFLite) inpainting for lettering on textured artwork.
 *
 * The fp16 model is downloaded on demand to app-private storage; it is not packaged in the APK.
 * Model contract from litert-community/MI-GAN-512-Places2-LiteRT:
 *   input [1,4,512,512], NCHW float32:
 *     channel 0 = keepMask - 0.5 (1 means keep, 0 means erase)
 *     channels 1..3 = normalized RGB [-1,1] multiplied by keepMask
 *   output [1,3,512,512], NCHW float32 in [-1,1].
 *
 * Use the GPU delegate only. The model card reports that XNNPACK declines this fp16 graph, so a
 * CPU fallback is deliberately not used: a slow/high-memory CPU run should not block manga reading.
 */
internal object WebModeInpainter {
    const val MODEL_URL =
        "https://huggingface.co/litert-community/MI-GAN-512-Places2-LiteRT/resolve/179a35d8e8a518f6253f9c42fc9b412c6438cf7c/migan_fp16.tflite"
    private const val MODEL_NAME = "migan_fp16.tflite"
    private const val EXPECTED_BYTES = 16_312_640L
    private const val MODEL_SHA256 = "ef53f8dca69e5ce29629441128322c4d9a0a527b2a043703e0ab497f2463c25f"
    private const val SIZE = 512
    private const val PLANE = SIZE * SIZE

    private val downloading = AtomicBoolean(false)
    private val warming = AtomicBoolean(false)

    // Global MI-GAN inference budget: at most 20 attempted calls in a rolling 60-second window.
    private const val GLOBAL_CALL_LIMIT = 20
    private const val GLOBAL_CALL_WINDOW_MS = 60_000L
    private val recentGlobalCalls = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquireGlobalCallBudget(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (recentGlobalCalls.peekFirst()?.let { now - it >= GLOBAL_CALL_WINDOW_MS } == true) {
            recentGlobalCalls.removeFirst()
        }
        if (recentGlobalCalls.size >= GLOBAL_CALL_LIMIT) return false
        recentGlobalCalls.addLast(now)
        return true
    }

    @Volatile private var slowUntil = 0L
    private const val SLOW_PAUSE_MS = 180_000L
    @Volatile private var inferenceCircuitUntilMs = 0L
    @Volatile private var inferenceCircuitReason = ""
    private const val INFERENCE_CIRCUIT_COOLDOWN_MS = 60_000L
    val tooSlow: Boolean get() = SystemClock.elapsedRealtime() < slowUntil
    val inferenceCircuitOpen: Boolean get() = SystemClock.elapsedRealtime() < inferenceCircuitUntilMs

    private fun openInferenceCircuit(reason: String) {
        synchronized(this) {
            inferenceCircuitReason = reason
            inferenceCircuitUntilMs = SystemClock.elapsedRealtime() + INFERENCE_CIRCUIT_COOLDOWN_MS
            // Recreate a possibly poisoned GPU interpreter only after the cooldown, not per bubble.
            try { engine?.close() } catch (_: Throwable) {}
            engine = null
            try { gpuDelegate?.close() } catch (_: Throwable) {}
            gpuDelegate = null
            mappedModel = null
            sessionFailed = false
        }
        OcrDiag.log("MIGAN_CIRCUIT_OPEN", "reason=inference_failed cooldownSec=60 detail=$reason")
    }

    private fun validateOutput(
        bitmap: Bitmap, x0: Int, y0: Int, rw: Int, rh: Int, mask: ByteArray, result: IntArray
    ): String? {
        if (result.size != rw * rh || mask.size != result.size) return "invalid_shape"
        val input = IntArray(result.size)
        bitmap.getPixels(input, 0, rw, x0, y0, rw, rh)
        var any = false
        var allBlack = true
        var unchanged = true
        for (i in mask.indices) {
            if (mask[i].toInt() == 0) continue
            any = true
            val value = result[i]
            val r = (value ushr 16) and 255
            val g = (value ushr 8) and 255
            val b = value and 255
            if (r > 5 || g > 5 || b > 5) allBlack = false
            if (value != input[i]) unchanged = false
        }
        if (!any) return "empty_mask"
        if (allBlack) return "all_black"
        if (unchanged) return "unchanged"
        return null
    }

    @Volatile private var engine: Interpreter? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    @Volatile private var sessionFailed = false
    private var mappedModel: MappedByteBuffer? = null

    /** Why the AI painter was skipped / how long the last run took (shown in the status pill). */
    @Volatile var lastNote: String = ""

    private fun crashMarker(ctx: Context): File =
        File(File(ctx.applicationContext.filesDir, "wm_inpaint"), ".running")

    /**
     * A native GPU/OOM failure may kill the process without throwing an exception. If the marker
     * remains from the previous launch, disable this optional painter until the model is downloaded
     * again. Must be called while holding OnnxHeavyGate.lock.
     */
    private fun aiSafe(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val marker = crashMarker(app)
        if (marker.exists()) {
            try { marker.delete() } catch (_: Throwable) {}
            Prefs.put(app, "wmAiInpaintCrashed", "1")
            OcrDiag.log("AI_PAINT", "previous MI-GAN run crashed the app; painter switched off until model re-download")
        }
        if (Prefs.get(app, "wmAiInpaintCrashed", "") == "1") {
            lastNote = "off (crashed before)"
            return false
        }

        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return true
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val is64 = android.os.Process.is64Bit()
        // MI-GAN is much smaller than the previous LaMa model, but GPU delegate buffers still need
        // headroom. A user preference can override this conservative free-RAM threshold.
        val needMb = Prefs.get(app, "wmAiMinFreeMb", "").toLongOrNull()
            ?: if (is64) 400L else 350L
        val availMb = mi.availMem / 1048576L
        if (mi.lowMemory || availMb < needMb) {
            lastNote = "skipped: low RAM (" + availMb + "MB free)"
            OcrDiag.log("AI_PAINT", "skipped: free RAM " + availMb + "MB < " + needMb + "MB (64bit=" + is64 + ")")
            return false
        }
        return true
    }

    private fun modelFile(ctx: Context): File =
        File(File(ctx.applicationContext.filesDir, "wm_inpaint"), MODEL_NAME)

    /** The optional model lives in app-private storage only after the user downloads it. */
    fun isReady(ctx: Context): Boolean =
        modelFile(ctx).let { it.isFile && it.length() == EXPECTED_BYTES }

    fun isDownloading(): Boolean = downloading.get()
    fun isLoaded(): Boolean = engine != null

    /** Close native interpreter/delegate buffers when heap headroom becomes critically low. */
    fun releaseRuntimeForMemoryPressure() {
        OnnxHeavyGate.lock.lock()
        try {
            synchronized(this) {
                try { engine?.close() } catch (_: Throwable) {}
                engine = null
                try { gpuDelegate?.close() } catch (_: Throwable) {}
                gpuDelegate = null
                mappedModel = null
                lastNote = "released for low heap"
                OcrDiag.log("MEM_GUARD", "migan_runtime_released reason=low_heap")
            }
        } finally {
            OnnxHeavyGate.lock.unlock()
        }
    }

    /** Load in the background so the first scanned image does not wait for GPU model compilation. */
    fun warmUpAsync(ctx: Context) {
        if (engine != null || sessionFailed || inferenceCircuitOpen || !isReady(ctx) || !warming.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try {
                OnnxHeavyGate.lock.lock()
                try {
                    if (aiSafe(app)) {
                        val marker = crashMarker(app)
                        try { marker.parentFile?.mkdirs(); marker.writeText("1") } catch (_: Throwable) {}
                        try { interpreter(app) } finally { try { marker.delete() } catch (_: Throwable) {} }
                    }
                } finally {
                    OnnxHeavyGate.lock.unlock()
                }
            } catch (_: Throwable) {
            } finally {
                warming.set(false)
            }
        }.apply { isDaemon = true; name = "wm-migan-warmup" }.start()
    }

    @Synchronized
    fun delete(ctx: Context): Boolean {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        gpuDelegate = null
        mappedModel = null
        sessionFailed = false
        Prefs.put(ctx.applicationContext, "wmAiInpaintCrashed", "")
        try { crashMarker(ctx).delete() } catch (_: Throwable) {}
        val file = modelFile(ctx)
        val partial = File(file.parentFile, file.name + ".part")
        try { partial.delete() } catch (_: Throwable) {}
        // The model is optional user-downloaded data; deleting it frees the storage it occupies.
        return !file.exists() || file.delete()
    }

    /** Download the optional model only when requested by the user. A pinned size + SHA-256 guard rejects partial or altered files. */
    fun download(ctx: Context, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit) {
        if (isReady(ctx)) {
            onDone(null)
            return
        }
        if (!downloading.compareAndSet(false, true)) {
            onDone("ডাউনলোড ইতিমধ্যে চলছে")
            return
        }
        val app = ctx.applicationContext
        Thread {
            var error: String? = null
            var conn: HttpURLConnection? = null
            try {
                val target = modelFile(app)
                target.parentFile?.let {
                    if (!it.exists() && !it.mkdirs()) throw IllegalStateException("ফোল্ডার তৈরি করা যায়নি")
                }
                val part = File(target.parentFile, target.name + ".part")
                part.delete()
                conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 45000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "NovelStudio-MI-GAN/1")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP " + code)
                val total = conn.contentLengthLong
                if (total > 0 && total != EXPECTED_BYTES) throw IllegalStateException("অপ্রত্যাশিত ফাইল সাইজ")
                var readTotal = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            readTotal += n
                            if (readTotal > EXPECTED_BYTES) throw IllegalStateException("ফাইলের সাইজ বেশি")
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            onProgress(readTotal, total)
                        }
                    }
                }
                if (total > 0 && readTotal != total) throw IllegalStateException("অসম্পূর্ণ ডাউনলোড")
                if (readTotal != EXPECTED_BYTES || part.length() != EXPECTED_BYTES) {
                    throw IllegalStateException("ফাইল যাচাই ব্যর্থ: সাইজ মেলেনি")
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualSha256 != MODEL_SHA256) throw IllegalStateException("MI-GAN ফাইলের SHA-256 মেলেনি")
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("ফাইল সংরক্ষণ ব্যর্থ")

                // Validate that this device's GPU runtime can actually load the downloaded model before reporting success.
                sessionFailed = false
                inferenceCircuitUntilMs = 0L
                inferenceCircuitReason = ""
                Prefs.put(app, "wmAiInpaintCrashed", "")
                OnnxHeavyGate.lock.lock()
                try {
                    if (interpreter(app) == null) {
                        target.delete()
                        sessionFailed = false
                        throw IllegalStateException("MI-GAN মডেল লোড হয়নি — এই ফোনের GPU delegate বা মডেল runtime সমর্থিত নয়")
                    }
                } finally {
                    OnnxHeavyGate.lock.unlock()
                }
            } catch (e: Throwable) {
                error = e.message ?: "ডাউনলোড ব্যর্থ"
                try { File(File(app.filesDir, "wm_inpaint"), MODEL_NAME + ".part").delete() } catch (_: Throwable) {}
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
                downloading.set(false)
                onDone(error)
            }
        }.apply { isDaemon = true; name = "wm-migan-download" }.start()
    }

    @Synchronized
    private fun interpreter(ctx: Context): Interpreter? {
        engine?.let { return it }
        if (sessionFailed || !isReady(ctx)) return null

        var delegate: GpuDelegate? = null
        var candidate: Interpreter? = null
        try {
            val file = modelFile(ctx).takeIf { it.isFile && it.length() == EXPECTED_BYTES } ?: return null
            val mapped = RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
            delegate = GpuDelegate()
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setAllowFp16PrecisionForFp32(true)
                addDelegate(delegate)
            }
            candidate = Interpreter(mapped, options)
            val inShape = candidate.getInputTensor(0).shape()
            val outShape = candidate.getOutputTensor(0).shape()
            if (inShape.size != 4 || inShape[0] != 1 || inShape[1] != 4 ||
                inShape[2] != SIZE || inShape[3] != SIZE ||
                outShape.size != 4 || outShape[0] != 1 || outShape[1] != 3 ||
                outShape[2] != SIZE || outShape[3] != SIZE) {
                throw IllegalStateException("MI-GAN-এর input/output shape প্রত্যাশিত নয়")
            }
            mappedModel = mapped
            gpuDelegate = delegate
            engine = candidate
            return candidate
        } catch (t: Throwable) {
            try { candidate?.close() } catch (_: Throwable) {}
            try { delegate?.close() } catch (_: Throwable) {}
            mappedModel = null
            sessionFailed = true
            lastNote = "GPU model unavailable"
            OcrDiag.log("AI_PAINT", "MI-GAN GPU initialization failed: " + (t.message ?: t.javaClass.simpleName))
            return null
        }
    }

    /**
     * Fills masked pixels in [x0,y0,rw,rh] of [bitmap].
     * [mask] is region-local: non-zero means erase. Returns generated ARGB values at masked entries,
     * or null when MI-GAN is unavailable or the input cannot safely be processed.
     */
    fun fill(
        ctx: Context, bitmap: Bitmap, x0: Int, y0: Int, rw: Int, rh: Int,
        mask: ByteArray, bubbleNo: Int = -1
    ): IntArray? {
        val queuedAt = SystemClock.elapsedRealtime()
        OnnxHeavyGate.lock.lock()
        val waitMs = SystemClock.elapsedRealtime() - queuedAt
        OcrDiag.log("MIGAN_QUEUE", "bubble=$bubbleNo waitMs=$waitMs")
        try {
            if (inferenceCircuitOpen) {
                OcrDiag.log("CLEAN", "migan_unavailable reason=inference_circuit_open tier3_skipped")
                return null
            }
            if (!aiSafe(ctx)) return null
            val marker = crashMarker(ctx)
            try { marker.parentFile?.mkdirs(); marker.writeText("1") } catch (_: Throwable) {}
            val t0 = SystemClock.elapsedRealtime()
            try {
                val result = fillLocked(ctx, bitmap, x0, y0, rw, rh, mask)
                if (result == null) {
                    openInferenceCircuit("initialization_or_inference_failed")
                    return null
                }
                val invalidReason = validateOutput(bitmap, x0, y0, rw, rh, mask, result)
                if (invalidReason != null) {
                    OcrDiag.log("MIGAN_BLANK_OUTPUT", "bubble=$bubbleNo reason=$invalidReason")
                    openInferenceCircuit(invalidReason)
                    return null
                }
                synchronized(this) {
                    inferenceCircuitUntilMs = 0L
                    inferenceCircuitReason = ""
                }
                val elapsed = SystemClock.elapsedRealtime() - t0
                lastNote = "last region " + (elapsed / 100) / 10.0 + "s"
                if (elapsed > 9000L) slowUntil = SystemClock.elapsedRealtime() + SLOW_PAUSE_MS
                return result
            } catch (t: Throwable) {
                openInferenceCircuit(t.javaClass.simpleName + ":" + (t.message ?: ""))
                return null
            } finally {
                try { marker.delete() } catch (_: Throwable) {}
            }
        } finally {
            OnnxHeavyGate.lock.unlock()
        }
    }

    @Synchronized
    private fun fillLocked(ctx: Context, bitmap: Bitmap, x0: Int, y0: Int, rw: Int, rh: Int, mask: ByteArray): IntArray? {
        val model = interpreter(ctx) ?: return null
        if (rw <= 0 || rh <= 0 || mask.size != rw * rh) return null

        var scaledIn: Bitmap? = null
        var srcBmp: Bitmap? = null
        var outSmall: Bitmap? = null
        var outBig: Bitmap? = null
        try {
            val bw = bitmap.width
            val bh = bitmap.height
            // Use a square context window around the text; MI-GAN accepts a fixed 512x512 input.
            // Model tensors always stay [1,4,512,512]/[1,3,512,512]. Some text boxes are wider/taller
            // than the source panel can hold as a square crop, so pad beyond the image edges by replicating
            // edge pixels instead of failing early for inputs such as 272x154.
            val side = minOf(SIZE, maxOf(160, (maxOf(rw, rh) * 1.6f).toInt()))
            if (rw > side || rh > side) return null
            val cx0 = x0 + rw / 2 - side / 2
            val cy0 = y0 + rh / 2 - side / 2
            val crop = IntArray(side * side)
            val validX0 = maxOf(0, cx0)
            val validX1 = minOf(bw, cx0 + side)
            val validWidth = validX1 - validX0
            if (validWidth <= 0 || bw <= 0 || bh <= 0) return null
            val leftOffset = validX0 - cx0
            val rightOffset = leftOffset + validWidth
            for (row in 0 until side) {
                val sourceY = (cy0 + row).coerceIn(0, bh - 1)
                val rowOffset = row * side
                bitmap.getPixels(crop, rowOffset + leftOffset, side, validX0, sourceY, validWidth, 1)
                val leftPixel = crop[rowOffset + leftOffset]
                for (x in 0 until leftOffset) crop[rowOffset + x] = leftPixel
                val rightPixel = crop[rowOffset + rightOffset - 1]
                for (x in rightOffset until side) crop[rowOffset + x] = rightPixel
            }
            srcBmp = Bitmap.createBitmap(crop, side, side, Bitmap.Config.ARGB_8888)
            scaledIn = Bitmap.createScaledBitmap(srcBmp, SIZE, SIZE, true)
            val px = IntArray(PLANE)
            scaledIn.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)

            // MI-GAN input: channel 0 is keepMask-0.5; RGB channels are normalized to [-1,1]
            // and multiplied by keepMask. Model mask semantics are the inverse of our local erase mask.
            val input = FloatArray(4 * PLANE)
            for (ty in 0 until SIZE) {
                val sy0 = ty * side / SIZE
                val sy1 = maxOf(sy0 + 1, (ty + 1) * side / SIZE)
                for (tx in 0 until SIZE) {
                    val sx0 = tx * side / SIZE
                    val sx1 = maxOf(sx0 + 1, (tx + 1) * side / SIZE)
                    var erase = false
                    var sy = sy0
                    while (sy < sy1 && !erase) {
                        val ry = cy0 + sy - y0
                        if (ry in 0 until rh) {
                            var sx = sx0
                            while (sx < sx1) {
                                val rx = cx0 + sx - x0
                                if (rx in 0 until rw && mask[ry * rw + rx].toInt() != 0) {
                                    erase = true
                                    break
                                }
                                sx++
                            }
                        }
                        sy++
                    }

                    val at = ty * SIZE + tx
                    val keep = if (erase) 0f else 1f
                    input[at] = keep - 0.5f
                    if (keep != 0f) {
                        val p = px[at]
                        input[PLANE + at] = (((p shr 16) and 255) / 127.5f - 1f)
                        input[2 * PLANE + at] = (((p shr 8) and 255) / 127.5f - 1f)
                        input[3 * PLANE + at] = ((p and 255) / 127.5f - 1f)
                    }
                }
            }

            val inputBytes = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
            inputBytes.asFloatBuffer().put(input)
            val outputBytes = ByteBuffer.allocateDirect(3 * PLANE * 4).order(ByteOrder.nativeOrder())
            model.run(inputBytes, outputBytes)

            val generated = FloatArray(3 * PLANE)
            outputBytes.rewind()
            outputBytes.asFloatBuffer().get(generated)
            val outPx = IntArray(PLANE)
            for (i in 0 until PLANE) {
                val r = ((generated[i] * 127.5f) + 127.5f).toInt().coerceIn(0, 255)
                val g = ((generated[PLANE + i] * 127.5f) + 127.5f).toInt().coerceIn(0, 255)
                val b = ((generated[2 * PLANE + i] * 127.5f) + 127.5f).toInt().coerceIn(0, 255)
                outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            outSmall = Bitmap.createBitmap(outPx, SIZE, SIZE, Bitmap.Config.ARGB_8888)
            outBig = Bitmap.createScaledBitmap(outSmall, side, side, true)
            val big = IntArray(side * side)
            outBig.getPixels(big, 0, side, 0, 0, side, side)
            val res = IntArray(rw * rh)
            for (ry in 0 until rh) {
                for (rx in 0 until rw) {
                    val at = ry * rw + rx
                    if (mask[at].toInt() == 0) continue
                    val sx = x0 + rx - cx0
                    val sy = y0 + ry - cy0
                    if (sx !in 0 until side || sy !in 0 until side) continue
                    res[at] = big[sy * side + sx]
                }
            }
            return res
        } catch (t: Throwable) {
            lastNote = "MI-GAN failed"
            OcrDiag.log("AI_PAINT", "MI-GAN inference failed: " + (t.message ?: t.javaClass.simpleName))
            return null
        } finally {
            if (outBig != null && outBig !== outSmall) outBig.recycle()
            outSmall?.recycle()
            if (scaledIn != null && scaledIn !== srcBmp) scaledIn.recycle()
            srcBmp?.recycle()
        }
    }
}
