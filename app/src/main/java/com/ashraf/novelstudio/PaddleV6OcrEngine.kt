package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.OCREngine as PaddleRuntime
import com.paddle.ocr.util.OpenCVUtils
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Adapter from PaddleOCR's official PP-OCRv6 Android pipeline to NovelStudio's OcrEngine contract.
 * Keeps original text quadrilaterals so current placement/layout code can use detected geometry.
 */
internal class PaddleV6OcrEngine(
    context: Context,
    private val tier: String
) : OcrEngine {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var runtime: PaddleRuntime? = null
    private var loadedVersion = -1
    private var failedVersion = -1
    private var failureMessage: String? = null

    private fun pack(): PaddleOcrModels.Pack =
        PaddleOcrModels.pack(tier) ?: throw OcrUnavailableException("Unknown PaddleOCR profile: $tier")

    @Synchronized
    private fun getRuntime(): PaddleRuntime {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw OcrUnavailableException("PaddleOCR Android runtime requires Android 8.0 or newer; using ML Kit")
        }
        PaddleOcrModels.init(appContext)
        val currentVersion = PaddleOcrModels.version
        if (currentVersion != loadedVersion) {
            try { runtime?.release() } catch (_: Exception) {}
            runtime = null
            loadedVersion = currentVersion
            failedVersion = -1
            failureMessage = null
        }
        if (failedVersion == currentVersion) {
            throw OcrUnavailableException(failureMessage ?: "PaddleOCR initialization failed")
        }
        val p = pack()
        if (!PaddleOcrModels.isInstalled(p)) {
            throw OcrUnavailableException("${p.label} is not downloaded")
        }
        if (!OpenCVUtils.init(appContext)) {
            failedVersion = currentVersion
            failureMessage = "OpenCV could not initialize for PaddleOCR"
            throw OcrUnavailableException(failureMessage!!)
        }
        val existing = runtime
        if (existing != null && currentVersion == loadedVersion) return existing
        val files = PaddleOcrModels.files(p)
        val config = PaddleOCRConfig(
            detImgMode = "BGR",
            detLimitSideLen = 64,
            detLimitType = "min",
            // Unchanged default (3000). "paddleDetMaxSide" lets you try a lower cap on-device WITHOUT a rebuild;
            // it is an experiment, not an established optimisation: compare detInferMs and recognition quality
            // in the NovelStudioPaddle log before changing the default.
            detMaxSideLimit = (Prefs.get(appContext, "paddleDetMaxSide", "2400").toIntOrNull() ?: 2400).coerceIn(640, 4000),
            detMaxPixels = (Prefs.get(appContext, "paddleDetMaxPixels", "").toLongOrNull() ?: DeviceTier.detMaxPixels(appContext)).coerceIn(300_000L, 6_000_000L),
            detThresh = 0.20f,
            detBoxThresh = 0.45f,
            detUnclipRatio = 1.4f,
            detMaxCandidates = 3000,
            detUseDilation = false,
            detScoreMode = "fast",
            detBoxType = "quad",
            recScoreThresh = 0f,
            recBatchSize = p.recognitionBatchSize
        )
        val created = try {
            PaddleRuntime(
                appContext,
                config,
                EngineConfig(numThreads = DeviceTier.threads(appContext, p.threads)),
                files.detector.absolutePath,
                files.recognition.absolutePath,
                files.config.absolutePath
            )
        } catch (e: Exception) {
            failureMessage = "Could not initialize ${p.label}: ${e.message ?: e.javaClass.simpleName}"
            failedVersion = currentVersion
            throw OcrUnavailableException(failureMessage!!, e)
        } catch (e: UnsatisfiedLinkError) {
            failureMessage = "PaddleOCR native libraries are unavailable"
            failedVersion = currentVersion
            throw OcrUnavailableException(failureMessage!!, e)
        } catch (e: OutOfMemoryError) {
            failureMessage = "Not enough RAM to load ${p.label}; use Small or ML Kit"
            failedVersion = currentVersion
            throw OcrUnavailableException(failureMessage!!, e)
        }
        runtime = created
        loadedVersion = currentVersion
        return created
    }

    private fun run(bitmap: Bitmap, script: OcrScript): OcrResult = synchronized(lock) {
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return@synchronized OcrResult(emptyList())
        val tLoad = android.os.SystemClock.elapsedRealtime()
        val cold = runtime == null
        val rt = getRuntime()
        val loadMs = android.os.SystemClock.elapsedRealtime() - tLoad
        val tRun = android.os.SystemClock.elapsedRealtime()
        val result = rt.run(bitmap)
        // Diagnostics: ids, sizes and timings only (never recognised text).
        Log.i("NovelStudioPaddle", "engine=PADDLE tier=$tier language=${script.name} modelLoadMs=$loadMs coldLoad=$cold " +
            "image=${bitmap.width}x${bitmap.height} detInput=${result.detInputShape} " +
            "detPreMs=${result.detPreprocessMs} detInferMs=${result.detInferenceMs} detPostMs=${result.detPostprocessMs} " +
            "recMs=${result.recognitionTimeMs} lines=${result.lineCount} " +
            "runMs=${android.os.SystemClock.elapsedRealtime() - tRun}")
        val blocks = ArrayList<OcrBlock>(result.results.size)
        for (item in result.results) {
            val text = item.text.trim()
            if (text.isEmpty()) continue
            val points = item.box.points.map { Pair(it.x, it.y) }
            if (points.size != 4) continue
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var cx = 0f
            var cy = 0f
            for (pt in points) {
                minX = min(minX, pt.first); maxX = max(maxX, pt.first)
                minY = min(minY, pt.second); maxY = max(maxY, pt.second)
                cx += pt.first; cy += pt.second
            }
            cx /= points.size
            cy /= points.size
            val p0 = points[0]
            val p1 = points[1]
            val p2 = points[2]
            // Match ML Kit's coordinate contract: centre + rotated edge dimensions, while top/left
            // keep the full axis-aligned bounds for clipping, rescan and bubble-interior sampling.
            val w = hypot((p1.first - p0.first).toDouble(), (p1.second - p0.second).toDouble()).toFloat().coerceAtLeast(1f)
            val h = hypot((p2.first - p1.first).toDouble(), (p2.second - p1.second).toDouble()).toFloat().coerceAtLeast(1f)
            val rawAngle = Math.toDegrees(atan2((p1.second - p0.second).toDouble(), (p1.first - p0.first).toDouble())).toFloat()
            val angle = OcrPost.foldAngle(rawAngle)
            val vertical = script != OcrScript.LATIN && h > w * 1.25f
            val block = OcrBlock(
                text = text,
                top = minY.toInt().coerceAtLeast(0),
                left = minX.toInt().coerceAtLeast(0),
                cx = cx,
                cy = cy,
                w = w,
                h = h,
                angle = angle,
                fontPx = if (vertical) w else h,
                vertical = vertical,
                confidence = item.confidence.coerceIn(0f, 1f),
                observations = 1,
                polygon = points.map { Pair(it.first.coerceIn(0f, bitmap.width.toFloat()), it.second.coerceIn(0f, bitmap.height.toFloat())) }
            )
            if (!OcrPost.isNoise(block)) blocks.add(block)
        }

        val ordered = if (script != OcrScript.LATIN && blocks.any { it.vertical }) {
            blocks.sortedWith(compareByDescending<OcrBlock> { it.cx }.thenBy { it.top })
        } else {
            blocks.sortedWith(compareBy<OcrBlock> { it.cy }.thenBy { it.left })
        }
        OcrResult(if (script == OcrScript.LATIN) ordered else OcrPost.mergeNeighbours(ordered, bitmap))
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult = run(bitmap, script)
    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = run(bitmap, script)
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = run(bitmap, script)
    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = run(bitmap, script)

    // Lock order is always lock -> this (run() holds [lock] and then enters getRuntime()), so close() cannot
    // release the ONNX sessions while an inference is still using them, and cannot deadlock with run().
    override fun close() {
      synchronized(lock) {
        synchronized(this) {
            try { runtime?.release() } catch (e: Exception) {
                Log.w("NovelStudioPaddle", "PaddleOCR release failed: ${e.message}")
            }
            runtime = null
            loadedVersion = -1
            failedVersion = -1
            failureMessage = null
        }
      }
    }
}
