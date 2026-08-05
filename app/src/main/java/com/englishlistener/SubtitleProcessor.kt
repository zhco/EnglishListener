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
        _lines.value = listOf(SubtitleLine("正在初始化ASR模型...", ""))
        Log.d(TAG, "start: initializing ASR from $asrDir exists=${asrDir.exists()}...")
        asr = AsrEngine(asrDir)
        if (!asr!!.initialize()) {
            val err = "ASR初始化失败: 模型文件可能未下载或损坏"
            Log.e(TAG, err)
            _lines.value = listOf(SubtitleLine(err, "请确认模型已下载"))
            asr = null
            return false
        }
        _lines.value = listOf(SubtitleLine("ASR就绪，正在加载翻译模型...", ""))
        Log.d(TAG, "ASR initialized OK, initializing translation from $translationModel exists=${translationModel.exists()}...")
        trans = TranslationEngine(translationModel)
        if (!trans!!.initialize()) {
            val err = "翻译模型加载失败: 文件可能未下载或损坏"
            Log.e(TAG, err)
            _lines.value = listOf(SubtitleLine(err, "请确认模型已下载"))
            asr?.release()
            asr = null
            trans = null
            return false
        }
        Log.d(TAG, "Translation initialized OK, adding audio listener")
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
        Log.d(TAG, "start: running=true, listener registered")
        return true
    }

    private fun onAudio(samples: FloatArray) {
        audioCount++
        if (audioCount == 1) {
            Log.d(TAG, "onAudio #1: ${samples.size} samples - AUDIO FLOWING!")
        }
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