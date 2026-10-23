package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class Novel543Extractor(private val context: android.content.Context) {
    fun extract(webView: WebView, attempt: Int = 0, callback: (Chapter?) -> Unit) {
        val url = webView.url.orEmpty()
        if (url.isBlank()) { callback(null); return }
        val script = """
(function(){
  var root=document.querySelector('#chapterWarp .chapter-content, .chapter-content');
  if(!root)return JSON.stringify({ok:false});
  var titleEl=root.querySelector(':scope > h1'), body=root.querySelector(':scope > .content');
  if(!titleEl||!body)return JSON.stringify({ok:false});
  function clean(s){return String(s||'').replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').trim();}
  var title=clean(titleEl.innerText||titleEl.textContent);
  var paragraphs=Array.from(body.querySelectorAll(':scope > p')).map(function(p){return clean(p.innerText||p.textContent);}).filter(function(t){return !!t;});
  function link(label){var a=Array.from(document.querySelectorAll('.foot-nav a[href]')).find(function(x){return clean(x.innerText||x.textContent).indexOf(label)>=0;});if(!a)return '';try{return new URL(a.href,location.href).href;}catch(e){return a.href||'';}}
  var meta=document.querySelector('meta[name="keywords"]'), novel=meta?clean((meta.content||'').split(',')[0]):'';
  if(!novel)novel=location.host.replace(/^www\./,'');
  return JSON.stringify({
    ok:!!title&&paragraphs.length>0&&paragraphs.join('').length>40,
    title:title, text:(title?title+'\n\n':'')+paragraphs.join('\n\n'), count:paragraphs.length,
    next:link('下一章'), prev:link('上一章'), url:location.href, novel:novel,
    number:(title.match(/第\s*([一二三四五六七八九十百千万零〇两\d]+)\s*章/)||[])[1]||'',
    contentSel:'#chapterWarp .chapter-content > .content',
    titleSel:'#chapterWarp .chapter-content > h1',
    nextSel:'.foot-nav a[href]', prevSel:'.foot-nav a[href]'
  });
})()
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val payload = decode(raw)
            val o = runCatching { JSONObject(payload) }.getOrNull()
            val text = o?.optString("text", "").orEmpty()
            if (o?.optBoolean("ok", false) != true || text.length < 80) {
                if (attempt < 8) webView.postDelayed({ extract(webView, attempt + 1, callback) }, 180L) else callback(null)
                return@evaluateJavascript
            }
            val chapter = Chapter(
                o.optString("title", "").trim(), text,
                o.optString("next", "").takeIf { it.startsWith("http") },
                o.optString("prev", "").takeIf { it.startsWith("http") },
                url, o.optString("novel", "Novel543").ifBlank { "Novel543" },
                o.optString("number", ""), o.optString("contentSel", "#chapterWarp .chapter-content > .content"),
                o.optString("titleSel", "#chapterWarp .chapter-content > h1"),
                o.optString("nextSel", ".foot-nav a[href]"), o.optString("prevSel", ".foot-nav a[href]")
            )
            SiteProfiles.remember(context, chapter)
            callback(chapter)
        }
    }
    private fun decode(raw: String?): String = try {
        if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0)
    } catch (_: Exception) { "" }
}
