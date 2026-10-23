package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class WtrLabExtractor {
    fun extract(webView: WebView, attempt: Int = 0, callback: (Chapter?) -> Unit) {
        val url = webView.url.orEmpty()
        if (url.isBlank()) { callback(null); return }

        val script = """
(function(){
  try {
    function txt(e){return String((e&&(e.innerText||e.textContent))||'').replace(/\u00a0/g,' ').trim();}
    function visible(e){
      if(!e)return false;
      var r=e.getBoundingClientRect(),s=getComputedStyle(e);
      return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';
    }
    function score(e,priority){
      if(!e||!visible(e))return -1;
      var t=txt(e);
      if(t.length<40)return -1;
      // Exclude page chrome and containers that clearly contain navigation.
      var lower=(' '+String(e.className||'')+' '+String(e.id||'')).toLowerCase();
      if(/header|footer|nav|comment|recommend|sidebar|advert/.test(lower))return -1;
      var pCount=0;
      try{pCount=e.querySelectorAll('p,.wtr-line,[data-line],br').length;}catch(x){}
      var s=priority*100000+Math.min(t.length,30000)+Math.min(pCount,100)*100;
      // Penalize generic page wrappers whose text includes lots of controls.
      if(e===document.body||e===document.documentElement)s-=1000000;
      return s;
    }
    var candidates=[],seen=new Set();
    function add(e,p){
      if(!e||seen.has(e))return;
      seen.add(e);candidates.push({el:e,p:p});
    }
    // Prefer the currently active SPA chapter. URL is unchanged between chapters.
    add(document.querySelector('.chapter-tracker.active .chapter-body'),120);
    add(document.querySelector('.chapter-tracker.active #chapter-content'),118);
    add(document.querySelector('.chapter-tracker.active'),110);
    add(document.querySelector('.chapter-tracker[data-active="true"] .chapter-body'),108);
    add(document.querySelector('#chapter-content'),100);
    add(document.querySelector('.chapter-body'),90);
    add(document.querySelector('.chapter-content'),80);
    add(document.querySelector('.reader-content'),70);
    add(document.querySelector('.reading-content'),65);
    add(document.querySelector('[class*="chapter-content"]'),60);
    add(document.querySelector('[id*="chapter-content"]'),55);

    // Fallback: inspect visible, text-heavy containers; this handles WTR DOM
    // variants where the reader has no stable #chapter-content selector.
    try{
      document.querySelectorAll('article,main,section,div').forEach(function(e){
        if(!visible(e))return;
        var t=txt(e);
        if(t.length>=120)add(e,Math.min(50,Math.floor(t.length/500)));
      });
    }catch(x){}

    var best=null,bestScore=-1;
    for(var i=0;i<candidates.length;i++){
      var c=candidates[i],s=score(c.el,c.p);
      if(s>bestScore){best=c.el;bestScore=s;}
    }
    if(!best)return JSON.stringify({ok:false,text:'',count:0,why:'no-visible-reader-root'});

    // If a selected wrapper contains nested chapter bodies, narrow to the
    // longest visible body so we don't accidentally extract copyright/header.
    var nested=[];
    try{
      best.querySelectorAll('.chapter-body,#chapter-content,.chapter-content,.wtr-line').forEach(function(e){
        if(e!==best&&visible(e)&&txt(e).length>=120)nested.push(e);
      });
    }catch(x){}
    if(nested.length){
      nested.sort(function(a,b){return txt(b).length-txt(a).length;});
      if(txt(nested[0]).length>=Math.max(120,txt(best).length*0.35))best=nested[0];
    }

    // WTR-LAB lazily renders chapter bodies (esp. in the hidden prefetch view).
    // Until the ACTIVE chapter has real lines, "best" is only a page wrapper
    // (header + chapter list + footer) or another preloaded chapter. Never
    // return that as chapter text: fail so the caller retries.
    var actT=document.querySelector('.chapter-tracker.active');
    if(actT&&actT.querySelectorAll('.wtr-line,.chapter-body p').length<2){
      return JSON.stringify({ok:false,text:'',count:0,why:'active-chapter-not-rendered'});
    }
    var clone=best.cloneNode(true);
    try{clone.querySelectorAll('script,style,noscript,button,input,select,textarea,nav,.ad,.ads,.advertisement,.chapter-navigation,.next,.prev,[aria-hidden="true"]').forEach(function(e){e.remove();});}catch(x){}
    // WTR-LAB: the chapter is .chapter-body > div.wtr-line[data-line]. The
    // detached clone has no layout, so its innerText is plain textContent and
    // the lines get glued together. Read the LIVE .wtr-line blocks instead and
    // join them with \n, so [001],[002]... map 1:1 to the real DOM lines.
    var wl=[],lineRoot=best;
    if(actT){var ab=actT.querySelector('.chapter-body');if(ab)lineRoot=ab;}
    try{wl=[].slice.call(lineRoot.querySelectorAll(':scope > .wtr-line'));}catch(x){}
    if(wl.length<2){try{wl=[].slice.call(lineRoot.querySelectorAll('.wtr-line'));}catch(x){}}
    var wtrLines=wl.map(function(e){return txt(e).replace(/\s+/g,' ').trim();})
      .filter(function(v){return v.length>0;});
    var text=(wtrLines.length>=2?wtrLines.join('\n'):txt(clone))
      .replace(/\r/g,'')
      .replace(/[\t\u00a0]+/g,' ')
      .replace(/ *\n */g,'\n')
      .replace(/\n{3,}/g,'\n\n')
      .trim();
    var lines=text.split(/\n+/).filter(function(s){return s.trim().length>0;});
    var count=0;
    try{count=clone.querySelectorAll('p,.wtr-line,[data-line],li,br').length;}catch(x){}
    if(wtrLines.length>=2)count=wtrLines.length;
    if(count<1)count=lines.length;
    if(count<1&&text.length>=40)count=1;

    var heading='';
    try{
      var h=best.querySelector('h1,h2,h3,h4,[class*="title"]');
      if(h)heading=txt(h);
    }catch(x){}
    if(!heading)heading=lines[0]||'';

    var number='';
    try{
      // The active tracker knows its own chapter number; the page-level "Ch. x / y"
      // counter can belong to a different (preloaded / neighbouring) chapter.
      var tn=actT&&actT.getAttribute('data-chapter-no');
      if(tn&&/^\d+$/.test(tn))number=tn;
    }catch(x){}
    var counterNodes=[];
    try{counterNodes=Array.from(document.querySelectorAll('body *')).filter(function(e){return e.children.length===0&&visible(e)&&/\bCh\.?\s*\d+\s*\/\s*\d+/i.test(e.textContent||'');});}catch(x){}
    if(!number&&counterNodes.length){
      var cm=(counterNodes[0].textContent||'').match(/\bCh\.?\s*(\d+)\s*\/\s*(\d+)/i);
      if(cm)number=cm[1];
    }
    if(!number){
      var nm=heading.match(/^\s*#?\s*(\d+)\b/);
      if(nm)number=nm[1];
    }
    if(!number){
      var um=location.pathname.match(/\/chapter-(\d+)/i);
      if(um)number=um[1];
    }

    function findLink(dir){
      var els=Array.from(document.querySelectorAll('a[href],button,[role="button"]'));
      var re=dir==='next'?/^(next|next\s*›|next\s*»|›|»|→|next chapter)$/i:/^(prev|previous|‹|«|←|previous chapter|prev chapter)$/i;
      for(var j=0;j<els.length;j++){
        var el=els[j],label=String(el.innerText||el.textContent||el.getAttribute('aria-label')||el.getAttribute('title')||'').replace(/\s+/g,' ').trim();
        if(!label||!re.test(label)||!visible(el))continue;
        return {href:el.href||'',selector:''};
      }
      return {href:'',selector:''};
    }
    return JSON.stringify({
      ok:text.length>=40&&count>0,
      text:text,count:count,title:heading,number:number,
      novel:document.title||'wtr-lab.com',
      contentSel:best.id?'#'+best.id:('.'+String(best.className||'').trim().split(/\s+/)[0]),
      titleSel:'',
      next:findLink('next'),prev:findLink('prev')
    });
  }catch(e){return JSON.stringify({ok:false,text:'',count:0,why:String(e)});}
})()
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            val payload = decode(raw)
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            val body = parsed?.optString("text", "")?.trim().orEmpty()
            val count = parsed?.optInt("count", 0) ?: 0
            val ok = parsed?.optBoolean("ok", false) == true && body.length >= 40 && count > 0
            if (!ok) {
                if (attempt < 8) webView.postDelayed({ extract(webView, attempt + 1, callback) }, 250L)
                else callback(null)
                return@evaluateJavascript
            }

            val title = parsed?.optString("title", "")?.trim().orEmpty()
            val number = parsed?.optString("number", "")?.trim().orEmpty()
            val next = parsed?.optJSONObject("next")?.optString("href", "")?.takeIf { it.isNotBlank() }
                ?: WtrLabNavigator().nextFromUrl(url)
            val prev = parsed?.optJSONObject("prev")?.optString("href", "")?.takeIf { it.isNotBlank() }
                ?: WtrLabNavigator().prevFromUrl(url)

            val chapter = Chapter(
                title.ifBlank { "Chapter ${number.ifBlank { "?" }}" },
                body,
                next,
                prev,
                url,
                parsed?.optString("novel", "")?.trim().orEmpty().ifBlank { "wtr-lab.com" },
                number,
                parsed?.optString("contentSel", "#chapter-content").orEmpty().ifBlank { "#chapter-content" },
                parsed?.optString("titleSel", "").orEmpty(),
                parsed?.optJSONObject("next")?.optString("selector", "").orEmpty(),
                parsed?.optJSONObject("prev")?.optString("selector", "").orEmpty()
            )
            SiteProfiles.remember(webView.context, chapter)
            callback(chapter)
        }
    }

    private fun decode(raw: String?): String = try {
        if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0)
    } catch (_: Exception) { "" }
}
