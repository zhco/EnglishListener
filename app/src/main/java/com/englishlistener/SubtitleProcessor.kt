package com.englishlistener

import android.util.Log
import com.englishlistener.asr.AsrEngine
import com.englishlistener.player.AudioCaptureProcessor
import com.englishlistener.translate.TranslationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class SubtitleLine(val english: String, val chinese: String = "")

class SubtitleProcessor(private val asrDir: File, private val translationModel: File) {
    companion object { private const val TAG = "SubtitleProcessor" }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var asr: AsrEngine? = null
    private var trans: TranslationEngine? = null
    val audioProcessor = AudioCaptureProcessor()
    private val _lines = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val lines: StateFlow<List<SubtitleLine>> = _lines
    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running
    private var audioCount = 0

    fun start(): Boolean {
        if (_running.value) return true
        _lines.value = listOf(SubtitleLine("正在初始化ASR模型...", asrDir.absolutePath))
        Log.d(TAG, "start: asrDir=${asrDir.absolutePath} exists=${asrDir.exists()} files=${asrDir.listFiles()?.map{it.name} ?: "null"}")
        Log.d(TAG, "start: transModel=${translationModel.absolutePath} exists=${translationModel.exists()} size=${translationModel.length()}")

        // check individual ASR files
        val asrFiles = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")
        val missing = asrFiles.filter { !File(asrDir, it).exists() }
        if (missing.isNotEmpty()) {
            val err = "ASR初始化失败: 缺少模型文件 ${missing.joinToString(", ")}"
            Log.e(TAG, err)
            _lines.value = listOf(SubtitleLine(err, "请确认模型已下载"), SubtitleLine("路径: ${asrDir.absolutePath}", ""))
            return false
        }

        asr = AsrEngine(asrDir)
        if (!asr!!.initialize()) {
            val err = "ASR初始化失败: 模型加载异常"
            Log.e(TAG, err)
            _lines.value = listOf(SubtitleLine(err, "请确认模型文件完整"), SubtitleLine("路径: ${asrDir.absolutePath}", ""))
            asr = null
            return false
        }
        _lines.value = listOf(SubtitleLine("ASR就绪，正在加载翻译模型...", ""))
        Log.d(TAG, "ASR OK, loading translation from ${translationModel.absolutePath}")

        if (!translationModel.exists() || translationModel.length() < 100_000_000) {
            val err = "翻译模型未下载: ${translationModel.name} (${translationModel.length()} bytes)"
            Log.e(TAG, err)
            _lines.value = listOf(SubtitleLine(err, "请确认模型已下载"), SubtitleLine("路径: ${translationModel.absolutePath}", ""))
            asr?.release(); asr = null
            return false
        }

        trans = TranslationEngine(translationModel)
        if (!trans!!.initialize()) {
            val err = "翻译模型加载失败: 文件可能损坏"
            Log.e(TAG, err)
            _lines.value = listOf(SubtitleLine(err, "路径: ${translationModel.absolutePath}"))
            asr?.release(); asr = null
            trans = null
            return false
        }
        Log.d(TAG, "Translation OK, adding audio listener")
        _lines.value = listOf(SubtitleLine("模型就绪，等待音频...", "请确保已选择电台"))
        trans?.onTranslation = { eng, ch ->
            Log.d(TAG, "translation: $eng -> $ch")
            val cur = _lines.value.toMutableList()
            val idx = cur.indexOfFirst { it.english == eng }
            if (idx >= 0) cur[idx] = cur[idx].copy(chinese = ch)
            else cur.add(SubtitleLine(eng, ch))
            _lines.value = cur
        }
        audioCount = 0
        audioProcessor.addListener(::onAudio)
        _running.value = true
        Log.d(TAG, "start: running=true")
        return true
    }

    private fun onAudio(samples: FloatArray) {
        audioCount++
        if (audioCount == 1) Log.d(TAG, "onAudio #1: ${samples.size} samples")
        if (audioCount <= 3 || audioCount % 100 == 0) Log.d(TAG, "onAudio #$audioCount: ${samples.size} samples")
        val r = asr?.processSamples(samples) ?: return
        if (r.isNotBlank()) {
            Log.d(TAG, "ASR result: $r")
            val cur = _lines.value.toMutableList()
            if (cur.none { it.english == r }) { cur.add(SubtitleLine(r)); _lines.value = cur; trans?.submit(r) }
        }
    }

    fun stop() {
        Log.d(TAG, "stop")
        _running.value = false
        audioProcessor.removeListener(::onAudio)
        asr?.release(); trans?.release()
        asr = null; trans = null
    }

    fun destroy() { stop(); scope.cancel(); trans?.destroy() }
}
