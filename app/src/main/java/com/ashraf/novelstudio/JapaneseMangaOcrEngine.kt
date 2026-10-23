package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ashraf.novelstudio.japanesemanga.JapaneseMangaRuntime
import org.json.JSONObject

/**
 * Adapter between the downloaded manga model (see [JapaneseMangaRuntime]) and the app's [OcrEngine]
 * surface. Every region the model returns is one speech-bubble-sized block whose text is already in
 * Japanese reading order (vertical columns right-to-left), so nothing downstream has to re-order it.
 */
internal class JapaneseMangaOcrEngine : OcrEngine {
    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult {
        val out = JapaneseMangaRuntime.recognize(bitmap)
        val regions = JSONObject(out.json).optJSONArray("regions") ?: return OcrResult(emptyList())
        val k = if (out.scale > 0f) 1f / out.scale else 1f
        val blocks = ArrayList<OcrBlock>()
        for (i in 0 until regions.length()) {
            val r = regions.optJSONObject(i) ?: continue
            val text = r.optString("text").trim()
            if (text.isEmpty()) continue
            val x = r.optDouble("x", 0.0).toFloat() * k
            val y = r.optDouble("y", 0.0).toFloat() * k
            val w = r.optDouble("w", 0.0).toFloat() * k
            val h = r.optDouble("h", 0.0).toFloat() * k
            if (w < 4f || h < 4f) continue
            val font = r.optDouble("font", 0.0).toFloat() * k
            val block = OcrBlock(
                text = text,
                top = y.toInt(), left = x.toInt(),
                cx = x + w / 2f, cy = y + h / 2f,
                w = w, h = h, angle = 0f,
                fontPx = if (font > 0f) font else minOf(w, h),
                vertical = r.optBoolean("vertical", false)
            )
            if (!OcrPost.isNoise(block)) blocks.add(block)
        }
        return OcrResult(blocks)
    }

    override fun close() {}
}

/**
 * The engine the scan pipeline actually uses. Japanese pages go to the dedicated manga model when it
 * has been downloaded; everything else (and any manga-model failure or empty result) stays on ML Kit,
 * so real-time scanning keeps working for all languages exactly as before.
 */
/** Short, user-visible name of the engine that produced the LAST OCR result (shown in the activity chip). */
internal object OcrEngineStatus {
    @Volatile var label: String = ""
    /** Safe diagnostic from the latest FOREGROUND Lens scan; read-ahead results must not overwrite it. */
    @Volatile var lastLensDiagnostic: String = "No Google Lens request captured yet"
    private val threadLensDiagnostic = ThreadLocal<String>()
    private val threadRouteTicket = ThreadLocal<Long>()
    private val threadRouteForeground = ThreadLocal<Boolean>()
    private var nextRouteTicket = 0L
    @Volatile private var latestForegroundRouteTicket = 0L

    /** Tag every concurrent OCR call so background read-ahead cannot overwrite foreground status. */
    @Synchronized
    fun beginRoute(foreground: Boolean): Long {
        val ticket = ++nextRouteTicket
        threadRouteTicket.set(ticket)
        threadRouteForeground.set(foreground)
        if (foreground) latestForegroundRouteTicket = ticket
        return ticket
    }

    @Synchronized
    fun setLensDiagnostic(message: String) {
        OcrDiag.log("LENS", message)
        threadLensDiagnostic.set(message)
        val ticket = threadRouteTicket.get()
        if (threadRouteForeground.get() == true && ticket != null && ticket == latestForegroundRouteTicket) {
            lastLensDiagnostic = message
        }
    }

    fun currentLensDiagnostic(): String = threadLensDiagnostic.get() ?: lastLensDiagnostic

    @Synchronized
    fun finishRoute(ticket: Long, foreground: Boolean, actual: String) {
        // Use the most recently started visible scan, not whichever network request happened to finish last.
        if (foreground && ticket == latestForegroundRouteTicket) label = actual
        threadLensDiagnostic.remove()
        threadRouteTicket.remove()
        threadRouteForeground.remove()
    }
    /** Last Paddle failure that made the router fall back to ML Kit (shown in the OCR models dialog). */
    @Volatile var lastError: String = ""
    @Volatile private var lastToast: String = ""

    @Volatile private var lastCopied: String = ""

    @Volatile private var lastRouteToast: String = ""

    /** Announces only when the real engine changes, not on every real-time OCR frame. */
    @Synchronized
    fun announceRoute(context: Context?, selected: String, actual: String) {
        // Real-time scanning can OCR successive screenshots concurrently. A result-specific toast
        // may arrive late and say "no text found" after another screenshot was read successfully.
        // Announce the selected engine only; per-image results remain available in the logs.
        if (selected != "lens" || context == null || lastRouteToast == "Google Lens") return
        lastRouteToast = "Google Lens"
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(
                    context,
                    "OCR engine: Google Lens",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Throwable) {}
        }
    }

    /** Copies the error to the clipboard once per distinct message, so it can be pasted into a bug report. */
    fun copyOnce(context: Context?, message: String) {
        if (context == null || message == lastCopied) return
        lastCopied = message
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("NovelStudio OCR error", message))
        } catch (_: Throwable) {}
    }

    /** Shows a long toast once per distinct message (so a failing scan loop does not spam the screen). */
    fun toastOnce(context: Context?, message: String) {
        if (context == null || message == lastToast) return
        lastToast = message
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        }
    }

    fun describe(selected: String, used: String, fellBack: Boolean): String = when {
        used == "lens" -> "Google Lens"
        used == "lens(empty)" -> "Google Lens (no text found)"
        used == "lens(error)" -> "Google Lens (request failed)"
        used.startsWith("mlkit(korean-by-design") -> "ML Kit (no Korean Paddle pack)"
        used == "paddle_korean" -> "Paddle Korean v5"
        used == "mlkit(empty-primary)" -> "ML Kit (Paddle found no text)"
        used == "mlkit(oom)" -> "ML Kit (Paddle: low RAM)"
        used == "mlkit(link-error)" -> "ML Kit (Paddle native lib missing)"
        used.startsWith("mlkit(error:") -> "ML Kit (Paddle error: " + used.removePrefix("mlkit(error:").removeSuffix(")") + ")"
        used.startsWith("mlkit") && fellBack && selected.startsWith("paddle") -> "ML Kit (Paddle fallback)"
        used.startsWith("mlkit") && fellBack -> "ML Kit (fallback)"
        used.startsWith("mlkit") -> "ML Kit"
        used == "paddle_small" -> "Paddle Small"
        used == "paddle_heavy" -> "Paddle Medium"
        used == "manga" -> "Manga OCR"
        used == "rapid_onnx" -> "RapidOCR"
        else -> ""
    }
}

internal class RoutingOcrEngine : OcrEngine {
    @Volatile private var appContext: Context? = null
    private val mlKit: OcrEngine = MlKitOcrEngine()
    private val manga: OcrEngine = JapaneseMangaOcrEngine()
    @Volatile private var selectedMode: String = "mlkit"
    private var activeMode: String = ""
    private var activeCustom: OcrEngine? = null

    // Per OCR thread: was the most recent routed read answered by Lens? Visible scans and read-ahead use different threads.
    private val lensThread = ThreadLocal<Boolean>()

    /** True when the last OCR call on this thread was answered by Lens; avoids another upload for a rescan. */
    fun lastRouteUsedLens(): Boolean = lensThread.get() == true

    /** Lens itself returns text boxes, so use its normal recognition call instead of a repeated fast request. */
    fun usesRemoteTextDetection(): Boolean = selectedMode == "lens"

    /**
     * Initialize the selected Lens client before launching parallel visible-panel requests. The first
     * call still goes through OcrGate so switching away from a local model never closes it mid-inference.
     */
    fun prepareForConcurrentLens(script: OcrScript) {
        if (selectedMode != "lens" || canRunLensConcurrently()) return
        OcrGate.run { active(script) }
    }

    /** Only bypass the shared local-inference gate after the Lens client is fully active. */
    @Synchronized
    private fun canRunLensConcurrently(): Boolean =
        selectedMode == "lens" && activeMode == "lens" && activeCustom is LensOcrEngine

    /** Called by WebMode before the first OCR pass so the chosen engine follows the Settings value. */
    @Synchronized
    fun configure(context: Context) {
        appContext = context.applicationContext
        val requested = Prefs.get(context, "ocrEngine", "mlkit")
        // Keep the Lens connection warm (cheap no-op unless it has been idle for a while).
        if (requested == "lens") LensOcrEngine.warmUp(context)
        // Initialise only the model pack the user selected, and only when switching engines.
        // Lens and ML Kit do not use the downloadable ONNX/Paddle model stores.
        if (requested != selectedMode) {
            when (requested) {
                "rapid_onnx" -> OcrModels.init(context)
                "paddle_small", "paddle_heavy" -> PaddleOcrModels.init(context)
            }
            // The old engine is released by active() INSIDE OcrGate; do not close it here while a scan is running.
            selectedMode = requested
        }
    }

    private fun active(script: OcrScript): OcrEngine {
        val mode = selectedMode
        synchronized(this) {
            if (activeCustom != null && activeMode != mode) {
                try { activeCustom?.close() } catch (_: Exception) {}
                activeCustom = null
                activeMode = ""
            }
        }
        if (mode == "mlkit") return mlKit
        // The legacy ONNX recognizer runs box-by-box and is unsuitable for vertical CJK screen reading.
        // Explain the route instead of silently making a downloaded engine look active.
        if (mode == "rapid_onnx" && (script == OcrScript.KOREAN || script == OcrScript.JAPANESE)) {
            OcrEngineStatus.lastError = "RapidOCR legacy is not used for ${script.name}; screen OCR is routed to ML Kit"
            return mlKit
        }
        if (mode == "manga") {
            return if (script == OcrScript.JAPANESE && JapaneseMangaRuntime.isAvailable()) manga else {
                OcrEngineStatus.lastError = "Japanese Manga OCR is unavailable for ${script.name}; using ML Kit"
                mlKit
            }
        }
        if (mode == "paddle_small" || mode == "paddle_heavy") {
            val tier = if (mode == "paddle_small") "small" else "heavy"
            val modelPack = PaddleOcrModels.pack(tier)
            if (modelPack == null || !PaddleOcrModels.isInstalled(modelPack)) {
                // Do not attempt a doomed native model load on every scan. If a downloaded model
                // was deleted, release its session on the next OCR request to free the RAM.
                OcrEngineStatus.lastError = "${mode} selected but its OCR model pack is missing or invalid; using ML Kit"
                Log.w("NovelStudioOCR", OcrEngineStatus.lastError)
                synchronized(this) {
                    if (activeMode == mode) {
                        try { activeCustom?.close() } catch (_: Exception) {}
                        activeCustom = null
                        activeMode = ""
                    }
                }
                return mlKit
            }
        }

        synchronized(this) {
            if (activeCustom == null || activeMode != mode) {
                try { activeCustom?.close() } catch (_: Exception) {}
                val context = appContext
                    ?: throw OcrUnavailableException("OCR engine has not been initialized")
                activeCustom = when (mode) {
                    "paddle_small" -> PaddleProfileOcrEngine(context, "small")
                    "paddle_heavy" -> PaddleProfileOcrEngine(context, "heavy")
                    "rapid_onnx" -> FallbackOcrEngine(OnnxOcrEngine(), MlKitOcrEngine())
                    "lens" -> LensOcrEngine(context).also { LensOcrEngine.warmUp(context) }
                    else -> mlKit
                }
                activeMode = mode
            }
            return activeCustom ?: mlKit
        }
    }

    private fun useful(result: OcrResult): Boolean =
        result.blocks.sumOf { block -> block.text.count { ch -> ch.isLetterOrDigit() } } >= 2

    private fun route(bitmap: Bitmap, script: OcrScript, run: (OcrEngine) -> OcrResult): OcrResult {
        val waited = OcrGate.Waited()
        val t0 = android.os.SystemClock.elapsedRealtime()
        val foregroundRoute = !OcrGate.isBackgroundThread()
        val statusTicket = OcrEngineStatus.beginRoute(foregroundRoute)
        lensThread.set(false)
        var used = "?"
        var fellBack = false
        try {
            val routeBlock: () -> OcrResult = {
                routeInner(bitmap, script, run) { engine, fb -> used = engine; fellBack = fb }
            }
            // Do not queue visible Lens OCR behind a remote read-ahead request. Local model engines
            // and the first Lens activation still use the serialized gate for safe lifecycle changes.
            val concurrentLens = !OcrGate.isBackgroundThread() && canRunLensConcurrently()
            return when {
                OcrGate.isBackgroundThread() -> OcrGate.run(waited, routeBlock)
                concurrentLens -> OcrGate.runLensForeground(waited, routeBlock)
                else -> OcrGate.run(waited, routeBlock)
            }
        } finally {
            val actualLabel = OcrEngineStatus.describe(selectedMode, used, fellBack)
            OcrEngineStatus.finishRoute(statusTicket, foregroundRoute, actualLabel)
            // Tell the user which engine is selected; per-scan diagnostics remain in the foreground status.
            OcrEngineStatus.announceRoute(appContext, selectedMode, actualLabel)
            // Diagnostic line: ids/timings only, never the recognised text.
            val routeLine = "requestedEngine=$selectedMode language=${script.name} " +
                "activeEngine=$used fallbackUsed=$fellBack " +
                "job=${Thread.currentThread().name} concurrentLens=${!OcrGate.isBackgroundThread() && canRunLensConcurrently()} " +
                "gateWaitMs=${waited.ms} totalMs=${android.os.SystemClock.elapsedRealtime() - t0} " +
                "image=${bitmap.width}x${bitmap.height}" +
                (if (OcrEngineStatus.lastError.isNotBlank()) " lastRouterError=${OcrEngineStatus.lastError.take(120)}" else "")
            Log.i("NovelStudioOCR", routeLine)
            OcrDiag.log("ROUTE", routeLine)
        }
    }

    private fun routeInner(
        bitmap: Bitmap, script: OcrScript, run: (OcrEngine) -> OcrResult,
        report: (String, Boolean) -> Unit
    ): OcrResult {
        val chosen = try {
            active(script)
        } catch (e: Exception) {
            Log.w("NovelStudioOCR", "Could not select ${selectedMode}: ${e.message}")
            if (selectedMode == "lens") {
                OcrEngineStatus.lastError = "lens ${script.name}: ${e.message ?: e.javaClass.simpleName}"
                report("lens(error)", false)
                lensThread.set(false)
                return OcrResult(emptyList())
            }
            mlKit
        } catch (e: OutOfMemoryError) {
            if (selectedMode == "lens") {
                OcrEngineStatus.lastError = "lens ${script.name}: out of memory"
                report("lens(error)", false)
                lensThread.set(false)
                return OcrResult(emptyList())
            }
            mlKit
        }
        if (chosen === mlKit) { report("mlkit", selectedMode != "mlkit"); return run(mlKit) }

        if (chosen is LensOcrEngine) {
            // Lens is an online engine using an unofficial endpoint. Its failures are expected and must
            // never be reported as Paddle failures or allowed to break the scanning workflow.
            var lens: OcrResult? = null
            var failure: String? = null
            try {
                lens = run(chosen)
            } catch (e: OcrUnavailableException) {
                failure = (e.message ?: "unavailable").take(160)
                // "cancelled" = a newer scan replaced this one (scroll/page change): expected, not a Lens fault.
                if (!failure.endsWith("cancelled")) OcrEngineStatus.toastOnce(appContext, "Google Lens request failed\n$failure")
            } catch (e: Exception) {
                failure = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160)
                Log.w("NovelStudioOCR", "lens failed unexpectedly", e)
            } catch (e: OutOfMemoryError) {
                failure = "out of memory"
            }
            if (failure != null) {
                OcrEngineStatus.lastError = "lens ${script.name}: $failure"
                Log.w("NovelStudioOCR", "lens unavailable ($failure); selected Google Lens remains independent")
                report("lens(error)", false)
                lensThread.set(false)
                return OcrResult(emptyList())
            }
            val blockCount = lens?.blocks?.size ?: 0
            val letterCount = lens?.blocks?.sumOf { block -> block.text.count { ch -> ch.isLetterOrDigit() } } ?: 0
            if (lens != null && useful(lens)) {
                // Google Lens is an independent engine. Never supplement or merge its result with ML Kit.
                OcrEngineStatus.setLensDiagnostic(
                    "ROUTER_USE_LENS script=${script.name} image=${bitmap.width}x${bitmap.height} " +
                    "blocks=$blockCount letters=$letterCount; ${OcrEngineStatus.currentLensDiagnostic()}"
                )
                Log.i("NovelStudioOCR", OcrEngineStatus.currentLensDiagnostic().take(1000))
                lensThread.set(true)
                report("lens", false)
                return lens
            }
            val engineDetail = OcrEngineStatus.currentLensDiagnostic()
            val detailImageId = Regex("imageId=([0-9a-f]+)").find(engineDetail)?.groupValues?.get(1) ?: "none"
            val emptyWhy = "ROUTER_LENS_EMPTY script=${script.name} image=${bitmap.width}x${bitmap.height} " +
                "lensNull=${lens == null} blocks=$blockCount letters=$letterCount detailImageId=$detailImageId " +
                "lensDetail=${engineDetail.take(650)}"
            OcrEngineStatus.setLensDiagnostic(emptyWhy)
            Log.w("NovelStudioOCR", emptyWhy)
            // Empty Lens OCR is still a Lens result; keep engines independent.
            report("lens(empty)", false)
            lensThread.set(false)
            return OcrResult(emptyList())
        }

        return try {
            val primary = run(chosen)
            if (useful(primary)) {
                // Label what REALLY read the page: Korean is Paddle only with the Korean pack, otherwise ML Kit.
                when ((chosen as? PaddleProfileOcrEngine)?.familyFor(script)) {
                    "mlkit-korean" -> report("mlkit(korean-by-design under $selectedMode)", true)
                    "paddle-ko" -> report("paddle_korean", false)
                    else -> report(selectedMode, false)
                }
                primary
            }
            else {
                OcrEngineStatus.lastError = "$selectedMode returned no usable text for ${script.name}; using ML Kit fallback"
                Log.w("NovelStudioOCR", OcrEngineStatus.lastError)
                report("mlkit(empty-primary)", true)
                run(mlKit)
            }
        } catch (e: Exception) {
            // "detection: OrtException – <first 70 chars of ONNX Runtime's message>" (no recognised text involved).
            val root = e.cause ?: e
            val stage = Regex("stage '([^']+)'").find(e.message ?: "")?.groupValues?.get(1)
            val detail = (root.message ?: "").replace(Regex("\\s+"), " ").trim().take(150)
            val why = (if (stage != null) "$stage: " else "") + root.javaClass.simpleName +
                (if (detail.isNotEmpty()) " – $detail" else "")
            val full = (if (stage != null) "$stage: " else "") + root.javaClass.simpleName + " – " +
                (root.message ?: "").replace(Regex("\\s+"), " ").trim().take(220)
            OcrEngineStatus.lastError = "$selectedMode ${script.name}: $full"
            val engineLabel = when (selectedMode) {
                "lens" -> "Google Lens OCR"
                "manga" -> "Japanese Manga OCR"
                "rapid_onnx" -> "RapidOCR"
                "paddle_small" -> "PaddleOCR Small"
                "paddle_heavy" -> "PaddleOCR Medium"
                else -> selectedMode
            }
            OcrEngineStatus.toastOnce(appContext, "$engineLabel error → ML Kit fallback\n$full")
            OcrEngineStatus.copyOnce(appContext, "NovelStudio $engineLabel error: $full")
            Log.w("NovelStudioOCR", "${selectedMode} failed ($why); falling back to ML Kit: ${e.message}", e)
            report("mlkit(error:$why)", true)
            run(mlKit)
        } catch (e: OutOfMemoryError) {
            Log.w("NovelStudioOCR", "${selectedMode} ran out of memory; falling back to ML Kit")
            report("mlkit(oom)", true)
            run(mlKit)
        } catch (e: LinkageError) {
            Log.w("NovelStudioOCR", "${selectedMode} native runtime unavailable; falling back to ML Kit: ${e.message}")
            report("mlkit(link-error)", true)
            run(mlKit)
        }
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognize(bitmap, script) }

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognizeFast(bitmap, script) }

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognizeFallback(bitmap, script) }

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognizeRegion(bitmap, script) }

    /** Never frees an engine while it is reading: the release runs on its own thread, behind OcrGate. */
    override fun close() {
        Thread({
            try { OcrGate.run { closeNow() } } catch (_: Throwable) {}
        }, "ns-ocr-close").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun closeNow() {
        try { activeCustom?.close() } catch (_: Exception) {}
        activeCustom = null
        activeMode = ""
        mlKit.close()
        manga.close()
    }
}
