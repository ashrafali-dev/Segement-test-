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
 * Goal of this mode: accuracy first, speed second. A scan may take longer, but it must not skip a bubble
 * and must not merge two bubbles into one.
 */
internal object ScanMode {
    const val PREF_DIRECTION = "scanDirection"
    const val PREF_ACCURATE = "scanAccurate"
    const val PREF_ASK = "scanAskBefore"

    /** How many times a panel that failed (timeout, Lens error, decode) is fetched and read again. */
    const val RETRY_PASSES = 2

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
                differentBubbles(prev, next) || OcrPost.outlineBetween(bmp, blocks[prev], blocks[next], blocks)
            } catch (_: Throwable) { false }
            if (cut) { out.add(cur); cur = ArrayList() }
            cur.add(next)
        }
        out.add(cur)
        return out
    }


    private fun fontOf(b: OcrBlock): Float = if (b.fontPx > 0f) b.fontPx else minOf(b.w, b.h)

    /**
     * Flood-fill test: can you walk from line [a] to line [b] over bubble fill without crossing a dark outline?
     * Two speech bubbles, even when they touch or the outlines are curved, are closed off from each other.
     * (The old test looked for ONE straight dark row between the lines, so curved outlines were never found and
     * two bubbles were read and translated as one.)
     * Conservative: dark artwork, a leak in an outline, or any doubt = "not closed off" (keeps the lines together).
     */
    fun closedOff(img: Bitmap, a: OcrBlock, b: OcrBlock, others: List<OcrBlock> = emptyList()): Boolean {
        try {
            val font = maxOf(fontOf(a), fontOf(b)).coerceAtLeast(8f)
            val m = (font * 0.8f).toInt().coerceAtLeast(10)
            val l = (minOf(a.cx - a.w / 2f, b.cx - b.w / 2f).toInt() - m).coerceIn(0, img.width - 2)
            val t = (minOf(a.cy - a.h / 2f, b.cy - b.h / 2f).toInt() - m).coerceIn(0, img.height - 2)
            val r = (maxOf(a.cx + a.w / 2f, b.cx + b.w / 2f).toInt() + m).coerceIn(l + 2, img.width)
            val bo = (maxOf(a.cy + a.h / 2f, b.cy + b.h / 2f).toInt() + m).coerceIn(t + 2, img.height)
            val w = r - l
            val h = bo - t
            if (w < 12 || h < 12 || w.toLong() * h > 2_500_000L) return false
            val px = IntArray(w * h)
            img.getPixels(px, 0, w, l, t, w, h)
            val f = Math.max(1, Math.ceil(Math.max(w, h) / 240.0).toInt())
            val gw = (w + f - 1) / f
            val gh = (h + f - 1) / f
            val cellMin = IntArray(gw * gh)
            val cellMax = IntArray(gw * gh)
            for (cy in 0 until gh) for (cx in 0 until gw) {
                var mn = 255; var mx = 0
                var y = cy * f
                val ye = minOf(h, y + f)
                while (y < ye) {
                    var x = cx * f
                    val xe = minOf(w, x + f)
                    while (x < xe) {
                        val p = px[y * w + x]
                        val lu = (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
                        if (lu < mn) mn = lu
                        if (lu > mx) mx = lu
                        x++
                    }
                    y++
                }
                cellMin[cy * gw + cx] = mn
                cellMax[cy * gw + cx] = mx
            }
            val sortedMax = cellMax.sortedArray()
            if (sortedMax[sortedMax.size / 2] < 150) return false       // dark bubble / dark artwork: not judged here
            val barrier = BooleanArray(gw * gh) { cellMin[it] < 105 }
            fun markText(bl: OcrBlock) {
                val c0 = ((bl.cx - bl.w / 2f - 3f - l) / f).toInt().coerceIn(0, gw - 1)
                val c1 = ((bl.cx + bl.w / 2f + 3f - l) / f).toInt().coerceIn(0, gw - 1)
                val r0 = ((bl.cy - bl.h / 2f - 3f - t) / f).toInt().coerceIn(0, gh - 1)
                val r1 = ((bl.cy + bl.h / 2f + 3f - t) / f).toInt().coerceIn(0, gh - 1)
                for (yy in r0..r1) for (xx in c0..c1) barrier[yy * gw + xx] = false
            }
            for (o in others) markText(o)
            markText(a); markText(b)
            var dark = 0
            for (v in barrier) if (v) dark++
            if (dark * 100 > barrier.size * 40) return false           // hatching / art everywhere
            val sx = ((a.cx - l) / f).toInt().coerceIn(0, gw - 1)
            val sy = ((a.cy - t) / f).toInt().coerceIn(0, gh - 1)
            val tx0 = ((b.cx - b.w / 2f - l) / f).toInt().coerceIn(0, gw - 1)
            val tx1 = ((b.cx + b.w / 2f - l) / f).toInt().coerceIn(0, gw - 1)
            val ty0 = ((b.cy - b.h / 2f - t) / f).toInt().coerceIn(0, gh - 1)
            val ty1 = ((b.cy + b.h / 2f - t) / f).toInt().coerceIn(0, gh - 1)
            val seen = BooleanArray(gw * gh)
            val q = IntArray(gw * gh)
            var qh = 0; var qt = 0
            q[qt++] = sy * gw + sx; seen[sy * gw + sx] = true
            while (qh < qt) {
                val cur = q[qh++]
                val cxx = cur % gw
                val cyy = cur / gw
                if (cxx in tx0..tx1 && cyy in ty0..ty1) return false      // reached the other line: same bubble
                if (cxx > 0) { val n = cur - 1; if (!seen[n] && !barrier[n]) { seen[n] = true; q[qt++] = n } }
                if (cxx < gw - 1) { val n = cur + 1; if (!seen[n] && !barrier[n]) { seen[n] = true; q[qt++] = n } }
                if (cyy > 0) { val n = cur - gw; if (!seen[n] && !barrier[n]) { seen[n] = true; q[qt++] = n } }
                if (cyy < gh - 1) { val n = cur + gw; if (!seen[n] && !barrier[n]) { seen[n] = true; q[qt++] = n } }
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * A chat/message box: the sender's name sits INSIDE the same grey box as the message, so "one closed outline =
     * one bubble" glued them together. A header line is told apart by its size (bigger or smaller than the body),
     * a clearly larger gap to the body than between the body's own lines, and a shorter width.
     * Returns the member indices (sorted top to bottom) cut into pieces; one piece = nothing to split.
     */
    fun splitHeader(blocks: List<OcrBlock>, ordered: List<Int>): List<List<Int>> {
        if (ordered.size < 2) return listOf(ordered)
        fun med(v: List<Float>): Float = v.sorted().let { it[it.size / 2] }
        fun isOdd(line: Int, rest: List<Int>, lineIsFirst: Boolean): Boolean {
            val restFont = med(rest.map { fontOf(blocks[it]) })
            val lf = fontOf(blocks[line])
            if (restFont <= 0f || lf <= 0f) return false
            val ratio = lf / restFont
            if (ratio > 0.89f && ratio < 1.12f) return false
            val near = if (lineIsFirst) rest.first() else rest.last()
            val gap = Math.abs(blocks[near].cy - blocks[line].cy)
            val typical = if (rest.size >= 2) {
                med(rest.zipWithNext { x, y -> Math.abs(blocks[y].cy - blocks[x].cy) })
            } else restFont * 1.3f
            if (typical <= 0f || gap < typical * 1.45f) return false
            val maxW = rest.maxOf { blocks[it].w }
            return blocks[line].w < maxW * 0.85f
        }
        var from = 0
        var to = ordered.size
        val out = ArrayList<List<Int>>()
        if (to - from >= 2 && isOdd(ordered[from], ordered.subList(from + 1, to), true)) {
            out.add(listOf(ordered[from])); from++
        }
        var tail: List<Int>? = null
        if (to - from >= 2 && isOdd(ordered[to - 1], ordered.subList(from, to - 1), false)) {
            tail = listOf(ordered[to - 1]); to--
        }
        out.add(ordered.subList(from, to))
        if (tail != null) out.add(tail)
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
