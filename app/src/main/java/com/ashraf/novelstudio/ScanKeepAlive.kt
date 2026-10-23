package com.ashraf.novelstudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the CPU (and the process) alive while a scan / translation / prefetch job is running,
 * so the work continues when the screen turns off or the app is in the background.
 *
 * It is only a keep-alive: it does not touch OCR, extraction or translation logic. MainActivity
 * calls [sync] from its existing 450 ms status loop; the service is started while a job is busy
 * and stopped ~25 s after the last job ends.
 *
 * Limit: pixel-based work (Force Extract, live "visible panel" OCR) reads what is drawn on the
 * screen, so it needs the screen on. Network, OCR on downloaded images, the chatbot WebView and
 * prefetch work without the screen.
 */
internal class ScanKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            release()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            // Not allowed to run as a foreground service right now: give up quietly.
            stopSelf()
            return START_NOT_STICKY
        }
        acquire()
        return START_NOT_STICKY
    }

    private fun acquire() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovelStudio:scan").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            // Always with a timeout, so a bug can never drain the battery forever. MainActivity's
            // status loop re-sends the start command, which renews this while work is running.
            wl.acquire(WAKE_TIMEOUT_MS)
        } catch (_: Exception) { }
    }

    private fun release() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        } catch (_: Exception) { }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scan & translation", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Novel Studio")
            .setContentText("Scanning / translating in the background…")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.ashraf.novelstudio.STOP_KEEP_ALIVE"
        private const val CHANNEL_ID = "ns_scan_keepalive"
        private const val NOTIF_ID = 4711
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

/** Tiny controller used by MainActivity; remembers whether the service is up. */
internal object ScanKeepAlive {
    private const val IDLE_GRACE_MS = 25_000L
    private const val RENEW_MS = 4 * 60 * 1000L
    private var running = false
    private var lastStartAt = 0L
    private var idleSince = 0L

    /** Call often (cheap). [busy] = any scan, OCR, translation or prefetch job is in flight. */
    fun sync(ctx: Context, busy: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (busy) {
            idleSince = 0L
            if (!running || now - lastStartAt >= RENEW_MS) start(ctx, now)
        } else if (running) {
            if (idleSince == 0L) idleSince = now
            else if (now - idleSince >= IDLE_GRACE_MS) stop(ctx)
        }
    }

    private fun start(ctx: Context, now: Long) {
        try {
            val i = Intent(ctx, ScanKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            running = true
            lastStartAt = now
        } catch (_: Exception) {
            // e.g. started from the background on Android 12+: try again on the next tick
            running = false
        }
    }

    fun stop(ctx: Context) {
        if (!running) return
        running = false
        idleSince = 0L
        try { ctx.stopService(Intent(ctx, ScanKeepAliveService::class.java)) } catch (_: Exception) { }
    }
}
