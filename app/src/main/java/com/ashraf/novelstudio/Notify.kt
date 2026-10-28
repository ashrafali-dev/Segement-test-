package com.ashraf.novelstudio

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The message system that replaces bottom toasts.
 *
 * Four visible behaviours, mapped from the old toast texts:
 *  A) the user's own action ("Copied", "Saved")  -> NO popup; the control confirms in place  [confirm]
 *  B) a background tab opened                    -> no message; the tab counter flashes        [handled in MainActivity]
 *  C) the user must act (error / needs a step)   -> an amber banner under the top bar       [banner]
 *  D) pure information                           -> the Tasks log only                      [log]
 *
 * A banner never auto-hides and never covers touches outside its own bounds. Only one banner is
 * shown at a time; a newer one replaces the older and the older goes to the log.
 */
object Notify {

    // ------------------------------------------------------------------ log (class D and history)

    class Entry(val text: String, val atMs: Long, val rawMs: Long) {
        val relative: String
            get() {
                val s = ((SystemClock.elapsedRealtime() - atMs) / 1000L).toInt()
                return when {
                    s < 60 -> "just now"
                    s < 3600 -> "${s / 60} min ago"
                    else -> "${s / 3600} h ago"
                }
            }
    }

    private const val LOG_MAX = 120
    private val log = ArrayDeque<Entry>()
    private var unread = false

    /** Pure information: written to the Tasks log, never shown on screen. */
    fun log(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (log.firstOrNull()?.text == t) return
        log.addFirst(Entry(t, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        while (log.size > LOG_MAX) log.removeLast()
        unread = true
    }

    fun entries(): List<Entry> = log.toList()

    fun hasUnread(): Boolean = unread
    fun markRead() { unread = false }

    // ------------------------------------------------------------------ in place confirmation (A)

    /**
     * Confirm the user's own action on the control itself for ~1.5 s, with no popup.
     * The control's background, text colour and label are restored afterwards.
     *
     * @param view the pressed control (a row or button)
     * @param label short word shown instead of the control's text, e.g. "Copied" (null = keep text,
     *              only the tint + check change, which suits buttons with an icon)
     */
    fun confirm(c: Context, view: View, label: String? = "Copied") {
        val p = Tokens.palette(c)
        val tv = view as? TextView
        val oldBg = view.background
        val oldColor = tv?.currentTextColor
        val oldText = tv?.text
        view.background = Tokens.shape(c, p.okTint, Tokens.R_ROW)
        tv?.setTextColor(p.okText)
        if (label != null && tv != null) tv.text = label
        view.contentDescription = label ?: view.contentDescription
        view.postDelayed({
            view.background = oldBg
            if (tv != null && oldText != null) {
                tv.setText(oldText)
                if (oldColor != null) tv.setTextColor(oldColor)
            }
        }, 1500L)
    }

    // ------------------------------------------------------------------ banner (C)

    private var host: FrameLayout? = null
    private var current: View? = null

    /**
     * Attach the banner layer. Called once from MainActivity after the root layout exists.
     * The host is a wrap-height FrameLayout placed just under the top bar, so the banner never has
     * to guess where the bar ends and never covers it.
     */
    fun attachHost(container: FrameLayout) {
        host = container
    }

    /**
     * Show a "needs you" banner under the top bar. Stays until dismissed or replaced.
     *
     * @param actionLabel one 40dp action button (e.g. "Log in", "Retry", "Show me"); null = no action
     */
    fun banner(
        c: Context,
        text: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        val h = host ?: run { log(text); return }
        // The older banner is not lost: it moves to the log.
        current?.let { old -> (old.tag as? String)?.let { log(it) } }
        current?.let { h.removeView(it) }

        val p = Tokens.palette(c)
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Tokens.shape(c, p.warnTint, Tokens.R_ROW, 1, p.warnBorder)
            setPadding(dpx(c, 16), dpx(c, 6), dpx(c, 6), dpx(c, 6))
            tag = text
            isClickable = true // never pass touches through to nothing; outside bounds still pass
        }
        val msg = TextView(c).apply {
            this.text = text
            setTextColor(p.warnText)
            textSize = Tokens.SP_BODY_SMALL
            typeface = Tokens.medium(c)
            maxLines = 2
        }
        bar.addView(msg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (actionLabel != null && onAction != null) {
            val act = TextView(c).apply {
                this.text = actionLabel
                setTextColor(p.warnTint)
                textSize = Tokens.SP_BODY_SMALL
                typeface = Tokens.medium(c)
                gravity = Gravity.CENTER
                background = Tokens.pill(c, p.warnText)
                setPadding(dpx(c, 16), 0, dpx(c, 16), 0)
                contentDescription = actionLabel
                setOnClickListener {
                    dismiss()
                    onAction()
                }
            }
            bar.addView(act, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpx(c, 40))
                .also { it.marginStart = dpx(c, 8) })
        }

        val close = TextView(c).apply {
            text = ""
            compoundDrawablePadding = 0
            contentDescription = Strings.DISMISS
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IconDrawable(Ic.CLOSE, p.warnText, 1.9f), null)
            setOnClickListener { dismiss() }
        }
        bar.addView(close, LinearLayout.LayoutParams(dpx(c, 40), dpx(c, 40)))

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also {
            it.gravity = Gravity.TOP
            it.setMargins(dpx(c, 10), dpx(c, 8), dpx(c, 10), 0)
        }
        h.addView(bar, lp)
        current = bar
        Tokens.fadeIn(bar, c, 170L, 6)
    }

    /**
     * Remove the current banner. Only a *replaced* banner goes to the log (see banner()); a banner the
     * user dismissed or acted on has done its job and would only clutter the log.
     */
    fun dismiss() {
        val h = host ?: return
        val v = current ?: return
        current = null
        Tokens.fadeOut(v, v.context, 150L, gone = true)
        v.postDelayed({ h.removeView(v) }, 200L)
    }

    fun currentText(): String? = (current?.tag as? String)

    private fun dpx(c: Context, dp: Int) = (dp * c.resources.displayMetrics.density + 0.5f).toInt()
}
