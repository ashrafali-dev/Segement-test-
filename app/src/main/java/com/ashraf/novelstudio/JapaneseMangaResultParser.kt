package com.ashraf.novelstudio

import org.json.JSONObject
import java.net.URI

/** Validates the optional Japanese engine payload and converts pixel boxes to image-relative layout. */
internal object JapaneseMangaResultParser {
    data class Result(val pageUrl: String, val layout: WebModeLayout)

    fun parse(raw: String): Result? {
        return try {
        if (raw.length !in 2..900_000) return null
        val root = JSONObject(raw)
        val pageUrl = root.optString("pageUrl").trim()
        if (!isHttpUrl(pageUrl)) return null
        val panels = root.optJSONArray("panels") ?: return null
        if (panels.length() !in 1..12) return null

        val bubbles = ArrayList<WmBubble>()
        for (i in 0 until panels.length()) {
            val panel = panels.optJSONObject(i) ?: continue
            val src = panel.optString("src").trim()
            if (!isHttpUrl(src)) continue
            val imageWidth = panel.optInt("width", 0)
            val imageHeight = panel.optInt("height", 0)
            if (imageWidth !in 1..12000 || imageHeight !in 1..20000) continue
            val regions = panel.optJSONArray("regions") ?: continue
            for (j in 0 until regions.length()) {
                if (bubbles.size >= MAX_BUBBLES) break
                val region = regions.optJSONObject(j) ?: continue
                val text = region.optString("text").trim()
                if (text.isEmpty() || text.length > 1200) continue
                val x = region.optInt("x", -1)
                val y = region.optInt("y", -1)
                val rw = region.optInt("w", 0)
                val rh = region.optInt("h", 0)
                if (rw < 2 || rh < 2 || x < 0 || y < 0 || x >= imageWidth || y >= imageHeight) continue
                val width = rw.coerceAtMost(imageWidth - x)
                val height = rh.coerceAtMost(imageHeight - y)
                val normalizedWidth = (width.toFloat() / imageWidth).coerceIn(0.003f, 1f)
                val normalizedHeight = (height.toFloat() / imageHeight).coerceIn(0.003f, 1f)
                val cx = ((x + width / 2f) / imageWidth).coerceIn(0f, 1f)
                val cy = ((y + height / 2f) / imageHeight).coerceIn(0f, 1f)
                val font = (minOf(width, height).toFloat() / imageWidth).coerceIn(0.004f, 0.12f)
                val bg = region.optInt("bg", 0xFFFFFFFF.toInt())
                val fg = region.optInt("fg", 0xFF171717.toInt())
                bubbles.add(
                    WmBubble(
                        src = src,
                        cx = cx, cy = cy,
                        w = normalizedWidth, h = normalizedHeight,
                        angle = 0f, font = font,
                        bg = bg, fg = fg, fg2 = fg,
                        soft = region.optBoolean("soft", false),
                        sourceText = text
                    )
                )
            }
        }
        if (bubbles.isEmpty()) null else Result(pageUrl, WebModeLayout(0, bubbles))
    } catch (_: Exception) {
        null
        }
    }

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private const val MAX_BUBBLES = 600
}
