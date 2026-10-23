package com.ashraf.novelstudio

import android.content.Context

/**
 * Faster completion checks used only by the AI Real-time Translation feature.
 *
 * While the chatbot is generating, check often enough to notice the stop state quickly.
 * Once streaming appears to stop, confirm the answer is stable across a few quick polls,
 * rather than waiting several seconds. Ordinary chapter translations keep their old timing.
 */
internal object RealtimeCompletionPolicy {
    private const val NORMAL_POLL_MS = 500L
    // WebView DOM probes are expensive on long, animated webtoon pages. These values
    // keep completion responsive without hammering the compositor/main thread.
    private const val REALTIME_FIRST_POLL_MS = 100L
    private const val REALTIME_STREAMING_POLL_MS = 160L
    private const val REALTIME_IDLE_POLL_MS = 90L

    fun enabled(context: Context): Boolean =
        DeepSeekRealtimeTranslation.isEnabled(context)

    fun firstPollDelayMs(context: Context): Long =
        if (enabled(context)) REALTIME_FIRST_POLL_MS else 300L

    fun nextPollDelayMs(context: Context, streaming: Boolean, replyAvailable: Boolean = true): Long {
        if (!enabled(context)) return NORMAL_POLL_MS
        return if (streaming || !replyAvailable) REALTIME_STREAMING_POLL_MS else REALTIME_IDLE_POLL_MS
    }

    // A single unchanged, non-streaming sample is enough when explicit completion evidence
    // exists (all paragraph markers or the chatbot's completed/send-ready UI). The no-signal
    // fallback remains at two samples so a brief generation pause is not mistaken for completion.
    fun stableThreshold(context: Context, normalThreshold: Int, realtimeThreshold: Int = 2): Int =
        if (enabled(context)) realtimeThreshold else normalThreshold

    fun noStopSignalThreshold(context: Context, normalThreshold: Int, realtimeThreshold: Int = 2): Int =
        if (enabled(context)) realtimeThreshold else normalThreshold
}
