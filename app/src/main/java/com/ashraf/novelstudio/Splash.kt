package com.ashraf.novelstudio

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Start-up cover: mascot (140dp) + wordmark (30sp) centred on the theme background.
 *
 * It is shown ONLY while the app is really initialising and is removed on the first frame the
 * reader/Home can draw — never with an artificial delay. No animation.
 */
object Splash {

    fun create(c: Context): FrameLayout {
        val p = Tokens.palette(c)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(p.bg)
        }
        val px = (140 * c.resources.displayMetrics.density + 0.5f).toInt()
        box.addView(ImageView(c).apply {
            setImageDrawable(MascotDrawable(p.dark))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Toon Sensei"
        }, LinearLayout.LayoutParams(px, px))

        val wp = (18 * c.resources.displayMetrics.density + 0.5f).toInt()
        box.addView(TextView(c).apply {
            text = Strings.WORDMARK
            textSize = Tokens.SP_WORDMARK
            typeface = Tokens.wordmark(c)
            setTextColor(p.ink)
            letterSpacing = 0.02f
            setPadding(0, wp, 0, 0)
            contentDescription = Strings.WORDMARK
        })

        // The whole cover is above everything until start-up finishes; it swallows touches meanwhile.
        return FrameLayout(c).apply {
            isClickable = true
            addView(box, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    /** Remove the cover with a short fade (0 ms when the system animations are off). */
    fun dismiss(cover: FrameLayout) {
        cover.isClickable = false
        Tokens.fadeOut(cover, cover.context, 160L, gone = true)
        cover.postDelayed({ (cover.parent as? android.view.ViewGroup)?.removeView(cover) }, 220L)
    }
}
