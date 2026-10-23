package com.ashraf.novelstudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Matrix
import java.nio.FloatBuffer

/**
 * PP-OCR style pipeline on ONNX Runtime: a DB detector finds text regions, a CRNN recogniser reads
 * each region (CTC greedy decode). Models are downloaded by the user ([OcrModels]) into filesDir/ocr.
 * A missing model throws [OcrUnavailableException]; [FallbackOcrEngine] then uses ML Kit.
 */
internal class OnnxOcrEngine : OcrEngine {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var det: OrtSession? = null
    private var detError: String? = null
    private val recs = HashMap<OcrScript, Pair<OrtSession, List<String>>>()
    private val recErrors = HashMap<OcrScript, String>()

    private var seenVersion = -1

    /** After a model was installed or deleted, forget cached sessions and cached "missing" errors. */
    @Synchronized
    private fun syncModels() {
        val v = OcrModels.version
        if (v == seenVersion) return
        // Old sessions are dropped, not closed: another OCR thread may still be running one.
        det = null
        detError = null
        recs.clear()
        recErrors.clear()
        seenVersion = v
    }

    @Synchronized
    private fun detector(): OrtSession {
        syncModels()
        val cached = det
        if (cached != null) return cached
        detError?.let { throw OcrUnavailableException(it) }
        val bytes = OcrModels.readFile("det.onnx")
        if (bytes == null) {
            val msg = "detector model not downloaded"
            detError = msg
            throw OcrUnavailableException(msg)
        }
        return env.createSession(bytes, OrtSession.SessionOptions()).also { det = it }
    }

    @Synchronized
    private fun reader(script: OcrScript): Pair<OrtSession, List<String>> {
        syncModels()
        val cachedRec = recs[script]
        if (cachedRec != null) return cachedRec
        recErrors[script]?.let { throw OcrUnavailableException(it) }
        val (model, dict) = when (script) {
            OcrScript.LATIN -> "rec_en.onnx" to "dict_en.txt"
            OcrScript.KOREAN -> "rec_korean.onnx" to "dict_korean.txt"
            OcrScript.JAPANESE -> "rec_ja.onnx" to "dict_ja.txt"
            OcrScript.CHINESE -> "rec_ch.onnx" to "dict_ch.txt"
        }
        val mb = OcrModels.readFile(model)
        val db = OcrModels.readFile(dict)
        if (mb == null || db == null) {
            val msg = "${script.name} model not downloaded"
            recErrors[script] = msg
            throw OcrUnavailableException(msg)
        }
        val keys = String(db, Charsets.UTF_8).lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        val pair = env.createSession(mb, OrtSession.SessionOptions()) to keys
        recs[script] = pair
        return pair
    }

    private class Box(val l: Int, val t: Int, val r: Int, val b: Int)

    /** Runs [sess] on one CHW float tensor and reads the output with [read]. */
    private fun <T> infer(sess: OrtSession, data: FloatArray, shape: LongArray, read: (OnnxTensor) -> T): T {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs[sess.inputNames.iterator().next()] = input
            val result = sess.run(inputs)
            try {
                return read(result.get(0) as OnnxTensor)
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    private fun readFloats(t: OnnxTensor): FloatArray {
        val b = t.floatBuffer.duplicate()
        val a = FloatArray(b.remaining())
        b.get(a)
        return a
    }

    /** DB detection: probability map -> connected regions -> boxes in original bitmap coordinates. */
    private fun detect(bmp: Bitmap): List<Box> {
        val sess = detector()
        val scale = minOf(1f, DET_MAX / maxOf(bmp.width, bmp.height).toFloat())
        val dw = maxOf(32, Math.round(bmp.width * scale / 32f) * 32)
        val dh = maxOf(32, Math.round(bmp.height * scale / 32f) * 32)
        val small = Bitmap.createScaledBitmap(bmp, dw, dh, true)
        val px = IntArray(dw * dh)
        small.getPixels(px, 0, dw, 0, 0, dw, dh)
        if (small !== bmp) small.recycle()

        val plane = dw * dh
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            // PP-OCR is trained on OpenCV (BGR) images: plane 0 = blue, 2 = red.
            input[i] = ((c and 255) / 255f - MEAN[0]) / STD[0]
            input[plane + i] = (((c shr 8) and 255) / 255f - MEAN[1]) / STD[1]
            input[2 * plane + i] = (((c shr 16) and 255) / 255f - MEAN[2]) / STD[2]
        }
        val prob = infer(sess, input, longArrayOf(1, 3, dh.toLong(), dw.toLong())) { readFloats(it) }
        if (prob.size < plane) throw OcrUnavailableException("unexpected detector output")

        val seen = BooleanArray(plane)
        val stack = IntArray(plane)
        val sx = bmp.width.toFloat() / dw
        val sy = bmp.height.toFloat() / dh
        val boxes = ArrayList<Box>()
        for (start in 0 until plane) {
            if (seen[start] || prob[start] < DET_THRESH) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = dw; var x1 = -1; var y0 = dh; var y1 = -1; var n = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val y = cur / dw
                val x = cur - y * dw
                n++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && !seen[cur - 1] && prob[cur - 1] >= DET_THRESH) { seen[cur - 1] = true; stack[sp++] = cur - 1 }
                if (x < dw - 1 && !seen[cur + 1] && prob[cur + 1] >= DET_THRESH) { seen[cur + 1] = true; stack[sp++] = cur + 1 }
                if (y > 0 && !seen[cur - dw] && prob[cur - dw] >= DET_THRESH) { seen[cur - dw] = true; stack[sp++] = cur - dw }
                if (y < dh - 1 && !seen[cur + dw] && prob[cur + dw] >= DET_THRESH) { seen[cur + dw] = true; stack[sp++] = cur + dw }
            }
            if (n < 8) continue
            val w = (x1 - x0 + 1).toFloat()
            val h = (y1 - y0 + 1).toFloat()
            // DB "unclip": grow the region so the detector's shrunken map covers the whole glyph set.
            val d = (w * h * UNCLIP) / (2f * (w + h))
            val l = ((x0 - d) * sx).toInt().coerceIn(0, bmp.width - 1)
            val t = ((y0 - d) * sy).toInt().coerceIn(0, bmp.height - 1)
            val r = ((x1 + 1 + d) * sx).toInt().coerceIn(l + 1, bmp.width)
            val b = ((y1 + 1 + d) * sy).toInt().coerceIn(t + 1, bmp.height)
            if (r - l < 4 || b - t < 4) continue
            boxes.add(Box(l, t, r, b))
        }
        return boxes
    }

    /** CTC greedy decode of a [1, T, C] probability tensor. */
    private fun ctcDecode(t: OnnxTensor, dict: List<String>): Pair<String, Float> {
        val shape = t.info.shape
        val steps = shape[1].toInt()
        val classes = shape[2].toInt()
        val buf = t.floatBuffer
        val sb = StringBuilder()
        var prev = 0
        var confSum = 0f
        var n = 0
        for (step in 0 until steps) {
            var best = 0
            var bestV = -1f
            val base = step * classes
            for (c in 0 until classes) {
                val v = buf.get(base + c)
                if (v > bestV) { bestV = v; best = c }
            }
            if (best != 0 && best != prev) {
                // index 0 = CTC blank; 1..N = dictionary lines; N+1 = space (PaddleOCR convention)
                when {
                    best - 1 < dict.size -> sb.append(dict[best - 1])
                    best - 1 == dict.size -> sb.append(' ')
                }
                confSum += bestV
                n++
            }
            prev = best
        }
        return sb.toString().trim() to (if (n == 0) 0f else confSum / n)
    }

    private fun readBox(bmp: Bitmap, b: Box, rec: Pair<OrtSession, List<String>>, script: OcrScript): OcrBlock? {
        val w = b.r - b.l
        val h = b.b - b.t
        // Only CJK uses top-to-bottom columns; a tall English box is just a few close lines.
        val vertical = script != OcrScript.LATIN && h > w * 1.6f
        var crop = Bitmap.createBitmap(bmp, b.l, b.t, w, h)
        try {
            if (vertical) {
                // Untested: direction of the 90-degree turn for top-to-bottom columns needs a device check.
                val rot = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(90f) }, true)
                crop.recycle()
                crop = rot
            }
            val tw = maxOf(8, Math.round(REC_H * crop.width.toFloat() / crop.height))
            val rs = Bitmap.createScaledBitmap(crop, tw, REC_H, true)
            val px = IntArray(tw * REC_H)
            rs.getPixels(px, 0, tw, 0, 0, tw, REC_H)
            if (rs !== crop) rs.recycle()
            val plane = tw * REC_H
            val input = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val c = px[i]
                input[i] = (c and 255) / 255f * 2f - 1f
                input[plane + i] = ((c shr 8) and 255) / 255f * 2f - 1f
                input[2 * plane + i] = ((c shr 16) and 255) / 255f * 2f - 1f
            }
            val (text, conf) = infer(rec.first, input, longArrayOf(1, 3, REC_H.toLong(), tw.toLong())) {
                ctcDecode(it, rec.second)
            }
            if (text.isBlank() || conf < REC_MIN_CONF) return null
            val fontPx = (if (vertical) w else h) * 0.9f
            return OcrBlock(
                text, b.t, b.l,
                b.l + w / 2f, b.t + h / 2f, w.toFloat(), h.toFloat(), 0f, fontPx, vertical, conf.coerceIn(0f, 1f)
            )
        } finally {
            crop.recycle()
        }
    }

    private fun readPage(bmp: Bitmap, script: OcrScript): OcrResult {
        val boxes = detect(bmp)
        if (boxes.isEmpty()) return OcrResult(emptyList())
        val rec = reader(script)
        val out = ArrayList<OcrBlock>()
        for (b in boxes) {
            val blk: OcrBlock? = try { readBox(bmp, b, rec, script) } catch (_: IllegalArgumentException) { null }
            if (blk != null) out.add(blk)
        }
        val lines = out.filterNot { OcrPost.isNoise(it) }.sortedWith(compareBy({ it.top }, { it.left }))
        // Same contract as the ML Kit engine: English keeps raw lines (the slice audit joins them later).
        return OcrResult(if (script == OcrScript.LATIN) lines else OcrPost.mergeNeighbours(lines, bmp))
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult = readPage(bitmap, script)
    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = readPage(bitmap, script)
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = readPage(bitmap, script)
    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = readPage(bitmap, script)

    @Synchronized
    override fun close() {
        det?.close()
        det = null
        recs.values.forEach { runCatching { it.first.close() } }
        recs.clear()
    }

    companion object {
        private const val DET_MAX = 960f
        private const val DET_THRESH = 0.3f
        private const val UNCLIP = 1.6f
        private const val REC_H = 48
        private const val REC_MIN_CONF = 0.4f
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * Primary engine first; ML Kit when the primary is unavailable (model missing, runtime error)
 * or reads nothing useful. Latency note: an empty primary result costs one extra ML Kit pass.
 */
internal class FallbackOcrEngine(
    private val primary: OcrEngine,
    private val fallback: OcrEngine
) : OcrEngine {
    private fun useful(r: OcrResult?): Boolean =
        r != null && r.blocks.any { b -> b.text.count { it.isLetterOrDigit() } >= 2 }

    private fun chain(run: (OcrEngine) -> OcrResult): OcrResult {
        val p: OcrResult? = try { run(primary) } catch (e: Exception) {
            OcrModels.lastError = (e.message ?: e.javaClass.simpleName)
            null
        } catch (_: OutOfMemoryError) {
            OcrModels.lastError = "out of memory"
            null
        }
        if (useful(p)) return p!!
        return run(fallback)
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult = chain { it.recognize(bitmap, script) }
    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = chain { it.recognizeFast(bitmap, script) }
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = chain { it.recognizeFallback(bitmap, script) }
    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = chain { it.recognizeRegion(bitmap, script) }

    override fun close() {
        primary.close()
        fallback.close()
    }
}
