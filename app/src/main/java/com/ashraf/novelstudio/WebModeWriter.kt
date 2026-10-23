package com.ashraf.novelstudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shows a translation on an IMAGE chapter. There is no text element to replace on such pages, so the
 * translation is drawn on top of the page. Two ways, both plugged into the existing
 * "View original / View translation" pill (Js.TOGGLE): the toggle empties/refills the wrapper, and the
 * wrapper lets touches through while empty, so the original images become usable again.
 *
 *  - [bubbleScript]: each translated paragraph goes back onto the spot where its original text was
 *    (same position, rotation and text size, over the cleaned bubble). The page stays scrollable.
 *  - [script]: fallback reader panel, used when the translation cannot be matched to the bubbles.
 */
internal object WebModeWriter {
    /** Height (CSS px) of the dock that floats over the page; the in-page OCR tools button sits above it. */
    @Volatile var bottomInsetPx: Int = 0

    private const val BODY = """
(function(paras,title,font,size,pid){
  try{
    var old=document.getElementById('__ns_wm_overlay');
    var w=document.createElement('div'); w.id='__ns_wm_overlay';
    w.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;pointer-events:none;';
    // Match the page the text came from: its own background, text colour, line height and column width,
    // so the reader view looks like part of the site (falls back to a dark theme when nothing usable is found).
    var ps={bg:'#101218',fg:'#e8eaf0',head:'#ffffff',fam:'',lh:'1.8',w:0};
    try{
      var rgb=function(c){var m=/rgba?\(([^)]+)\)/.exec(c||'');if(!m)return null;var q=m[1].split(',').map(function(x){return parseFloat(x);});return {r:q[0],g:q[1],b:q[2],a:q.length>3?q[3]:1};};
      var solid=function(el){while(el&&el.nodeType===1){var c=rgb(getComputedStyle(el).backgroundColor);if(c&&c.a>0.6)return c;el=el.parentElement;}return null;};
      var lum=function(c){return (0.299*c.r+0.587*c.g+0.114*c.b)/255;};
      var best=null,bl=0,pp=document.querySelectorAll('p');
      for(var pi=0;pi<pp.length&&pi<400;pi++){var pe=pp[pi];if(pe.closest&&pe.closest('#__ns_wm_overlay'))continue;var pn=(pe.innerText||'').length;if(pn>bl){bl=pn;best=pe;}}
      var hostEl=best||document.body,cs=getComputedStyle(hostEl);
      var pbg=solid(hostEl)||solid(document.body)||solid(document.documentElement),pfg=rgb(cs.color);
      if(pbg&&pfg&&Math.abs(lum(pbg)-lum(pfg))>0.35){ps.bg='rgb('+pbg.r+','+pbg.g+','+pbg.b+')';ps.fg='rgb('+pfg.r+','+pfg.g+','+pfg.b+')';ps.head=ps.fg;}
      else if(pbg){var dk=lum(pbg)<0.5;ps.bg='rgb('+pbg.r+','+pbg.g+','+pbg.b+')';ps.fg=dk?'#e8eaf0':'#1b1d22';ps.head=dk?'#ffffff':'#000000';}
      var lhv=parseFloat(cs.lineHeight),fsv=parseFloat(cs.fontSize);
      if(lhv>0&&fsv>0){var ratio=lhv/fsv;if(ratio>=1.3&&ratio<=2.4)ps.lh=String(ratio);}
      var bw=best?best.getBoundingClientRect().width:0;if(bw>200&&bw<(window.innerWidth||0)-8)ps.w=Math.round(bw);
      ps.fam=cs.fontFamily||'';
    }catch(e){}
    var box=document.createElement('div');
    box.style.cssText='position:absolute;left:0;top:0;right:0;bottom:0;overflow-y:auto;-webkit-overflow-scrolling:touch;'
      +'pointer-events:auto;background:'+ps.bg+';color:'+ps.fg+';padding:18px 16px 90px;box-sizing:border-box;line-height:'+ps.lh+';'
      +'font-size:'+size+'px;'+(font?"font-family:'NSBn',sans-serif;":'font-family:'+(ps.fam||'sans-serif')+';');
    var col=document.createElement('div');
    col.style.cssText='margin:0 auto;'+(ps.w?'max-width:'+ps.w+'px;':'');
    box.appendChild(col);
    if(title){var h=document.createElement('div');h.textContent=title;
      h.style.cssText='font-weight:700;font-size:'+(size+3)+'px;margin:0 0 16px;color:'+ps.head+';';col.appendChild(h);}
    for(var i=0;i<paras.length;i++){var t=(paras[i]||'').trim();if(!t)continue;
      var p=document.createElement('p');p.textContent=t;p.style.cssText='margin:0 0 '+Math.round(size*0.9)+'px;';col.appendChild(p);}
    w.appendChild(box);
    if(old&&old.parentNode)old.parentNode.replaceChild(w,old); else (document.body||document.documentElement).appendChild(w);
    window.__nsEl=w; window.__nsOrig=''; window.__nsTr=w.innerHTML; window.__nsShown=1;
    w.setAttribute('data-ns','1'); w.setAttribute('data-ns-page-id',pid||''); window.__nsPageId=pid||'';
    return 'ok';
  }catch(e){return 'err:'+e;}
})(__PARAS__,__TITLE__,__FONT__,__SIZE__,__PID__)
"""

    fun script(paras: List<String>, title: String, font: String, sizePx: Int, pageId: String): String =
        BODY.replace("__PARAS__", JSONArray(paras).toString())
            .replace("__TITLE__", JSONObject.quote(title))
            .replace("__FONT__", JSONObject.quote(font))
            .replace("__SIZE__", sizePx.toString())
            .replace("__PID__", JSONObject.quote(pageId))

    private const val BUBBLES = """
(function(items,sources,font,pid){
  try{
    if(window.__nsWmStop){try{window.__nsWmStop();}catch(e){}}
    // Keep the previous overlay visible while the replacement is constructed. Removing it here
    // exposes the untranslated image for a frame and causes a noticeable flash during realtime.
    var old=document.getElementById('__ns_wm_overlay');
    var de=document.scrollingElement||document.documentElement;
    // ABS: the page itself scrolls, so the bubbles live INSIDE the page (position:absolute) and the
    // browser scrolls them together with the images on its own thread: no lag, no shaking.
    // FIXED (only when an inner element scrolls): bubbles follow the images from script.
    var CSS_ABS='position:absolute;left:0;top:0;width:0;height:0;overflow:visible;z-index:2147483647;pointer-events:none;';
    var CSS_FIX='position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;pointer-events:none;overflow:hidden;';
    var ABS=(de.scrollHeight||0)>(window.innerHeight||0)+80;
    var w=document.createElement('div'); w.id='__ns_wm_overlay';
    w.style.cssText=ABS?CSS_ABS:CSS_FIX;
    // Cleaned images are drawn below translation bubbles without mutating source <img> elements.
    var cleanLayer=document.createElement('div'); cleanLayer.id='__ns_wm_clean_layer';
    cleanLayer.style.cssText='position:absolute;left:0;top:0;width:0;height:0;overflow:visible;pointer-events:none;z-index:0;';
    w.appendChild(cleanLayer);
    var cleanImgs=Object.create(null);
    function ensureCleanImage(im,s,url,r,o0){
      if(!url||!im||!r||r.width<8||r.height<8)return;
      var key=norm(s),entry=cleanImgs[key];
      if(!entry){
        var ci=document.createElement('img');ci.alt='';ci.draggable=false;
        ci.style.cssText='position:absolute;left:0;top:0;display:block;max-width:none;max-height:none;'
          +'object-fit:fill;pointer-events:none;user-select:none;margin:0;padding:0;border:0;z-index:0;';
        cleanLayer.appendChild(ci);entry=cleanImgs[key]={el:ci,url:''};
      }
      if(entry.url!==url){entry.url=url;entry.el.src=url;}
      var x=ABS?(r.left-o0.left):r.left,y=ABS?(r.top-o0.top):r.top;
      entry.el.style.width=r.width+'px';entry.el.style.height=r.height+'px';
      entry.el.style.transform='translate3d('+x+'px,'+y+'px,0)';
    }
    var fam=font?"'NSBn',sans-serif":'sans-serif';
    for(var i=0;i<items.length;i++){
      var it=items[i];
      var b=document.createElement('div'); b.className='__ns_b';
      b.setAttribute('data-s',it.s);
      b.setAttribute('data-cl',it.cl||'');
      b.setAttribute('data-bg',it.bg);
      b.setAttribute('data-sf',it.sf?'1':'0');
      b.setAttribute('data-g',[it.cx,it.cy,it.w,it.h,it.a,it.f].join(','));
      b.style.cssText='position:absolute;left:0;top:0;display:none;box-sizing:border-box;overflow:visible;'
        +'align-items:center;justify-content:center;padding:3px;pointer-events:none;will-change:transform;'
        // If the underlying source image was cleaned, do not put an opaque box over it:
        // untouched nearby lettering/line art stays visible instead of disappearing under the translation.
        +'background:'+(it.cl?'transparent':it.bg)+';color:'+it.fg+';font-family:'+fam+';font-weight:600;'
        +(it.cl?'border-radius:0;box-shadow:none;':(it.sf?'border-radius:12px;box-shadow:0 0 7px 5px '+it.bg+';':'border-radius:4px;box-shadow:0 0 0 3px '+it.bg+';'));
      var t=document.createElement('div'); t.textContent=it.t;
      // Preserve Bengali grapheme clusters/ conjuncts and use a shaping-friendly text layout.
      // OCR bubble bounds can be slightly smaller than the speech bubble, so fit() below
      // measures both axes and shrinks the text instead of letting it spill outside.
      t.style.cssText='display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;'
        +'text-align:center;line-height:1.25;white-space:normal;word-break:normal;text-wrap:balance;'
        // Bengali vowel signs (ি ী ু ৃ ে ৈ) stick out above and below the line box; room for them
        // stops them being clipped by the box edge or by the gradient's background-clip.
        +'padding:0.16em 0.1em;'
        +'overflow-wrap:normal;hyphens:none;font-kerning:normal;'
        // Keep browser defaults for complex-script shaping (including Bengali conjuncts).
        +'font-variant-ligatures:normal;text-rendering:optimizeLegibility;';
      b.style.setProperty('font-family',fam,'important'); t.style.setProperty('font-family',fam,'important');
      if(it.o){
        // outlined original lettering (fill + stroke): fill colour plus a round outline of the stroke colour
        var sh=[];
        for(var k=0;k<16;k++){var an=k*Math.PI/8,rr=(k%2?0.03:0.05);sh.push((Math.cos(an)*rr).toFixed(3)+'em '+(Math.sin(an)*rr).toFixed(3)+'em 0 '+it.o);}
        t.style.textShadow=sh.join(',');
        t.style.fontWeight='700';
      }else if(it.g2&&it.g2!==it.fg){
        // the original lettering was a gradient: keep it
        t.style.background='linear-gradient(to bottom,'+it.fg+','+it.g2+')';
        t.style.webkitBackgroundClip='text'; t.style.backgroundClip='text';
        t.style.webkitTextFillColor='transparent'; t.style.color='transparent';
      }
      b.appendChild(t); w.appendChild(b);
    }
    var allS=[],seenSources=Object.create(null);
    for(var si=0;si<(sources||[]).length;si++){
      var sourceUrl=norm(sources[si]);
      if(sourceUrl&&!seenSources[sourceUrl]){seenSources[sourceUrl]=1;allS.push(sourceUrl);}
    }
    for(var ai=0;ai<items.length;ai++){
      var as0=norm(items[ai].s);
      if(as0&&!seenSources[as0]){seenSources[as0]=1;allS.push(as0);}
    }
    // Source URLs whose path is shared by different query IDs are NOT interchangeable.
    // Remember ambiguity from this layout too, since virtualized readers may keep only one recycled
    // image in the DOM while several panels with the same base path are off-screen.
    var sourceByBase=Object.create(null),ambiguousSourceBase=Object.create(null);
    for(var si0=0;si0<allS.length;si0++){
      var source=norm(allS[si0]),sourceBase=base(source);
      if(sourceBase&&sourceByBase[sourceBase]&&sourceByBase[sourceBase]!==source)ambiguousSourceBase[sourceBase]=1;
      else if(sourceBase)sourceByBase[sourceBase]=source;
    }
    var LAZY=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-echo','data-actualsrc','data-orig-src','data-pc-src'];
    var cache={},miss={};
    function norm(u){return (u||'').split('#')[0];}
    function base(u){var x=norm(u),q=x.indexOf('?');return q>0?x.slice(0,q):x;}
    function srcsOf(im){
      var o=[],c=im.currentSrc||im.src; if(c)o.push(norm(c));
      // Include the declared src too: currentSrc may be a responsive srcset variant.
      var declared=im.getAttribute('src');
      if(declared){try{o.push(norm(new URL(declared,document.baseURI).href));}catch(e){}}
      for(var a=0;a<LAZY.length;a++){var v=im.getAttribute(LAZY[a]);
        if(v&&v.length>4){try{o.push(norm(new URL(v,document.baseURI).href));}catch(e){}}}
      var ss=im.getAttribute('data-srcset')||im.getAttribute('srcset');
      if(ss){
        // Keep all responsive candidates so a width/DPR change can still resolve to this same element.
        var variants=ss.split(',');
        for(var vi=0;vi<variants.length;vi++){
          var vu=(variants[vi].trim().split(/\s+/)[0]||'');
          if(!vu)continue;
          try{o.push(norm(new URL(vu,document.baseURI).href));}catch(e){}
        }
      }
      return o;}
    function buildImgIndex(){
      var exact=Object.create(null), byBase=Object.create(null), imgs=document.images;
      for(var i=0;i<imgs.length;i++){
        var im=imgs[i], keys=srcsOf(im);
        for(var k=0;k<keys.length;k++){
          var key=norm(keys[k]), bk=base(key);
          if(key&&!exact[key])exact[key]=im;
          if(bk){
            var prior=byBase[bk];
            if(!prior)byBase[bk]={el:im,ambiguous:false};
            else if(prior.el!==im)prior.ambiguous=true;
          }
        }
      }
      return {exact:exact,byBase:byBase};
    }
    // Canvas-backed manga readers (e.g. Shonen Jump+) have no <img> that the overlay can bind to.
    // OCR stores their visible canvas as ns-canvas:left,contentTop,widthxheight. Resolve that stable
    // geometry back to the live canvas, so the same fractional bubble coordinates can be overlaid.
    function canvasSpec(s){
      var m=/^ns-canvas:(-?\d+),(-?\d+),(\d+(?:\.\d+)?)x(\d+(?:\.\d+)?)$/.exec(norm(s));
      return m?{x:Number(m[1]),y:Number(m[2]),w:Number(m[3]),h:Number(m[4])}:null;
    }
    function canvasTops(cv,r){
      var out=[r.top,r.top+(window.pageYOffset||de.scrollTop||document.documentElement.scrollTop||0)];
      var p=cv.parentElement,guard=0;
      while(p&&p!==document.body&&p!==document.documentElement&&guard++<16){
        var extra=(p.scrollHeight||0)-(p.clientHeight||0);
        if(extra>80){
          var ov='';try{ov=getComputedStyle(p).overflowY||'';}catch(e){}
          if(ov==='auto'||ov==='scroll'||ov==='overlay'||p===de){
            var pr=p.getBoundingClientRect();out.push(r.top-pr.top+(p.scrollTop||0));
          }
        }
        p=p.parentElement;
      }
      return out;
    }
    function canvasScore(cv,spec){
      if(!cv||String(cv.tagName||'').toLowerCase()!=='canvas')return Infinity;
      var r;try{r=cv.getBoundingClientRect();}catch(e){return Infinity;}
      if(r.width<40||r.height<40)return Infinity;
      var cs;try{cs=getComputedStyle(cv);}catch(e){cs=null;}
      if(cs&&(cs.display==='none'||cs.visibility==='hidden'||parseFloat(cs.opacity||'1')<0.05))return Infinity;
      var dx=Math.abs(r.left-spec.x),dw=Math.abs(r.width-spec.w),dh=Math.abs(r.height-spec.h);
      if(dx>Math.max(24,spec.w*0.12)||dw>Math.max(48,spec.w*0.2)||dh>Math.max(48,spec.h*0.2))return Infinity;
      var tops=canvasTops(cv,r),dy=Infinity;
      for(var i=0;i<tops.length;i++)dy=Math.min(dy,Math.abs(tops[i]-spec.y));
      if(dy>Math.max(42,spec.h*0.1))return Infinity;
      var ratio=Math.abs((r.width/r.height)-(spec.w/spec.h));
      if(ratio>0.12)return Infinity;
      return dx+dy+dw+dh+ratio*100;
    }
    function findCanvas(s,spec){
      spec=spec||canvasSpec(s);if(!spec)return null;
      var list=document.querySelectorAll('canvas'),best=null,bestScore=Infinity;
      for(var i=0;i<list.length;i++){
        var score=canvasScore(list[i],spec);
        if(score<bestScore){bestScore=score;best=list[i];}
      }
      return best;
    }
    // A cached element owns a source only when its actual candidate URL matches exactly.
    // Stripping the query string here is unsafe: some readers encode panel identity in it.
    function owns(im,s){
      var k=srcsOf(im),ns=norm(s);
      for(var i=0;i<k.length;i++){if(k[i]===ns)return true;}
      return false;
    }
    function findImg(s, index){
      var spec=canvasSpec(s);
      if(spec){
        // Canvas identities describe a location, not an image URL; revalidate it every pass because
        // manga readers can recycle canvas nodes while scrolling or switching pages.
        var cachedCanvas=cache[s];
        if(cachedCanvas&&document.contains(cachedCanvas)&&canvasScore(cachedCanvas,spec)<Infinity)return cachedCanvas;
        var canvas=findCanvas(s,spec);
        if(canvas){cache[s]=canvas;delete miss[s];return canvas;}
        delete cache[s];miss[s]=Date.now();return null;
      }
      var c=cache[s];
      if(c&&document.contains(c)){
        if(owns(c,s))return c;
        // Virtual readers recycle <img> nodes for other panels: a cached node that no longer shows s
        // would put this text on the wrong picture. Look for the real owner; if there is none
        // (e.g. the page swapped to a blob: URL) keep the old node exactly as before.
        delete cache[s];
        var moved=lookupImg(s,index);
        if(moved)return moved;
        // This node may now show a different panel or query-ID variant. If it has any identifiable
        // source but no longer owns s, hide the bubble; never pin it to a recycled image.
        for(var q=0;q<allS.length;q++){if(allS[q]!==s&&owns(c,allS[q]))return null;}
        var keysNow=srcsOf(c),hasIdentity=false;
        for(var ki=0;ki<keysNow.length;ki++){if(norm(keysNow[ki])){hasIdentity=true;break;}}
        if(hasIdentity)return null;
        // Retain the old reference only if the page offers no usable source identity at all.
        cache[s]=c;
        return c;
      }
      return lookupImg(s,index);
    }
    function lookupImg(s, index){
      // A single index is built per layout pass when any source needs lookup. This avoids
      // scanning document.images once per bubble during scroll/re-render-heavy virtual readers.
      if(index){
        var exact=index.exact[norm(s)], near=index.byBase[base(s)];
        // A query-stripped fallback is safe only when that base path belongs to one DOM image.
        // If two panels share a path but differ by ?id=..., do not guess and paint text on the wrong panel.
        var match=exact||(near&&!near.ambiguous&&!ambiguousSourceBase[base(s)]?near.el:null);
        if(match){cache[s]=match;delete miss[s];return match;}
        miss[s]=Date.now(); return null;
      }
      var now=Date.now(); if(miss[s]&&now-miss[s]<1500)return null;
      // Usually an image is already cached; keep a fallback for callers before an index exists.
      var imgs=document.images;
      for(var i=0;i<imgs.length;i++){var l=srcsOf(imgs[i]);
        for(var k=0;k<l.length;k++){if(l[k]===norm(s)){cache[s]=imgs[i];return imgs[i];}}}
      var bs0=base(s),baseMatch=null,baseAmbiguous=!!ambiguousSourceBase[bs0];
      if(!baseAmbiguous){
        for(var i2=0;i2<imgs.length;i2++){var l2=srcsOf(imgs[i2]);
          for(var k2=0;k2<l2.length;k2++){
            if(base(l2[k2])!==bs0)continue;
            if(baseMatch&&baseMatch!==imgs[i2]){baseAmbiguous=true;break;}
            baseMatch=imgs[i2];
          }
          if(baseAmbiguous)break;
        }
      }
      if(baseMatch&&!baseAmbiguous){cache[s]=baseMatch;return baseMatch;}
      miss[s]=now; return null;
    }
    // Largest font (up to maxPx) at which the text still fits inside its box.
    function fit(b,maxPx){
      var t=b&&b.firstChild;
      if(!t)return;
      // The OCR rectangle is not always the full usable speech-balloon interior (curved
      // edges and connected/two-lobed bubbles are especially easy to overestimate).
      // Keep a safety inset so Bengali vowel signs and the last wrapped line remain inside.
      var boxW=b.clientWidth,boxH=b.clientHeight;
      if(boxW<8||boxH<8)return;
      var availW=Math.max(12,boxW*0.88);
      var availH=Math.max(12,boxH*0.86);
      t.style.width=availW+'px';
      t.style.maxWidth=availW+'px';
      t.style.minWidth='0';
      t.style.height='auto';
      t.style.maxHeight='none';
      t.style.boxSizing='border-box';
      t.style.margin='0 auto';
      // Start with the OCR-estimated cap, but never let an invalid estimate break fitting.
      var cap=Number(maxPx);
      if(!isFinite(cap)||cap<2)cap=4;
      var lo=1.5,hi=Math.max(lo,cap),best=lo;
      function fits(){
        // Read layout after changing font size; DOM metrics account for the loaded Bengali
        // font and actual line wrapping, unlike character-count estimates.
        var r=t.getBoundingClientRect();
        return r.height<=availH+0.5 &&
          t.scrollHeight<=availH+1 &&
          t.scrollWidth<=t.clientWidth+1 &&
          r.width<=availW+1;
      }
      // Binary-search the largest size that fits both axes, with a conservative inner box.
      for(var n=0;n<16;n++){
        var mid=(lo+hi)/2;
        t.style.fontSize=mid+'px';
        if(fits()){best=mid;lo=mid;}else{hi=mid;}
      }
      t.style.fontSize=best+'px';
      // Final post-layout guard for WebView/font shaping differences: if measured ink/line
      // metrics still exceed the safe area, reduce a little more rather than clipping text.
      for(var pass=0;pass<4;pass++){
        var r=t.getBoundingClientRect();
        var actualH=Math.max(r.height,t.scrollHeight);
        var actualW=Math.max(r.width,t.scrollWidth);
        var scale=Math.min(1,availH/Math.max(1,actualH),availW/Math.max(1,actualW));
        if(scale>=0.995)break;
        var current=parseFloat(t.style.fontSize)||best;
        var next=Math.max(1.5,current*scale*0.96);
        if(next>=current-0.02)break;
        t.style.fontSize=next+'px';
      }
      t.style.overflow='visible';
    }
    var MARGIN=1400;
    // Two bubbles whose boxes overlap partly (joined / burst bubbles): the later one used to paint its
    // background over the first one's text. Cut the overlap away so both stay fully readable.
    function resolve(L){
      for(var i=0;i<L.length;i++)for(var j=i+1;j<L.length;j++){
        var A=L[i],B=L[j];
        if(A.s!==B.s||A.rot||B.rot)continue;
        var ox=Math.min(A.x1,B.x1)-Math.max(A.x0,B.x0),oy=Math.min(A.y1,B.y1)-Math.max(A.y0,B.y0);
        if(ox<=2||oy<=2)continue;
        var aA=(A.x1-A.x0)*(A.y1-A.y0),aB=(B.x1-B.x0)*(B.y1-B.y0);
        if(ox*oy>0.6*Math.min(aA,aB)){
          if(Math.min(aA,aB)>=0.3*Math.max(aA,aB)){
            // Two comparable boxes on the same spot are two readings of one bubble (e.g. its two lines). Drawn on
            // top of each other they garble each other (the cleaned picture has no cover box), so stack them
            // top to bottom inside their union, sharing the height by text length.
            var tA=(A.b.textContent||''),tB=(B.b.textContent||'');
            var T=(A.y0+A.y1<=B.y0+B.y1)?A:B,Bo=(T===A)?B:A;
            if(tA===tB){Bo.x0=Bo.x1=Bo.y0=Bo.y1=-99999;continue;}   // exact duplicate: draw once
            var U0=Math.min(A.y0,B.y0),U1=Math.max(A.y1,B.y1),X0=Math.min(A.x0,B.x0),X1=Math.max(A.x1,B.x1);
            var lt=Math.max(1,(T.b.textContent||'').length),lb=Math.max(1,(Bo.b.textContent||'').length);
            var cut=U0+(U1-U0)*lt/(lt+lb);
            T.x0=Bo.x0=X0;T.x1=Bo.x1=X1;T.y0=U0;T.y1=cut-1;Bo.y0=cut+1;Bo.y1=U1;
            continue;
          }
          // a small note inside a much bigger box: the smaller one is drawn on top
          if(aA<=aB){A.z=2;B.z=1;}else{B.z=2;A.z=1;}
          continue;
        }
        var vOk=(A.y0<B.y0&&A.y1<B.y1)||(B.y0<A.y0&&B.y1<A.y1);
        var hOk=(A.x0<B.x0&&A.x1<B.x1)||(B.x0<A.x0&&B.x1<A.x1);
        var useV=vOk&&(!hOk||oy<=ox);
        if(!useV&&!hOk)continue;
        var P,Q;
        if(useV){P=(A.y0<B.y0)?A:B;}else{P=(A.x0<B.x0)?A:B;}
        Q=(P===A)?B:A;
        var k0=useV?'y0':'x0',k1=useV?'y1':'x1';
        var pA=(P.x1-P.x0)*(P.y1-P.y0),qA=(Q.x1-Q.x0)*(Q.y1-Q.y0);
        var cutP=pA>=qA;                                  // cut the bigger box: it has the room
        var gap=1.5;
        if(cutP){
          var n1=Q[k0]-gap,m1=Math.max(14,(useV?P.oh:P.ow)*0.4);
          if(n1-P[k0]>=m1){P[k1]=n1;continue;}
        }else{
          var n0=P[k1]+gap,m0=Math.max(14,(useV?Q.oh:Q.ow)*0.4);
          if(Q[k1]-n0>=m0){Q[k0]=n0;continue;}
        }
        // the bigger box would become too small: share the overlap between both
        var mid=(Q[k0]+P[k1])/2;
        P[k1]=Math.max(P[k0]+4,mid-gap);
        Q[k0]=Math.min(Q[k1]-4,mid+gap);
      }
    }
    function layout(){
      if(!document.contains(w)){
        // Another overlay replaced this one -> stop. Otherwise the page (a re-render, a lazy-load swap)
        // threw our overlay away: put it back instead of silently giving up.
        if(window.__nsEl!==w){stop();return;}
        if(window.__nsShown){try{(document.body||document.documentElement).appendChild(w);}catch(e){}}
        if(!document.contains(w))return;
      }
      var bs=w.querySelectorAll('.__ns_b'),vh=window.innerHeight;
      if(!ABS&&(de.scrollHeight||0)>(window.innerHeight||0)+80){
        // The overlay was built while the page was still short (images not loaded yet), so it chose the
        // script-driven FIXED mode, which trails behind the picture while scrolling. The page now scrolls
        // by itself: move the bubbles into the page so the browser scrolls them with the images.
        ABS=true; w.style.cssText=CSS_ABS;
        for(var u=0;u<bs.length;u++)bs[u].removeAttribute('data-k');
      }
      var o=ABS?w.getBoundingClientRect():null;
      var L=[],needsIndex=false,lookupNow=Date.now();
      // Most Webtoon images stay in the DOM, so cached source-to-image references are enough.
      // Only build the per-pass index when a cached image disappeared or an image has not yet
      // been seen (and its short miss-cache has expired).
      for(var pi=0;pi<bs.length;pi++){
        var ps=bs[pi].getAttribute('data-s'),pc=cache[ps];
        if(pc&&document.contains(pc))continue;
        if(miss[ps]&&lookupNow-miss[ps]<1500)continue;
        needsIndex=true;break;
      }
      var imgIndex=needsIndex?buildImgIndex():null;
      for(var i=0;i<bs.length;i++){
        var b=bs[i],im=findImg(b.getAttribute('data-s'),imgIndex);
        if(!im){if(b.style.display!=='none')b.style.display='none';continue;}
        var r=im.getBoundingClientRect();
        if(r.width<8){if(b.style.display!=='none')b.style.display='none';continue;}
        // Keep original DOM nodes intact so srcset, lazy loading and site click handlers keep working.
        var cleanUrl=b.getAttribute('data-cl');
        if(cleanUrl)ensureCleanImage(im,b.getAttribute('data-s'),cleanUrl,r,o);
        var g=b.getAttribute('data-g').split(',');
        var bw=g[2]*r.width,bh=g[3]*r.height;
        var vx=r.left+g[0]*r.width,vy=r.top+g[1]*r.height;       // centre in the viewport
        var near=!(vy+bh/2<-MARGIN||vy-bh/2>vh+MARGIN);
        if(!near){if(b.style.display!=='none')b.style.display='none';continue;}
        var px=ABS?(vx-o.left):vx,py=ABS?(vy-o.top):vy;          // centre in the overlay's own space
        L.push({b:b,s:b.getAttribute('data-s'),g:g,rw:r.width,ow:bw,oh:bh,
          x0:px-bw/2,x1:px+bw/2,y0:py-bh/2,y1:py+bh/2,rot:((g[4]*1)!==0),z:0});
      }
      resolve(L);
      for(var n=0;n<L.length;n++){
        var R=L[n],bb=R.b,gg=R.g;
        var cw=R.x1-R.x0,ch=R.y1-R.y0,cx=(R.x0+R.x1)/2,cy=(R.y0+R.y1)/2;
        var key=Math.round(cx)+','+Math.round(cy)+','+Math.round(cw)+','+Math.round(ch);
        var sz=Math.round(cw)+','+Math.round(ch);
        var szChanged=bb.getAttribute('data-sz')!==sz;
        if(bb.getAttribute('data-k')!==key){
          bb.setAttribute('data-k',key);
          bb.style.width=cw+'px'; bb.style.height=ch+'px';
          bb.style.transform='translate3d('+(cx-cw/2)+'px,'+(cy-ch/2)+'px,0)'+((gg[4]*1)?' rotate('+gg[4]+'deg)':'');
        }
        var zz=R.z?String(R.z):'';
        if(bb.style.zIndex!==zz)bb.style.zIndex=zz;
        if(bb.style.display!=='flex')bb.style.display='flex';
        var fw=String(Math.round(R.rw));
        if(szChanged||bb.getAttribute('data-fw')!==fw){bb.setAttribute('data-sz',sz);bb.setAttribute('data-fw',fw);fit(bb,Math.max(gg[5]*R.rw*1.5,bb.getAttribute('data-sf')==='1'?R.oh*0.8:0));}
      }}
    var raf=0,lastRun=0,forceNext=false,settleT=0,ro=null;
    function sched(){
      if(raf)return;
      // In ABS mode scrolling needs no work at all except showing bubbles that come near: 4x a second is plenty.
      raf=requestAnimationFrame(function(){raf=0;
        var n=Date.now(),f=forceNext; forceNext=false;
        if(ABS&&!f&&n-lastRun<250)return; lastRun=n;
        try{layout();}catch(e){}});}
    // Anything that moves the pictures (viewport resize, an image finishing its load, the page growing,
    // the end of a scroll) re-places the bubbles on the very next frame. Before, such an event that landed
    // within 250 ms of the last pass was simply dropped and the text sat on the old spot until the
    // next 500 ms tick.
    function schedNow(){forceNext=true;sched();}
    var iv=setInterval(function(){lastRun=0;sched();},500);
    function onScroll(){
      if(ABS){sched();clearTimeout(settleT);settleT=setTimeout(schedNow,160);}
      else {if(raf)return;raf=requestAnimationFrame(function(){raf=0;try{layout();}catch(e){}});}
    }
    function onImgLoad(e){var t=e&&e.target;if(t&&t.tagName==='IMG')schedNow();}
    function stop(){
      window.removeEventListener('scroll',onScroll,true);window.removeEventListener('resize',schedNow);
      window.removeEventListener('orientationchange',schedNow);window.removeEventListener('load',onImgLoad,true);
      clearInterval(iv);clearTimeout(settleT);
      if(ro){try{ro.disconnect();}catch(e){}ro=null;}
    }
    window.addEventListener('scroll',onScroll,{capture:true,passive:true}); window.addEventListener('resize',schedNow);
    window.addEventListener('orientationchange',schedNow); window.addEventListener('load',onImgLoad,true);
    try{
      if(window.ResizeObserver){
        ro=new ResizeObserver(schedNow);
        ro.observe(document.documentElement); if(document.body)ro.observe(document.body);
      }
    }catch(e){}
    window.__nsWmStop=stop;
    if(old&&old.parentNode)old.parentNode.replaceChild(w,old); else (document.body||document.documentElement).appendChild(w);
    window.__nsEl=w; window.__nsOrig=''; window.__nsTr=w.innerHTML; window.__nsShown=1;
    w.setAttribute('data-ns','1'); w.setAttribute('data-ns-page-id',pid||''); window.__nsPageId=pid||'';
    lastRun=0; layout();

    // Manual region OCR is available beside the translated overlay, including for a missed bubble.
    try{
      if(!window.__nsAreaSelectInit){
        window.__nsAreaSelectInit=1;
        window.__nsStartAreaSelect=function(){
          if(window.__nsAreaCancel){try{window.__nsAreaCancel();}catch(e){}}
          var ui=document.createElement('div');ui.id='__ns_area_select_ui';
          ui.style.cssText='position:fixed;inset:0;z-index:2147483647;pointer-events:none;';
          var box=document.createElement('div');box.id='__ns_area_select_box';
          box.style.cssText='position:fixed;box-sizing:border-box;border:2px solid #36d7ff;background:rgba(54,215,255,.16);'
            +'border-radius:3px;pointer-events:auto;touch-action:none;cursor:move;';
          var tag=document.createElement('div');tag.textContent='MOVE';
          tag.style.cssText='position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);padding:4px 7px;'
            +'border-radius:6px;background:rgba(10,24,38,.78);color:#fff;font:700 11px sans-serif;pointer-events:none;';
          box.appendChild(tag);
          var hint=document.createElement('div');hint.textContent='Move box · drag a corner to resize';
          hint.style.cssText='position:fixed;top:12px;left:8px;max-width:54vw;padding:9px 11px;border-radius:10px;'
            +'background:rgba(22,27,40,.96);color:#fff;font:600 12px sans-serif;box-shadow:0 2px 12px #0008;';
          var confirm=document.createElement('button');confirm.textContent='OCR + Translate';
          confirm.style.cssText='position:fixed;top:10px;right:76px;pointer-events:auto;border:0;border-radius:10px;'
            +'padding:10px 12px;background:#087f8c;color:#fff;font:700 13px sans-serif;box-shadow:0 2px 12px #0008;';
          var cancel=document.createElement('button');cancel.textContent='Cancel';
          cancel.style.cssText='position:fixed;top:10px;right:8px;pointer-events:auto;border:0;border-radius:10px;'
            +'padding:10px 11px;background:#30394b;color:#fff;font:600 13px sans-serif;';
          var btn=document.getElementById('__ns_area_btn');
          var lazy=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-echo','data-actualsrc','data-orig-src','data-pc-src'];
          function absUrl(src){try{return src?new URL(src,document.baseURI).href:'';}catch(e){return '';}}
          function imageSrc(im){
            if(!im)return '';
            var src=im.currentSrc||im.src||'';
            if(!src||/^data:image\/(gif|svg)/i.test(src)||/placeholder|blank\.|spacer|loading|1x1|transparen/i.test(src)){
              for(var i=0;i<lazy.length;i++){var v=im.getAttribute(lazy[i]);if(v&&v.length>4){src=v;break;}}
              if(!src||/^data:image\/(gif|svg)/i.test(src)){var ss=im.getAttribute('data-srcset')||im.getAttribute('srcset');if(ss)src=ss.split(',')[0].trim().split(/\s+/)[0];}
            }
            return absUrl(src);
          }
          function bgSrc(el){try{var bg=getComputedStyle(el).backgroundImage||'';var m=/url\((?:"|')?([^"')]+)(?:"|')?\)/.exec(bg);return m?absUrl(m[1]):'';}catch(e){return '';}}
          function visible(r){return r.width>=140&&r.height>=80&&r.right>0&&r.bottom>0&&r.left<innerWidth&&r.top<innerHeight;}
          var px=innerWidth*.5,py=innerHeight*.5,hit=document.elementFromPoint(px,py);
          var img=hit&&(hit.tagName==='IMG'?hit:(hit.closest?hit.closest('img'):null)),src=img?imageSrc(img):'';
          if(img&&src&&!visible(img.getBoundingClientRect())){img=null;src='';}
          var node=hit;
          while((!img||!src)&&node&&node!==document.body){
            var cs=bgSrc(node),nr=node.getBoundingClientRect();
            if(cs&&nr.width>=180&&nr.height>=100&&visible(nr)){img=node;src=cs;break;}
            node=node.parentElement;
          }
          if(!img||!src){
            var bestArea=0,imgs=document.images;
            for(var ii=0;ii<imgs.length;ii++){
              var cand=imgs[ii],cr=cand.getBoundingClientRect(),csrc=imageSrc(cand);if(!csrc||!visible(cr))continue;
              var area=Math.max(0,Math.min(cr.right,innerWidth)-Math.max(cr.left,0))*Math.max(0,Math.min(cr.bottom,innerHeight)-Math.max(cr.top,0));
              if(area>bestArea){bestArea=area;img=cand;src=csrc;}
            }
          }
          if(!img||!src){
            ui.appendChild(hint);ui.appendChild(cancel);(document.body||document.documentElement).appendChild(ui);
            hint.textContent='No visible chapter image found. Scroll to the panel and retry.';
            function noImageCleanup(){if(ui.parentNode)ui.parentNode.removeChild(ui);window.__nsAreaCancel=null;if(btn)btn.textContent='▧ OCR Area';}
            cancel.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();noImageCleanup();});
            window.__nsAreaCancel=noImageCleanup;if(btn)btn.textContent='✕ Cancel OCR Area';return;
          }
          var imRect=img.getBoundingClientRect(),clip={l:Math.max(0,imRect.left),t:Math.max(0,imRect.top),r:Math.min(innerWidth,imRect.right),b:Math.min(innerHeight,imRect.bottom)};
          if(clip.r-clip.l<32||clip.b-clip.t<32){if(btn)btn.textContent='▧ OCR Area';return;}
          var bw=Math.min(clip.r-clip.l-4,Math.max(56,(clip.r-clip.l)*.42));
          var bh=Math.min(clip.b-clip.t-4,Math.max(48,(clip.b-clip.t)*.24));
          var bx=(clip.l+clip.r-bw)/2,by=(clip.t+clip.b-bh)/2,drag=null,closed=false;
          ['nw','ne','sw','se'].forEach(function(pos){
            var h=document.createElement('div');h.className='__ns_area_handle';h.setAttribute('data-edge',pos);
            h.style.cssText='position:absolute;width:22px;height:22px;box-sizing:border-box;border:2px solid #fff;'
              +'border-radius:5px;background:#08a7bd;box-shadow:0 1px 5px #0009;pointer-events:auto;touch-action:none;z-index:2;';
            if(pos.indexOf('n')>=0)h.style.top='-11px';else h.style.bottom='-11px';
            if(pos.indexOf('w')>=0)h.style.left='-11px';else h.style.right='-11px';
            box.appendChild(h);
          });
          ui.appendChild(box);ui.appendChild(hint);ui.appendChild(confirm);ui.appendChild(cancel);(document.body||document.documentElement).appendChild(ui);
          function putBox(){box.style.left=bx+'px';box.style.top=by+'px';box.style.width=bw+'px';box.style.height=bh+'px';}
          function cleanup(){
            if(closed)return;closed=true;
            document.removeEventListener('pointerdown',onDown,true);document.removeEventListener('pointermove',onMove,true);
            document.removeEventListener('pointerup',onUp,true);document.removeEventListener('pointercancel',onCancel,true);
            document.removeEventListener('touchmove',blockScroll,true);
            if(ui.parentNode)ui.parentNode.removeChild(ui);window.__nsAreaCancel=null;if(btn)btn.textContent='▧ OCR Area';
          }
          function blockScroll(e){if(!closed&&e.cancelable)e.preventDefault();}
          function onCancel(){drag=null;cleanup();}
          function onDown(e){
            if(closed||e.target===confirm||e.target===cancel)return;
            var h=e.target&&e.target.closest?e.target.closest('[data-edge]'):null;
            if(!h&&!(e.target===box||box.contains(e.target)))return;
            drag={mode:h?'resize':'move',edge:h?h.getAttribute('data-edge'):'',x:e.clientX,y:e.clientY,bx:bx,by:by,bw:bw,bh:bh};
            e.preventDefault();e.stopImmediatePropagation();
          }
          function onMove(e){
            if(!drag||closed)return;var dx=e.clientX-drag.x,dy=e.clientY-drag.y;
            if(drag.mode==='move'){
              bx=Math.max(clip.l,Math.min(clip.r-bw,drag.bx+dx));by=Math.max(clip.t,Math.min(clip.b-bh,drag.by+dy));
            }else{
              var edge=drag.edge,minW=28,minH=28,left=drag.bx,right=drag.bx+drag.bw,top=drag.by,bottom=drag.by+drag.bh;
              if(edge.indexOf('w')>=0)left=Math.max(clip.l,Math.min(right-minW,drag.bx+dx));
              if(edge.indexOf('e')>=0)right=Math.min(clip.r,Math.max(left+minW,drag.bx+drag.bw+dx));
              if(edge.indexOf('n')>=0)top=Math.max(clip.t,Math.min(bottom-minH,drag.by+dy));
              if(edge.indexOf('s')>=0)bottom=Math.min(clip.b,Math.max(top+minH,drag.by+drag.bh+dy));
              bx=left;by=top;bw=right-left;bh=bottom-top;
            }
            putBox();e.preventDefault();e.stopImmediatePropagation();
          }
          function onUp(e){if(drag){drag=null;if(e){e.preventDefault();e.stopImmediatePropagation();}}}
          function runOcr(){
            var nx=Math.max(0,Math.min(.994,(bx-imRect.left)/imRect.width)),ny=Math.max(0,Math.min(.994,(by-imRect.top)/imRect.height));
            var nw=Math.max(.006,Math.min(1-nx,bw/imRect.width)),nh=Math.max(.006,Math.min(1-ny,bh/imRect.height)),target=src;
            cleanup();if(!target||bw<24||bh<24)return;
            try{window.NsRetr.forceOcrArea(target,nx,ny,nw,nh);}catch(err){}
          }
          confirm.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();runOcr();});
          cancel.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();cleanup();});
          document.addEventListener('pointerdown',onDown,true);document.addEventListener('pointermove',onMove,true);
          document.addEventListener('pointerup',onUp,true);document.addEventListener('pointercancel',onCancel,true);
          document.addEventListener('touchmove',blockScroll,{capture:true,passive:false});
          window.__nsAreaCancel=cleanup;if(btn)btn.textContent='✕ Cancel OCR Area';putBox();
        };
      }
      if(!document.getElementById('__ns_area_btn')){
        // Keep one discoverable icon on screen in real-time bubble mode; reveal actions on tap.
        var areaBtn=document.createElement('button');areaBtn.id='__ns_area_btn';areaBtn.type='button';
        areaBtn.textContent='▧';
        areaBtn.setAttribute('aria-label','OCR tools');
        areaBtn.title='OCR tools';
        areaBtn.style.cssText='position:fixed;right:10px;bottom:calc(80px + var(--ns-bi,0px));z-index:2147483647;pointer-events:auto;'
          +'width:44px;height:44px;padding:0;display:flex;align-items:center;justify-content:center;'
          +'border:1px solid #ffffff55;border-radius:14px;background:rgba(26,34,50,.94);color:#fff;'
          +'font:600 23px sans-serif;box-shadow:0 3px 12px #0007;-webkit-tap-highlight-color:transparent;';
        var ocrMenu=null;
        function hideOcrMenu(){if(ocrMenu&&ocrMenu.parentNode)ocrMenu.parentNode.removeChild(ocrMenu);ocrMenu=null;}
        function visibleImageSource(){
          var ww=window.innerWidth||360,hh=window.innerHeight||640,best='',bestArea=0;
          var LAZY=['data-src','data-lazy-src','data-original','data-original-src','data-bg','data-background','data-background-image','data-url'];
                    function good(s){s=String(s||'').trim();return !!s&&!/^data:image\/(gif|svg)/i.test(s)&&!/placeholder|blank\.|spacer|loading|1x1|transparen/i.test(s);}
          function abs(s){try{return s?new URL(String(s).trim(),document.baseURI).href:'';}catch(e){return '';}}
          function choose(el){
            var s=el.currentSrc||el.getAttribute('src')||el.src||'';
            if(!good(s)||((el.naturalWidth||0)>0&&(el.naturalWidth||0)<8)){
              for(var k=0;k<LAZY.length;k++){var v=el.getAttribute&&el.getAttribute(LAZY[k]);if(good(v)){s=v;break;}}
            }
            if(!good(s)){var ss=(el.getAttribute&&el.getAttribute('data-srcset'))||(el.getAttribute&&el.getAttribute('srcset'))||'';
              if(ss){var a=ss.split(',');s=(a[a.length-1]||'').trim().split(/\s+/)[0]||'';}}
            return abs(s);
          }
          function consider(src,r){
            src=abs(src);if(!src||r.width<60||r.height<45||r.right<=0||r.bottom<=0||r.left>=ww||r.top>=hh)return;
            var area=Math.max(0,Math.min(r.right,ww)-Math.max(r.left,0))
              *Math.max(0,Math.min(r.bottom,hh)-Math.max(r.top,0));
            if(area>bestArea){bestArea=area;best=src;}
          }
          var imgs=document.images||[];
          for(var i=0;i<imgs.length;i++){
            var im=imgs[i],r=im.getBoundingClientRect();
            if(r.width*r.height>=3500)consider(choose(im),r);
          }
          var svg=document.querySelectorAll('svg image');
          for(var si=0;si<svg.length;si++){var se=svg[si],sr=se.getBoundingClientRect();consider((se.href&&se.href.baseVal)||se.getAttribute('href')||se.getAttribute('xlink:href')||'',sr);}
          if(best)return best;
          // Manual-only fallback: CSS and lazy backgrounds are searched on tap, never every poll.
          var nodes=document.querySelectorAll('*'),limit=Math.min(nodes.length,12000);
          for(var j=0;j<limit;j++){
            var el=nodes[j],r=el.getBoundingClientRect();
            if(r.width<80||r.height<50||r.right<=0||r.bottom<=0||r.left>=ww||r.top>=hh)continue;
            var bg='';try{bg=getComputedStyle(el).backgroundImage||'';}catch(ignore){}
            var m=bg&&bg!=='none'?/url\((?:"|')?([^"')]+)(?:"|')?\)/.exec(bg):null;
            if(m&&good(m[1]))consider(m[1],r);
            if(!best&&typeof el.getAttribute==='function'){
              for(var a2=0;a2<LAZY.length;a2++){var val=el.getAttribute(LAZY[a2]);if(good(val)){consider(val,r);break;}}
            }
          }
          return best;
        }
        function showOcrMenu(){
          hideOcrMenu();
          var m=document.createElement('div');ocrMenu=m;m.id='__ns_ocr_menu';
          m.style.cssText='position:fixed;right:10px;bottom:calc(132px + var(--ns-bi,0px));z-index:2147483647;pointer-events:auto;'
            +'min-width:172px;padding:5px;border-radius:13px;background:rgba(26,31,46,.98);'
            +'border:1px solid rgba(255,255,255,.2);box-shadow:0 4px 18px #0008;'
            +'font:600 14px sans-serif;color:#fff;-webkit-user-select:none;user-select:none;';
          function item(label,action){
            var b=document.createElement('button');b.type='button';b.textContent=label;
            b.style.cssText='display:block;width:100%;box-sizing:border-box;margin:3px 0;padding:11px 10px;'
              +'text-align:left;border:0;border-radius:8px;background:#30394b;color:#fff;font:600 14px sans-serif;';
            b.addEventListener('click',function(ev){
              ev.preventDefault();ev.stopPropagation();hideOcrMenu();
              try{action();}catch(ignore){}
            });
            m.appendChild(b);
          }
          item('↻  Force OCR',function(){
            var src=visibleImageSource();
            if(!src){try{if(window.NsRetr&&window.NsRetr.forceOcrArea)window.NsRetr.forceOcrArea('',0,0,1,1);}catch(ignore){}return;}
            try{window.NsRetr.forceOcrArea(src,0,0,1,1);}catch(ignore){}
          });
          item('▧  OCR Area',function(){
            if(window.__nsStartAreaSelect)window.__nsStartAreaSelect();
          });
          (document.body||document.documentElement).appendChild(m);
          setTimeout(function(){if(ocrMenu===m)hideOcrMenu();},7000);
        }
        areaBtn.addEventListener('click',function(e){
          e.preventDefault();e.stopPropagation();
          if(window.__nsAreaCancel)window.__nsAreaCancel();
          showOcrMenu();
        });
        document.addEventListener('pointerdown',function(e){
          if(ocrMenu&&!ocrMenu.contains(e.target)&&e.target!==areaBtn)hideOcrMenu();
        },true);
        (document.body||document.documentElement).appendChild(areaBtn);
      }
    }catch(e){}

    // Long-press a translated bubble to open correction actions (Kotlin bridge: NsRetr).
    try{
      if(!window.__nsLp){
        window.__nsLp=1;
        var lpT=0,lpX=0,lpY=0,lpMenu=null,lpSwallow=0;
        var lpHide=function(){if(lpMenu&&lpMenu.parentNode)lpMenu.parentNode.removeChild(lpMenu);lpMenu=null;};
        var lpHit=function(x,y){
          var ov=document.getElementById('__ns_wm_overlay'); if(!ov)return null;
          var bs=ov.querySelectorAll('.__ns_b'),best=null;
          for(var i=0;i<bs.length;i++){var bb=bs[i]; if(bb.style.display==='none')continue;
            var r=bb.getBoundingClientRect();
            if(x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom)best=bb;}
          return best;};
        var lpShow=function(bb,x,y){
          lpHide();
          var g=bb.getAttribute('data-g').split(','),s=bb.getAttribute('data-s');
          var m=document.createElement('div'); m.id='__ns_lp_menu';
          m.style.cssText='position:fixed;z-index:2147483647;pointer-events:auto;width:224px;padding:5px;border-radius:13px;'
            +'font:600 14px sans-serif;color:#fff;background:rgba(26,31,46,.98);border:1px solid rgba(255,255,255,.2);'
            +'box-shadow:0 4px 18px rgba(0,0,0,.5);-webkit-user-select:none;user-select:none;';
          var vw=window.innerWidth||360;
          m.style.left=Math.max(8,Math.min(vw-240,x-100))+'px';
          m.style.top=Math.max(8,y-112)+'px';
          var shownAt=Date.now();
          function item(label,action){
            var b=document.createElement('button');b.type='button';b.textContent=label;
            b.style.cssText='display:block;width:100%;box-sizing:border-box;margin:3px 0;padding:11px 10px;text-align:left;'
              +'border:0;border-radius:8px;background:#30394b;color:#fff;font:600 14px sans-serif;';
            b.addEventListener('click',function(ev){
              ev.preventDefault();ev.stopPropagation();
              if(Date.now()-shownAt<250)return;
              lpHide();try{action();}catch(ignore){}
            });
            m.appendChild(b);
          }
          item('🔁 Re-translate',function(){
            window.NsRetr.retranslate(s,parseFloat(g[0]),parseFloat(g[1]),parseFloat(g[2]),parseFloat(g[3]));
          });
          item('🔎 Force OCR + translate',function(){
            window.NsRetr.forceOcrBubble(s,parseFloat(g[0]),parseFloat(g[1]),parseFloat(g[2]),parseFloat(g[3]));
          });
          item('▧ Select Area',function(){
            if(window.__nsStartAreaSelect)window.__nsStartAreaSelect();
          });
          (document.body||document.documentElement).appendChild(m); lpMenu=m;
          setTimeout(function(){if(lpMenu===m)lpHide();},7000);
        };
        document.addEventListener('touchstart',function(e){
          if(lpMenu&&!lpMenu.contains(e.target))lpHide();
          clearTimeout(lpT);
          if(!e.touches||e.touches.length!==1)return;
          var t=e.touches[0]; lpX=t.clientX; lpY=t.clientY;
          lpT=setTimeout(function(){var bb=lpHit(lpX,lpY); if(!bb)return; lpSwallow=Date.now(); lpShow(bb,lpX,lpY);},600);
        },{capture:true,passive:true});
        document.addEventListener('touchmove',function(e){
          var t=e.touches&&e.touches[0]; if(!t)return;
          if(Math.abs(t.clientX-lpX)>10||Math.abs(t.clientY-lpY)>10)clearTimeout(lpT);
        },{capture:true,passive:true});
        document.addEventListener('touchend',function(){clearTimeout(lpT);},{capture:true,passive:true});
        document.addEventListener('touchcancel',function(){clearTimeout(lpT);},{capture:true,passive:true});
        document.addEventListener('contextmenu',function(e){
          if(Date.now()-lpSwallow<1500){e.preventDefault();e.stopPropagation();}
        },true);
      }
    }catch(e){}
    // First layout may measure a fallback font while the bundled Bengali font is still loading.
    // Re-fit every bubble after font readiness so conjuncts and long Bengali words stay intact.
    try{
      if(font && document.fonts && document.fonts.ready){
        document.fonts.ready.then(function(){
          if(!document.contains(w))return;
          var bs=w.querySelectorAll('.__ns_b');
          for(var fi=0;fi<bs.length;fi++)bs[fi].removeAttribute('data-fw');
          lastRun=0;layout();
        });
      }
    }catch(e){}
    return 'ok';
  }catch(e){return 'err:'+e;}
})(__ITEMS__,__SOURCES__,__FONT__,__PID__)
"""

    /**
     * A tainted canvas reader can replace its bitmap without changing the URL or DOM geometry.
     * When OCR confirms that the canvas content changed, clear both the visible overlay and the
     * hidden HTML used by View Original/View Translation. Keeping only one of those would allow
     * the toggle to resurrect translations from the previous manga page.
     */
    fun clearCanvasOverlayScript(): String = """
        (function(){
          try {
            var w = document.getElementById('__ns_wm_overlay');
            try { if (window.__nsWmStop) window.__nsWmStop(); } catch(e) {}
            if (w && w.parentNode) w.parentNode.removeChild(w);
            window.__nsEl = null;
            window.__nsOrig = '';
            window.__nsTr = '';
            window.__nsShown = 0;
            window.__nsWmStop = null;
            return 'cleared';
          } catch(e) { return 'clear-error:' + e; }
        })()
    """.trimIndent()

    private fun css(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    private fun r5(x: Float): Double = Math.round(x * 100000.0) / 100000.0

    /**
     * Draws each translated paragraph on the spot of its original text. [translated] is index-aligned
     * with [WebModeLayout.bubbles]; a null/blank entry leaves that bubble's original text untouched.
     */
    fun bubbleScript(layout: WebModeLayout, translated: List<String?>, font: String, pageId: String): String {
        val arr = JSONArray()
        // The cleaner records exactly which bubbles it erased (flat and artwork alike). The cleaned image is
        // shown only if every ERASED bubble of that image has a translation; otherwise the original <img>
        // stays visible so untranslated text cannot silently disappear. Bubbles the cleaner skipped do not
        // block it: they simply keep the opaque-box fallback.
        val canShowCleanImage = layout.bubbles.withIndex()
            .groupBy { WebModeLogic.normalizeUrl(it.value.src) }
            .mapValues { (_, group) ->
                group.filter { it.value.sourceText.isNotBlank() &&
                        WebModeTextRemovalCache.isErased(it.value.src, WebModeTextRemovalCache.bubbleKey(it.value)) }
                    .all { (index, _) -> !translated.getOrNull(index).isNullOrBlank() }
            }
        // The same speech can reach the layout twice (two slices / two OCR readings of one bubble) with boxes that
        // overlap and texts that mostly agree. Drawn together their translations print on top of each other.
        // Keep the fuller reading, hide the other one.
        val hidden = HashSet<Int>()
        val grams = layout.bubbles.map { bb ->
            val s = WebModeLayout.normalizeOcrText(bb.sourceText)
            HashSet<String>().also { g -> for (k in 0 until s.length - 1) g.add(s.substring(k, k + 2)) }
        }
        val bl = layout.bubbles
        for (i in bl.indices) for (j in i + 1 until bl.size) {
            if (i in hidden || j in hidden) continue
            val a1 = bl[i]; val b1 = bl[j]
            if (WebModeLogic.normalizeUrl(a1.src) != WebModeLogic.normalizeUrl(b1.src)) continue
            if (translated.getOrNull(i).isNullOrBlank() || translated.getOrNull(j).isNullOrBlank()) continue
            val ox = minOf(a1.cx + a1.w / 2f, b1.cx + b1.w / 2f) - maxOf(a1.cx - a1.w / 2f, b1.cx - b1.w / 2f)
            val oy = minOf(a1.cy + a1.h / 2f, b1.cy + b1.h / 2f) - maxOf(a1.cy - a1.h / 2f, b1.cy - b1.h / 2f)
            if (ox <= 0f || oy <= 0f) continue
            val minArea = minOf(a1.w * a1.h, b1.w * b1.h)
            if (minArea <= 0f || ox * oy < 0.5f * minArea) continue
            val ga = grams[i]; val gb = grams[j]
            if (ga.size < 3 || gb.size < 3) continue
            val common = ga.count { it in gb }
            if (common < 0.6f * minOf(ga.size, gb.size)) continue
            val dropJ = if (a1.sourceText.length != b1.sourceText.length) a1.sourceText.length > b1.sourceText.length
                else a1.w * a1.h >= b1.w * b1.h
            hidden.add(if (dropJ) j else i)
        }
        for ((i, b) in layout.bubbles.withIndex()) {
            if (i in hidden) continue
            val t = WmSfx.fix(translated.getOrNull(i)?.trim().orEmpty())
            if (t.isEmpty()) continue
            arr.put(JSONObject()
                .put("t", t).put("s", WebModeLogic.normalizeUrl(b.src))
                .put("cx", r5(b.cx)).put("cy", r5(b.cy)).put("w", r5(b.w)).put("h", r5(b.h))
                .put("a", r5(b.angle)).put("f", r5(b.font))
                .put("bg", css(b.bg)).put("fg", css(b.fg)).put("g2", css(b.fg2)).put("sf", b.soft).put("o", if (b.stroke != 0) css(b.stroke) else "")
                // Transparent translation only where THIS bubble's old lettering was really erased; a bubble the
                // cleaner skipped keeps its cover box, so the old text never shows through the translation.
                .put("cl", if (WebModeTextRemovalCache.isErased(b.src, WebModeTextRemovalCache.bubbleKey(b)) &&
                    canShowCleanImage[WebModeLogic.normalizeUrl(b.src)] == true)
                    WebModeTextRemovalCache.urlFor(b.src).orEmpty() else ""))
        }
        // The translated text goes in last, so nothing in it can be mistaken for a placeholder.
        val sources = JSONArray()
        // Pass every OCR source, including bubbles with no translation. Otherwise an off-screen
        // query-ID variant could be mistaken for the one translated item still in the DOM.
        layout.bubbles.asSequence()
            .map { WebModeLogic.normalizeUrl(it.src) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { sources.put(it) }
        return "try{document.documentElement.style.setProperty('--ns-bi','" + bottomInsetPx + "px');}catch(e){};" + BUBBLES
            .replace("__FONT__", JSONObject.quote(font))
            .replace("__PID__", JSONObject.quote(pageId))
            .replace("__ITEMS__", arr.toString())
            .replace("__SOURCES__", sources.toString())
    }
}

/**
 * Safety net for sound effects the chatbot handed back in English letters (BOOM, HAH ...): the
 * common ones are replaced by their Bengali form. Anything unknown is kept exactly as received.
 */
internal object WmSfx {
    /** False when the target language is not Bengali: the English-to-Bengali sound-effect map is skipped. */
    @Volatile var bengali = true

    private val MAP = mapOf(
        "BOOM" to "ধুম!", "BANG" to "দুম!", "CRASH" to "ঝনঝন!", "SMASH" to "ধ্বংস!", "THUD" to "ধপ!",
        "THUMP" to "ধুপ!", "BAM" to "ধাম!", "POW" to "পাউ!", "WHAM" to "ধড়াম!", "SLAM" to "দড়াম!",
        "CLANG" to "ঠং!", "CLANK" to "ঠকাস!", "CLICK" to "ক্লিক!", "SLASH" to "ঝপ!", "SWISH" to "সাঁই!",
        "SWOOSH" to "শাঁ!", "WHOOSH" to "সোঁ!", "ZAP" to "ঝাপ!", "CRACK" to "মড়াৎ!", "SNAP" to "ঠাস!",
        "SPLASH" to "ছপাৎ!", "DRIP" to "টপ!", "RUMBLE" to "গুড়গুড়!", "ROAR" to "গর্জন!", "GRR" to "গরররর!",
        "GRRR" to "গরররর!", "HAH" to "হাঃ", "HAA" to "হাআ", "HAAH" to "হাঃ", "HUFF" to "হাঁস!",
        "PANT" to "হাঁপ", "GASP" to "হাঁ!", "GULP" to "গিল!", "GRUNT" to "গোঙানি", "SIGH" to "আহ্...",
        "SHHH" to "শশশ!", "HMM" to "হুম...", "HM" to "হুম", "UGH" to "উফ!", "AGH" to "আঃ!", "AAH" to "আহ!",
        "AAAH" to "আআআ!", "AAAAH" to "আআআআ!", "KYAA" to "কিয়াআ!", "EEK" to "ইশ!", "TCH" to "চ!",
        "TSK" to "চ!", "PHEW" to "ফুঃ", "GRAB" to "খপ!", "STAB" to "খচ!", "THUNK" to "ঠক!", "KNOCK" to "ঠকঠক",
        "FLASH" to "ঝলক!", "SPARK" to "স্ফুলিঙ্গ!", "RIP" to "ছিঁড়!", "TEAR" to "ফড়াত!", "THWACK" to "চটাস!",
        "WHAP" to "চটাস!", "SLAP" to "চড়!", "PUNCH" to "ঘুষি!", "KICK" to "লাথি!", "THROB" to "ধকধক",
        "BADUM" to "ধুকধুক", "TICK" to "টিক", "TOCK" to "টক", "BEEP" to "বিপ!", "BUZZ" to "ভনভন"
    )

    private fun letters(s: String) = s.filter { it.isLetter() }

    fun fix(t: String): String {
        if (t.isEmpty()) return t
        if (t == "~" || t == "-" || t == "—" || t == "…") return ""     // chatbot's "this was noise" answer: leave the picture alone
        val l = letters(t)
        if (l.isEmpty() || l.any { it.code > 0x24F }) return t          // already (partly) non-Latin
        if (!bengali) return t
        val key = l.uppercase().replace(Regex("(.)\\1{2,}"), "$1$1")
        return MAP[key] ?: MAP[l.uppercase()] ?: t
    }
}
