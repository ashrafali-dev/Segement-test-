package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental Instant Tools OCR.
 *
 * The endpoint returns plain text rather than coordinates. ML Kit is used only to locate text lines;
 * the API receives individual bubble/aside crops and every response is mapped back to source-image
 * coordinates. Bubble outlines keep side whispers separate from dialogue. Full panels are never
 * intentionally uploaded.
 */
internal class TestingOcrEngine(context: Context) : OcrEngine {
    companion object {
        private const val TAG = "NovelStudioTestingOCR"
        private const val ENDPOINT = "https://instanttools.io/api/handwriting-to-text"
        private const val ORIGIN = "https://instanttools.io"
        private const val REFERER = "https://instanttools.io/tools/handwriting-to-text"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val JPEG_QUALITY = 96
        private const val MAX_REGION_AREA_FRACTION = 0.72
        private const val MAX_RESPONSE_CHARS = 120_000
    }

    private val appContext = context.applicationContext
    private val localLocator = MlKitOcrEngine()
    @Volatile private var closed = false

    private data class Region(
        val cropLeft: Int, val cropTop: Int, val cropRight: Int, val cropBottom: Int,
        val textLeft: Int, val textTop: Int, val textRight: Int, val textBottom: Int,
        val localBlocks: List<OcrBlock>,
        val source: String
    )

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        readRegions(bitmap, script, fastMode = false)

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        readRegions(bitmap, script, fastMode = true)

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        OcrResult(emptyList()) // A fallback pass must not repeat the remote requests.

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        readRegions(bitmap, script, fastMode = false)

    private fun readRegions(bitmap: Bitmap, script: OcrScript, fastMode: Boolean): OcrResult {
        if (closed) throw OcrUnavailableException("Testing OCR engine is closed")
        if (bitmap.isRecycled || bitmap.width < 16 || bitmap.height < 16) return OcrResult(emptyList())
        val jobStart = SystemClock.elapsedRealtime()
        val locateStart = SystemClock.elapsedRealtime()

        // Geometry pre-pass is local and uses the same priority gate as ML Kit.
        val located = OcrGate.run {
            if (fastMode) localLocator.recognizeFast(bitmap, script)
            else localLocator.recognize(bitmap, script)
        }
        val lineBlocks = OcrPost.mergeNeighbours(
            located.blocks.filter { it.text.isNotBlank() && !OcrPost.isNoise(it) }, bitmap
        )
        val detector = try { BubbleDetector.cached(bitmap) } catch (_: Throwable) { null }
        val regions = makeRegions(bitmap, lineBlocks, detector)
        val locateMs = SystemClock.elapsedRealtime() - locateStart

        if (regions.isEmpty()) {
            OcrDiag.log("TESTING_OCR", "no safe text crop image=${bitmap.width}x${bitmap.height} blocks=${lineBlocks.size} locateMs=${locateMs}")
            return OcrResult(lineBlocks.ifEmpty { located.blocks })
        }

        val out = ArrayList<OcrBlock>()
        var apiSuccess = 0
        var apiFailed = 0
        var apiEmpty = 0
        for ((index, region) in regions.withIndex()) {
            if (Thread.currentThread().isInterrupted || closed) {
                Thread.currentThread().interrupt()
                break
            }
            val requestStart = SystemClock.elapsedRealtime()
            var crop: Bitmap? = null
            var text = ""
            var cropMs = 0L
            var apiMs = 0L
            var failure = ""
            try {
                val cropStart = SystemClock.elapsedRealtime()
                crop = cropFor(bitmap, region)
                cropMs = SystemClock.elapsedRealtime() - cropStart
                if (crop == null) {
                    failure = "unsafe-or-empty-crop"
                } else {
                    val imageData = encodeDataUrl(crop)
                    val encodedAt = SystemClock.elapsedRealtime()
                    val raw = post(imageData)
                    apiMs = SystemClock.elapsedRealtime() - encodedAt
                    text = parseText(raw)
                    if (text.isNotBlank()) {
                        apiSuccess++
                        out.add(toOutputBlock(text, region))
                    } else {
                        apiEmpty++
                        failure = "empty-response"
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failure = "cancelled"
            } catch (e: Exception) {
                apiFailed++
                failure = e.javaClass.simpleName + (e.message?.let { ": ${it.take(90)}" } ?: "")
                Log.w(TAG, "region ${index + 1}/${regions.size} request failed: ${failure}")
            } finally {
                try { if (crop != null && crop !== bitmap && !crop.isRecycled) crop.recycle() } catch (_: Throwable) {}
            }

            if (text.isBlank()) out.addAll(region.localBlocks)
            OcrDiag.log(
                "TESTING_OCR",
                "region=${index + 1}/${regions.size} source=${region.source} " +
                    "box=${region.textRight - region.textLeft}x${region.textBottom - region.textTop} " +
                    "cropMs=${cropMs} apiMs=${apiMs} totalMs=${SystemClock.elapsedRealtime() - requestStart} " +
                    "chars=${text.length} status=${if (text.isNotBlank()) "ok" else failure.ifBlank { "empty" }}"
            )
        }

        val result = OcrResult(out.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.cy }, { it.cx })))
        OcrDiag.log(
            "TESTING_OCR",
            "job image=${bitmap.width}x${bitmap.height} regions=${regions.size} apiOk=${apiSuccess} " +
                "apiEmpty=${apiEmpty} apiFailed=${apiFailed} locateMs=${locateMs} " +
                "totalMs=${SystemClock.elapsedRealtime() - jobStart} outputBlocks=${result.blocks.size}"
        )
        return result
    }

    private fun makeRegions(
        bitmap: Bitmap, blocks: List<OcrBlock>, detections: List<BubbleDetectorCore.Det>?
    ): List<Region> {
        val imageArea = bitmap.width.toDouble() * bitmap.height
        val textDetections = (detections ?: emptyList())
            .filter { it.label == BubbleDetectorCore.CLS_TEXT_BUBBLE || it.label == BubbleDetectorCore.CLS_TEXT_FREE }
            .filter { sensibleRect(it.l, it.t, it.r, it.b, bitmap, imageArea) }
            .sortedWith(compareBy({ it.t }, { it.l }, { it.area }))
            .fold(ArrayList<BubbleDetectorCore.Det>()) { acc, det ->
                val duplicate = acc.any { kept ->
                    kept.label == det.label &&
                        boxIou(kept.l, kept.t, kept.r, kept.b, det.l, det.t, det.r, det.b) > 0.84f
                }
                if (!duplicate) acc.add(det)
                acc
            }

        val assigned = BooleanArray(blocks.size)
        val blocksForDetection = Array(textDetections.size) { ArrayList<OcrBlock>() }
        for (i in blocks.indices) {
            val block = blocks[i]
            var best = -1
            var bestArea = Long.MAX_VALUE
            for (di in textDetections.indices) {
                val d = textDetections[di]
                val margin = max(4f, min(d.w, d.h).toFloat() * 0.15f)
                if (block.cx < d.l - margin || block.cx > d.r + margin ||
                    block.cy < d.t - margin || block.cy > d.b + margin) continue
                if (d.area < bestArea) { best = di; bestArea = d.area }
            }
            if (best >= 0) {
                blocksForDetection[best].add(block)
                assigned[i] = true
            }
        }

        val result = ArrayList<Region>()
        for (di in textDetections.indices) {
            val d = textDetections[di]
            val local = blocksForDetection[di]
            val geometry = if (local.isNotEmpty()) unionRect(local, bitmap) else intArrayOf(d.l, d.t, d.r, d.b)
            result.add(
                Region(
                    d.l, d.t, d.r, d.b,
                    geometry[0], geometry[1], geometry[2], geometry[3],
                    local.toList(),
                    if (d.label == BubbleDetectorCore.CLS_TEXT_FREE) "detector-free" else "detector-bubble"
                )
            )
        }

        val remaining = blocks.indices.filterNot { assigned[it] }
        if (remaining.isNotEmpty()) {
            val groups = BubbleGrouping.groups(blocks, remaining) { a, b ->
                BubbleDetectorCore.inDifferentBubbles(detections, a.cx, a.cy, b.cx, b.cy) ||
                    safeOutlineBetween(bitmap, a, b) ||
                    safeOutlineCrossing(bitmap, a, b, blocks)
            }
            for (group in groups) {
                if (group.isEmpty()) continue
                val local = group.map { blocks[it] }
                val box = unionRect(local, bitmap)
                result.add(
                    Region(
                        box[0], box[1], box[2], box[3],
                        box[0], box[1], box[2], box[3], local,
                        if (local.any { it.vertical }) "local-vertical" else "local-bubble-group"
                    )
                )
            }
        }
        return result.sortedWith(compareBy({ it.textTop }, { it.textLeft }))
    }

    private fun sensibleRect(l: Int, t: Int, r: Int, b: Int, bitmap: Bitmap, imageArea: Double): Boolean {
        val left = l.coerceIn(0, bitmap.width)
        val top = t.coerceIn(0, bitmap.height)
        val right = r.coerceIn(0, bitmap.width)
        val bottom = b.coerceIn(0, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return false
        val area = (right - left).toDouble() * (bottom - top)
        return area < imageArea * MAX_REGION_AREA_FRACTION &&
            !(right - left >= bitmap.width * 0.94f && bottom - top >= bitmap.height * 0.94f)
    }

    private fun unionRect(blocks: List<OcrBlock>, bitmap: Bitmap): IntArray {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (block in blocks) {
            l = min(l, (block.cx - block.w / 2f).toInt())
            t = min(t, (block.cy - block.h / 2f).toInt())
            r = max(r, kotlin.math.ceil((block.cx + block.w / 2f).toDouble()).toInt())
            b = max(b, kotlin.math.ceil((block.cy + block.h / 2f).toDouble()).toInt())
        }
        return intArrayOf(
            l.coerceIn(0, bitmap.width - 1), t.coerceIn(0, bitmap.height - 1),
            r.coerceIn(1, bitmap.width), b.coerceIn(1, bitmap.height)
        )
    }

    private fun boxIou(
        al: Int, at: Int, ar: Int, ab: Int, bl: Int, bt: Int, br: Int, bb: Int
    ): Float {
        val iw = min(ar, br) - max(al, bl)
        val ih = min(ab, bb) - max(at, bt)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toDouble() * ih
        val aa = max(0, ar - al).toDouble() * max(0, ab - at)
        val ba = max(0, br - bl).toDouble() * max(0, bb - bt)
        return (inter / (aa + ba - inter).coerceAtLeast(1.0)).toFloat()
    }

    private fun safeOutlineBetween(bitmap: Bitmap, a: OcrBlock, b: OcrBlock): Boolean =
        try { OcrPost.outlineBetween(bitmap, a, b) } catch (_: Throwable) { false }

    private fun safeOutlineCrossing(bitmap: Bitmap, a: OcrBlock, b: OcrBlock, all: List<OcrBlock>): Boolean =
        try { OcrPost.outlineCrossing(bitmap, a, b, all) } catch (_: Throwable) { false }

    private fun cropFor(bitmap: Bitmap, region: Region): Bitmap? {
        val font = region.localBlocks.map {
            it.fontPx.takeIf { size -> size.isFinite() && size > 0f }
                ?: min(it.w, it.h).takeIf { size -> size.isFinite() && size > 0f }
                ?: 8f
        }.sorted().let { if (it.isEmpty()) 10f else it[it.size / 2] }.coerceIn(4f, 48f)
        val padX = max(5, min(34, (font * 0.9f).toInt()))
        val padY = max(5, min(34, (font * 0.75f).toInt()))
        val l = (region.cropLeft - padX).coerceIn(0, bitmap.width - 1)
        val t = (region.cropTop - padY).coerceIn(0, bitmap.height - 1)
        val r = (region.cropRight + padX).coerceIn(l + 1, bitmap.width)
        val b = (region.cropBottom + padY).coerceIn(t + 1, bitmap.height)
        if (r - l < 8 || b - t < 8) return null
        val area = (r - l).toDouble() * (b - t)
        val pageArea = bitmap.width.toDouble() * bitmap.height
        if (area >= pageArea * MAX_REGION_AREA_FRACTION ||
            (r - l >= bitmap.width * 0.94f && b - t >= bitmap.height * 0.94f)) return null
        return try { Bitmap.createBitmap(bitmap, l, t, r - l, b - t) } catch (_: Throwable) { null }
    }

    private fun encodeDataUrl(crop: Bitmap): String {
        val out = ByteArrayOutputStream()
        if (!crop.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            throw IOException("Could not encode OCR crop")
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(image: String): String {
        val payload = JSONObject().put("image", image).put("mode", "plain")
            .toString().toByteArray(StandardCharsets.UTF_8)
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Origin", ORIGIN)
            connection.setRequestProperty("Referer", REFERER)
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
            )
            connection.outputStream.use { it.write(payload); it.flush() }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    sb.append(buffer, 0, count)
                    if (sb.length > MAX_RESPONSE_CHARS) throw IOException("OCR response too large")
                }
                sb.toString()
            }.orEmpty()
            if (status !in 200..299) {
                throw IOException("HTTP ${status}" + if (body.isNotBlank()) ": ${body.take(100)}" else "")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseText(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        val parsed = try { JSONTokener(value).nextValue() } catch (_: Throwable) {
            return if (value.startsWith("{") || value.startsWith("[")) "" else value
        }
        return extractText(parsed, 0).replace("\r\n", "\n").trim()
    }

    private fun extractText(value: Any?, depth: Int): String {
        if (depth > 7 || value == null || value == JSONObject.NULL) return ""
        return when (value) {
            is String -> value.trim().take(MAX_RESPONSE_CHARS)
            is JSONObject -> {
                for (key in arrayOf("text", "output", "result", "content", "data", "transcription", "plain_text", "plainText", "answer")) {
                    if (!value.has(key)) continue
                    val found = extractText(value.opt(key), depth + 1)
                    if (found.isNotBlank()) return found
                }
                for (key in arrayOf("choices", "message", "response", "generated_text")) {
                    if (!value.has(key)) continue
                    val found = extractText(value.opt(key), depth + 1)
                    if (found.isNotBlank()) return found
                }
                ""
            }
            is JSONArray -> {
                val parts = ArrayList<String>()
                for (i in 0 until value.length()) {
                    val part = extractText(value.opt(i), depth + 1)
                    if (part.isNotBlank()) parts.add(part)
                }
                parts.joinToString("\n")
            }
            else -> ""
        }
    }

    private fun toOutputBlock(text: String, region: Region): OcrBlock {
        val left = region.textLeft
        val top = region.textTop
        val right = max(left + 1, region.textRight)
        val bottom = max(top + 1, region.textBottom)
        val local = region.localBlocks
        val vertical = if (local.isNotEmpty()) local.count { it.vertical } * 2 >= local.size
            else (bottom - top) > (right - left) * 1.2f
        val angle = local.map { it.angle }.filter { it.isFinite() }.let {
            if (it.isEmpty()) 0f else it.average().toFloat()
        }
        val font = local.map { it.fontPx }.filter { it.isFinite() && it > 0f }.let {
            if (it.isEmpty()) min(right - left, bottom - top).toFloat() else it.average().toFloat()
        }
        val confidence = local.map { it.confidence }.filter { it.isFinite() }.let {
            if (it.isEmpty()) 0f else it.average().toFloat()
        }
        return OcrBlock(
            text = text.trim(), top = top, left = left,
            cx = (left + right) / 2f, cy = (top + bottom) / 2f,
            w = (right - left).toFloat(), h = (bottom - top).toFloat(),
            angle = angle, fontPx = font, vertical = vertical, confidence = confidence,
            observations = max(1, local.maxOfOrNull { it.observations } ?: 1)
        )
    }

    override fun close() {
        closed = true
        try { OcrGate.run { localLocator.close() } } catch (_: Throwable) {}
    }
}
