package com.ashraf.novelstudio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Mode start page (board 21): app bar with Back + the mode name, one line of description,
 * a search/paste pill, "Recent in <mode>" and "Supported sites" chips.
 *
 * It is an overlay over the app (the reader underneath is untouched); choosing a link closes it
 * and opens that link with the dock. Deliberately has no features of its own.
 */
object ModeStart {

    fun show(host: ViewGroup, modeKey: String, onOpen: (String) -> Unit) {
        val c: Context = host.context
        val p = Tokens.palette(c)
        fun dpx(v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

        val title = when (modeKey) {
            "scan" -> Strings.CHIP_SCANNING
            "realtime" -> Strings.CHIP_REALTIME
            "lite" -> Strings.CHIP_LITE
            else -> Strings.MODE_NOVEL
        }
        val desc = when (modeKey) {
            "scan" -> "Open a comic page. Every panel is read and the translation is placed on it."
            "realtime" -> "Open a comic page. New panels are translated as you scroll."
            "lite" -> "Open a comic page. One screen is read when you tap."
            else -> "Open a novel site. Chapter text is translated on the page."
        }

        val root = FrameLayout(c).apply { setBackgroundColor(p.bg) }

        val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        // ---- app bar
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(6), 0, dpx(6), 0)
        }
        bar.addView(ImageView(c).apply {
            setImageDrawable(IconDrawable(Ic.BACK, p.ink, 1.8f))
            setPadding(dpx(10), dpx(10), dpx(10), dpx(10))
            contentDescription = "Back"
            setOnClickListener { host.removeView(root) }
        }, LinearLayout.LayoutParams(dpx(44), dpx(44)))
        bar.addView(TextView(c).apply {
            text = title
            textSize = Tokens.SP_TITLE
            typeface = Tokens.medium(c)
            setTextColor(p.ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpx(56)))

        // ---- body
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(4), dpx(20), dpx(24))
        }
        body.addView(TextView(c).apply {
            text = desc
            textSize = Tokens.SP_BODY_SMALL
            setTextColor(p.dim)
            setLineSpacing(0f, 1.5f)
        })

        val input = EditText(c).apply {
            hint = "Search or paste a chapter link"
            setHintTextColor(p.dim)
            setTextColor(p.ink)
            textSize = Tokens.SP_BODY_SMALL
            typeface = Tokens.regular(c)
            background = null
            setSingleLine(true)
            includeFontPadding = false
            setPadding(dpx(14), 0, dpx(14), 0)
        }
        body.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Tokens.pill(c, p.card, 1, p.line)
            setPadding(dpx(6), 0, 0, 0)
            addView(ImageView(c).apply {
                setImageDrawable(IconDrawable(Ic.SEARCH, p.dim, 1.7f))
                setPadding(dpx(10), dpx(10), dpx(10), dpx(10))
            }, LinearLayout.LayoutParams(dpx(40), dpx(40)))
            addView(input, LinearLayout.LayoutParams(0, dpx(54), 1f))
            addView(TextView(c).apply {
                text = "Open"
                setTextColor(p.onAccent)
                textSize = Tokens.SP_BODY_SMALL
                typeface = Tokens.medium(c)
                gravity = Gravity.CENTER
                background = Tokens.pill(c, p.ink)
                contentDescription = "Open"
                setOnClickListener {
                    val t = input.text.toString().trim()
                    if (t.isEmpty()) return@setOnClickListener
                    host.removeView(root)
                    onOpen(t)
                }
            }, LinearLayout.LayoutParams(dpx(70), dpx(40)).also { it.marginEnd = dpx(7) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpx(54)).also { it.topMargin = dpx(16) })

        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        host.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        Tokens.fadeIn(root, c, 170L)
    }
}
