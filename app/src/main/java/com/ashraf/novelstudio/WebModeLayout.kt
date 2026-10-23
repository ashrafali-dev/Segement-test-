package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Where one piece of recognised text sat on its image. Everything is a fraction of the image, so it
 * still fits when the page is shown at another width: [cx]/[cy] centre, [w]/[h] box size, [font]
 * glyph size as a fraction of the image WIDTH. [angle] is in degrees, [bg]/[fg] are ARGB colours
 * sampled from the original artwork.
 */
internal data class WmBubble(
    val src: String,
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    val angle: Float, val font: Float,
    val bg: Int, val fg: Int,
    /** Colour at the bottom of the lettering; differs from [fg] when the original text was a gradient. */
    val fg2: Int = fg,
    /** True when the old text sat on artwork (not a flat bubble): the cover gets soft, feathered edges. */
    val soft: Boolean = false,
    /** Exact normalized OCR text captured at this position; used to bind output to the right bubble. */
    val sourceText: String = "",
    /** Bengali text already translated for this position. Empty means not translated yet. */
    val translatedText: String = "",
    /** Colour of the lettering's outline (SFX / titles drawn as fill + stroke); 0 = no outline. */
    val stroke: Int = 0
)

/**
 * The bubbles of one chapter, in the same order as their text paragraphs in the chapter text that is
 * sent to the chatbot. [titleOffset] is 1 when the chapter text starts with a title paragraph.
 */
internal class WebModeLayout(val titleOffset: Int, val bubbles: List<WmBubble>) {

    fun toJson(): String {
        val a = JSONArray()
        for (b in bubbles) a.put(JSONObject()
            .put("s", b.src).put("cx", r5(b.cx)).put("cy", r5(b.cy)).put("w", r5(b.w)).put("h", r5(b.h))
            .put("a", r5(b.angle)).put("f", r5(b.font)).put("bg", b.bg).put("fg", b.fg)
            .put("fg2", b.fg2).put("sf", b.soft)
            .put("srcText", b.sourceText).put("tr", b.translatedText).put("so", b.stroke))
        return JSONObject().put("v", 2).put("to", titleOffset).put("b", a).toString()
    }

    companion object {
        private fun r5(x: Float): Double = Math.round(x * 100000.0) / 100000.0

        fun fromJson(s: String): WebModeLayout? = try {
            val o = JSONObject(s)
            val a = o.getJSONArray("b")
            val list = ArrayList<WmBubble>(a.length())
            for (i in 0 until a.length()) {
                val b = a.getJSONObject(i)
                list.add(WmBubble(
                    b.getString("s"),
                    b.getDouble("cx").toFloat(), b.getDouble("cy").toFloat(),
                    b.getDouble("w").toFloat(), b.getDouble("h").toFloat(),
                    b.getDouble("a").toFloat(), b.getDouble("f").toFloat(),
                    b.getInt("bg"), b.getInt("fg"),
                    b.optInt("fg2", b.getInt("fg")), b.optBoolean("sf", false),
                    b.optString("srcText", ""), b.optString("tr", ""), b.optInt("so", 0)
                ))
            }
            WebModeLayout(o.optInt("to", 1), list)
        } catch (_: Exception) { null }

        private val MARK = Regex("\\[(\\d{3,})\\]")
        private val HASH_NOISE = Regex("[\\s\\p{P}\\p{S}]+")

        /** Canonical OCR text: OCR whitespace, case and punctuation variation must not resend it. */
        fun normalizeOcrText(text: String): String =
            text.lowercase().replace(HASH_NOISE, "").trim()

        /** Stable per-bubble fingerprint: normalized OCR text plus its image and relative position. */
        fun fingerprint(b: WmBubble): String {
            val src = WebModeLogic.normalizeUrl(b.src)
            val text = normalizeOcrText(b.sourceText)
            val geometry = listOf(b.cx, b.cy, b.w, b.h).joinToString(",") {
                Math.round(it * 10000f).toInt().toString()
            }
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$src|$geometry|$text".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Map the current reply to the current scan, then retain the result on the matching bubbles. */
        fun translated(layout: WebModeLayout, values: List<String?>): WebModeLayout =
            WebModeLayout(layout.titleOffset, layout.bubbles.mapIndexed { i, b ->
                val value = values.getOrNull(i)?.trim().orEmpty()
                if (value.isEmpty()) b else b.copy(translatedText = value)
            })
        private val NL = Regex("\\s*\\n\\s*")

        /**
         * Align the chatbot's marked reply to exact OCR bubbles. A markerless reply has no stable
         * bubble identity; matching it by paragraph count can silently put every translation in
         * the wrong box after the model merges, reorders, or drops one paragraph. In that case the
         * caller must show the reader view, never guess placement.
         */
        fun align(text: String, layout: WebModeLayout): List<String?>? {
            val n = layout.bubbles.size
            if (n == 0) return null
            val ms = MARK.findAll(text).toList()
            if (ms.isEmpty()) return null

            val expectedLast = n + layout.titleOffset
            val byNo = HashMap<Int, String>()
            val seenNumbers = HashSet<Int>()
            var previousNo = 0
            for ((k, m) in ms.withIndex()) {
                val no = m.groupValues[1].toIntOrNull() ?: return null
                // Repeated, reversed, or out-of-range markers cannot be trusted for overlay placement.
                if (no <= previousNo || no > expectedLast) return null
                previousNo = no
                seenNumbers.add(no)
                val end = if (k + 1 < ms.size) ms[k + 1].range.first else text.length
                val body = text.substring(m.range.last + 1, end).trim()
                // Older prompts/models used a tilde as a fake translation for skipped OCR.
                // Treat it as an empty answer so it is never painted over a comic bubble.
                if (body.isNotEmpty() && !body.matches(Regex("[~～]+"))) byNo[no] = body
            }

            // Full-chapter replies include the title as [001], so a response that starts at [001]
            // but lacks the final expected marker is ambiguous: it may have renumbered bubbles after
            // dropping the title, or it may have omitted the final bubble. Do not shift all bubbles.
            if (layout.titleOffset > 0 && seenNumbers.contains(1) && !seenNumbers.contains(expectedLast)) {
                return null
            }

            // Marker numbers are positional IDs. Never select a different offset just because it
            // happens to produce more matches; missing IDs leave their own bubble blank.
            val out = List(n) { i -> byNo[i + 1 + layout.titleOffset]?.replace(NL, " ") }
            val found = out.count { !it.isNullOrBlank() }
            return if (found > 0 && (found * 100 >= n * 70 || layout.titleOffset == 0)) out else null
        }
    }
}

/**
 * Layouts of the chapters opened recently. Memory first; [persist] also keeps the last few on disk
 * so a saved translation can still be put into its bubbles after the app was restarted.
 */
internal object WebModeLayoutStore {
    private const val KEY = "wmLayouts"
    private const val MAX = 8

    // Latest OCR batch (used to align this answer) and cumulative translated layout (kept while scrolling).
    private val scans = object : LinkedHashMap<String, WebModeLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebModeLayout>?): Boolean = size > MAX
    }
    private val mem = object : LinkedHashMap<String, WebModeLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebModeLayout>?): Boolean = size > MAX
    }

    // Last successful OCR for each canvas source, kept separately from the cumulative translated layout.
    // The cumulative layout may contain bubbles from several recycled pages and is not a valid comparison
    // baseline for deciding whether the currently displayed canvas changed.
    private val canvasSnapshots = object : LinkedHashMap<String, WebModeLayout>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebModeLayout>?): Boolean = size > 64
    }

    // OCR detects page reuse off the UI thread; the UI consumes transitions and clears old overlays.
    private val pendingCanvasTransitions = object : LinkedHashMap<String, LinkedHashSet<String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>?): Boolean = size > 8
    }
    // Monotonic per-canvas generation lets in-flight replies prove they still target the same bitmap.
    private val canvasGenerationBySource = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 128
    }
    // Clean screenshots of tainted canvas pixels. Bound the LRU for low-memory Android devices.
    private val canvasPixelSnapshots = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    }

    // Reader SPAs (MangaDex) rewrite the trailing page number while the page is scrolled:
    // /chapter/<uuid>/1 -> /chapter/<uuid>/8. The layout saved during the scan and the chapter that later
    // receives the translation must still map to the same entry, so that page number is dropped from the key.
    private val READER_PAGE_SUFFIX = Regex("(/chapter/[0-9a-fA-F-]{36})/\\d+/?$")

    private fun key(url: String) = WebModeLogic.normalizeUrl(url).replace(READER_PAGE_SUFFIX, "\$1")

    @Synchronized
    fun put(ctx: Context, url: String, layout: WebModeLayout) {
        val k = key(url)
        // Load persisted translations before comparing so a restart cannot resurrect bubbles from
        // an old page after the reader recycles its canvas at the same URL/coordinates.
        val previousLayout = mem[k] ?: if (isShonenJumpPlus(url)) get(ctx, url) else null
        val transitions = LinkedHashSet<String>()

        if (isShonenJumpPlus(url)) {
            val sources = layout.bubbles.asSequence()
                .map { WebModeLogic.normalizeUrl(it.src) }
                .filter { it.startsWith("ns-canvas:") }
                .distinct()
                .toList()
            for (src in sources) {
                val fresh = layout.bubbles.filter {
                    WebModeLogic.normalizeUrl(it.src) == src && it.sourceText.isNotBlank()
                }
                if (fresh.isEmpty()) continue
                val snapshotKey = "$k|$src"
                val prior = canvasSnapshots[snapshotKey]?.bubbles
                    ?: scans[k]?.bubbles?.filter { WebModeLogic.normalizeUrl(it.src) == src }
                    ?: previousLayout?.bubbles?.filter { WebModeLogic.normalizeUrl(it.src) == src }
                    ?: emptyList()
                val transition = canvasTransitionDetected(prior, fresh)
                // A sparse read (< 3 bubbles) that does not contradict the stored page is only a partial
                // view of it: keep the fuller snapshot instead of overwriting it.
                if (fresh.size < 3 && !transition) continue
                if (transition) {
                    transitions.add(src)
                    canvasGenerationBySource[snapshotKey] = (canvasGenerationBySource[snapshotKey] ?: 0L) + 1L
                }
                canvasSnapshots[snapshotKey] = WebModeLayout(layout.titleOffset, fresh)
            }
        }

        if (transitions.isNotEmpty()) {
            val old = previousLayout ?: mem[k]
            if (old != null) {
                // The physical canvas element is reused, but these bubbles belong to another bitmap/page.
                // Remove only the recycled canvas' old bubbles; other still-visible canvases remain intact.
                mem[k] = WebModeLayout(old.titleOffset, old.bubbles.filterNot {
                    WebModeLogic.normalizeUrl(it.src) in transitions
                })
                pendingCanvasTransitions.getOrPut(k) { LinkedHashSet() }.addAll(transitions)
                // Persist immediately so the stale page cannot return after an app restart or failed reply.
                persist(ctx, url)
            }
        }

        scans[k] = layout
        mem[k]?.let { mem[k] = mergeLayouts(it, layout) }
    }

    @Synchronized
    fun takeCanvasTransitions(url: String): Set<String> =
        pendingCanvasTransitions.remove(key(url))?.toSet().orEmpty()

    /**
     * Observe a clean, screenshot-derived fingerprint for a tainted manga canvas.
     * This is independent of OCR success, so a changed page can invalidate old translations even when
     * Lens is offline or returns no readable text. The signature contains quantised samples and '?' for
     * pixels outside the visible viewport; comparison uses only locations visible in both frames.
     */
    @Synchronized
    fun observeCanvasFrame(ctx: Context, url: String, src: String, signature: String): Boolean {
        if (!isShonenJumpPlus(url)) return false
        val normalizedSource = WebModeLogic.normalizeUrl(src)
        if (!normalizedSource.startsWith("ns-canvas:") || signature.isBlank()) return false
        val k = key(url)
        val sourceKey = "$k|$normalizedSource"
        val previous = canvasPixelSnapshots.put(sourceKey, signature) ?: return false
        if (previous.length != signature.length) return false

        var comparable = 0
        var changed = 0
        for (i in signature.indices) {
            val a = previous[i]
            val b = signature[i]
            if (a == '?' || b == '?') continue
            comparable++
            if (a != b) changed++
        }
        // Require a broad change across the canvas, not a few antialiasing pixels or a UI artifact.
        if (comparable < 16 || changed.toFloat() / comparable.toFloat() < 0.45f) return false

        canvasGenerationBySource[sourceKey] = (canvasGenerationBySource[sourceKey] ?: 0L) + 1L
        pendingCanvasTransitions.getOrPut(k) { LinkedHashSet() }.add(normalizedSource)

        // This bitmap, not OCR, is what proved the page changed. Drop the old OCR baseline and both
        // the cumulative and latest-scan entries for this canvas, so a later text-only fallback cannot
        // accidentally fetch the prior page's geometry after OCR failure.
        canvasSnapshots.remove(sourceKey)
        scans[k]?.let { scanned ->
            scans[k] = WebModeLayout(scanned.titleOffset, scanned.bubbles.filterNot {
                WebModeLogic.normalizeUrl(it.src) == normalizedSource
            })
        }
        val previousLayout = mem[k] ?: get(ctx, url)
        if (previousLayout != null) {
            mem[k] = WebModeLayout(previousLayout.titleOffset, previousLayout.bubbles.filterNot {
                WebModeLogic.normalizeUrl(it.src) == normalizedSource
            })
            // Persist removal immediately; do not resurrect the prior page if OCR/chat fails or the app restarts.
            persist(ctx, url)
        }
        return true
    }

    /** Capture the current reader-page generation for each canvas source in this request's layout. */
    @Synchronized
    fun canvasVersions(url: String, layout: WebModeLayout?): Map<String, Long> {
        if (layout == null || !isShonenJumpPlus(url)) return emptyMap()
        val k = key(url)
        val sources = layout.bubbles.asSequence()
            .map { WebModeLogic.normalizeUrl(it.src) }
            .filter { it.startsWith("ns-canvas:") }
            .distinct()
            .toList()
        return sources.associate { src ->
            val sourceKey = "$k|$src"
            val snapshot = canvasSnapshots[sourceKey]?.bubbles.orEmpty()
            val requestBubbles = layout.bubbles.filter {
                WebModeLogic.normalizeUrl(it.src) == src && it.sourceText.isNotBlank()
            }
            // A batch may be queued while another scan detects the next manga page. If we blindly
            // capture the *new* generation here, an old batch would appear current. Every bubble in
            // a canvas-backed request must still match the most recent OCR snapshot before the batch
            // may inherit that generation. MIN_VALUE is a permanent-invalid sentinel for this batch.
            val belongsToCurrentPage = snapshot.isNotEmpty() && requestBubbles.isNotEmpty() &&
                requestBubbles.all { request ->
                    snapshot.any { scanned -> sameBubbleReading(scanned, request) }
                }
            sourceKey to if (belongsToCurrentPage) {
                canvasGenerationBySource[sourceKey] ?: 0L
            } else {
                Long.MIN_VALUE
            }
        }
    }

    @Synchronized
    fun canvasVersionsStillCurrent(versions: Map<String, Long>): Boolean =
        versions.all { (sourceKey, generation) ->
            (canvasGenerationBySource[sourceKey] ?: 0L) == generation
        }

    @Synchronized
    fun setScan(url: String, layout: WebModeLayout) { scans[key(url)] = layout }

    @Synchronized
    fun getScan(url: String): WebModeLayout? = scans[key(url)]

    @Synchronized
    fun get(ctx: Context, url: String): WebModeLayout? {
        val k = key(url)
        mem[k]?.let { return it }
        return try {
            val data = JSONObject(Prefs.get(ctx, KEY, "{}")).optJSONObject("data") ?: return null
            val l = WebModeLayout.fromJson(data.optString(k).ifEmpty { return null }) ?: return null
            mem[k] = l
            l
        } catch (_: Exception) { null }
    }

    /** Merge translated current bubbles into the full-page layout so previously translated bubbles stay visible. */
    @Synchronized
    fun mergeTranslated(
        ctx: Context,
        url: String,
        scan: WebModeLayout,
        translated: List<String?>
    ): WebModeLayout {
        val current = WebModeLayout.translated(scan, translated)
        val old = get(ctx, url)
        // Canvas-generation changes are detected when the OCR scan is registered in put().
        // This method must merge only the reply's own saved layout; the supplied scan can be a
        // selected subset of bubbles and is not a safe page-change fingerprint.
        val merged = mergeLayouts(old, current)
        mem[key(url)] = merged
        persist(ctx, url)
        return merged
    }

    /** Replace the stored layout for a deliberate whole-page Web Mode translation. */
    @Synchronized
    fun replace(ctx: Context, url: String, layout: WebModeLayout) {
        scans[key(url)] = layout
        mem[key(url)] = layout
        persist(ctx, url)
    }

    /**
     * Force OCR is an explicit correction, not an ordinary incremental scan:
     * remove stale bubbles intersecting the selected crop, then install the newly measured boxes.
     * Unrelated images and bubbles remain untouched. A blank/failed OCR result never erases prior data.
     */
    @Synchronized
    fun replaceRegion(ctx: Context, url: String, src: String, region: WebModeOcrRegion, incoming: WebModeLayout) {
        val k = key(url)
        val target = WebModeLogic.normalizeUrl(src)
        val fresh = incoming.bubbles.filter {
            WebModeLogic.normalizeUrl(it.src) == target && it.sourceText.isNotBlank()
        }
        // Even an empty batch must replace the latest scan view, otherwise a caller could
        // accidentally reuse a previous chapter's stale scan. The cumulative layout is untouched.
        if (fresh.isEmpty()) {
            scans[k] = WebModeLayout(incoming.titleOffset, emptyList())
            return
        }

        val old = mem[k] ?: scans[k]
        val x0 = region.x.coerceIn(0f, 1f)
        val y0 = region.y.coerceIn(0f, 1f)
        val x1 = (region.x + region.w).coerceIn(x0, 1f)
        val y1 = (region.y + region.h).coerceIn(y0, 1f)
        val kept = old?.bubbles.orEmpty().filterNot { b ->
            if (WebModeLogic.normalizeUrl(b.src) != target) return@filterNot false
            val bl = b.cx - b.w / 2f
            val br = b.cx + b.w / 2f
            val bt = b.cy - b.h / 2f
            val bb = b.cy + b.h / 2f
            val iw = maxOf(0f, minOf(br, x1) - maxOf(bl, x0))
            val ih = maxOf(0f, minOf(bb, y1) - maxOf(bt, y0))
            val boxArea = maxOf(0f, b.w) * maxOf(0f, b.h)
            val interArea = iw * ih
            val centerInside = b.cx >= x0 && b.cx <= x1 && b.cy >= y0 && b.cy <= y1
            // Any old box touched by the selected crop is eligible for replacement: the fresh OCR
            // may have shifted the glyphs enough that an overlap-percentage threshold would miss it.
            centerInside || interArea > 0f
        }

        // The latest scan is only this OCR batch, so the chatbot reply maps to the right boxes.
        scans[k] = WebModeLayout(incoming.titleOffset, fresh)
        // Keep every unrelated bubble in the cumulative layout for the current page.
        mem[k] = WebModeLayout(incoming.titleOffset, kept + fresh)
        persist(ctx, url)
    }

    /**
     * Is [b] a re-read of the SAME bubble as [a]? Tolerances are relative to the bubble's own size. They used to be
     * fixed fractions of the whole image (0.012), which on a 12000 px tall webtoon strip is ~140 px: two different
     * bubbles one under the other counted as "the same position", and mergeLayouts replaced one by the other, so a
     * real bubble silently vanished. Different bubbles are at least a bubble-height apart; a re-read only jitters.
     */
    internal fun samePosition(a: WmBubble, b: WmBubble): Boolean {
        if (WebModeLogic.normalizeUrl(a.src) != WebModeLogic.normalizeUrl(b.src)) return false
        val maxW = maxOf(a.w, b.w); val maxH = maxOf(a.h, b.h)
        if (maxW <= 0f || maxH <= 0f) return false
        if (kotlin.math.abs(a.cx - b.cx) > 0.15f * maxW + 0.0008f) return false
        if (kotlin.math.abs(a.cy - b.cy) > 0.15f * maxH + 0.0008f) return false
        return minOf(a.w, b.w) / maxW > 0.75f && minOf(a.h, b.h) / maxH > 0.75f
    }

    private fun boxOverlapOfSmaller(a: WmBubble, b: WmBubble): Float {
        val left = maxOf(a.cx - a.w / 2f, b.cx - b.w / 2f)
        val right = minOf(a.cx + a.w / 2f, b.cx + b.w / 2f)
        val top = maxOf(a.cy - a.h / 2f, b.cy - b.h / 2f)
        val bottom = minOf(a.cy + a.h / 2f, b.cy + b.h / 2f)
        val overlap = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val smaller = minOf(a.w * a.h, b.w * b.h)
        return if (smaller <= 0f) 0f else overlap / smaller
    }

    /** A later OCR pass may grow a tight text box into the full speech-balloon interior. */
    private fun sameBubbleReading(a: WmBubble, b: WmBubble): Boolean {
        if (WebModeLogic.normalizeUrl(a.src) != WebModeLogic.normalizeUrl(b.src)) return false
        val x = WebModeLayout.normalizeOcrText(a.sourceText)
        val y = WebModeLayout.normalizeOcrText(b.sourceText)
        if (x.isBlank() || y.isBlank()) return false
        if (!(x == y || x.contains(y) || y.contains(x))) return false
        return boxOverlapOfSmaller(a, b) >= 0.55f
    }

    /**
     * [small] sits mostly inside [big] and its words are (almost) all contained in big's text: an OCR ghost
     * (a re-read of part of the bubble, e.g. "PIN ONES" from "...KEEPING AN EYE ON THE ONES"), not a bubble.
     */
    private fun ghostOf(big: WmBubble, small: WmBubble): Boolean {
        if (big === small || big.src != small.src) return false
        val bigArea = big.w * big.h
        val smallArea = small.w * small.h
        if (smallArea <= 0f || bigArea < smallArea * 1.8f) return false
        val x = Math.min(big.cx + big.w / 2, small.cx + small.w / 2) - Math.max(big.cx - big.w / 2, small.cx - small.w / 2)
        val y = Math.min(big.cy + big.h / 2, small.cy + small.h / 2) - Math.max(big.cy - big.h / 2, small.cy - small.h / 2)
        if (x <= 0f || y <= 0f || x * y < smallArea * 0.6f) return false
        val b = WebModeLayout.normalizeOcrText(big.sourceText)
        val s = WebModeLayout.normalizeOcrText(small.sourceText)
        if (s.isEmpty() || s.length > b.length * 0.6) return false
        if (b.contains(s)) return true
        val n = s.length - 1
        if (n < 2) return false
        val grams = HashSet<String>()
        for (i in 0 until b.length - 1) grams.add(b.substring(i, i + 2))
        var hit = 0
        for (i in 0 until n) if (grams.contains(s.substring(i, i + 2))) hit++
        return hit * 100 >= n * 70
    }

    private fun isShonenJumpPlus(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            .substringBefore(':').lowercase().removePrefix("www.")
        return host == "shonenjumpplus.com" || host.endsWith(".shonenjumpplus.com")
    }

    /**
     * Pure decision: did the (recycled) canvas switch to another page?
     *  - 3+ readable bubbles on both sides: positive evidence, the existing match-ratio rule decides;
     *  - a sparse fresh read (1-2 bubbles): inconclusive by count alone, so it counts as a transition only on
     *    NEGATIVE evidence: the stored page had 3+ bubbles and NOT ONE fresh bubble is explained by any of them
     *    (same canvas, overlapping box AND similar text). A re-read of the same page always explains itself, so
     *    this cannot fire on a normal partial/noisy re-scan.
     */
    internal fun canvasTransitionDetected(prior: List<WmBubble>, fresh: List<WmBubble>): Boolean {
        val p = prior.filter { WebModeLayout.normalizeOcrText(it.sourceText).isNotBlank() }
        val f = fresh.filter { WebModeLayout.normalizeOcrText(it.sourceText).isNotBlank() }
        if (p.size < 3 || f.isEmpty()) return false
        if (f.size >= 3) return !sameCanvasContent(p, f)
        return f.none { b -> p.any { a -> explainsBubble(a, b) } }
    }

    private fun explainsBubble(a: WmBubble, b: WmBubble): Boolean {
        if (WebModeLogic.normalizeUrl(a.src) != WebModeLogic.normalizeUrl(b.src)) return false
        if (boxOverlapOfSmaller(a, b) < 0.3f) return false
        return OcrLineFusion.agreement(a.sourceText, b.sourceText) >= 0.5f
    }

    /**
     * A recycled Shonen Jump+ canvas can retain identical geometry while showing an entirely new page.
     * Require several text-and-position matches before treating two OCR layouts as the same artwork.
     * Small samples are inconclusive and therefore do not delete saved translations.
     */
    private fun sameCanvasContent(previous: List<WmBubble>, fresh: List<WmBubble>): Boolean {
        val prior = previous.filter {
            WebModeLayout.normalizeOcrText(it.sourceText).isNotBlank()
        }
        val incoming = fresh.filter {
            WebModeLayout.normalizeOcrText(it.sourceText).isNotBlank()
        }
        if (prior.size < 3 || incoming.size < 3) return true

        val matchedPrior = HashSet<Int>()
        var matches = 0
        for (bubble in incoming) {
            val match = prior.indices.firstOrNull { index ->
                index !in matchedPrior && sameBubbleReading(prior[index], bubble)
            }
            if (match != null) {
                matchedPrior.add(match)
                matches++
            }
        }
        val comparable = minOf(prior.size, incoming.size)
        return matches >= 3 && matches * 100 >= comparable * 40
    }

    private fun mergeLayouts(old: WebModeLayout?, incoming: WebModeLayout): WebModeLayout {
        val result = old?.bubbles?.toMutableList() ?: mutableListOf()
        for (fresh in incoming.bubbles) {
            if (result.any { ghostOf(it, fresh) }) continue
            result.removeAll { ghostOf(fresh, it) }
            val freshKey = WebModeLayout.normalizeOcrText(fresh.sourceText)
            val samePositionIndices = result.indices.filter { samePosition(result[it], fresh) }
            val relatedIndices = result.indices.filter { sameBubbleReading(result[it], fresh) }
            val candidates = (samePositionIndices + relatedIndices).distinct()
            if (candidates.isEmpty()) {
                result.add(fresh)
                continue
            }

            // If the new scan lost words that an earlier scan had already read, do not downgrade
            // a complete bubble to a short fragment. Keep the fuller existing entry and its translation.
            val fullerPriorIndex = candidates
                .filter { idx ->
                    val priorKey = WebModeLayout.normalizeOcrText(result[idx].sourceText)
                    priorKey.length > freshKey.length + maxOf(2, freshKey.length / 10) &&
                        priorKey.contains(freshKey)
                }
                .maxByOrNull { WebModeLayout.normalizeOcrText(result[it].sourceText).length }

            if (fullerPriorIndex != null) {
                val fullerKey = WebModeLayout.normalizeOcrText(result[fullerPriorIndex].sourceText)
                val remove = candidates.filter { idx ->
                    idx != fullerPriorIndex &&
                        WebModeLayout.normalizeOcrText(result[idx].sourceText).let { key ->
                            key.isNotBlank() && fullerKey.contains(key) &&
                                sameBubbleReading(result[idx], result[fullerPriorIndex])
                        }
                }.sortedDescending()
                for (idx in remove) result.removeAt(idx)
                continue
            }

            // Prefer an exact-text entry so a previously translated bubble can keep its text,
            // but otherwise replace the old partial box with the newly recovered full-bubble box.
            val exactTextIndex = candidates.firstOrNull {
                WebModeLayout.normalizeOcrText(result[it].sourceText) == freshKey
            }
            val chosen = exactTextIndex ?: samePositionIndices.firstOrNull() ?: candidates.maxByOrNull {
                boxOverlapOfSmaller(result[it], fresh)
            }!!
            val prior = result[chosen]
            val priorKey = WebModeLayout.normalizeOcrText(prior.sourceText)
            val sameText = priorKey == freshKey
            result[chosen] = fresh.copy(
                sourceText = fresh.sourceText.ifBlank { prior.sourceText },
                translatedText = if (sameText) fresh.translatedText.ifBlank { prior.translatedText } else fresh.translatedText
            )

            // A fuller text block can absorb several earlier partial line boxes from the same balloon.
            // Remove only entries whose OCR text is contained in the recovered text and whose boxes overlap.
            val remove = candidates.filter { idx ->
                idx != chosen && WebModeLayout.normalizeOcrText(result.getOrNull(idx)?.sourceText.orEmpty()).let { key ->
                    key.isNotBlank() && freshKey.contains(key) &&
                        result.getOrNull(idx)?.let { sameBubbleReading(it, fresh) } == true
                }
            }.sortedDescending()
            for (idx in remove) if (idx in result.indices) result.removeAt(idx)
        }
        return WebModeLayout(incoming.titleOffset, result)
    }

    @Synchronized
    fun persist(ctx: Context, url: String) {
        val k = key(url)
        val l = mem[k] ?: return
        try {
            val root = try { JSONObject(Prefs.get(ctx, KEY, "{}")) } catch (_: Exception) { JSONObject() }
            val data = root.optJSONObject("data") ?: JSONObject()
            val order = ArrayList<String>()
            root.optJSONArray("order")?.let { for (i in 0 until it.length()) order.add(it.getString(i)) }
            order.remove(k); order.add(k)
            data.put(k, l.toJson())
            while (order.size > MAX) data.remove(order.removeAt(0))
            Prefs.put(ctx, KEY, JSONObject().put("order", JSONArray(order)).put("data", data).toString())
        } catch (_: Exception) { }
    }
}

/**
 * Looks at the artwork around a recognised text block: the background colour to cover the old text
 * with, a text colour that was really used, and how far the empty bubble interior reaches beyond
 * the tight text box (so a longer translation has room instead of overflowing).
 */
internal object WmBubbleStyle {
    class Style(val l: Int, val t: Int, val r: Int, val b: Int, val bg: Int, val fg: Int, val fg2: Int, val soft: Boolean, val stroke: Int = 0)

    private const val TOL = 38          // per-channel distance still counted as "the same background"
    private const val STEP = 2

    /** Axis-aligned box of a block inside a bitmap: [l, t, r, b]. */
    fun rectOf(b: OcrBlock, bw: Int, bh: Int): IntArray {
        val rad = Math.toRadians(b.angle.toDouble())
        val ex = (abs(Math.cos(rad)) * b.w + abs(Math.sin(rad)) * b.h) / 2.0
        val ey = (abs(Math.sin(rad)) * b.w + abs(Math.cos(rad)) * b.h) / 2.0
        val l = (b.cx - ex).toInt().coerceIn(0, bw - 1)
        val t = (b.cy - ey).toInt().coerceIn(0, bh - 1)
        val r = (b.cx + ex).toInt().coerceIn(l + 1, bw)
        val bt = (b.cy + ey).toInt().coerceIn(t + 1, bh)
        return intArrayOf(l, t, r, bt)
    }

    fun analyse(bmp: Bitmap, box: IntArray, others: List<IntArray>, expand: Boolean): Style {
        val bw = bmp.width
        val bh = bmp.height
        var l = box[0]; var t = box[1]; var r = box[2]; var b = box[3]
        var bg = ringMedian(bmp, l, t, r, b, 3)
        val soft = !ringFlat(bmp, l, t, r, b, bg)
        if (soft) {
            // Glow / shadow around the lettering tints the ring right next to it (that gave grey boxes on
            // black). Read the cover colour from a ring OUTSIDE the glow instead, if that ring is flat.
            val rp = ((b - t) * 0.22f).toInt().coerceIn(6, 26)
            val outer = ringMedian(bmp, l - rp, t - rp, r + rp, b + rp, 3)
            if (ringFlat(bmp, l - rp, t - rp, r + rp, b + rp, outer)) bg = outer
        }
        val mid = (t + b) / 2
        val fgTop = textColour(bmp, l, t, r, maxOf(t + 1, mid), bg)
        val fgBot = textColour(bmp, l, minOf(mid, b - 1), r, b, bg)
        val fgAll = textColour(bmp, l, t, r, b, bg)
        // Two clearly different halves = a gradient lettering: keep both ends, else one colour.
        val grad = colourDist(fgTop, fgBot) > 70 && contrast(fgTop, bg) >= MIN_CONTRAST && contrast(fgBot, bg) >= MIN_CONTRAST
        var fg = if (grad) fgTop else fgAll
        var fg2 = if (grad) fgBot else fgAll
        // Outlined lettering (fill + stroke): the old code took the colour farthest from the background, which
        // is the OUTLINE (e.g. white), so a dark-red "GRIT" with a white edge came out plain white. Read the
        // fill from the inside of the strokes and the outline from their edge, and keep both.
        var stroke = 0
        val probe = outlineProbe(bmp, l, t, r, b, bg)
        if (probe != null) {
            fg = probe[0]; fg2 = probe[0]; stroke = probe[1]
        }
        // Only speech-bubble-like (light) interiors are grown; on black pages the cover stays on the text itself.
        if (expand && lum(bg) > 70) {
            val buf = IntArray(maxOf(bw, bh) + 4)
            val maxX = maxOf(12, ((r - l) * 0.8f).toInt())
            val maxY = maxOf(12, ((b - t) * 0.8f).toInt())
            var gl = 0; var gr = 0; var gt = 0; var gb = 0
            var okL = true; var okR = true; var okT = true; var okB = true
            while (okL || okR || okT || okB) {
                if (okL) {
                    val n = l - STEP
                    if (gl >= maxX || n < 0 || hits(others, n, t, l, b) || !stripOk(bmp, n, t, l, b, bg, buf)) okL = false
                    else { l = n; gl += STEP }
                }
                if (okR) {
                    val n = r + STEP
                    if (gr >= maxX || n > bw || hits(others, r, t, n, b) || !stripOk(bmp, r, t, n, b, bg, buf)) okR = false
                    else { r = n; gr += STEP }
                }
                if (okT) {
                    val n = t - STEP
                    if (gt >= maxY || n < 0 || hits(others, l, n, r, t) || !stripOk(bmp, l, n, r, t, bg, buf)) okT = false
                    else { t = n; gt += STEP }
                }
                if (okB) {
                    val n = b + STEP
                    if (gb >= maxY || n > bh || hits(others, l, b, r, n) || !stripOk(bmp, l, b, r, n, bg, buf)) okB = false
                    else { b = n; gb += STEP }
                }
            }
            // Stay a little inside the bubble so its outline is never covered.
            if (gl > 6) l += 3
            if (gr > 6) r -= 3
            if (gt > 6) t += 3
            if (gb > 6) b -= 3
        }
        return Style(l, t, r, b, bg, fg, fg2, soft, stroke)
    }

    /**
     * Looks along rows for runs of ink wide enough to have an inside (>= 8 px). When the run's edge colour keeps
     * differing strongly from its middle colour, the lettering is outlined: returns [fill, outline] (ARGB), else null.
     */
    private fun outlineProbe(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, bg: Int): IntArray? {
        val w = r - l
        val h = b - t
        if (w < 12 || h < 12 || w.toLong() * h > 1_500_000L) return null
        val row = IntArray(w)
        val stepY = maxOf(1, h / 80)
        var runs = 0
        var outlined = 0
        var fr = 0L; var fgc = 0L; var fb = 0L
        var er = 0L; var eg = 0L; var eb = 0L
        var y = t
        while (y < b) {
            bmp.getPixels(row, 0, w, l, y, w, 1)
            var x = 0
            while (x < w) {
                if (colourDist(row[x], bg) < 90) { x++; continue }
                var e = x
                while (e < w && colourDist(row[e], bg) >= 90) e++
                val len = e - x
                if (len in 8..120) {
                    val mid = row[x + len / 2]
                    val left = row[x + 2]
                    val right = row[e - 3]
                    runs++
                    // both ends agree with each other and clearly differ from the middle
                    if (colourDist(left, right) < 70 && colourDist(left, mid) > 90 && colourDist(right, mid) > 90) {
                        outlined++
                        fr += (mid shr 16) and 255; fgc += (mid shr 8) and 255; fb += mid and 255
                        er += (((left shr 16) and 255) + ((right shr 16) and 255)) / 2
                        eg += (((left shr 8) and 255) + ((right shr 8) and 255)) / 2
                        eb += ((left and 255) + (right and 255)) / 2
                    }
                }
                x = e
            }
            y += stepY
        }
        if (runs < 6 || outlined < 6 || outlined * 100 < runs * 40) return null
        val n = outlined.toLong()
        val fill = (0xFF shl 24) or ((fr / n).toInt() shl 16) or ((fgc / n).toInt() shl 8) or (fb / n).toInt()
        val edge = (0xFF shl 24) or ((er / n).toInt() shl 16) or ((eg / n).toInt() shl 8) or (eb / n).toInt()
        return intArrayOf(fill, edge)
    }

    /**
     * Finds the whole inside of the speech bubble around [box] by flooding its fill colour (the text
     * is just "holes" in that fill) and returns [l, t, r, b] of that inside, or null when this is not
     * a flat bubble. It also returns null unless there is leftover lettering inside the bubble that no
     * recognised block ([allRects]) accounts for - i.e. text the OCR missed.
     */
    fun bubbleInterior(
        bmp: Bitmap, box: IntArray, allRects: List<IntArray>, requireLeftover: Boolean = true,
        bandsOut: MutableList<IntArray>? = null, minBand: Int = 6
    ): IntArray? {
        val bg = ringMedian(bmp, box[0], box[1], box[2], box[3], 3)
        if (!ringFlat(bmp, box[0], box[1], box[2], box[3], bg)) return null
        val bwid = box[2] - box[0]; val bhei = box[3] - box[1]
        val mx = maxOf(40, (bwid * 0.9f).toInt()); val my = maxOf(40, (bhei * 1.2f).toInt())
        val x0 = (box[0] - mx).coerceAtLeast(0); val y0 = (box[1] - my).coerceAtLeast(0)
        val x1 = (box[2] + mx).coerceAtMost(bmp.width); val y1 = (box[3] + my).coerceAtMost(bmp.height)
        val w = x1 - x0; val h = y1 - y0
        if (w < 8 || h < 8 || w.toLong() * h > 1_200_000L) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val seen = BooleanArray(w * h)
        val q = IntArray(w * h)
        var qh = 0; var qt = 0
        fun push(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val i = y * w + x
            if (seen[i] || !near(px[i], bg)) return
            seen[i] = true; q[qt++] = i
        }
        // seeds: a ring just outside the text box
        val rl = box[0] - x0 - 3; val rt = box[1] - y0 - 3; val rr = box[2] - x0 + 3; val rb = box[3] - y0 + 3
        var sx = rl
        while (sx <= rr) { push(sx, rt); push(sx, rb); sx += 3 }
        var sy = rt
        while (sy <= rb) { push(rl, sy); push(rr, sy); sy += 3 }
        if (qt == 0) return null
        while (qh < qt) {
            val i = q[qh++]
            val x = i % w; val y = i / w
            push(x + 1, y); push(x - 1, y); push(x, y + 1); push(x, y - 1)
        }
        // bubbles are closed by their outline; a fill that reaches the window edges everywhere is just the page
        var edge = 0
        for (x in 0 until w) { if (seen[x]) edge++; if (seen[(h - 1) * w + x]) edge++ }
        for (y in 0 until h) { if (seen[y * w]) edge++; if (seen[y * w + w - 1]) edge++ }
        if (edge * 100 > 2 * (w + h) * 15) return null
        // extent of the fill, ignoring thin protrusions such as the bubble tail
        val rows = IntArray(h); val cols = IntArray(w)
        for (i in 0 until w * h) if (seen[i]) { rows[i / w]++; cols[i % w]++ }
        var maxRow = 0; for (v in rows) if (v > maxRow) maxRow = v
        var maxCol = 0; for (v in cols) if (v > maxCol) maxCol = v
        if (maxRow == 0 || maxCol == 0) return null
        var t = 0; while (t < h && rows[t] * 10 < maxRow * 3) t++
        var b = h - 1; while (b > t && rows[b] * 10 < maxRow * 3) b--
        var l = 0; while (l < w && cols[l] * 10 < maxCol * 3) l++
        var r = w - 1; while (r > l && cols[r] * 10 < maxCol * 3) r--
        if (r - l < 12 || b - t < 12) return null
        // Real-time grouping only needs the bubble's inside, not proof of missed lettering.
        if (!requireLeftover && bandsOut == null) return intArrayOf(l + x0, t + y0, r + x0 + 1, b + y0 + 1)
        // Leftover lettering = "holes" in the bubble fill (between the fill's left and right edge of each row, so
        // the outline and the corners of an oval are never counted) that no recognised block accounts for.
        val rowMin = IntArray(h) { w }
        val rowMax = IntArray(h) { -1 }
        for (i in 0 until w * h) if (seen[i]) {
            val yy = i / w; val xx = i - yy * w
            if (xx < rowMin[yy]) rowMin[yy] = xx
            if (xx > rowMax[yy]) rowMax[yy] = xx
        }
        val inkRow = IntArray(h)
        var ink = 0
        for (y in t..b) {
            if (rowMax[y] < 0) continue
            for (x in rowMin[y]..rowMax[y]) {
                val i = y * w + x
                if (seen[i] || near(px[i], bg)) continue
                val ax = x + x0; val ay = y + y0
                var covered = false
                for (o in allRects) {
                    if (ax >= o[0] - 4 && ax < o[2] + 4 && ay >= o[1] - 4 && ay < o[3] + 4) { covered = true; break }
                }
                if (!covered) { inkRow[y]++; ink++ }
            }
        }
        if (bandsOut != null) {
            // consecutive ink rows (small gaps bridged) = one unread line of lettering
            var y = t
            while (y <= b) {
                if (inkRow[y] < 2) { y++; continue }
                var top = y; var bottom = y; var gap = 0
                var yy = y
                while (yy <= b && gap <= 2) {
                    if (inkRow[yy] >= 2) { bottom = yy; gap = 0 } else gap++
                    yy++
                }
                if (bottom - top + 1 >= minBand) bandsOut.add(intArrayOf(top + y0, bottom + y0 + 1))
                y = bottom + 1
            }
        }
        if (requireLeftover && ink < 40) return null
        return intArrayOf(l + x0, t + y0, r + x0 + 1, b + y0 + 1)
    }

    private fun hits(others: List<IntArray>, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        for (o in others) {
            if (x0 < o[2] + 2 && x1 > o[0] - 2 && y0 < o[3] + 2 && y1 > o[1] - 2) return true
        }
        return false
    }

    private fun near(c: Int, bg: Int): Boolean =
        abs(((c shr 16) and 255) - ((bg shr 16) and 255)) <= TOL &&
            abs(((c shr 8) and 255) - ((bg shr 8) and 255)) <= TOL &&
            abs((c and 255) - (bg and 255)) <= TOL

    /** True when (almost) every pixel of the strip still looks like the background. */
    private fun stripOk(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, bg: Int, buf: IntArray): Boolean {
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0 || w * h > buf.size) return false
        bmp.getPixels(buf, 0, w, x0, y0, w, h)
        var bad = 0
        val n = w * h
        for (i in 0 until n) if (!near(buf[i], bg)) bad++
        return bad * 100 <= n * 6
    }

    private fun median(v: IntArray, n: Int): Int {
        java.util.Arrays.sort(v, 0, n)
        return v[n / 2]
    }

    /** Median colour of a thin ring just outside the text box = the bubble's fill. */
    private fun ringMedian(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, pad: Int = 3): Int {
        val x0 = (l - pad).coerceAtLeast(0)
        val y0 = (t - pad).coerceAtLeast(0)
        val x1 = (r + pad).coerceAtMost(bmp.width - 1)
        val y1 = (b + pad).coerceAtMost(bmp.height - 1)
        val rs = IntArray(600); val gs = IntArray(600); val bs = IntArray(600)
        var n = 0
        val per = 2 * ((x1 - x0) + (y1 - y0)) + 4
        val step = maxOf(1, per / 240)
        fun add(x: Int, y: Int) {
            if (n >= rs.size) return
            val c = bmp.getPixel(x, y)
            rs[n] = (c shr 16) and 255; gs[n] = (c shr 8) and 255; bs[n] = c and 255; n++
        }
        var x = x0
        while (x <= x1) { add(x, y0); add(x, y1); x += step }
        var y = y0
        while (y <= y1) { add(x0, y); add(x1, y); y += step }
        if (n == 0) return 0xFFFFFFFF.toInt()
        return (0xFF shl 24) or (median(rs, n) shl 16) or (median(gs, n) shl 8) or median(bs, n)
    }

    private const val MIN_CONTRAST = 45   // the old threshold (90) threw away most coloured lettering for black/white

    private fun colourDist(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))

    private fun contrast(a: Int, b: Int): Int = abs(lum(a) - lum(b))

    /** True when the ring around the text is one flat colour (a speech bubble), false on artwork. */
    private fun ringFlat(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, bg: Int): Boolean {
        val pad = 3
        val x0 = (l - pad).coerceAtLeast(0); val y0 = (t - pad).coerceAtLeast(0)
        val x1 = (r + pad).coerceAtMost(bmp.width - 1); val y1 = (b + pad).coerceAtMost(bmp.height - 1)
        var n = 0; var bad = 0
        val step = maxOf(1, ((x1 - x0) + (y1 - y0)) / 60)
        fun chk(x: Int, y: Int) { n++; if (!near(bmp.getPixel(x, y), bg)) bad++ }
        var x = x0
        while (x <= x1) { chk(x, y0); chk(x, y1); x += step }
        var y = y0
        while (y <= y1) { chk(x0, y); chk(x1, y); y += step }
        return n == 0 || bad * 100 <= n * 15
    }

    private fun lum(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    /** The colour the old text was drawn in: the average of the pixels farthest from the background. */
    private fun textColour(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, bg: Int): Int {
        val sx = maxOf(1, (r - l) / 40)
        val sy = maxOf(1, (b - t) / 40)
        val px = ArrayList<Long>()
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = bmp.getPixel(x, y)
                val d = abs(((c shr 16) and 255) - ((bg shr 16) and 255)) +
                    abs(((c shr 8) and 255) - ((bg shr 8) and 255)) + abs((c and 255) - (bg and 255))
                px.add((d.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL))
                x += sx
            }
            y += sy
        }
        val fallback = if (lum(bg) > 140) 0xFF111111.toInt() else 0xFFF5F5F5.toInt()
        if (px.isEmpty()) return fallback
        px.sortDescending()
        val take = maxOf(1, px.size / 10)
        var rr = 0L; var gg = 0L; var bb = 0L
        for (i in 0 until take) {
            val c = (px[i] and 0xFFFFFFFFL).toInt()
            rr += (c shr 16) and 255; gg += (c shr 8) and 255; bb += c and 255
        }
        val fg = (0xFF shl 24) or ((rr / take).toInt() shl 16) or ((gg / take).toInt() shl 8) or (bb / take).toInt()
        // Anti-aliasing and busy artwork can blur the estimate; if it is not clearly readable, use black/white.
        return if (abs(lum(fg) - lum(bg)) < MIN_CONTRAST) fallback else fg
    }
}
