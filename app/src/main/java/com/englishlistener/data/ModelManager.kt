package com.englishlistener.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadState(
    val phase: Phase = Phase.IDLE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String = "",
    val error: String? = null
)

enum class Phase { IDLE, CHECKING, DOWNLOADING, VERIFYING, COMPLETED, FAILED }

class ModelManager(private val context: Context) {
    companion object {
        private const val TAG = "ModelManager"
        private const val MT_FILE = "Hy-MT1.5-1.8B-1.25bit.gguf"
        private const val MT_SIZE = 440L * 1024 * 1024
        private val MT_URLS = listOf(
            "https://hf-mirror.com/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/$MT_FILE",
            "https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/$MT_FILE"
        )
        private const val ASR_MODEL = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
        private val ASR_URLS = listOf(
            "https://hf-mirror.com/csukuangfj/$ASR_MODEL/resolve/main",
            "https://huggingface.co/csukuangfj/$ASR_MODEL/resolve/main"
        )
        private val ASR_FILES = listOf(
            Triple("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71083163L, "语音识别引擎"),
            Triple("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1370000L, "语音解码器"),
            Triple("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 265000L, "连接器"),
            Triple("tokens.txt", 5050L, "词表")
        )
        val ASR_REQUIRED_FILES = ASR_FILES.map { it.first }
        private const val VERSION_FILE = "model_version.txt"
    }
    private val _ds = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _ds.asStateFlow()
    val modelsDir: File get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }
    val asrDir: File get() = File(modelsDir, "asr").also { it.mkdirs() }
    val translationModelFile: File get() = File(modelsDir, MT_FILE)
    fun isTranslationModelReady() = translationModelFile.exists() && translationModelFile.length() >= MT_SIZE * 0.99
    fun isAsrModelReady(): Boolean {
        val vf = File(asrDir, VERSION_FILE)
        if (!vf.exists() || vf.readText().trim() != ASR_MODEL) return false
        return ASR_REQUIRED_FILES.all { File(asrDir, it).exists() }
    }
    fun areAllModelsReady() = isTranslationModelReady() && isAsrModelReady()

    suspend fun downloadAllModels() {
        _ds.value = DownloadState(phase = Phase.CHECKING)
        if (!isAsrModelReady()) {
            val vf = File(asrDir, VERSION_FILE)
            if (vf.exists() && vf.readText().trim() != ASR_MODEL) {
                asrDir.listFiles()?.forEach { it.delete() }
                Log.d(TAG, "Cleared old ASR model")
            }
            if (!downloadAsrModel()) return
        }
        if (!isTranslationModelReady()) { if (!downloadTranslationModel()) return }
        _ds.value = DownloadState(phase = Phase.COMPLETED, progress = 1f)
    }

    suspend fun downloadTranslationModel(): Boolean {
        val f = translationModelFile; val es = if (f.exists()) f.length() else 0L
        if (es >= MT_SIZE * 0.99) return true
        _ds.value = DownloadState(phase = Phase.DOWNLOADING, currentFile = "混元翻译模型", totalBytes = MT_SIZE)
        val ok = downloadFile(MT_URLS, f, MT_SIZE, es)
        if (ok) _ds.value = _ds.value.copy(progress = 1f)
        return ok
    }

    suspend fun downloadAsrModel(): Boolean {
        if (isAsrModelReady()) return true
        val tb = ASR_FILES.sumOf { it.second }
        for ((n, s, l) in ASR_FILES) {
            val f = File(asrDir, n); val es = if (f.exists()) f.length() else 0L
            if (es >= s * 0.99) continue
            _ds.value = DownloadState(phase = Phase.DOWNLOADING, currentFile = "$l ($n)", totalBytes = s)
            val urls = ASR_URLS.map { "$it/$n" }
            if (!downloadFile(urls, f, s, es)) return false
            _ds.value = _ds.value.copy(progress = 1f)
        }
        File(asrDir, VERSION_FILE).writeText(ASR_MODEL)
        _ds.value = DownloadState(phase = Phase.VERIFYING, progress = 1f, totalBytes = tb)
        return isAsrModelReady()
    }

    private suspend fun downloadFile(urls: List<String>, file: File, exp: Long, es: Long): Boolean {
        for (url in urls) {
            val ok = withContext(Dispatchers.IO) { tryDownload(url, file, exp, es) }
            if (ok) return true
        }
        _ds.value = DownloadState(phase = Phase.FAILED, error = "所有下载源均不可用，请检查网络")
        return false
    }

    private fun tryDownload(url: String, file: File, exp: Long, es: Long): Boolean {
        try {
            var rangeOff = es
            while (true) {
                val conn = openConnection(url, rangeOff)
                val resp = conn.responseCode
                if (resp != 200 && resp != 206) {
                    Log.e(TAG, "HTTP $resp [${conn.url}]")
                    conn.disconnect()
                    return false
                }
                val total = if (resp == 206) {
                    conn.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: exp
                } else {
                    conn.contentLengthLong.coerceAtLeast(exp)
                }
                if (resp == 200 && rangeOff > 0) {
                    conn.disconnect()
                    rangeOff = 0
                    file.delete()
                    continue
                }
                val inp = conn.inputStream
                if (inp == null) { conn.disconnect(); return false }
                val out = FileOutputStream(file, rangeOff > 0)
                val buf = ByteArray(8192)
                var d = rangeOff
                var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n); d += n
                    _ds.value = _ds.value.copy(progress = d.toFloat() / total)
                }
                inp.close(); out.close(); conn.disconnect()
                return file.length() >= exp * 0.99
            }
        } catch (e: Exception) { Log.e(TAG, "dl fail: $url", e); return false }
    }

    private fun openConnection(url: String, rangeOff: Long): HttpURLConnection {
        var currentUrl = url
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "EnglishListener/1.0")
                if (rangeOff > 0) setRequestProperty("Range", "bytes=$rangeOff-")
                connect()
            }
            val code = conn.responseCode
            if (code in 200..299) return conn
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                continue
            }
            return conn
        }
        return URL(currentUrl).openConnection() as HttpURLConnection
    }

    fun deleteModels() { modelsDir.deleteRecursively(); _ds.value = DownloadState(phase = Phase.IDLE) }
}
