package com.ashraf.novelstudio

/** Pure helpers for Web Mode (no Android types, so they are unit-testable on the JVM). */
internal data class PanelRef(val src: String, val top: Int, val left: Int, val w: Int, val h: Int)

internal object WebModeLogic {
    const val SLICE_H = 2000
    const val MAX_FAIL_FRACTION = 0.30

    fun normalizeUrl(u: String): String = u.substringBefore('#').trim()

    /** Remove duplicate image URLs and order panels by their position in the page. */
    fun dedupeAndOrder(refs: List<PanelRef>): List<PanelRef> {
        val ordered = refs.distinctBy { normalizeUrl(it.src) }
            .sortedWith(compareBy<PanelRef>({ it.top }, { it.left }))
        // Lazy-loading pages keep a clone of the same picture at exactly the same position (the URL differs only in its
        // query string). The clone was downloaded and OCR-ed again, and its failed download sent the whole job to the
        // 17-20 s screen-capture fallback.
        val out = ArrayList<PanelRef>(ordered.size)
        for (r in ordered) {
            val twin = out.any { o ->
                Math.abs(o.top - r.top) <= 2 && Math.abs(o.left - r.left) <= 2 &&
                    Math.abs(o.w - r.w) <= 2 && Math.abs(o.h - r.h) <= 2
            }
            if (!twin) out.add(r)
        }
        return out
    }

    private fun hostOfUrl(u: String): String =
        u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    /**
     * Page furniture that is not a comic panel (promo banners, avatars of the comment section, UI sprites). The log
     * showed 220x202, 420x86, 420x420 and 34x34 images from other hosts being "read" again and again: each one cost
     * Lens + ML Kit calls, produced garbage such as "아 하c YO P" and made the read-ahead reply fail its alignment
     * check. A picture is dropped only when the page also has a clearly bigger picture from ANOTHER host that
     * dominates it (the real panels) and this one is much smaller; then it cannot be a panel of the chapter.
     */
    fun dropChromeImages(refs: List<PanelRef>): List<PanelRef> {
        if (refs.size < 2) return refs
        val byHost = refs.groupBy { hostOfUrl(it.src) }
        if (byHost.size < 2) return refs
        val dominantHost = byHost.maxByOrNull { (_, v) -> v.sumOf { it.w.toLong() * it.h } }?.key ?: return refs
        val dom = byHost[dominantHost].orEmpty()
        if (dominantHost.isEmpty() || dom.isEmpty()) return refs
        val areas = dom.map { it.w.toLong() * it.h }.sorted()
        val medianDom = areas[areas.size / 2]
        if (medianDom <= 0L) return refs
        val domWidth = dom.maxOf { it.w }
        return refs.filter { r ->
            if (hostOfUrl(r.src) == dominantHost) return@filter true
            val area = r.w.toLong() * r.h
            val tiny = r.w < 120 || r.h < 100
            val smallerThanPanels = area < medianDom * 0.6
            val narrowerThanPanels = r.w < domWidth * 0.9
            // keep it when it is as big as a panel (a chapter that really mixes CDNs)
            !(tiny || (smallerThanPanels && (narrowerThanPanels || r.h < 0.5 * dom.minOf { it.h })) || r.h < r.w * 0.3)
        }
    }

    private val KO = Regex("naver\\.com|kakao|webtoons\\.com/ko|comic\\.naver", RegexOption.IGNORE_CASE)
    private val JA = Regex("webtoons\\.com/ja|comic-walker|piccoma|pixiv|shonenjump", RegexOption.IGNORE_CASE)
    private val ZH = Regex("webtoons\\.com/zh|bilibili|kuaikan|ac\\.qq|manhuagui|dmzj|manhuaren", RegexOption.IGNORE_CASE)

    /** First-guess script from the page language and URL. A fallback chain covers wrong guesses. */
    fun pickScript(lang: String, url: String): OcrScript {
        val l = lang.trim().lowercase()
        return when {
            l.startsWith("ko") || KO.containsMatchIn(url) -> OcrScript.KOREAN
            l.startsWith("ja") || JA.containsMatchIn(url) -> OcrScript.JAPANESE
            l.startsWith("zh") || ZH.containsMatchIn(url) -> OcrScript.CHINESE
            else -> OcrScript.LATIN
        }
    }

    /** Other scripts to try, in order, when the first guess reads nothing during a thorough scan. */
    fun fallbackScripts(first: OcrScript): List<OcrScript> =
        listOf(OcrScript.CHINESE, OcrScript.JAPANESE, OcrScript.KOREAN, OcrScript.LATIN).filter { it != first }

    /**
     * Realtime gets a small, shared retry budget. Prefer Japanese/Korean ahead of Chinese when a
     * generic/English page guess is wrong; failed alternates are remembered so the next panel can
     * test the next language instead of retrying Chinese forever.
     */
    fun realtimeFallbackScripts(first: OcrScript): List<OcrScript> {
        val order = when (first) {
            OcrScript.LATIN -> listOf(OcrScript.JAPANESE, OcrScript.KOREAN, OcrScript.CHINESE)
            OcrScript.JAPANESE -> listOf(OcrScript.CHINESE, OcrScript.KOREAN, OcrScript.LATIN)
            OcrScript.KOREAN -> listOf(OcrScript.JAPANESE, OcrScript.CHINESE, OcrScript.LATIN)
            OcrScript.CHINESE -> listOf(OcrScript.JAPANESE, OcrScript.KOREAN, OcrScript.LATIN)
        }
        return order.filter { it != first }
    }

    /** Rows shared by two neighbouring slices, so text sitting on a cut is whole in at least one of them. */
    const val SLICE_OVERLAP = 240

    /**
     * [start, endExclusive) row ranges, each at most [sliceH] tall. Neighbouring slices share
     * [overlap] rows (text on the cut line used to be split in half and skipped by OCR).
     */
    fun slices(height: Int, sliceH: Int = SLICE_H, overlap: Int = SLICE_OVERLAP): List<Pair<Int, Int>> {
        if (height <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        val ov = overlap.coerceIn(0, sliceH / 2)
        var y = 0
        while (true) {
            val end = minOf(height, y + sliceH)
            out.add(y to end)
            if (end >= height) break
            y = end - ov
        }
        return out
    }

    private val QUERY_NO = Regex("[?&](?:episode_no|episode|ep|chapter_no|chapter|no)=(\\d{1,6})", RegexOption.IGNORE_CASE)
    private val TITLE_NO = listOf(
        Regex("\\b(?:chapter|chap|ch|episode|ep)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        Regex("第\\s*(\\d+)\\s*[章话話节節回]"),
        Regex("제\\s*(\\d+)\\s*화"),
        Regex("(\\d+)\\s*[화話话]")
    )

    fun episodeNumber(title: String, url: String): String {
        QUERY_NO.find(url)?.let { return it.groupValues[1] }
        for (p in TITLE_NO) p.find(title)?.let { return it.groupValues[1] }
        val path = url.substringBefore('?').substringBefore('#')
        Regex("(\\d{1,6})(?!.*\\d)").find(path)?.let { return it.groupValues[1] }
        return ""
    }

    /** Title first, then each panel's text as its own paragraph(s), in panel order. */
    fun assemble(title: String, panelTexts: List<String>): String {
        val body = panelTexts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        val t = title.trim()
        return if (t.isEmpty()) body else if (body.isEmpty()) t else t + "\n\n" + body
    }

    /** True when too many panels failed for the chapter to be called complete. */
    fun tooManyFailures(total: Int, failed: Int): Boolean =
        total > 0 && failed > 0 && failed.toDouble() / total > MAX_FAIL_FRACTION
}
