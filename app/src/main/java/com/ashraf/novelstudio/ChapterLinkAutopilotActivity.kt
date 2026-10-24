package com.ashraf.novelstudio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ChapterLinkAutopilotActivity : Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var summary: TextView
    private lateinit var jobs: LinearLayout
    private var poll: Runnable?=null

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi(); refresh() }
    override fun onResume() {
        super.onResume()
        val r=object:Runnable { override fun run() { if(isFinishing) return; refresh(); handler.postDelayed(this,1200L) } }
        poll=r; handler.post(r)
    }
    override fun onPause() { poll?.let(handler::removeCallbacks); poll=null; super.onPause() }
    override fun onDestroy() { poll?.let(handler::removeCallbacks); super.onDestroy() }

    private fun buildUi() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(UiTheme.role(UiTheme.Role.SHEET)) }
        val top=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(12)) }
        top.addView(TextView(this).apply {
            text="Chapter Autopilot"; textSize=Type.display; typeface=Type.bold(); setTextColor(UiTheme.role(UiTheme.Role.TEXT))
        })
        top.addView(TextView(this).apply {
            text="EXPERIMENTAL · Link → Scan → Chatbot → Ready"; textSize=Type.caption; typeface=Type.medium()
            setTextColor(UiTheme.role(UiTheme.Role.DIM)); setPadding(0,dp(4),0,dp(14))
        })
        input=EditText(this).apply {
            hint="Paste chapter URLs, one per line"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines=3; maxLines=6; gravity=Gravity.TOP or Gravity.START; textSize=Type.body
            setTextColor(UiTheme.role(UiTheme.Role.TEXT)); setHintTextColor(UiTheme.role(UiTheme.Role.FAINT))
            setPadding(dp(14),dp(12),dp(14),dp(12))
            background=Glass.solid(this@ChapterLinkAutopilotActivity,UiTheme.role(UiTheme.Role.CONTROL),16,UiTheme.role(UiTheme.Role.STROKE_SOFT))
        }
        top.addView(input,LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(button("Add links to queue",true) {
            val n=ChapterLinkAutopilotQueue.addUrls(this,input.text.toString()); input.setText("")
            toast(if(n>0) "Added $n chapter link(s)" else "No new valid HTTP/HTTPS links found"); refresh()
        },LinearLayout.LayoutParams(-1,dp(46)).also { it.topMargin=dp(10) })

        val runRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        runRow.addView(button("Start / Resume",true) { startAutopilot() },LinearLayout.LayoutParams(0,dp(44),1f).also { it.marginEnd=dp(6) })
        runRow.addView(button("Pause new links",false) {
            ChapterLinkAutopilotQueue.setPaused(this,true); toast("Will pause after the current chapter"); refresh()
        },LinearLayout.LayoutParams(0,dp(44),1f).also { it.marginStart=dp(6) })
        top.addView(runRow,LinearLayout.LayoutParams(-1,dp(44)).also { it.topMargin=dp(8) })

        val tools=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        tools.addView(button("Retry failed",false) {
            val n=ChapterLinkAutopilotQueue.retryFailed(this); toast(if(n>0) "Retry queued for $n chapter(s)" else "No failed chapters"); refresh()
        },LinearLayout.LayoutParams(0,dp(40),1f).also { it.marginEnd=dp(6) })
        tools.addView(button("Clear ready",false) {
            val n=ChapterLinkAutopilotQueue.clearReady(this); toast("Removed $n completed queue item(s)"); refresh()
        },LinearLayout.LayoutParams(0,dp(40),1f).also { it.marginStart=dp(6) })
        top.addView(tools,LinearLayout.LayoutParams(-1,dp(40)).also { it.topMargin=dp(8) })
        summary=TextView(this).apply { textSize=Type.caption; setTextColor(UiTheme.role(UiTheme.Role.DIM)); setPadding(0,dp(14),0,0) }
        top.addView(summary,LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top,LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll=ScrollView(this).apply {
            clipToPadding=false; setPadding(dp(16),0,dp(16),dp(22))
            addView(LinearLayout(this@ChapterLinkAutopilotActivity).apply { orientation=LinearLayout.VERTICAL; jobs=this },
                ViewGroup.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun startAutopilot() {
        ChapterLinkAutopilotQueue.setPaused(this,false)
        if(!ChapterLinkAutopilotQueue.hasRunnable(this)) { toast("Add chapter links first, or retry failed items"); refresh(); return }
        startActivity(Intent(this,MainActivity::class.java).setAction(ChapterLinkAutopilotQueue.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    private fun refresh() {
        if(!::jobs.isInitialized) return
        val all=ChapterLinkAutopilotQueue.list(this)
        val ready=all.count { it.status==ChapterLinkAutopilotQueue.READY }
        val failed=all.count { it.status==ChapterLinkAutopilotQueue.FAILED }
        val active=all.count { it.status==ChapterLinkAutopilotQueue.EXTRACTING || it.status==ChapterLinkAutopilotQueue.SCANNING || it.status==ChapterLinkAutopilotQueue.TRANSLATING }
        val queued=all.count { it.status==ChapterLinkAutopilotQueue.QUEUED }
        summary.text="${all.size} links · ${ready} ready · ${active} active · ${queued} queued · ${failed} failed" +
            if(ChapterLinkAutopilotQueue.isPaused(this)) " · paused" else ""
        jobs.removeAllViews()
        if(all.isEmpty()) {
            jobs.addView(TextView(this).apply { text="No chapters queued yet. Paste URLs above, then start."; textSize=Type.body; setTextColor(UiTheme.role(UiTheme.Role.DIM)); setPadding(dp(8),dp(22),dp(8),dp(22)) })
            return
        }
        all.forEachIndexed { index, job ->
            val host=runCatching { Uri.parse(job.resolvedUrl.ifBlank { job.url }).host.orEmpty() }.getOrDefault("")
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12))
                background=Glass.solid(this@ChapterLinkAutopilotActivity,UiTheme.role(UiTheme.Role.CONTROL),18,UiTheme.role(UiTheme.Role.STROKE_SOFT))
            }
            card.addView(TextView(this).apply {
                text="${index+1}. " + job.title.ifBlank { host.ifBlank { "Chapter link" } }
                textSize=Type.body; typeface=Type.bold(); setTextColor(UiTheme.role(UiTheme.Role.TEXT)); maxLines=2
            })
            card.addView(TextView(this).apply {
                text=host+"\n"+job.url; textSize=Type.caption; setTextColor(UiTheme.role(UiTheme.Role.FAINT)); maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0,dp(3),0,dp(4))
            })
            val detail=if(job.error.isNotBlank()) job.error else job.stage
            card.addView(TextView(this).apply {
                text="${job.status.replace('_',' ')} · ${job.progress}%\n${detail}"
                textSize=Type.caption
                setTextColor(if(job.status==ChapterLinkAutopilotQueue.FAILED) 0xFFFF8A8A.toInt() else UiTheme.role(UiTheme.Role.DIM))
                setPadding(0,dp(2),0,dp(7))
            })
            card.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100; progress=job.progress
                isIndeterminate=job.status==ChapterLinkAutopilotQueue.EXTRACTING || (job.status==ChapterLinkAutopilotQueue.SCANNING && job.progress<=1)
            },LinearLayout.LayoutParams(-1,dp(5)))
            jobs.addView(card,LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin=dp(10) })
        }
    }

    private fun button(label: String,primary: Boolean,onClick:()->Unit): TextView = TextView(this).apply {
        text=label; textSize=Type.label; typeface=Type.bold(); gravity=Gravity.CENTER
        setTextColor(if(primary) UiTheme.role(UiTheme.Role.ON_ACCENT) else UiTheme.role(UiTheme.Role.TEXT))
        background=Glass.ripple(this@ChapterLinkAutopilotActivity,
            if(primary) Glass.accent(this@ChapterLinkAutopilotActivity,18)
            else Glass.solid(this@ChapterLinkAutopilotActivity,UiTheme.role(UiTheme.Role.CONTROL),18,UiTheme.role(UiTheme.Role.STROKE_SOFT)),18)
        isClickable=true; isFocusable=true; contentDescription=label; setOnClickListener { onClick() }
    }

    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value*resources.displayMetrics.density).toInt()
}
