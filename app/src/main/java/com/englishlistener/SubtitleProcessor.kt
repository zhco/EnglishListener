package com.englishlistener

import android.util.Log
import com.englishlistener.asr.AsrEngine
import com.englishlistener.asr.AsrResult
import com.englishlistener.player.AudioCaptureProcessor
import com.englishlistener.translate.TranslationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.min

data class SubtitleLine(val english: String, val chinese: String = "")

class SubtitleProcessor(private val asrDir: File, private val translationModel: File) {
    companion object { private const val TAG = "SubtitleProcessor" }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var asr: AsrEngine? = null
    private var trans: TranslationEngine? = null
    val audioProcessor = AudioCaptureProcessor()
    private val _lines = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val lines: StateFlow<List<SubtitleLine>> = _lines
    private val _captureStatus = MutableStateFlow("idle")
    val captureStatus: StateFlow<String> = _captureStatus
    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running
    private var audioCount = 0

    fun start(): Boolean {
        if (_running.value) return true
        _lines.value = listOf(SubtitleLine("正在初始化ASR模型...", asrDir.absolutePath))
        Log.d(TAG, "start: asrDir=${asrDir.absolutePath} exists=${asrDir.exists()}")

        asr = AsrEngine(asrDir)
        val err = asr!!.initialize()
        if (err != null) {
            Log.e(TAG, "ASR init error: $err")
            _lines.value = listOf(SubtitleLine("ASR初始化失败: $err", "路径: ${asrDir.absolutePath}"))
            asr = null
            return false
        }

        _lines.value = listOf(SubtitleLine("ASR就绪，正在加载翻译模型...", ""))
        Log.d(TAG, "ASR OK, loading translation from ${translationModel.absolutePath}")

        if (!translationModel.exists() || translationModel.length() < 100_000_000) {
            val errMsg = "翻译模型未下载: ${translationModel.name} (${translationModel.length()} bytes)"
            Log.e(TAG, errMsg)
            _lines.value = listOf(SubtitleLine(errMsg, "路径: ${translationModel.absolutePath}"))
            asr?.release(); asr = null
            return false
        }

        trans = TranslationEngine(translationModel)
        if (!trans!!.initialize()) {
            Log.e(TAG, "Translation init failed, continuing ASR-only")
            trans = null
        }

        val initMsg = if (trans != null) "模型就绪（含翻译），等待音频..." else "模型就绪（仅ASR），等待音频..."
        Log.d(TAG, initMsg)
        _lines.value = listOf(SubtitleLine(initMsg))
        trans?.onTranslation = { eng, ch ->
            val cur = _lines.value.toMutableList()
            val idx = cur.indexOfFirst { it.english == eng }
            if (idx >= 0) cur[idx] = cur[idx].copy(chinese = ch)
            else cur.add(SubtitleLine(eng, ch))
            _lines.value = cur
        }
        audioCount = 0
        audioProcessor.addListener(::onAudio)
        audioProcessor.addStatusListener(::onCaptureStatus)
        _running.value = true
        return true
    }

    private fun onCaptureStatus(status: String) {
        Log.d(TAG, "captureStatus: $status")
        _captureStatus.value = status
    }

    private fun onAudio(samples: FloatArray) {
        audioCount++
        if (audioCount % 100 == 0) Log.d(TAG, "onAudio #$audioCount")
        val result = asr?.processSamples(samples) ?: return
        val text = result.text
        if (text.isBlank()) return

        val cur = _lines.value.toMutableList()
        val realStart = cur.indexOfLast {
            it.english.startsWith("ASR") || it.english.startsWith("模型") || it.english.startsWith("翻译")
        }
        val lastIdx = cur.size - 1
        val lastReal = if (lastIdx > realStart) lastIdx else -1

        if (lastReal >= 0) {
            val prev = cur[lastReal].english
            val prefLen = min(prev.length, 8)
            val isExtension = (prev.isNotEmpty() && text.startsWith(prev)) ||
                (text.length > prev.length && prefLen > 0 && text.contains(prev.substring(0, prefLen)))
            if (isExtension) {
                cur[lastReal] = SubtitleLine(text)
                _lines.value = cur
                trans?.submit(text)
                return
            }
        }

        if (cur.none { it.english == text }) {
            cur.add(SubtitleLine(text))
            _lines.value = cur
            trans?.submit(text)
        }
    }

    fun stop() {
        _running.value = false
        audioProcessor.removeListener(::onAudio)
        audioProcessor.removeStatusListener(::onCaptureStatus)
        _captureStatus.value = "stopped"
        asr?.release(); trans?.release()
        asr = null; trans = null
    }

    fun destroy() { stop(); scope.cancel(); trans?.destroy() }
}
