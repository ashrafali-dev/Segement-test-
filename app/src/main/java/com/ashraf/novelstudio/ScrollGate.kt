package com.ashraf.novelstudio

/**
 * Tells the real-time OCR loop whether the reader is flicking through the page (OCR would be wasted: the panel
 * is gone before the result arrives) or reading it (slow scroll or standing still: OCR runs normally).
 *
 * Pure Kotlin, time is passed in, so it is unit-tested on the JVM. Only fed by native WebView scroll events; a page
 * that scrolls an inner element produces no events here, and then nothing is ever held back (old behaviour).
 */
internal object ScrollGate {
    /** Fast = faster than this many screen heights per second. A slow, steady read stays below it. */
    const val FAST_SCREENS_PER_SECOND = 0.6f
    /** Velocity is measured over the last events inside this window. */
    private const val WINDOW_MS = 220L
    /** After the last fast scroll event, OCR keeps waiting this long (the flick may still be coasting). */
    const val CALM_MS = 250L
    /** Events older than this are not "scrolling now". */
    private const val STALE_MS = 160L

    private val times = LongArray(12)
    private val ys = IntArray(12)
    private var count = 0
    private var next = 0
    private var lastFastAt = Long.MIN_VALUE / 2

    @Synchronized
    fun reset() { count = 0; next = 0; lastFastAt = Long.MIN_VALUE / 2 }

    /** Call from the WebView scroll listener. [viewportPx] is the visible height of the page. */
    @Synchronized
    fun onScroll(nowMs: Long, scrollY: Int, viewportPx: Int) {
        times[next] = nowMs; ys[next] = scrollY
        next = (next + 1) % times.size
        if (count < times.size) count++
        if (velocityScreensPerSecond(nowMs, viewportPx) > FAST_SCREENS_PER_SECOND) lastFastAt = nowMs
    }

    /** True while OCR should wait: a fast scroll is happening, or one ended less than [CALM_MS] ago. */
    @Synchronized
    fun holdOcr(nowMs: Long, viewportPx: Int): Boolean {
        if (count == 0) return false
        if (velocityScreensPerSecond(nowMs, viewportPx) > FAST_SCREENS_PER_SECOND) return true
        return nowMs - lastFastAt < CALM_MS
    }

    /** Current speed in screen heights per second (0 when there are no fresh events). */
    @Synchronized
    private fun velocityScreensPerSecond(nowMs: Long, viewportPx: Int): Float {
        if (count < 2 || viewportPx <= 0) return 0f
        val newestIdx = (next - 1 + times.size) % times.size
        val tNew = times[newestIdx]
        if (nowMs - tNew > STALE_MS) return 0f
        var oldestIdx = newestIdx
        for (k in 1 until count) {
            val i = (newestIdx - k + times.size) % times.size
            if (tNew - times[i] > WINDOW_MS) break
            oldestIdx = i
        }
        val dt = tNew - times[oldestIdx]
        if (dt < 30L) return 0f
        val dy = Math.abs(ys[newestIdx] - ys[oldestIdx])
        return dy.toFloat() / viewportPx / (dt / 1000f)
    }
}
