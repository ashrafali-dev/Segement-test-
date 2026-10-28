package com.ashraf.novelstudio

import android.app.ActivityManager
import android.content.Context

/** RAM-based device class used to pick safe-but-fast defaults (bubble detector on/off, inpainting). */
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
}
