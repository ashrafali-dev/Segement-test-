package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The reader dock: mode chip, previous, main action, next (with an "Ahead ready" badge) and the
 * Chat ring. It only reports taps through callbacks; MainActivity decides what each one does, so
 * all existing navigation / translation behaviour is untouched.
 *
 * Layout follows the boards: ~66dp tall, 4dp top padding, 14dp bottom, 1dp top hairline.
 */
class Dock(context: Context) : LinearLayout(context) {

    /** What the main button should say/do right now. */
    enum class Main { TRANSLATE, STOP, SCAN, PAUSE, RESUME, READ }

    var onModeTap: () -> Unit = {}
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onMain: () -> Unit = {}
    var onChatTap: () -> Unit = {}
    var onChatLong: () -> Unit = {}

    private val p = Tokens.palette(context)

    private val modeChip: TextView
    private val prevBtn: ImageView
    private val nextBtn: ImageView
    private val nextBadge: TextView
    private val mainBtn: TextView
    private val chatBtn: ChatRing

    // cached state so the 1 s status tick does no layout work when nothing changed
    private var mainState: Main? = null
    private var nextReady: Boolean? = null
    private var lastTickKey: String = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpx(10), dpx(4), dpx(10), dpx(14))
        // Flat background: the 1dp top hairline is drawn by the first child so it spans the full width.
        background = Tokens.shape(context, p.bg, 0)
        clipChildren = false

        modeChip = TextView(context).apply {
            text = Strings.MODE_NOVEL
            setTextColor(p.ink)
            textSize = Tokens.SP_BODY_SMALL
            typeface = Tokens.medium(context)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(14), 0, dpx(10), 0)
            compoundDrawablePadding = dpx(4)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IconDrawable(Ic.CHEVRON_DOWN, p.ink, 1.8f), null)
            background = Tokens.pill(context, p.chipBg)
            contentDescription = "Reading mode. Tap to change"
            minHeight = dpx(44)
            setOnClickListener { onModeTap() }
        }

        prevBtn = iconButton(Ic.BACK, Strings.PRIOR) { onPrev() }

        mainBtn = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = Tokens.SP_BODY
            typeface = Tokens.medium(context)
            contentDescription = Strings.ACTION_TRANSLATE
            compoundDrawablePadding = dpx(8)
            setOnClickListener { onMain() }
        }

        nextBtn = iconButton(Ic.FORWARD, Strings.NEXT) { onNext() }

        chatBtn = ChatRing(context).apply {
            contentDescription = "Chatbot status"
            setOnClickListener { onChatTap() }
            setOnLongClickListener { onChatLong(); true }
        }

        addView(modeChip, LinearLayout.LayoutParams(WRAP, dpx(44)))
        addView(prevBtn, LinearLayout.LayoutParams(dpx(48), dpx(48)))
        addView(mainBtn, LinearLayout.LayoutParams(dpx(120), dpx(44)).also {
            it.weight = 0f
        })
        // Next gets a small FrameLayout so the green "ready" badge can sit at its top-right.
        val badge = TextView(context).apply {
            text = ""
            background = Tokens.pill(context, p.ok)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IconDrawable(Ic.CHECK, 0xFFFFFFFF.toInt(), 2.4f), null)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        nextBadge = badge
        val nextWrap = FrameLayout(context).apply {
            addView(nextBtn, FrameLayout.LayoutParams(dpx(48), dpx(48)))
            addView(badge, FrameLayout.LayoutParams(dpx(14), dpx(14), Gravity.TOP or Gravity.END).also {
                it.topMargin = dpx(2)
                it.rightMargin = dpx(1)
            })
        }
        addView(nextWrap, LinearLayout.LayoutParams(dpx(48), dpx(48)))
        addView(chatBtn, LinearLayout.LayoutParams(dpx(48), dpx(48)))

        // The mode chip takes the remaining width so the five items always fit one row.
        (modeChip.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = 1f
        }
    }

    /** Change the mode chip text. [text] is already a short label ("Novel", "Scan", "Live", "Lite"). */
    fun setModeLabel(text: String) {
        if (modeChip.text.toString() != text) modeChip.text = text
    }

    /** Fill vs. outline for the main action. */
    fun setMain(state: Main) {
        if (mainState == state) return
        mainState = state
        val (label, icon, filled) = when (state) {
            Main.TRANSLATE -> Triple(Strings.ACTION_TRANSLATE, Ic.LINK, true)
            Main.STOP -> Triple(Strings.ACTION_STOP, Ic.STOP_SQUARE, false)
            Main.SCAN -> Triple(Strings.ACTION_SCAN, Ic.ZOOM, true)
            Main.PAUSE -> Triple(Strings.ACTION_PAUSE, Ic.PAUSE, false)
            Main.RESUME -> Triple(Strings.ACTION_RESUME, Ic.PLAY, true)
            Main.READ -> Triple(Strings.ACTION_READ, Ic.ZOOM, true)
        }
        mainBtn.text = label
        mainBtn.contentDescription = label
        if (filled) {
            mainBtn.setTextColor(p.onAccent)
            mainBtn.background = Tokens.pill(context, p.ink)
            mainBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(IconDrawable(icon, p.onAccent, 1.8f), null, null, null)
        } else {
            mainBtn.setTextColor(p.onAccentTint)
            mainBtn.background = Tokens.pill(context, p.accentTint, 1, p.accent)
            mainBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(IconDrawable(icon, p.onAccentTint, 1.8f), null, null, null)
        }
    }

    /** Green check at the top-right of Next when the next chapter is already translated. */
    fun setNextReady(ready: Boolean) {
        if (nextReady == ready) return
        nextReady = ready
        nextBadge.visibility = if (ready) View.VISIBLE else View.GONE
        nextBtn.contentDescription = if (ready) "${Strings.NEXT} (translated and ready)" else Strings.NEXT
    }

    /**
     * Chat ring, fed only by StatusModel. [count] < 0 = no known total (icon only).
     * [attention] turns the ring amber with "!" (login, captcha, failure).
     */
    fun setChat(attention: Boolean, count: Int, fraction: Float, contentDesc: String) {
        val key = "$attention|$count|${(fraction * 100).toInt()}"
        if (key == lastTickKey) return
        lastTickKey = key
        chatBtn.update(attention, count, fraction)
        chatBtn.contentDescription = contentDesc
    }

    fun chatAnchor(): View = chatBtn
    fun modeAnchor(): View = modeChip

    private val hairline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** 1dp top hairline in the line colour, over the flat background. */
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        hairline.color = p.line
        canvas.drawRect(0f, 0f, width.toFloat(), resources.displayMetrics.density, hairline)
    }

    private fun iconButton(ic: Ic, desc: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageDrawable(IconDrawable(ic, p.ink, 1.8f))
            setPadding(dpx(12), dpx(12), dpx(12), dpx(12))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------------ the Chat ring itself
    /**
     * 36dp ring around a chat icon. Track = hairline colour. A determinate fill is drawn along the
     * ring arc; the middle shows the count while translating, the chat icon when idle, "!" when the
     * user must act. No animation: the arc only moves when the value changes.
     */
    private inner class ChatRing(c: Context) : FrameLayout(c) {
        private val ring = RingView(c)
        private val label = TextView(c)

        init {
            addView(ring, LayoutParams(dpx(36), dpx(36), Gravity.CENTER))
            label.apply {
                gravity = Gravity.CENTER
                textSize = Tokens.SP_MICRO
                typeface = Typeface.create(Tokens.medium(context), Typeface.BOLD)
                setTextColor(p.ink)
                includeFontPadding = false
            }
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setPadding(dpx(6), dpx(6), dpx(6), dpx(6))
        }

        fun update(attention: Boolean, count: Int, fraction: Float) {
            when {
                attention -> {
                    ring.set(p.warnRing, 1f)
                    label.text = "!"
                    label.setTextColor(p.warnRing)
                    label.visibility = View.VISIBLE
                }
                count >= 0 -> {
                    ring.set(p.accent, fraction)
                    label.text = count.toString()
                    label.setTextColor(p.ink)
                    label.visibility = View.VISIBLE
                }
                else -> {
                    ring.set(p.accent, 0f)
                    label.text = ""
                    label.visibility = View.GONE
                    addIcon()
                }
            }
            if (count >= 0 || attention) showIcon(false) else showIcon(true)
        }

        private val iconView = ImageView(context).apply {
            setImageDrawable(IconDrawable(Ic.CHAT, p.ink, 1.7f))
            visibility = View.GONE
        }

        private fun addIcon() {
            if (iconView.parent == null) addView(iconView, LayoutParams(dpx(18), dpx(18), Gravity.CENTER))
        }

        private fun showIcon(show: Boolean) {
            addIcon()
            iconView.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /** Draws the 36dp ring track plus a determinate arc. Palette is resolved once, never per frame. */
    private class RingView(c: Context) : View(c) {
        private val trackColor = Tokens.palette(c).line
        private var color = 0
        private var fraction = 0f
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val rect = android.graphics.RectF()

        fun set(accent: Int, f: Float) {
            if (color == accent && fraction == f) return
            color = accent
            fraction = f.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = resources.displayMetrics.density * 2.5f
            track.strokeWidth = w
            val inset = w / 2f + 1f
            rect.set(inset, inset, width - inset, height - inset)
            track.color = trackColor
            canvas.drawArc(rect, 0f, 360f, false, track)
            if (fraction > 0f) {
                track.color = color
                canvas.drawArc(rect, -90f, 360f * fraction, false, track)
            }
        }
    }

    private fun dpx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
