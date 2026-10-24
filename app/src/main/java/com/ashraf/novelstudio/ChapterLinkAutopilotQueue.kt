package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class ChapterLinkAutopilotJob(
    val id: String,
    val url: String,
    val resolvedUrl: String = "",
    val title: String = "",
    val novel: String = "",
    val number: String = "",
    val rawText: String = "",
    val status: String = ChapterLinkAutopilotQueue.QUEUED,
    val stage: String = "Waiting",
    val progress: Int = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

internal object ChapterLinkAutopilotUrlParser {
    private val pattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val trailing = charArrayOf('.', ',', ';', ':', ')', ']', '}', '"', '\'')

    fun extract(raw: String): List<String> = pattern.findAll(raw)
        .map { it.value.trim().trimEnd(*trailing) }
        .filter(::isHttpUrl)
        .distinctBy(::canonical)
        .toList()

    fun isHttpUrl(raw: String): Boolean = try {
        val uri = URI(raw.trim())
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) { false }

    fun canonical(raw: String): String = try {
        val u = URI(raw.trim())
        URI(u.scheme?.lowercase(), null, u.host?.lowercase(), u.port, u.path, u.query, null)
            .toASCIIString().trimEnd('/')
    } catch (_: Exception) { raw.trim().substringBefore('#').trimEnd('/') }
}

internal object ChapterLinkAutopilotQueue {
    const val ACTION_START = "com.ashraf.novelstudio.action.START_CHAPTER_LINK_AUTOPILOT"
    const val QUEUED = "QUEUED"
    const val EXTRACTING = "EXTRACTING"
    const val SCANNING = "SCANNING"
    const val TRANSLATING = "TRANSLATING"
    const val READY = "READY"
    const val FAILED = "FAILED"

    private const val PREFS = "chapter_link_autopilot"
    private const val JOBS = "jobs_v1"
    private const val PAUSED = "paused"
    private val lock = Any()
    private val runnable = setOf(QUEUED, EXTRACTING, SCANNING, TRANSLATING)

    fun list(c: Context): List<ChapterLinkAutopilotJob> = synchronized(lock) { read(c).sortedBy { it.createdAt } }

    fun addUrls(c: Context, text: String): Int = synchronized(lock) {
        val jobs = read(c).toMutableList()
        val seen = jobs.flatMap { listOf(it.url, it.resolvedUrl) }.filter(String::isNotBlank)
            .map(ChapterLinkAutopilotUrlParser::canonical).toMutableSet()
        var added = 0
        ChapterLinkAutopilotUrlParser.extract(text).forEach { url ->
            if (seen.add(ChapterLinkAutopilotUrlParser.canonical(url))) {
                jobs.add(ChapterLinkAutopilotJob(UUID.randomUUID().toString(), url))
                added++
            }
        }
        if (added > 0) write(c, jobs)
        added
    }

    fun update(
        c: Context, id: String, status: String? = null, stage: String? = null,
        progress: Int? = null, error: String? = null, resolvedUrl: String? = null,
        title: String? = null, novel: String? = null, number: String? = null, rawText: String? = null
    ) = synchronized(lock) {
        val jobs = read(c).toMutableList()
        val i = jobs.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        val old = jobs[i]
        jobs[i] = old.copy(
            status=status ?: old.status, stage=stage ?: old.stage,
            progress=(progress ?: old.progress).coerceIn(0,100), error=error ?: old.error,
            resolvedUrl=resolvedUrl ?: old.resolvedUrl, title=title ?: old.title,
            novel=novel ?: old.novel, number=number ?: old.number, rawText=rawText ?: old.rawText,
            updatedAt=System.currentTimeMillis()
        )
        write(c,jobs)
    }

    fun setPaused(c: Context, paused: Boolean) { c.getSharedPreferences(PREFS,0).edit().putBoolean(PAUSED,paused).apply() }
    fun isPaused(c: Context): Boolean = c.getSharedPreferences(PREFS,0).getBoolean(PAUSED,false)
    fun hasRunnable(c: Context): Boolean = !isPaused(c) && list(c).any { it.status in runnable }
    fun hasBackgroundWork(c: Context): Boolean {
        val jobs=list(c)
        // Queued-only work does not start a keep-alive by itself; the user must press Start.
        return jobs.any { it.status == EXTRACTING || it.status == SCANNING || it.status == TRANSLATING }
    }
    fun nextRunnable(c: Context): ChapterLinkAutopilotJob? =
        if (isPaused(c)) null else list(c).firstOrNull { it.status in runnable }

    fun retryFailed(c: Context): Int = synchronized(lock) {
        val jobs=read(c).toMutableList(); var n=0
        for(i in jobs.indices) if(jobs[i].status==FAILED) {
            val old=jobs[i]
            jobs[i]=old.copy(status=QUEUED,stage=if(old.rawText.isNotBlank()) "Retrying saved OCR text" else "Queued for retry",
                progress=if(old.rawText.isNotBlank()) 82 else 0,error="",updatedAt=System.currentTimeMillis())
            n++
        }
        if(n>0) write(c,jobs)
        n
    }

    fun clearReady(c: Context): Int = synchronized(lock) {
        val jobs=read(c); val kept=jobs.filterNot { it.status==READY }; val n=jobs.size-kept.size
        if(n>0) write(c,kept)
        n
    }

    fun isAutopilotUrl(c: Context, url: String): Boolean {
        if(url.isBlank()) return false
        val key=ChapterLinkAutopilotUrlParser.canonical(url)
        return list(c).any { it.status!=FAILED &&
            (ChapterLinkAutopilotUrlParser.canonical(it.url)==key ||
                (it.resolvedUrl.isNotBlank() && ChapterLinkAutopilotUrlParser.canonical(it.resolvedUrl)==key)) }
    }

    fun matches(job: ChapterLinkAutopilotJob, url: String): Boolean {
        if(url.isBlank()) return false
        val key=ChapterLinkAutopilotUrlParser.canonical(url)
        return ChapterLinkAutopilotUrlParser.canonical(job.url)==key ||
            (job.resolvedUrl.isNotBlank() && ChapterLinkAutopilotUrlParser.canonical(job.resolvedUrl)==key)
    }

    private fun read(c: Context): List<ChapterLinkAutopilotJob> = try {
        val arr=JSONArray(c.getSharedPreferences(PREFS,0).getString(JOBS,"[]").orEmpty())
        buildList {
            for(i in 0 until arr.length()) {
                val o=arr.optJSONObject(i) ?: continue
                val url=o.optString("url")
                if(!ChapterLinkAutopilotUrlParser.isHttpUrl(url)) continue
                add(ChapterLinkAutopilotJob(
                    id=o.optString("id").ifBlank { UUID.randomUUID().toString() }, url=url,
                    resolvedUrl=o.optString("resolvedUrl"), title=o.optString("title"), novel=o.optString("novel"),
                    number=o.optString("number"), rawText=o.optString("rawText"),
                    status=o.optString("status",QUEUED).ifBlank { QUEUED },
                    stage=o.optString("stage","Waiting").ifBlank { "Waiting" },
                    progress=o.optInt("progress",0).coerceIn(0,100), error=o.optString("error"),
                    createdAt=o.optLong("createdAt",System.currentTimeMillis()),
                    updatedAt=o.optLong("updatedAt",System.currentTimeMillis())
                ))
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun write(c: Context, jobs: List<ChapterLinkAutopilotJob>) {
        val arr=JSONArray()
        jobs.forEach { j -> arr.put(JSONObject()
            .put("id",j.id).put("url",j.url).put("resolvedUrl",j.resolvedUrl)
            .put("title",j.title).put("novel",j.novel).put("number",j.number).put("rawText",j.rawText)
            .put("status",j.status).put("stage",j.stage).put("progress",j.progress).put("error",j.error)
            .put("createdAt",j.createdAt).put("updatedAt",j.updatedAt)) }
        c.getSharedPreferences(PREFS,0).edit().putString(JOBS,arr.toString()).apply()
    }
}

/** Uses the existing WebModeExtractor and MainActivity chatbot queue; it does not duplicate OCR or chatbot code. */
internal class ChapterLinkAutopilotRunner(
    context: Context,
    private val handler: Handler,
    private val webView: WebView,
    private val extractorProvider: () -> WebModeExtractor,
    private val enqueueForChatbot: (Chapter) -> Unit,
    private val onStateChanged: () -> Unit = {}
) {
    private val app=context.applicationContext
    private var active=false
    private var current: ChapterLinkAutopilotJob?=null
    private var waitingForLoad=false
    private var scanning=false
    private var scanHandle: WebModeExtractor.Handle?=null
    private var loadTimeout: Runnable?=null
    private var translatePoll: Runnable?=null
    private var translationStarted=0L
    private var token=0

    fun start() { active=true; next() }

    fun onPageFinished(url: String?) {
        val job=current ?: return
        if(!active || !waitingForLoad || scanning || url.isNullOrBlank()) return
        val actual=webView.url.orEmpty()
        if(actual.isBlank() || actual!=url) return
        if(!ChapterLinkAutopilotUrlParser.isHttpUrl(actual)) { fail(job.id,"Unsupported redirect URL."); return }
        waitingForLoad=false; scanning=true
        loadTimeout?.let(handler::removeCallbacks); loadTimeout=null
        ChapterLinkAutopilotQueue.update(app,job.id,status=ChapterLinkAutopilotQueue.SCANNING,
            resolvedUrl=actual,stage="Scanning chapter images with existing OCR",progress=1,error="")
        onStateChanged()
        val run=token
        try {
            scanHandle=extractorProvider().start(WebModeRequest(
                webView=webView,
                isValid={ active && token==run && current?.id==job.id },
                onProgress={ stage,pct ->
                    if(active && token==run && current?.id==job.id) {
                        ChapterLinkAutopilotQueue.update(app,job.id,status=ChapterLinkAutopilotQueue.SCANNING,
                            resolvedUrl=actual,stage=stage,progress=pct,error="")
                        onStateChanged()
                    }
                },
                onDone={ out ->
                    if(active && token==run && current?.id==job.id) {
                        scanHandle=null
                        when(out) {
                            is WebModeOutcome.Failure -> fail(job.id,out.message.ifBlank { "Chapter OCR failed." })
                            is WebModeOutcome.Success -> {
                                val ch=out.chapter
                                if(ch.text.trim().length<10) fail(job.id,"OCR did not find enough readable text.")
                                else {
                                    ChapterLinkAutopilotQueue.update(app,job.id,status=ChapterLinkAutopilotQueue.TRANSLATING,
                                        resolvedUrl=ch.url,title=ch.title,novel=ch.novel,number=ch.number,rawText=ch.text,
                                        stage="OCR complete — preparing chatbot translation",progress=82,error="")
                                    onStateChanged()
                                    submit(job.id,ch)
                                }
                            }
                        }
                    }
                }
            ))
        } catch(t:Throwable) { fail(job.id,"Could not start OCR: "+t.javaClass.simpleName) }
    }

    fun onPageLoadError(url: String?, message: String) {
        val job=current ?: return
        if(!active || !waitingForLoad) return
        if(!url.isNullOrBlank() && url!=job.url && url!=job.resolvedUrl) return
        fail(job.id,"Could not load chapter page: "+message.take(120))
    }

    fun onTranslationSaved(ch: Chapter) {
        val snapshot=current ?: return
        val job=ChapterLinkAutopilotQueue.list(app).firstOrNull { it.id==snapshot.id } ?: snapshot
        if(job.status!=ChapterLinkAutopilotQueue.TRANSLATING || !ChapterLinkAutopilotQueue.matches(job,ch.url)) return
        val saved=Store.find(app,ch) ?: return
        if(Store.readRaw(app,saved.id).trim().length>=30) ready(job.id)
    }

    fun onTranslationFailed(ch: Chapter, message: String) {
        val snapshot=current ?: return
        val job=ChapterLinkAutopilotQueue.list(app).firstOrNull { it.id==snapshot.id } ?: snapshot
        if(job.status!=ChapterLinkAutopilotQueue.TRANSLATING || !ChapterLinkAutopilotQueue.matches(job,ch.url)) return
        handler.postDelayed({ if(current?.id==job.id) fail(job.id,message.ifBlank { "Chatbot translation failed." }) },650L)
    }

    fun stopForProcessRecreation() {
        active=false; token++
        scanHandle?.cancel(); scanHandle=null
        loadTimeout?.let(handler::removeCallbacks); loadTimeout=null
        translatePoll?.let(handler::removeCallbacks); translatePoll=null
        runCatching { webView.stopLoading() }
        current?.let { job ->
            val latest=ChapterLinkAutopilotQueue.list(app).firstOrNull { it.id==job.id }
            if(latest!=null && latest.status!=ChapterLinkAutopilotQueue.READY && latest.status!=ChapterLinkAutopilotQueue.FAILED)
                ChapterLinkAutopilotQueue.update(app,job.id,status=ChapterLinkAutopilotQueue.QUEUED,
                    stage=if(latest.rawText.isNotBlank()) "Resuming saved OCR text" else "Will resume chapter scan",
                    progress=if(latest.rawText.isNotBlank()) 82 else 0)
        }
        current=null; waitingForLoad=false; scanning=false
    }

    private fun next() {
        if(!active || current!=null) return
        if(ChapterLinkAutopilotQueue.isPaused(app)) { onStateChanged(); return }
        val job=ChapterLinkAutopilotQueue.nextRunnable(app) ?: run { onStateChanged(); return }
        current=job; token++; val run=token
        waitingForLoad=false; scanning=false; scanHandle=null

        if(job.rawText.isNotBlank() && job.status==ChapterLinkAutopilotQueue.TRANSLATING) {
            submit(job.id,Chapter(title=job.title.ifBlank { "Chapter" },text=job.rawText,next=null,prev=null,
                url=job.resolvedUrl.ifBlank { job.url },novel=job.novel.ifBlank { Uri.parse(job.url).host.orEmpty() },
                number=job.number,contentSel=""))
            return
        }

        ChapterLinkAutopilotQueue.update(app,job.id,status=ChapterLinkAutopilotQueue.EXTRACTING,
            stage="Opening chapter link",progress=0,error="")
        onStateChanged()
        waitingForLoad=true
        try {
            webView.onResume(); webView.resumeTimers(); webView.stopLoading()
            webView.loadUrl(job.resolvedUrl.ifBlank { job.url })
        } catch(t:Throwable) { fail(job.id,"Could not open chapter link: "+t.javaClass.simpleName); return }
        val timeout=Runnable {
            if(active && token==run && current?.id==job.id && waitingForLoad) fail(job.id,"Timed out while loading chapter page.")
        }
        loadTimeout=timeout; handler.postDelayed(timeout,45_000L)
    }

    private fun submit(id: String,ch: Chapter) {
        if(!active || current?.id!=id) return
        val saved=Store.find(app,ch)
        if(saved!=null && Store.readRaw(app,saved.id).trim().length>=30) { ready(id); return }
        ChapterLinkAutopilotQueue.update(app,id,status=ChapterLinkAutopilotQueue.TRANSLATING,
            resolvedUrl=ch.url,title=ch.title,novel=ch.novel,number=ch.number,rawText=ch.text,
            stage="Sending OCR text to the existing chatbot queue",progress=88,error="")
        onStateChanged(); translationStarted=SystemClock.elapsedRealtime()
        try { enqueueForChatbot(ch) } catch(t:Throwable) { fail(id,"Could not queue translation: "+t.javaClass.simpleName); return }
        poll(id,ch)
    }

    private fun poll(id: String,ch: Chapter) {
        translatePoll?.let(handler::removeCallbacks)
        val r=Runnable {
            if(!active || current?.id!=id) return@Runnable
            val saved=Store.find(app,ch)
            if(saved!=null && Store.readRaw(app,saved.id).trim().length>=30) { ready(id); return@Runnable }
            if(SystemClock.elapsedRealtime()-translationStarted>=12*60*1000L) {
                fail(id,"No saved chatbot reply yet. Check chatbot login/session, then retry."); return@Runnable
            }
            onStateChanged(); poll(id,ch)
        }
        translatePoll=r; handler.postDelayed(r,1800L)
    }

    private fun ready(id: String) {
        if(current?.id!=id) return
        ChapterLinkAutopilotQueue.update(app,id,status=ChapterLinkAutopilotQueue.READY,
            stage="Translation saved in Novel Studio Library",progress=100,error="")
        translatePoll?.let(handler::removeCallbacks); translatePoll=null
        loadTimeout?.let(handler::removeCallbacks); loadTimeout=null
        current=null; waitingForLoad=false; scanning=false; onStateChanged()
        handler.postDelayed({ if(active) next() },350L)
    }

    private fun fail(id: String,message: String) {
        if(current?.id!=id) return
        scanHandle?.cancel(); scanHandle=null
        loadTimeout?.let(handler::removeCallbacks); loadTimeout=null
        translatePoll?.let(handler::removeCallbacks); translatePoll=null
        val p=ChapterLinkAutopilotQueue.list(app).firstOrNull { it.id==id }?.progress ?: 0
        ChapterLinkAutopilotQueue.update(app,id,status=ChapterLinkAutopilotQueue.FAILED,
            stage="Needs attention",error=message,progress=p)
        current=null; waitingForLoad=false; scanning=false; onStateChanged()
        handler.postDelayed({ if(active) next() },700L)
    }
}
