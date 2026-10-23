package com.ashraf.novelstudio

// All the JavaScript we inject into pages lives here.
object Js {
    /** Extra wait (ms) between the text landing in the chat box and the Send click. 0 = click as soon as Send is enabled. */
    @Volatile var sendHoldMs: Long = 0L

    /**
     * Document-start script for the novel/webtoon pane ("Force-load images" menu switch, off by default).
     * Makes lazy image loaders think every image is already on screen, so a scan does not need the page
     * to be scrolled before images get their real URL: loading=lazy is forced to eager and every
     * IntersectionObserver reports its targets as intersecting. Written defensively: any failure leaves the page untouched.
     */
    const val FORCE_LOAD = """
(function(){
  if(window.__nsForceLoad)return; window.__nsForceLoad=1;
  try{
    var d=Object.getOwnPropertyDescriptor(HTMLImageElement.prototype,'loading');
    if(d&&d.set){Object.defineProperty(HTMLImageElement.prototype,'loading',{get:function(){return 'eager';},set:function(){},configurable:true});}
    var sa=Element.prototype.setAttribute;
    Element.prototype.setAttribute=function(n,v){
      if(this instanceof HTMLImageElement&&String(n).toLowerCase()==='loading')v='eager';
      return sa.call(this,n,v);
    };
  }catch(e){}
  try{
    var IO=window.IntersectionObserver;
    if(IO){
      var Fake=function(cb,opts){
        var real=new IO(cb,opts),self=this;
        function entry(el){
          var r=el.getBoundingClientRect();
          return {target:el,isIntersecting:true,intersectionRatio:1,time:performance.now(),
            boundingClientRect:r,intersectionRect:r,rootBounds:null};
        }
        this.root=real.root;this.rootMargin=real.rootMargin;this.thresholds=real.thresholds;
        this.observe=function(el){
          real.observe(el);
          setTimeout(function(){try{cb([entry(el)],self);}catch(e){}},0);
        };
        this.unobserve=function(el){real.unobserve(el);};
        this.disconnect=function(){real.disconnect();};
        this.takeRecords=function(){return real.takeRecords();};
      };
      Fake.prototype=IO.prototype;
      window.IntersectionObserver=Fake;
    }
  }catch(e){}
})();
"""
    val DARK_ON = "(function(){var id='__nsdark';if(document.getElementById(id))return;var s=document.createElement('style');s.id=id;" +
        "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff}img,video,picture,canvas{filter:invert(1) hue-rotate(180deg)!important}';" +
        "(document.head||document.documentElement).appendChild(s);})();"
    val DARK_OFF = "(function(){var e=document.getElementById('__nsdark');if(e)e.remove();})();"

    // ---------------------------------------------------------------- chatbot helpers (per-site profiles)
    private val PRELUDE = """
var __P={
 'chatgpt.com':{a:'[data-message-author-role="assistant"],[data-message-role="assistant"],article[data-turn="assistant"]',b:'.markdown',stop:'[data-testid="stop-button"],button[aria-label*="Stop" i]',send:'button#composer-submit-button,button[data-testid="send-button"],button[aria-label*="Send prompt" i],button[aria-label*="Send" i]'},
 'chat.openai.com':{a:'[data-message-author-role="assistant"],[data-message-role="assistant"],article[data-turn="assistant"]',b:'.markdown',stop:'[data-testid="stop-button"],button[aria-label*="Stop" i]',send:'button#composer-submit-button,button[data-testid="send-button"],button[aria-label*="Send prompt" i],button[aria-label*="Send" i]'},
 'gemini.google.com':{a:'model-response,.model-response-text,message-content',b:'.markdown',stop:'button[aria-label*="Stop response" i],button[aria-label*="Stop generating" i],button[aria-label*="Stop" i],[data-testid*="stop" i],[data-test-id*="stop" i]',send:'button[aria-label="Send message" i],button[aria-label*="Send" i],button.send-button'},
 'claude.ai':{a:'.font-claude-message,[data-testid="assistant-message"]',b:'',stop:'button[aria-label*="Stop" i],[data-is-streaming="true"]',send:'button[data-testid="chat-input-send"],button[aria-label*="Send message" i],button[aria-label*="Send" i]'},
 'deepseek.com':{a:'.ds-markdown',b:'',stop:'',send:'[role="button"].ds-button--primary'},
 'grok.com':{a:'[class*="message-bubble"],[class*="response-content-markdown"]',b:'',stop:'button[aria-label*="Stop" i]',send:'button[type="submit"],button[aria-label*="Submit" i]'}
};
var __D={a:'[data-message-author-role="assistant"],.markdown,.prose',b:'',stop:'button[aria-label*="Stop" i]',send:'button[aria-label*="Send" i],button[type="submit"]'};
function __prof(){var h=location.hostname;for(var k in __P){if(h===k||h.endsWith('.'+k))return __P[k];}return __D;}
function __asst(p){var s=['[data-message-author-role="assistant"]','[data-message-role="assistant"]','[data-message-author="assistant"]','[data-role="assistant"]','article[data-turn="assistant"]','section[data-turn="assistant"]','[data-testid^="conversation-turn-"][data-turn="assistant"]','[data-testid^="conversation-turn-"]:has([data-message-role="assistant"])','.agent-turn',p.a,'[data-testid*="assistant" i]','model-response','.font-claude-message','.ds-markdown','message-content','[class*="response-content" i]','[class*="assistant-message" i]','[class*="assistant" i]'].filter(Boolean).join(',');var l=[].slice.call(document.querySelectorAll(s));return l.filter(function(e){var r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();return r.width>0&&r.height>0&&tx.length>0&&!l.some(function(o){return o!==e&&o.contains(e);});});}
function __reply(p){var l=__asst(p);if(l.length){var z=l[l.length-1];var inner=z.querySelector&&z.querySelector('.markdown,.prose,[class*="markdown"],[class*="prose"]');return inner||z;}var s=['article[data-turn="assistant"]','section[data-turn="assistant"]','[data-message-role="assistant"]','[data-testid^="conversation-turn-"][data-turn="assistant"]','[data-testid^="conversation-turn-"]:has([data-message-role="assistant"])','.agent-turn','.markdown','.prose','.ds-markdown','model-response','message-content','.font-claude-message','[class*="response-content" i]','[class*="markdown" i]'];var c=[];for(var i=0;i<s.length;i++){var a=[].slice.call(document.querySelectorAll(s[i]));for(var j=0;j<a.length;j++){var e=a[j],r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();if(r.width>0&&r.height>0&&tx.length>=30&&!c.some(function(o){return o!==e&&o.contains(e);}))c.push(e);}}if(!c.length)return null;c.sort(function(a,b){return a.compareDocumentPosition(b)&Node.DOCUMENT_POSITION_FOLLOWING?-1:1;});return c[c.length-1];}
function __stream(p){
 var stopSelectors=[p.stop||'', '[data-testid*="stop" i]', '[data-test-id*="stop" i]',
  'button[aria-label*="stop" i]', 'button[title*="stop" i]',
  '[data-is-streaming="true"]', '[aria-label*="cancel generation" i]'].filter(Boolean).join(',');
 try{var ss=[].slice.call(document.querySelectorAll(stopSelectors));for(var si=0;si<ss.length;si++){if(__vis(ss[si]))return 1;}}catch(e){}
 // Site-independent signal: the newest assistant text changed within the last 0.9 s => still generating.
 try{var al=__asst(p),lt=al.length?al[al.length-1]:null,ln=lt?(lt.innerText||lt.textContent||'').length:-1,g=window.__nsGrow,nw=Date.now();
  if(!g||g.n!==al.length||g.l!==ln){window.__nsGrow={n:al.length,l:ln,t:nw,init:!!g};if(g&&g.n===al.length&&ln>=0&&g.l>=0)return 1;}
  else if(g.init&&nw-g.t<900&&ln>0)return 1;}catch(e){}
 // Claude can expose a textual thinking/responding status without a reliable stop button.
 if(/claude\.ai$/i.test(location.hostname)){
  var tail=(document.body.innerText||'').slice(-6000);
  if(/\b(?:Claude\s+is\s+thinking|Claude\s+is\s+responding|Untangling)\b/i.test(tail))return 1;
 }
 return 0;
}
function __nrm(t){return String(t||'').replace(/\s+/g,' ').trim();}
function __markEls(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,out=[];while(n=w.nextNode()){if(n.nodeValue.indexOf('[001]')>=0&&n.parentElement)out.push(n.parentElement);}return out;}
function __gen(){var sent=window.__nsSentN||'',baseM=window.__nsBaseM||new Set(),baseT=window.__nsBaseT||new Set(),echo=[],cand=[];
 __markEls().forEach(function(e){if(baseM.has(e))return;var t=__nrm(e.innerText||e.textContent);if(!t||baseT.has(t))return;if(sent&&sent.indexOf(t.slice(0,50))>=0){echo.push(e);return;}cand.push(e);});
 if(!cand.length)return null;
 var best=null;
 for(var i=0;i<cand.length;i++){var e=cand[i],len=(e.innerText||e.textContent||'').length;
  for(var up=0;up<6;up++){var pa=e.parentElement;if(!pa||pa===document.body||pa===document.documentElement||pa.tagName==='MAIN')break;
   var pt=pa.innerText||pa.textContent||'';if(pt.length>len*3+500)break;
   if(sent&&sent.indexOf(__nrm(pt).slice(0,50))>=0)break;
   var bad=false;for(var j=0;j<echo.length&&!bad;j++){if(pa.contains(echo[j]))bad=true;}
   if(!bad){var bm=[].slice.call(baseM);for(var k=0;k<bm.length&&!bad;k++){if(pa.contains(bm[k]))bad=true;}}
   if(bad)break;e=pa;len=pt.length;}
  if(!best||e.contains(best)||!best.contains(e))best=e;}
 return best;}
function __esc(x){var sp='.*+?^{}()|[]\\/-'+String.fromCharCode(36),o='';for(var k=0;k<x.length;k++){var c=x.charAt(k);o+=(sp.indexOf(c)>=0?'\\'+c:c);}return o;}
function __scan(){var sent=window.__nsSentN;if(!sent)return null;var raw=document.body.innerText||'';if(raw.length>400000)raw=raw.slice(-400000);
 var j=raw.lastIndexOf('[001]');
 if(j>=0){var seg=raw.slice(j);if(sent.indexOf(__nrm(seg).slice(0,50))<0)return {got:1,how:'marker',text:seg,len:seg.length,found:__mk(seg)};}
 var all=__nrm(raw),tail=sent.slice(-60),i=all.lastIndexOf(tail);
 if(i>=0){var rest=all.slice(i+tail.length).trim();if(rest.length>0){var rr=rest,last=-1,end=0,mm;try{var re=new RegExp(__esc(tail).replace(/ /g,'\\s+'),'g');while((mm=re.exec(raw))){end=mm.index+mm[0].length;last=mm.index;if(mm[0].length===0)re.lastIndex++;}}catch(e){last=-1;}if(last>=0){var cand=raw.slice(end).trim();if(cand.length>0)rr=cand;}return {got:1,how:'echo',text:rr,len:rr.length,found:0};}}
 return {got:0,how:'none',text:'',len:0,found:0};}
function __uiClean(t){
 var s=String(t||'').replace(/\u00a0/g,' ');
 var rules=[
  /Message\s+collapsed(?:\s+Show\s+more)?(?:\s+just\s+now)?/ig,
  /\bShow\s+more\b/ig,
  /\bUntangling\b/ig,
  /\bClaude\s+is\s+thinking\b/ig,
  /\bClaude\s+is\s+responding\b/ig,
  /\bClaude\s+is\s+AI\s+and\s+can\s+make\s+mistakes\.?/ig,
  /\bWrite\s+a\s+message(?:\.{3}|…)?/ig,
  /\bHaiku\s+5\.5\s+Low\b/ig,
  /\bDeepThink(?:\s+Search)?\b/ig,
  /Action\s+completed\s+or\s+failed\.?\s*Please\s+check\s+the\s+page\s+and\s+try\s+again\.?/ig,
  /Claude\s+finished\s+the\s+response/ig,
  /OCR\s*[:：]?[^\n]*(?:screen capture|স্ক্রিন ক্যাপচার|খালি ফেরত এসেছে)[^\n]*/ig,
  /(?:Force extract|জোর করে বের করা হয়েছে)[^\n]*(?:screen capture|স্ক্রিন ক্যাপচার|খালি)[^\n]*/ig
 ];
 rules.forEach(function(r){s=s.replace(r,' ');});
 return s.replace(/[ \t]+/g,' ').replace(/ *\n */g,'\n').replace(/\n{3,}/g,'\n\n').trim();
}
function __trim(t){var s=__uiClean(t);var ls=s.split('\n'),tok=/(?:copy|share|regenerate|retry|like|dislike|edit|read aloud|show thinking|good response|bad response|sources?)\s*$/i,note=/can make mistakes|ai-generated|for reference only|check important info|double-check|generated by ai/i;
 while(ls.length){var l=ls[ls.length-1].trim();if(!l){ls.pop();continue;}var m=l.search(note);if(m>=0&&l.length-m<140){var cut=l.slice(0,m).replace(/[A-Za-z'’ ]*$/,'').trim();if(cut){ls[ls.length-1]=cut;continue;}ls.pop();continue;}var l2=l;while(tok.test(l2)){l2=l2.replace(tok,'').trim();}if(l2!==l){if(l2){ls[ls.length-1]=l2;}else{ls.pop();}continue;}if(/^\d+\s*\/\s*\d+$/.test(l)){ls.pop();continue;}break;}
 return ls.join('\n').trim();}
function __meaningful(t){
 var raw=String(t||'').replace(/\u00a0/g,' ');
 if(/action\s+completed\s+or\s+failed|please\s+check\s+the\s+page\s+and\s+try\s+again|finished\s+the\s+response|OCR\s*[:：]|screen\s+capture\s+came\s+back\s+blank|স্ক্রিন ক্যাপচার|খালি ফেরত এসেছে/i.test(raw))return false;
 var s=__uiClean(raw).replace(/\[\d{3,}\]/g,' ').replace(/[~～]+/g,' ').trim();
 s=s.replace(/[\s\-—:;,.!?(){}\[\]<>|*_]+/g,'');
 if(!s)return false;
 if(s.length<2)return false;
 if(/^(?:chatgpt|claude|deepseek|gemini|grok|share|copy|regenerate|retry|justnow|vieworiginal|writemessage|haiku55low|webtoonrealtime)$/i.test(s))return false;
 return /[A-Za-z\u0980-\u09FF\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]/.test(s);
}
function __fixm(t){return String(t||'').replace(/\*{1,2}(\[\d{3}\])\*{1,2}/g,function(m,a){return a;}).replace(/([^\n\s])[ \t]*(\[\d{3}\])/g,function(m,a,b){return a+'\n\n'+b;});}
function __ntext(p,e){
 var i=(p.b&&e.querySelector(p.b))||e.querySelector('.markdown,.prose,[class*="markdown"],[class*="prose"]')||e;
 try{
  var c=i.cloneNode(true);
  var remove='button,[role="button"],nav,aside,svg,script,style,noscript,textarea,input,time,[class*="thinking"],[class*="reasoning"],[class*="thought"],[class*="think"],[data-testid*="thinking"],[data-testid*="reasoning"],[data-testid*="thought"],[data-testid*="think"],[aria-label*="thinking"],[aria-label*="reasoning"],[aria-label*="think"]';
  c.querySelectorAll(remove).forEach(function(x){x.remove();});
  [].slice.call(c.querySelectorAll('*')).reverse().forEach(function(x){
   var t=__nrm(x.innerText||x.textContent);
   if(t.length<220&&/^(?:Message collapsed(?: Show more)?(?: just now)?|Show more|just now|Untangling(?: Untangling)?|Claude is thinking|Claude is responding|Write a message(?:\.{3}|…)?|Claude is AI and can make mistakes\.?|Haiku 5\.5 Low|DeepThink(?: Search)?)$/i.test(t))x.remove();
  });
  return __uiClean(c.innerText||c.textContent||'');
 }catch(err){return __uiClean(i.innerText||i.textContent||'');}
}
function __new(p,l){var base=window.__nsBase||[],bm=new Map(),bt=new Set();base.forEach(function(x){bm.set(x.el,x.t);bt.add(x.t);});var now=l||__asst(p),hit=null;for(var i=0;i<now.length;i++){var e=now[i],t=(e.innerText||e.textContent||'').trim(),o=bm.get(e);if(o===undefined){if(bt.has(t))continue;hit=e;}else if(t!==o){hit=e;}}return hit||__gen();}
function __mk(t){var m=t.match(/\[\d{3}\]/g)||[],s={},c=0;for(var i=0;i<m.length;i++){if(!s[m[i]]){s[m[i]]=1;c++;}}return c;}
function __trk(){var bn=window.__nsBaseNodes,bt=window.__nsBaseTexts,sent=window.__nsSentN;if(!bn||!sent||!document.body)return null;
 var skip='button,nav,aside,form,textarea,input,[contenteditable],[role="button"],[role="menu"],script,style,noscript,svg';
 var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,parts=[];
 while(n=tw.nextNode()){if(bn.has(n))continue;var v=n.nodeValue;if(!v||!v.trim())continue;var pe=n.parentElement;if(!pe)continue;
  try{if(pe.closest(skip))continue;}catch(e){}
  var t=__nrm(v);if(bt&&bt.has(t))continue;
  if(t.length>=12&&(sent.indexOf(t)>=0||(t.length>=40&&sent.indexOf(t.slice(0,40))>=0)))continue;
  parts.push(v);}
 var text=parts.join('\n');return {len:text.length,found:__mk(text),text:text};}
function __vis(b){if(!b||!b.isConnected)return false;var s=getComputedStyle(b),r=b.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}
function __ok(b){return __vis(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true';}
function __ctls(box){var out=[],seen=new Set(),c=box.parentElement,prev=[];for(var up=0;c&&up<7;up++,c=c.parentElement){var l=[].slice.call(c.querySelectorAll('button,[role="button"]'));if(l.length>14)break;prev=l;}prev.forEach(function(x){if(!seen.has(x)){seen.add(x);out.push(x);}});return out;}
function __snap(box){var m=new Map();__ctls(box).forEach(function(b){m.set(b,__ok(b));});return m;}
function __diffSend(box,snap){var cand=__ctls(box).filter(function(b){if(!__ok(b)||b.querySelector('input[type="file"]'))return false;var was=snap&&snap.get(b);return was===undefined||was===false;});return cand.length?cand[cand.length-1]:null;}
function __denyBtn(b){
 var n=String((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.getAttribute('data-testid')||'')+' '+(b.getAttribute('data-test-id')||'')+' '+String(b.innerText||'').slice(0,24)).toLowerCase();
 return /\b(voice|microphone|mic|dictation|audio|record|stop|cancel|pause|interrupt|abort|attach|attachment|upload|file|photo|image|camera|search|deep.?think|reasoning|plus|menu|settings|model|tools?)\b/.test(n);
}
// Last-resort Send finder for sites whose Send button has no label (e.g. DeepSeek icon button):
// 1) a control that became usable after the text was inserted, 2) the right-most usable control to the right of the editor's centre.
function __guessSend(box,snap){
 // Unlabelled Send buttons (DeepSeek and similar icon-only controls).
 // Safe rule: only a control that APPEARED or became ENABLED after the text was inserted can be
 // Send. A microphone/voice/attach control was already usable before, so it is never picked.
 try{
  if(!box||!box.isConnected||!snap)return null;
  var c=__ctls(box).filter(function(b){
   if(!__ok(b)||b.querySelector('input[type=\"file\"]')||__denyBtn(b))return false;
   var was=snap.get(b);
   return was===undefined||was===false;
  });
  if(!c.length)return null;
  var xr=box.getBoundingClientRect();
  c.sort(function(a,b){
   var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();
   return (Math.abs(ra.right-xr.right)+Math.abs(ra.bottom-xr.bottom))-(Math.abs(rb.right-xr.right)+Math.abs(rb.bottom-xr.bottom));
  });
  return c[0];
 }catch(e){return null;}
}
// Last resort, used ONLY for a native tap after the composer is proven to hold the full text:
// the right-most, bottom-most icon control of the composer that is not a known utility control.
function __guessSendLoose(box){
 try{
  if(!box||!box.isConnected)return null;
  var xr=box.getBoundingClientRect();
  var c=__ctls(box).filter(function(b){
   if(!__ok(b)||b.querySelector('input[type=\"file\"]')||__denyBtn(b))return false;
   var r=b.getBoundingClientRect();
   if(r.width>96||r.height>96)return false;
   return r.left>=xr.left+xr.width*0.5-8;
  });
  if(!c.length)return null;
  c.sort(function(a,b){
   var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();
   return (rb.right+rb.bottom*0.25)-(ra.right+ra.bottom*0.25);
  });
  return c[0];
 }catch(e){return null;}
}
function __pressFull(b){
 if(!b||!b.isConnected||!__ok(b))return false;
 try{b.scrollIntoView({block:'nearest'});}catch(e){}
 try{b.focus();}catch(e){}
 // A single semantic click avoids synthetic touch/mouse events being treated as
 // a separate microphone/voice action by mobile composer controls.
 try{b.click();return true;}catch(e){return false;}
}
function __findSend(p,box,snap){
 if(!box||!box.isConnected)return null;
 var form=null;try{form=box.closest('form');}catch(e){}
 var sels=[];
 if(p.send)sels.push(p.send);
 sels.push(
  'button[data-testid="send-button"]',
  'button[data-testid*="send" i]',
  '[data-testid*="send" i][role="button"]',
  'button[data-test-id*="send" i]',
  '[data-test-id*="send" i][role="button"]',
  'button[aria-label*="send" i]',
  '[role="button"][aria-label*="send" i]',
  'button[title*="send" i]',
  '[role="button"][title*="send" i]',
  'button[aria-label*="submit" i]',
  '[role="button"][aria-label*="submit" i]'
 );
 function label(b){
  return String((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+
   (b.getAttribute('data-testid')||'')+' '+(b.getAttribute('data-test-id')||'')+' '+
   (b.innerText||b.textContent||'')+' '+(typeof b.className==='string'?b.className:'')).toLowerCase();
 }
 function okSend(b){
  if(!__ok(b)||b.querySelector('input[type="file"]'))return false;
  var t=label(b);
  // Never click a voice/microphone or other composer utility control.
  // Only the accessible name is checked (not CSS classes), so a class such as "file-x" cannot veto the real Send button.
  if(__denyBtn(b))return false;
  // Explicitly labelled Send/Submit, or known send-button test IDs only.
  return /\b(send|submit)\b/.test(t)||/send-button/.test(t);
 }
 var all=[],seen=new Set();
 function add(root,sel){
  try{[].slice.call(root.querySelectorAll(sel)).forEach(function(b){
   if(!seen.has(b)&&okSend(b)){seen.add(b);all.push(b);}
  });}catch(e){}
 }
 if(form)for(var i=0;i<sels.length;i++)add(form,sels[i]);
 for(var i=0;i<sels.length;i++)add(document,sels[i]);
 if(!all.length&&form){
  var sub=[];try{sub=[].slice.call(form.querySelectorAll('button[type="submit"],input[type="submit"]'));}catch(e){}
  sub=sub.filter(okSend);all=all.concat(sub);
 }
 if(!all.length)return null;
 var xr=box.getBoundingClientRect();
 function score(b){
  var r=b.getBoundingClientRect(),q=Math.abs(r.right-xr.right)+Math.abs(r.top-xr.bottom);
  if(form&&form.contains(b))q-=10000;
  if(/send-button/.test(label(b)))q-=500;
  return q;
 }
 all.sort(function(a,b){return score(a)-score(b);});
 return all[0];
}
function __press(b){
 if(!b||!b.isConnected||!__ok(b))return false;
 try{b.focus();}catch(e){}
 try{b.click();return true;}catch(e){return false;}
}
function __box(){
 // DeepSeek may use a transparent native textarea under a visible backdrop.
 // Do not reject a real editor just because it carries aria-hidden/aria-disabled.
 var sels=['#prompt-textarea','textarea','input:not([type="hidden"])',
  '[contenteditable="true"]','[contenteditable="plaintext-only"]','[contenteditable]',
  '[role="textbox"]','[data-placeholder*="Message" i]','[placeholder*="Message" i]'];
 var c=[],seen=new Set();
 for(var i=0;i<sels.length;i++){
  try{
   var a=[].slice.call(document.querySelectorAll(sels[i]));
   for(var j=0;j<a.length;j++){
    var e=a[j]; if(seen.has(e)||e.disabled)continue;
    var r=e.getBoundingClientRect();
    if(r.width<=0||r.height<=0)continue;
    seen.add(e);c.push(e);
   }
  }catch(e){}
 }
 if(!c.length)return null;
 var active=document.activeElement;
 if(active&&c.indexOf(active)>=0)return active;
 function priority(e){
  var v=String((e.id||'')+' '+(e.getAttribute('placeholder')||'')+' '+
   (e.getAttribute('data-placeholder')||'')+' '+(e.getAttribute('aria-label')||'')+' '+
   (e.getAttribute('data-testid')||'')+' '+(typeof e.className==='string'?e.className:'')).toLowerCase();
  var n=0;
  if(e.id==='prompt-textarea'||e.getAttribute('data-testid')==='prompt-textarea')n+=1000;
  if(/message|composer|prompt|chat.?input|text.?area/.test(v))n+=100;
  if(e.matches&&e.matches('textarea,[contenteditable="true"],[contenteditable="plaintext-only"],[role="textbox"]'))n+=20;
  return n;
 }
 c.sort(function(a,b){
  var d=priority(b)-priority(a);if(d)return d;
  return b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom;
 });
 return c[0];
}
"""

    private fun run(body: String, realtimeFast: Boolean = false): String {
        // The generic streaming probe treats the newest text changing in the last 900 ms as active.
        // For live OCR batches, shorten that quiet window so a finished answer is copied/applied quickly;
        // ordinary chapter translations retain the conservative 900 ms detector.
        val prelude = if (realtimeFast) PRELUDE.replace("nw-g.t<900", "nw-g.t<250") else PRELUDE
        return prelude + "\n;" + body
    }

    private const val SEND_BODY = """
(function(text,doSend){
  var p=__prof(); var before=__asst(p); var n0=before.length; var len0=0;
  window.__nsBase=before.map(function(e){return {el:e,t:(e.innerText||e.textContent||'').trim()};});
  var old=__reply(p);
  if(old){var ob=p.b?(old.querySelector(p.b)||old):old;len0=((ob.innerText||ob.textContent||'').trim().length);}
  if(!len0&&n0){var old2=before[n0-1];var ob2=p.b?(old2.querySelector(p.b)||old2):old2;len0=((ob2.innerText||ob2.textContent||'').trim().length);}
  // Never put a new text into the composer while this chat is still producing an answer.
  var holdMs=__HOLD__, forceSend=__FORCE__, preferNative=__NATIVE__;
  if(doSend&&!forceSend){try{if(__stream(p))return 'busy';}catch(e){}}
  var box=__box();
  if(!box) return 'nobox';
  // Keep the soft keyboard down: while the app drives the composer it is marked inputmode=none,
  // so the focus() calls below cannot open the IME. It is restored when the send is over or on a real touch.
  var __imeGen=(window.__nsImeGen=(window.__nsImeGen||0)+1);
  function __nsImeOff(el){try{if(!el||el.__nsImeSaved!==undefined)return;el.__nsImeSaved=el.getAttribute('inputmode');el.setAttribute('inputmode','none');window.__nsImeEl=el;}catch(e){}}
  function __nsImeOn(el){try{if(!el||el.__nsImeSaved===undefined)return;var sv=el.__nsImeSaved;el.__nsImeSaved=undefined;if(sv===null)el.removeAttribute('inputmode');else el.setAttribute('inputmode',sv);}catch(e){}}
  window.__nsImeRestoreNow=function(){try{__nsImeOn(window.__nsImeEl);__nsImeOn(box);}catch(e){}};
  function __nsImeLater(ms){setTimeout(function(){if(window.__nsImeGen===__imeGen)window.__nsImeRestoreNow();},ms);}
  __nsImeOff(box);
  box.focus();
  var snap=__snap(box);window.__nsSnap=snap;
  window.__nsSentN=__nrm(text);
  try{var __bs=new WeakSet(),__bt=new Set(),__tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),__nn;
    while(__nn=__tw.nextNode()){__bs.add(__nn);var __x=__nrm(__nn.nodeValue);if(__x.length>=20)__bt.add(__x);}
    window.__nsBaseNodes=__bs;window.__nsBaseTexts=__bt;window.__nsSentAt=Date.now();}catch(e){window.__nsBaseNodes=null;window.__nsBaseTexts=null;}
  try{var me=__markEls();window.__nsBaseM=new Set(me);window.__nsBaseT=new Set(me.map(function(e){return __nrm(e.innerText||e.textContent);}));}catch(e){window.__nsBaseM=new Set();window.__nsBaseT=new Set();}
  function boxText(){return String((box.tagName==='TEXTAREA'||box.tagName==='INPUT')?box.value:(box.innerText||box.textContent)||'').trim();}
  if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){
    var proto=box.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    var setter=Object.getOwnPropertyDescriptor(proto,'value').set;
    setter.call(box,'');
    box.dispatchEvent(new Event('input',{bubbles:true}));
    setter.call(box,text);
    try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){box.dispatchEvent(new Event('input',{bubbles:true}));}
    box.dispatchEvent(new Event('change',{bubbles:true}));
  } else {
    // Prefer a browser editing transaction so React/ProseMirror editors register
    // the text as genuine input, rather than seeing only a visual textContent change.
    var inserted=false;
    try{
      box.focus();
      var sel=window.getSelection(),range=document.createRange();
      range.selectNodeContents(box);
      sel.removeAllRanges();sel.addRange(range);
      inserted=document.execCommand('insertText',false,text) &&
        __nrm(boxText())===__nrm(text);
    }catch(e){inserted=false;}
    if(!inserted){
      // Fallback for editors where the browser editing command is unavailable.
      try{
        box.focus();
        try{box.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){}
        box.textContent=text;
        try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}
        catch(e){box.dispatchEvent(new Event('input',{bubbles:true}));}
        box.dispatchEvent(new Event('change',{bubbles:true}));
      }catch(e){}
    }
  }
  function __nsShowSendWait(){
    try{
      var old=document.getElementById('__ns_send_wait');
      if(old)old.remove();
      var box=__box();if(!box)return;
      var wrap=document.createElement('div');wrap.id='__ns_send_wait';
      wrap.setAttribute('aria-hidden','true');
      wrap.style.cssText='position:fixed;z-index:2147483647;right:14px;bottom:calc(env(safe-area-inset-bottom,0px) + 86px);display:flex;align-items:center;gap:8px;padding:7px 11px;border-radius:18px;background:rgba(25,25,28,.92);color:#fff;font:500 12px system-ui,sans-serif;box-shadow:0 2px 10px rgba(0,0,0,.22);pointer-events:none;opacity:0;transform:translateY(4px);transition:opacity .12s ease,transform .12s ease';
      wrap.innerHTML='<span>Sending</span><span style="display:flex;gap:3px"><i style="display:block;width:4px;height:4px;border-radius:50%;background:#fff;animation:__ns_send_dot .8s infinite ease-in-out"></i><i style="display:block;width:4px;height:4px;border-radius:50%;background:#fff;animation:__ns_send_dot .8s .15s infinite ease-in-out"></i><i style="display:block;width:4px;height:4px;border-radius:50%;background:#fff;animation:__ns_send_dot .8s .3s infinite ease-in-out"></i></span>';
      if(!document.getElementById('__ns_send_wait_css')){
        var css=document.createElement('style');css.id='__ns_send_wait_css';css.textContent='@keyframes __ns_send_dot{0%,60%,100%{opacity:.35;transform:translateY(0)}30%{opacity:1;transform:translateY(-3px)}}';(document.head||document.documentElement).appendChild(css);
      }
      (document.body||document.documentElement).appendChild(wrap);
      requestAnimationFrame(function(){wrap.style.opacity='1';wrap.style.transform='translateY(0)';});
      window.__nsHideSendWait=function(){try{var x=document.getElementById('__ns_send_wait');if(x){x.style.opacity='0';x.style.transform='translateY(4px)';setTimeout(function(){if(x.isConnected)x.remove();},140);}}catch(e){}};
    }catch(e){}
  }
  function __nsHideSendWait(){try{if(window.__nsHideSendWait)window.__nsHideSendWait();else{var x=document.getElementById('__ns_send_wait');if(x)x.remove();}}catch(e){}}
  if(doSend){
    // Do not press Send until the editor has committed the full text and the control is enabled.
    // The first readiness check happens 300 ms after insertion; retries only re-check readiness,
    // they never submit more than once.
    window.__nsSendState='pending';
    var tailN=__nrm(text).slice(-60);
    function occ(){var a=__nrm(document.body.innerText||''),c=0,i=-1;while((i=a.indexOf(tailN,i+1))>=0)c++;return c;}
    var baseOcc=occ();
    var expected=__nrm(text);
    function boxText(){return String((box.tagName==='TEXTAREA'||box.tagName==='INPUT')?box.value:(box.innerText||box.textContent)||'').trim();}
    function composerMatches(){return box.isConnected&&__nrm(boxText())===expected;}
    function sentOk(){
      var r=boxText();
      return (!r||r.length<Math.min(text.trim().length,20)) || (tailN.length>0&&occ()>baseOcc);
    }
    var started=Date.now();
    var readyDeadline=started+300;
    var landedAt=0;                  // when the text was first seen committed in the editor (start of the hold)   // same worst case as before, but Send is pressed the moment it is enabled
    var textRepairAttempts=0;
    function keyEnter(){
      try{box.focus();}catch(e){}
      var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
      ['keydown','keypress','keyup'].forEach(function(k){try{box.dispatchEvent(new KeyboardEvent(k,o));}catch(e){}});
    }
    function requestComposerSubmit(){
      try{
        var f=box.closest('form');
        if(f&&typeof f.requestSubmit==='function'){f.requestSubmit();return true;}
      }catch(e){}
      return false;
    }
    function confirmSend(method){
      var t0=Date.now();
      (function chk(){
        var streaming=0;try{streaming=__stream(p);}catch(e){}
        if(sentOk()||streaming){window.__nsSendState='sent:'+method;__nsImeLater(1500);return;}
        if(Date.now()-t0<3500){setTimeout(chk,120);return;}
        __nsHideSendWait();window.__nsSendState='failed';__nsImeLater(6000);
      })();
    }
    // A freshly opened conversation can remount its editor after the first input event.
    // Re-find the live composer and replace the app-owned prompt once before giving up.
    function repairComposerText(){
      try{
        var live=__box();
        if(live&&live.isConnected)box=live;
        if(!box||!box.isConnected)return false;
        __nsImeOff(box);
        box.focus();
        if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){
          var proto=box.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
          var setter=Object.getOwnPropertyDescriptor(proto,'value').set;
          setter.call(box,text);
          try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}
          catch(e){box.dispatchEvent(new Event('input',{bubbles:true}));}
          box.dispatchEvent(new Event('change',{bubbles:true}));
        }else{
          var insertedAgain=false;
          try{
            var sel=window.getSelection(),range=document.createRange();
            range.selectNodeContents(box);sel.removeAllRanges();sel.addRange(range);
            insertedAgain=document.execCommand('insertText',false,text)&&__nrm(boxText())===expected;
          }catch(e){}
          if(!insertedAgain){
            try{box.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){}
            box.textContent=text;
            try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}
            catch(e){box.dispatchEvent(new Event('input',{bubbles:true}));}
            box.dispatchEvent(new Event('change',{bubbles:true}));
          }
        }
        if(!snap){snap=__snap(box);window.__nsSnap=snap;}
        return composerMatches();
      }catch(e){return false;}
    }
    function waitUntilReady(){
      if(!box||!box.isConnected){
        var live=__box();
        if(live&&live.isConnected){box=live;snap=__snap(box);window.__nsSnap=snap;}
        else if(Date.now()-started<7000){setTimeout(waitUntilReady,150);return;}
        else{__nsHideSendWait();window.__nsSendState='failed:composer-detached';__nsImeLater(6000);return;}
      }
      // Hard gate: no send action until the full prompt is committed to the live editor.
      var matches=composerMatches();
      if(!matches){
        if(textRepairAttempts<3&&Date.now()-started>=650+textRepairAttempts*900){
          textRepairAttempts++;
          repairComposerText();
          __nsShowSendWait();
    setTimeout(waitUntilReady,180);
        }else if(Date.now()-started<7000){
          setTimeout(waitUntilReady,150);
        }else{
          __nsHideSendWait();window.__nsSendState='failed:text-not-committed';__nsImeLater(6000);
        }
        return;
      }
      // User-set pause between "text is in the box" and the Send click (0 = no pause).
      if(!landedAt){landedAt=Date.now();readyDeadline=Math.max(readyDeadline,landedAt+holdMs+300);}
      if(holdMs>0&&Date.now()-landedAt<holdMs){setTimeout(waitUntilReady,Math.max(5,Math.min(40,holdMs-(Date.now()-landedAt)+1)));return;}
      var btn=__findSend(p,box,snap);
      if(btn&&__ok(btn)){
        // Learned for this site: the page ignores script clicks, so hand over to the native tap at once.
        if(preferNative&&doSend){__nsHideSendWait();window.__nsSendState='failed';__nsImeLater(6000);return;}
        if(__pressFull(btn)){
          __nsHideSendWait();
          confirmSend('btn');
          return;
        }
      }
      // Send button has no usable label (DeepSeek and similar): the control that became enabled
      // once text was inserted, or the right-most control beside the editor.
      var g=__guessSend(box,snap);
      if(g){
        if(preferNative&&doSend){__nsHideSendWait();window.__nsSendState='failed';__nsImeLater(6000);return;}
        if(__pressFull(g)){
          __nsHideSendWait();
          confirmSend('guess');
          return;
        }
      }
      // Text is in but no enabled Send control yet: re-check every 40 ms until the old 300 ms mark.
      if(Date.now()<readyDeadline){setTimeout(waitUntilReady,20);return;}
      // Last resort: the site's keyboard/form mechanism.
      if(requestComposerSubmit()){
        __nsHideSendWait();
        confirmSend('submit');
      }else{
        keyEnter();
        __nsHideSendWait();
        confirmSend('enter');
      }
    }
    // First readiness check after 40 ms (was a blind 300 ms wait); waitUntilReady keeps polling until
    // the editor has committed the text and Send is enabled.
    setTimeout(waitUntilReady,10);
  }
  if(!doSend)__nsImeLater(1200);
  return 'ok:'+n0+':'+len0;
})(__TEXT__,__SEND__)
"""

    // type the text into the chat box (and press send if asked). Returns "ok:<assistant message count>" or "nobox"
    fun send(text: String, doSend: Boolean, force: Boolean = false, preferNative: Boolean = false): String =
        run(
            SEND_BODY.replace("__TEXT__", org.json.JSONObject.quote(text)).replace("__SEND__", doSend.toString())
                .replace("__HOLD__", sendHoldMs.coerceIn(0L, 15_000L).toString()).replace("__FORCE__", force.toString())
                .replace("__NATIVE__", preferNative.toString())
        )

    /** Send status plus elapsed milliseconds, so a queued job can wait for a just-submitted message. */
    fun sendState(): String = run(
        "(function(){var t=Number(window.__nsSentAt||0);return String(window.__nsSendState||'idle')+'|'+(t?Date.now()-t:-1);})()"
    )

    /** Centre of the best Send candidate in WebView pixels ("x|y"), or "none". Used for a trusted native tap. */
    fun sendButtonRect(): String = run(
        "(function(){try{var p=__prof(),box=__box();if(!box)return 'none';var snap=window.__nsSnap||null;" +
        "var b=__findSend(p,box,snap)||__guessSend(box,snap);" +
        "if(!b){var cur=String((box.tagName==='TEXTAREA'||box.tagName==='INPUT')?box.value:(box.innerText||box.textContent)||'').trim();" +
        "var exp=String(window.__nsSentN||'');if(exp&&cur.length>=Math.min(exp.length,20))b=__guessSendLoose(box);}" +
        "if(!b)return 'none';" +
        "try{b.scrollIntoView({block:'nearest'});}catch(e){}" +
        "var r=b.getBoundingClientRect(),vv=window.visualViewport,dpr=window.devicePixelRatio||1;" +
        "var x=(r.left+r.width/2-(vv?vv.offsetLeft:0))*dpr,y=(r.top+r.height/2-(vv?vv.offsetTop:0))*dpr;" +
        "return Math.round(x)+'|'+Math.round(y);}catch(e){return 'none';}})()"
    )

    /** "1" when the composer was emptied or a reply has started, i.e. the message really went out. */
    fun sendLanded(): String = run(
        "(function(){try{var box=__box(),p=__prof();var t=box?String((box.tagName==='TEXTAREA'||box.tagName==='INPUT')?box.value:(box.innerText||box.textContent)||'').trim():'';" +
        "var exp=String(window.__nsSentN||'');if(!box||t.length<Math.min(exp.length,20)||__stream(p))return '1';return '0';}catch(e){return '0';}})()"
    )

    /** Clear only the send state after a reply is fully consumed; keep timestamps for readLen fallbacks. */
    fun clearSendState(): String = run(
        "(function(){window.__nsSendState='idle';return 'ok';})()"
    )

    /** Returns 1 only after a live, visible composer exists; used before rollover/paste. */
    fun composerReady(): String = run(
        "(function(){try{var b=__box();if(!b)return '0';var r=b.getBoundingClientRect(),s=getComputedStyle(b);" +
        "return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&" +
        "!b.disabled&&b.getAttribute('aria-disabled')!=='true'?'1':'0';}catch(e){return '0';}})()"
    )

    // "<assistant msg count>|<streaming 0/1>|<length of last reply>"
    // "<assistant msg count>|<streaming 0/1>|<length of THIS job's reply>|<distinct [NNN] markers seen>|<new reply seen 0/1>"
    // "This job's reply" = an assistant node that is new, or whose text changed, since send().
    // "<assistant msg count>|<streaming 0/1>|<length of THIS job's reply>|<distinct [NNN] markers seen>|<new reply seen 0/1>"
    // 1) page-text scan (no selectors: works on any chatbot), 2) element detection as a fallback.
    fun readLen(realtimeFast: Boolean = false): String = run("(function(){var p=__prof();var l=null;var nowT=Date.now();var tk=__trk();var tkOn=!!window.__nsBaseNodes;var tkGot=!!tk&&tk.len>=20;var useOld=!tkOn||(!tkGot&&(Date.now()-(window.__nsSentAt||0))>12000);var sc=useOld?__scan():null;var len=0,found=0,got=0,replyText='';if(tkGot){got=1;len=tk.len;found=tk.found;replyText=tk.text||'';}else if(tk&&tk.len>0&&!useOld){got=1;len=tk.len;found=tk.found;replyText=tk.text||'';}else if(useOld){if(sc&&sc.got){got=1;len=sc.len;found=sc.found;replyText=sc.text||'';}else{if(!l)l=__asst(p);var h=__new(p,l);if(h){var t=__ntext(p,h);got=1;len=t.length;found=__mk(t);replyText=t;}else{var e=__reply(p);var b=e&&(p.b?(e.querySelector(p.b)||e):e);replyText=b?((b.innerText||b.textContent||'').trim()):'';len=replyText.length;}}}var useful=__meaningful(replyText)?1:0;var filled=0;try{var mkc=(String(replyText).match(/\\[\\d{3,}\\]/g)||[]).length;var segs=String(replyText).split(/\\[\\d{3,}\\]/).slice(1);filled=(mkc>0&&segs.length>=mkc&&segs.every(function(x){return x.replace(/[\\s\\u00a0]+/g,'').length>0;}))?1:0;}catch(e){}var stop=__stream(p);var sendReady=0;try{var sb=p.send?document.querySelector(p.send):null;sendReady=__ok(sb)?1:0;if(!sendReady){var box=__box();var ct=box&&__ctls(box);sendReady=ct&&ct.some(function(b){return __ok(b)&&/send/i.test((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.getAttribute('data-testid')||''))})?1:0;}}catch(e){}var done=(got&&(len>50||filled)&&!stop&&sendReady&&(useful||filled))?1:0;var cnt;if(!l&&tkGot&&window.__nsAsstT&&nowT-window.__nsAsstT<3000){cnt=window.__nsAsstN;}else{if(!l)l=__asst(p);cnt=l.length;window.__nsAsstN=cnt;window.__nsAsstT=nowT;}return cnt+'|'+stop+'|'+len+'|'+found+'|'+got+'|'+(window.__nsSendState||'')+'|'+done+'|'+useful+'|'+filled;})()", realtimeFast)

    // Final reply text. Markers are forced to the start of a line so the paragraph split works
    // even when the chatbot put several paragraphs on one line.
    fun readText(): String = run("(function(){var p=__prof();var sc=__scan();var h=__new(p);var et=h?__ntext(p,h):'';var out='';if(sc&&sc.got&&sc.how==='marker'){out=(et&&__mk(et)>=sc.found&&et.length<=sc.len+80)?et:__trim(sc.text);}else if(et){out=et;}else if(sc&&sc.got&&sc.text){out=__trim(sc.text);}else{var e=__reply(p);if(e){var b=p.b?(e.querySelector(p.b)||e):e;out=(b.innerText||b.textContent||'').trim();}}return __fixm(out);})()")

    // Report for the menu: what each detector sees on the chatbot page.
    fun diagChat(): String = run("(function(){var p=__prof(),o=[];o.push('host: '+location.hostname+' | profile: '+(p===__D?'DEFAULT (unknown site)':'known'));var box=__box();o.push('composer: '+(box?(box.tagName+'#'+(box.id||'')+'.'+String(box.className).slice(0,60)):'NOT FOUND'));if(box){var cs=__ctls(box);o.push('controls near composer ('+cs.length+'):');cs.slice(0,14).forEach(function(b,i){var c=b.className&&b.className.baseVal!==undefined?b.className.baseVal:b.className;o.push(' '+i+' '+b.tagName+' role='+(b.getAttribute('role')||'')+' aria='+(b.getAttribute('aria-label')||'')+' dis='+(b.getAttribute('aria-disabled')||b.disabled||'')+' ok='+(__ok(b)?1:0)+' txt='+String(b.innerText||'').trim().slice(0,12)+' cls='+String(c).slice(0,50));});}var sb=null;try{sb=p.send?document.querySelector(p.send):null;}catch(e){}o.push('profile send selector: '+(p.send||'-')+' -> '+(sb?'found':'no'));var st=null;try{st=p.stop?document.querySelector(p.stop):null;}catch(e){}o.push('stop selector '+(p.stop||'-')+' -> '+(st?'ACTIVE':'none'));var l=__asst(p);o.push('assistant nodes (profile): '+l.length);l.slice(-2).forEach(function(e){o.push('  '+e.tagName+'.'+String(e.className).slice(0,50)+' len='+(e.innerText||'').length);});var raw=document.body.innerText||'';o.push('body text len='+raw.length+' | [001] occurrences='+((raw.match(/\\[001\\]/g)||[]).length));var sc=__scan();o.push('scan: '+(sc?(sc.how+' got='+sc.got+' found='+sc.found+' len='+sc.len):'no prompt sent yet in this page'));if(sc&&sc.text)o.push('scan start: '+sc.text.slice(0,80).replace(/\\s+/g,' ')+' ... end: '+sc.text.slice(-120).replace(/\\s+/g,' '));var h=__new(p);o.push('element reply: '+(h?(h.tagName+'.'+String(h.className).slice(0,50)+' markers='+__mk(__ntext(p,h))):'none'));return o.join('\n');})()")

    fun stop(): String = run("(function(){var p=__prof();var b=p.stop?document.querySelector(p.stop):null;if(b&&b.tagName==='BUTTON')b.click();return 'k';})()")

    // wipes a long leftover text (previous chapter) from the chat box
    fun clearBox(): String = run("(function(){var b=__box();if(!b)return 'n';var t=(b.value!==undefined?b.value:b.innerText)||'';if(t.length<150)return 's';b.focus();if(b.tagName==='TEXTAREA'||b.tagName==='INPUT'){Object.getOwnPropertyDescriptor(b.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(b,'');b.dispatchEvent(new Event('input',{bubbles:true}));}else{document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);}return 'c';})()")

    // Verify that the live novel container still contains our inserted translation.
    fun isAppliedTranslation(expected: String): String =
        run("(function(expected){var el=window.__nsEl;if(!window.__nsShown||!el||!document.contains(el)||el.getAttribute('data-ns')!=='1')return '0';" +
            "function n(t){return String(t||'').replace(/\\u00a0/g,' ').replace(/\\s+/g,' ').trim();}" +
            "var actual=n(el.innerText||el.textContent),wanted=n(expected);return actual&&wanted&&actual===wanted?'1':'0';})(" +
            org.json.JSONObject.quote(expected) + ")")

    // ---------------------------------------------------------------- novel page: replace text / toggle
    // Marker-aware DOM writer: map each translated [NNN] block back to the
    // exact original DOM element whose source text was extracted. This prevents
    // "first translation -> first <p>" drift when the AI merges/reorders formatting.
    private const val APPLY_MAPPED_BODY = """
(function(sel,translation,sourceJson,font,size,pageId){
  function norm(s){return String(s||'').replace(/\u00a0/g,' ').replace(/\s+/g,' ').trim();}
  function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
  var source=[];
  try{source=JSON.parse(sourceJson||'[]');}catch(e){source=[];}

  // Resolve the same chapter root used by the normal writer.
  var el=null;
  try{if(sel)el=document.querySelector(sel);}catch(e){}
  if(!el){
    var sels=['#chapter-content','.chapter-content','.chapter_content','#chr-content','.chr-c',
      '.reading-content','.text-left','#content','.entry-content','.cha-content','.cha-words',
      '.chapter-body','.novel_content','.j_readContent','.txt','#chaptercontent','.chapter-c',
      '#article','.article-content','.content','article','main'];
    var best=null,bs=0;
    for(var i=0;i<sels.length;i++){
      var es=[];try{es=[].slice.call(document.querySelectorAll(sels[i]));}catch(e){es=[];}
      for(var j=0;j<es.length;j++){
        var x=es[j],tx=norm(x.innerText||x.textContent);
        if(tx.length<80)continue;
        var p=x.querySelectorAll('p').length;
        var sc=tx.length+p*250;
        if(sc>bs){bs=sc;best=x;}
      }
    }
    el=best;
  }
  if(!el)return 'noel';

  // WTR-LAB has multiple preloaded chapter bodies. Prefer the active body.
  if(location.hostname.toLowerCase().indexOf('wtr-lab.com')>=0){
    try{
      var tr=document.querySelector('.chapter-tracker.active[data-chapter-no]')||document.querySelector('.chapter-tracker.active');
      var no=tr&&(tr.getAttribute('data-chapter-no')||'');
      var m=location.pathname.match(/\/chapter-(\d+)\/?$/i);
      if(m)no=m[1];
      if(no){
        var q=['#chapter-'+no+' .chapter-body','#tracker-'+no+' .chapter-body',
          '.chapter-tracker[data-chapter-no="'+no+'"] .chapter-body','[data-chapter-no="'+no+'"] .chapter-body'];
        for(var qi=0;qi<q.length&&!el;qi++){try{el=document.querySelector(q[qi]);}catch(e){}}
      }
    }catch(e){}
  }

  var fam='';
  if(font&&font.length){
    try{
      var st=document.getElementById('__ns_font');
      if(!st){st=document.createElement('style');st.id='__ns_font';(document.head||document.documentElement).appendChild(st);}
      // NSBn is registered from APK font bytes by MainActivity. Do not request a
      // fake font URL from the novel site's own origin: most sites do not host it.
      st.textContent=".ns-translated-content,.ns-translated-content *{font-family:'NSBn',sans-serif!important;}";
      fam="'NSBn',sans-serif";
    }catch(e){}
  }

  // Parse stable marker IDs. [000] is the title when present; [001+] are body units.
  var map={},chunks=String(translation||'').split(/(?=\[\d{3,}\]\s*)/);
  chunks.forEach(function(chunk){
    var mm=chunk.match(/^\[(\d{3,})\]\s*/);
    if(mm){var id=mm[1],val=chunk.slice(mm[0].length).trim();if(val&&!map[id])map[id]=val;}
  });
  if(!Object.keys(map).length)return 'no-markers';

  // Candidate elements are existing leaf/block text elements only. Never create
  // replacement <p> nodes. The exact source-text match is the primary alignment.
  var tags='p,li,blockquote,pre,h1,h2,h3,h4,h5,h6,div,section,article,span';
  var all=[].slice.call(el.querySelectorAll(tags));
  var used=new Set(), pairs=[], titleEl=null;

  function isLeafish(e){
    if(!e||!e.isConnected)return false;
    var t=norm(e.innerText||e.textContent);
    if(!t)return false;
    if(e.tagName==='DIV'||e.tagName==='SECTION'||e.tagName==='ARTICLE'||e.tagName==='SPAN'){
      var bad=e.querySelector('p,li,blockquote,pre,h1,h2,h3,h4,h5,h6');
      if(bad)return false;
    }
    return true;
  }

  function exactFind(src){
    var s=norm(src),best=null;
    if(!s)return null;
    for(var i=0;i<all.length;i++){
      var e=all[i];if(used.has(e)||!isLeafish(e))continue;
      if(norm(e.innerText||e.textContent)===s){
        // Prefer semantic paragraph/title nodes over generic wrappers.
        var pri=/^H[1-6]$/.test(e.tagName)?5:(e.tagName==='P'?4:(e.tagName==='LI'?3:1));
        var score=pri*100000-Math.abs((e.innerText||'').length-s.length);
        if(!best||score>best.score)best={el:e,score:score};
      }
    }
    return best&&best.el;
  }

  function fallbackBodyNodes(){
    var direct=[].slice.call(el.querySelectorAll(':scope > p,:scope > li,:scope > blockquote,:scope > pre'));
    direct=direct.filter(function(e){return !used.has(e)&&norm(e.innerText||e.textContent);});
    if(direct.length)return direct;
    return all.filter(function(e){return !used.has(e)&&isLeafish(e)&&!/^H[1-6]$/.test(e.tagName);});
  }

  // First align the title by exact source text, then each body marker by its
  // original source segment. The source list is the extraction-time identity map.
  for(var si=0;si<source.length;si++){
    var src=norm(source[si]),id=String(si+1).padStart(3,'0'),val=map[id];
    if(val===undefined)continue;
    var target=exactFind(src);
    if(!target && si===0)target=el.querySelector(':scope > h1,:scope > h2,:scope > h3');
    if(target){
      used.add(target);
      pairs.push({id:id,el:target,val:val});
      if(si===0)titleEl=target;
    }
  }

  // If exact text failed because the site inserted whitespace/spans, use the
  // original DOM order only for the still-unmatched source units.
  var remaining=fallbackBodyNodes();
  for(var ri=0;ri<source.length;ri++){
    var rid=String(ri+1).padStart(3,'0'),rv=map[rid];
    if(rv===undefined||pairs.some(function(p){return p.id===rid;}))continue;
    var t=remaining.shift();
    if(!t)continue;
    used.add(t);pairs.push({id:rid,el:t,val:rv});
  }

  // Safety: do not partially rewrite a chapter. We need a strong majority of
  // source units mapped, otherwise leave the original page untouched.
  var wanted=Object.keys(map).length;
  var mapped=pairs.length;
  if(mapped<wanted || mapped<Math.max(1,Math.floor(wanted*0.75)))return 'map-mismatch:'+mapped+':'+wanted;

  if(window.__nsEl!==el||window.__nsOrig==null){
    window.__nsOrig=el.innerHTML;window.__nsEl=el;
  }
  pairs.forEach(function(p){
    p.el.textContent=p.val;
    p.el.style.setProperty('font-size',(size||18)+'px','important');
    p.el.style.setProperty('line-height','1.75','important');
    p.el.style.setProperty('display','block','important');
    p.el.style.setProperty('white-space','normal','important');
    p.el.style.setProperty('word-break','keep-all','important');
    p.el.style.setProperty('overflow-wrap','normal','important');
    p.el.style.setProperty('letter-spacing','normal','important');
    p.el.style.setProperty('font-kerning','normal','important');
    p.el.style.setProperty('font-feature-settings','normal','important');
    p.el.style.setProperty('text-rendering','optimizeLegibility','important');
    if(fam)p.el.style.setProperty('font-family',fam,'important');
  });
  el.setAttribute('data-ns','1');
  el.setAttribute('data-ns-page-id',pageId||'');
  window.__nsPageId=pageId||'';
  window.__nsShown=1;
  return 'ok:mapped:'+mapped;
})(__SEL__,__TRANSLATION__,__SOURCE__,__FONT__,__SIZE__,__PAGE_ID__)
""";

    private const val APPLY_BODY = """
(function(sel,paras,font,size,pageId){
  var el=null;
  // The WTR-LAB reader preloads adjacent chapters; always replace the active body.
  if(location.hostname.toLowerCase().indexOf('wtr-lab.com')>=0){
    // Active tracker is often a sibling of .chapter-body, not its parent.
    // Resolve the matching chapter body instead of the first preloaded body.
    try{
      var tr=document.querySelector('.chapter-tracker.active[data-chapter-no]')||document.querySelector('.chapter-tracker.active');
      var activeNo=tr&&(tr.getAttribute('data-chapter-no')||'');
      var urlChapter=location.pathname.match(/\/chapter-(\d+)\/?$/i);
      // The URL identifies the requested chapter more reliably than a scroll-active
      // tracker when WTR-LAB has neighbouring chapters preloaded.
      var no=urlChapter?urlChapter[1]:activeNo;
      var candidates=[];
      if(no)candidates.push('#chapter-'+no+' .chapter-body','#tracker-'+no+' .chapter-body',
        '.chapter-tracker[data-chapter-no="'+no+'"] .chapter-body','[data-chapter-no="'+no+'"] .chapter-body');
      for(var ti=0;tr&&ti<6;ti++,tr=tr.parentElement){
        var bodies=tr.querySelectorAll?Array.from(tr.querySelectorAll('.chapter-body')).filter(function(b){return (b.innerText||'').trim().length>200;}):[];
        if(bodies.length===1)candidates.push(bodies[0]);
        else if(bodies.length>1){
          bodies.sort(function(a,b){
            var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
            var av=Math.max(0,Math.min(ar.bottom,innerHeight)-Math.max(ar.top,0));
            var bv=Math.max(0,Math.min(br.bottom,innerHeight)-Math.max(br.top,0));
            return bv-av;
          });
          candidates.push(bodies[0]);
        }
      }
      for(var ci=0;ci<candidates.length;ci++){
        var cand=candidates[ci];
        if(typeof cand==='string'){try{cand=document.querySelector(cand);}catch(e){cand=null;}}
        if(cand&&cand.classList&&cand.classList.contains('chapter-body')&&(cand.innerText||'').trim().length>200){el=cand;break;}
      }
      if(!el&&document.querySelector('.chapter-infinite-reader')){
        var allBodies=Array.from(document.querySelectorAll('.chapter-infinite-reader .chapter-body')).filter(function(b){return (b.innerText||'').trim().length>200;});
        allBodies.sort(function(a,b){
          var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
          var av=Math.max(0,Math.min(ar.bottom,innerHeight)-Math.max(ar.top,0));
          var bv=Math.max(0,Math.min(br.bottom,innerHeight)-Math.max(br.top,0));
          return bv-av;
        });
        if(allBodies.length)el=allBodies[0];
      }
    }catch(e){}
  }
  try{ if(!el&&sel) el=document.querySelector(sel); }catch(e){}
  // Jsoup's generated cssSelector can become stale after a SPA/navigation
  // rerender. Fall back to the same content selectors used by Extractor.
  if(!el){
    var sels=['#chapter-content','.chapter-content','.chapter_content','#chr-content','.chr-c',
      '.reading-content','.text-left','#content','.entry-content','.cha-content','.cha-words',
      '.chapter-body','.novel_content','.j_readContent','.txt','#chaptercontent','.chapter-c',
      '#article','.article-content','.content','article'];
    var best=null,bs=0;
    for(var i=0;i<sels.length;i++){
      var es=[];
      try{es=[].slice.call(document.querySelectorAll(sels[i]));}catch(e){es=[];}
      for(var j=0;j<es.length;j++){
        var x=es[j], tx=(x.innerText||'').trim();
        if(tx.length<500) continue;
        var sc=tx.length;
        sc+=(x.querySelectorAll('p').length*250);
        if(sc>bs){bs=sc;best=x;}
      }
    }
    if(!best){
      var es=[].slice.call(document.querySelectorAll('article,main,section,div'));
      for(var k=0;k<es.length;k++){
        var x=es[k],tx=(x.innerText||'').trim();
        if(tx.length<500) continue;
        var ps=x.querySelectorAll('p').length;
        if(ps<3) continue;
        var sc=tx.length+ps*250;
        if(sc>bs){bs=sc;best=x;}
      }
    }
    el=best;
  }
  // Never write into a page-wide wrapper (#app, body, a bare 'div'...): that wipes the
  // site's own UI and breaks navigation. Use the element that really holds the <p> lines.
  function dp(x){var n=0;for(var q=0;q<x.children.length;q++){if(x.children[q].tagName==='P')n++;}return n;}
  if(el&&dp(el)<5&&!el.getAttribute('data-ns')){
    var bd=null,bn=0,cs=[].slice.call(el.querySelectorAll('div,article,section,main'));
    for(var z=0;z<cs.length;z++){var nn=dp(cs[z]);if(nn>bn){bn=nn;bd=cs[z];}}
    if(bd&&bn>=5) el=bd;
    else if(el===document.body||el===document.documentElement||/^(app|root|__next|__nuxt)$/.test(el.id||'')) el=null;
  }
  if(!el) return 'noel';
  if(window.__nsEl!==el||window.__nsOrig==null){ window.__nsOrig=el.innerHTML; window.__nsEl=el; }
  var fam='';
  if(font&&font.length){
    try{
      var st=document.getElementById('__ns_font');
      if(!st){ st=document.createElement('style'); st.id='__ns_font'; (document.head||document.documentElement).appendChild(st); }
      // Use the FontFace already loaded from local APK bytes, not a remote URL
      // that is absent on third-party novel sites.
      st.textContent=".ns-translated-content,.ns-translated-content *{font-family:'NSBn',sans-serif!important;}";
      fam="'NSBn',sans-serif";
    }catch(e){}
  }
  // IMPORTANT: write into the site's existing HTML elements first.
  // Do NOT build a new translation <div>/<p> tree when the page already has
  // one paragraph element per extracted paragraph. This keeps the site's
  // original DOM, classes, CSS, spacing and exact paragraph positions.
  var existing=Array.from(el.querySelectorAll(':scope > p')).filter(function(p){
    return String(p.innerText||p.textContent||'').replace(/\\u00a0/g,' ').trim().length>0;
  });
  if(existing.length>=3){
    var off=paras.length===existing.length+1?1:0;
    if(paras.length-off===existing.length){
      var hs=off?el.querySelector(':scope > h1, :scope > h2, :scope > h3'):null;
      if(off&&hs){
        hs.textContent=String(paras[0]||'');
        hs.style.setProperty('font-size',(size||18)+'px','important');
        if(fam)hs.style.setProperty('font-family',fam,'important');
      }
      existing.forEach(function(p,i){
        p.textContent=String(paras[i+off]||'');
        p.style.setProperty('font-size',(size||18)+'px','important');
        p.style.setProperty('line-height','1.75','important');
        p.style.setProperty('display','block','important');
        p.style.setProperty('white-space','normal','important');
        p.style.setProperty('word-break','keep-all','important');
        p.style.setProperty('overflow-wrap','normal','important');
        p.style.setProperty('letter-spacing','normal','important');
        p.style.setProperty('font-kerning','normal','important');
        p.style.setProperty('font-feature-settings','normal','important');
        p.style.setProperty('text-rendering','optimizeLegibility','important');
        if(fam)p.style.setProperty('font-family',fam,'important');
      });
      el.setAttribute('data-ns','1');
      el.setAttribute('data-ns-page-id',pageId||'');
      window.__nsPageId=pageId||'';
      window.__nsShown=1;
      return 'ok';
    }
  }

  // Legacy fallback for sites whose chapter DOM cannot be aligned with the
  // extracted paragraphs. Only this path creates replacement HTML.
  var rc=el.getBoundingClientRect(), cst=getComputedStyle(el);
  var padL=Math.max(0,16-Math.max(rc.left,0)-(parseFloat(cst.paddingLeft)||0));
  var padR=Math.max(0,16-Math.max(window.innerWidth-rc.right,0)-(parseFloat(cst.paddingRight)||0));
  var wrap=document.createElement('div');
  wrap.className='ns-translated-content';
  wrap.style.cssText='box-sizing:border-box;max-width:100%;text-align:left;overflow-wrap:normal;word-break:keep-all;letter-spacing:normal;'
    +'padding:0 '+padR+'px 0 '+padL+'px;font-size:'+(size||18)+'px;'
    +(fam?'font-family:'+fam+'!important;':'');
  var frag=document.createDocumentFragment();
  for(var i=0;i<paras.length;i++){
    var p=document.createElement('p'); p.textContent=paras[i];
    // Force true paragraph blocks: some reader stylesheets turn <p> into inline content.
    p.style.setProperty('display','block','important');
    p.style.setProperty('white-space','normal','important');
    p.style.setProperty('overflow-wrap','normal','important');
    p.style.setProperty('word-break','keep-all','important');
    p.style.setProperty('letter-spacing','normal','important');
    p.style.setProperty('font-kerning','normal','important');
    p.style.setProperty('font-feature-settings','normal','important');
    p.style.setProperty('text-rendering','optimizeLegibility','important');
    // Reader-site !important rules can otherwise override custom fonts and paragraph spacing.
    p.style.setProperty('margin-top','0','important');
    p.style.setProperty('margin-right','0','important');
    p.style.setProperty('margin-bottom','1em','important');
    p.style.setProperty('margin-left','0','important');
    p.style.setProperty('line-height','1.75','important');
    p.style.setProperty('font-size',(size||18)+'px','important');
    if(fam)p.style.setProperty('font-family',fam,'important');
    wrap.appendChild(p);
  }
  frag.appendChild(wrap);
  el.innerHTML=''; el.appendChild(frag); el.setAttribute('data-ns','1');
  el.setAttribute('data-ns-page-id',pageId||'');
  window.__nsPageId=pageId||'';
  window.__nsShown=1;
  return 'ok';
})(__SEL__,__PARAS__,__FONT__,__SIZE__,__PAGE_ID__)
"""

    fun apply(sel: String, parasJson: String, font: String = "", size: Int = 18, pageId: String = ""): String =
        APPLY_BODY.replace("__SEL__", org.json.JSONObject.quote(sel)).replace("__PARAS__", parasJson)
            .replace("__FONT__", org.json.JSONObject.quote(font)).replace("__SIZE__", size.toString())
            .replace("__PAGE_ID__", org.json.JSONObject.quote(pageId))

    fun applyMapped(sel: String, translation: String, sourceSegmentsJson: String, font: String = "", size: Int = 18, pageId: String = ""): String =
        APPLY_MAPPED_BODY
            .replace("__SEL__", org.json.JSONObject.quote(sel))
            .replace("__TRANSLATION__", org.json.JSONObject.quote(translation))
            .replace("__SOURCE__", org.json.JSONObject.quote(sourceSegmentsJson))
            .replace("__FONT__", org.json.JSONObject.quote(font))
            .replace("__SIZE__", size.toString())
            .replace("__PAGE_ID__", org.json.JSONObject.quote(pageId))

    fun stillApplied(sel: String, pageId: String = ""): String =
        "(function(sel,pid){var el=null;" +
        "if(window.__nsEl&&document.contains(window.__nsEl)&&window.__nsShown&&window.__nsEl.getAttribute('data-ns')==='1'&&(!pid||window.__nsEl.getAttribute('data-ns-page-id')===pid))el=window.__nsEl;" +
        "if(!el){try{if(sel)el=document.querySelector(sel);}catch(e){}}" +
        "if(!el)el=document.querySelector('[data-ns=\"1\"]');" +
        "if(!el||el.getAttribute('data-ns')!=='1')return 'lost';" +
        "if(pid&&el.getAttribute('data-ns-page-id')!==pid)return 'lost-page';" +
        "return 'ok';})(" +
        org.json.JSONObject.quote(sel) + "," + org.json.JSONObject.quote(pageId) + ")"

    val TOGGLE = "(function(){var el=window.__nsEl;if(!el||window.__nsOrig==null||!document.contains(el))return 'none';" +
        "if(window.__nsShown){window.__nsTr=el.innerHTML;el.innerHTML=window.__nsOrig;window.__nsShown=0;el.removeAttribute('data-ns');return 'orig';}" +
        "else{el.innerHTML=window.__nsTr;window.__nsShown=1;el.setAttribute('data-ns','1');return 'tr';}})()"

    // ---------------------------------------------------------------- click the site's own Next / Prev button
    private const val CLICK_BODY = """
(function(){
  var re=new RegExp('^('+'__ALTS__'+')$','i');
  var wre=new RegExp('__WORD__','i');
  var dir='__DIR__';

  // WebNovel and other readers may use custom elements or icon-only controls.
  // Prefer semantic selectors first, then fall back to text/metadata scoring.
  var direct = dir==='next'
    ? ['#next','[id="next"]','[data-testid="next"]','[aria-label="Next Chapter" i]','[title="Next Chapter" i]','button[title*="Next Chapter" i]','a[title*="Next Chapter" i]','mov-button#next']
    : ['#prev','[id="prev"]','[data-testid="prev"]','[aria-label="Previous Chapter" i]','[title="Previous Chapter" i]','button[title*="Previous Chapter" i]','a[title*="Previous Chapter" i]','mov-button#prev'];

  for(var d=0;d<direct.length;d++){
    var ds=[];
    try{ds=[].slice.call(document.querySelectorAll(direct[d]));}catch(e){ds=[];}
    for(var q=0;q<ds.length;q++){
      var de=ds[q],dr=de.getBoundingClientRect();
      if(dr.width>=3&&dr.height>=3&&!de.disabled&&de.getAttribute('aria-disabled')!=='true'){
        try{de.click();return 'clicked';}catch(x){}
      }
    }
  }

  var links=[].slice.call(document.querySelectorAll('a[href],button,[role=button],div,span,li,i'));
  var best=null,bs=0;
  for(var i=0;i<links.length;i++){
    var e=links[i],tc=(e.textContent||'').trim();
    if(tc.length>40) continue;
    var cn=(typeof e.className==='string')?e.className:'';
    var meta=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+cn+' '+(e.id||'')+' '+(e.getAttribute('data-eventname')||'');
    var href=(e.getAttribute('href')||'');
    var s=0;
    if(re.test(tc)) s+=6; else if(tc.length<=25&&wre.test(tc)) s+=4;
    if(wre.test(meta)) s+=4;
    if(/chapter|\/book\//i.test(href)&&wre.test(href)) s+=3;
    if(s===0) continue;
    if(/disabled/i.test(cn)||e.disabled||e.getAttribute('aria-disabled')==='true') continue;
    var r=e.getBoundingClientRect();
    if(r.width<3||r.height<3) continue;
    if(/chap/i.test(meta+tc+href)) s+=1;
    if(s>bs){bs=s;best=e;}
  }
  if(!best) return 'none';
  try{best.click();return 'clicked';}catch(x){return 'none';}
})()
"""

    fun clickNext(dir: String): String {
        val alts = if (dir == "next")
            "next|next chapter|next page|next ›|next »|›|»|→|下一頁|下一页|下一章|下一节|下一節|下一话|下一話|下一回|다음|다음화|次へ|次の話|পরবর্তী|নেক্সট"
        else
            "prev|previous|prev chapter|previous chapter|prev page|‹|«|←|上一頁|上一页|上一章|上一节|上一節|上一话|上一話|上一回|이전|이전화|前へ|前の話|আগের|পূর্ববর্তী"
        val word = if (dir == "next") "next" else "prev(?!iew)"
        return CLICK_BODY.replace("__ALTS__", alts).replace("__WORD__", word).replace("__DIR__", dir)
    }
    /**
     * Installed on every page of the reader pane. Remembers (in the site's own localStorage, so it survives
     * the navigation) a signature of the last control the USER tapped. The app compares the address before and
     * after: if it moved to the next / previous chapter, that signature is what "Next" / "Prev" looks like on
     * this site (also when it is a script button without any href).
     */
    const val NAV_LEARN = """
(function(){
  if(window.__nsNavLearn)return; window.__nsNavLearn=1;
  document.addEventListener('click',function(ev){
    try{
      if(!ev.isTrusted)return;
      var t=ev.target; if(!t||!t.closest)return;
      var el=t.closest('a,button,[role="button"],[onclick]')||t;
      var cl=[];
      try{cl=String(el.getAttribute('class')||'').split(/\s+/).filter(function(x){return x&&x.length<28&&!/[0-9]{3,}/.test(x)&&!/^[a-z]{1,3}[-_][A-Za-z0-9_-]{6,}$/.test(x);}).slice(0,4);}catch(e){}
      var par=el.parentElement,idx=0;
      if(par){var ch=par.children;for(var i=0;i<ch.length;i++){if(ch[i]===el){idx=i;break;}}}
      var sv=el.querySelector?el.querySelector('svg path'):null;
      var sig={tag:(el.tagName||'').toLowerCase(),
        aria:(el.getAttribute('aria-label')||'').slice(0,40),title:(el.getAttribute('title')||'').slice(0,40),
        testid:(el.getAttribute('data-testid')||el.getAttribute('data-test')||'').slice(0,40),
        text:((el.innerText||el.textContent||'').replace(/\s+/g,' ').trim()).slice(0,24),
        cls:cl,role:el.getAttribute('role')||'',idx:idx,
        svg:sv?(sv.getAttribute('d')||'').slice(0,60):'',
        href:(el.getAttribute('href')||'').replace(/[0-9]+/g,'#').slice(0,80)};
      localStorage.setItem('__ns_lastclick',JSON.stringify({t:Date.now(),u:location.href,sig:sig}));
    }catch(e){}
  },true);
})();
"""

    /** Finds the control described by [sig] (a signature saved by NAV_LEARN) and uses it. */
    fun clickLearned(sigJson: String): String = ("""
(function(sig){
  try{
    var vis=function(e){var r=e.getBoundingClientRect();return r.width>2&&r.height>2;};
    var nodes=document.querySelectorAll('a,button,[role="button"],[onclick],li,div,span');
    var best=null,bs=0,n=Math.min(nodes.length,4000);
    for(var i=0;i<n;i++){
      var e=nodes[i];
      if(sig.tag&&(e.tagName||'').toLowerCase()!==sig.tag)continue;
      if(!vis(e))continue;
      var sc=0,strong=0;
      var aria=e.getAttribute('aria-label')||'',title=e.getAttribute('title')||'';
      var tid=e.getAttribute('data-testid')||e.getAttribute('data-test')||'';
      var txt=((e.innerText||e.textContent||'').replace(/\s+/g,' ').trim()).slice(0,24);
      if(sig.aria&&aria.slice(0,40)===sig.aria){sc+=4;strong++;}
      if(sig.title&&title.slice(0,40)===sig.title){sc+=3;strong++;}
      if(sig.testid&&tid.slice(0,40)===sig.testid){sc+=5;strong++;}
      if(sig.text&&txt===sig.text){sc+=3;strong++;}
      var sv=e.querySelector?e.querySelector('svg path'):null;
      if(sig.svg&&sv&&(sv.getAttribute('d')||'').slice(0,60)===sig.svg){sc+=4;strong++;}
      var ec=String(e.getAttribute('class')||'').split(/\s+/),hit=0;
      for(var k=0;k<(sig.cls||[]).length;k++){if(ec.indexOf(sig.cls[k])>=0)hit++;}
      sc+=hit;
      if(sig.role&&(e.getAttribute('role')||'')===sig.role)sc+=1;
      var par=e.parentElement,idx=0;
      if(par){var ch=par.children;for(var j=0;j<ch.length;j++){if(ch[j]===e){idx=j;break;}}}
      if(idx===sig.idx)sc+=1;
      if(sig.href&&(e.getAttribute('href')||'').replace(/[0-9]+/g,'#').slice(0,80)===sig.href){sc+=3;strong++;}
      // must match on something specific (label / icon / id / text / link shape), or on several class names
      if(!(strong>0||hit>=2))continue;
      if(sc>bs){bs=sc;best=e;}
    }
    if(!best||bs<5)return 'none';
    var a=best.closest?best.closest('a[href]'):null;
    if(a&&a.href&&/^https?:/i.test(a.href))return 'href:'+a.href;
    var types=['pointerdown','mousedown','pointerup','mouseup','click'];
    for(var q=0;q<types.length;q++){
      try{best.dispatchEvent(new MouseEvent(types[q],{bubbles:true,cancelable:true,view:window}));}catch(x){}
    }
    return 'clicked';
  }catch(err){return 'none';}
})(__SIG__)
""").replace("__SIG__", sigJson)

    // WebNovel navigation based on the open-source WebnovelReader crawler.
    // That project uses the site's stable chapter catalog selector:
    //   .j_catalog_list .volume-item li a
    // and opens <book-path>/catalog, then walks the adjacent chapter.
    // This avoids guessing the mobile reader's icon/button DOM.

    // Called after the WebView has loaded /book/<slug>/catalog.
    // Returns the adjacent chapter URL without navigating the catalog page.
    fun webNovelPickCatalog(dir: String, currentTitle: String, currentUrl: String = ""): String {
        val safe = currentTitle
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        val safeUrl = currentUrl
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return """
(function(){
  var dir='__DIR__', title='__TITLE__', sourceUrl='__SOURCE_URL__';
  var norm=function(s){return (s||'').replace(/\s+/g,' ').trim().toLowerCase();};
  var stripIndex=function(s){return norm(s).replace(/^\s*\d+\s*[-.:)]?\s*/,'');};
  var clean=function(u){
    try{return new URL(u,location.href).pathname.replace(/\/+$/,'');}
    catch(e){return String(u||'').split('?')[0].split('#')[0].replace(/\/+$/,'');}
  };

  var path=location.pathname.replace(/\/+$/,'');
  var bm=path.match(/^\/book\/[^/]+/i);
  var bookPath=bm?bm[0]:'';
  if(!bookPath)return 'none';

  var curTitle=stripIndex(title);
  var sourcePath=sourceUrl?clean(sourceUrl):'';
  var as=[].slice.call(document.querySelectorAll('.j_catalog_list .volume-item li a[href], .j_catalog_list a[href], a[href]'));
  var links=[];

  for(var i=0;i<as.length;i++){
    var a=as[i], h=a.getAttribute('href')||'';
    if(!h)continue;
    var p=clean(h);
    if(p===clean(location.href)||/\/catalog\/?$/i.test(p))continue;
    if(p.indexOf(bookPath+'/')!==0)continue;

    var t=stripIndex(
      a.getAttribute('title') ||
      a.getAttribute('aria-label') ||
      a.textContent ||
      ''
    );
    if(!t)continue;
    links.push({p:p,t:t});
  }

  if(!links.length)return 'none';

  var idx=-1;
  // URL identity is more reliable than the heading, which may be localized or truncated.
  if(sourcePath){
    for(var u=0;u<links.length;u++){
      if(links[u].p===sourcePath){idx=u;break;}
    }
  }
  for(var x=0;idx<0&&x<links.length;x++){
    if(links[x].t===curTitle){idx=x;break;}
  }

  if(idx<0){
    var normalizeTitle=function(s){
      return norm(s)
        .replace(/[“”"']/g,'')
        .replace(/\s*[-–—:]\s*/g,' ')
        .replace(/\s+/g,' ')
        .trim();
    };
    var nt=normalizeTitle(curTitle);
    for(var y=0;y<links.length;y++){
      if(normalizeTitle(links[y].t)===nt){idx=y;break;}
    }
  }

  if(idx<0){
    var words=curTitle.split(/\s+/).filter(function(w){return w.length>=3;});
    var part=(curTitle.match(/\(part\s+([0-9]+)\)/i)||[])[1]||'';
    var best=-1,bestScore=0;
    for(var z=0;z<links.length;z++){
      var sc=0,t=links[z].t;
      for(var q=0;q<words.length;q++){
        if(t.indexOf(words[q])>=0)sc+=words[q].length>=5?3:1;
      }
      if(part&&new RegExp('\\(part\\s+'+part+'\\)','i').test(t))sc+=8;
      if(sc>bestScore){bestScore=sc;best=z;}
    }
    if(bestScore>=6)idx=best;
  }

  if(idx<0)return 'none';
  var ni=dir==='next'?idx+1:idx-1;
  if(ni<0||ni>=links.length)return 'edge';

  try{return new URL(links[ni].p,location.href).href;}
  catch(e){return 'none';}
})()
""".trimIndent()
            .replace("__TITLE__", safe)
            .replace("__DIR__", dir)
            .replace("__SOURCE_URL__", safeUrl)
    }

    fun webNovelNext(dir: String, currentTitle: String): String {
        // WebNovel mobile can expose either /book/<numeric-id> or a slug.
        // Do not require a numeric bookId: the catalog URL works for both.
        val safe = currentTitle
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return """
(function(){
  var dir='__DIR__', title='__TITLE__';
  var norm=function(s){return (s||'').replace(/\s+/g,' ').trim().toLowerCase();};
  var stripIndex=function(s){return norm(s).replace(/^\s*\d+\s*[-.:)]?\s*/,'');};
  var clean=function(u){
    try{return new URL(u,location.href).pathname.replace(/\/+$/,'');}
    catch(e){return String(u||'').split('?')[0].split('#')[0].replace(/\/+$/,'');}
  };

  var path=location.pathname.replace(/\/+$/,'');
  var bm=path.match(/^\/book\/[^/]+/i);
  var bookPath=bm?bm[0]:'';
  if(!bookPath)return 'failed:no-book-path';

  var curTitle=stripIndex(title);
  var currentUrl=clean(location.href);

  function scoreTitle(a,b){
    if(a===b)return 100000;
    var aw=a.split(/\s+/).filter(function(w){return w.length>=2;});
    var score=0;
    for(var i=0;i<aw.length;i++){
      if(b.indexOf(aw[i])>=0)score+=aw[i].length>=5?3:1;
    }
    return score;
  }

  function navigateFromCatalog(html){
    var doc=new DOMParser().parseFromString(html,'text/html');
    var as=[].slice.call(doc.querySelectorAll('.j_catalog_list .volume-item li a[href], a[href]'));
    var links=[];
    for(var i=0;i<as.length;i++){
      var a=as[i], h=a.getAttribute('href')||'';
      if(!h)continue;
      var p=clean(h);
      if(p===clean(bookPath+'/catalog')||p===clean(location.href))continue;
      if(p.indexOf(bookPath+'/')!==0)continue;
      var t=stripIndex(a.getAttribute('title')||a.textContent||'');
      if(!t)continue;
      links.push({p:p,t:t});
    }
    if(!links.length)return 'failed:no-chapter-links';

    // First try exact chapter URL, if the reader exposes one.
    var idx=-1;
    for(var x=0;x<links.length;x++){
      if(links[x].p===currentUrl){idx=x;break;}
    }

    // Then exact normalized title. This correctly distinguishes:
    // Chapter 1 ... (part 1), (part 2), (part 3).
    if(idx<0){
      for(var y=0;y<links.length;y++){
        if(links[y].t===curTitle){idx=y;break;}
      }
    }

    // Last fallback: highest title similarity.
    if(idx<0){
      var best=-1,bestScore=0;
      for(var z=0;z<links.length;z++){
        var sc=scoreTitle(curTitle,links[z].t);
        if(sc>bestScore){bestScore=sc;best=z;}
      }
      if(bestScore>=3)idx=best;
    }

    if(idx<0)return 'failed:no-current-chapter';
    var ni=dir==='next'?idx+1:idx-1;
    if(ni<0||ni>=links.length)return 'failed:edge';
    try{
      location.href=new URL(links[ni].p,location.href).href;
      return 'catalog-chapter-go';
    }catch(e){return 'failed:bad-target';}
  }

  var catalogUrl=location.origin+bookPath+'/catalog';

  // The important part: this fetch is only used to read the ordered links.
  // The catalog page itself is never loaded into the WebView.
  fetch(catalogUrl,{credentials:'include',cache:'no-store'})
    .then(function(r){
      if(!r.ok)throw new Error('catalog HTTP '+r.status);
      return r.text();
    })
    .then(function(html){
      var result=navigateFromCatalog(html);
      if(result.indexOf('failed:')===0){
        console.log('[NovelStudio] WebNovel catalog parse:',result);
      }
    })
    .catch(function(e){
      console.log('[NovelStudio] WebNovel catalog fetch failed',e);
    });

  return 'webnovel-catalog-reading';
})()
""".trimIndent()
            .replace("__TITLE__", safe)
            .replace("__DIR__", dir)
    }

    // ---------------------------------------------------------------- fast chapter extraction
    // Port of Novel Translator's DOM extractor:
    // read only the already-rendered chapter DOM, never serialize the whole page.
    // It deliberately keeps short lines (e.g. Chinese dialogue / labels) instead
    // of dropping them by character count.
    fun fastExtract(contentSel: String = "", titleSel: String = "", nextSel: String = "", prevSel: String = ""): String {
        return """
(function(){
  var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,SVG:1,CANVAS:1,VIDEO:1,AUDIO:1,IFRAME:1,INPUT:1,TEXTAREA:1,BUTTON:1,SELECT:1,OPTION:1,NAV:1,FOOTER:1,HEADER:1};
  var BLOCK={P:1,LI:1,BLOCKQUOTE:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,PRE:1,DIV:1,SECTION:1,ARTICLE:1};
  function clean(s){return String(s||'').replace(/\u00a0/g,' ').replace(/[ \t]+\n/g,'\n').replace(/\n[ \t]+/g,'\n').replace(/\n{3,}/g,'\n\n').trim();}
  // Cheap read, same as the extension's textOf(): no cloning. innerText of a live
  // element already leaves out <script>/<style>, so a deep clone per node is waste.
  var TC=new Map();   // one synchronous run: element text never changes, so read each once
  function text(e){
    if(!e)return '';
    var c=TC.get(e);
    if(c!==undefined)return c;
    c=clean(e.innerText||e.textContent||'');
    TC.set(e,c);
    return c;
  }
  function visible(e){return !!e&&e.isConnected&&(e.offsetWidth>0||e.offsetHeight>0||e.getClientRects().length>0);}
  function pick(sel){if(!sel)return null;try{var e=document.querySelector(sel);return e&&visible(e)?e:null;}catch(x){return null;}}
  // Return a selector that identifies this exact DOM node, not merely the
  // first element sharing its class. Infinite readers (especially WTR-LAB)
  // keep several chapter bodies in the DOM at once.
  function stable(e){
    if(!e)return '';
    var validId=function(x){return /^[A-Za-z_][A-Za-z0-9_-]*$/.test(x||'');};
    var id=(e.id||'').trim();
    if(validId(id)){try{if(document.querySelectorAll('#'+id).length===1)return '#'+id;}catch(x){}}
    var parts=[],cur=e,guard=0;
    while(cur&&cur.nodeType===1&&guard++<24){
      var tag=cur.tagName.toLowerCase();
      var cls=Array.from(cur.classList||[]).filter(function(x){return /^[A-Za-z_][A-Za-z0-9_-]*$/.test(x);}).slice(0,2);
      var seg=tag+(cls.length?'.'+cls.join('.'): '');
      var parent=cur.parentElement;
      if(parent){
        var peers=Array.from(parent.children||[]).filter(function(x){return x.tagName===cur.tagName;});
        if(peers.length>1)seg+=':nth-of-type('+(peers.indexOf(cur)+1)+')';
      }
      parts.unshift(seg);
      var candidate=parts.join(' > ');
      try{if(document.querySelectorAll(candidate).length===1)return candidate;}catch(x){}
      if(validId((cur.id||'').trim())){
        var anchored='#'+cur.id+(parts.length>1?' > '+parts.slice(1).join(' > '):'');
        try{if(document.querySelectorAll(anchored).length===1)return anchored;}catch(x){}
      }
      cur=parent;
    }
    return parts.join(' > ');
  }
  var MEAN;
  try{MEAN=new RegExp('[\\p{L}\\p{N}]','u');}
  catch(x){MEAN=/[0-9A-Za-z\u00C0-\u1FFF\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF\uFF10-\uFF5A]/;}
  function meaningful(s){return MEAN.test(s||'');}
  function hasBlockChild(e){return Array.from(e.children||[]).some(function(c){return !SKIP[c.tagName]&&!!BLOCK[c.tagName];});}

  function roots(){
    var host=location.hostname.toLowerCase(), sels=[];
    if(host.indexOf('novel543.com')>=0)sels.push('.chapter-content','#content','.article-content');
    if(host.indexOf('webnovel.com')>=0)sels.push('.cha-words','.cha-content','.chapter-content','.chapter_content','article','main');
    // WTR-LAB renders the reader and chapter navigation inside shared page
    // containers. Prefer the actual chapter body before broad article/main shells.
    if(host.indexOf('wtr-lab.com')>=0)sels.push(
       // Prefer the active chapter in WTR-LAB's infinite reader; adjacent chapters
       // may already be preloaded in the DOM.
       '.chapter-tracker.active .chapter-body',
       '.chapter-body','.chapter-container .chapter-body','.chapter-wrap .chapter-body',
       '.chapter-content','#chapter-content','.reader-content','.reading-content',
       '[class*="chapter-content"]','[id*="chapter-content"]'
     );
    sels.push(
      __CONTENT__,
      '.cha-words','.cha-content','.chapter-content','.chapter_content',
      '.article-content','#content','article[role="main"]','article','main','.content',
      '#chapter-content','#chr-content','.chr-c','.reading-content','.text-left',
      '.entry-content','.chapter-body','.novel_content','.j_readContent','.txt',
      '#chaptercontent','.chapter-c','#article'
    );
    var seen=[],out=[];
    sels.forEach(function(sel){
      if(!sel)return;
      var list=[];
      try{list=Array.from(document.querySelectorAll(sel));}catch(x){return;}
      list.forEach(function(e){
        if(!e||seen.indexOf(e)>=0||!e.isConnected)return;
        if(text(e).length<80)return;
        seen.push(e);out.push(e);
      });
    });
    return out.slice(0,8);
  }

  function units(root){
    var out=[],seen=new Set();
    // WTR-LAB uses .wtr-line / [data-line] as paragraph units rather than <p>.
    // Extract these units directly to retain paragraph boundaries.
    if(location.hostname.toLowerCase().indexOf('wtr-lab.com')>=0){
      var wtrLines=Array.from(root.querySelectorAll('.wtr-line, [data-line]'));
      if(wtrLines.length){
        var leafLines=wtrLines.filter(function(e){return !e.querySelector('.wtr-line, [data-line]');});
        var cleanLines=leafLines.filter(function(e){
          var t=text(e);
          return meaningful(t)&&!/^\d+(?:\.\d+)?%$/.test(t);
        });
        if(cleanLines.length>=3)return cleanLines;
      }
    }
    // Reject site chrome even when the website uses generic DIVs instead of
    // semantic NAV/FOOTER tags. WTR-LAB puts the TOC, chapter progress and
    // previous/next controls near the reader, and broad roots used to collect them.
    var chromeSel='nav,header,footer,aside,[role="navigation"],[role="menu"],' +
      '[class*="sidebar" i],[id*="sidebar" i],[class*="pagination" i],[id*="pagination" i],' +
      '[class*="chapter-list" i],[id*="chapter-list" i],[class*="toc" i],[id*="toc" i],' +
      '[class*="toolbar" i],[id*="toolbar" i],[class*="breadcrumb" i],[id*="breadcrumb" i],' +
      '[class*="reader-nav" i],[id*="reader-nav" i],[class*="chapter-nav" i],[id*="chapter-nav" i]';
    function isChrome(e,t){
      try{if(e.closest(chromeSel))return true;}catch(x){}
      // Link-heavy rows are navigation/TOC entries, not story paragraphs.
      var len=(t||'').length,links=0;
      try{e.querySelectorAll('a').forEach(function(a){links+=(a.innerText||a.textContent||'').trim().length;});}catch(x){}
      if(len>0&&links/len>0.55)return true;
      var s=String(t||'').replace(/\\s+/g,' ').trim().toLowerCase();
      if(!s)return true;
      if(/^(wtr[- ]lab|library|novels?|ranking|leaderboard|table of contents|contents|previous|next|previous chapter|next chapter|আগের|পরের|সূচিপত্র|লাইব্রেরি|উপন্যাস|র‍্যাঙ্কিং|লিডারবোর্ড)$/.test(s))return true;
      if(/^(?:chapter|episode)\\s*\\d+\\s*(?:\\/|of)\\s*\\d+$/i.test(s))return true;
      if(/^\\d+(?:\\.\\d+)?%$/.test(s))return true;
      return false;
    }
    function add(e){
      if(!e||SKIP[e.tagName]||seen.has(e)||!e.isConnected)return;
      if(hasBlockChild(e))return;
      var t=text(e);
      if(!meaningful(t)||isChrome(e,t))return;
      seen.add(e);out.push(e);
    }

    var all=[root].concat(Array.from(root.querySelectorAll('p,li,blockquote,h1,h2,h3,h4,h5,h6,div,section,article,pre')));
    for(var i=0;i<all.length&&out.length<1500;i++)add(all[i]);

    // Standalone inline lines are common in mobile readers, especially WebNovel.
    Array.from(root.querySelectorAll('span,strong,b,font')).forEach(function(e){
      if(out.length>=1500||e.children.length)return;
      var p=e.parentElement,inText=false;
      while(p&&p!==root){
        if(['P','LI','BLOCKQUOTE','H1','H2','H3','H4','H5','H6','PRE'].indexOf(p.tagName)>=0){inText=true;break;}
        p=p.parentElement;
      }
      if(!inText)add(e);
    });

    // If the reader is mostly leaf spans, use them as the actual lines.
    if(out.length<3){
      Array.from(root.querySelectorAll('span')).forEach(function(e){
        if(out.length>=1500||e.children.length)return;
        var p=e.parentElement,inText=false;
        while(p&&p!==root){
          if(['P','LI','BLOCKQUOTE','H1','H2','H3','H4','H5','H6','PRE'].indexOf(p.tagName)>=0){inText=true;break;}
          p=p.parentElement;
        }
        if(!inText)add(e);
      });
    }

    // Direct text nodes between blocks.
    Array.from(root.querySelectorAll('div,section,article,p,li,blockquote,pre')).concat([root]).forEach(function(parent){
      if(out.length>=1500||seen.has(parent))return;
      Array.from(parent.childNodes||[]).forEach(function(node){
        if(node.nodeType!==Node.TEXT_NODE)return;
        var v=clean(node.nodeValue);
        if(!meaningful(v))return;
        var span=document.createElement('span');
        span.setAttribute('data-ns-fast-generated','1');
        span.textContent=node.nodeValue;
        node.parentNode.replaceChild(span,node);
        add(span);
      });
    });
    return out.slice(0,1500);
  }

  // Use the same collector for Novel543 as the working browser extension.
  // Do NOT collapse the whole .chapter-content into one text blob: on some
  // Novel543 pages that node contains the reader shell/title while the actual
  // chapter lines live in child DIV/SPAN nodes. The extension walks those leaf
  // units and that is the reliable path.

  var content=pick(__CONTENT__);
  var rootList=roots();
  // WTR-LAB preloads neighbouring chapter bodies; always target the active one,
  // even if a remembered generic selector matches an earlier body in the DOM.
  if(location.hostname.toLowerCase().indexOf('wtr-lab.com')>=0){
    // Active tracker and body are commonly siblings. Resolve the chapter body
    // by tracker chapter number/wrapper; never blindly use the first preloaded body.
    var activeWtr=null;
    try{
      var tr=document.querySelector('.chapter-tracker.active[data-chapter-no]')||document.querySelector('.chapter-tracker.active');
      var activeNo=tr&&(tr.getAttribute('data-chapter-no')||'');
      var urlChapter=location.pathname.match(/\/chapter-(\d+)\/?$/i);
      // Prefer the exact chapter from the address bar; the active tracker can lag
      // while the infinite reader scrolls between preloaded chapter bodies.
      var no=urlChapter?urlChapter[1]:activeNo;
      if(no)activeWtr=pick('#chapter-'+no+' .chapter-body')||
        pick('#tracker-'+no+' .chapter-body')||
        pick('.chapter-tracker[data-chapter-no="'+no+'"] .chapter-body')||
        pick('[data-chapter-no="'+no+'"] .chapter-body');
      for(var ti=0;!activeWtr&&tr&&ti<6;ti++,tr=tr.parentElement){
        var bodies=tr.querySelectorAll?Array.from(tr.querySelectorAll('.chapter-body')).filter(function(b){return text(b).length>200;}):[];
        if(bodies.length===1)activeWtr=bodies[0];
        else if(bodies.length>1){
          bodies.sort(function(a,b){
            var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
            var av=Math.max(0,Math.min(ar.bottom,innerHeight)-Math.max(ar.top,0));
            var bv=Math.max(0,Math.min(br.bottom,innerHeight)-Math.max(br.top,0));
            return bv-av;
          });
          activeWtr=bodies[0];
        }
      }
      if(!activeWtr&&document.querySelector('.chapter-infinite-reader')){
        var allBodies=Array.from(document.querySelectorAll('.chapter-infinite-reader .chapter-body')).filter(function(b){return text(b).length>200;});
        allBodies.sort(function(a,b){
          var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
          var av=Math.max(0,Math.min(ar.bottom,innerHeight)-Math.max(ar.top,0));
          var bv=Math.max(0,Math.min(br.bottom,innerHeight)-Math.max(br.top,0));
          return bv-av;
        });
        if(allBodies.length)activeWtr=allBodies[0];
      }
    }catch(x){}
    if(activeWtr)content=activeWtr;
  }
  if(content)rootList=[content].concat(rootList.filter(function(x){return x!==content;}));

  // Novel543 can expose a title-only .chapter-content while the real
  // rendered chapter text is in a sibling/container. Evaluate all likely
  // chapter containers after sanitizing scripts/ads, then choose the one
  // with the most real text.
  var hostNow=location.hostname.toLowerCase();
  var chosen=null,chosenUnits=[],chosenScore=-1;
  if(hostNow==='novel543.com'||hostNow.endsWith('.novel543.com')){
    var nsels=['.chapter-content','#content','.article-content','.content','article','main'];
    var nc=[];
    nsels.forEach(function(sel){
      try{Array.from(document.querySelectorAll(sel)).forEach(function(e){
        if(!e||!e.isConnected||!visible(e)||nc.indexOf(e)>=0)return;
        var nt=text(e);
        if(nt.length>=40)nc.push(e);
      });}catch(x){}
    });
    nc.forEach(function(e){
      var nt=text(e),nu=units(e);
      if(!nu.length||nt.length<40)return;
      var codeHits=(nt.match(/(?:function\s*\(|document\.|window\.|TAMadLoadA|TAMedia_AD|adc\.tamedia\.com\.tw|sdk-async-v2)/gi)||[]).length;
      var realLen=Math.max(0,nt.length-codeHits*120);
      var score=realLen*10+Math.min(nu.length,1000);
      if(score>chosenScore){chosen=e;chosenUnits=nu;chosenScore=score;}
    });
  } else {
    var partial=null,partialUnits=[];
    for(var r=0;r<rootList.length;r++){
      var u=units(rootList[r]);
      if(u.length>=3){chosen=rootList[r];chosenUnits=u;break;}
      if(u.length>partialUnits.length){partial=rootList[r];partialUnits=u;}
    }
    if(!chosen&&partial){chosen=partial;chosenUnits=partialUnits;}
  }

  // Last resort: reader-like containers, still bounded and never a whole-page scan.
  if(!chosen){
    var cand=Array.from(document.querySelectorAll('[class*="chapter"],[class*="content"],[class*="words"],[id*="chapter"],[id*="content"],[id*="words"]'));
    cand.sort(function(a,b){return text(b).length-text(a).length;});
    for(var c=0;c<Math.min(12,cand.length);c++){
      if(text(cand[c]).length<200)continue;
      var cu=units(cand[c]);
      if(cu.length>=3){chosen=cand[c];chosenUnits=cu;break;}
    }
  }

  if(!chosen||!chosenUnits.length)return JSON.stringify({ok:false,why:'no-units',roots:rootList.length});

  var titleEl=pick(__TITLE__);
  if(!titleEl){try{titleEl=document.querySelector('.chapter-title,.chr-title,#chapter-heading,h1,h2');}catch(x){}}
  var title=text(titleEl);
  if(!title)title=document.title||'';

  var isN543=hostNow==='novel543.com'||hostNow.endsWith('.novel543.com');
  // Novel543 splits one chapter into pages: 8095_46.html -> 8095_46_2.html -> 8095_47.html.
  // Only the site's own next/prev link knows the real neighbour, so read it by its TEXT
  // (not by nth-child position, which changes between page 1 / middle / last page).
  function n543ok(h){
    try{
      var u=new URL(h,location.href),m=u.pathname.match(/^\/(\d+)\/\d+_\d+(?:_\d+)?\.html$/);
      return !!m&&u.pathname.split('/')[1]===location.pathname.split('/')[1];
    }catch(x){return false;}
  }
  function byText(kind){
    var nx=kind==='next';
    if(hostNow.indexOf('wtr-lab.com')>=0){
      try{
        var current=new URL(location.href);
        var cm=current.pathname.match(/^(.*\/chapter-)(\d+)\/?$/i);
        if(cm){
          var wanted=Number(cm[2])+(nx?1:-1);
          if(wanted>0){
            var prefix=cm[1], candidatePath=prefix+wanted;
            var links=Array.from(document.querySelectorAll('a[href]'));
            var direct=links.find(function(a){
              try{
                var u=new URL(a.href,location.href);
                return u.origin===current.origin &&
                  u.pathname.replace(/\/$/,'')===candidatePath &&
                  u.pathname.split('/').slice(0,-1).join('/')===current.pathname.replace(/\/$/,'').split('/').slice(0,-1).join('/');
              }catch(x){return false;}
            });
            if(direct)return direct;
          }
        }
      }catch(x){}
    }
    var P1=nx?/下一[頁页]|^下[頁页]$|next page/i:/上一[頁页]|^上[頁页]$|prev(?:ious)? page/i;
    var P2=nx?/下一[章节節话話回篇]|^下[章节節]$|next chapter|^next\b|次の話|次へ|다음/i:/上一[章节節话話回篇]|^上[章节節]$|prev(?:ious)? chapter|^prev|前の話|前へ|이전/i;
    var cur=location.href.split('#')[0],best=null,bs=0;
    var as=document.querySelectorAll('a[href]');
    for(var i=0;i<as.length;i++){
      var a=as[i],h=a.href||'';
      if(!/^https?:/i.test(h)||h.split('#')[0]===cur)continue;
      var t=(a.innerText||a.textContent||'').replace(/\s+/g,'');
      if(t.length>14)continue;
      var u=(a.getAttribute('title')||'')+' '+(a.getAttribute('aria-label')||'');
      var sc=0;
      if(P1.test(t)||P1.test(u))sc=3;else if(P2.test(t)||P2.test(u))sc=2;
      if(!sc)continue;
      if(isN543&&!n543ok(h))continue;
      if(sc>bs){best=a;bs=sc;}
    }
    return best;
  }
  function link(kind,sel){
    var e=null;
    if(isN543)e=byText(kind);          // never trust a fixed nth-child here
    if(!e)e=pick(sel);
    if(!e&&!isN543)e=byText(kind);
    if(!e){
      var q=kind==='next'
        ? 'link[rel="next"],a[rel="next"],a.next,.next a,.next-chapter a,.chapter-next a,'+
          'a[class*="next" i][href],[data-testid*="next" i] a,[aria-label*="next" i],[title*="next" i]'
        : 'link[rel="prev"],a[rel="prev"],a.prev,.prev a,.prev-chapter a,.chapter-prev a,'+
          'a[class*="prev" i][href],[data-testid*="prev" i] a,[aria-label*="prev" i],[title*="prev" i]';
      try{e=document.querySelector(q);}catch(x){e=null;}
    }
    if(e&&e.href&&(!isN543||n543ok(e.href)))return {href:e.href,text:text(e),selector:stable(e)};
    return null;
  }

  var segs;
  if(isN543&&chosen){
    // Always use sanitized rendered text. Never pass page JavaScript to the translator.
    var rootText=text(chosen);
    var lines=rootText.split(/\n+/).map(function(s){return clean(s);}).filter(Boolean);
    lines=lines.filter(function(s){
      return meaningful(s)&&!/(?:^function\s*\(|^\(function\s*\(|document\.|window\.|TAMadLoadA|TAMedia_AD|adc\.tamedia\.com\.tw|sdk-async-v2|createElement\(|insertBefore\()/i.test(s);
    });
    if(title)lines=lines.filter(function(s){return s!==title;});
    rootText=lines.join('\n\n');
    segs=rootText.split(/\n\s*\n/).map(function(s){return clean(s);}).filter(function(s){
      return meaningful(s)&&s.length>=2&&!/(?:TAMadLoadA|TAMedia_AD|adc\.tamedia\.com\.tw|sdk-async-v2|document\.|window\.|createElement\(|insertBefore\()/i.test(s);
    });
  }else{
    segs=chosenUnits.map(function(e){return text(e);})
      .map(function(s){return clean(s);})
      .filter(function(s){
        if(!meaningful(s))return false;
        if(/^function\s*\(|^\(function\s*\(/.test(s))return false;
        if(/(?:document\.|window\.|TAMadLoadA|TAMedia_AD|adc\.tamedia\.com\.tw|sdk-async-v2)/i.test(s))return false;
        return true;
      });
    if(title){
      var filtered=segs.filter(function(s){return s!==title;});
      if(filtered.length)segs=filtered;
    }
  }
  // WTR-LAB sometimes renders a chapter as one wrapper with <br> / text-node
  // paragraph breaks instead of real <p> or .wtr-line elements. In that case the
  // generic leaf-unit collector returns one huge unit, so recover the rendered
  // paragraph boundaries from innerText before Kotlin adds internal markers.
  if(hostNow.indexOf('wtr-lab.com')>=0 && chosen && chosenUnits.length<3){
    var wtrText=text(chosen);
    if(title){
      wtrText=wtrText.split(/\n+/).filter(function(s){return clean(s)!==title;}).join('\n');
      if(wtrText.trim()===title)wtrText='';
    }
    var wtrBlocks=wtrText.split(/\n\s*\n/).map(function(s){return clean(s);}).filter(function(s){return meaningful(s)&&s.length>1;});
    if(wtrBlocks.length<3){
      wtrBlocks=wtrText.split(/\n+/).map(function(s){return clean(s);}).filter(function(s){return meaningful(s)&&s.length>1;});
    }
    // Use recovered units only when they look like actual rendered paragraph/line
    // blocks. If there are no DOM line breaks, Kotlin's sentence fallback handles it.
    if(wtrBlocks.length>=3)segs=wtrBlocks;
  }

  var body=segs.join('\n\n');
  var novel='';
  try{
    var m=document.querySelector('meta[property="og:novel:book_name"],meta[property="og:novel:novel_name"],meta[name="book_name"]');
    novel=m?(m.getAttribute('content')||'').trim():'';
  }catch(x){}
  if(!novel){
    try{
      var a=document.querySelector('a[href*="/novel/"],a[href*="/book/"],a[href*="/series/"]');
      if(a)novel=text(a);
    }catch(x){}
  }

  return JSON.stringify({
    ok:true,
    text:body,
    segments:segs,
    count:segs.length,
    title:title,
    pageTitle:document.title||'',
    novel:novel,
    contentSel:stable(chosen),
    titleSel:titleEl?stable(titleEl):'',
    next:link('next',__NEXT__),
    prev:link('prev',__PREV__)
  });
})()
""".trimIndent()
            .let { "(function(){try{return (" + it.trim() + ")}catch(e){return JSON.stringify({ok:false,why:'js-error: '+String(e)});}})()" }
            .replace("__CONTENT__", org.json.JSONObject.quote(contentSel))
            .replace("__TITLE__", org.json.JSONObject.quote(titleSel))
            .replace("__NEXT__", org.json.JSONObject.quote(nextSel))
            .replace("__PREV__", org.json.JSONObject.quote(prevSel))
    }




}
