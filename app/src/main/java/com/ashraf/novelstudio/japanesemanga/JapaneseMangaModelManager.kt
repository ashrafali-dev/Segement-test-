package com.ashraf.novelstudio.japanesemanga

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns only optional manga-engine files under app-private storage. */
internal class JapaneseMangaModelManager(context: Context) {
    val root = File(context.applicationContext.filesDir, "japanese_manga_engine")
    private val marker = File(root, "engine_manifest.json")
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>(null)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "japanese-manga-models").apply { isDaemon = true } }
    @Volatile private var busy = false

    data class Progress(val index: Int, val count: Int, val name: String, val read: Long, val total: Long)
    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Failure(val message: String) : Result()
    }
    private data class Asset(val path: String, val url: String, val min: Long, val max: Long)

    fun isDownloading() = busy
    fun hasFiles(): Boolean = root.exists() && (root.list()?.isNotEmpty() == true)
    fun packageBytes(): Long = SPECS.sumOf { safeFile(it.path)?.takeIf { file -> file.isFile }?.length() ?: 0L }

    @Synchronized fun isInstalled(): Boolean {
        if (!marker.isFile) return false
        return try {
            val meta = JSONObject(marker.readText())
            meta.optString("version") == VERSION && meta.optBoolean("ready") &&
                SPECS.all { s -> safeFile(s.path)?.let { it.isFile && it.length() in s.min..s.max } == true }
        } catch (_: Exception) { false }
    }

    fun cancel() {
        cancelled.set(true)
        active.getAndSet(null)?.disconnect()
    }

    fun shutdown() { cancel(); worker.shutdownNow() }

    fun download(onProgress: (Progress) -> Unit, onComplete: (Result) -> Unit) {
        synchronized(this) {
            if (busy) { onComplete(Result.Failure("ডাউনলোড ইতিমধ্যে চলছে")); return }
            busy = true
            cancelled.set(false)
        }
        worker.execute {
            var result: Result = Result.Success
            try {
                if (!root.exists() && !root.mkdirs()) error("ইঞ্জিন ফোল্ডার তৈরি করা যায়নি")
                marker.delete()
                SPECS.forEachIndexed { i, spec ->
                    checkCancel()
                    val target = safeFile(spec.path) ?: error("অবৈধ ফাইল পাথ")
                    if (target.isFile && target.length() in spec.min..spec.max) {
                        onProgress(Progress(i + 1, SPECS.size, spec.path, target.length(), target.length()))
                    } else {
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("ফোল্ডার তৈরি করা যায়নি") }
                        target.delete()
                        val part = File(target.parentFile, target.name + ".part")
                        part.delete()
                        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000; readTimeout = 45000; instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "NovelStudio-JapaneseMangaEngine/1")
                        }
                        active.set(connection)
                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) error("HTTP " + code + " · " + spec.path)
                            val total = connection.contentLengthLong
                            if (total > 0 && total !in spec.min..spec.max) error("অপ্রত্যাশিত ফাইল সাইজ · " + spec.path)
                            BufferedInputStream(connection.inputStream).use { input ->
                                FileOutputStream(part).use { output ->
                                    val buffer = ByteArray(32768)
                                    var readTotal = 0L
                                    while (true) {
                                        checkCancel()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        readTotal += n
                                        if (readTotal > spec.max) error("ফাইলের সাইজ বেশি · " + spec.path)
                                        output.write(buffer, 0, n)
                                        onProgress(Progress(i + 1, SPECS.size, spec.path, readTotal, total))
                                    }
                                    if (total > 0 && readTotal != total) error("অসম্পূর্ণ ডাউনলোড · " + spec.path)
                                }
                            }
                        } finally {
                            active.compareAndSet(connection, null)
                            connection.disconnect()
                        }
                        checkCancel()
                        if (part.length() !in spec.min..spec.max) error("ফাইল যাচাই ব্যর্থ · " + spec.path)
                        if (!part.renameTo(target)) error("ফাইল সংরক্ষণ ব্যর্থ · " + spec.path)
                    }
                }
                checkCancel()
                val files = JSONObject()
                SPECS.forEach { spec ->
                    val f = safeFile(spec.path) ?: error("অবৈধ ফাইল পাথ")
                    if (!f.isFile || f.length() !in spec.min..spec.max) error("ফাইল যাচাই ব্যর্থ · " + spec.path)
                    files.put(spec.path, JSONObject().put("bytes", f.length()).put("sha256", sha256(f)))
                }
                val temporary = File(root, "engine_manifest.part")
                temporary.writeText(JSONObject().put("ready", true).put("version", VERSION)
                    .put("runtime", ORT_VERSION).put("license", "PP-OCRv6_manga: Apache-2.0; ONNX Runtime Web: MIT")
                    .put("files", files).toString(2))
                if (!temporary.renameTo(marker)) error("ইনস্টলেশন মার্কার লেখা যায়নি")
                if (!isInstalled()) error("ইনস্টলেশনের শেষ যাচাই ব্যর্থ")
            } catch (_: CancelledException) {
                result = Result.Cancelled
                cleanupPartial()
            } catch (e: Exception) {
                result = if (cancelled.get()) Result.Cancelled else Result.Failure(e.message ?: "ডাউনলোড ব্যর্থ")
                cleanupPartial()
            } finally {
                active.getAndSet(null)?.disconnect()
                synchronized(this) { busy = false }
                onComplete(result)
            }
        }
    }

    @Synchronized fun deleteEngine(): Boolean {
        if (busy) return false
        marker.delete()
        return !root.exists() || root.deleteRecursively()
    }

    fun resolve(path: String): File? = safeFile(path)

    private fun safeFile(path: String): File? {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") ||
            path.split("/").any { it == ".." }) return null
        val base = root.canonicalFile
        val candidate = File(base, path).canonicalFile
        return candidate.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun cleanupPartial() {
        SPECS.forEach { s -> safeFile(s.path)?.let { File(it.parentFile, it.name + ".part").delete() } }
        File(root, "engine_manifest.part").delete()
        marker.delete()
    }

    private fun checkCancel() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancelledException() }
    private class CancelledException : Exception()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val VERSION = "pp-ocrv6-manga-v0.2"
        const val ORT_VERSION = "1.23.2"
        private const val HF = "https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/main/"
        private const val ORT = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.23.2/dist/"
        private val SPECS = listOf(
            Asset("ort.wasm.min.js", ORT + "ort.wasm.min.js", 10000, 200000),
            Asset("ort-wasm-simd-threaded.wasm", ORT + "ort-wasm-simd-threaded.wasm", 5000000, 40000000),
            Asset("ort-wasm-simd-threaded.mjs", ORT + "ort-wasm-simd-threaded.mjs", 5000, 200000),
            Asset("det/manga_det_v0.2.onnx", HF + "det/manga_det_v0.2.onnx", 500000, 8000000),
            Asset("rec/manga_rec_v0.2.onnx", HF + "rec/manga_rec_v0.2.onnx", 10000000, 30000000),
            Asset("ppocrv6_dict.txt", HF + "ppocrv6_dict.txt", 10000, 1000000)
        )
    }
}