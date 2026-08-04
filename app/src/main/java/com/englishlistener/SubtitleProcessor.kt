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

    fun start(streamUrl: String?): Boolean {
        if (_running.value) return true
        asr = AsrEngine(asrDir).also { if (!it.initialize()) { stop(); return false } }
        trans = TranslationEngine(translationModel).also { if (!it.initialize()) { stop(); return false } }
        trans?.onTranslation = { eng, ch ->
            val cur = _lines.value.toMutableList()
            val idx = cur.indexOfFirst { it.english == eng }
            if (idx >= 0) cur[idx] = cur[idx].copy(chinese = ch)
            else cur.add(SubtitleLine(eng, ch))
            _lines.value = cur
        }
        audioProcessor.addListener(::onAudio)
        if (streamUrl != null) audioProcessor.start(streamUrl)
        _running.value = true; return true
    }

    private fun onAudio(samples: FloatArray) {
        val r = asr?.processSamples(samples) ?: return
        if (r.isNotBlank()) {
            val cur = _lines.value.toMutableList()
            if (cur.none { it.english == r }) { cur.add(SubtitleLine(r)); _lines.value = cur; trans?.submit(r) }
        }
    }

    fun stop() {
        _running.value = false
        audioProcessor.stop(); audioProcessor.removeListener(::onAudio)
        asr?.release(); trans?.release()
        asr = null; trans = null
    }

    fun destroy() { stop(); scope.cancel(); trans?.destroy() }
}