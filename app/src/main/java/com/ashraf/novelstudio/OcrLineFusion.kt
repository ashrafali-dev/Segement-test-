package com.ashraf.novelstudio

/** Line-level reconciliation shared by all OCR passes. No Android APIs: JVM regression-testable.
 * Never use overlap-of-the-smaller BOX alone: a paragraph box can cover a completely different row.
 * Never deduplicate by text alone: two speakers can both say "YES".
 */
internal object OcrLineFusion {
    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun sameRow(a: OcrBlock, b: OcrBlock): Boolean {
        if (a.vertical != b.vertical) return false
        if (a.vertical || kotlin.math.abs(a.angle) >= 6f || kotlin.math.abs(b.angle) >= 6f) {
            return OcrPost.overlapOfSmaller(a, b) >= 0.75f
        }
        val h = minOf(a.h, b.h)
        if (h <= 0f || maxOf(a.h, b.h) > h * 1.9f) return false
        val overlapY = minOf(a.cy + a.h / 2, b.cy + b.h / 2) - maxOf(a.cy - a.h / 2, b.cy - b.h / 2)
        if (overlapY < h * 0.55f || kotlin.math.abs(a.cy - b.cy) > h * 0.5f) return false
        val gapX = maxOf(a.cx - a.w / 2, b.cx - b.w / 2) - minOf(a.cx + a.w / 2, b.cx + b.w / 2)
        return gapX <= h * 1.1f
    }

    /** Ordered character agreement tolerates an OCR typo without requiring exact containment. */
    fun agreement(a: String, b: String): Float {
        val x = key(a); val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y || x.contains(y) || y.contains(x)) return 1f
        // Bound quadratic work for unrelated, abnormally long detector output.
        if (x.length > 1200 || y.length > 1200) return 0f
        var prev = IntArray(y.length + 1)
        var next = IntArray(y.length + 1)
        for (c in x) {
            next[0] = 0
            for (j in y.indices) next[j + 1] = if (c == y[j]) prev[j] + 1 else maxOf(prev[j + 1], next[j])
            val tmp = prev; prev = next; next = tmp
        }
        return prev[y.length].toFloat() / minOf(x.length, y.length)
    }

    private fun union(a: OcrBlock, b: OcrBlock, text: String): OcrBlock {
        // Rotated/vertical readings keep the winning recognizer geometry, not an axis-aligned union.
        if (a.vertical || kotlin.math.abs(a.angle) >= 6f || kotlin.math.abs(b.angle) >= 6f)
            return (if (key(b.text).length > key(a.text).length) b else a).copy(text = text)
        val l = minOf(a.cx - a.w / 2, b.cx - b.w / 2)
        val r = maxOf(a.cx + a.w / 2, b.cx + b.w / 2)
        val t = minOf(a.cy - a.h / 2, b.cy - b.h / 2)
        val bt = maxOf(a.cy + a.h / 2, b.cy + b.h / 2)
        val confidence = when (text) {
            a.text -> a.confidence
            b.text -> b.confidence
            else -> minOf(a.confidence, b.confidence)
        }
        return a.copy(text = text, top = t.toInt(), left = l.toInt(), cx = (l + r) / 2,
            cy = (t + bt) / 2, w = r - l, h = bt - t, fontPx = maxOf(a.fontPx, b.fontPx),
            confidence = confidence, observations = a.observations + b.observations)
    }

    private fun reconcile(a: OcrBlock, b: OcrBlock): OcrBlock? {
        if (!sameRow(a, b)) return null
        val x = key(a.text); val y = key(b.text)
        if (x.isEmpty() || y.isEmpty()) return null
        val overlapX = minOf(a.cx + a.w / 2, b.cx + b.w / 2) - maxOf(a.cx - a.w / 2, b.cx - b.w / 2)
        // Equal words at DIFFERENT positions on one row ("NO, NO!") must survive.
        val samePlace = overlapX > minOf(a.w, b.w) * 0.45f
        if (samePlace && (x == y || x.contains(y) || y.contains(x))) {
            val winner = if (y.length > x.length &&
                (a.confidence == 0f || b.confidence == 0f || b.confidence >= a.confidence - 0.18f)) b else a
            return union(a, b, winner.text)
        }
        if (samePlace && minOf(x.length, y.length) >= 4 && agreement(x, y) >= 0.84f) {
            // Allow a longer corrected read only when the extra letters have actual spatial support.
            val aDensity = x.length / a.w.coerceAtLeast(1f)
            val bDensity = y.length / b.w.coerceAtLeast(1f)
            val fuller = y.length > x.length && bDensity <= aDensity * 1.6f &&
                (a.confidence == 0f || b.confidence == 0f || b.confidence >= a.confidence - 0.12f)
            val correction = b.confidence > 0f && b.confidence > a.confidence + 0.08f &&
                y.length >= x.length * 0.8f
            return union(a, b, if (fuller || correction) b.text else a.text)
        }
        val left = if (a.cx <= b.cx) a else b
        val right = if (left === a) b else a
        val gap = (right.cx - right.w / 2) - (left.cx + left.w / 2)
        // Disjoint pieces on the SAME baseline ("WHAT ARE" + "YOU DOING?").
        if (!a.vertical && gap >= -minOf(a.h, b.h) * 0.2f && gap <= minOf(a.h, b.h) * 1.1f)
            return union(a, b, left.text.trim() + " " + right.text.trim())

        // Complementary partial reads: merge only an EXACT word overlap and only if the right
        // candidate extends physically to the right. Never stitch unrelated overlapping guesses.
        if (!a.vertical && right.cx + right.w / 2 > left.cx + left.w / 2 + minOf(a.h, b.h) * 0.25f) {
            val lt = left.text.trim().split(Regex("\\s+"))
            val rt = right.text.trim().split(Regex("\\s+"))
            for (n in minOf(lt.size, rt.size) downTo 1) {
                val suffix = lt.takeLast(n).map(::key)
                if (suffix.joinToString("").length < 3 || suffix != rt.take(n).map(::key)) continue
                return union(a, b, (lt + rt.drop(n)).joinToString(" "))
            }
        }
        // Conflicting readings of one footprint: keep the original, not BOTH (duplicate translations).
        if (samePlace && OcrPost.overlapOfSmaller(a, b) >= 0.65f) {
            val better = b.confidence > a.confidence + 0.10f && y.length >= x.length * 0.75f
            return if (better) union(a, b, b.text) else a
        }
        return null
    }

    /** Overlapping image slices can see the beginning/end of the SAME paragraph. Keep common
     * suffix/prefix words once, while unrelated neighbouring lines are simply concatenated.
     */
    fun joinSliceText(first: String, second: String): String {
        val a = first.trim().split(Regex("\\s+"))
        val b = second.trim().split(Regex("\\s+"))
        for (n in minOf(a.size, b.size) downTo 2) {
            val tail = a.takeLast(n).map(::key)
            if (tail.joinToString("").length >= 8 && tail == b.take(n).map(::key))
                return (a + b.drop(n)).joinToString(" ")
        }
        return (first.trim() + " " + second.trim()).trim()
    }

    fun addAll(target: MutableList<OcrBlock>, candidates: List<OcrBlock>) {
        for (candidate in candidates) {
            var current = candidate
            var index = 0
            while (index < target.size) {
                val joined = reconcile(target[index], current)
                if (joined == null) { index++; continue }
                current = joined
                target.removeAt(index)
                // A full line may cover several previous fragments; recheck the complete set.
                index = 0
            }
            target.add(current)
        }
        target.sortWith(compareBy({ it.top }, { it.left }))
    }
}
