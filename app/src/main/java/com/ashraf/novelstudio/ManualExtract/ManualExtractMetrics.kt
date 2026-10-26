package com.ashraf.novelstudio.manualextract

import android.util.Log

/** Per-step timings for one manual-extract run, emitted under [ManualExtractConfig.TAG]. */
internal class ManualExtractMetrics {
    private val marks = LinkedHashMap<String, Long>()
    private val start = android.os.SystemClock.elapsedRealtime()
    var engine: String = "-"
    var cropW: Int = 0
    var cropH: Int = 0
    var ocrW: Int = 0
    var ocrH: Int = 0
    var blocks: Int = 0
    var letters: Int = 0

    fun mark(step: String) { marks[step] = android.os.SystemClock.elapsedRealtime() }

    /** Writes one line: total + the delta between consecutive marks. */
    fun log(ok: Boolean, note: String = "") {
        val total = android.os.SystemClock.elapsedRealtime() - start
        val sb = StringBuilder()
        sb.append("run ok=").append(ok).append(" total=").append(total).append("ms engine=").append(engine)
        sb.append(" crop=").append(cropW).append('x').append(cropH)
        sb.append(" ocr=").append(ocrW).append('x').append(ocrH)
        sb.append(" blocks=").append(blocks).append(" letters=").append(letters)
        var prev = start
        for ((k, v) in marks) { sb.append(' ').append(k).append('=').append(v - prev); prev = v }
        if (note.isNotBlank()) sb.append(" note=").append(note)
        Log.i(ManualExtractConfig.TAG, sb.toString())
    }
}
