package com.ashraf.novelstudio

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One place that remembers EVERYTHING the OCR pipeline did, so a single "Copy OCR log" tap gives a
 * complete picture: device, settings, engine state, per-site statistics and a timeline of every step
 * (page discovery, candidates found/rejected, downloads, canvas capture, screenshot fallback, each OCR
 * call with timings, Google Lens requests, failures).
 *
 * The log lives in memory only (ring buffer, newest kept). OCR'd text is NOT stored, only short
 * snippets (first characters) so "OCR returned garbage" can be told apart from "OCR returned nothing".
 */
internal object OcrDiag {
    private const val MAX_CHARS = 150_000
    private val lock = Any()
    private val buf = StringBuilder()
    private val bootMs = SystemClock.elapsedRealtime()
    private val clock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private class SiteStat {
        var jobs = 0; var ok = 0; var fail = 0
        var viaDom = 0; var viaCanvas = 0; var viaScreen = 0
        var totalMs = 0L; var lastError = ""; var lastSummary = ""
    }
    private val sites = LinkedHashMap<String, SiteStat>()

    /** Short, single-line text preview for the log (never the whole recognised text). */
    fun snip(s: String?, n: Int = 40): String =
        (s ?: "").replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n) + "…" else it }

    fun log(tag: String, msg: String) {
        val line = "${clock.get()!!.format(Date())} [${Thread.currentThread().name.take(18)}] $tag: $msg\n"
        try { Log.i("NovelStudioOcrDiag", "$tag: $msg") } catch (_: Throwable) {}
        synchronized(lock) {
            buf.append(line)
            if (buf.length > MAX_CHARS) buf.delete(0, buf.length - (MAX_CHARS * 3 / 4))
        }
    }

    fun clear() { synchronized(lock) { buf.setLength(0); sites.clear() } }

    /** Called once when a Web Mode job finishes (success or failure). */
    fun jobDone(host: String, summary: String, ok: Boolean, how: String, ms: Long, error: String) {
        synchronized(lock) {
            val s = sites.getOrPut(host.ifBlank { "?" }) { SiteStat() }
            s.jobs++
            if (ok) s.ok++ else s.fail++
            when (how) { "canvas" -> s.viaCanvas++; "screen" -> s.viaScreen++; else -> s.viaDom++ }
            s.totalMs += ms
            s.lastSummary = summary
            if (error.isNotBlank()) s.lastError = error
        }
        log("JOB_DONE", "ok=$ok via=$how ms=$ms err=$error | $summary")
    }

    @Suppress("DEPRECATION")
    fun report(ctx: Context, pageUrl: String): String {
        val sb = StringBuilder(32_000)
        val rt = Runtime.getRuntime()
        sb.append("===== NOVEL STUDIO OCR REPORT =====\n")
        sb.append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            sb.append("app: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n")
        } catch (_: Throwable) {}
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("memory: ").append((rt.totalMemory() - rt.freeMemory()) shr 20).append('/')
            .append(rt.maxMemory() shr 20).append(" MB | uptime ").append((SystemClock.elapsedRealtime() - bootMs) / 1000).append("s\n")
        sb.append("page: ").append(pageUrl.take(220)).append("\n\n")

        sb.append("--- SETTINGS ---\n")
        for (k in listOf("ocrEngine", "ocrRescan", "ocrDebug", "scanFastFirst", "scanStepMs", "forceLoadImages", "rtBatchLevel", "ocrLang")) {
            sb.append(k).append('=').append(try { Prefs.get(ctx, k, "(default)") } catch (_: Throwable) { "?" }).append('\n')
        }
        val ep = try { Prefs.get(ctx, "lensEndpoint", "").trim() } catch (_: Throwable) { "" }
        sb.append("lensEndpoint=").append(if (ep.isBlank()) "default" else "CUSTOM $ep").append('\n')
        val key = try { Prefs.get(ctx, "lensApiKey", "").trim() } catch (_: Throwable) { "" }
        sb.append("lensApiKey=").append(if (key.isBlank()) "default" else "CUSTOM").append("\n\n")

        sb.append("--- ENGINE STATE ---\n")
        sb.append("last engine used: ").append(OcrEngineStatus.label.ifBlank { "(none yet)" }).append('\n')
        sb.append("last router error: ").append(OcrEngineStatus.lastError.ifBlank { "(none)" }).append('\n')
        sb.append("last Lens request: ").append(OcrEngineStatus.lastLensDiagnostic).append('\n')
        sb.append("Lens circuit breaker: ").append(try { LensOcrEngine.breakerStatus() } catch (_: Throwable) { "?" }).append("\n\n")

        sb.append("--- PER-SITE STATS (this session) ---\n")
        synchronized(lock) {
            if (sites.isEmpty()) sb.append("(no OCR job finished yet)\n")
            for ((h, s) in sites) {
                sb.append(h).append(": jobs=").append(s.jobs).append(" ok=").append(s.ok).append(" fail=").append(s.fail)
                    .append(" | dom=").append(s.viaDom).append(" canvas=").append(s.viaCanvas).append(" screen=").append(s.viaScreen)
                    .append(" | avgMs=").append(if (s.jobs > 0) s.totalMs / s.jobs else 0)
                    .append("\n   lastError: ").append(s.lastError.ifBlank { "-" })
                    .append("\n   last: ").append(s.lastSummary).append('\n')
            }
        }
        sb.append("\n--- TIMELINE (oldest first) ---\n")
        synchronized(lock) { sb.append(if (buf.isEmpty()) "(empty: scan a page first, then copy again)\n" else buf.toString()) }
        sb.append("===== END =====\n")
        return sb.toString()
    }
}
