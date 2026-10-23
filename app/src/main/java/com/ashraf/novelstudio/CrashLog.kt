package com.ashraf.novelstudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Small on-device diagnostics: an uncaught-exception hook plus a "UI freeze" watchdog.
 *
 * When the app stops answering taps (main thread blocked) the watchdog writes the main thread's stack to
 * crash-log.txt, so the exact line that blocks it is known even if Android later kills the process. The menu's
 * "Copy crash / freeze log" row copies the file so it can be pasted into a chat.
 */
internal object CrashLog {
    private const val MAX_BYTES = 60_000L
    private var file: File? = null
    private var started = false

    /** Updated by the UI (main thread) so the watchdog thread can say which page was open. */
    @Volatile var context: String = ""

    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    @Synchronized
    fun install(ctx: Context) {
        if (started) return
        started = true
        file = File(ctx.applicationContext.filesDir, "crash-log.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { append("CRASH on thread '${t.name}'\n" + Log.getStackTraceString(e)) } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
        val main = Handler(Looper.getMainLooper())
        val ack = AtomicLong(SystemClock.uptimeMillis())
        Thread({
            var reportedAt = 0L
            var second = false
            while (true) {
                main.post { ack.set(SystemClock.uptimeMillis()) }
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { return@Thread }
                val stalled = SystemClock.uptimeMillis() - ack.get()
                if (stalled < 3_500L) { reportedAt = 0L; second = false; continue }
                if (reportedAt == 0L) {
                    reportedAt = SystemClock.uptimeMillis()
                    append("UI FREEZE ${stalled} ms (first sample)\n" + mainStack())
                } else if (!second && SystemClock.uptimeMillis() - reportedAt >= 3_000L) {
                    second = true
                    append("UI FREEZE ${stalled} ms (second sample)\n" + mainStack())
                }
            }
        }, "ns-freeze-watchdog").apply { isDaemon = true; start() }
    }

    private fun mainStack(): String = try {
        Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "    at $it" }
    } catch (_: Throwable) { "    (no stack)" }

    @Synchronized
    private fun append(text: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) f.writeText("")
            val rt = Runtime.getRuntime()
            val mem = "mem ${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20} MB"
            f.appendText("[$stamp] $text\n    page: $context | $mem\n\n")
        } catch (_: Throwable) { }
    }

    /** A short remark (renderer died, memory pressure ...) that did not come from an exception. */
    fun note(msg: String) { try { append("NOTE " + msg) } catch (_: Throwable) { } }

    fun read(): String = try { file?.takeIf { it.exists() }?.readText().orEmpty() } catch (_: Throwable) { "" }

    fun clear() { try { file?.writeText("") } catch (_: Throwable) { } }
}
