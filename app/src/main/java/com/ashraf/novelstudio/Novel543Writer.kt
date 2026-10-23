package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** Keep Novel543's original h1 element and its exact position. */
internal class Novel543Writer {
    fun write(webView: WebView, chapter: Chapter, translatedText: String, fontFile: String, fontSizePx: Int, pageKey: String, callback: (Boolean) -> Unit) {
        val paras = split(translatedText)
        val script = """
(function(paras,font,size,pageId){
  var root=document.querySelector('#chapterWarp .chapter-content, .chapter-content');
  if(!root)return 'no-root';
  var title=root.querySelector(':scope > h1'), body=root.querySelector(':scope > .content');
  if(!title||!body)return 'no-title-or-body';
  var lines=Array.from(body.querySelectorAll(':scope > p')).filter(function(p){return String(p.innerText||p.textContent||'').replace(/\u00a0/g,' ').trim().length>0;});
  var offset=paras.length===lines.length+1?1:0;
  if(paras.length-offset!==lines.length)return 'line-mismatch:'+paras.length+':'+lines.length;
  if(offset===1){title.textContent=String(paras[0]||'');title.style.setProperty('word-break','normal','important');title.style.setProperty('overflow-wrap','break-word','important');if(font)title.style.setProperty('font-family',font,'important');}
  lines.forEach(function(p,i){p.textContent=String(paras[i+offset]||'');p.style.setProperty('font-size',(size||18)+'px','important');p.style.setProperty('line-height','1.8','important');p.style.setProperty('word-break','normal','important');p.style.setProperty('overflow-wrap','break-word','important');if(font)p.style.setProperty('font-family',font,'important');});
  root.setAttribute('data-ns','1');root.setAttribute('data-ns-page-id',pageId||'');
  window.__nsEl=root;window.__nsOrig=null;window.__nsShown=1;window.__nsPageId=pageId||'';
  return 'ok';
})(__PARAS__,__FONT__,__SIZE__,__PAGE_ID__)
        """.trimIndent()
            .replace("__PARAS__", JSONArray(paras).toString())
            .replace("__FONT__", JSONObject.quote(fontFile))
            .replace("__SIZE__", fontSizePx.toString())
            .replace("__PAGE_ID__", JSONObject.quote(pageKey))
        webView.evaluateJavascript(script) { raw -> callback(raw != null && raw.contains("ok")) }
    }
    private fun split(text: String): List<String> {
        val marker = Regex("\\[\\d{3,}\\]\\s*")
        val marked = marker.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (marked.size > 1) return marked
        return text.split(Regex("\\n\\s*\\n|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
