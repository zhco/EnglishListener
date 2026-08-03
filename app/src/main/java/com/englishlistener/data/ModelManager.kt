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

/**
 * 模型下载状态（全局，两张模型共享同一个进度条）
 */
data class DownloadState(
    val phase: Phase = Phase.IDLE,
    val progress: Float = 0f,           // 0..1 总体进度
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String = "",       // 当前正在下载的文件名
    val error: String? = null
)

enum class Phase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    EXTRACTING,   // 解压中（ASR tar.bz2）
    VERIFYING,
    COMPLETED,
    FAILED
}

/**
 * 模型下载管理器
 *
 * 两张模型：
 * 1. 混元翻译模型 HY-MT1.5-1.25bit (440MB GGUF)
 *    → hf-mirror.com / HuggingFace
 * 2. sherpa-onnx ASR 中英双语模型 (INT8, ~200MB tar.bz2)
 *    → GitHub Releases / hf-mirror.com
 *
 * 存储：getExternalFilesDir("models")
 * 支持断点续传 (HTTP Range)
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"

        // ──── 翻译模型 ────
        private const val MT_FILE = "Hy-MT1.5-1.8B-1.25bit.gguf"
        private const val MT_SIZE = 440L * 1024 * 1024

        private val MT_URLS = listOf(
            "https://hf-mirror.com/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/$MT_FILE",
            "https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/$MT_FILE"
        )

        // ──── ASR 模型 (sherpa-onnx Zipformer 中英双语 INT8) ────
        private const val ASR_TAR = "sherpa-onnx-asr-bilingual-zh-en.tar.bz2"
        private const val ASR_SIZE = 210L * 1024 * 1024

        // ASR 解压后必需的文件清单
        val ASR_REQUIRED_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )

        private val ASR_URLS = listOf(
            // GitHub Releases 官方（海外用户快）
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            // hf-mirror 镜像（国内快）
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2"
        )
    }

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** 模型根目录 */
    val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    /** ASR 模型子目录 */
    val asrDir: File
        get() = File(modelsDir, "asr").also { it.mkdirs() }

    // ──── 就绪检查 ────

    val translationModelFile: File get() = File(modelsDir, MT_FILE)

    fun isTranslationModelReady(): Boolean =
        translationModelFile.exists() && translationModelFile.length() >= MT_SIZE * 0.99

    fun isAsrModelReady(): Boolean =
        ASR_REQUIRED_FILES.all { name -> File(asrDir, name).exists() }

    fun areAllModelsReady(): Boolean = isTranslationModelReady() && isAsrModelReady()

    // ──── 下载入口：顺序下载两张模型 ────

    suspend fun downloadAllModels() {
        _downloadState.value = DownloadState(phase = Phase.CHECKING)

        // 先下 ASR（体积小，让用户早点看到进展）
        if (!isAsrModelReady()) {
            val ok = downloadAsrModel()
            if (!ok) return
        }

        // 再下翻译模型
        if (!isTranslationModelReady()) {
            val ok = downloadTranslationModel()
            if (!ok) return
        }

        _downloadState.value = DownloadState(phase = Phase.COMPLETED, progress = 1f)
    }

    // ──── 单个模型下载 ────

    suspend fun downloadTranslationModel(): Boolean {
        val file = translationModelFile
        val existingSize = if (file.exists()) file.length() else 0L

        if (existingSize >= MT_SIZE * 0.99) return true

        _downloadState.value = DownloadState(
            phase = Phase.DOWNLOADING, currentFile = "混元翻译模型"
        )
        return downloadFile(MT_URLS, file, MT_SIZE, existingSize)
    }

    suspend fun downloadAsrModel(): Boolean {
        if (isAsrModelReady()) return true

        val tarFile = File(modelsDir, ASR_TAR)

        // 如果之前下载过 tar 但没解压，检查完整性
        val existingSize = if (tarFile.exists()) tarFile.length() else 0L

        if (existingSize < ASR_SIZE * 0.99 || !isAsrModelReady()) {
            _downloadState.value = DownloadState(
                phase = Phase.DOWNLOADING, currentFile = "语音识别模型"
            )
            val ok = downloadFile(ASR_URLS, tarFile, ASR_SIZE, existingSize)
            if (!ok) return false
        }

        // 解压
        _downloadState.value = _downloadState.value.copy(
            phase = Phase.EXTRACTING, currentFile = "正在解压..."
        )
        return extractTarBz2(tarFile)
    }

    // ──── 通用文件下载（断点续传 + 镜像切换） ────

    private suspend fun downloadFile(
        urls: List<String>,
        file: File,
        expectedSize: Long,
        existingSize: Long
    ): Boolean {
        return withContext(Dispatchers.IO) {
            for ((idx, url) in urls.withIndex()) {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 60000

                    if (existingSize > 0) {
                        connection.setRequestProperty("Range", "bytes=$existingSize-")
                    }
                    connection.connect()

                    val totalSize = when (connection.responseCode) {
                        206 -> connection.getHeaderField("Content-Range")
                            ?.substringAfter("/")?.toLongOrNull() ?: expectedSize
                        else -> connection.contentLengthLong.coerceAtLeast(expectedSize)
                    }

                    val input = connection.inputStream ?: continue
                    val output = FileOutputStream(file, existingSize > 0)
                    val buffer = ByteArray(8192)
                    var downloaded = existingSize

                    _downloadState.value = _downloadState.value.copy(
                        phase = Phase.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = totalSize
                    )

                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        output.write(buffer, 0, n)
                        downloaded += n
                        _downloadState.value = _downloadState.value.copy(
                            downloadedBytes = downloaded,
                            progress = downloaded.toFloat() / totalSize
                        )
                    }
                    input.close()
                    output.close()

                    _downloadState.value = _downloadState.value.copy(phase = Phase.VERIFYING)
                    if (file.length() >= expectedSize * 0.99) return@withContext true

                    Log.w(TAG, "校验失败，尝试下一个镜像")
                } catch (e: Exception) {
                    Log.e(TAG, "下载失败: $url", e)
                }
            }
            _downloadState.value = DownloadState(
                phase = Phase.FAILED, error = "所有下载源均不可用，请检查网络"
            )
            return@withContext false
        }
    }

    // ──── tar.bz2 解压 ────

    private fun extractTarBz2(tarFile: File): Boolean {
        return try {
            // Android 无原生 tar.bz2 支持，走 shell 调用
            val targetDir = asrDir.absolutePath
            val cmd = "bunzip2 -c ${tarFile.absolutePath} | tar -xf - -C $targetDir --strip-components=1"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()

            if (exitCode == 0 && isAsrModelReady()) {
                tarFile.delete()  // 清理 tar 包节省空间
                true
            } else {
                Log.e(TAG, "解压失败 exitCode=$exitCode")
                _downloadState.value = DownloadState(
                    phase = Phase.FAILED,
                    error = "解压失败，请重试"
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压异常", e)
            _downloadState.value = DownloadState(
                phase = Phase.FAILED,
                error = "解压异常: ${e.localizedMessage}"
            )
            false
        }
    }

    /** 删除所有已下载模型 */
    fun deleteModels() {
        modelsDir.deleteRecursively()
        _downloadState.value = DownloadState(phase = Phase.IDLE)
    }
}
