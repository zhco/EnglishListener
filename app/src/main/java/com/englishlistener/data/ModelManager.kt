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

enum class Phase {
    IDLE, CHECKING, DOWNLOADING, VERIFYING, COMPLETED, FAILED
}

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
        private const val ASR_BASE = "https://hf-mirror.com/csukuangfj/$ASR_MODEL/resolve/main"
        private const val ASR_FB = "https://huggingface.co/csukuangfj/$ASR_MODEL/resolve/main"
        private val ASR_FILES = listOf(
            Triple("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 74600000L, "\u8bed\u97f3\u8bc6\u522b\u5f15\u64ce"),
            Triple("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1370000L, "\u8bed\u97f3\u89e3\u7801\u5668"),
            Triple("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 265000L, "\u8fde\u63a5\u5668"),
            Triple("tokens.txt", 50000L, "\u8bcd\u8868")
        )
        val ASR_REQUIRED_FILES = ASR_FILES.map { it.first }
        private const val VERSION_FILE = "model_version.txt"
        private const val MAX_REDIRECTS = 5
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
        _ds.value = DownloadState(phase = Phase.DOWNLOADING, currentFile = "\u6df7\u5143\u7ffb\u8bd1\u6a21\u578b", totalBytes = MT_SIZE)
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
            if (!downloadFile(listOf("$ASR_BASE/$n", "$ASR_FB/$n"), f, s, es)) return false
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
        _ds.value = DownloadState(phase = Phase.FAILED, error = "\u6240\u6709\u4e0b\u8f7d\u6e90\u5747\u4e0d\u53ef\u7528\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc")
        return false
    }

    private fun tryDownload(url: String, file: File, exp: Long, es: Long): Boolean {
        try {
            val conn = followRedirects(url)
            val ts = when (conn.responseCode) {
                206 -> conn.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: exp
                200 -> conn.contentLengthLong.coerceAtLeast(exp)
                else -> { Log.e(TAG, "HTTP ${conn.responseCode} [${url.substringAfterLast('/')}]"); conn.disconnect(); return false }
            }
            val inp = conn.inputStream ?: run { conn.disconnect(); return false }
            val out = FileOutputStream(file, es > 0)
            val buf = ByteArray(8192)
            var d = es
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n); d += n
                _ds.value = _ds.value.copy(progress = d.toFloat() / ts)
            }
            inp.close(); out.close(); conn.disconnect()
            return file.length() >= exp * 0.99
        } catch (e: Exception) { Log.e(TAG, "dl fail: $url", e); return false }
    }

    private fun followRedirects(urlStr: String): HttpURLConnection {
        var url = URL(urlStr)
        var hops = 0
        while (hops < MAX_REDIRECTS) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "EnglishListener/1.0")
                connect()
            }
            when (conn.responseCode) {
                301, 302, 307, 308 -> {
                    val loc = conn.getHeaderField("Location") ?: throw Exception("Redirect without Location")
                    conn.disconnect()
                    url = URL(loc)
                    hops++
                }
                else -> return conn
            }
        }
        throw Exception("Too many redirects")
    }

    fun deleteModels() { modelsDir.deleteRecursively(); _ds.value = DownloadState(phase = Phase.IDLE) }
}
