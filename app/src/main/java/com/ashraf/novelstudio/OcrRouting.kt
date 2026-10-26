package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/*
 * OCR routing. Exactly two engines exist: Google Lens (online, optional) and ML Kit (on-device, default and
 * the fallback for every Lens failure). Older builds also had Paddle / RapidOCR / Japanese-manga engines; they
 * were removed because they did not read pages reliably on phones.
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
    /** Last Lens failure that made the router fall back to ML Kit (shown in the OCR engine dialog). */
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
        used == "lens(cancelled)" -> "Google Lens (cancelled)"
        used == "mlkit(lens-error-fallback)" -> "ML Kit (Lens request failed)"
        used == "mlkit(lens-empty-fallback)" -> "ML Kit (Lens found no text)"
        used == "mlkit(lens-select-error)" -> "ML Kit (Lens unavailable)"
        used.startsWith("mlkit") && fellBack -> "ML Kit (fallback)"
        used.startsWith("mlkit") -> "ML Kit"
        else -> ""
    }
}

internal class RoutingOcrEngine : OcrEngine {
    private companion object {
        /** Cap on how long a foreground Lens route waits for the shared gate (see OcrGate.run(timeoutMs)). */
        const val FOREGROUND_LENS_GATE_TIMEOUT_MS = 1_200L
    }

    @Volatile private var appContext: Context? = null
    private val mlKit: OcrEngine = MlKitOcrEngine()
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
        // Only ML Kit and Google Lens exist. A value saved by an older build (paddle_*, rapid_onnx, manga)
        // is migrated to ML Kit once, so nothing keeps pointing at a deleted engine.
        var requested = Prefs.get(context, "ocrEngine", "mlkit")
        if (requested != "mlkit" && requested != "lens") {
            Prefs.put(context, "ocrEngine", "mlkit")
            requested = "mlkit"
        }
        // Keep the Lens connection warm (cheap no-op unless it has been idle for a while).
        if (requested == "lens") LensOcrEngine.warmUp(context)
        if (requested != selectedMode) {
            // The old engine is released by active() INSIDE OcrGate; do not close it here while a scan is running.
            selectedMode = requested
        }
    }

    private fun active(@Suppress("UNUSED_PARAMETER") script: OcrScript): OcrEngine {
        val mode = selectedMode
        synchronized(this) {
            if (activeCustom != null && activeMode != mode) {
                try { activeCustom?.close() } catch (_: Exception) {}
                activeCustom = null
                activeMode = ""
            }
        }
        if (mode != "lens") return mlKit
        synchronized(this) {
            if (activeCustom == null || activeMode != mode) {
                try { activeCustom?.close() } catch (_: Exception) {}
                val context = appContext
                    ?: throw OcrUnavailableException("OCR engine has not been initialized")
                activeCustom = LensOcrEngine(context).also { LensOcrEngine.warmUp(context) }
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
            val result = when {
                OcrGate.isBackgroundThread() -> OcrGate.run(waited, routeBlock)
                concurrentLens -> OcrGate.runLensForeground(waited, routeBlock)
                // Foreground with Lens selected but not yet active (first call / activation in progress): the
                // route body still initializes Lens. Bound the gate wait so this never stalls unboundedly
                // behind a local inference (the "concurrentLens=false, gateWaitMs=300–600 ms" case). ML Kit
                // keeps the fully serialized, no-timeout gate because it shares the model the lock protects.
                selectedMode == "lens" -> OcrGate.run(waited, timeoutMs = FOREGROUND_LENS_GATE_TIMEOUT_MS, block = routeBlock)
                else -> OcrGate.run(waited, routeBlock)
            }
            // Stamp the label onto the result itself. The global OcrEngineStatus.label is overwritten by any
            // concurrent route (read-ahead / a later empty frame), which made the "OCR: … engine=" line claim
            // "no text found" while blocks were actually returned. The per-result label can never be stale.
            result.engineLabel = OcrEngineStatus.describe(selectedMode, used, fellBack)
            return result
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
                OcrEngineStatus.lastError = "lens ${script.name}: ${e.message ?: e.javaClass.simpleName}; using ML Kit"
                report("mlkit(lens-select-error)", true)
                lensThread.set(false)
                return run(mlKit)
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
            } catch (e: InterruptedException) {
                // A newer viewport owns the screen. Do not turn a cancelled OCR job into a slow ML Kit retry.
                Thread.currentThread().interrupt()
                OcrEngineStatus.lastError = "Google Lens request cancelled"
                lensThread.set(false)
                report("lens(cancelled)", false)
                return OcrResult(emptyList())
            } catch (e: Exception) {
                failure = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160)
                Log.w("NovelStudioOCR", "lens failed unexpectedly", e)
            } catch (e: OutOfMemoryError) {
                failure = "out of memory"
            }
            if (failure != null) {
                OcrEngineStatus.lastError = "lens ${script.name}: $failure"
                Log.w("NovelStudioOCR", "lens unavailable ($failure)")
                lensThread.set(false)
                if (failure.endsWith("cancelled", ignoreCase = true)) {
                    report("lens(cancelled)", false)
                    return OcrResult(emptyList())
                }
                // Lens is an unofficial online endpoint; keep the reading flow alive with on-device OCR.
                report("mlkit(lens-error-fallback)", true)
                return try { run(mlKit) } catch (fallback: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.i("NovelStudioOCR", "ML Kit fallback cancelled after Lens error; newer OCR job owns the viewport")
                    OcrResult(emptyList())
                } catch (fallback: Exception) {
                    Log.w("NovelStudioOCR", "ML Kit fallback also failed after Lens error", fallback)
                    OcrEngineStatus.lastError += "; ML Kit fallback failed: ${fallback.message ?: fallback.javaClass.simpleName}"
                    OcrResult(emptyList())
                } catch (oom: OutOfMemoryError) {
                    OcrEngineStatus.lastError += "; ML Kit fallback ran out of memory"
                    OcrResult(emptyList())
                }
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
            OcrEngineStatus.lastError = "Google Lens returned no usable text for ${script.name}; trying ML Kit fallback"
            Log.w("NovelStudioOCR", OcrEngineStatus.lastError)
            // Lens answered normally (HTTP 200) and found no text: ML Kit on the same picture only repeats the work
            // (1-2 s per art-only panel; in the report it found nothing every time). Set lensEmptyFallback=1 to bring it back.
            if (lens != null && engineDetail.contains("http=200") &&
                appContext?.let { Prefs.get(it, "lensEmptyFallback", "0") } != "1") {
                report("lens(empty)", false)
                lensThread.set(true)
                return lens ?: OcrResult(emptyList())
            }
            report("mlkit(lens-empty-fallback)", true)
            lensThread.set(false)
            return try { run(mlKit) } catch (fallback: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.i("NovelStudioOCR", "ML Kit fallback cancelled after empty Lens result; newer OCR job owns the viewport")
                OcrResult(emptyList())
            } catch (fallback: Exception) {
                Log.w("NovelStudioOCR", "ML Kit fallback failed after empty Lens result", fallback)
                OcrEngineStatus.lastError += "; ML Kit fallback failed: ${fallback.message ?: fallback.javaClass.simpleName}"
                OcrResult(emptyList())
            } catch (oom: OutOfMemoryError) {
                OcrEngineStatus.lastError += "; ML Kit fallback ran out of memory"
                OcrResult(emptyList())
            }
        }

        // Only Lens and ML Kit exist: any other engine object is unreachable, but never leave a scan unanswered.
        report("mlkit", selectedMode != "mlkit")
        return run(mlKit)
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognize(bitmap, script) }

    /** A user-triggered Extract bypasses the Google Lens result cache. */
    fun recognizeFresh(bitmap: Bitmap, script: OcrScript, fastMode: Boolean = false): OcrResult =
        route(bitmap, script) {
            when (it) {
                is LensOcrEngine -> it.recognizeFresh(bitmap, script, fastMode)
                else -> if (fastMode) it.recognizeFast(bitmap, script) else it.recognize(bitmap, script)
            }
        }

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
    }
}
