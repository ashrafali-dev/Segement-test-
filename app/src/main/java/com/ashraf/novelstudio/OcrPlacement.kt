package com.ashraf.novelstudio

/**
 * The one place that converts OCR coordinates into canvas coordinates.
 *
 * Three coordinate spaces exist in this app and they were being mixed:
 *  - UPLOAD space: what Lens actually received (the JPEG it was sent; e.g. 690x1600).
 *  - PANEL space: the panel's own pixels (e.g. 420x975, the bitmap that was decoded). Panel [panelTop] is
 *    the document offset (scroll-top) of that panel's top edge.
 *  - CANVAS space: what the overlay draws in (the page/canvas), = panel + [panelTop].
 *
 * The rules that make placement deterministic:
 *  1. [mapToPanel] scales ONCE from upload dims into panel dims. It never knows about scroll.
 *  2. [placeOnCanvas] adds the panel's scroll offset ONCE, as the very last step before drawing.
 *  3. Pixel boxes are already in panel pixels and must NOT go through [mapToPanel] again.
 *
 * Pure Kotlin, no Android types: unit-testable on the JVM.
 */
internal data class CanvasBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

/**
 * Scale a box that is expressed in UPLOAD pixels into PANEL pixels.
 *
 * Pure scale about the origin: sx = panelW / uploadW, sy = panelH / uploadH. NO scroll offset is added
 * here (that is [placeOnCanvas]'s job) — adding it in both places is exactly the drift this fixes.
 * Degenerate inputs (0 upload/panel size) return the box unchanged instead of dividing by zero.
 */
internal fun mapToPanel(
    box: CanvasBox, uploadW: Int, uploadH: Int, panelW: Int, panelH: Int
): CanvasBox {
    if (uploadW <= 0 || uploadH <= 0 || panelW <= 0 || panelH <= 0) return box
    val sx = panelW.toFloat() / uploadW
    val sy = panelH.toFloat() / uploadH
    if (sx == 1f && sy == 1f) return box
    return CanvasBox(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy)
}

/**
 * Move a PANEL-space box into CANVAS space. This is the ONLY place a scroll offset is added, and it is
 * called only at final draw time. [scaleFactor] lets a caller that already works in a scaled panel
 * (e.g. a 2x OCR upscale) map back without a second explicit division.
 */
internal fun placeOnCanvas(panelBox: CanvasBox, panelTopOffset: Int, scaleFactor: Float = 1f): CanvasBox {
    val k = if (scaleFactor > 0f && scaleFactor != 1f) 1f / scaleFactor else 1f
    val dy = panelTopOffset.toFloat()
    return CanvasBox(panelBox.left * k, panelBox.top * k + dy, panelBox.right * k, panelBox.bottom * k + dy)
}

/**
 * A user-marked region is the user's word about where text is. The recognised text is placed at the
 * region's top-left plus the block's own offset inside the region — no scaling, no scroll (scroll is
 * applied later by [placeOnCanvas]).
 */
internal fun regionBox(regionLeft: Float, regionTop: Float, regionW: Float, regionH: Float, blockCx: Float, blockCy: Float, blockW: Float, blockH: Float): CanvasBox =
    CanvasBox(
        regionLeft + blockCx - blockW / 2f,
        regionTop + blockCy - blockH / 2f,
        regionLeft + blockCx + blockW / 2f,
        regionTop + blockCy + blockH / 2f
    )

/** One debug line per placed block: raw (upload) -> mapped (panel) -> canvas. NOVEL STUDIO OCR REPORT safe. */
internal fun coordinateTrace(
    raw: CanvasBox, uploadW: Int, uploadH: Int, panelW: Int, panelH: Int, panelTop: Int
): String {
    val panel = mapToPanel(raw, uploadW, uploadH, panelW, panelH)
    val canvas = placeOnCanvas(panel, panelTop)
    fun f(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
    return "COORD raw[${f(raw.left)},${f(raw.top)},${f(raw.right)},${f(raw.bottom)}] upload=${uploadW}x$uploadH" +
        " -> panel[${f(panel.left)},${f(panel.top)},${f(panel.right)},${f(panel.bottom)}] ${panelW}x$panelH" +
        " -> canvas[${f(canvas.left)},${f(canvas.top)},${f(canvas.right)},${f(canvas.bottom)}] top=$panelTop"
}

/** IoU of two boxes (0 when they do not overlap or either is degenerate). */
internal fun iou(a: CanvasBox, b: CanvasBox): Float {
    val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    if (iw <= 0f || ih <= 0f) return 0f
    val inter = iw * ih
    val union = a.width * a.height + b.width * b.height - inter
    return if (union <= 0f) 0f else inter / union
}

/**
 * How much of [inner] is covered by [outer] (fraction of [inner]'s area, 0..1).
 *
 * IoU alone is the wrong acceptance test for a translation box: a translation box is usually smaller than
 * the bubble, so it can sit perfectly inside the visible bubble and still score a low IoU. What must be
 * true is "the text box lies inside the bubble" — that is this measure.
 */
internal fun coverage(outer: CanvasBox, inner: CanvasBox): Float {
    val innerArea = inner.width * inner.height
    if (innerArea <= 0f) return 0f
    val iw = minOf(outer.right, inner.right) - maxOf(outer.left, inner.left)
    val ih = minOf(outer.bottom, inner.bottom) - maxOf(outer.top, inner.top)
    if (iw <= 0f || ih <= 0f) return 0f
    return iw * ih / innerArea
}
