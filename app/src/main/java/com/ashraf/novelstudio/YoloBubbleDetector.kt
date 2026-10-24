package com.ashraf.novelstudio

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * OPTIONAL precise speech-bubble detector for phones with plenty of RAM: the YOLO26s-seg "bubble + text" model of
 * Manga Translator (TFLite, float16, input 1472x1472). It returns the real SHAPE of every balloon, so the text is
 * placed and the old lettering erased exactly like in Manga Translator.
 *
 * Nothing is bundled and nothing is loaded unless
 *   1. the user imported the model file (menu: Bubble Detector -> Import model file), and
 *   2. the LiteRT/TFLite library is part of the app (see app/build.gradle), and
 *   3. the mode is "on", or "auto" on a phone with enough RAM.
 * The TFLite classes are reached by reflection, so the app compiles and runs without the library: the detector then
 * simply reports "library missing" and the light pixel-based [BubbleFitCore] is used. Every failure falls back to it.
 */
internal object YoloBubbleDetector {
    private const val TAG = "NsYolo"
    private const val IN = 1472
    private const val PROTO = 368
    private const val MASK_COEFS = 32
    private const val MIN_BALLOON = 0.35f
    private const val NMS_IOU = 0.45f
    private const val MAX_BALLOONS = 40
    private const val MIN_MODEL_BYTES = 2_000_000L
    private const val AUTO_MIN_RAM_MB = 5500L

    /** One detected balloon: its rectangle in page pixels and the interior mask on a grid of [ds]-pixel cells. */
    class Balloon(
        val ox: Int, val oy: Int, val w: Int, val h: Int,
        val sw: Int, val sh: Int, val ds: Int, val filled: BooleanArray, val confidence: Float
    ) {
        fun area(): Long = w.toLong() * h
        fun contains(x: Int, y: Int): Boolean = x >= ox && x < ox + w && y >= oy && y < oy + h
    }

    @Volatile var lastError: String = ""
        private set
    private var session: Session? = null
    private var sessionFile: Long = 0L

    fun modelFile(c: Context): File = File(File(c.filesDir, "models/detection"), "bubble_seg.tflite")

    fun isInstalled(c: Context): Boolean = modelFile(c).let { it.isFile && it.length() >= MIN_MODEL_BYTES }

    fun libraryPresent(): Boolean = try { Class.forName("org.tensorflow.lite.Interpreter"); true } catch (_: Throwable) { false }

    private fun totalRamMb(c: Context): Long = try {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem / (1024L * 1024L)
    } catch (_: Throwable) { 0L }

    /** "auto" (default) | "on" | "off". */
    fun mode(c: Context): String = Prefs.get(c, "wmYoloMode", "auto")

    fun enabled(c: Context): Boolean {
        if (!isInstalled(c) || !libraryPresent()) return false
        return when (mode(c)) {
            "on" -> true
            "off" -> false
            else -> totalRamMb(c) >= AUTO_MIN_RAM_MB
        }
    }

    /** Plain-words state for the menu. */
    fun status(c: Context): String {
        val ramGb = String.format("%.1f", totalRamMb(c) / 1024.0)
        val sb = StringBuilder()
        sb.append("Model file: ").append(if (isInstalled(c)) "imported (" + modelFile(c).length() / (1024 * 1024) + " MB)" else "not imported").append('\n')
        sb.append("TFLite library in this app: ").append(if (libraryPresent()) "yes" else "NO - add the LiteRT line to app/build.gradle (see note)").append('\n')
        sb.append("Mode: ").append(mode(c)).append("  (auto = on when the phone has ").append(AUTO_MIN_RAM_MB / 1024.0).append("+ GB RAM; this phone: ").append(ramGb).append(" GB)\n")
        sb.append("Right now: ").append(if (enabled(c)) "ACTIVE" else "not active - the light pixel-based bubble finder is used")
        if (lastError.isNotBlank()) sb.append("\nLast problem: ").append(lastError)
        return sb.toString()
    }

    /** Copies a model file picked by the user into the app. Returns an error text, or null when it worked. */
    fun importFrom(c: Context, uri: Uri): String? {
        val target = modelFile(c)
        val tmp = File(target.parentFile, "bubble_seg.part")
        return try {
            target.parentFile?.mkdirs()
            c.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                ?: return "Could not open the chosen file"
            if (tmp.length() < MIN_MODEL_BYTES) { tmp.delete(); return "That file is too small to be the bubble model" }
            release()
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.delete(); return "Could not save the model inside the app" }
            lastError = ""
            if (libraryPresent()) {
                // Validate the model right away so a wrong file is reported now, not during reading.
                try { Session(target).close() } catch (e: Throwable) {
                    target.delete()
                    return "This is not the expected model (" + (e.message ?: e.javaClass.simpleName) + ")"
                }
            }
            null
        } catch (e: Throwable) {
            try { tmp.delete() } catch (_: Throwable) {}
            e.message ?: e.javaClass.simpleName
        }
    }

    fun delete(c: Context) {
        release()
        try { modelFile(c).delete() } catch (_: Throwable) {}
    }

    @Synchronized
    fun release() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    /** Balloons of [bmp] (page pixels), or null when the detector is not usable. Never throws. */
    @Synchronized
    fun detect(c: Context, bmp: Bitmap): List<Balloon>? {
        if (!enabled(c)) return null
        return try {
            val file = modelFile(c)
            var s = session
            if (s == null || sessionFile != file.lastModified()) {
                try { s?.close() } catch (_: Throwable) {}
                s = Session(file)
                session = s
                sessionFile = file.lastModified()
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = s.detect(bmp)
            Log.i(TAG, "balloons=${out.size} image=${bmp.width}x${bmp.height} ms=${android.os.SystemClock.elapsedRealtime() - t0}")
            lastError = ""
            out
        } catch (e: Throwable) {
            lastError = (e.message ?: e.javaClass.simpleName).take(160)
            Log.w(TAG, "detector failed: $lastError")
            release()
            null
        }
    }

    // ---------------------------------------------------------------------------------------------

    private class Session(file: File) : AutoCloseable {
        private val interp: Any
        private val runM: Method
        private val closeM: Method
        private val detIndex: Int
        private val protoIndex: Int
        private val anchors: Int
        private val input: ByteBuffer
        private val detBuf: ByteBuffer
        private val protoBuf: ByteBuffer
        private val mapped: ByteBuffer

        init {
            mapped = FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
            val interpCls = Class.forName("org.tensorflow.lite.Interpreter")
            val optsCls = Class.forName("org.tensorflow.lite.Interpreter\$Options")
            val opts = optsCls.getConstructor().newInstance()
            optsCls.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(opts, Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)))
            try { optsCls.getMethod("setUseXNNPACK", Boolean::class.javaPrimitiveType).invoke(opts, false) } catch (_: Throwable) {}
            interp = interpCls.getConstructor(ByteBuffer::class.java, optsCls).newInstance(mapped, opts)
            runM = interpCls.getMethod("runForMultipleInputsOutputs", Array<Any>::class.java, Map::class.java)
            closeM = interpCls.getMethod("close")
            val getIn = interpCls.getMethod("getInputTensor", Int::class.javaPrimitiveType)
            val getOut = interpCls.getMethod("getOutputTensor", Int::class.javaPrimitiveType)
            val outCount = interpCls.getMethod("getOutputTensorCount").invoke(interp) as Int
            val tensorCls = Class.forName("org.tensorflow.lite.Tensor")
            val shapeM = tensorCls.getMethod("shape")
            val bytesM = tensorCls.getMethod("numBytes")
            val inShape = shapeM.invoke(getIn.invoke(interp, 0)) as IntArray
            require(inShape.contentEquals(intArrayOf(1, IN, IN, 3))) { "expected input [1,$IN,$IN,3]" }
            require(outCount == 2) { "expected 2 outputs" }
            var di = -1
            var pi = -1
            for (i in 0 until outCount) {
                val sh = shapeM.invoke(getOut.invoke(interp, i)) as IntArray
                if (sh.size == 3 && sh[1] == 38) di = i
                if (sh.size == 4 && sh[1] == PROTO && sh[2] == PROTO && sh[3] == MASK_COEFS) pi = i
            }
            require(di >= 0 && pi >= 0) { "outputs are not [1,38,N] + [1,$PROTO,$PROTO,$MASK_COEFS]" }
            detIndex = di; protoIndex = pi
            val dShape = shapeM.invoke(getOut.invoke(interp, di)) as IntArray
            anchors = dShape[2]
            input = ByteBuffer.allocateDirect(IN * IN * 3 * 4).order(ByteOrder.nativeOrder())
            detBuf = ByteBuffer.allocateDirect(bytesM.invoke(getOut.invoke(interp, di)) as Int).order(ByteOrder.nativeOrder())
            protoBuf = ByteBuffer.allocateDirect(bytesM.invoke(getOut.invoke(interp, pi)) as Int).order(ByteOrder.nativeOrder())
        }

        override fun close() {
            try { closeM.invoke(interp) } catch (_: Throwable) {}
        }

        fun detect(bmp: Bitmap): List<Balloon> {
            val bw = bmp.width
            val bh = bmp.height
            if (bw < 16 || bh < 16) return emptyList()
            // Letterbox (grey padding, centred) exactly like the Manga Translator pipeline.
            val gain = Math.min(IN.toFloat() / bw, IN.toFloat() / bh)
            val nw = Math.max(1, (bw * gain).toInt())
            val nh = Math.max(1, (bh * gain).toInt())
            val padX = Math.max(0f, (IN - nw) / 2f)
            val padY = Math.max(0f, (IN - nh) / 2f)
            val canvasBmp = Bitmap.createBitmap(IN, IN, Bitmap.Config.ARGB_8888)
            try {
                val cv = Canvas(canvasBmp)
                cv.drawColor(Color.rgb(114, 114, 114))
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                cv.drawBitmap(bmp, Rect(0, 0, bw, bh), Rect(padX.toInt(), padY.toInt(), padX.toInt() + nw, padY.toInt() + nh), paint)
                input.clear()
                val fb = input.asFloatBuffer()
                val row = IntArray(IN)
                val f = FloatArray(IN * 3)
                for (y in 0 until IN) {
                    canvasBmp.getPixels(row, 0, IN, 0, y, IN, 1)
                    var k = 0
                    for (p in row) {
                        f[k++] = ((p ushr 16) and 0xff) / 255f
                        f[k++] = ((p ushr 8) and 0xff) / 255f
                        f[k++] = (p and 0xff) / 255f
                    }
                    fb.put(f)
                }
                input.rewind()
            } finally { canvasBmp.recycle() }
            detBuf.clear(); protoBuf.clear()
            runM.invoke(interp, arrayOf<Any>(input), mapOf<Int, Any>(detIndex to detBuf, protoIndex to protoBuf))
            val det = detBuf.asFloatBuffer()
            val proto = protoBuf.asFloatBuffer()

            class Raw(val cx: Float, val cy: Float, val w: Float, val h: Float, val conf: Float, val coef: FloatArray)
            val cands = ArrayList<Raw>()
            for (a in 0 until anchors) {
                val s0 = det.get(4 * anchors + a)       // class 0 = speech balloon, class 1 = free text (not used here)
                val s1 = det.get(5 * anchors + a)
                if (!s0.isFinite() || !s1.isFinite() || s0 < MIN_BALLOON || s0 < s1) continue
                val cx = det.get(a) * IN; val cy = det.get(anchors + a) * IN
                val w = det.get(2 * anchors + a) * IN; val h = det.get(3 * anchors + a) * IN
                if (!(w > 4f && h > 4f)) continue
                cands.add(Raw(cx, cy, w, h, s0, FloatArray(MASK_COEFS) { det.get((6 + it) * anchors + a) }))
            }
            cands.sortByDescending { it.conf }
            val kept = ArrayList<Raw>()
            for (c in cands) {
                if (kept.size >= MAX_BALLOONS) break
                var dup = false
                for (k in kept) {
                    val iw = Math.max(0f, Math.min(c.cx + c.w / 2, k.cx + k.w / 2) - Math.max(c.cx - c.w / 2, k.cx - k.w / 2))
                    val ih = Math.max(0f, Math.min(c.cy + c.h / 2, k.cy + k.h / 2) - Math.max(c.cy - c.h / 2, k.cy - k.h / 2))
                    val inter = iw * ih
                    val uni = c.w * c.h + k.w * k.h - inter
                    if (uni > 0f && inter / uni > NMS_IOU) { dup = true; break }
                }
                if (!dup) kept.add(c)
            }
            val out = ArrayList<Balloon>(kept.size)
            for (r in kept) {
                val l = Math.floor(((r.cx - r.w / 2 - padX) / gain).toDouble()).toInt().coerceIn(0, bw - 1)
                val t = Math.floor(((r.cy - r.h / 2 - padY) / gain).toDouble()).toInt().coerceIn(0, bh - 1)
                val rr = Math.ceil(((r.cx + r.w / 2 - padX) / gain).toDouble()).toInt().coerceIn(l + 1, bw)
                val bb = Math.ceil(((r.cy + r.h / 2 - padY) / gain).toDouble()).toInt().coerceIn(t + 1, bh)
                val w = rr - l
                val h = bb - t
                if (w < 12 || h < 12) continue
                val ds = Math.max(1, (Math.max(w, h) + 199) / 200)
                val sw = (w + ds - 1) / ds
                val sh = (h + ds - 1) / ds
                val filled = BooleanArray(sw * sh)
                var any = 0
                for (cy in 0 until sh) {
                    val py = t + Math.min(h - 1, cy * ds + ds / 2)
                    val protoY = ((py * gain + padY) / IN * PROTO).toInt().coerceIn(0, PROTO - 1)
                    for (cx in 0 until sw) {
                        val px = l + Math.min(w - 1, cx * ds + ds / 2)
                        val protoX = ((px * gain + padX) / IN * PROTO).toInt().coerceIn(0, PROTO - 1)
                        val base = (protoY * PROTO + protoX) * MASK_COEFS
                        var score = 0f
                        for (k in 0 until MASK_COEFS) score += r.coef[k] * proto.get(base + k)
                        if (score > 0f) { filled[cy * sw + cx] = true; any++ }
                    }
                }
                if (any < 12) continue
                out.add(Balloon(l, t, w, h, sw, sh, ds, filled, r.conf))
            }
            return out
        }
    }
}
