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
     * Starts one extract for the tapped region. Safe to call from the main thread. If a run is already in
     * flight it is cancelled first.
     */
    fun startExtract(context: Context, src: String, x: Float, y: Float, w: Float, h: Float, script: OcrScript) {
        if (!ManualExtractConfig.ENABLED) {
            OcrDiag.log("MANUAL", "skip: mode disabled")
            return
        }
        val attached = host ?: run {
            OcrDiag.log("MANUAL", "skip: host not attached (installManualExtractHost never ran)")
            return
        }
        cancel()

        OcrDiag.log("MANUAL", "start src=${src.take(90)} rect=${"%.3f".format(x)},${"%.3f".format(y)} ${"%.3f".format(w)}x${"%.3f".format(h)} script=${script.name}")
        val token = runSeq.incrementAndGet()
        val o = ocr ?: ManualExtractOcr(context.applicationContext).also { ocr = it }
        val pipeline = ManualExtractPipeline(o, attached)
        state = State.RUNNING
        val t = Thread({
            try {
                if (token != runSeq.get()) return@Thread   // a newer tap owns the screen
                pipeline.run(src, x, y, w, h, script) { token != runSeq.get() }
            } catch (e: Throwable) {
                // never let one extract crash the reader, but never swallow the reason either
                OcrDiag.log("MANUAL", "crashed ${e.javaClass.simpleName}: ${e.message?.take(140)}")
                Log.w(ManualExtractConfig.TAG, "manual extract crashed", e)
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
