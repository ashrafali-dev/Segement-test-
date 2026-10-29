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
        while (recentGlobalCalls.isNotEmpty() && now - recentGlobalCalls.peekFirst() >= GLOBAL_CALL_WINDOW_MS) {
            recentGlobalCalls.removeFirst()
        }
        if (recentGlobalCalls.size >= GLOBAL_CALL_LIMIT) return false
        recentGlobalCalls.addLast(now)
        return true
    }

    @Volatile private var slowUntil = 0L
    private const val SLOW_PAUSE_MS = 180_000L
    val tooSlow: Boolean get() = SystemClock.elapsedRealtime() < slowUntil

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

    private fun ensureBundledModel(ctx: Context): File? {
        val app = ctx.applicationContext
        val target = modelFile(app)
        if (target.isFile && target.length() == EXPECTED_BYTES) return target
        try {
            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) return null }
            val part = File(target.parentFile, target.name + ".part")
            part.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            app.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(65536)
                    var count = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > EXPECTED_BYTES) throw IllegalStateException("bundled MI-GAN asset is oversized")
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                    if (count != EXPECTED_BYTES) throw IllegalStateException("bundled MI-GAN asset size mismatch")
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != MODEL_SHA256) throw IllegalStateException("bundled MI-GAN SHA-256 mismatch")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("could not stage bundled MI-GAN model")
            return target
        } catch (_: Throwable) {
            try { File(target.parentFile, target.name + ".part").delete() } catch (_: Throwable) {}
            return null
        }
    }

    fun isReady(ctx: Context): Boolean = ensureBundledModel(ctx) != null

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
        if (engine != null || sessionFailed || !isReady(ctx) || !warming.compareAndSet(false, true)) return
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
        // The authoritative model is in the APK; only delete its staged private copy.
        return !file.exists() || file.delete()
    }

    /** Runtime downloads are disabled: MI-GAN must be bundled in assets/migan_fp16.tflite. */
    fun download(ctx: Context, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit) {
        val app = ctx.applicationContext
        if (isReady(app)) onDone(null) else {
            OcrDiag.log("CLEAN", "migan_unavailable reason=file_missing falling_back_to_tier2")
            onDone("MI-GAN is missing from this APK. Rebuild with the bundled asset and reinstall.")
        }
    }

    @Synchronized
    private fun interpreter(ctx: Context): Interpreter? {
        engine?.let { return it }
        if (sessionFailed || !isReady(ctx)) return null

        var delegate: GpuDelegate? = null
        var candidate: Interpreter? = null
        try {
            val file = ensureBundledModel(ctx) ?: return null
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
    fun fill(ctx: Context, bitmap: Bitmap, x0: Int, y0: Int, rw: Int, rh: Int, mask: ByteArray): IntArray? {
        OnnxHeavyGate.lock.lock()
        try {
            if (!aiSafe(ctx)) return null
            val marker = crashMarker(ctx)
            try { marker.parentFile?.mkdirs(); marker.writeText("1") } catch (_: Throwable) {}
            val t0 = SystemClock.elapsedRealtime()
            try {
                val result = fillLocked(ctx, bitmap, x0, y0, rw, rh, mask)
                if (result != null) {
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    lastNote = "last region " + (elapsed / 100) / 10.0 + "s"
                    if (elapsed > 9000L) slowUntil = SystemClock.elapsedRealtime() + SLOW_PAUSE_MS
                }
                return result
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
            val side = minOf(maxOf(160, (maxOf(rw, rh) * 1.6f).toInt()), bw, bh)
            if (rw > side || rh > side) return null
            val cx0 = (x0 + rw / 2 - side / 2).coerceIn(0, bw - side)
            val cy0 = (y0 + rh / 2 - side / 2).coerceIn(0, bh - side)
            val crop = IntArray(side * side)
            bitmap.getPixels(crop, 0, side, cx0, cy0, side, side)
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
