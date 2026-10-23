package com.ashraf.novelstudio

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView

object Gemini {
    fun isGemini(url: String?): Boolean {
        val host = try { Uri.parse(url ?: "").host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return host == "gemini.google.com" || host.endsWith(".gemini.google.com")
    }

    @Volatile private var lastSentText: String = ""

    fun cleanResponse(text: String): String {
        val sent = lastSentText.trim()
        if (sent.isEmpty()) return text.trim()
        val t = text.trim()
        val at = t.indexOf(sent)
        if (at >= 0) return t.substring(at + sent.length).trim()
        return t
    }

    fun isInternalAuthUrl(uri: Uri): Boolean {
        val host = (uri.host ?: "").lowercase()
        return host == "accounts.google.com" ||
            host.endsWith(".accounts.google.com") ||
            host == "gemini.google.com" ||
            host.endsWith(".gemini.google.com")
    }

    fun send(webView: WebView, text: String, doSend: Boolean, callback: ValueCallback<String>?) {
        lastSentText = text
        val quoted = org.json.JSONObject.quote(text)
        val js = """
(function(text,doSend){
  function visible(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  function all(root,selector,out){
    out=out||[];
    try{var a=root.querySelectorAll(selector);for(var i=0;i<a.length;i++)out.push(a[i]);}catch(e){}
    try{var nodes=root.querySelectorAll('*');for(var j=0;j<nodes.length;j++)if(nodes[j].shadowRoot)all(nodes[j].shadowRoot,selector,out);}catch(e){}
    return out;
  }
  function direct(selectors){
    for(var i=0;i<selectors.length;i++){
      try{
        var a=document.querySelectorAll(selectors[i]);
        for(var j=0;j<a.length;j++) if(visible(a[j])) return a[j];
      }catch(e){}
    }
    return null;
  }
  function findBox(){
    // Gemini normally exposes the composer in the main document. Try the
    // cheap path first; scan shadow roots only when the direct lookup fails.
    var directBox=direct([
      'rich-textarea .ql-editor',
      'rich-textarea [contenteditable="true"]',
      'div.ql-editor[contenteditable="true"]',
      '[aria-label="Enter a prompt here"]',
      '[contenteditable="true"][role="textbox"]',
      'textarea',
      '[role="textbox"]',
      '[contenteditable="true"]'
    ]);
    if(directBox)return directBox;

    var s=['rich-textarea .ql-editor','rich-textarea [contenteditable="true"]','div.ql-editor[contenteditable="true"]','[aria-label="Enter a prompt here"]','[contenteditable="true"][role="textbox"]','[contenteditable="true"]','textarea','[role="textbox"]'];
    var c=[];
    for(var i=0;i<s.length;i++){var a=all(document,s[i],[]);for(var j=0;j<a.length;j++)if(visible(a[j])&&!c.includes(a[j]))c.push(a[j]);}
    c.sort(function(a,b){return b.getBoundingClientRect().height-a.getBoundingClientRect().height;});
    return c[0]||null;
  }
  function nativeSet(e,v){
    try{var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(e,v);}catch(x){e.value=v;}
    e.dispatchEvent(new Event('input',{bubbles:true}));try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:v}));}catch(x){}e.dispatchEvent(new Event('change',{bubbles:true}));
  }
  function assistantNodes(){
    var s='model-response,message-content,.model-response-text,.response-content';
    var a=[];
    try{a=Array.from(document.querySelectorAll(s));}catch(e){}
    // Avoid the expensive recursive shadow-root walk unless Gemini's normal
    // response nodes are not present in the main document.
    if(!a.length)a=all(document,s,[]);
    return a.filter(function(e){
      var r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();
      if(!(r.width>0&&r.height>0&&tx.length>0))return false;
      return !a.some(function(o){return o!==e&&o.contains(e);});
    });
  }
  var before=assistantNodes(),n0=before.length,len0=0;
  if(n0){var last=before[n0-1];len0=(last.innerText||last.textContent||'').trim().length;}
  var box=findBox();if(!box)return 'nobox';
  if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){nativeSet(box,text);}
  else{
    try{
      box.focus();var sel=window.getSelection(),range=document.createRange();range.selectNodeContents(box);sel.removeAllRanges();sel.addRange(range);
      var ok=document.execCommand('insertText',false,text);
      if(!ok||!((box.innerText||box.textContent||'').trim())){
        var safe=String(text).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
        box.innerHTML='<p>'+safe.replace(/\r?\n/g,'<br>')+'</p>';
      }
    }catch(e){box.textContent=text;}
    try{box.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){}
    try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){}
    box.dispatchEvent(new Event('change',{bubbles:true}));
  }
  if(doSend)setTimeout(function(){
    var s=['button[aria-label*="Send" i]','button[aria-label*="Submit" i]','button.send-button','button[data-testid*="send" i]'],btn=null;
    for(var i=0;i<s.length&&!btn;i++){
      var directButtons=[];
      try{directButtons=Array.from(document.querySelectorAll(s[i]));}catch(e){}
      for(var j=0;j<directButtons.length;j++){
        var b=directButtons[j];
        if(visible(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true'){btn=b;break;}
      }
      if(!btn){
        var a=all(document,s[i],[]);
        for(var k=0;k<a.length;k++){var b2=a[k];if(visible(b2)&&!b2.disabled&&b2.getAttribute('aria-disabled')!=='true'){btn=b2;break;}}
      }
    }
    if(btn){btn.click();return;}
    try{box.focus();box.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));box.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}catch(e){}
  },120);
  return 'ok:'+n0+':'+len0;
})(__TEXT__,__SEND__)
""".trimIndent().replace("__TEXT__", quoted).replace("__SEND__", doSend.toString())
        webView.evaluateJavascript(js, callback)
    }
}
