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

    private const val BUBBLES_PART_1 = """
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
    // Keep opaque bubble fills below every translated label. A text box can move horizontally
    // to resolve collisions without carrying its background over a neighbouring translation.
    var coverLayer=document.createElement('div'); coverLayer.id='__ns_wm_cover_layer';
    coverLayer.style.cssText='position:absolute;left:0;top:0;width:0;height:0;overflow:visible;pointer-events:none;z-index:1;';
    w.appendChild(coverLayer);
    var textLayer=document.createElement('div'); textLayer.id='__ns_wm_text_layer';
    textLayer.style.cssText='position:absolute;left:0;top:0;width:0;height:0;overflow:visible;pointer-events:none;z-index:2;';
    w.appendChild(textLayer);
    var cleanImgs=Object.create(null);
    var holes=Object.create(null);   // per picture: rectangles (image fractions) where the ORIGINAL must show through
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
      var hl=holes[key];
      if(hl&&hl.length){
        // evenodd path = whole picture minus the holes
        var W=r.width,H=r.height,d='M0 0H'+W+'V'+H+'H0Z';
        for(var hi=0;hi<hl.length;hi++){var q=hl[hi];
          var hx0=Math.max(0,q[0]*W),hy0=Math.max(0,q[1]*H),hx1=Math.min(W,q[2]*W),hy1=Math.min(H,q[3]*H);
          if(hx1>hx0&&hy1>hy0)d+='M'+hx0.toFixed(1)+' '+hy0.toFixed(1)+'H'+hx1.toFixed(1)+'V'+hy1.toFixed(1)+'H'+hx0.toFixed(1)+'Z';}
        var cp='path(evenodd,"'+d+'")';
        if(entry.cp!==cp){entry.cp=cp;entry.el.style.clipPath=cp;entry.el.style.webkitClipPath=cp;}
      }else if(entry.cp){entry.cp='';entry.el.style.clipPath='';entry.el.style.webkitClipPath='';}
      entry.el.style.transform='translate3d('+x+'px,'+y+'px,0)';
    }
    var fam=font?"'NSBn',sans-serif":'sans-serif';
    for(var i=0;i<items.length;i++){
      var it=items[i];
      if(it.hole){var hk=norm(it.s);(holes[hk]=holes[hk]||[]);for(var hq=0;hq<it.r.length;hq++)holes[hk].push(it.r[hq]);continue;}
      var b=document.createElement('div'); b.className='__ns_b';
      b.setAttribute('data-s',it.s);
      b.setAttribute('data-cl',it.cl||'');
      // Erased in place (canvas panel): the old lettering is already gone from the page pixels, so no cover box.
      b.setAttribute('data-er',it.er?'1':'');
      b.setAttribute('data-bg',it.bg);
      b.setAttribute('data-sf',it.sf?'1':'0');
      b.setAttribute('data-g',[it.cx,it.cy,it.w,it.h,it.a,it.f].join(','));
      // Largest rectangle INSIDE the speech bubble (fractions of the cover box: left,top,right,bottom margins).
      if(it.tb&&it.tb.length===4)b.setAttribute('data-tb',it.tb.join(','));
      // Conservative interior bands from the detector/pixel mask. The fitter may use more of the balloon only
      // when every measured text line remains inside these real shape bands.
      if(it.cv&&it.cv.length>=4)b.setAttribute('data-cv',it.cv.join(','));
      // Model-mask geometry is a fixed shape; the shape-aware fitter below proves any wider layout stays inside it.
      if(it.mf)b.setAttribute('data-mf','1');
      // This element is text-only. Opaque bubble fills are rendered separately in coverLayer.
      // overflow:hidden is what keeps a long translation inside its mask. fit() still shrinks the font to
      // avoid clipping, so this is a hard backstop rather than the normal path.
      b.style.cssText='position:absolute;left:0;top:0;display:none;box-sizing:border-box;overflow:hidden;'
        +'align-items:center;justify-content:center;padding:3px;pointer-events:none;will-change:transform;'
        +'background:transparent;color:'+it.fg+';font-family:'+fam+';font-weight:600;border-radius:0;box-shadow:none;';
      var t=document.createElement('div'); t.textContent=it.t;
      // Preserve Bengali grapheme clusters/ conjuncts and use a shaping-friendly text layout.
      // OCR bubble bounds can be slightly smaller than the speech bubble, so fit() below
      // measures both axes and shrinks the text instead of letting it spill outside.
      t.style.cssText='display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;'
        +'text-align:center;line-height:1.25;white-space:normal;word-break:normal;overflow-wrap:normal;'
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
      b.appendChild(t);
      var cover=null;
      if(!it.cl&&!it.er){
        cover=document.createElement('div');cover.className='__ns_cover';
        cover.style.cssText='position:absolute;left:0;top:0;display:none;box-sizing:border-box;overflow:visible;'
          +'pointer-events:none;will-change:transform;background:'+((it.cv&&it.cv.length>=4)?'transparent':it.bg)+';';
        if(it.cv&&it.cv.length>=4){
          for(var ci=0;ci+3<it.cv.length;ci+=4){
            var cd=document.createElement('div');cd.className='__ns_cv';
            cd.style.cssText='position:absolute;pointer-events:none;margin:0;padding:0;left:'+(it.cv[ci]*100)+'%;top:'+(it.cv[ci+1]*100)+'%;'
              +'width:'+((it.cv[ci+2]-it.cv[ci])*100)+'%;height:'+((it.cv[ci+3]-it.cv[ci+1])*100)+'%;background:'+it.bg+';';
            cover.appendChild(cd);
          }
        }else{
          cover.style.borderRadius=it.sf?'12px':'4px';
          cover.style.boxShadow=it.sf?'0 0 7px 5px '+it.bg:'0 0 0 3px '+it.bg;
        }
        coverLayer.appendChild(cover);
      }
      b.__nsCover=cover;
      textLayer.appendChild(b);
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
    /**
     * How many of this overlay's images are still unusable for placement: not decoded yet, or laid out at zero
     * size because the reader has not finished sizing them. Only the first pass consults this.
     */
    function unsizedImages(nodes){
      // Read only what the DOM already holds. Calling findImg() here built a full image index on every
      // animation frame and poisoned the miss-cache, which then made the real placement pass pick the
      // wrong <img> — text landed on the wrong panel and masking followed it there.
      var n=0;
      for(var i=0;i<nodes.length;i++){
        var s=nodes[i].getAttribute('data-s'),im=cache[s];
        if(!im||!document.contains(im))continue;
        if(!im.complete||!im.naturalWidth)continue;
        var r=im.getBoundingClientRect();
        if(r.width<2||r.height<2)n++;
      }
      return n;
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
      if(!t)return 0;
      var boxW=b.clientWidth,boxH=b.clientHeight;
      if(boxW<8||boxH<8)return boxH;
      // Shape-fit may let the text extend beyond the old OCR box, but only inside measured balloon bands.
      // Reset on every new measurement so a resize can never inherit a stale visible-overflow state.
      b.__nsShapeFitApplied=false;b.style.overflow='hidden';
      var availW=Math.max(8,boxW*0.88),availH=Math.max(8,boxH*0.86),hasTb=false,cW=boxW,cH=boxH;
      var maskLock=b.getAttribute('data-mf')==='1';
      function fitInsideMask(startPx){
        t.style.overflowWrap='normal';t.style.wordBreak='normal';
        var px=Math.max(1.5,Number(startPx)||1.5);
        for(var mi=0;mi<70;mi++){
          t.style.fontSize=px+'px';
          if(fits())break;
          if(px<=1.5)break;
          px=Math.max(1.5,px*0.9);
        }
        t.style.fontSize=px+'px';
        t.style.overflow='hidden';
      }
      var tbs=b.getAttribute('data-tb');
      if(tbs){
        var q=tbs.split(','),pl=q[0]*boxW,pt=q[1]*boxH,pr=q[2]*boxW,pb=q[3]*boxH;
        cW=boxW-pl-pr;cH=boxH-pt-pb;
        if(isFinite(cW)&&isFinite(cH)&&cW>=boxW*0.25&&cH>=boxH*0.25&&cW>=8&&cH>=8){
          b.style.padding=Math.max(0,pt)+'px '+Math.max(0,pr)+'px '+Math.max(0,pb)+'px '+Math.max(0,pl)+'px';
          availW=Math.max(8,cW*0.96);availH=Math.max(8,cH*0.94);hasTb=true;
        }
      }
      if(!hasTb)b.style.padding='3px';
      t.style.width=availW+'px';t.style.maxWidth=availW+'px';t.style.minWidth='0';
      t.style.height='auto';t.style.maxHeight='none';t.style.boxSizing='border-box';t.style.margin='0 auto';
      t.style.whiteSpace='normal';t.style.maxBlockSize='none';t.style.wordBreak='normal';t.style.overflowWrap='normal';
      t.style.flex='none';   // a flex item would shrink to the narrow box and report a wrong, too tall size while we measure
      // Keep the whole string. There is no ellipsis or line cap; shrink to sub-pixel sizes only
      // as a last resort, then request a taller geometry instead of clipping the final lines.
      var cap=Number(maxPx);if(!isFinite(cap)||cap<2)cap=4;
      var lo=0.5,hi=Math.max(lo,hasTb?cap*1.35:cap),best=lo,fitCap=hi;
      // A readable floor: below it the box grows (sideways first, then taller) instead of the font shrinking further.
      var floorPx=maskLock?Math.max(3,Math.min(cap*0.5,hi)):Math.max(6,Math.min(cap*0.62,hi));
      function fits(){
        var r=t.getBoundingClientRect();
        return r.height<=availH+0.5&&t.scrollHeight<=availH+1&&
          t.scrollWidth<=t.clientWidth+1&&r.width<=availW+1;
      }
      // Text may use a wider part of an irregular balloon than the single largest safe rectangle,
      // but only when the actual glyph line boxes are proven to sit inside the detector's interior bands.
      // This is script-neutral: it measures the rendered text, not Japanese/Korean/English source labels.
      function lineRects(){
        try{
          var rg=document.createRange();rg.selectNodeContents(t);
          var rs=rg.getClientRects(),out=[];
          for(var ri=0;ri<rs.length;ri++){
            var q=rs[ri];
            if(q&&q.width>0.35&&q.height>0.35)out.push({left:q.left,right:q.right,top:q.top,bottom:q.bottom,width:q.width,height:q.height});
          }
          return out;
        }catch(e){return [];}
      }
      function renderedLineCount(){
        var rs=lineRects(),ys=[];
        for(var ri=0;ri<rs.length;ri++){
          var cy=(rs[ri].top+rs[ri].bottom)/2,found=false;
          for(var yi=0;yi<ys.length;yi++)if(Math.abs(ys[yi]-cy)<=Math.max(2,rs[ri].height*0.28)){found=true;break;}
          if(!found)ys.push(cy);
        }
        return ys.length;
      }
      function staysInsideDetectedShape(){
        var bands=b.__nsSafeBandsPx,rs=lineRects();
        if(!bands||!bands.length||!rs.length)return false;
        for(var ri=0;ri<rs.length;ri++){
          var q=rs[ri],pad=Math.min(1.1,Math.max(0.25,q.height*0.06));
          var y0=q.top+pad,y1=q.bottom-pad;
          if(y1<y0){var mid=(q.top+q.bottom)/2;y0=mid;y1=mid;}
          for(var si=0;si<5;si++){
            var y=y0+(y1-y0)*(si+0.5)/5,inside=false;
            for(var bi=0;bi<bands.length;bi++){
              var band=bands[bi];
              if(y>=band[1]+0.15&&y<=band[3]-0.15&&q.left>=band[0]+0.5&&q.right<=band[2]-0.5){inside=true;break;}
            }
            if(!inside)return false;
          }
        }
        return true;
      }
      function fitWithinShape(preferWide){
        var bands=b.__nsSafeBandsPx;
        var shapeCap=Number(b.__nsShapeMaxW),pageW=Number(b.__nsShapePageW);
        if(!bands||!bands.length||b.__nsShapeFitBlocked||!isFinite(shapeCap)||shapeCap<8)return false;
        var oldPad=b.style.padding,oldW=availW,oldH=availH,oldTW=t.style.width,
            oldMW=t.style.maxWidth,oldFS=t.style.fontSize,oldOverflow=t.style.overflow,
            oldMargin=t.style.margin,oldPosition=t.style.position,oldLeft=t.style.left,oldTransform=t.style.transform;
        // The parent is the source/cover box, not necessarily the full balloon. Allow a wider child up to
        // the widest real interior band; every candidate's rendered glyphs are checked against those bands.
        var upperW=Math.max(8,Math.min(shapeCap,isFinite(pageW)&&pageW>8?pageW-4:shapeCap));
        var startW=Math.max(8,Math.min(availW,upperW));
        if(preferWide)startW=Math.min(upperW,Math.max(startW+6,startW*1.16));
        startW=Math.min(startW,upperW);
        b.style.padding='3px';
        // A wider child must remain centred on the original OCR/bubble anchor instead of starting at the
        // parent's left edge when its width exceeds the old source-text box.
        t.style.margin='0';t.style.position='relative';t.style.left='50%';t.style.transform='translateX(-50%)';
        availH=Math.max(8,boxH*0.86);
        var chosen=null;
        // This runs on the WebView UI thread. Keep the search bounded: checking every glyph line against every
        // mask band inside all 108 font/width trials made multi-bubble pages appear stuck after the answer arrived.
        // Find a box-fitting font cheaply, then perform only a few shape validations while stepping down if needed.
        for(var wi=0;wi<=4;wi++){
          var trialW=startW+(upperW-startW)*(wi/4);
          availW=trialW;
          t.style.width=trialW+'px';t.style.maxWidth=trialW+'px';t.style.overflow='visible';
          var loPx=Math.max(1.5,floorPx),hiPx=fitCap,bestPx=0;
          for(var fi=0;fi<8;fi++){
            var midPx=(loPx+hiPx)/2;
            t.style.fontSize=midPx+'px';
            if(fits()){bestPx=midPx;loPx=midPx;}else hiPx=midPx;
          }
          if(bestPx<floorPx)bestPx=floorPx;
          var validPx=0,trialPx=bestPx;
          for(var si=0;si<4&&trialPx>=floorPx;si++){
            t.style.fontSize=trialPx+'px';
            if(fits()&&staysInsideDetectedShape()){validPx=trialPx;break;}
            if(trialPx<=floorPx+0.05)break;
            trialPx=Math.max(floorPx,trialPx*0.88);
          }
          // Compare five representative widths, choosing the largest validated font without blocking the reader.
          if(validPx>=floorPx&&(!chosen||validPx>chosen.px+0.05||
             (Math.abs(validPx-chosen.px)<=0.05&&trialW<chosen.w)))chosen={w:trialW,px:validPx};
        }
        if(chosen){
          availW=chosen.w;t.style.width=chosen.w+'px';t.style.maxWidth=chosen.w+'px';
          t.style.fontSize=chosen.px+'px';t.style.overflow='visible';
          b.style.overflow='visible';b.__nsShapeFitApplied=true;
          return true;
        }
        b.style.padding=oldPad;availW=oldW;availH=oldH;
        t.style.width=oldTW;t.style.maxWidth=oldMW;t.style.fontSize=oldFS;t.style.overflow=oldOverflow;
        t.style.margin=oldMargin;t.style.position=oldPosition;t.style.left=oldLeft;t.style.transform=oldTransform;
        b.style.overflow='hidden';b.__nsShapeFitApplied=false;
        return false;
      }
      for(var n=0;n<18;n++){
        var mid=(lo+hi)/2;t.style.fontSize=mid+'px';
        if(fits()){best=mid;lo=mid;}else{hi=mid;}
      }
      var sourceText=String(t.textContent||'').trim();
      var shapeWidth=Number(b.__nsShapeMaxW)||boxW;
      var manyNarrowLines=hasTb&&sourceText.length>=8&&renderedLineCount()>=3&&
        (cW<boxW*0.78||shapeWidth>cW*1.15);
      // If the safe rectangle forces a long translation into narrow lines, try wider layouts in the real balloon
      // interior. This works for all target scripts and never assumes that source and translated text have similar widths.
      if((best<floorPx||manyNarrowLines)&&fitWithinShape(manyNarrowLines))return boxH;
      if(best<floorPx){
        if(maskLock){fitInsideMask(best);return boxH;}
        // Even the smallest readable size does not fit: take the free space at the sides.
        // Font size AND the full text length are kept; the box simply becomes wider (bounded by the picture).
        // Prefer ~85% of the original size and widen only as far as that needs; fall back to the floor when even
        // the full free width cannot hold it.
        var wantPx=Math.max(floorPx,Math.min(hi,cap*0.85));
        t.style.fontSize=wantPx+'px';
        var nomW=Number(b.__nsNomW)||boxW,maxW=Math.max(boxW,Number(b.__nsMaxW)||boxW);
        var maxExt=Math.max(0,(maxW-boxW)*0.88);
        var baseAvail=availW,ext=0;
        function fitsExt(e){t.style.width=(baseAvail+e)+'px';t.style.maxWidth=(baseAvail+e)+'px';return fits();}
        if(maxExt>1&&!fitsExt(0)){
          if(!fitsExt(maxExt)&&wantPx>floorPx){t.style.fontSize=floorPx+'px';}
          if(fitsExt(maxExt)){
            var eLo=0,eHi=maxExt;
            for(var ei=0;ei<14;ei++){var em=(eLo+eHi)/2;if(fitsExt(em))eHi=em;else eLo=em;}
            ext=eHi;
          }else ext=maxExt;
        }
        availW=baseAvail+ext;fitsExt(ext);
        if(ext>0.5){
          var newBoxW=boxW+ext/0.88+2;
          var wr=newBoxW/Math.max(1,nomW);if(wr>(Number(b.__nsReqWRatio)||0)+0.005){b.__nsReqWRatio=wr;b.__nsReqHRatio=0;}
        }
        t.style.overflow='visible';
        if(fits())return boxH;
        // Still too tall at the widest allowed box. The box may grow a little taller (bounded), then the font keeps
        // shrinking: a slightly smaller text that sits in its bubble beats a giant card over the artwork.
        // Words are never split: a word wider than the box makes the FONT smaller, it is not broken across lines.
        var shr=hasTb?Math.max(0.08,(cH/Math.max(1,boxH))*0.94):0.86;
        var maxH=Math.max(boxH,Number(b.__nsMaxH)||boxH),capAvail=Math.max(availH,maxH*shr);
        function heightNow(){var q=t.getBoundingClientRect();return Math.max(q.height,t.scrollHeight);}
        var fpx=parseFloat(t.style.fontSize)||floorPx,gd=0;
        while(heightNow()>capAvail+1&&fpx>3.5&&gd++<60){fpx*=0.94;t.style.fontSize=fpx+'px';}
        var needH=heightNow();
        if(needH<=availH+1)return boxH;    // nothing taller is needed; never grow "just in case"
        return Math.min(maxH,Math.ceil(needH/shr)+4);
      }
      t.style.fontSize=best+'px';
      for(var pass=0;pass<4;pass++){
        var r=t.getBoundingClientRect(),actualH=Math.max(r.height,t.scrollHeight),actualW=Math.max(r.width,t.scrollWidth);
        var scale=Math.min(1,availH/Math.max(1,actualH),availW/Math.max(1,actualW));
        if(scale>=0.995)break;
        var current=parseFloat(t.style.fontSize)||best,next=Math.max(0.5,current*scale*0.96);
        if(next>=current-0.02)break;t.style.fontSize=next+'px';
      }
      t.style.overflow='visible';
      if(fits())return boxH;
      // A mask-derived safe rectangle is the hard boundary. Shrink/wrap inside it rather than requesting
      // a larger box whose new dimensions would push translated text outside the detected balloon.
      if(maskLock){fitInsideMask(parseFloat(t.style.fontSize)||best);return boxH;}
      // A single long word or very long translation may need more room even at minimum size.
      // Shrink further (words stay whole); if height still does not fit, expand the box and
      // let collision resolution move it sideways on the next frame. The text itself is never cut.
      t.style.overflowWrap='normal';t.style.wordBreak='normal';
      var wrapLo=0.5,wrapHi=Math.max(wrapLo,parseFloat(t.style.fontSize)||best),wrapBest=wrapLo;
      for(var wi=0;wi<14;wi++){
        var wm=(wrapLo+wrapHi)/2;t.style.fontSize=wm+'px';
        if(fits()){wrapBest=wm;wrapLo=wm;}else{wrapHi=wm;}
      }
      t.style.fontSize=wrapBest+'px';
      if(fits())return boxH;
      // No font size fits even with emergency wrapping; grow the box and let the side-by-side
      // collision pass make room. This fallback is preferable to clipping a line or glyph.
      t.style.fontSize=Math.max(1,floorPx)+'px';
      var rr=t.getBoundingClientRect(),requiredContentH=Math.max(rr.height,t.scrollHeight);
      if(requiredContentH<=availH+1)return boxH;
      var safeHeightRatio=hasTb?Math.max(0.08,(cH/Math.max(1,boxH))*0.94):0.86;
      return Math.ceil(requiredContentH/safeHeightRatio)+4;
    }
    var MARGIN=1400;
    // Two bubbles whose boxes overlap partly (joined / burst bubbles): the later one used to paint its
    // background over the first one's text. Cut the overlap away so both stay fully readable.
    // Collision resolution is horizontal-only: Y centres never move to make room.
    // Comparable boxes are packed side-by-side in their shared image bounds. If the
    // row is too wide, widths shrink proportionally and fit() re-typesets the full text.
    function resolve(L){
      var n=L.length,GAP=3,parent=new Array(n);
      function root(i){while(parent[i]!==i){parent[i]=parent[parent[i]];i=parent[i];}return i;}
      function join(a,b){var ra=root(a),rb=root(b);if(ra===rb)return false;parent[rb]=ra;return true;}
      function geom(R,original){
        var x0=original?R.ox0:R.x0,x1=original?R.ox1:R.x1,
            y0=original?R.oy0:R.y0,y1=original?R.oy1:R.y1;
        var w=Math.max(0,x1-x0),h=Math.max(0,y1-y0),cx=(x0+x1)/2,cy=(y0+y1)/2,
            a=(Number(R.angle)||0)*Math.PI/180,co=Math.abs(Math.cos(a)),si=Math.abs(Math.sin(a)),
            rw=co*w+si*h,rh=si*w+co*h;
        return {l:cx-rw/2,r:cx+rw/2,t:cy-rh/2,b:cy+rh/2,w:rw,h:rh,cx:cx,cy:cy};
      }
      // Boxes only COLLIDE when they really overlap. Text blocks that merely touch (lines of one caption, a button label
      // under a sentence) used to be treated as a collision and were then packed side by side, which threw every
      // translation sideways, away from the words it replaces. A thin vertical overlap is trimmed instead.
      function hits(a,b,g){
        var ox=Math.min(a.r,b.r)-Math.max(a.l,b.l),oy=Math.min(a.b,b.b)-Math.max(a.t,b.t);
        return ox>2&&oy>Math.max(2,0.22*Math.min(a.h,b.h));
      }
      function area(r){return Math.max(0,r.r-r.l)*Math.max(0,r.b-r.t);}
      for(var i=0;i<n;i++){
        parent[i]=i;L[i].hidden=false;
        L[i].needH=Math.max(L[i].needH||0,(Number(L[i].b.__nsReqHRatio)||0)*L[i].oh);
        L[i].needW=Math.max(L[i].needW||0,(Number(L[i].b.__nsReqWRatio)||0)*L[i].ow);
      }
      // Thin vertical overlaps (neighbouring lines/labels): move the two touching edges apart, keep everything in place.
      for(var i=0;i<n;i++)for(var j=i+1;j<n;j++){
        var A=L[i],B=L[j];if(A.hidden||B.hidden||A.s!==B.s||A.rot||B.rot)continue;
        var ox=Math.min(A.ox1,B.ox1)-Math.max(A.ox0,B.ox0),oy=Math.min(A.oy1,B.oy1)-Math.max(A.oy0,B.oy0);
        if(ox<=2||oy<=0||oy>Math.max(2,0.22*Math.min(A.oh,B.oh)))continue;
        var U=(A.oy0+A.oy1<=B.oy0+B.oy1)?A:B,V=(U===A)?B:A,cut=oy/2+1,
            trimU=Math.min(cut,Math.max(0,(U.oh-4)/2)),
            trimV=Math.min(cut,Math.max(0,(V.oh-4)/2));
        // Trim symmetrically around each anchor. Cutting only the touching edge shifts the text centre
        // (e.g. 105 -> 103.5), which moves translations away from their original button/bubble.
        U.oy0+=trimU;U.oy1-=trimU;U.oh=Math.max(4,U.oy1-U.oy0);U.y0=U.oy0;U.y1=U.oy1;
        V.oy0+=trimV;V.oy1-=trimV;V.oh=Math.max(4,V.oy1-V.oy0);V.y0=V.oy0;V.y1=V.oy1;
      }
      // Exact duplicate OCR/translation at the same place is one label, not two.
      for(var i=0;i<n;i++)for(var j=i+1;j<n;j++){
        var A=L[i],B=L[j];if(A.hidden||B.hidden||A.s!==B.s)continue;
        var at=(A.b.textContent||'').trim(),bt=(B.b.textContent||'').trim();
        if(!at||at!==bt)continue;
        var ar=geom(A,true),br=geom(B,true),ix=Math.max(0,Math.min(ar.r,br.r)-Math.max(ar.l,br.l)),
            iy=Math.max(0,Math.min(ar.b,br.b)-Math.max(ar.t,br.t)),ov=ix*iy,small=Math.min(area(ar),area(br));
        if(small>0&&ov/small>=0.82){B.hidden=true;B.x0=B.x1=B.y0=B.y1=-99999;}
      }
      // Free horizontal room of every box: up to the nearest neighbour that shares its rows (or the picture edge).
      for(var i=0;i<n;i++){
        var A=L[i];if(A.hidden)continue;
        var fl=Number.isFinite(A.il)?A.il+1:-Infinity,fr=Number.isFinite(A.ir)?A.ir-1:Infinity;
        for(var j=0;j<n;j++){
          if(j===i)continue;var B=L[j];if(B.hidden||B.s!==A.s)continue;
          var vo=Math.min(A.oy1,B.oy1)-Math.max(A.oy0,B.oy0);
          if(vo<=2)continue;
          // Reserve a lane around each ORIGINAL text centre. The old edge-only test ignored overlapping
          // OCR boxes, so the collision packer later shoved valid labels left/right (e.g. Continue and Skip).
          var ac=(A.ox0+A.ox1)/2,bc=(B.ox0+B.ox1)/2;
          if(bc<ac)fl=Math.max(fl,(bc+ac)/2+GAP/2);
          else if(bc>ac)fr=Math.min(fr,(bc+ac)/2-GAP/2);
        }
        A.freeL=fl;A.freeR=fr;
      }
      // Same-image OCR boxes must keep their source anchors. If their boxes are broad/overlapping,
      // the free-lane calculation above shrinks them in place. Only collisions between different source
      // images may be packed, because those can represent two images occupying the same viewport area.
      for(var i=0;i<n;i++)for(var j=i+1;j<n;j++){
        var A=L[i],B=L[j];if(A.hidden||B.hidden||A.s===B.s)continue;
        if(hits(geom(A,true),geom(B,true),GAP))join(i,j);
      }
      for(var pass=0;pass<n+1;pass++){
        for(var ri=0;ri<n;ri++){
          var RB=L[ri];if(RB.hidden)continue;
          var ocx=(RB.ox0+RB.ox1)/2,ocy=(RB.oy0+RB.oy1)/2;
          var wantW=Math.max(RB.ow,RB.needW||0);
          var room=(Number.isFinite(RB.freeR)&&Number.isFinite(RB.freeL))?
            2*Math.max(0,Math.min(ocx-RB.freeL,RB.freeR-ocx)):Infinity;
          // Keep the OCR centre fixed. Width can grow for Bengali, but never beyond the lane around that centre.
          // A very tall, narrow OCR box often represents one vertical Japanese text column, not the usable
          // width of the speech balloon. If no safe balloon rectangle was found, let a word-wrapped
          // translation widen inside the available image lane instead of stacking Bengali glyphs.
          var textValue=String(RB.b.textContent||'');
          var hasAttrs=RB.b&&typeof RB.b.getAttribute==='function';
          var unshapedColumn=!RB.rot&&(!hasAttrs||RB.b.getAttribute('data-mf')!=='1')&&
            (!hasAttrs||!RB.b.getAttribute('data-tb'))&&RB.ow>0&&RB.oh>=Math.max(80,RB.ow*2.4)&&/\s/.test(textValue);
          var maxBoxW=unshapedColumn?Math.min(RB.rw*0.72,Math.max(RB.ow*3,Math.min(RB.oh*0.55,RB.rw*0.72))):RB.ow*1.35;
          RB.wideMaxW=maxBoxW;
          RB.w=Math.max(8,Math.min(wantW,room,maxBoxW));RB.h=Math.max(RB.oh,Math.min(RB.needH||0,RB.oh*1.8));
          RB.x0=ocx-RB.w/2;RB.x1=ocx+RB.w/2;
          RB.y0=ocy-RB.h/2;RB.y1=ocy+RB.h/2;
        }
        var groups=Object.create(null);
        for(var i=0;i<n;i++){if(L[i].hidden)continue;var k=root(i);(groups[k]=groups[k]||[]).push(L[i]);}
        for(var k in groups){
          var G=groups[k];
          for(var gq=0;gq<G.length;gq++)G[gq].grp=G.length>1;
          if(G.length<2)continue;
          G.sort(function(a,b){var dx=(a.ox0+a.ox1)-(b.ox0+b.ox1);return dx||((a.oy0+a.oy1)-(b.oy0+b.oy1));});
          var leftBound=-Infinity,rightBound=Infinity,centre=0,total=0;
          for(var q=0;q<G.length;q++){
            var R=G[q];
            leftBound=Math.max(leftBound,Number.isFinite(R.il)?R.il:0);
            rightBound=Math.min(rightBound,Number.isFinite(R.ir)?R.ir:(window.innerWidth||400));
            centre+=(R.ox0+R.ox1)/2;
            var rg=geom(R,true);total+=rg.w;
          }
          if(!(rightBound>leftBound+2)){leftBound=0;rightBound=window.innerWidth||400;}
          var available=Math.max(1,rightBound-leftBound-4),gap=Math.min(GAP,available/Math.max(4,G.length*2)),
              widths=0;
          for(var q=0;q<G.length;q++)widths+=geom(G[q],true).w;
          var scale=Math.min(1,Math.max(0.001,(available-gap*(G.length-1))/Math.max(1,widths)));
          var cursor,totalNow=0;
          for(var q=0;q<G.length;q++){
            var R=G[q],origW=R.ow,origH=R.oh,ang=Number(R.angle)||0,need=Math.max(origH,R.needH||0);
            R.w=origW*scale;R.h=ang?Math.max(origH*scale,R.needH||0):need;
            var vis=geom({x0:0,x1:R.w,y0:0,y1:R.h,angle:ang},false);R._visW=vis.w;totalNow+=vis.w;
          }
          if(totalNow+gap*(G.length-1)>available){
            // A tall rotated box has a horizontal footprint even when its width shrinks.
            // For those rare cases, reduce the whole footprint further before placing it.
            var extra=Math.max(0.001,(available-gap*(G.length-1))/Math.max(1,totalNow));
            for(var q=0;q<G.length;q++){var R=G[q];R.w*=extra;if((Number(R.angle)||0)!==0&&!(R.needH>R.oh))R.h*=extra;
              R._visW=geom({x0:0,x1:R.w,y0:0,y1:R.h,angle:R.angle},false).w;}
            totalNow=0;for(var q=0;q<G.length;q++)totalNow+=G[q]._visW;
          }
          var target=centre/G.length,packed=totalNow+gap*(G.length-1),
              startX=target-packed/2;
          if(packed<=available)startX=Math.max(leftBound+2,Math.min(rightBound-2-packed,startX));
          else startX=leftBound+2;
          cursor=startX;
          for(var q=0;q<G.length;q++){
            var R=G[q],ang=Number(R.angle)||0,cx=cursor+R._visW/2,cy=(R.oy0+R.oy1)/2;
            R.x0=cx-R.w/2;R.x1=cx+R.w/2;R.y0=cy-R.h/2;R.y1=cy+R.h/2;
            cursor+=R._visW+gap;
          }
        }
        // Re-pack only different-image collisions. Same-image labels are anchored to their own OCR boxes.
        var merged=false;
        for(var i=0;i<n;i++)for(var j=i+1;j<n;j++){
          var A=L[i],B=L[j];if(A.hidden||B.hidden||A.s===B.s||root(i)===root(j))continue;
          if(hits(geom(A,false),geom(B,false),GAP))if(join(i,j))merged=true;
        }
        if(!merged)break;
      }
    }
    function bw0(R){return R.ow;}
    var orgX=0,orgY=0;
    function ensureLabelCover(bb){
      var labelCover=bb.__nsLabelCover;
      if(!labelCover){
        labelCover=document.createElement('div');labelCover.className='__ns_label_cover';
        var labelBg=bb.getAttribute('data-bg')||'#fff',labelSfx=bb.getAttribute('data-sf')==='1';
        labelCover.style.cssText='position:absolute;left:0;top:0;display:none;box-sizing:border-box;overflow:visible;'
          +'pointer-events:none;will-change:transform;background:'+labelBg+';border-radius:'+(labelSfx?'12px':'4px')+';'
          +'box-shadow:'+(labelSfx?'0 0 7px 5px '+labelBg:'0 0 0 3px '+labelBg)+';';
        coverLayer.appendChild(labelCover);bb.__nsLabelCover=labelCover;
      }
      return labelCover;
    }"""
    private const val BUBBLES_PART_2 = """
    // Final check after every layout (font load, resize, scroll, a new OCR result all end up in layout()):
    // (1) every visible text must lie inside its own measured box; if not, request a fitting box;
    // (2) if rendered text overlaps, shrink the later label and re-measure instead of shifting it from
    //     the OCR anchor. Same-image text must remain exactly over its original panel lettering.
    // The result is published in window.__nsLayoutReport so the app (and tests) can read it.
    function verify(L){
      var vis=[],overflow=0,overlaps=0,pushed=0,need=false,reduced=[];
      for(var i=0;i<L.length;i++){
        var R=L[i];if(R.hidden||R.b.style.display==='none')continue;
        var t=R.b.firstChild;if(!t)continue;
        var tr=t.getBoundingClientRect(),br=R.b.getBoundingClientRect();
        var th=Math.max(tr.height,t.scrollHeight),tw=Math.max(tr.width,t.scrollWidth);
        var bh=R.y1-R.y0,bw=R.x1-R.x0;
        if(!R.rot&&(th>bh+1.5||(tw>bw+1.5&&!R.b.__nsShapeFitApplied))){
          overflow++;
          if(R.b.getAttribute('data-mf')==='1'){
            // Shape-fit text may be wider than the old OCR box, but it has been checked against the balloon's real bands.
            // If the height alone overflows, shrink only for height; never undo a valid wider shape layout.
            var lockedFs=parseFloat(t.style.fontSize)||6;
            var lockedScale=R.b.__nsShapeFitApplied
              ? Math.min(0.92,(bh-2)/Math.max(1,th))
              : Math.min(0.92,(bw-2)/Math.max(1,tw),(bh-2)/Math.max(1,th));
            if(lockedFs>2.5&&lockedScale<0.995)t.style.fontSize=Math.max(2.5,lockedFs*lockedScale*0.96)+'px';
          }else{
            var ratio=Math.min(1.8,(th/0.86+4)/Math.max(1,R.oh));
            if(th>bh+1.5&&ratio>(Number(R.b.__nsReqHRatio)||0)+0.005){R.b.__nsReqHRatio=ratio;need=true;}
          }
        }
        // the real ink area of the lines (the text block itself can be wider than its last line)
        var ink=tr;try{var rg=document.createRange();rg.selectNodeContents(t);var ib=rg.getBoundingClientRect();if(ib&&ib.width>0&&ib.height>0)ink=ib;}catch(e){}
        R.inkL=ink.left;R.inkT=ink.top;R.inkW=ink.width;R.inkH=ink.height;
        vis.push({R:R,l:ink.left,r:ink.right,t:ink.top,b:ink.bottom});
      }
      vis.sort(function(a,b){return a.t-b.t;});
      for(var i=0;i<vis.length;i++)for(var j=i+1;j<vis.length;j++){
        var A=vis[i],B=vis[j];
        if(A.R.s!==B.R.s)continue;
        var ox=Math.min(A.r,B.r)-Math.max(A.l,B.l),oy=Math.min(A.b,B.b)-Math.max(A.t,B.t);
        if(ox<=1||oy<=1)continue;
        overlaps++;
        // Preserve the OCR anchor even when two rendered labels touch. If there is room, reduce
        // the later label's font and re-measure; never push it down from the place being translated.
        if(B.R.rot)continue;
        var bb=B.R.b,txt=bb&&bb.firstChild;
        if(!txt||reduced.indexOf(bb)>=0)continue;
        reduced.push(bb);
        var fs=parseFloat(txt.style.fontSize)||0,minFs=Math.max(4,Math.min(7,fs*0.65));
        if(fs>minFs+0.5){txt.style.fontSize=Math.max(minFs,fs*0.88)+'px';need=true;}
      }
      // Cards for text that sits away from its own bubble: exactly the size of the lettering plus a little margin.
      for(var i=0;i<vis.length;i++){
        var R=vis[i].R,bb=R.b;
        if((bb.getAttribute('data-moved')!=='1'&&!bb.__nsPushed)||R.rot||bb.getAttribute('data-cl')||bb.getAttribute('data-er')){if(bb.__nsLabelCover&&bb.__nsLabelCover.style.display!=='none')bb.__nsLabelCover.style.display='none';continue;}
        var pad=Math.max(3,Math.min(8,R.inkH*0.15));
        var lc=ensureLabelCover(bb),cx=vis[i].l-orgX-pad,cy=vis[i].t-orgY-pad,cwid=vis[i].r-vis[i].l+2*pad,chei=vis[i].b-vis[i].t+2*pad;
        var lk='t'+Math.round(cx)+','+Math.round(cy)+','+Math.round(cwid)+','+Math.round(chei);
        if(lc.getAttribute('data-k')!==lk){lc.setAttribute('data-k',lk);lc.style.width=cwid+'px';lc.style.height=chei+'px';
          lc.style.transform='translate3d('+cx+'px,'+cy+'px,0)';}
        lc.style.display='block';
      }
      window.__nsLayoutReport={visible:vis.length,overflow:overflow,overlaps:overlaps,pushed:pushed,t:Date.now()};
      return need;
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
      // Startup guard: the first pass right after page load runs before lazy images have their final size,
      // so boxes landed in random spots and some were sized against a 0-height image, then visibly jumped
      // once layout settled. Wait (briefly) for every referenced image to be decoded and sized before the
      // first placement; after that the normal scroll/resize re-layout handles everything.
      if(!window.__nsSettled){
        // Only a picture that is laid out at zero size is a real "not ready yet" signal. readyState was
        // also required, and canvas readers can stay interactive for the whole session, so every overlay
        // waited the full grace period and then drew with stale geometry.
        var un=unsizedImages(bs),waited=Date.now()-(window.__nsWaitFrom||(window.__nsWaitFrom=Date.now()));
        if(un>0&&waited<800){
          for(var qi=0;qi<bs.length;qi++)bs[qi].style.display='none';
          sched();return;
        }
        window.__nsSettled=1;
      }
      if(!ABS&&(de.scrollHeight||0)>(window.innerHeight||0)+80){
        // The overlay was built while the page was still short (images not loaded yet), so it chose the
        // script-driven FIXED mode, which trails behind the picture while scrolling. The page now scrolls
        // by itself: move the bubbles into the page so the browser scrolls them with the images.
        ABS=true; w.style.cssText=CSS_ABS;
        for(var u=0;u<bs.length;u++)bs[u].removeAttribute('data-k');
      }
      var o=ABS?w.getBoundingClientRect():null;
      orgX=ABS?o.left:0;orgY=ABS?o.top:0;
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
        if(!im){if(b.style.display!=='none')b.style.display='none';if(b.__nsCover&&b.__nsCover.style.display!=='none')b.__nsCover.style.display='none';continue;}
        var r=im.getBoundingClientRect();
        if(r.width<8){if(b.style.display!=='none')b.style.display='none';if(b.__nsCover&&b.__nsCover.style.display!=='none')b.__nsCover.style.display='none';continue;}
        // Map the same safe interior bands used to erase the source lettering into live viewport pixels.
        // Geometry is refreshed with the image on every layout pass, including responsive resize/scroll.
        var g=b.getAttribute('data-g').split(',');
        var gx=Number(g[0]),gy=Number(g[1]),gw=Number(g[2]),gh=Number(g[3]);
        // data-cv entries are fractions of THIS bubble's cover box (bubbleScript converts from page fractions).
        // Do not map them from the page origin: doing so shifted the mask-check bands away from the actual balloon,
        // rejecting valid wide layouts and forcing target text back into the narrow source-text rectangle.
        var cvRaw=b.getAttribute('data-cv'),cvNums=cvRaw?cvRaw.split(','):[],safeBands=[],shapeMaxW=0;
        for(var cvi=0;cvi+3<cvNums.length;cvi+=4){
          var cl=Number(cvNums[cvi]),ct=Number(cvNums[cvi+1]),cr=Number(cvNums[cvi+2]),cb=Number(cvNums[cvi+3]);
          if(!isFinite(cl)||!isFinite(ct)||!isFinite(cr)||!isFinite(cb)||cr<=cl||cb<=ct)continue;
          var band=[r.left+(gx-gw/2+cl*gw)*r.width,
                    r.top+(gy-gh/2+ct*gh)*r.height,
                    r.left+(gx-gw/2+cr*gw)*r.width,
                    r.top+(gy-gh/2+cb*gh)*r.height];
          safeBands.push(band);shapeMaxW=Math.max(shapeMaxW,band[2]-band[0]);
        }
        b.__nsSafeBandsPx=safeBands;b.__nsShapeMaxW=shapeMaxW;b.__nsShapePageW=r.width;
        // Keep original DOM nodes intact so srcset, lazy loading and site click handlers keep working.
        var cleanUrl=b.getAttribute('data-cl');
        if(cleanUrl)ensureCleanImage(im,b.getAttribute('data-s'),cleanUrl,r,o);
        var bw=g[2]*r.width,bh=g[3]*r.height;
        var vx=r.left+g[0]*r.width,vy=r.top+g[1]*r.height;       // centre in the viewport
        var near=!(vy+bh/2<-MARGIN||vy-bh/2>vh+MARGIN);
        if(!near){if(b.style.display!=='none')b.style.display='none';if(b.__nsCover&&b.__nsCover.style.display!=='none')b.__nsCover.style.display='none';continue;}
        var px=ABS?(vx-o.left):vx,py=ABS?(vy-o.top):vy;          // centre in the overlay's own space
        var imgX=ABS?(r.left-o.left):r.left,angle=Number(g[4])||0;
        b.__nsNomW=bw;b.__nsMaxW=Math.max(bw,r.width-4);
        L.push({needW:Math.max(0,(Number(b.__nsReqWRatio)||0)*bw),b:b,s:b.getAttribute('data-s'),g:g,rw:r.width,ow:bw,oh:bh,
          ox0:px-bw/2,ox1:px+bw/2,oy0:py-bh/2,oy1:py+bh/2,il:imgX,ir:imgX+r.width,
          needH:Math.max(0,(Number(b.__nsReqHRatio)||0)*bh),angle:angle,rot:angle!==0,
          x0:px-bw/2,x1:px+bw/2,y0:py-bh/2,y1:py+bh/2,hidden:false});
      }
      var requestedRelayout=false;
      for(var layoutPass=0;layoutPass<3;layoutPass++){
        resolve(L);
        var expanded=false;
        for(var n=0;n<L.length;n++){
          var R=L[n],bb=R.b,gg=R.g;
          if(R.hidden){if(bb.style.display!=='none')bb.style.display='none';if(bb.__nsCover)bb.__nsCover.style.display='none';continue;}
          bb.__nsPushed=false;
          bb.__nsShapeFitBlocked=!!R.grp||bb.getAttribute('data-moved')==='1';
          var cw=R.x1-R.x0,ch=R.y1-R.y0;
          var key=Math.round(R.x0)+','+Math.round(R.y0)+','+Math.round(cw)+','+Math.round(ch);
          var keyChanged=bb.getAttribute('data-k')!==key;
          var moved=Math.abs(R.x0-R.ox0)>1||Math.abs(R.y0-R.oy0)>1||Math.abs(cw-R.ow)>1||Math.abs(ch-R.oh)>1;
          var movedState=moved?'1':'0',movedChanged=bb.getAttribute('data-moved')!==movedState;
          if(moved)bb.removeAttribute('data-tb');
          bb.setAttribute('data-moved',movedState);
          var sz=Math.round(cw)+','+Math.round(ch),szChanged=bb.getAttribute('data-sz')!==sz||movedChanged;
          if(keyChanged){
            bb.setAttribute('data-k',key);bb.style.width=cw+'px';bb.style.height=ch+'px';
            bb.style.transform='translate3d('+R.x0+'px,'+R.y0+'px,0)'+(R.angle?' rotate('+R.angle+'deg)':'');
          }
          // Background/fill stays at the original OCR box in a lower global layer;
          // only the text moves. Therefore no later fill can cover an earlier label.
          var cover=bb.__nsCover;
          if(cover){
            var ck=Math.round(R.ox0)+','+Math.round(R.oy0)+','+Math.round(R.ow)+','+Math.round(R.oh)+','+R.angle;
            if(cover.getAttribute('data-k')!==ck){
              cover.setAttribute('data-k',ck);cover.style.width=R.ow+'px';cover.style.height=R.oh+'px';
              cover.style.transform='translate3d('+R.ox0+'px,'+R.oy0+'px,0)'+(R.angle?' rotate('+R.angle+'deg)':'');
            }
            if(cover.style.display!=='block')cover.style.display='block';
          }
          // If the text moved away from its source bubble, retain the source cover AND add a
          // matching card under the relocated text so it stays readable over artwork.
          // Every card lives in coverLayer, below textLayer, so it cannot occlude another label.
          // A text that left its own bubble gets a small card under the TEXT ITSELF (never the whole box): see verify().
          if(!moved&&bb.__nsLabelCover&&bb.__nsLabelCover.style.display!=='none')bb.__nsLabelCover.style.display='none';
          // All background paints are below this layer; z-order no longer resolves collisions.
          if(bb.style.zIndex!=='')bb.style.zIndex='';
          if(bb.style.display!=='flex')bb.style.display='flex';
          var fw=String(Math.round(R.rw));
          if(szChanged||bb.getAttribute('data-fw')!==fw){
            bb.setAttribute('data-sz',sz);bb.setAttribute('data-fw',fw);
            // A box that shares its row with neighbours may not take more width than the row gave it.
            var roomW=(Number.isFinite(R.freeR)&&Number.isFinite(R.freeL))?R.freeR-R.freeL-2:R.rw-4;
            var wideCap=Number(R.wideMaxW)||R.ow*1.35;
            bb.__nsMaxW=R.grp?Math.max(8,cw):Math.max(R.ow,Math.min(wideCap,roomW));
            bb.__nsMaxH=R.oh*1.8;
            if(cw<R.ow)bb.__nsMaxW=Math.max(8,cw);   // a box that had to be narrowed must not be re-widened by the text
            var prevW=Number(bb.__nsReqWRatio)||0;
            var requiredH=fit(bb,(Number(gg[5])||0.01)*R.rw);
            if((Number(bb.__nsReqWRatio)||0)>prevW+0.005)expanded=true;   // wider box wanted: lay out again with it
            if(requiredH>ch+0.5){
              var ratio=Math.min(1.8,requiredH/Math.max(1,R.oh)),oldRatio=Number(bb.__nsReqHRatio)||0;
              if(ratio>oldRatio+0.005){bb.__nsReqHRatio=ratio;expanded=true;}
            }
          }
        }
        if(!expanded){
          if(verify(L)){requestedRelayout=true;continue;}
          break;
        }
        requestedRelayout=true;
      }
      if(requestedRelayout)schedNow();
    }
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
    private val BUBBLES: String = buildString(BUBBLES_PART_1.length + BUBBLES_PART_2.length) {
        append(BUBBLES_PART_1)
        append(BUBBLES_PART_2)
    }

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
            if (t.isEmpty()) {
                // Erased in the cleaned picture but not translated (yet): cut a HOLE in the cleaned picture there, so the
                // original lettering stays visible instead of vanishing - and every OTHER bubble of the picture can still
                // use the clean picture (before, one untranslated bubble switched the whole picture back to ugly boxes).
                if (b.sourceText.isNotBlank() && b.w > 0f && b.h > 0f &&
                    WebModeTextRemovalCache.isErased(b.src, WebModeTextRemovalCache.bubbleKey(b)) &&
                    WebModeTextRemovalCache.urlFor(b.src) != null) {
                    val rects = JSONArray()
                    rects.put(JSONArray().put(r5(b.cx - b.w / 2f)).put(r5(b.cy - b.h / 2f)).put(r5(b.cx + b.w / 2f)).put(r5(b.cy + b.h / 2f)))
                    var q = 0
                    while (q + 3 < b.cv.size) {
                        rects.put(JSONArray().put(r5(b.cv[q])).put(r5(b.cv[q + 1])).put(r5(b.cv[q + 2])).put(r5(b.cv[q + 3])))
                        q += 4
                    }
                    arr.put(JSONObject().put("hole", true).put("s", WebModeLogic.normalizeUrl(b.src)).put("r", rects))
                }
                continue
            }
            arr.put(JSONObject()
                .put("t", t).put("s", WebModeLogic.normalizeUrl(b.src))
                .put("cx", r5(b.cx)).put("cy", r5(b.cy)).put("w", r5(b.w)).put("h", r5(b.h))
                .put("a", r5(b.angle)).put("f", r5(b.font))
                .apply {
                    if (b.cv.size >= 4 && b.w > 0f && b.h > 0f) {
                        val bl = b.cx - b.w / 2f; val bt = b.cy - b.h / 2f
                        val ja = JSONArray()
                        var q = 0
                        while (q + 3 < b.cv.size) {
                            ja.put(r5((b.cv[q] - bl) / b.w)).put(r5((b.cv[q + 1] - bt) / b.h))
                                .put(r5((b.cv[q + 2] - bl) / b.w)).put(r5((b.cv[q + 3] - bt) / b.h))
                            q += 4
                        }
                        put("cv", ja)
                    }
                    if (b.tbw > 0f && b.tbh > 0f && b.w > 0f && b.h > 0f) {
                        val bl = b.cx - b.w / 2f; val bt = b.cy - b.h / 2f
                        val tl = b.tcx - b.tbw / 2f; val tt = b.tcy - b.tbh / 2f
                        val ml = (tl - bl) / b.w; val mt = (tt - bt) / b.h
                        val mr = ((b.cx + b.w / 2f) - (b.tcx + b.tbw / 2f)) / b.w
                        val mb = ((b.cy + b.h / 2f) - (b.tcy + b.tbh / 2f)) / b.h
                        if (ml > -0.02f && mt > -0.02f && mr > -0.02f && mb > -0.02f)
                            put("tb", JSONArray().put(r5(Math.max(0f, ml))).put(r5(Math.max(0f, mt)))
                                .put(r5(Math.max(0f, mr))).put(r5(Math.max(0f, mb))))
                    }
                }
                .put("mf", b.modelMaskFit)
                .put("bg", css(b.bg)).put("fg", css(b.fg)).put("g2", css(b.fg2)).put("sf", b.soft).put("o", if (b.stroke != 0) css(b.stroke) else "")
                // Transparent translation only where THIS bubble's old lettering was really erased; a bubble the
                // cleaner skipped keeps its cover box, so the old text never shows through the translation.
                .put("cl", if (WebModeTextRemovalCache.isErased(b.src, WebModeTextRemovalCache.bubbleKey(b)))
                    WebModeTextRemovalCache.urlFor(b.src).orEmpty() else "")
                // A canvas panel has no image URL, so `er` is its only way to drop the cover box. It must stay
                // limited to canvas sources: an ordinary panel whose cleaned image is not cached (evicted, or a
                // different job's erased marks) would otherwise lose its cover box with the original lettering
                // still on the page — the translation then sits on top of the untranslated text.
                .put("er", b.src.startsWith("ns-canvas:", true) &&
                    WebModeTextRemovalCache.isErased(b.src, WebModeTextRemovalCache.bubbleKey(b))))
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

    /** Keep at most two consecutive copies without initializing java.util.regex on the UI thread. */
    private fun collapseRepeatedRuns(s: String): String {
        if (s.length < 3) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            var end = i + 1
            while (end < s.length && s[end] == ch) end++
            repeat(minOf(2, end - i)) { out.append(ch) }
            i = end
        }
        return out.toString()
    }

    private fun letters(s: String) = s.filter { it.isLetter() }

    fun fix(t: String): String {
        if (t.isEmpty()) return t
        if (t == "~" || t == "-" || t == "—" || t == "…") return ""     // chatbot's "this was noise" answer: leave the picture alone
        val l = letters(t)
        if (l.isEmpty() || l.any { it.code > 0x24F }) return t          // already (partly) non-Latin
        if (!bengali) return t
        val key = collapseRepeatedRuns(l.uppercase())
        return MAP[key] ?: MAP[l.uppercase()] ?: t
    }
}
