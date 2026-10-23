package com.ashraf.novelstudio

import android.net.Uri
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class Novel543Navigator {
    fun matches(url: String?): Boolean {
        val host = runCatching { Uri.parse(url.orEmpty()).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "novel543.com" || host.endsWith(".novel543.com")
    }
    fun catalogUrl(chapterUrl: String): String? {
        if (!matches(chapterUrl)) return null
        val uri = Uri.parse(chapterUrl)
        val bookId = uri.pathSegments.firstOrNull()?.takeIf { it.matches(Regex("\\d+")) } ?: return null
        return "${uri.scheme ?: "https"}://${uri.host}/$bookId/dir"
    }
    fun prefetchTarget(chapterUrl: String): String? = null
    fun adjacentScript(direction: String): String {
        val wanted = if (direction == "prev") "上一章" else "下一章"
        return """
(function(){
  var wanted=${JSONObject.quote(wanted)};
  var links=Array.from(document.querySelectorAll('.foot-nav a[href]'));
  var a=links.find(function(x){return (x.innerText||x.textContent||'').trim().indexOf(wanted)>=0;});
  if(!a)return 'edge';
  try {return new URL(a.href,location.href).href;} catch(e){return a.href||'edge';}
})()
        """.trimIndent()
    }
    fun catalogPickScript(direction: String, title: String, sourceUrl: String): String {
        val delta = if (direction == "prev") -1 else 1
        return """
(function(){
  function norm(u){try{var x=new URL(u,location.href);return x.origin+x.pathname.replace(/\/+$/,'');}catch(e){return '';}}
  var source=${JSONObject.quote(sourceUrl)};
  var links=Array.from(document.querySelectorAll('a[href]')).filter(function(a){
    try {var p=new URL(a.href,location.href).pathname;return /\.html?$/i.test(p)&&/\/\d+\//.test(p);}catch(e){return false;}
  });
  var items=[],seen={}; links.forEach(function(a){var h=norm(a.href);if(h&&!seen[h]){seen[h]=1;items.push(h);}});
  var i=items.indexOf(source), n=i+($delta);
  if(i<0||n<0||n>=items.length)return 'edge'; return items[n];
})()
        """.trimIndent()
    }
    fun pickAdjacent(webView: WebView, direction: String, callback: (String?) -> Unit) {
        webView.evaluateJavascript(adjacentScript(direction)) { raw ->
            val value = try { if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0) } catch (_: Exception) { "" }
            callback(value.takeIf { it.isNotBlank() })
        }
    }
}
