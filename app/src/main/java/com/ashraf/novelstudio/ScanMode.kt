package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap

/**
 * How a Scanning-mode job moves through the chapter.
 *
 * SCROLL = normal webtoon (scroll down). RTL / LTR = page-by-page canvas readers (Japanese manga sites):
 * the page is turned sideways instead of scrolled.
 */
internal enum class ScanDirection(val key: String, val label: String) {
    SCROLL("scroll", "Scroll scanning (top to bottom)"),
    RTL("rtl", "Right to left pages (Japanese manga)"),
    LTR("ltr", "Left to right pages");

    val horizontal: Boolean get() = this != SCROLL

    companion object {
        fun fromKey(k: String?): ScanDirection = values().firstOrNull { it.key == k } ?: SCROLL
    }
}

/**
 * Everything that belongs to the Scanning (full chapter, bolt tap) mode and nothing else.
 * Real-time mode never reads these settings.
 *
 * Goal of this mode: accuracy first, speed second. A scan may take longer, but it must not skip a bubble,
 * must not leave the old lettering visible and must not merge two bubbles into one.
 */
internal object ScanMode {
    const val PREF_DIRECTION = "scanDirection"
    const val PREF_ACCURATE = "scanAccurate"
    const val PREF_ASK = "scanAskBefore"

    /** How many times a panel that failed (timeout, Lens error, decode) is fetched and read again. */
    const val RETRY_PASSES = 2

    /** Cover boxes are a little larger than the lettering in accurate mode so no old stroke peeks out. */
    const val COVER_PAD_SCALE = 1.35f

    fun direction(c: Context): ScanDirection = ScanDirection.fromKey(Prefs.get(c, PREF_DIRECTION, "scroll"))
    fun setDirection(c: Context, d: ScanDirection) = Prefs.put(c, PREF_DIRECTION, d.key)

    /** Accurate mode: full OCR pipeline (no fast pass), detector awaited, nothing skipped. Default ON. */
    fun accurate(c: Context): Boolean = Prefs.bool(c, PREF_ACCURATE, true)

    /** Ask the three questions (scroll / right-to-left / left-to-right) before every scan. Default ON. */
    fun askBefore(c: Context): Boolean = Prefs.bool(c, PREF_ASK, true)

    /**
     * Progress that follows the REAL work. Phases:
     *   10..40  downloading images and handing them to Lens
     *   40..96  waiting for Lens answers (one step per finished panel)
     *   96..99  retries of failed panels
     */
    class Progress(total: Int) {
        private val n = total.coerceAtLeast(1)
        fun fetching(index: Int): Int = 10 + (index.coerceIn(0, n) * 30) / n
        fun reading(done: Int): Int = 40 + (done.coerceIn(0, n) * 56) / n
        fun retrying(done: Int, of: Int): Int = 96 + (done.coerceAtLeast(0) * 3) / of.coerceAtLeast(1)
    }

    /**
     * Cuts a bubble's lines (already sorted top to bottom) into separate bubbles wherever the picture shows an
     * outline between two neighbouring lines, or the detector says they sit in different bubbles.
     * Returns the lines grouped per bubble; one group means nothing had to be split.
     */
    fun splitAtOutlines(
        bmp: Bitmap,
        blocks: List<OcrBlock>,
        ordered: List<Int>,
        differentBubbles: (Int, Int) -> Boolean
    ): List<List<Int>> {
        if (ordered.size < 2) return listOf(ordered)
        val out = ArrayList<MutableList<Int>>()
        var cur = ArrayList<Int>().also { it.add(ordered[0]) }
        for (k in 1 until ordered.size) {
            val prev = ordered[k - 1]
            val next = ordered[k]
            val cut = try {
                differentBubbles(prev, next) || OcrPost.outlineBetween(bmp, blocks[prev], blocks[next])
            } catch (_: Throwable) { false }
            if (cut) { out.add(cur); cur = ArrayList() }
            cur.add(next)
        }
        out.add(cur)
        return out
    }

    /** Cheap fingerprint of a screenshot (32x32 sample). Two equal keys in a row = the page did not change. */
    fun quickKey(bmp: Bitmap): Long {
        var small: Bitmap? = null
        return try {
            small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
            val px = IntArray(32 * 32)
            small.getPixels(px, 0, 32, 0, 0, 32, 32)
            var h = 1125899906842597L
            for (p in px) h = 31L * h + (p ushr 3)   // drop the lowest bits: tolerate tiny rendering noise
            h
        } catch (_: Throwable) {
            0L
        } finally {
            try { if (small != null && small !== bmp) small.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * Turns the page sideways. RTL: the next page lies to the LEFT. LTR: to the RIGHT.
     * 1) a horizontally scrolling container is scrolled by one page width;
     * 2) otherwise the reader's own controls are used: arrow key and a tap on the "next" side.
     * Returns {"moved":bool}. The Kotlin side decides "end of chapter" from the screenshot, not from this flag.
     */
    fun jsPageTurn(rtl: Boolean): String = """
var rtl=${if (rtl) "true" else "false"},moved=false,mode='none';
try{
  var de0=document.documentElement,all=document.querySelectorAll('*'),best=null,bestA=0;
  for(var i=0;i<all.length;i++){
    var e=all[i],cs=getComputedStyle(e);
    if((cs.overflowX==='auto'||cs.overflowX==='scroll')&&e.scrollWidth>e.clientWidth+8){
      var a=e.clientWidth*e.clientHeight;if(a>bestA){best=e;bestA=a;}
    }
  }
  if(!best&&de0.scrollWidth>(window.innerWidth||0)+8)best=document.scrollingElement||de0;
  if(best){
    var step=Math.round(best.clientWidth*0.92),b0=best.scrollLeft;
    best.scrollLeft=b0+(rtl?-step:step);
    if(Math.abs(best.scrollLeft-b0)>2){moved=true;mode='scroll';}
  }
  if(!moved){
    var key=rtl?'ArrowLeft':'ArrowRight',code=rtl?37:39;
    ['keydown','keyup'].forEach(function(t){
      var ev=new KeyboardEvent(t,{key:key,code:key,keyCode:code,which:code,bubbles:true,cancelable:true});
      document.dispatchEvent(ev);if(document.body)document.body.dispatchEvent(ev);
    });
    var w=window.innerWidth||360,h=window.innerHeight||640;
    var el=document.elementFromPoint(Math.round(rtl?w*0.1:w*0.9),Math.round(h*0.5));
    if(el){
      ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){
        try{el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window,clientX:Math.round(rtl?w*0.1:w*0.9),clientY:Math.round(h*0.5)}));}catch(x){}
      });
    }
    mode='key+tap';
  }
}catch(err){}
return JSON.stringify({moved:moved,mode:mode});
"""
}
