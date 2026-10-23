package com.ashraf.novelstudio

import android.app.ActivityManager
import android.content.Context

/** RAM-based device class used to pick safe-but-fast OCR defaults (Paddle detector size, threads, recommendation). */
internal object DeviceTier {
    enum class Tier { LOW, MID, HIGH }

    @Volatile private var cached: Tier? = null

    fun totalRamMb(c: Context): Long = try {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024L * 1024L)
    } catch (_: Throwable) { 0L }

    // totalMem reports a bit less than the marketed size (an "8 GB" phone shows ~7.3-7.7 GB).
    fun tier(c: Context): Tier = cached ?: run {
        val mb = totalRamMb(c)
        val t = when {
            mb in 1..4600 -> Tier.LOW      // up to ~4 GB
            mb in 4601..6800 -> Tier.MID   // ~6 GB
            mb > 6800 -> Tier.HIGH         // ~8 GB and up
            else -> Tier.MID               // unknown
        }
        cached = t
        t
    }

    fun detMaxPixels(c: Context): Long = when (tier(c)) {
        Tier.LOW -> 1_200_000L
        Tier.MID -> 2_000_000L
        Tier.HIGH -> 3_000_000L
    }

    fun threads(c: Context, packDefault: Int): Int = when (tier(c)) {
        Tier.LOW -> packDefault
        Tier.MID -> maxOf(packDefault, 3)
        Tier.HIGH -> maxOf(packDefault, 4)
    }

    /** Engine key to flag as "Recommended" in the picker. */
    fun recommendedEngine(c: Context): String = when (tier(c)) {
        Tier.HIGH -> "paddle_heavy"
        Tier.MID -> "paddle_small"
        Tier.LOW -> "mlkit"
    }
}
