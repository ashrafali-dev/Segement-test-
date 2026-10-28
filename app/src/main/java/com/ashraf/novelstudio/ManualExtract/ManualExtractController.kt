package com.ashraf.novelstudio.manualextract

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ashraf.novelstudio.OcrDiag
import com.ashraf.novelstudio.OcrScript
import java.util.concurrent.atomic.AtomicInteger

/**
 * Entry point for the low-RAM manual-extract mode.
 *
 * One tap = one run. A second tap cancels the first cleanly (its result is dropped, its bitmaps are recycled).
 * There is no polling, prefetch, ahead thread or queued background work: this object starts one worker thread,
 * and when it finishes everything is idle again.
 */
internal object ManualExtractController {

    enum class State { IDLE, RUNNING }

    @Volatile var state: State = State.IDLE
        private set

    private val runSeq = AtomicInteger(0)
    private var ocr: ManualExtractOcr? = null
    private var worker: Thread? = null

    /** True once wired by the Activity (capture + placement available). */
    @Volatile private var host: ManualExtractPipeline.Host? = null

    fun isEnabled(): Boolean = ManualExtractConfig.ENABLED && host != null

    /** The Activity installs its capture/place adapter here (called once, on the main thread). */
    fun attach(host: ManualExtractPipeline.Host) { this.host = host }

    /**
     * Main-thread reporting of unsuccessful runs, wired by the host. Failure reasons are short and concrete
     * ("Capture failed", "No text in that area", "No translation") so the tap never looks like a dead control.
     */
    @Volatile
    var onFailure: (String) -> Unit = {}

    private fun notifyFailure(reason: String) {
        val shown = when {
            reason.startsWith("capture") -> "Capture failed — scroll so the bubble is on screen"
            reason.startsWith("no text") -> "No text found in that area"
            reason.startsWith("crop out of bounds") -> "That area is too small — tap the bubble again"
            reason.startsWith("translate") -> "Translation failed — check the chatbot"
            reason.startsWith("cancelled") -> return
            else -> "Extract failed: $reason"
        }
        try { onFailure(shown) } catch (_: Throwable) {}
    }

    /**
     * Starts one extract for the tapped region. Safe to call from the main thread. If a run is already in
     * flight it is cancelled first.
     */
    fun startExtract(context: Context, src: String, x: Float, y: Float, w: Float, h: Float, script: OcrScript) {
        startExtractInternal(context, src, x, y, w, h, script, preset = null)
    }

    /**
     * Runs on pixels the Activity already captured (the 6-dot frame). The bitmap is owned by this run and is
     * recycled when the pipeline finishes; the caller must not touch it afterwards.
     */
    fun startExtractWithBitmap(context: Context, src: String, bitmap: android.graphics.Bitmap, script: OcrScript) {
        // `src` identifies the on-screen image the overlay must anchor to. It used to be blank, so the
        // writer could not find any <img> for this bubble and simply hid it: the translation never
        // appeared at all in the 6-dot frame path.
        startExtractInternal(context, src, 0f, 0f, 1f, 1f, script, preset = bitmap)
    }

    private fun startExtractInternal(
        context: Context, src: String, x: Float, y: Float, w: Float, h: Float, script: OcrScript,
        preset: android.graphics.Bitmap?
    ) {
        if (!ManualExtractConfig.ENABLED) {
            OcrDiag.log("MANUAL", "skip: mode disabled")
            preset?.recycle()
            return
        }
        val attached = host ?: run {
            OcrDiag.log("MANUAL", "skip: host not attached (installManualExtractHost never ran)")
            preset?.recycle()
            return
        }
        // Single flight: a tap that arrives while a run is in progress is dropped, not queued. Touches arrive
        // several times per second on some readers and each one used to cancel the previous run, so nothing
        // ever finished and the log filled with 1 ms "capture failed" runs.
        if (state == State.RUNNING) {
            OcrDiag.log("MANUAL", "skip: a run is already in flight")
            preset?.recycle()
            return
        }
        cancel()

        OcrDiag.setMode("manual")
        OcrDiag.log(
            "MANUAL",
            if (preset != null) "start frame=${preset.width}x${preset.height} script=${script.name}"
            else "start src=${src.take(90)} rect=${"%.3f".format(x)},${"%.3f".format(y)} ${"%.3f".format(w)}x${"%.3f".format(h)} script=${script.name}"
        )
        val token = runSeq.incrementAndGet()
        val o = ocr ?: ManualExtractOcr(context.applicationContext).also { ocr = it }
        val pipeline = ManualExtractPipeline(o, attached)
        state = State.RUNNING
        val t = Thread({
            try {
                if (token != runSeq.get()) {
                    preset?.recycle()
                    return@Thread   // a newer tap owns the screen
                }
                val result = if (preset != null) pipeline.runOnBitmap(preset, src, script) { token != runSeq.get() }
                    else pipeline.run(src, x, y, w, h, script) { token != runSeq.get() }
                // Tell the user why nothing appeared. Silent failure was the whole complaint.
                if (!result.ok && token == runSeq.get()) notifyFailure(result.reason)
            } catch (e: Throwable) {
                // never let one extract crash the reader, but never swallow the reason either
                OcrDiag.log("MANUAL", "crashed ${e.javaClass.simpleName}: ${e.message?.take(140)}")
                Log.w(ManualExtractConfig.TAG, "manual extract crashed", e)
                if (token == runSeq.get()) notifyFailure("Extract failed")
            } finally {
                if (token == runSeq.get()) state = State.IDLE
            }
        }, "ns-manual-extract")
        t.isDaemon = true
        worker = t
        t.start()
    }

    /**
     * Cancels the in-flight run. The worker checks its token before every stage, so a stale run does not place
     * anything. Called on each new tap and when the reader is left.
     */
    fun cancel() {
        runSeq.incrementAndGet()
        val w = worker
        worker = null
        // The worker is cooperative: dropping our token makes it stop placing; interrupt unblocks a sleep.
        try { w?.interrupt() } catch (_: Throwable) {}
        state = State.IDLE
    }

    /** Releases the OCR engines (their models) when the reader is destroyed. */
    fun release() {
        cancel()
        try { ocr?.close() } catch (_: Throwable) {}
        ocr = null
        host = null
    }

    /** Milliseconds since the current run started, or -1 when idle (metrics helper). */
    fun runningFor(): Long = if (state == State.RUNNING) SystemClock.elapsedRealtime() else -1L
}
