package com.ashraf.novelstudio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Mode" bottom sheet: Novel / Scanning / Real-time / Manual Lite.
 * The selected row is filled with accentTint. Tapping a row reports the choice and closes.
 */
object ModeSheet {

    /** Product-level mode the sheet returns (Manual Lite = Real-time with Auto OFF). */
    enum class Choice { NOVEL, SCAN, REALTIME, LITE }

    fun show(host: ViewGroup, current: Choice, onPick: (Choice) -> Unit) {
        val c: Context = host.context
        val p = Tokens.palette(c)
        fun dpx(dp: Int) = (dp * c.resources.displayMetrics.density + 0.5f).toInt()

        val root = FrameLayout(c).apply {
            setBackgroundColor(p.scrim)
            isClickable = true
        }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Tokens.sheetShape(c, p.card, Tokens.R_SHEET)
            setPadding(dpx(16), dpx(12), dpx(16), dpx(24))
            setOnClickListener { }
        }
        card.addView(View(c).apply { background = Tokens.pill(c, p.lineStrong) },
            LinearLayout.LayoutParams(dpx(36), dpx(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = dpx(12) })
        card.addView(TextView(c).apply {
            text = Strings.MODE_TITLE
            setTextColor(p.ink); textSize = Tokens.SP_TITLE; typeface = Tokens.medium(c)
            setPadding(dpx(6), 0, dpx(6), dpx(10))
        })

        val rows = listOf(
            Triple(Choice.NOVEL, Strings.MODE_NOVEL, Strings.DESC_NOVEL),
            Triple(Choice.SCAN, Strings.CHIP_SCANNING, Strings.DESC_SCAN),
            Triple(Choice.REALTIME, Strings.CHIP_REALTIME, Strings.DESC_LIVE),
            Triple(Choice.LITE, Strings.CHIP_LITE, Strings.DESC_LITE)
        )
        val icons = listOf(Ic.BOOK, Ic.ZOOM, Ic.PLAY, Ic.LINK)
        rows.forEachIndexed { i, (choice, title, desc) ->
            val selected = choice == current
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpx(12), dpx(8), dpx(12), dpx(8))
                minimumHeight = dpx(64)
                background = if (selected) Tokens.shape(c, p.accentTint, Tokens.R_ROW) else null
                contentDescription = title
                setOnClickListener {
                    host.removeView(root)
                    onPick(choice)
                }
            }
            val icon = ImageView(c).apply {
                setImageDrawable(IconDrawable(icons[i], if (selected) p.onAccentTint else p.ink, 1.7f))
                setPadding(dpx(2), dpx(2), dpx(2), dpx(2))
            }
            row.addView(icon, LinearLayout.LayoutParams(dpx(24), dpx(24)).also { it.marginEnd = dpx(14) })
            val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(c).apply {
                text = title; textSize = Tokens.SP_BODY; typeface = Tokens.medium(c)
                setTextColor(if (selected) p.onAccentTint else p.ink)
            })
            text.addView(TextView(c).apply {
                text = desc; textSize = Tokens.SP_SECONDARY; setTextColor(p.dim)
                setLineSpacing(0f, 1.4f)
            })
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        root.setOnClickListener { host.removeView(root) }
        host.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
