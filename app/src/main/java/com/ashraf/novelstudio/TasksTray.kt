package com.ashraf.novelstudio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The Tasks tray: one bottom sheet that answers "what is happening below?" and hosts the quiet log.
 *
 * Built only when opened (a sheet is a one-off screen, so allocation here is fine). While it is
 * open it re-renders from StatusModel at most once per second.
 */
class TasksTray(private val activityContext: Context, private val host: ViewGroup) {

    var onStopAll: () -> Unit = {}
    var onResend: () -> Unit = {}
    var onOpenChatbot: () -> Unit = {}
    var onAheadToggle: (Boolean) -> Unit = {}
    var onClosed: () -> Unit = {}

    private val p = Tokens.palette(activityContext)
    private var sheet: FrameLayout? = null
    private var laneBox: LinearLayout? = null
    private var logBox: LinearLayout? = null
    private var aheadSwitch: Toggle? = null

    fun isOpen(): Boolean = sheet != null

    /**
     * @param aheadOn current "Ahead" setting
     * @param snap current status snapshot (lanes, detail line)
     */
    fun show(aheadOn: Boolean, snap: StatusModel.Snapshot) {
        if (sheet != null) { render(snap, aheadOn); return }
        val root = FrameLayout(activityContext).apply {
            setBackgroundColor(p.scrim)
            isClickable = true
            setOnClickListener { close() }
        }

        laneBox = LinearLayout(activityContext).apply { orientation = LinearLayout.VERTICAL }
        logBox = LinearLayout(activityContext).apply { orientation = LinearLayout.VERTICAL }
        aheadSwitch = Toggle(activityContext, aheadOn) { onAheadToggle(it) }

        val card = LinearLayout(activityContext).apply {
            orientation = LinearLayout.VERTICAL
            background = Tokens.sheetShape(activityContext, p.card, Tokens.R_SHEET)
            setPadding(dpx(20), dpx(12), dpx(20), dpx(28))
            setOnClickListener { /* swallow taps so the scrim's close does not fire */ }
        }

        // grabber
        card.addView(View(activityContext).apply { background = Tokens.pill(activityContext, p.lineStrong) },
            LinearLayout.LayoutParams(dpx(36), dpx(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = dpx(8) })

        // title row
        val titleRow = LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpx(48)
        }
        titleRow.addView(TextView(activityContext).apply {
            text = Strings.TASKS
            setTextColor(p.ink); textSize = Tokens.SP_TITLE; typeface = Tokens.medium(activityContext)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(activityContext).apply {
            text = Strings.STOP_ALL
            setTextColor(p.accent); textSize = Tokens.SP_BODY_SMALL; typeface = Tokens.medium(activityContext)
            gravity = Gravity.CENTER
            setPadding(dpx(12), dpx(10), dpx(12), dpx(10))
            contentDescription = Strings.STOP_ALL
            setOnClickListener { onStopAll() }
        })
        card.addView(titleRow)

        card.addView(laneBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Ahead toggle row
        val aheadRow = LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpx(12), 0, dpx(12))
        }
        val aheadTextBox = LinearLayout(activityContext).apply { orientation = LinearLayout.VERTICAL }
        aheadTextBox.addView(TextView(activityContext).apply {
            text = Strings.AHEAD; setTextColor(p.ink); textSize = Tokens.SP_BODY
        })
        aheadTextBox.addView(TextView(activityContext).apply {
            text = Strings.AHEAD_DESC; setTextColor(p.dim); textSize = Tokens.SP_SECONDARY
            setLineSpacing(0f, 1.3f)
        })
        aheadRow.addView(aheadTextBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        aheadRow.addView(aheadSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(aheadRow)

        // the old chip's second line, now hidden from the main screen
        card.addView(TextView(activityContext).apply {
            text = snap.detailLine
            setTextColor(p.dim); textSize = Tokens.SP_TINY
            setPadding(0, 0, 0, dpx(16))
        })

        // buttons
        val buttons = LinearLayout(activityContext).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(outlineButton(Strings.RESEND) { onResend() }, LinearLayout.LayoutParams(0, dpx(48), 1f).also { it.marginEnd = dpx(10) })
        buttons.addView(filledButton(Strings.OPEN_CHATBOT) { onOpenChatbot() }, LinearLayout.LayoutParams(0, dpx(48), 1f))
        card.addView(buttons)

        // quiet log
        card.addView(TextView(activityContext).apply {
            text = Strings.LOG; setTextColor(p.dim); textSize = Tokens.SP_SECONDARY
            setPadding(0, dpx(18), 0, dpx(6))
        })
        val logs = ScrollView(activityContext).apply {
            isVerticalScrollBarEnabled = false
            addView(logBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(logs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpx(180)))

        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        sheet = root
        host.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        render(snap, aheadOn)
        Notify.markRead()
    }

    /** Re-render live while open. Cheap: only touches texts whose value changed. */
    fun render(snap: StatusModel.Snapshot, aheadOn: Boolean) {
        val box = laneBox ?: return
        box.removeAllViews()
        if (snap.lanes.isEmpty()) {
            box.addView(TextView(activityContext).apply {
                text = "Nothing is running."
                setTextColor(p.dim); textSize = Tokens.SP_SECONDARY
                setPadding(0, dpx(18), 0, dpx(18))
            })
        }
        snap.lanes.forEachIndexed { i, lane ->
            box.addView(laneRow(lane, i < snap.lanes.lastIndex))
        }
        aheadSwitch?.setChecked(aheadOn)
        val lb = logBox
        if (lb != null) {
            lb.removeAllViews()
            val entries = Notify.entries()
            if (entries.isEmpty()) {
                lb.addView(logRow(Strings.LOG_EMPTY, ""))
            } else {
                entries.take(20).forEach { lb.addView(logRow(NotifyClass.clean(it.text), it.relative)) }
            }
        }
    }

    fun close() {
        val s = sheet ?: return
        sheet = null
        host.removeView(s)
        onClosed()
    }

    private fun laneRow(lane: StatusModel.Lane, divider: Boolean): View {
        val row = LinearLayout(activityContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpx(10), 0, dpx(10))
        }
        val line1 = LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(activityContext).apply {
            background = Tokens.pill(activityContext, when (lane.state) {
                StatusModel.State.WORKING -> p.accent
                StatusModel.State.DONE -> p.ok
                StatusModel.State.WAITING -> p.dim
                StatusModel.State.ATTENTION -> p.warnRing
                StatusModel.State.IDLE -> p.dim
            })
        }
        line1.addView(dot, LinearLayout.LayoutParams(dpx(8), dpx(8)).also { it.marginEnd = dpx(12) })
        val textBox = LinearLayout(activityContext).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(TextView(activityContext).apply {
            text = lane.name; setTextColor(p.ink); textSize = Tokens.SP_BODY
        })
        textBox.addView(TextView(activityContext).apply {
            text = lane.stateText; setTextColor(p.dim); textSize = Tokens.SP_SECONDARY
        })
        line1.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = TextView(activityContext).apply {
            text = buildString {
                if (lane.hasProgress) append(Strings.count(lane.n!!, lane.total))
                if (lane.elapsedSec > 0) {
                    if (isNotEmpty()) append("\n")
                    append("${lane.elapsedSec} s")
                }
                if (!lane.hasProgress && !lane.rightText.isNullOrBlank() && lane.elapsedSec == 0) append(lane.rightText)
            }
            setTextColor(if (lane.state == StatusModel.State.DONE) p.okText else p.dim)
            textSize = Tokens.SP_SECONDARY
            gravity = Gravity.END
        }
        line1.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(line1)

        if (lane.hasProgress) {
            row.addView(Bar(activityContext, lane.fraction), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpx(3)
            ).also { it.topMargin = dpx(10) })
        }
        if (divider) {
            row.addView(View(activityContext).apply { setBackgroundColor(p.line) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        return row
    }

    private fun logRow(text: String, when_: String): View = LinearLayout(activityContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dpx(10), 0, dpx(10))
        addView(TextView(activityContext).apply {
            this.text = text; setTextColor(p.ink); textSize = Tokens.SP_BODY_SMALL
        })
        if (when_.isNotBlank()) addView(TextView(activityContext).apply {
            this.text = when_; setTextColor(p.dim); textSize = Tokens.SP_TINY
        })
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = TextView(activityContext).apply {
        text = label; setTextColor(p.ink); textSize = Tokens.SP_BODY; typeface = Tokens.medium(activityContext)
        gravity = Gravity.CENTER
        background = Tokens.pill(activityContext, 0x00000000, 1, p.lineStrong)
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun filledButton(label: String, onClick: () -> Unit) = TextView(activityContext).apply {
        text = label; setTextColor(p.onAccent); textSize = Tokens.SP_BODY; typeface = Tokens.medium(activityContext)
        gravity = Gravity.CENTER
        background = Tokens.pill(activityContext, p.ink)
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun dpx(dp: Int) = (dp * activityContext.resources.displayMetrics.density + 0.5f).toInt()

    /** A 3dp determinate bar: track + accent fill. */
    private class Bar(c: Context, private val frac: Float) : View(c) {
        private val p = Tokens.palette(c)
        override fun onDraw(canvas: android.graphics.Canvas) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = p.line
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
            paint.color = p.accent
            canvas.drawRoundRect(0f, 0f, width * frac.coerceIn(0f, 1f), height.toFloat(), height / 2f, height / 2f, paint)
        }
    }

    /** Small flat switch (46x28) per the boards: accent when on, hairline track when off. */
    class Toggle(c: Context, on: Boolean, private val onChange: (Boolean) -> Unit) : View(c) {
        private val p = Tokens.palette(c)
        private var checked = on
        init {
            contentDescription = "Ahead"
            setOnClickListener {
                checked = !checked
                contentDescription = if (checked) "Ahead on" else "Ahead off"
                invalidate()
                onChange(checked)
            }
            minimumWidth = (46 * c.resources.displayMetrics.density).toInt()
            minimumHeight = (28 * c.resources.displayMetrics.density).toInt()
        }
        fun setChecked(v: Boolean) { if (v != checked) { checked = v; invalidate() } }
        override fun onMeasure(w: Int, h: Int) =
            setMeasuredDimension(minimumWidth, minimumHeight)
        override fun onDraw(canvas: android.graphics.Canvas) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = if (checked) p.accent else p.lineStrong
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
            paint.color = p.onAccent
            val r = height / 2f - height * 0.107f
            val cx = if (checked) width - height / 2f else height / 2f
            canvas.drawCircle(cx, height / 2f, r, paint)
        }
    }
}
