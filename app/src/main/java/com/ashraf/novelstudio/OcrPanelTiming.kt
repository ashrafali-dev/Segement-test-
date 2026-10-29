package com.ashraf.novelstudio

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Per-panel timing. cold=first actual Lens request in this process; hot=subsequent Lens-request panels. */
internal object OcrPanelTiming {
    private data class Frame(
        val panel: Int,
        val startedAtMs: Long,
        val host: String,
        var lensCalls: Int = 0,
        var lensBucket: String = "",
        var ocrMs: Long = 0L,
        var cleanMs: Long = 0L
    )
    private val active = ThreadLocal<Frame?>()
    private val firstLensCall = AtomicInteger(0)
    private val statsLock = Any()
    private var coldPanels = 0L
    private var hotPanels = 0L
    private var coldTotalMs = 0L
    private var hotTotalMs = 0L
    private data class HostBucketCounts(var cold: Long = 0L, var hot: Long = 0L)
    private val hostBuckets = HashMap<String, HostBucketCounts>()

    fun begin(panel: Int, startedAtMs: Long, prefilterMs: Long = 0L, host: String = "?") {
        if (panel <= 0) { active.remove(); return }
        active.set(Frame(
            panel = panel,
            startedAtMs = if (startedAtMs > 0L) startedAtMs else SystemClock.elapsedRealtime(),
            host = host.lowercase(Locale.US).ifBlank { "?" },
            ocrMs = prefilterMs.coerceAtLeast(0L)
        ))
    }

    fun hostBucketCounts(host: String): Pair<Long, Long> = synchronized(statsLock) {
        val counts = hostBuckets[host.lowercase(Locale.US).ifBlank { "?" }]
        (counts?.cold ?: 0L) to (counts?.hot ?: 0L)
    }

    fun noteLensCall() {
        val frame = active.get() ?: return
        frame.lensCalls++
        if (frame.lensBucket.isEmpty()) frame.lensBucket =
            if (firstLensCall.getAndIncrement() == 0) "cold" else "hot"
    }

    fun addOcr(ms: Long) { active.get()?.let { it.ocrMs += ms.coerceAtLeast(0L) } }
    fun addClean(ms: Long) { active.get()?.let { it.cleanMs += ms.coerceAtLeast(0L) } }

    fun finish() {
        val frame = active.get() ?: return
        active.remove()
        val totalMs = (SystemClock.elapsedRealtime() - frame.startedAtMs).coerceAtLeast(0L)
        val bucket = frame.lensBucket.ifBlank { "none" }
        OcrDiag.log("TIMING", "panel=${frame.panel} lens=$bucket ocrMs=${frame.ocrMs} " +
            "cleanMs=${frame.cleanMs} totalMs=$totalMs lensCalls=${frame.lensCalls}")
        if (bucket == "cold" || bucket == "hot") {
            val summary: String
            synchronized(statsLock) {
                if (bucket == "cold") {
                    coldPanels++; coldTotalMs += totalMs
                    hostBuckets.getOrPut(frame.host) { HostBucketCounts() }.cold++
                } else {
                    hotPanels++; hotTotalMs += totalMs
                    hostBuckets.getOrPut(frame.host) { HostBucketCounts() }.hot++
                }
                val avgCold = if (coldPanels == 0L) 0L else coldTotalMs / coldPanels
                val avgHot = if (hotPanels == 0L) 0L else hotTotalMs / hotPanels
                summary = "cold=$coldPanels hot=$hotPanels avgCold=$avgCold avgHot=$avgHot metric=totalMs"
            }
            OcrDiag.log("TIMING_BUCKET", summary)
        }
    }

    fun logNoLens(panel: Int, startedAtMs: Long, prefilterMs: Long, reason: String) {
        val totalMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        OcrDiag.log("TIMING", "panel=$panel lens=none ocrMs=$prefilterMs cleanMs=0 totalMs=$totalMs lensCalls=0 reason=$reason")
    }
}
