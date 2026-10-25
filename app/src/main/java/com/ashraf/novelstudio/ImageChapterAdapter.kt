package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Source-neutral image chapter metadata. Page URLs remain in the order the source reader presents them.
 * This is metadata only: no chapter images are copied into the app's repository or bundled into the APK.
 */
internal data class ImageChapter(
    val sourceId: String,
    val title: String,
    val novel: String,
    val number: String,
    val url: String,
    val pageUrls: List<String>,
    val next: String? = null,
    val prev: String? = null
) {
    fun toJson(): String = JSONObject()
        .put("sourceId", sourceId)
        .put("title", title)
        .put("novel", novel)
        .put("number", number)
        .put("url", url)
        .put("pages", JSONArray(pageUrls))
        .put("next", next ?: "")
        .put("prev", prev ?: "")
        .toString()

    companion object {
        fun fromJson(raw: String): ImageChapter? = runCatching {
            val o = JSONObject(raw)
            val pages = o.optJSONArray("pages") ?: JSONArray()
            val urls = ImageChapterPageUrls.orderedDistinct(
                (0 until pages.length()).mapNotNull { pages.optString(it).takeIf(String::isNotBlank) }
            )
            val url = ReaderSourceUrl.validate(o.optString("url")) ?: return null
            if (urls.isEmpty()) return null
            ImageChapter(
                sourceId = o.optString("sourceId").ifBlank { "unknown" },
                title = o.optString("title").ifBlank { "Comic chapter" },
                novel = o.optString("novel").ifBlank { "Unknown" },
                number = o.optString("number"),
                url = url,
                pageUrls = urls,
                next = ReaderSourceUrl.validate(o.optString("next")).takeIf { it != url },
                prev = ReaderSourceUrl.validate(o.optString("prev")).takeIf { it != url }
            )
        }.getOrNull()
    }
}

/** Small, pure helpers make URL handling testable without loading Android WebView. */
internal object ImageChapterPageUrls {
    fun normalize(raw: String): String? = runCatching {
        val value = raw.trim()
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        value.substringBefore('#').takeIf { it.isNotBlank() }
    }.getOrNull()

    fun orderedDistinct(rawUrls: Iterable<String>): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (raw in rawUrls) {
            val url = normalize(raw) ?: continue
            if (seen.add(url)) result.add(url)
        }
        return result
    }
}

/**
 * Matches Mihon/Keiyoushi Naver reader precedence: use the desktop viewer image list first,
 * then the mobile toon-view list (whose data-src is the authoritative image URL), then known
 * Naver viewer containers as a compatibility fallback. The returned order is source DOM order.
 */
internal object NaverComicPageSources {
    fun prefer(
        viewerPages: Iterable<String>,
        toonPages: Iterable<String>,
        fallbackPages: Iterable<String> = emptyList()
    ): List<String> {
        val viewer = ImageChapterPageUrls.orderedDistinct(viewerPages)
        if (viewer.isNotEmpty()) return viewer
        val toon = ImageChapterPageUrls.orderedDistinct(toonPages)
        if (toon.isNotEmpty()) return toon
        return ImageChapterPageUrls.orderedDistinct(fallbackPages)
    }
}

internal interface ImageChapterAdapter {
    val id: String
    fun matches(url: String?): Boolean
    fun extract(webView: WebView, callback: (ImageChapter?) -> Unit)
}

/**
 * Built-in Naver Webtoon chapter image adapter.
 *
 * It intentionally targets episode-detail URLs and Naver's chapter viewer containers only.
 * It will not turn search/catalogue pages into a reader just because they contain thumbnails.
 */
internal class NaverComicImageAdapter : ImageChapterAdapter {
    override val id: String = "naver-comic"

    override fun matches(url: String?): Boolean {
        val validated = ReaderSourceUrl.validate(url) ?: return false
        return runCatching {
            val uri = URI(validated)
            val host = uri.host.lowercase().removePrefix("www.")
            val path = uri.path.orEmpty().lowercase()
            val query = query(uri.rawQuery)
            host in HOSTS &&
                (path.contains("/webtoon/detail") || path.contains("/bestchallenge/detail") || path.contains("/challenge/detail")) &&
                query["titleId"]?.matches(Regex("\\d{1,12}")) == true &&
                query["no"]?.matches(Regex("\\d{1,9}")) == true
        }.getOrDefault(false)
    }

    override fun extract(webView: WebView, callback: (ImageChapter?) -> Unit) {
        val currentUrl = ReaderSourceUrl.validate(webView.url)
        if (currentUrl == null || !matches(currentUrl)) {
            callback(null)
            return
        }
        webView.evaluateJavascript(EXTRACT_SCRIPT) { raw ->
            val payload = runCatching {
                if (raw.isNullOrBlank() || raw == "null") "" else JSONArray("[$raw]").getString(0)
            }.getOrDefault("")
            if (payload.isBlank()) {
                callback(null)
                return@evaluateJavascript
            }
            val o = runCatching { JSONObject(payload) }.getOrNull()
            if (o == null) {
                callback(null)
                return@evaluateJavascript
            }
            val sourceUrl = ReaderSourceUrl.validate(o.optString("url")) ?: currentUrl
            fun readPages(key: String): List<String> {
                val array = o.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }
            val pages = NaverComicPageSources.prefer(
                readPages("viewerPages"),
                readPages("toonPages"),
                readPages("fallbackPages")
            )
            if (pages.isEmpty()) {
                callback(null)
                return@evaluateJavascript
            }
            val uri = runCatching { URI(sourceUrl) }.getOrNull()
            val query = query(uri?.rawQuery)
            val title = o.optString("title").trim().take(200)
            val novel = o.optString("novel").trim().take(120)
            val next = ReaderSourceUrl.validate(o.optString("next")).takeIf { it != sourceUrl }
            val prev = ReaderSourceUrl.validate(o.optString("prev")).takeIf { it != sourceUrl }
            callback(
                ImageChapter(
                    sourceId = id,
                    title = title.ifBlank { "Episode ${query["no"].orEmpty()}" },
                    novel = novel.ifBlank { "Naver Webtoon" },
                    number = query["no"].orEmpty(),
                    url = sourceUrl,
                    pageUrls = pages,
                    next = next,
                    prev = prev
                )
            )
        }
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&").mapNotNull { part ->
            val key = part.substringBefore("=", "")
            if (key.isBlank()) null else {
                val value = part.substringAfter("=", "")
                runCatching {
                    URLDecoder.decode(key, Charsets.UTF_8.name()) to
                        URLDecoder.decode(value, Charsets.UTF_8.name())
                }.getOrNull()
            }
        }.toMap()
    }

    companion object {
        private val HOSTS = setOf("comic.naver.com", "m.comic.naver.com")

        /**
         * Keep collection inside chapter-specific viewer elements, not every IMG on the page.
         * Support both current desktop/mobile Naver viewer markup and its lazy-loaded attributes.
         */
        internal const val EXTRACT_SCRIPT = """
(function(){
  try {
    var current = location.href;
    function absolute(raw) {
      if (!raw || !String(raw).trim()) return '';
      try {
        var u = new URL(String(raw).trim(), document.baseURI);
        return (u.protocol === 'http:' || u.protocol === 'https:') ? u.href : '';
      } catch (e) { return ''; }
    }
    var LAZY = ['data-src','data-original','data-lazy-src','data-url','data-image',
      'data-original-src','data-lazy','data-echo','data-actualsrc','data-orig-src'];
    function lazyUrl(im) {
      for (var i=0;i<LAZY.length;i++) {
        var v = absolute(im.getAttribute(LAZY[i]) || '');
        if (v) return v;
      }
      return '';
    }
    function sourceUrl(im, preferLazy) {
      var lazy = lazyUrl(im);
      var declared = im.getAttribute('src') || '';
      var live = im.currentSrc || declared || '';
      var src = absolute(live);
      var naturalWidth = im.naturalWidth || 0;
      var isPlaceholder = !src ||
        /^(?:data:|about:)/i.test(live) ||
        /(?:transparent|placeholder|spacer|blank|loading|lazy[-_.])/i.test(live);
      // Mihon's mobile reader uses data-src before src. Desktop viewer prefers src/currentSrc,
      // except when that source is a placeholder or the browser has not loaded any pixels yet.
      if (preferLazy && lazy) return lazy;
      if (lazy && (isPlaceholder || !im.complete || naturalWidth <= 2)) return lazy;
      return src || lazy;
    }
    function collect(selector, preferLazy) {
      var nodes = document.querySelectorAll(selector);
      var out = [], seen = Object.create(null);
      for (var i=0;i<nodes.length;i++) {
        var src = sourceUrl(nodes[i], preferLazy);
        if (!src || seen[src]) continue;
        seen[src] = true;
        out.push(src);
      }
      return out;
    }
    // Keep Mihon's actual precedence. Do not filter by naturalWidth/height: Naver lazy images
    // commonly report a 1x1 placeholder until requested, and small panels are still valid pages.
    var viewerPages = collect('.wt_viewer img', false);
    var toonPages = collect('.toon_view_lst img.toon_image', true);
    // Fallback only for Naver page variants that moved away from those known Mihon containers.
    var fallbackPages = collect('#_imageList img, #comic_view_area img, .webtoon_viewer img, img[data-page-no]', false);

    function textOf(selectors) {
      for (var i=0;i<selectors.length;i++) {
        var e = document.querySelector(selectors[i]);
        var t = e ? (e.innerText || e.textContent || '').trim() : '';
        if (t && t.length < 220) return t;
      }
      return '';
    }
    function chapterLink(kind) {
      var direct = kind === 'next'
        ? ['a.btn_next','a.pg_next','a[rel="next"]','link[rel="next"]','a[data-direction="next"]']
        : ['a.btn_prev','a.pg_prev','a[rel="prev"]','link[rel="prev"]','a[data-direction="prev"]'];
      for (var i=0;i<direct.length;i++) {
        var el = document.querySelector(direct[i]);
        if (el && el.href) {
          var resolved = absolute(el.href);
          if (resolved && resolved.split('#')[0] !== current.split('#')[0]) return resolved;
        }
      }
      var re = kind === 'next'
        ? /(다음화|다음 회|next chapter|next episode)/i
        : /(이전화|이전 회|previous chapter|previous episode)/i;
      var anchors = document.querySelectorAll('a[href]');
      for (var j=0;j<anchors.length;j++) {
        var a=anchors[j];
        var label=((a.innerText || a.textContent || '')+' '+(a.getAttribute('aria-label') || '')+' '+(a.title || '')).trim();
        if (!re.test(label) || /disabled/i.test(a.className || '') || a.getAttribute('aria-disabled') === 'true') continue;
        var href=absolute(a.href);
        if (href && href.split('#')[0] !== current.split('#')[0]) return href;
      }
      return '';
    }
    var title = textOf(['.subj_episode','.episode_title','.tit_area h2','.wt_viewer h2','h2']) || document.title || '';
    var novel = textOf(['.detail_header .subj','.title_area .title','#titleName','.toon_name','.series-title','h1']);
    var q = new URL(location.href).searchParams;
    return JSON.stringify({
      url: current,
      title: title,
      novel: novel,
      number: q.get('no') || '',
      viewerPages: viewerPages,
      toonPages: toonPages,
      fallbackPages: fallbackPages,
      next: chapterLink('next'),
      prev: chapterLink('prev')
    });
  } catch(e) { return ''; }
})()
"""
    }
}

internal object ImageChapterRegistry {
    private val adapters: List<ImageChapterAdapter> = listOf(NaverComicImageAdapter())

    fun find(url: String?): ImageChapterAdapter? =
        adapters.firstOrNull { it.matches(url) }
}
