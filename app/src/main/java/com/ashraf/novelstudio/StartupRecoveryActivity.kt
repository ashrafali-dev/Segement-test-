package com.ashraf.novelstudio

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Lightweight launcher guard. It installs the crash handler before MainActivity starts and
 * exposes the saved report on the next launch if MainActivity crashes before showing its UI.
 */
class StartupRecoveryActivity : Activity() {
    private val bg = Color.rgb(12, 15, 24)
    private val fg = Color.rgb(242, 244, 250)
    private val muted = Color.rgb(177, 185, 202)
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        report = runCatching { CrashLog.read() }.getOrDefault("")
        val hasCrash = report.contains("CRASH on thread") || report.contains("UI FREEZE")
        if (!hasCrash) {
            openNovelStudio()
            return
        }
        showReportScreen()
    }

    private fun showReportScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Novel Studio · Recovery"
            textSize = 23f
            setTextColor(fg)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "A previous launch crashed or froze. Copy or share this report and send it here so the exact startup error can be fixed."
            textSize = 15f
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(14))
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(21, 25, 38))
            addView(TextView(this@StartupRecoveryActivity).apply {
                text = report.takeLast(18000).ifBlank { "No crash details were saved." }
                textSize = 12f
                setTextColor(fg)
                setTextIsSelectable(true)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(actionButton("Copy crash report") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Novel Studio crash report", report.takeLast(18000)))
            Toast.makeText(this, "Crash report copied. Paste it into the support chat.", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(-1, dp(48)).also { it.topMargin = dp(12) })

        root.addView(actionButton("Share crash report") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Novel Studio startup crash report")
                putExtra(Intent.EXTRA_TEXT, report.takeLast(18000))
            }
            startActivity(Intent.createChooser(send, "Share crash report"))
        }, LinearLayout.LayoutParams(-1, dp(48)).also { it.topMargin = dp(8) })

        root.addView(actionButton("Try opening Novel Studio") {
            openNovelStudio()
        }, LinearLayout.LayoutParams(-1, dp(48)).also { it.topMargin = dp(8) })

        setContentView(root)
    }

    private fun actionButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(fg)
        setBackgroundColor(Color.rgb(43, 51, 73))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun openNovelStudio() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not open Novel Studio: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            showReportScreen()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
