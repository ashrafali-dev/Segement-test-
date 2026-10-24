package com.ashraf.novelstudio

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutionException

/** Scripts Web Mode can ask an engine to read. */
internal enum class OcrScript { LATIN, CHINESE, JAPANESE, KOREAN }

/**
 * One recognised text block. All numbers are pixels inside the slice that was scanned.
 * [top]/[left] keep the reading order; the rest describes where the text really sat, so a
 * translation can be put back on the same spot: [cx]/[cy] centre, [w]/[h] size of the (unrotated)
 * text box, [angle] rotation in degrees (clockwise), [fontPx] estimated glyph size, [vertical] for
 * top-to-bottom columns (common in manga).
 */
internal data class OcrBlock(
    val text: String,
    val top: Int,
    val left: Int,
    val cx: Float = left.toFloat(),
    val cy: Float = top.toFloat(),
    val w: Float = 0f,
    val h: Float = 0f,
    val angle: Float = 0f,
    val fontPx: Float = 0f,
    val vertical: Boolean = false,
    val confidence: Float = 0f,
    val observations: Int = 1,
    /** Optional detector quadrilateral in the same image coordinates as the bounding box. */
    val polygon: List<Pair<Float, Float>> = emptyList()
)

internal class OcrResult(val blocks: List<OcrBlock>) {
    val text: String get() = blocks.joinToString("\n\n") { it.text.trim() }.trim()
}

/** Failure reported by an engine (model missing, init failure, ...). */
internal class OcrUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Geometry helpers shared by the engine and Web Mode (pure Kotlin, no Android types). */
internal object OcrPost {
    /**
     * ML Kit sometimes reports stylised or skewed text as turned by ~180 degrees, and the translation
     * was then drawn upside down. Text is never meant to be read upside down, so fold the angle into
     * (-90, 90]. Anything steeper than [MAX_TILT] after that is treated as "not tilted".
     */
    const val MAX_TILT = 50f

    fun foldAngle(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x <= -180f) x += 360f
        if (x > 90f) x -= 180f
        if (x <= -90f) x += 180f
        return x
    }

    fun usableTilt(a: Float): Float {
        val f = foldAngle(a)
        return if (Math.abs(f) <= MAX_TILT) f else 0f
    }

    /** Axis-aligned overlap area of two centre/size boxes. */
    private fun inter(a: OcrBlock, b: OcrBlock): Float {
        val x = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
        val y = Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2)
        return if (x > 0 && y > 0) x * y else 0f
    }

    /** Share of the SMALLER box that lies inside the other one. */
    fun overlapOfSmaller(a: OcrBlock, b: OcrBlock): Float {
        val small = Math.min(a.w * a.h, b.w * b.h)
        return if (small <= 0f) 0f else inter(a, b) / small
    }

    /** Grayscale with strongly stretched contrast: makes glowing / outlined lettering readable for OCR. */
    fun boostContrast(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val cm = android.graphics.ColorMatrix()
        cm.setSaturation(0f)
        val k = 2.2f
        val o = -128f * (k - 1f)
        cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, o,
            0f, k, 0f, 0f, o,
            0f, 0f, k, 0f, o,
            0f, 0f, 0f, 1f, 0f)))
        val p = android.graphics.Paint()
        p.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /**
     * Keeps only the BRIGHT lettering (white text with a glow / dark outline on black) as black on
     * white. The glow haze that confuses the recogniser disappears and the letters come out clean.
     */
    fun brightMask(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val l = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
            px[i] = if (l >= 205) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private const val NEUTRAL_PUNCT = ".,!?…·'\"‘’“”-–—:;()[]「」『』?!。、"

    /** Scan-site watermarks ("asurascans.com", "www.xyz.net") are not dialogue and must never be translated. */
    private val SITE = Regex(
        "(?i)(?:https?://|www\\s?\\.)|\\b[a-z0-9][a-z0-9-]{2,}\\s?\\.(?:com|net|org|io|xyz|site|info|app)\\b"
    )

    /** Drops blocks that are clearly recogniser noise: no letters, mostly symbols, or hair-thin streaks. */
    fun isNoise(b: OcrBlock): Boolean {
        val t = b.text.trim()
        if (t.isEmpty()) return true
        if (SITE.containsMatchIn(t)) return true
        val letters = t.count { it.isLetter() }
        if (letters == 0) return true
        // Punctuation and spaces are neutral: "네, 네?!" is 2 letters + punctuation, and is real speech.
        // Only text that is mostly OTHER symbols (|, /, ~~~, box-drawing...) is recogniser noise.
        val core = t.count { !it.isWhitespace() && it !in NEUTRAL_PUNCT }
        if (core >= 3 && letters * 100 < core * 45) return true
        if (b.h < 4f || b.w < 2f) return true
        // Vertical smear of a few "letters" is noise, but real vertical Japanese/Chinese speech (tategaki) is not.
        if (!b.vertical && b.h > b.w * 6f && t.length <= 4) return true
        return false
    }

    /**
     * True when the text is (mostly) Bengali script. The app paints its own Bengali translation into the page; a
     * screen capture contains those overlays, Lens reads them back and the chatbot "translates" them again
     * (stacked, garbled overlays). With Bengali as the target language nothing worth translating is Bengali.
     */
    fun isMostlyBengaliScript(text: String): Boolean {
        var letters = 0; var bn = 0
        for (c in text) if (c.isLetter()) { letters++; if (c.code in 0x0980..0x09FF) bn++ }
        return letters > 0 && bn * 100 >= letters * 60
    }

    /** True for a scan-site watermark line ("asurascans.com"). Used per LINE so one watermark never drops a whole bubble. */
    fun isSiteMark(text: String): Boolean = SITE.containsMatchIn(text.trim())

    /**
     * Noise filter for Google Lens blocks. Lens is a high-precision recogniser that already returns whole
     * paragraphs, so the ML Kit hallucination rules are too aggressive here: they judged each block alone and
     * could throw away a legitimate line. Only blocks with no letters at all, mostly-symbol blocks and
     * hair-thin streaks are dropped. Digits count as content (a line like "100,000" inside a bubble is kept
     * because the bubble as a whole has letters); watermark lines are removed earlier, line by line.
     */
    fun isNoiseLens(b: OcrBlock): Boolean {
        val t = b.text.trim()
        if (t.isEmpty()) return true
        if (t.none { it.isLetter() }) return true
        val core = t.count { !it.isWhitespace() && it !in NEUTRAL_PUNCT }
        val alnum = t.count { it.isLetterOrDigit() }
        if (core >= 3 && alnum * 100 < core * 45) return true
        if (b.h < 4f || b.w < 2f) return true
        if (!b.vertical && b.h > b.w * 6f && t.length <= 4) return true
        return false
    }

    fun scaled(b: OcrBlock, k: Float): OcrBlock = OcrBlock(
        b.text, (b.top * k).toInt(), (b.left * k).toInt(),
        b.cx * k, b.cy * k, b.w * k, b.h * k, b.angle, b.fontPx * k, b.vertical, b.confidence, b.observations,
        b.polygon.map { Pair(it.first * k, it.second * k) }
    )

    /**
     * ML Kit often cuts ONE speech bubble into several blocks ("WHY IS THIS ROPE SO" / "HARD TO
     * UNTIE?"). The chatbot then got fragments, translated some and left the rest in English.
     * Blocks that sit right under each other, overlap horizontally and have the same text size and
     * tilt are one sentence: join them into one block.
     */
    fun mergeNeighbours(blocks: List<OcrBlock>, image: Bitmap? = null): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val work = ArrayList(blocks.sortedWith(compareBy({ it.cy }, { it.cx })))
        // outlineBetween() samples pixels (slow). The loop restarts after every merge, so remember which
        // pairs were already vetoed instead of sampling the same strip again and again.
        val vetoed = java.util.IdentityHashMap<OcrBlock, java.util.IdentityHashMap<OcrBlock, Boolean>>()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in 0 until work.size) {
                for (j in i + 1 until work.size) {
                    val a = work[i]; val b = work[j]
                    if (vetoed[a]?.containsKey(b) == true) continue
                    if (!joinable(a, b)) continue
                    // NEVER join two lines across a third block that sits between them. That is exactly how the
                    // middle line of a 3-line bubble vanished: top+bottom were fused, the middle one stayed
                    // behind (or was painted over by the fused box) and was never translated.
                    if (hasBlockBetween(work, a, b)) continue
                    // Two stacked speech bubbles are close enough to look like one paragraph by geometry alone.
                    // If the picture shows a bubble outline / artwork between the two texts they are separate.
                    if (image != null && !a.vertical && outlineBetween(image, a, b)) {
                        vetoed.getOrPut(a) { java.util.IdentityHashMap() }[b] = true
                        continue
                    }
                    work[i] = join(a, b)
                    work.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return work.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /**
     * Looks at the strip of the picture between the lower edge of the upper text and the upper edge of the
     * lower one. Lines of one bubble have clean bubble fill between them; two bubbles have an outline and
     * often artwork in that strip. Works for white and black bubbles because it measures how much of the
     * strip differs from the strip's own typical brightness. Returns false (= keep the geometry decision)
     * whenever the strip cannot be sampled.
     */
    fun outlineBetween(img: Bitmap, a: OcrBlock, b: OcrBlock): Boolean {
        try {
            val top = if (a.cy <= b.cy) a else b
            val bot = if (top === a) b else a
            val y0 = (top.cy + top.h / 2f).toInt()
            val y1 = (bot.cy - bot.h / 2f).toInt()
            val gap = y1 - y0
            // Lines of ONE bubble sit tight together (comic lettering has almost no leading). Two bubbles are
            // further apart because two outlines and some padding fit between them. A small gap is therefore
            // never an outline: looking at it only finds the tops/bottoms of the letters themselves.
            val font = Math.max(
                if (a.fontPx > 0f) a.fontPx else Math.min(a.w, a.h),
                if (b.fontPx > 0f) b.fontPx else Math.min(b.w, b.h)
            )
            if (gap < Math.max(10f, font * 0.5f)) return false
            val l = Math.max(a.cx - a.w / 2f, b.cx - b.w / 2f)
            val r = Math.min(a.cx + a.w / 2f, b.cx + b.w / 2f)
            val x0 = l.toInt().coerceIn(0, img.width - 1)
            val x1 = r.toInt().coerceIn(x0 + 1, img.width)
            // only the middle of the gap: the edges still touch the letters
            val ya = (y0 + gap * 0.2f).toInt().coerceIn(0, img.height - 1)
            val yb = (y1 - gap * 0.2f).toInt().coerceIn(ya + 1, img.height)
            if (x1 - x0 < 6 || yb - ya < 2) return false
            val stepX = Math.max(1, (x1 - x0) / 48)
            // brightness of the strip's own background
            val all = ArrayList<Int>()
            var yy = ya
            while (yy < yb) {
                var xx = x0
                while (xx < x1) { all.add(luma(img.getPixel(xx, yy))); xx += stepX }
                yy += Math.max(1, (yb - ya) / 12)
            }
            if (all.size < 12) return false
            val med = all.sorted()[all.size / 2]
            // an outline is a (nearly) continuous dark/light line across the strip: one ROW that differs
            // strongly from the background over most of its width. Scattered ink never does that.
            var y = ya
            while (y < yb) {
                var off = 0
                var n = 0
                var x = x0
                while (x < x1) {
                    if (Math.abs(luma(img.getPixel(x, y)) - med) > 70) off++
                    n++
                    x += stepX
                }
                if (n >= 6 && off * 100 >= n * 60) return true
                y++
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }

    private fun luma(p: Int): Int = (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10

    /**
     * Two upright columns of the same speech bubble (Japanese manga): similar column width, they overlap in height and
     * sit right next to each other. Before, vertical blocks were never joined, so each column became its own bubble:
     * one got the translation and the other kept its original lettering, and the sentence reached the chatbot in pieces.
     */
    internal fun joinableColumns(a: OcrBlock, b: OcrBlock): Boolean {
        if (Math.abs(a.angle) >= 6f || Math.abs(b.angle) >= 6f) return false
        val fa = if (a.fontPx > 0f) a.fontPx else a.w
        val fb = if (b.fontPx > 0f) b.fontPx else b.w
        if (fa <= 0f || fb <= 0f) return false
        val ratio = fa / fb
        if (ratio < 0.6f || ratio > 1.7f) return false
        val f = Math.max(fa, fb)
        val left = if (a.cx <= b.cx) a else b
        val right = if (left === a) b else a
        val gap = (right.cx - right.w / 2) - (left.cx + left.w / 2)
        if (gap > f * 0.9f || gap < -f * 0.5f) return false
        val oy = Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2)
        val shorter = Math.min(a.h, b.h)
        return shorter > 0f && oy >= shorter * 0.4f
    }

    /** True when another block sits vertically between [a] and [b] and overlaps either one horizontally. */
    fun hasBlockBetween(all: List<OcrBlock>, a: OcrBlock, b: OcrBlock): Boolean {
        val top = if (a.cy <= b.cy) a else b
        val bot = if (top === a) b else a
        val y0 = top.cy + top.h * 0.25f
        val y1 = bot.cy - bot.h * 0.25f
        if (y1 <= y0) return false
        for (c in all) {
            if (c === a || c === b) continue
            if (c.cy <= y0 || c.cy >= y1) continue
            if (hOverlapPx(c, a) > 0f || hOverlapPx(c, b) > 0f) return true
        }
        return false
    }

    private fun hOverlapPx(a: OcrBlock, b: OcrBlock): Float =
        Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)

    private fun joinable(a: OcrBlock, b: OcrBlock): Boolean {
        if (a.vertical != b.vertical) return false
        if (a.vertical) return joinableColumns(a, b)
        if (Math.abs(a.angle) >= 6f || Math.abs(b.angle) >= 6f) return false   // clearly tilted text stays as it is
        val fa = if (a.fontPx > 0f) a.fontPx else a.h
        val fb = if (b.fontPx > 0f) b.fontPx else b.h
        if (fa <= 0f || fb <= 0f) return false
        val ratio = fa / fb
        if (ratio < 0.45f || ratio > 2.2f) return false
        val f = Math.max(fa, fb)
        val top = if (a.cy <= b.cy) a else b
        val bot = if (top === a) b else a
        val gap = (bot.cy - bot.h / 2) - (top.cy + top.h / 2)
        if (gap > f * 1.7f || gap < -f * 0.6f) return false
        val ox = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
        val narrow = Math.min(a.w, b.w)
        if (narrow <= 0f || ox < narrow * 0.3f) return false
        // centred lines of one bubble share a middle; a side-by-side caption does not
        if (Math.abs(a.cx - b.cx) > Math.max(a.w, b.w) * 0.7f) return false
        return true
    }

    private fun join(a: OcrBlock, b: OcrBlock): OcrBlock {
        if (a.vertical && b.vertical) {
            // Japanese columns are read from the right one to the left one.
            val rightB = if (a.cx >= b.cx) a else b
            val leftB = if (rightB === a) b else a
            val l = Math.min(a.cx - a.w / 2, b.cx - b.w / 2)
            val r = Math.max(a.cx + a.w / 2, b.cx + b.w / 2)
            val t = Math.min(a.cy - a.h / 2, b.cy - b.h / 2)
            val bt = Math.max(a.cy + a.h / 2, b.cy + b.h / 2)
            return OcrBlock(
                rightB.text.trim() + " " + leftB.text.trim(), t.toInt(), l.toInt(),
                (l + r) / 2f, (t + bt) / 2f, r - l, bt - t, 0f,
                Math.max(a.fontPx, b.fontPx), true
            )
        }
        val top = if (a.cy <= b.cy) a else b
        val bot = if (top === a) b else a
        val l = Math.min(a.cx - a.w / 2, b.cx - b.w / 2)
        val r = Math.max(a.cx + a.w / 2, b.cx + b.w / 2)
        val t = Math.min(a.cy - a.h / 2, b.cy - b.h / 2)
        val bt = Math.max(a.cy + a.h / 2, b.cy + b.h / 2)
        val text = top.text.trim() + " " + bot.text.trim()
        return OcrBlock(
            text, t.toInt(), l.toInt(),
            (l + r) / 2f, (t + bt) / 2f, r - l, bt - t, 0f,
            Math.max(a.fontPx, b.fontPx), false
        )
    }
}

/**
 * The only OCR surface the rest of the app sees. Swap the implementation in
 * [WebModeExtractor.defaultEngine] to change engines; nothing else depends on ML Kit.
 */
internal interface OcrEngine {
    /** Blocking. Call from a worker thread, never the main thread. */
    fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult
    /** Fast single-pass OCR for screen scanning; callers may request a bounded fallback when sparse. */
    fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = recognize(bitmap, script)
    /**
     * One bounded retry for sparse/failed OCR. Implementations may upscale and boost contrast.
     * Empty default keeps alternate/test engines from accidentally repeating expensive work.
     */
    fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = OcrResult(emptyList())
    /** A small crop that holds ONE speech bubble: cheaper than [recognize] (few variants). */
    fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = recognize(bitmap, script)
    fun close()
}

/**
 * On-device ML Kit engine. Latin, Chinese (Simplified + Traditional), Japanese and Korean each use
 * their own bundled model (com.google.mlkit:text-recognition[-chinese|-japanese|-korean]).
 * Recognisers are created lazily, so a script that is never needed never loads its model.
 */
internal class MlKitOcrEngine : OcrEngine {
    private val recognizers = HashMap<OcrScript, TextRecognizer>()

    @Synchronized
    private fun recognizer(script: OcrScript): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    /** English comic text needs several views even when the first view looks non-empty.
     * Keep LINES until the uncovered-ink audit; merging before that hides missing middle rows.
     * Each optional view is awaited before recycling, with a bounded pixel budget.
     */
    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        readViews(bitmap, script, thorough = script == OcrScript.LATIN, light = true)

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        readViews(bitmap, script, thorough = true)

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        readViews(bitmap, script, thorough = true)

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        readViews(bitmap, script, thorough = true, region = true)

    /**
     * [light] = speed mode for screen scanning: the plain view plus ONE enlarged view per tile.
     * The contrast-stretched and inverted views (2 more ML Kit passes per tile) are only used by the
     * full engine, i.e. when a panel really read badly. They made every screen 2-3x slower for little gain.
     */
    private fun readViews(
        bitmap: Bitmap, script: OcrScript, thorough: Boolean, region: Boolean = false,
        light: Boolean = false
    ): OcrResult {
        val all = ArrayList<OcrBlock>()
        val rec = recognizer(script)
        fun scan(view: Bitmap, x: Int = 0, y: Int = 0, originalW: Int = view.width,
                 originalH: Int = view.height, required: Boolean = false) {
            try {
                val text = Tasks.await(rec.process(InputImage.fromBitmap(view, 0)))
                val kx = originalW.toFloat() / view.width
                val ky = originalH.toFloat() / view.height
                val mapped = toBlocks(text, script).map { b ->
                    b.copy(top = (b.top * ky).toInt() + y, left = (b.left * kx).toInt() + x,
                        cx = b.cx * kx + x, cy = b.cy * ky + y,
                        w = b.w * kx, h = b.h * ky, fontPx = b.fontPx * minOf(kx, ky))
                }
                OcrLineFusion.addAll(all, mapped.filterNot { OcrPost.isNoise(it) })
            } catch (e: ExecutionException) {
                if (required) throw OcrUnavailableException("ML Kit ${script.name} failed", e.cause ?: e)
            } catch (e: MlKitException) {
                if (required) throw OcrUnavailableException("ML Kit ${script.name}: code ${e.errorCode}", e)
            }
        }
        scan(bitmap, required = true)
        if (thorough) {
            // Tile instead of shrinking a tall webtoon to fit an enlarged view. Overlap keeps lines
            // on tile boundaries complete; all geometry is mapped back to the ORIGINAL slice.
            val tileH = if (region) 1000 else 900
            for ((top, bottom) in WebModeLogic.slices(bitmap.height, tileH, 180)) {
                var crop: Bitmap? = null
                var up: Bitmap? = null
                var contrast: Bitmap? = null
                var inverted: Bitmap? = null
                try {
                    crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
                    val factor = minOf(2f, 2200f / maxOf(crop.width, crop.height),
                        kotlin.math.sqrt(3_200_000.0 / (crop.width.toDouble() * crop.height)).toFloat())
                        .coerceAtLeast(1f)
                    up = if (factor > 1.05f) Bitmap.createScaledBitmap(crop,
                        (crop.width * factor).toInt(), (crop.height * factor).toInt(), true) else crop
                    scan(up, y = top, originalW = crop.width, originalH = crop.height)
                    if (light) continue
                    // Japanese/Chinese/Korean: plain + enlarged view is enough; the extra contrast pass only cost seconds.
                    if (script != OcrScript.LATIN && !region) continue
                    contrast = OcrPost.boostContrast(up)
                    scan(contrast, y = top, originalW = crop.width, originalH = crop.height)
                    if (script == OcrScript.LATIN) {
                        // Explicit polarity inversion recovers glowing white serif captions. A fixed
                        // bright threshold alone destroys anti-aliased strokes and is not a replacement.
                        inverted = invert(contrast)
                        scan(inverted, y = top, originalW = crop.width, originalH = crop.height)
                    }
                } catch (_: OutOfMemoryError) {
                    // Already recognised lines survive a failed optional view.
                } finally {
                    val owned = listOfNotNull(inverted, contrast, up, crop).distinct()
                    for (b in owned) if (b !== bitmap && !b.isRecycled) b.recycle()
                }
            }
        }
        val lines = all.sortedWith(compareBy({ it.top }, { it.left }))
        return OcrResult(if (script == OcrScript.LATIN) lines else OcrPost.mergeNeighbours(lines, bitmap))
    }

    private fun invert(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint()
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f)))
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * ML Kit's own "blocks" split one sentence at random on glowing / stylised lettering. Take its
     * LINES instead and let [OcrPost.mergeNeighbours] rebuild the sentences with one consistent rule.
     * Vertical text and clearly tilted blocks (sound effects) stay whole.
     */
    private fun toBlocks(vt: com.google.mlkit.vision.text.Text, script: OcrScript): List<OcrBlock> {
        val out = ArrayList<OcrBlock>()
        for (b in vt.textBlocks) {
            val t = b.text.trim()
            if (t.isEmpty()) continue
            val whole = toBlock(t, b)
            if ((whole.vertical && script != OcrScript.LATIN) || b.lines.isEmpty() || Math.abs(whole.angle) >= 6f) { out.add(whole); continue }
            for (l in b.lines) {
                val lt = l.text.trim()
                if (lt.isEmpty()) continue
                val g = geo(l.cornerPoints, l.boundingBox) ?: continue
                val box = l.boundingBox
                out.add(OcrBlock(lt, box?.top ?: 0, box?.left ?: 0, g.cx, g.cy, g.w, g.h, g.angle, g.h * 0.9f, false,
                    l.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f))
            }
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private class Geo(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)

    /** Centre, size and rotation from the 4 corner points (clockwise from top-left), else the bounding box. */
    private fun geo(corners: Array<android.graphics.Point>?, box: android.graphics.Rect?): Geo? {
        if (corners != null && corners.size == 4) {
            val cx = corners.sumOf { it.x.toDouble() }.toFloat() / 4f
            val cy = corners.sumOf { it.y.toDouble() }.toFloat() / 4f
            val w = Math.hypot((corners[1].x - corners[0].x).toDouble(), (corners[1].y - corners[0].y).toDouble()).toFloat()
            val h = Math.hypot((corners[2].x - corners[1].x).toDouble(), (corners[2].y - corners[1].y).toDouble()).toFloat()
            val a = Math.toDegrees(Math.atan2((corners[1].y - corners[0].y).toDouble(), (corners[1].x - corners[0].x).toDouble())).toFloat()
            return Geo(cx, cy, w, h, OcrPost.foldAngle(a))
        }
        if (box != null) return Geo(box.exactCenterX(), box.exactCenterY(), box.width().toFloat(), box.height().toFloat(), 0f)
        return null
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[s.size / 2]
    }

    private fun toBlock(t: String, b: com.google.mlkit.vision.text.Text.TextBlock): OcrBlock {
        val box = b.boundingBox
        val top = box?.top ?: 0
        val left = box?.left ?: 0
        val g = geo(b.cornerPoints, box) ?: return OcrBlock(t, top, left)
        // Glyph size: a horizontal line is as tall as the glyphs, a vertical column as wide.
        val tall = ArrayList<Float>()
        val wide = ArrayList<Float>()
        var verticalVotes = 0
        for (l in b.lines) {
            val lg = geo(l.cornerPoints, l.boundingBox) ?: continue
            if (lg.h > lg.w * 1.6f) { verticalVotes++; wide.add(lg.w) } else tall.add(lg.h)
        }
        val vertical = verticalVotes > 0 && verticalVotes * 2 >= b.lines.size
        val font = if (vertical) median(wide) else median(tall)
        val px = if (font > 0f) font * 0.9f else minOf(g.w, g.h) * 0.5f
        return OcrBlock(t, top, left, g.cx, g.cy, g.w, g.h, g.angle, px, vertical,
            b.lines.map { it.confidence }.filter { it.isFinite() }.average().toFloat().let {
                if (it.isFinite()) it.coerceIn(0f, 1f) else 0f
            })
    }

    @Synchronized
    override fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
