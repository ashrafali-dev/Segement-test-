package com.ashraf.novelstudio

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-demand ONNX OCR models. Nothing is bundled in the APK: the user downloads a language pack
 * from the menu and can delete it again. Files live in filesDir/ocr. Every .onnx file is checked
 * against a SHA-256 before it is accepted; a failed or interrupted download leaves nothing behind.
 * Without a pack, [OnnxOcrEngine] reports "unavailable" and ML Kit reads the page.
 */
internal object OcrModels {
    private const val BASE = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/"

    /**
     * [sha256] empty = no published hash is known: the file is only checked for being a plausible
     * dictionary. [url] empty = not configured yet (the pack shows as unavailable).
     */
    class ModelFile(val fileName: String, val url: String, val sha256: Set<String>, val approxMb: Int)

    class Pack(val id: String, val label: String, val script: OcrScript, val files: List<ModelFile>) {
        val available: Boolean get() = files.all { it.url.isNotBlank() } && DETECTOR.url.isNotBlank()
    }

    /** Text detector shared by every language pack. */
    val DETECTOR = ModelFile(
        "det.onnx", BASE + "onnx/PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx",
        setOf("d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9"), 5
    )

    val PACKS: List<Pack> = listOf(
        Pack("ja", "🇯🇵 Japanese", OcrScript.JAPANESE, listOf(
            ModelFile(
                "rec_ja.onnx", BASE + "onnx/PP-OCRv4/rec/japan_PP-OCRv4_rec_mobile.onnx",
                setOf("e1075a67dba758ecfc7ebc78a10ae61c95ac8fb66a9c86fab5541e33f085cb7a"), 10
            ),
            ModelFile(
                "dict_ja.txt", BASE + "paddle/PP-OCRv4/rec/japan_PP-OCRv4_rec_mobile/japan_dict.txt",
                emptySet(), 1
            )
        )),
        Pack("en", "🇬🇧 English / Latin", OcrScript.LATIN, listOf(
            ModelFile(
                "rec_en.onnx", BASE + "onnx/PP-OCRv4/rec/en_PP-OCRv4_rec_mobile.onnx",
                setOf("e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318"), 8
            ),
            ModelFile(
                "dict_en.txt", BASE + "paddle/PP-OCRv4/rec/en_PP-OCRv4_rec_mobile/en_dict.txt",
                emptySet(), 1
            )
        )),
        Pack("ko", "🇰🇷 Korean", OcrScript.KOREAN, listOf(
            ModelFile(
                "rec_korean.onnx", BASE + "onnx/PP-OCRv4/rec/korean_PP-OCRv4_rec_mobile.onnx",
                setOf("ab151ba9065eccd98f884cf4d927db091be86137276392072edd4f9d43ad7426"), 10
            ),
            ModelFile(
                "dict_korean.txt", BASE + "paddle/PP-OCRv4/rec/korean_PP-OCRv4_rec_mobile/korean_dict.txt",
                emptySet(), 1
            )
        )),
        Pack("zh", "🇨🇳 Chinese", OcrScript.CHINESE, listOf(
            ModelFile(
                "rec_ch.onnx", BASE + "onnx/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile.onnx",
                setOf("48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"), 10
            ),
            ModelFile(
                "dict_ch.txt", BASE + "paddle/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile/ppocr_keys_v1.txt",
                emptySet(), 1
            )
        ))
    )

    /** Bumped on every install/delete so the engine drops its cached sessions. */
    @Volatile var version = 0
    /** Last reason the ONNX engine could not be used (shown in the model dialog). */
    @Volatile var lastError = ""

    @Volatile private var appContext: Context? = null
    private val busy = HashSet<String>()

    fun init(c: Context) { if (appContext == null) appContext = c.applicationContext }

    private fun dir(): File? = appContext?.let { File(it.filesDir, "ocr") }
    private fun file(name: String): File? = dir()?.let { File(it, name) }

    fun readFile(name: String): ByteArray? = try {
        file(name)?.takeIf { it.isFile }?.readBytes()
    } catch (_: IOException) { null }

    fun isInstalled(p: Pack): Boolean =
        file(DETECTOR.fileName)?.isFile == true && p.files.all { file(it.fileName)?.isFile == true }

    fun isBusy(p: Pack): Boolean = synchronized(busy) { p.id in busy }

    fun sizeMb(p: Pack): Int {
        var bytes = 0L
        for (f in p.files) bytes += file(f.fileName)?.takeIf { it.isFile }?.length() ?: 0L
        return Math.max(1, (bytes / (1024 * 1024)).toInt())
    }

    fun approxMb(p: Pack): Int {
        var mb = 0
        for (f in p.files) mb += f.approxMb
        if (file(DETECTOR.fileName)?.isFile != true) mb += DETECTOR.approxMb
        return mb
    }

    /** Blocking: call from a worker thread. [onProgress] gets (file name, percent). Throws on any failure. */
    fun download(p: Pack, onProgress: (String, Int) -> Unit) {
        if (!p.available) throw IOException("${p.label}: download link not configured yet")
        synchronized(busy) { if (!busy.add(p.id)) throw IOException("already downloading") }
        try {
            val todo = ArrayList<ModelFile>()
            if (file(DETECTOR.fileName)?.isFile != true) todo.add(DETECTOR)
            for (f in p.files) if (file(f.fileName)?.isFile != true) todo.add(f)
            for (f in todo) fetch(f) { pct -> onProgress(f.fileName, pct) }
            lastError = ""
            version++
        } finally {
            synchronized(busy) { busy.remove(p.id) }
        }
    }

    /** Deletes the pack; the shared detector goes too once no pack is left. */
    fun delete(p: Pack) {
        for (f in p.files) file(f.fileName)?.delete()
        if (PACKS.none { isInstalled(it) }) file(DETECTOR.fileName)?.delete()
        version++
    }

    fun deleteAll() {
        dir()?.listFiles()?.forEach { it.delete() }
        version++
    }

    private fun fetch(mf: ModelFile, onPct: (Int) -> Unit) {
        val target = file(mf.fileName) ?: throw IOException("storage not ready")
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(mf.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NovelStudio")
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code for ${mf.fileName}")
            val total = conn.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onPct(pct) }
                        }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (mf.sha256.isNotEmpty() && hex !in mf.sha256) throw IOException("hash mismatch for ${mf.fileName}")
            if (mf.sha256.isEmpty() && !looksLikeDictionary(part)) throw IOException("${mf.fileName} is not a valid dictionary")
            if (!part.renameTo(target)) {
                part.copyTo(target, true)
            }
        } finally {
            conn?.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private fun looksLikeDictionary(f: File): Boolean {
        if (f.length() < 50 || f.length() > 2_000_000L) return false
        val text = String(f.readBytes(), Charsets.UTF_8)
        if (text.contains('\uFFFD')) return false
        return text.lines().count { it.isNotBlank() } >= 20
    }
}
