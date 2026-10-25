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
            val pagesArray = o.optJSONArray("pages") ?: JSONArray()
            val pages = ImageChapterPageUrls.orderedDistinct(
                (0 until pagesArray.length()).mapNotNull { pagesArray.optString(it).takeIf(String::isNotBlank) }
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
    function textOf(selectors) {
      for (var i=0;i<selectors.length;i++) {
        var e=document.querySelector(selectors[i]);
        var t=e?(e.innerText||e.textContent||'').trim():'';
        if(t && t.length<220) return t;
      }
      return '';
    }
    function urlOf(im) {
      var keys=['data-src','data-original','data-lazy-src','data-url','data-image','data-original-src'];
      var src=im.currentSrc||im.getAttribute('src')||'';
      if(!src || /^(?:about:blank|data:image\/(?:gif|svg))/i.test(src)) {
        for(var i=0;i<keys.length;i++) {
          var v=im.getAttribute(keys[i]);
          if(v && !/^(?:about:blank|data:image\/(?:gif|svg))/i.test(v)){src=v;break;}
        }
      }
      try { return new URL(src, document.baseURI).href; } catch(e) { return ''; }
    }
    var selectors=['.wt_viewer img','.toon_view_lst img.toon_image','#_imageList img','#comic_view_area img'];
    var imgs=[], seen=Object.create(null);
    // A single combined selector preserves original DOM order if a page exposes more than one wrapper.
    var nodes=document.querySelectorAll(selectors.join(','));
    for(var ni=0;ni<nodes.length;ni++){
      var im=nodes[ni], src=urlOf(im);
      if(!src || seen[src]) continue;
      var meta=((im.className||'')+' '+(im.id||'')+' '+(im.alt||'')+' '+src).toLowerCase();
      if(/logo|avatar|profile|banner|badge|spinner|advert|\/ads?\/|link_slo/.test(meta)) continue;
      var w=im.naturalWidth||im.width||0, h=im.naturalHeight||im.height||0;
      if((w>0 && w<180) || (h>0 && h<180)) continue;
      seen[src]=1;
      imgs.push(src);
    }
    function chapterLink(kind) {
      var re=kind==='next'?/(다음화|다음 회|next chapter|next episode)/i:/(이전화|이전 회|previous chapter|previous episode)/i;
      var a=Array.from(document.querySelectorAll('a[href]')).find(function(x){
        var t=((x.innerText||x.textContent||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.title||'')).trim();
        return re.test(t) && !/disabled/i.test(x.className||'');
      });
      if(!a) return '';
      try { return new URL(a.href,location.href).href; } catch(e) { return ''; }
    }
    var title=textOf(['.subj_episode','.episode_title','.tit_area h2','.wt_viewer h2','h2']);
    var novel=textOf(['.detail_header .subj','.title_area .title','#titleName','.toon_name','.series-title','h1']);
    if(!title) title=document.title||'';
    var q=new URL(location.href).searchParams;
    return JSON.stringify({
      url:current,
      title:title,
      novel:novel,
      number:q.get('no')||'',
      pages:imgs,
      next:chapterLink('next'),
      prev:chapterLink('prev')
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
