package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class QidianExtractor {
    fun extract(webView: WebView, attempt: Int = 0, callback: (Chapter?) -> Unit) {
        val url = webView.url.orEmpty()
        if (url.isBlank()) { callback(null); return }
        val script = """
(function(){
  var m=location.pathname.match(/\/chapter\/(\d+)\/(\d+)/);
  if(!m)return JSON.stringify({ok:false});
  var bookId=m[1], chapterId=m[2];
  var root=document.getElementById('c-'+chapterId);
  if(!root || !root.matches('main.content')) {
    root=Array.from(document.querySelectorAll('main.content')).find(function(e){return e.id==='c-'+chapterId;})||null;
  }
  if(!root)return JSON.stringify({ok:false});
  var wrap=root.closest('.print')||root.parentElement;
  var titleEl=wrap&&wrap.querySelector('h1.title');
  function clean(s){return String(s||'').replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').trim();}
  function plain(el){
    if(!el)return '';
    var clone=el.cloneNode(true);
    clone.querySelectorAll('.review,.review-content').forEach(function(x){x.remove();});
    return clean(clone.innerText||clone.textContent||'');
  }
  var title=plain(titleEl);
  var titleCount=titleEl&&titleEl.querySelector('.review-count');
  if(titleCount && title) title += ' ['+clean(titleCount.textContent)+']';
  var lines=Array.from(root.querySelectorAll(':scope > p')).map(function(p){
    var e=p.querySelector('.content-text');
    if(!e)return '';
    var t=clean(e.innerText||e.textContent);
    var count=p.querySelector('.review .review-count');
    if(count && t)t+=' ['+clean(count.textContent)+']';
    return t;
  }).filter(function(t){return !!t;});
  var source=location.origin+location.pathname.replace(/\/+$/,'');
  function norm(u){try{var x=new URL(u,location.href);return x.origin+x.pathname.replace(/\/+$/,'');}catch(e){return '';}}
  var anchors=Array.from(document.querySelectorAll('li[id^="chapter-item-"] > a[href*="/chapter/"]'));
  var items=[],seen={};
  anchors.forEach(function(a){var h=norm(a.href);if(h&&!seen[h]){seen[h]=1;items.push(h);}});
  var idx=items.indexOf(source), next=idx>=0?items[idx+1]:'', prev=idx>0?items[idx-1]:'';
  if(!next){
    var n=document.querySelector('a.nav-btn');
    if(n && /下一章/.test(n.innerText||'')) next=norm(n.href);
  }
  return JSON.stringify({
    ok:lines.length>0,
    title:title,
    text:(title?title+'\n\n':'')+lines.join('\n\n'),
    count:lines.length,
    next:next||'',
    prev:prev||'',
    url:location.href,
    novel:(document.title||'').split('_')[0].trim(),
    contentSel:'#c-'+chapterId,
    titleSel:'.print[data-id="'+chapterId+'"] > h1.title'
  });
})()
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val payload = decode(raw)
            val o = runCatching { JSONObject(payload) }.getOrNull()
            val text = o?.optString("text", "").orEmpty()
            if (o?.optBoolean("ok", false) != true || text.length < 40) {
                if (attempt < 8) webView.postDelayed({ extract(webView, attempt + 1, callback) }, 180L)
                else callback(null)
                return@evaluateJavascript
            }
            val title = o.optString("title", "").trim()
            val number = Regex("第([一二三四五六七八九十百千万零〇两\\d]+)章").find(title)?.groupValues?.getOrNull(1).orEmpty()
            val ch = Chapter(
                title, text,
                o.optString("next", "").takeIf { it.startsWith("http") },
                o.optString("prev", "").takeIf { it.startsWith("http") },
                url, o.optString("novel", "").ifBlank { "Qidian" }, number,
                o.optString("contentSel", ""), o.optString("titleSel", ""),
                "li[id^='chapter-item-'] > a", "li[id^='chapter-item-'] > a"
            )
            SiteProfiles.remember(webView.context, ch)
            callback(ch)
        }
    }

    private fun decode(raw: String?): String = try {
        if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0)
    } catch (_: Exception) { "" }
}
