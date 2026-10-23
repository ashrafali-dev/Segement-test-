package com.ashraf.novelstudio

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Minimal, privacy-conscious usage metrics for the owner dashboard.
 * Sends only a random installation ID, app/Android versions, mode, language,
 * provider and aggregate event metadata. Never sends page URLs, novel text or translations.
 */
internal object NovelStudioAnalytics {
    private const val SUPABASE_URL = "https://tezgkarktyftzhejaplp.supabase.co"
    // Publishable client key only. The service-role key must remain inside Supabase Edge Functions.
    private const val PUBLISHABLE_KEY = "sb_publishable_Y_MafVNuqKfCJ1KJh-aB9w_wHyfGije"
    private const val PREFS_NAME = "novelstudio_analytics"
    private const val INSTALL_ID_KEY = "install_id"
    private const val PRESENCE_INTERVAL_MS = 30_000L

    private val allowedEvents = setOf(
        "app_open",
        "mode_selected",
        "translation_started",
        "translation_completed",
        "translation_failed",
        "target_language_selected",
        "ocr_model_selected",
        "prefetch_toggled",
        "prefetch_completed",
        "manual_ocr_used",
        "app_update_seen"
    )
    private val allowedModes = setOf("novel", "scanning", "realtime")
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "novelstudio-analytics").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var presenceTask: Runnable? = null

    @Synchronized
    private fun installId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(INSTALL_ID_KEY, null)
        if (existing != null && isUuidV4(existing)) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(INSTALL_ID_KEY, created).apply()
        return created
    }

    private fun isUuidV4(value: String): Boolean =
        value.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"))

    private fun appVersion(context: Context): String {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().take(40)
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun basePayload(context: Context): JSONObject = JSONObject()
        .put("install_id", installId(context))
        .put("app_version", appVersion(context))
        .put("android_version", Build.VERSION.RELEASE.orEmpty().take(30))

    /**
     * Queue an allowed analytics event. Failures are intentionally silent and must never
     * affect browsing, OCR, chatbot interaction, or translation.
     */
    fun track(
        context: Context,
        eventName: String,
        mode: String? = null,
        targetLanguage: String? = null,
        ocrModel: String? = null,
        provider: String? = null,
        success: Boolean? = null,
        durationMs: Long? = null
    ) {
        if (eventName !in allowedEvents) return
        val appContext = context.applicationContext
        val payload = try {
            basePayload(appContext).put("event_name", eventName).apply {
                if (mode != null && mode in allowedModes) put("mode", mode)
                if (!targetLanguage.isNullOrBlank()) put("target_language", targetLanguage.take(40))
                if (!ocrModel.isNullOrBlank()) put("ocr_model", ocrModel.take(80))
                if (!provider.isNullOrBlank()) put("provider", provider.take(40))
                if (success != null) put("success", success)
                if (durationMs != null && durationMs in 0L..3_600_000L) put("duration_ms", durationMs.toInt())
            }
        } catch (_: Exception) {
            return
        }
        enqueuePost("novelstudio-analytics", payload)
    }

    /**
     * The dashboard marks devices online for 90 seconds after the last heartbeat.
     * A 30-second foreground heartbeat keeps that count fresh without background tracking.
     */
    @Synchronized
    fun startPresence(context: Context, modeProvider: () -> String) {
        stopPresence()
        val appContext = context.applicationContext
        val task = object : Runnable {
            override fun run() {
                val mode = try { modeProvider() } catch (_: Exception) { null }
                val payload = try {
                    basePayload(appContext).apply {
                        if (mode != null && mode in allowedModes) put("mode", mode)
                    }
                } catch (_: Exception) {
                    null
                }
                if (payload != null) enqueuePost("novelstudio-presence", payload)
                mainHandler.postDelayed(this, PRESENCE_INTERVAL_MS)
            }
        }
        presenceTask = task
        task.run()
    }

    @Synchronized
    fun stopPresence() {
        presenceTask?.let { mainHandler.removeCallbacks(it) }
        presenceTask = null
    }

    private fun enqueuePost(functionName: String, payload: JSONObject) {
        try {
            executor.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL("$SUPABASE_URL/functions/v1/$functionName").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5_000
                        readTimeout = 5_000
                        doOutput = true
                        setRequestProperty("apikey", PUBLISHABLE_KEY)
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    (if (code in 200..399) connection.inputStream else connection.errorStream)?.use { it.close() }
                } catch (_: Exception) {
                    // Analytics is best-effort: no retry loop, toast, or user-visible impact.
                } finally {
                    connection?.disconnect()
                }
            }
        } catch (_: Exception) {
            // Ignore a rejected executor task; analytics must never interrupt app features.
        }
    }
}
