package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * WTR-LAB writer.
 *
 * Important: do not replace #chapter-content with a newly-created list of
 * <p> tags. WTR already has the correct paragraph DOM. We only replace the
 * text inside the existing blocks, so the Bengali stays exactly where the
 * English paragraph was. We intentionally do NOT try to reproduce inline
 * <b>/<i>/<span> formatting because that mapping is not reliable.
 */
internal class WtrLabWriter {
    fun write(
        webView: WebView,
        chapter: Chapter,
        translatedText: String,
        fontFile: String,
        fontSizePx: Int,
        pageKey: String,
        callback: (Boolean) -> Unit
    ) {
        val source = splitSource(chapter.text)
        if (source.isEmpty()) {
            callback(false)
            return
        }

        val js = """
(function(source,translation,font,size,pageId){
  function norm(s){
    return String(s||'')
      .replace(/\u00a0/g,' ')
      .replace(/\s+/g,' ')
      .trim();
  }
  function visible(e){
    if(!e)return false;
    var r=e.getBoundingClientRect(),s=getComputedStyle(e);
    return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';
  }

  var body=null;
  var selectors=[
    '#chapter-content',
    '.chapter-tracker.active .chapter-body',
    '.chapter-body',
    '.chapter-content'
  ];
  for(var i=0;i<selectors.length;i++){
    try{
      var e=document.querySelector(selectors[i]);
      if(e&&visible(e)&&norm(e.innerText||e.textContent).length>=40){
        body=e;break;
      }
    }catch(x){}
  }
  if(!body)return 'no-body';

  // Parse only the numbered translation blocks. Never silently collapse the
  // whole answer into one paragraph.
  var map={};
  var re=/\[(\d{3,})\]\s*/g, m, starts=[];
  while((m=re.exec(String(translation||'')))!==null){
    starts.push({id:m[1],start:m.index,end:re.lastIndex});
  }
  for(var mi=0;mi<starts.length;mi++){
    var a=starts[mi], b=starts[mi+1];
    var val=String(translation||'').slice(a.end,b?b.start:String(translation||'').length).trim();
    if(val)map[a.id]=val;
  }
  var ids=Object.keys(map);
  if(!ids.length)return 'no-markers';

  // Existing WTR blocks in DOM order. We do not create replacement <p>s.
  // WTR-LAB's real structure: .chapter-body > div.wtr-line[data-line]. There
  // are NO <p> tags, so the .wtr-line blocks must be tried first.
  function nonEmpty(e){return norm(e.innerText||e.textContent).length>0;}
  var nodes=[].slice.call(body.querySelectorAll(':scope > .wtr-line')).filter(nonEmpty);

  if(nodes.length<2){
    nodes=[].slice.call(body.querySelectorAll(':scope > p,:scope > h1,:scope > h2,:scope > h3,:scope > h4,:scope > li,:scope > blockquote,:scope > pre'))
      .filter(nonEmpty);
  }

  if(nodes.length<2){
    nodes=[].slice.call(body.querySelectorAll('.wtr-line,p,h1,h2,h3,h4,li,blockquote,pre'))
      .filter(nonEmpty);
  }
  if(!nodes.length)return 'no-blocks';

  // Exact source-text alignment first. This is the critical part: marker [001]
  // must land in the same DOM block from which source [001] was extracted.
  var used=new Set(), pairs=[];
  for(var si=0;si<source.length;si++){
    var id=String(si+1).padStart(3,'0');
    if(map[id]===undefined)continue;
    var src=norm(source[si]), found=null;
    for(var ni=0;ni<nodes.length;ni++){
      if(used.has(nodes[ni]))continue;
      if(norm(nodes[ni].innerText||nodes[ni].textContent)===src){
        found=nodes[ni];break;
      }
    }
    if(found){
      used.add(found);
      pairs.push({id:id,el:found,val:map[id]});
    }
  }

  // If whitespace/spans changed the exact text, use DOM order only when the
  // number of source blocks and DOM blocks is consistent. Never guess on a
  // partial mapping.
  if(pairs.length<ids.length){
    if(source.length!==nodes.length || ids.length!==source.length)
      return 'map-mismatch:'+pairs.length+':'+ids.length+':'+source.length+':'+nodes.length;
    pairs=[];
    for(var oi=0;oi<source.length;oi++){
      var oid=String(oi+1).padStart(3,'0');
      if(map[oid]===undefined)return 'map-mismatch-marker';
      pairs.push({id:oid,el:nodes[oi],val:map[oid]});
    }
  }

  if(pairs.length!==ids.length || pairs.length!==source.length)
    return 'map-mismatch:'+pairs.length+':'+ids.length+':'+source.length+':'+nodes.length;

  var old=body.innerHTML;
  pairs.forEach(function(p){
    // Deliberately textContent: no fake/inaccurate bold/italic reconstruction.
    p.el.textContent=p.val;
    p.el.style.setProperty('font-size',(size||18)+'px','important');
    p.el.style.setProperty('line-height','1.75','important');
    p.el.style.setProperty('word-break','normal','important');
    p.el.style.setProperty('overflow-wrap','break-word','important');
  });

  if(font){
    try{
      var st=document.getElementById('__ns_wtr_font');
      if(!st){
        st=document.createElement('style');
        st.id='__ns_wtr_font';
        (document.head||document.documentElement).appendChild(st);
      }
      st.textContent='.ns-wtr-active,.ns-wtr-active *{font-family:NSBn,sans-serif!important;}';
      pairs.forEach(function(p){p.el.classList.add('ns-wtr-active');});
    }catch(e){}
  }

  body.setAttribute('data-ns','1');
  body.setAttribute('data-ns-page-id',pageId||'');
  window.__nsEl=body;
  window.__nsOrig=old;
  window.__nsTr=body.innerHTML;
  window.__nsShown=1;
  window.__nsPageId=pageId||'';
  return 'ok:mapped:'+pairs.length;
})(__SOURCE__,__TRANSLATION__,__FONT__,__SIZE__,__PAGE_ID__)
""".trimIndent()
            .replace("__SOURCE__", JSONArray(source).toString())
            .replace("__TRANSLATION__", JSONObject.quote(translatedText))
            .replace("__FONT__", JSONObject.quote(fontFile))
            .replace("__SIZE__", fontSizePx.toString())
            .replace("__PAGE_ID__", JSONObject.quote(pageKey))

        webView.evaluateJavascript(js) { result ->
            callback(result != null && result.contains("ok:mapped:"))
        }
    }

    private fun splitSource(text: String): List<String> {
        var blocks = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (blocks.size <= 2) {
            val lines = text.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.size >= 3) blocks = lines
        }

        if (blocks.size == 1 && blocks[0].length > 800) {
            blocks = chunkBySentences(blocks[0])
        }
        return blocks
    }

    private fun chunkBySentences(text: String, target: Int = 280): List<String> {
        val ends = "。！？!?…」』”।"
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (sb.length >= target && ends.indexOf(c) >= 0) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }
}
