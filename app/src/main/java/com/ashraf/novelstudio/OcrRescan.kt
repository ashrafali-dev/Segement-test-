package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log

/**
 * Second look for text the first OCR pass left unread.
 *
 * ML Kit's detector sometimes skips a line (glowing / outlined / stylised lettering) while it reads
 * the lines around it. The old retry only fired when the first pass found almost nothing, so a panel
 * with 3 good blocks and 1 missing line was never looked at again.
 *
 * Here the picture itself is checked: strong-edge pixels that sit OUTSIDE every block ML Kit already
 * returned are grouped into wide, line-shaped regions. Each region is cut out, enlarged, and read
 * again (plain, bright-mask and contrast-stretched looks). Anything new that does not overlap an
 * existing block is added, and the blocks are merged again so a continuation line joins its sentence.
 *
 * Two kinds of region are read:
 *  - a line the first pass missed completely (ink that no block explains), and
 *  - a line the first pass read only PARTLY: leftover ink sits on the same row right next to a block
 *    ("YOU [DOIN]G?" where only the middle was read). The whole row is read again and, if that gives
 *    more letters, replaces the fragment. Before, such leftovers were never revisited, so half of a
 *    sentence stayed in English while the fragment was translated on its own.
 *
 * The search repeats (a few rounds) because every recovered line can reveal more unread ink.
 *
 * Pure addition: if anything fails, the caller keeps the original result.
 */
internal object OcrRescan {
    private const val TAG = "NSOcrRescan"

    // Tuned on a real webtoon screenshot (glowing serif caption between two bubbles).
    private const val GRID_W = 800        // analysis width in pixels
    private const val EDGE_T = 55         // gradient strength that counts as an edge
    private const val GAP = 5             // horizontal gap (grid px) bridged between letters
    private const val MIN_W = 16          // region must be at least this wide (grid px)
    private const val MIN_H = 4
    private const val MAX_H = 100
    private const val MIN_ASPECT = 1.3f   // text lines are wide, not square blobs
    private const val DENS_LO = 0.055f     // share of edge pixels in the region's box
    private const val DENS_HI = 0.65f

    /** [anchor] is the block that only partly explains this row (null for a completely unread line). */
    internal class Region(val l: Int, val t: Int, val r: Int, val b: Int, val anchor: OcrBlock? = null)

    private class Comp(val l: Int, val t: Int, val r: Int, val b: Int, val e: Int, val ok: Boolean, val score: Float)

    private fun luma(p: Int): Int =
        (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10

    private fun letters(s: String): Int = s.count { it.isLetterOrDigit() }

    /** Line-shaped ink regions (original pixel coordinates) that no block in [covered] explains. */
    fun findUncovered(bmp: Bitmap, covered: List<OcrBlock>, maxRegions: Int): List<Region> {
        val bw = bmp.width
        val bh = bmp.height
        if (bw < 48 || bh < 24 || maxRegions <= 0) return emptyList()
        val s = if (bw > GRID_W) GRID_W.toFloat() / bw else 1f
        val gw = Math.max(8, (bw * s).toInt())
        val gh = Math.max(8, (bh * s).toInt())
        val sx = gw.toFloat() / bw
        val sy = gh.toFloat() / bh

        val small = if (gw == bw && gh == bh) bmp else Bitmap.createScaledBitmap(bmp, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (small !== bmp) small.recycle()
        val lum = IntArray(gw * gh)
        for (i in px.indices) lum[i] = luma(px[i])

        // 1) strong edges
        val edge = BooleanArray(gw * gh)
        for (y in 1 until gh - 1) {
            val row = y * gw
            for (x in 1 until gw - 1) {
                val i = row + x
                val d = Math.abs(lum[i + 1] - lum[i - 1]) + Math.abs(lum[i + gw] - lum[i - gw])
                if (d > EDGE_T) edge[i] = true
            }
        }

        // 2) blank out everything the first pass already explained (with a little margin for glow)
        for (b in covered) {
            val a = Math.toRadians(Math.abs(b.angle).toDouble())
            val c = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            val hx = (b.w * c + b.h * sn) / 2f
            val hy = (b.w * sn + b.h * c) / 2f
            val font = if (b.fontPx > 0f) b.fontPx else b.h
            val pad = ((font * sy * 0.08f).toInt()).coerceIn(1, 2)
            val x0 = (((b.cx - hx) * sx).toInt() - pad).coerceIn(0, gw)
            val x1 = (((b.cx + hx) * sx).toInt() + pad).coerceIn(0, gw)
            val y0 = (((b.cy - hy) * sy).toInt() - pad).coerceIn(0, gh)
            val y1 = (((b.cy + hy) * sy).toInt() + pad).coerceIn(0, gh)
            for (yy in y0 until y1) {
                val row = yy * gw
                for (xx in x0 until x1) edge[row + xx] = false
            }
        }

        // 3) bridge the gaps between letters sideways, then thicken by one row
        val closed = BooleanArray(gw * gh)
        for (y in 0 until gh) {
            val row = y * gw
            var filledTo = -1
            for (x in 0 until gw) {
                if (!edge[row + x]) continue
                val end = Math.min(gw - 1, x + GAP)
                var xx = Math.max(x - GAP, filledTo + 1)
                while (xx <= end) { closed[row + xx] = true; xx++ }
                if (end > filledTo) filledTo = end
            }
        }
        val grown = BooleanArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                if (closed[i] || (y > 0 && closed[i - gw]) || (y < gh - 1 && closed[i + gw])) grown[i] = true
            }
        }

        // 4) connected components. Every component with a little ink is kept (small leftovers next to a
        //    block matter for partly read rows); `ok` marks the ones that look like a whole text line.
        val comps = ArrayList<Comp>()
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        for (start in 0 until gw * gh) {
            if (!grown[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = gw; var maxX = -1; var minY = gh; var maxY = -1
            while (sp > 0) {
                val cur = stack[--sp]
                val cy = cur / gw
                val cx = cur - cy * gw
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0 && grown[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; stack[sp++] = cur - 1 }
                if (cx < gw - 1 && grown[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; stack[sp++] = cur + 1 }
                if (cy > 0 && grown[cur - gw] && !seen[cur - gw]) { seen[cur - gw] = true; stack[sp++] = cur - gw }
                if (cy < gh - 1 && grown[cur + gw] && !seen[cur + gw]) { seen[cur + gw] = true; stack[sp++] = cur + gw }
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            var e = 0
            for (yy in minY..maxY) {
                val row = yy * gw
                for (xx in minX..maxX) if (edge[row + xx]) e++
            }
            if (e < 6) continue
            val dens = e.toFloat() / (w * h)
            val ok = w >= MIN_W && h >= MIN_H && h <= MAX_H &&
                w.toFloat() / h >= MIN_ASPECT && dens >= DENS_LO && dens <= DENS_HI
            val l = (minX / sx).toInt()
            val r = ((maxX + 1) / sx).toInt()
            val t = (minY / sy).toInt()
            val b = ((maxY + 1) / sy).toInt()
            comps.add(Comp(l, t, r, b, e, ok, w * h * dens))
        }

        fun region(l0: Int, t0: Int, r0: Int, b0: Int, anchor: OcrBlock?): Region? {
            val hPx = b0 - t0
            val padX = Math.max(6, (hPx * 0.25f).toInt())
            val padY = Math.max(6, (hPx * 0.35f).toInt())
            val l = Math.max(0, l0 - padX)
            val t = Math.max(0, t0 - padY)
            val r = Math.min(bw, r0 + padX)
            val b = Math.min(bh, b0 + padY)
            return if (r - l >= 12 && b - t >= 8) Region(l, t, r, b, anchor) else null
        }

        val out = ArrayList<Region>()
        val used = BooleanArray(comps.size)

        // 5) partly read rows: leftover ink on the same row, right beside a block, belongs to that line.
        //    Tall things (bubble outline, artwork) and far-away ink are not part of the row.
        class Ext(val reg: Region, val size: Int)
        val exts = ArrayList<Ext>()
        for (blk in covered) {
            if (blk.vertical || Math.abs(OcrPost.usableTilt(blk.angle)) >= 6f) continue
            val lh = if (blk.h > 0f) blk.h else continue
            if (lh < 8f) continue
            var L = blk.cx - blk.w / 2f; var R = blk.cx + blk.w / 2f
            var T = blk.cy - lh / 2f; var B = blk.cy + lh / 2f
            val mine = ArrayList<Int>()
            var changed = true
            while (changed) {
                changed = false
                for (i in comps.indices) {
                    if (used[i] || i in mine) continue
                    val c = comps[i]
                    val ch = (c.b - c.t).toFloat()
                    if (ch > lh * 2.2f || ch < lh * 0.3f) continue
                    if (Math.abs((c.t + c.b) / 2f - blk.cy) > lh * 0.75f) continue
                    val gap = Math.max(0f, Math.max(c.l - R, L - c.r))
                    if (gap > lh * 3f) continue
                    mine.add(i); changed = true
                    L = Math.min(L, c.l.toFloat()); R = Math.max(R, c.r.toFloat())
                    T = Math.min(T, c.t.toFloat()); B = Math.max(B, c.b.toFloat())
                }
            }
            if (mine.isEmpty()) continue
            // a speck (the tail of a "!", a stray dot) is not worth another OCR pass
            val leftoverW = mine.sumOf { (comps[it].r - comps[it].l).toDouble() }
            if (leftoverW < lh * 0.6) continue
            for (i in mine) used[i] = true
            region(L.toInt(), T.toInt(), R.toInt(), B.toInt(), blk)?.let { exts.add(Ext(it, mine.size)) }
        }
        exts.sortByDescending { (it.reg.r - it.reg.l) * (it.reg.b - it.reg.t) }
        for (x in exts) { if (out.size >= maxRegions) break; out.add(x.reg) }

        // 6) lines nothing explains at all
        val order = comps.indices.filter { comps[it].ok && !used[it] }.sortedByDescending { comps[it].score }
        for (i in order) {
            if (out.size >= maxRegions) break
            val c = comps[i]
            region(c.l, c.t, c.r, c.b, null)?.let { out.add(it) }
        }
        return out
    }

    /** True when the picture's own background (median of its border) is light: dark lettering on paper. */
    private fun lightBackground(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2) return true
        val v = IntArray(240)
        var n = 0
        val step = Math.max(1, (2 * (w + h)) / 200)
        var x = 0
        while (x < w && n < v.size - 1) { v[n++] = luma(bmp.getPixel(x, 0)); v[n++] = luma(bmp.getPixel(x, h - 1)); x += step }
        var y = 0
        while (y < h && n < v.size - 1) { v[n++] = luma(bmp.getPixel(0, y)); v[n++] = luma(bmp.getPixel(w - 1, y)); y += step }
        java.util.Arrays.sort(v, 0, n)
        return v[n / 2] >= 128
    }

    /**
     * Reads one region: enlarged, then a second look chosen for the background; the plain look wins
     * unless another look reads clearly more.
     *
     * The bright-mask look keeps only BRIGHT pixels, which is right for white lettering on black and wrong
     * for ordinary dark lettering in a white bubble: there it turns the bubble black and the letters white,
     * and the recogniser answers with junk ("DOING" -> "ONIO") that the old "most letters wins" rule could
     * pick over the good plain reading. So it is only used on dark backgrounds, and a look has to beat the
     * plain one by a margin.
     */
    private fun readRegion(
        engine: OcrEngine, bmp: Bitmap, reg: Region, script: OcrScript, fastMode: Boolean
    ): List<OcrBlock> {
        val cw = reg.r - reg.l
        val ch = reg.b - reg.t
        if (cw < 12 || ch < 8) return emptyList()
        val crop = Bitmap.createBitmap(bmp, reg.l, reg.t, cw, ch)
        val made = ArrayList<Bitmap>()
        try {
            var f = Math.min(3f, Math.max(1f, 96f / ch))
            val longest = Math.max(cw, ch) * f
            if (longest > 2400f) f = Math.max(1f, f * 2400f / longest)
            val base: Bitmap = if (f > 1.05f) {
                val up = Bitmap.createScaledBitmap(crop, (cw * f).toInt(), (ch * f).toInt(), true)
                made.add(up)
                up
            } else crop
            val looks = ArrayList<Bitmap>()
            looks.add(base)
            // Latin recognizeRegion already performs scale, contrast and polarity views. Do not
            // multiply those passes again for every uncovered row.
            if (script == OcrScript.LATIN) {
                // base is the sole input; the engine supplies the additional views.
            } else if (lightBackground(base)) {
                if (!fastMode) {
                    val boosted = OcrPost.boostContrast(base)
                    made.add(boosted)
                    looks.add(boosted)
                }
            } else {
                val mask = OcrPost.brightMask(base)
                made.add(mask)
                looks.add(mask)
                if (!fastMode) {
                    val boosted = OcrPost.boostContrast(base)
                    made.add(boosted)
                    looks.add(boosted)
                }
            }
            val best = ArrayList<OcrBlock>()
            for (look in looks) {
                // One cropped line/row is easy for the recogniser; the 4-pass "region" engine made every
                // rescanned row cost as much as a whole screen. Only non-fast, non-Latin reads keep it.
                val res = if (fastMode || script == OcrScript.LATIN) engine.recognizeFast(look, script)
                    else engine.recognizeRegion(look, script)
                OcrLineFusion.addAll(best, res.blocks.filterNot { OcrPost.isNoise(it) })
            }
            val sc = base.width.toFloat() / cw
            return best.map { b ->
                val s = OcrPost.scaled(b, 1f / sc)
                s.copy(top = s.top + reg.t, left = s.left + reg.l, cx = s.cx + reg.l, cy = s.cy + reg.t)
            }
        } finally {
            for (m in made) m.recycle()
            if (crop !== bmp) crop.recycle()
        }
    }

    private fun overlapOfRegions(a: Region, b: Region): Float {
        val x = Math.min(a.r, b.r) - Math.max(a.l, b.l)
        val y = Math.min(a.b, b.b) - Math.max(a.t, b.t)
        if (x <= 0 || y <= 0) return 0f
        val small = Math.min((a.r - a.l) * (a.b - a.t), (b.r - b.l) * (b.b - b.t))
        return if (small <= 0) 0f else x.toFloat() * y / small
    }

    /**
     * [base] is the normal OCR result for [bmp]. Returns [base] plus any lines found by the second
     * look (and completed rows that were only partly read), or [base] itself when nothing changed.
     */
    fun augment(
        ctx: Context, engine: OcrEngine, bmp: Bitmap, base: OcrResult,
        script: OcrScript, fastMode: Boolean
    ): OcrResult {
        val debug = try { Prefs.bool(ctx, "ocrDebug", false) } catch (_: Exception) { false }
        val current = ArrayList<OcrBlock>(base.blocks)
        val found = ArrayList<OcrBlock>()
        val empty = ArrayList<Region>()
        val tried = ArrayList<Region>()
        // English captions with many missed rows must not stop after two regions. Scale the bounded
        // audit to the slice height; retain the small latency budget for the other language engines.
        var budget = if (script == OcrScript.LATIN) {
            if (fastMode) (bmp.height / 160).coerceIn(3, 6) else (bmp.height / 120).coerceIn(8, 16)
        } else if (fastMode) 2 else 5
        var changed = false
        var regionCount = 0

        var round = 0
        val maxRounds = if (script == OcrScript.LATIN) (if (fastMode) 2 else 4) else if (fastMode) 2 else 3
        while (round < maxRounds && budget > 0) {
            round++
            val proposed: List<Region> = try {
                findUncovered(bmp, current, budget + tried.size)
            } catch (_: Exception) {
                emptyList()
            } catch (_: OutOfMemoryError) {
                emptyList()
            }
            // a region that already gave nothing is not read a second time
            val regions = proposed.filter { p -> tried.none { overlapOfRegions(it, p) > 0.6f } }.take(budget)
            if (regions.isEmpty()) break
            regionCount += regions.size
            var progressed = false
            for (reg in regions) {
                budget--
                tried.add(reg)
                val got: List<OcrBlock> = try {
                    readRegion(engine, bmp, reg, script, fastMode)
                } catch (_: Exception) {
                    emptyList()
                } catch (_: OutOfMemoryError) {
                    emptyList()
                }
                val anchor = reg.anchor
                val usable = got.filter { !OcrPost.isNoise(it) }
                if (usable.isEmpty()) { empty.add(reg); continue }
                if (anchor != null) {
                    val combined = usable.sortedWith(compareBy({ it.top }, { it.left })).joinToString(" ") { it.text }
                    // Do not replace a valid fragment with longer but unrelated detector noise.
                    if (letters(combined) <= letters(anchor.text) ||
                        OcrLineFusion.agreement(anchor.text, combined) < 0.88f) {
                        empty.add(reg); continue
                    }
                }
                val before = current.toList()
                OcrLineFusion.addAll(current, usable)
                if (before != current) {
                    found.addAll(usable)
                    progressed = true
                } else empty.add(reg)
            }
            if (progressed) changed = true
        }
        if (regionCount > 0) {
            Log.i(TAG, "rounds=$round regions=$regionCount recovered=${found.size} script=$script fast=$fastMode")
        }
        if (debug) {
            try { saveDebug(ctx, bmp, base.blocks, found, empty) } catch (_: Exception) {} catch (_: OutOfMemoryError) {}
        }
        if (!changed) return base
        // Caller performs paragraph grouping after all line-level audits are complete.
        return OcrResult(current)
    }

    // ------------------------------------------------------------------ Google Lens: selective audit

    private val auditPool = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ns-lens-audit").apply { isDaemon = true }
    }
    @Volatile private var auditMisses = 0
    @Volatile private var auditPausedUntil = 0L

    /**
     * Speed-neutral completeness check for a remote (Google Lens) read.
     *  1. A purely local pixel pass ([findUncovered], a few ms) looks for text-like ink that NO Lens block covers.
     *  2. Nothing suspicious (the normal case) -> [base] is returned immediately: zero added latency.
     *  3. Only the suspicious spots (max 2) are cropped and sent to Lens IN PARALLEL, with a hard deadline; the
     *     whole page is never re-sent. The Lens engine caches by image bytes, so an unchanged crop is free.
     *  4. Self-limiting: three audits in a row that recover nothing (artwork that merely looks like text) pause
     *     the audit for 20 s, so a page full of drawings cannot slow the reader down.
     */
    fun augmentRemote(
        engine: OcrEngine, bmp: Bitmap, base: OcrResult, script: OcrScript, deadlineMs: Long = 1500L
    ): OcrResult {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < auditPausedUntil) return base
        val regions: List<Region> = try {
            findUncovered(bmp, base.blocks, 2)
        } catch (_: Exception) { emptyList() } catch (_: OutOfMemoryError) { emptyList() }
        if (regions.isEmpty()) return base
        val tasks = regions.map { reg ->
            java.util.concurrent.Callable<List<OcrBlock>> {
                try { readRegion(engine, bmp, reg, script, true) } catch (_: Exception) { emptyList() }
            }
        }
        val futures = try {
            auditPool.invokeAll(tasks, deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); return base
        }
        val current = ArrayList<OcrBlock>(base.blocks)
        var recovered = 0
        for ((i, f) in futures.withIndex()) {
            if (f.isCancelled) continue                       // missed the deadline: keep the fast result
            val got = try { f.get() } catch (_: Exception) { continue }
            val usable = got.filter { !OcrPost.isNoiseLens(it) }
            if (usable.isEmpty()) continue
            val anchor = regions[i].anchor
            if (anchor != null) {
                val combined = usable.sortedWith(compareBy({ it.top }, { it.left })).joinToString(" ") { it.text }
                if (letters(combined) <= letters(anchor.text) || OcrLineFusion.agreement(anchor.text, combined) < 0.88f) continue
            }
            val before = current.toList()
            OcrLineFusion.addAll(current, usable)
            if (before != current) recovered += usable.size
        }
        if (recovered == 0) {
            if (++auditMisses >= 3) { auditPausedUntil = now + 20_000L; auditMisses = 0 }
        } else auditMisses = 0
        Log.i(TAG, "lens-audit regions=${regions.size} recovered=$recovered ms=${android.os.SystemClock.elapsedRealtime() - now}")
        return if (recovered > 0) OcrResult(current) else base
    }

    // ------------------------------------------------------------------ debug images

    private var savedCount = 0

    /**
     * Green = blocks from the normal pass, orange = lines recovered by the rescan,
     * red = regions that were re-read but gave nothing. Saved to the private ocr_debug folder (newest 12 kept).
     */
    @Synchronized
    private fun saveDebug(
        ctx: Context, bmp: Bitmap, base: List<OcrBlock>, found: List<OcrBlock>, empty: List<Region>
    ) {
        if (savedCount >= 12) return
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return
        try {
            val canvas = Canvas(copy)
            val p = Paint()
            p.style = Paint.Style.STROKE
            p.strokeWidth = Math.max(2f, copy.width / 300f)
            fun box(b: OcrBlock, color: Int) {
                p.color = color
                canvas.drawRect(b.cx - b.w / 2f, b.cy - b.h / 2f, b.cx + b.w / 2f, b.cy + b.h / 2f, p)
            }
            for (b in base) box(b, Color.GREEN)
            for (b in found) box(b, Color.rgb(255, 140, 0))
            p.color = Color.RED
            for (r in empty) canvas.drawRect(r.l.toFloat(), r.t.toFloat(), r.r.toFloat(), r.b.toFloat(), p)
            write(ctx, copy)
            savedCount++
        } finally {
            copy.recycle()
        }
    }

    /**
     * Debug pictures go to the app's PRIVATE folder (Android/data/<app>/files/ocr_debug), never to the
     * Gallery: they used to flood Pictures/NovelStudioOCR with dozens of marked-up screenshots.
     * Only the newest [KEEP] files are kept.
     */
    private const val KEEP = 12

    private fun write(ctx: Context, bm: Bitmap) {
        val dir = ctx.getExternalFilesDir("ocr_debug") ?: java.io.File(ctx.filesDir, "ocr_debug")
        dir.mkdirs()
        val name = "ocr_" + System.currentTimeMillis() + "_" + savedCount + ".jpg"
        java.io.FileOutputStream(java.io.File(dir, name)).use { bm.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in files.dropLast(KEEP)) runCatching { f.delete() }
    }

    /** Called at app start: the debug switch is a per-session tool, it must not silently stay on. */
    fun resetDebug(ctx: Context) {
        try { Prefs.putBool(ctx, "ocrDebug", false) } catch (_: Exception) {}
    }
}
