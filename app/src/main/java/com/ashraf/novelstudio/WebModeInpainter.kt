package com.ashraf.novelstudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional AI inpainting (LaMa, ONNX) for lettering that sits on artwork. The model is NOT part of the app:
 * it is downloaded once, on request, into app-private storage. Input/output contract of the model
 * (othytenk/lama-int4-int8, a quantized copy of Carve/LaMa-ONNX): image float32 [1,3,512,512] RGB 0..1, mask float32 [1,1,512,512]
 * (1 = erase, 0 = keep), output float32 [1,3,512,512] RGB (0..255).
 */
internal object WebModeInpainter {
    // LaMa (big-lama) with int4/int8 weights, 37 MB, same inputs/outputs as the 208 MB fp32 model (othytenk/lama-int4-int8).
    const val MODEL_URL = "https://huggingface.co/othytenk/lama-int4-int8/resolve/main/lama_int4_int8_r4.onnx"
    private const val MODEL_NAME = "lama_int4_int8_r4.onnx"
    private const val MODEL_SHA256 = "4e3b78a253eb4abf21896811c928ffa91214b916e5d6ced24cecdd7999b4aaa1"
    private const val MIN_BYTES = 20_000_000L
    private const val MAX_BYTES = 150_000_000L
    private const val SIZE = 512

    private val downloading = AtomicBoolean(false)
    private val warming = AtomicBoolean(false)
    /** Set when one inpaint took longer than 9 s on this phone: AI is skipped from then on (until the app restarts). */
    @Volatile var tooSlow = false
    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionFailed = false
    @Volatile private var imageName = "image"
    @Volatile private var maskName = "mask"

    private fun modelFile(ctx: Context): File =
        File(File(ctx.applicationContext.filesDir, "wm_inpaint"), MODEL_NAME)

    fun isReady(ctx: Context): Boolean = modelFile(ctx).let { it.isFile && it.length() in MIN_BYTES..MAX_BYTES }
    fun isDownloading(): Boolean = downloading.get()
    fun isLoaded(): Boolean = session != null

    /** Loads the model in the background (several seconds), so the first scanned page never waits for it. */
    fun warmUpAsync(ctx: Context) {
        if (session != null || sessionFailed || !isReady(ctx) || !warming.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try { session(app) } catch (_: Throwable) {} finally { warming.set(false) }
        }.apply { isDaemon = true; name = "wm-inpaint-warmup" }.start()
    }

    @Synchronized
    fun delete(ctx: Context): Boolean {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        sessionFailed = false
        return !modelFile(ctx).exists() || modelFile(ctx).delete()
    }

    /** Downloads the model on a background thread. [onDone] gets null on success, otherwise a short error. */
    fun download(ctx: Context, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit) {
        if (!downloading.compareAndSet(false, true)) { onDone("ডাউনলোড ইতিমধ্যে চলছে"); return }
        val app = ctx.applicationContext
        Thread {
            var error: String? = null
            var conn: HttpURLConnection? = null
            try {
                val target = modelFile(app)
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("ফোল্ডার তৈরি করা যায়নি") }
                val part = File(target.parentFile, target.name + ".part")
                part.delete()
                conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 45000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "NovelStudio-Inpaint/1")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = conn.contentLengthLong
                if (total > MAX_BYTES) throw IllegalStateException("অপ্রত্যাশিত ফাইল সাইজ")
                var readTotal = 0L
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            readTotal += n
                            if (readTotal > MAX_BYTES) throw IllegalStateException("ফাইলের সাইজ বেশি")
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            onProgress(readTotal, total)
                        }
                    }
                }
                if (total > 0 && readTotal != total) throw IllegalStateException("অসম্পূর্ণ ডাউনলোড")
                if (part.length() !in MIN_BYTES..MAX_BYTES) throw IllegalStateException("ফাইল যাচাই ব্যর্থ")
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != MODEL_SHA256) throw IllegalStateException("ফাইলের চেকসাম মেলেনি")
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("ফাইল সংরক্ষণ ব্যর্থ")
                // Prove right now that this phone's ONNX Runtime can run the model; otherwise say so instead of failing silently.
                sessionFailed = false
                if (session(app) == null) {
                    target.delete()
                    sessionFailed = false
                    throw IllegalStateException("মডেল লোড হয়নি — এই অ্যাপের ONNX Runtime সংস্করণ মডেলটির জন্য পুরনো হতে পারে")
                }
            } catch (e: Throwable) {
                error = e.message ?: "ডাউনলোড ব্যর্থ"
                try { File(File(app.filesDir, "wm_inpaint"), "$MODEL_NAME.part").delete() } catch (_: Throwable) {}
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
                downloading.set(false)
                onDone(error)
            }
        }.apply { isDaemon = true; name = "wm-inpaint-download" }.start()
    }

    @Synchronized
    private fun session(ctx: Context): OrtSession? {
        session?.let { return it }
        if (sessionFailed || !isReady(ctx)) return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors())))
            // Required by this int4/int8 model: dequantize the weights once at load so it runs like fp32.
            opts.addConfigEntry("session.disable_quant_qdq", "1")
            val s = env.createSession(modelFile(ctx).absolutePath, opts)
            val names = s.inputNames.toList()
            imageName = if (names.contains("image")) "image" else names[0]
            maskName = if (names.contains("mask")) "mask" else names[names.size - 1]
            session = s
            s
        } catch (_: Throwable) {
            sessionFailed = true
            null
        }
    }

    /**
     * Fills the masked pixels of the region [x0,y0,rw,rh] of [bitmap] with AI-generated picture content.
     * [mask] is region-local (rw*rh, non-zero = erase). Returns an rw*rh array whose masked entries hold the
     * new ARGB colour, or null when the model is unavailable or anything fails (caller falls back).
     */
    @Synchronized
    fun fill(ctx: Context, bitmap: Bitmap, x0: Int, y0: Int, rw: Int, rh: Int, mask: ByteArray): IntArray? {
        val s = session(ctx) ?: return null
        val t0 = SystemClock.elapsedRealtime()
        var scaledIn: Bitmap? = null
        var srcBmp: Bitmap? = null
        var outSmall: Bitmap? = null
        var outBig: Bitmap? = null
        var tImg: OnnxTensor? = null
        var tMask: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            val bw = bitmap.width
            val bh = bitmap.height
            // A square window with some context around the region; the model works at a fixed 512x512.
            val side = minOf(maxOf(160, (maxOf(rw, rh) * 1.6f).toInt()), bw, bh)
            if (rw > side || rh > side) return null
            val cx0 = (x0 + rw / 2 - side / 2).coerceIn(0, bw - side)
            val cy0 = (y0 + rh / 2 - side / 2).coerceIn(0, bh - side)
            val crop = IntArray(side * side)
            bitmap.getPixels(crop, 0, side, cx0, cy0, side, side)
            srcBmp = Bitmap.createBitmap(crop, side, side, Bitmap.Config.ARGB_8888)
            scaledIn = Bitmap.createScaledBitmap(srcBmp, SIZE, SIZE, true)
            val px = IntArray(SIZE * SIZE)
            scaledIn.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            val plane = SIZE * SIZE
            val img = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = px[i]
                img[i] = ((p shr 16) and 255) / 255f
                img[plane + i] = ((p shr 8) and 255) / 255f
                img[2 * plane + i] = (p and 255) / 255f
            }
            // Mask at 512x512: a target pixel is "erase" if ANY source pixel of its block is masked.
            val m = FloatArray(plane)
            for (ty in 0 until SIZE) {
                val sy0 = ty * side / SIZE
                val sy1 = maxOf(sy0 + 1, (ty + 1) * side / SIZE)
                for (tx in 0 until SIZE) {
                    val sx0 = tx * side / SIZE
                    val sx1 = maxOf(sx0 + 1, (tx + 1) * side / SIZE)
                    var hit = false
                    var sy = sy0
                    while (sy < sy1 && !hit) {
                        val ry = cy0 + sy - y0
                        if (ry in 0 until rh) {
                            var sx = sx0
                            while (sx < sx1) {
                                val rx = cx0 + sx - x0
                                if (rx in 0 until rw && mask[ry * rw + rx].toInt() != 0) { hit = true; break }
                                sx++
                            }
                        }
                        sy++
                    }
                    if (hit) m[ty * SIZE + tx] = 1f
                }
            }
            val env = OrtEnvironment.getEnvironment()
            tImg = OnnxTensor.createTensor(env, FloatBuffer.wrap(img), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
            tMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(m), longArrayOf(1, 1, SIZE.toLong(), SIZE.toLong()))
            result = s.run(mapOf(imageName to tImg, maskName to tMask))
            val ot = result.get(0) as OnnxTensor
            val arr = FloatArray(3 * plane)
            ot.floatBuffer.get(arr)
            var mx = 0f
            for (v in arr) if (v > mx) mx = v
            val k = if (mx <= 1.5f) 255f else 1f      // tolerate a model that returns 0..1
            val outPx = IntArray(plane)
            for (i in 0 until plane) {
                val r = (arr[i] * k).toInt().coerceIn(0, 255)
                val g = (arr[plane + i] * k).toInt().coerceIn(0, 255)
                val b = (arr[2 * plane + i] * k).toInt().coerceIn(0, 255)
                outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            outSmall = Bitmap.createBitmap(outPx, SIZE, SIZE, Bitmap.Config.ARGB_8888)
            outBig = Bitmap.createScaledBitmap(outSmall, side, side, true)
            val big = IntArray(side * side)
            outBig.getPixels(big, 0, side, 0, 0, side, side)
            val res = IntArray(rw * rh)
            for (ry in 0 until rh) for (rx in 0 until rw) {
                val at = ry * rw + rx
                if (mask[at].toInt() == 0) continue
                val sx = x0 + rx - cx0
                val sy = y0 + ry - cy0
                if (sx !in 0 until side || sy !in 0 until side) continue
                res[at] = big[sy * side + sx]
            }
            if (SystemClock.elapsedRealtime() - t0 > 9000L) tooSlow = true
            return res
        } catch (_: Throwable) {
            return null
        } finally {
            try { result?.close() } catch (_: Throwable) {}
            try { tImg?.close() } catch (_: Throwable) {}
            try { tMask?.close() } catch (_: Throwable) {}
            if (outBig != null && outBig !== outSmall) outBig.recycle()
            outSmall?.recycle()
            if (scaledIn != null && scaledIn !== srcBmp) scaledIn.recycle()
            srcBmp?.recycle()
        }
    }
}
