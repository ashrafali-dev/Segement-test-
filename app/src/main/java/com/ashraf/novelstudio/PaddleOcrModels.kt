package com.ashraf.novelstudio

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Download-on-demand PP-OCRv6 ONNX model packs.
 *
 * Small is the low-memory model; Medium (internal id "heavy", kept so saved settings still work) is the largest
 * PP-OCRv6 tier -- there is no bigger v6 model.
 * Models are never bundled into the APK. Files are validated before an atomic install.
 */
internal object PaddleOcrModels {
    data class Pack(
        val id: String,
        val label: String,
        val detectorUrl: String,
        val recognitionUrl: String,
        val configUrl: String,
        val detectorSha256: String?,
        val recognitionSha256: String?,
        val approximateMb: Int,
        val recognitionBatchSize: Int,
        val threads: Int
    )

    data class Files(val detector: File, val recognition: File, val config: File)

    // Official PaddlePaddle ONNX exports. Recognition config files must come from the same model revision.
    val PACKS = listOf(
        Pack(
            id = "small",
            label = "PP-OCRv6 Small",
            detectorUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/5994b4f1a827310b6d38f6b13716af6381847be0/inference.onnx",
            recognitionUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/d9754520d68905205e3bbf32142a337d843353c4/inference.onnx",
            configUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/d9754520d68905205e3bbf32142a337d843353c4/inference.yml",
            detectorSha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            recognitionSha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
            approximateMb = 32,
            recognitionBatchSize = 8,
            threads = 2
        ),
        Pack(
            id = "heavy",
            label = "PP-OCRv6 Medium",
            detectorUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/dbee314/inference.onnx",
            recognitionUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/12b418c3792e3ccf3fed807eb17863981051955e/inference.onnx",
            configUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/12b418c3792e3ccf3fed807eb17863981051955e/inference.yml",
            detectorSha256 = "eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1",
            recognitionSha256 = "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
            approximateMb = 140,
            recognitionBatchSize = 4,
            threads = 2
        ),
        // Korean: official PP-OCRv5 Korean recognizer (ONNX, CTC, Hangul dictionary in inference.yml, 48 px height --
        // the same preprocessing/decoder the runtime already uses). The PP-OCRv6 Small detector is reused: text
        // detection does not depend on the script. Revision pinned; rec SHA-256 from the Hugging Face LFS pointer.
        Pack(
            id = "korean",
            label = "PP-OCRv5 Korean (Hangul)",
            detectorUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/5994b4f1a827310b6d38f6b13716af6381847be0/inference.onnx",
            recognitionUrl = "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/5c6f574b8e2230adf4287b33e736d71b9fabd28e/inference.onnx",
            configUrl = "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/5c6f574b8e2230adf4287b33e736d71b9fabd28e/inference.yml",
            detectorSha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            recognitionSha256 = "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08",
            approximateMb = 25,
            recognitionBatchSize = 8,
            threads = 2
        )
    )

    /** True when the Korean Paddle pack is fully downloaded and valid. */
    fun koreanInstalled(): Boolean = pack("korean")?.let { isInstalled(it) } == true

    @Volatile var version: Int = 0
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile private var appContext: Context? = null
    private val busy = HashSet<String>()

    fun init(context: Context) {
        if (appContext == null) synchronized(this) {
            if (appContext == null) appContext = context.applicationContext
        }
    }

    fun pack(id: String): Pack? = PACKS.firstOrNull { it.id == id }

    private fun root(): File? = appContext?.let { File(it.filesDir, "paddle_ocr_v6") }
    fun files(pack: Pack): Files {
        val folder = File(root() ?: throw IOException("PaddleOCR storage is not initialized"), pack.id)
        return Files(
            File(folder, "det.onnx"),
            File(folder, "rec.onnx"),
            File(folder, "rec.yml")
        )
    }

    fun isInstalled(pack: Pack): Boolean = try {
        val f = files(pack)
        validModelFile(f.detector) && validModelFile(f.recognition) && validConfigFile(f.config)
    } catch (_: Exception) {
        false
    }

    fun isBusy(pack: Pack): Boolean = synchronized(busy) { pack.id in busy }

    fun sizeMb(pack: Pack): Int {
        return try {
            val f = files(pack)
            val bytes = listOf(f.detector, f.recognition, f.config).sumOf { if (it.isFile) it.length() else 0L }
            maxOf(1, (bytes / (1024L * 1024L)).toInt())
        } catch (_: Exception) {
            0
        }
    }

    /** Blocking: invoke only from a worker thread. Installs each file only after validation. */
    fun download(pack: Pack, onProgress: (String, Int) -> Unit) {
        synchronized(busy) {
            if (!busy.add(pack.id)) throw IOException(pack.label + " is already downloading")
        }
        try {
            val f = files(pack)
            val specs = listOf(
                ModelFile(f.detector, pack.detectorUrl, pack.detectorSha256, true),
                ModelFile(f.recognition, pack.recognitionUrl, pack.recognitionSha256, true),
                ModelFile(f.config, pack.configUrl, null, false)
            )
            for (spec in specs) {
                if (spec.isModel && validModelFile(spec.target) || !spec.isModel && validConfigFile(spec.target)) continue
                if (spec.target.exists()) spec.target.delete()
                fetch(spec, onProgress)
            }
            if (!isInstalled(pack)) throw IOException("Downloaded model pack failed final validation")
            lastError = ""
            synchronized(this) { version++ }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            synchronized(busy) { busy.remove(pack.id) }
        }
    }

    fun delete(pack: Pack) {
        runCatching { files(pack) }.getOrNull()?.let { f ->
            f.detector.delete()
            f.recognition.delete()
            f.config.delete()
            f.detector.parentFile?.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        }
        synchronized(this) { version++ }
    }

    fun deleteAll() {
        root()?.listFiles()?.forEach { folder ->
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
        synchronized(this) { version++ }
    }

    private data class ModelFile(
        val target: File,
        val url: String,
        val sha256: String?,
        val isModel: Boolean
    )

    private fun fetch(spec: ModelFile, onProgress: (String, Int) -> Unit) {
        if (spec.url.isBlank()) throw IOException("No download URL configured for " + spec.target.name)
        spec.target.parentFile?.mkdirs()
        val part = File(spec.target.path + ".part")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NovelStudio-PaddleOCR")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP " + code + " while downloading " + spec.target.name)
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastPct = -1
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        if (total > 0L) {
                            val pct = (downloaded * 100L / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(spec.target.name, pct)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (downloaded < 1024L) throw IOException("Downloaded file is too small: " + spec.target.name)
            val head = part.inputStream().use { input -> ByteArray(96).also { input.read(it) } }
            val prefix = String(head, Charsets.ISO_8859_1).trimStart()
            if (prefix.startsWith("<!DOCTYPE", true) || prefix.startsWith("<html", true) || prefix.startsWith("{\"error\"", true)) {
                throw IOException("The model host returned an error page for " + spec.target.name)
            }
            if (spec.sha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(spec.sha256, ignoreCase = true)) throw IOException("SHA-256 verification failed for " + spec.target.name)
            }
            if (spec.isModel) {
                if (downloaded < 1_000_000L) throw IOException("ONNX model is unexpectedly small: " + spec.target.name)
            } else {
                val yaml = part.readText(Charsets.UTF_8)
                if (!yaml.contains("PostProcess:") || !yaml.contains("character_dict:") || yaml.contains('\uFFFD')) {
                    throw IOException("Recognition dictionary config is invalid: " + spec.target.name)
                }
            }
            if (!part.renameTo(spec.target)) {
                part.copyTo(spec.target, overwrite = true)
                if (!spec.target.isFile || spec.target.length() != part.length()) throw IOException("Could not install " + spec.target.name)
                part.delete()
            }
        } finally {
            connection?.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private fun validModelFile(file: File): Boolean = file.isFile && file.length() >= 1_000_000L
    private fun validConfigFile(file: File): Boolean {
        if (!file.isFile || file.length() < 10_000L || file.length() > 25_000_000L) return false
        return try {
            val head = file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText().take(250_000) }
            head.contains("PostProcess:") && head.contains("character_dict:")
        } catch (_: Exception) {
            false
        }
    }
}
