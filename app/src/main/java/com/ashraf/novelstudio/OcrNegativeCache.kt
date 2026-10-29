package com.ashraf.novelstudio

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.util.LinkedHashMap

/**
 * Shared negative OCR cache. It runs before engine routing so a hit bypasses both Lens network I/O
 * and ML Kit inference. Keys include crop dimensions and a perceptual hash of the crop pixels.
 */
internal object OcrNegativeCache {
    private data class Key(val width: Int, val height: Int, val pHash: Long)
    private data class Negative(val insertedAt: Long, val ttlMs: Long, val savedMs: Long)
    private data class EmptyHistory(val count: Int, val lastAt: Long)

    private const val TAG = "NovelStudioOCR"
    private const val DEFAULT_TTL_MS = 30_000L
    private const val REPEATED_TTL_MS = 120_000L
    private const val REPEAT_WINDOW_MS = 120_000L
    private const val MAX_ENTRIES = 96

    private val negatives = object : LinkedHashMap<Key, Negative>(MAX_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Negative>?): Boolean =
            size > MAX_ENTRIES
    }
    // Keep the streak separate from the TTL entry: an expired cache hit must not erase consecutive-empty history.
    private val history = object : LinkedHashMap<Key, EmptyHistory>(MAX_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, EmptyHistory>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 64-bit difference hash over sampled grayscale pixels. */
    private fun key(bitmap: Bitmap): Key {
        val w = bitmap.width
        val h = bitmap.height
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            val py = ((y + 0.5f) * h / 8f).toInt().coerceIn(0, h - 1)
            for (x in 0 until 8) {
                val x0 = ((x + 0.25f) * (w - 1) / 8f).toInt().coerceIn(0, w - 1)
                val x1 = ((x + 1.25f) * (w - 1) / 8f).toInt().coerceIn(0, w - 1)
                val a = bitmap.getPixel(x0, py)
                val b = bitmap.getPixel(x1, py)
                val la = (((a ushr 16) and 255) * 299 + ((a ushr 8) and 255) * 587 + (a and 255) * 114) / 1000
                val lb = (((b ushr 16) and 255) * 299 + ((b ushr 8) and 255) * 587 + (b and 255) * 114) / 1000
                if (la >= lb) hash = hash or (1L shl bit)
                bit++
            }
        }
        return Key(w, h, hash)
    }

    private fun nearKey(target: Key, keys: Set<Key>): Key? =
        keys.firstOrNull { it.width == target.width && it.height == target.height &&
            java.lang.Long.bitCount(it.pHash xor target.pHash) <= 2 }

    @Synchronized
    fun hit(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val target = key(bitmap)
        val storedKey = nearKey(target, negatives.keys) ?: return false
        val entry = negatives[storedKey] ?: return false
        val now = SystemClock.elapsedRealtime()
        val age = now - entry.insertedAt
        if (age < 0L || age >= entry.ttlMs) {
            negatives.remove(storedKey)
            return false
        }
        Log.i(TAG, "LENS_NEG_CACHE_HIT: w=${target.width} h=${target.height} ageMs=$age savedMs=${entry.savedMs}")
        return true
    }

    @Synchronized
    fun record(bitmap: Bitmap, empty: Boolean, elapsedMs: Long) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val target = key(bitmap)
        val now = SystemClock.elapsedRealtime()
        if (!empty) {
            nearKey(target, negatives.keys)?.let { negatives.remove(it) }
            nearKey(target, history.keys)?.let { history.remove(it) }
            return
        }

        val priorKey = nearKey(target, history.keys)
        val prior = priorKey?.let { history[it] }
        val count = if (prior != null && now >= prior.lastAt && now - prior.lastAt < REPEAT_WINDOW_MS)
            prior.count + 1 else 1
        history[priorKey ?: target] = EmptyHistory(count, now)
        val ttl = if (count >= 3) REPEATED_TTL_MS else DEFAULT_TTL_MS
        val negativeKey = nearKey(target, negatives.keys) ?: target
        negatives[negativeKey] = Negative(now, ttl, elapsedMs.coerceAtLeast(1L))
        Log.i(TAG, "LENS_NEG_CACHE_INSERT: w=${target.width} h=${target.height} ttl=${ttl / 1000}s")
    }

    @Synchronized
    fun clear() {
        negatives.clear()
        history.clear()
    }
}
