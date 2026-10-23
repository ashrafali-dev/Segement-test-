package com.ashraf.novelstudio

import android.net.Uri
import android.webkit.WebView
import org.json.JSONArray

internal class QidianNavigator {
    fun matches(url: String?): Boolean {
        val host = runCatching { Uri.parse(url.orEmpty()).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "qidian.com" || host.endsWith(".qidian.com")
    }

    fun catalogUrl(chapterUrl: String): String? = chapterUrl.takeIf { matches(it) && it.contains("/chapter/") }
    fun prefetchTarget(chapterUrl: String): String? = null

    fun catalogPickScript(direction: String, sourceUrl: String): String {
        val delta = if (direction == "prev") -1 else 1
        return """
(function(){
  function norm(u){
    try { var x=new URL(u,location.href); return x.origin+x.pathname.replace(/\/+$/,''); }
    catch(e){ return String(u||'').replace(/[?#].*$/,'').replace(/\/+$/,''); }
  }
  var source=norm(${org.json.JSONObject.quote(sourceUrl)});
  var links=Array.from(document.querySelectorAll('li[id^="chapter-item-"] > a[href*="/chapter/"]'));
  var items=[],seen={};
  links.forEach(function(a){var href=norm(a.href);if(href&&!seen[href]){seen[href]=1;items.push(href);}});
  var index=items.indexOf(source);
  if(index<0){
    var m=source.match(/\/chapter\/(\d+)\/(\d+)/);
    if(m) index=items.findIndex(function(h){return new RegExp('/chapter/'+m[1]+'/'+m[2]+'$').test(h);});
  }
  var at=index+($delta);
  if(index<0 || at<0 || at>=items.length)return 'edge';
  return items[at];
})()
        """.trimIndent()
    }

    fun pick(webView: WebView, direction: String, sourceUrl: String, callback: (String?) -> Unit) {
        webView.evaluateJavascript(catalogPickScript(direction, sourceUrl)) { raw ->
            val value = try { if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0) } catch (_: Exception) { "" }
            callback(value.takeIf { it.isNotBlank() })
        }
    }
}
