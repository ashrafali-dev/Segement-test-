package com.ashraf.novelstudio

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class Chapter(
    val title: String,
    val text: String,
    val next: String?,
    val prev: String?,
    val url: String,
    val novel: String,
    val number: String,
    val contentSel: String,
    val titleSel: String = "",
    val nextSel: String = "",
    val prevSel: String = ""
)

object Extractor {
    private const val BAD = "script,style,noscript,iframe,nav,header,footer,aside,form,button,svg,.ads,.ad,[class*=advert],[id*=advert],[class*=comment],[id*=comment],[class*=sidebar]"

    private val SELECTORS = listOf(
        // WTR-LAB's current reader uses chapter-body and chapter-container;
        // keep these ahead of generic article/main fallbacks.
        ".chapter-body", ".chapter-container .chapter-body", ".chapter-wrap .chapter-body",
        ".chapter-content", "#chapter-content", ".chapter_content", "#chr-content", ".chr-c",
        ".reading-content", ".text-left", "#content", ".entry-content", ".cha-content", ".cha-words",
        ".novel_content", ".j_readContent", ".txt", "#chaptercontent", ".chapter-c",
        "#article", ".article-content", ".content", "article"
    )

    private val NEXT = Regex("next|下一|다음|次の|次へ|次章|পরবর্তী|নেক্সট|›|»|→", RegexOption.IGNORE_CASE)
    private val PREV = Regex("prev(?!iew)|上一|이전|前の|前へ|前章|পূর্ববর্তী|আগের|প্রিভিয়াস|‹|«|←", RegexOption.IGNORE_CASE)
    private val CH_RE = Regex("chapter|chap\\.|\\bch\\b|episode|\\bep\\b|第.{1,8}[章话話节節回]|제\\s*\\d+\\s*화|\\bpart\\b|\\bvol", RegexOption.IGNORE_CASE)
    private val GENERIC = setOf("novel", "novels", "book", "books", "library", "browse", "home", "series", "manga", "genres", "ranking", "latest")

    // ------------------------------------------------------------ text
    private fun textOf(el: Element): String {
        val c = el.clone()
        c.children().select(BAD).remove()
        for (br in c.select("br")) br.after(TextNode("\n"))
        val ps = c.select("p")
        val t = if (ps.size >= 3) {
            ps.map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        } else {
            c.wholeText()
        }
        return t.replace(Regex("[ \t\u00a0]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun score(el: Element): Int {
        var s = 0
        for (n in el.childNodes()) {
            if (n is TextNode) s += n.text().trim().length
            else if (n is Element && n.tagName() == "p") s += n.text().length
        }
        return s
    }

    private fun findContent(doc: Document, preferred: String = ""): Element? {
        if (preferred.isNotBlank()) {
            try {
                val remembered = doc.selectFirst(preferred)
                if (remembered != null && textOf(remembered).length > 500) return remembered
            } catch (_: Exception) {}
        }
        for (sel in SELECTORS) {
            val e = doc.selectFirst(sel)
            if (e != null && textOf(e).length > 500) return e
        }
        var best: Element? = null
        var bs = 300
        for (e in doc.select("div,article,section,main")) {
            val s = score(e)
            if (s > bs) { bs = s; best = e }
        }
        return best
    }

    private fun findTitle(doc: Document, preferred: String = ""): Pair<String, String> {
        if (preferred.isNotBlank()) {
            try {
                val remembered = doc.selectFirst(preferred)
                val t = remembered?.text()?.trim() ?: ""
                if (t.isNotEmpty() && t.length < 200) return t to preferred
            } catch (_: Exception) {}
        }
        for (sel in listOf(".chapter-title", ".chr-title", "#chapter-heading", "h1", "h2")) {
            val t = doc.selectFirst(sel)?.text()?.trim() ?: ""
            if (t.isNotEmpty() && t.length < 200) {
                val actual = try { doc.selectFirst(sel)?.let { stableSelector(it) } ?: sel } catch (_: Exception) { sel }
                return t to actual
            }
        }
        return doc.title().trim() to ""
    }

    // ------------------------------------------------------------ story name / chapter number
    private fun findNovel(doc: Document, url: String, title: String): String {
        for (p in listOf("meta[property=og:novel:book_name]", "meta[property=og:novel:novel_name]", "meta[name=book_name]")) {
            val v = doc.selectFirst(p)?.attr("content")?.trim() ?: ""
            if (v.isNotEmpty()) return v
        }
        val host = hostOf(url).removePrefix("www.")
        val siteWord = host.substringBefore('.').lowercase()
        val parts = doc.title().split(Regex("\\s[-|–—»:]+\\s|\\s*_\\s*|\\s*\\|\\s*"))
            .map { it.trim() }.filter { it.length in 2..90 }
        for (p in parts) {
            if (CH_RE.containsMatchIn(p) || p == title) continue
            if (siteWord.isNotEmpty() && p.lowercase().replace(" ", "").contains(siteWord)) continue
            return p
        }
        for (a in doc.select("a[href]")) {
            val h = a.absUrl("href").lowercase()
            val t = a.text().trim()
            if ((h.contains("/book/") || h.contains("/novel/") || h.contains("/series/")) &&
                t.length in 3..80 && !CH_RE.containsMatchIn(t) && t.lowercase() !in GENERIC && h != url.lowercase()) return t
        }
        return host
    }

    private fun findNumber(title: String, url: String): String {
        val pats = listOf(
            Regex("\\b(?:chapter|chap|ch|episode|ep)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("第\\s*(\\d+)\\s*[章话話节節回]"),
            Regex("제\\s*(\\d+)\\s*화"),
            Regex("(\\d+)\\s*[화話话]")
        )
        val isWebNovel = hostOf(url).contains("webnovel.com", ignoreCase = true)
        for (p in pats) {
            val m = p.find(title)
            if (m != null) {
                val value = m.groupValues[1]
                // WebNovel sometimes labels an opaque internal ID as a zero-padded
                // chapter number (e.g. "Chapter 06730"). Do not show that as the
                // human-facing chapter index.
                if (isWebNovel && value.length >= 4 && value.startsWith("0")) return ""
                return value
            }
        }
        // Never infer WebNovel's chapter number from a random number in the title
        // or its opaque URL ID. Keep the progress card title-only when unknown.
        if (isWebNovel) return ""
        // A title that simply starts with its number: "7. The Duel", "12 - Fire". A number that merely appears
        // somewhere ("2365 years later") is NOT a chapter number any more.
        Regex("^\\s*#?(\\d{1,4})\\s*(?:[.:：、)\\-–—]\\s*\\S|$)").find(title)?.let { return it.groupValues[1] }
        val path = pathOf(url)
        // The URL only counts when it says so itself: chapter-12, /ep/7, ?chapter=3 ...
        Regex("(?i)(?:chapter|chap|chapitre|capitulo|episode|ep|ch)[-_/=.]?0*(\\d{1,5}(?:\\.\\d+)?)").findAll(path).lastOrNull()
            ?.let { return it.groupValues[1] }
        Regex("(?i)[?&](?:chapter|chap|ch|episode|ep)=(\\d{1,5})(?:&|$)").find(url)?.let { return it.groupValues[1] }
        // Bare trailing number in the URL (/123/45.html): accept it only when the title shows the same number,
        // otherwise it is usually a book/site id and must not be shown as the chapter.
        val last = Regex("(\\d{1,4})(?!.*\\d)").find(path)?.value
        if (last != null && Regex("(?<!\\d)" + last.trimStart('0').ifEmpty { "0" } + "(?!\\d)").containsMatchIn(title)) {
            return last.trimStart('0').ifEmpty { "0" }
        }
        return ""
    }

    /** Public entry for other extractors (prefetch) so every path derives the chapter number the same safe way. */
    fun chapterNumberOf(title: String, url: String): String = findNumber(title, url)

    // Prefer selectors that survive chapter-to-chapter DOM changes.
    // Jsoup's cssSelector() can generate positional :nth-child selectors,
    // which are often useless after a SPA rerender.
    private fun stableSelector(el: Element): String {
        val id = el.id().trim()
        if (id.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) return "#$id"
        val tag = el.tagName()
        val cls = el.classNames()
            .filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*")) }
            .take(3)
        if (cls.isNotEmpty()) return tag + cls.joinToString("") { ".$it" }
        return tag
    }

    // ------------------------------------------------------------ links
    private fun strip(u: String) = u.substringBefore('#')

    private fun hostOf(u: String): String {
        val i = u.indexOf("://")
        if (i < 0) return ""
        return u.substring(i + 3).substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    }

    private fun pathOf(u: String): String {
        val i = u.indexOf("://")
        if (i < 0) return ""
        val rest = u.substring(i + 3)
        val s = rest.indexOf('/')
        if (s < 0) return ""
        return rest.substring(s).substringBefore('?').substringBefore('#')
    }

    private fun segs(u: String) = pathOf(u).split('/').count { it.isNotEmpty() }
    private fun hostRoot(u: String) = hostOf(u).split('.').takeLast(2).joinToString(".")

    // A "next chapter" link must stay on the same site and must not jump UP to a home/book page
    private fun plausible(cur: String, cand: String): Boolean {
        if (hostRoot(cur) != hostRoot(cand)) return false
        val c = segs(cand)
        return c >= 1 && c >= segs(cur)
    }

    private fun findLink(doc: Document, url: String, kind: String, preferred: String = ""): Pair<String?, String> {
        val re = if (kind == "next") NEXT else PREV
        if (preferred.isNotBlank()) {
            try {
                val remembered = doc.selectFirst(preferred)
                if (remembered != null) {
                    val h = remembered.absUrl("href")
                    if (h.isNotEmpty() && strip(h) != strip(url) && plausible(url, h)) return h to preferred
                }
            } catch (_: Exception) {}
        }
        val rel = doc.selectFirst("link[rel=$kind], a[rel=$kind]")
        if (rel != null) {
            val h = rel.absUrl("href")
            if (h.isNotEmpty() && strip(h) != strip(url) && plausible(url, h)) {
                val actual = try { stableSelector(rel) } catch (_: Exception) { "" }
                return h to actual
            }
        }
        var best: String? = null
        var bs = 0
        for (a in doc.select("a[href]")) {
            val h = a.absUrl("href")
            if (!h.startsWith("http") || strip(h) == strip(url) || !plausible(url, h)) continue
            if (a.className().contains("disabled", true) || a.attr("aria-disabled") == "true") continue
            val txt = a.text().trim()
            val meta = listOf(a.attr("aria-label"), a.attr("title"), a.id(), a.className(), a.attr("rel")).joinToString(" ")
            var s = 0
            if (txt.length <= 30 && re.containsMatchIn(txt)) s += 3
            if (re.containsMatchIn(meta)) s += 2
            if (s > bs) { bs = s; best = h }
        }
        if (best != null) {
            val el = doc.select("a[href]").firstOrNull { it.absUrl("href") == best }
            val actual = try { el?.let { stableSelector(it) } ?: "" } catch (_: Exception) { "" }
            return best to actual
        }
        return null to ""
    }

    // Last resort only (short chapter numbers only — never long IDs like webnovel's)
    fun bump(url: String, d: Int): String? {
        val q = url.indexOfAny(charArrayOf('?', '#'))
        val base = if (q < 0) url else url.substring(0, q)
        val rest = if (q < 0) "" else url.substring(q)
        val hostEnd = base.indexOf('/', 8)
        if (hostEnd < 0) return null
        val head = base.substring(0, hostEnd)
        val path = base.substring(hostEnd)
        val m = Regex("(\\d+)(?!.*\\d)").find(path) ?: return null
        if (m.value.length > 6) return null
        val n = m.value.toLong() + d
        if (n < 0) return null
        val nv = n.toString().padStart(m.value.length, '0')
        return head + path.replaceRange(m.range, nv) + rest
    }

    fun extract(
        doc: Document,
        url: String,
        preferredContent: String = "",
        preferredTitle: String = "",
        preferredNext: String = "",
        preferredPrev: String = ""
    ): Chapter? {
        val el = findContent(doc, preferredContent) ?: return null
        val body = textOf(el)
        val (title, titleSel) = findTitle(doc, preferredTitle)
        val (next, nextSel) = findLink(doc, url, "next", preferredNext)
        val (prev, prevSel) = findLink(doc, url, "prev", preferredPrev)
        val text = if (body.startsWith(title)) body else title + "\n\n" + body
        val sel = try { stableSelector(el) } catch (e: Exception) { "" }
        return Chapter(
            title, text, next, prev, url, findNovel(doc, url, title),
            findNumber(title, url), sel, titleSel, nextSel, prevSel
        )
    }
}
