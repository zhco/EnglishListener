package com.englishlistener.ui

import android.app.Application
import android.media.projection.MediaProjection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englishlistener.SubtitleProcessor
import com.englishlistener.data.*
import com.englishlistener.player.PlayerState
import com.englishlistener.player.RadioPlayer
import com.englishlistener.player.SystemAudioCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppScreen { SETUP, MAIN }
enum class CaptureMode { STREAM, SYSTEM }

data class UiState(
    val screen: AppScreen = AppScreen.SETUP,
    val stations: List<RadioStation> = RadioStations.all,
    val currentStation: RadioStation? = null,
    val playerState: PlayerState = PlayerState(),
    val downloadState: DownloadState = DownloadState(),
    val subtitleEnabled: Boolean = false,
    val subtitleStatus: String = "",
    val captureStatus: String = "",
    val captureMode: CaptureMode = CaptureMode.STREAM,
    val isLoading: Boolean = false,
    val englishSubtitles: List<String> = emptyList(),
    val chineseSubtitles: List<String> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val modelManager = ModelManager(application)
    val subtitleProcessor = SubtitleProcessor(modelManager.asrDir, modelManager.translationModelFile)
    val player = RadioPlayer(application)
    private val systemCapture = SystemAudioCapture()

    init {
        viewModelScope.launch { if (modelManager.areAllModelsReady()) { _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN, isLoading = true) } }
        viewModelScope.launch { player.playerState.collect { ps -> _uiState.value = _uiState.value.copy(playerState = ps) } }
        viewModelScope.launch { modelManager.downloadState.collect { ds -> _uiState.value = _uiState.value.copy(downloadState = ds, isLoading = ds.phase != Phase.COMPLETED); if (ds.phase == Phase.COMPLETED && _uiState.value.screen == AppScreen.SETUP) { delay(1200); _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN, isLoading = false) } } }
        viewModelScope.launch { subtitleProcessor.lines.collect { lines -> _uiState.value = _uiState.value.copy(englishSubtitles = lines.map { it.english }, chineseSubtitles = lines.map { it.chinese }) } }
        viewModelScope.launch { subtitleProcessor.captureStatus.collect { s -> _uiState.value = _uiState.value.copy(captureStatus = s) } }
    }

    fun selectStation(station: RadioStation) { _uiState.value = _uiState.value.copy(currentStation = station, isLoading = true); player.play(station.name, station.streamUrl); _uiState.value = _uiState.value.copy(isLoading = false) }
    fun togglePlayPause() { player.togglePlayPause() }
    fun startDownload() { _uiState.value = _uiState.value.copy(isLoading = true); viewModelScope.launch { modelManager.downloadAllModels() } }
    fun skipDownload() { _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN) }

    fun toggleSubtitle() {
        val enabled = !_uiState.value.subtitleEnabled
        _uiState.value = _uiState.value.copy(subtitleEnabled = enabled, isLoading = true, subtitleStatus = if (enabled) "启动中..." else "")
        if (enabled) {
            val ok = subtitleProcessor.start()
            if (!ok) { _uiState.value = _uiState.value.copy(subtitleEnabled = false, isLoading = false); return }
            val mode = _uiState.value.captureMode
            if (mode == CaptureMode.STREAM) {
                val station = _uiState.value.currentStation
                if (station != null) {
                    _uiState.value = _uiState.value.copy(subtitleStatus = "连接电台: ${station.name}")
                    subtitleProcessor.startStreamCapture(station.streamUrl)
                } else {
                    _uiState.value = _uiState.value.copy(subtitleStatus = "请先选择电台")
                }
            }
            // SYSTEM mode: wait for startSystemCapture() to be called after MediaProjection grant
        } else {
            systemCapture.stop()
            subtitleProcessor.stop()
        }
        _uiState.value = _uiState.value.copy(isLoading = false)
    }

    fun startSystemCapture(mp: MediaProjection) {
        _uiState.value = _uiState.value.copy(captureMode = CaptureMode.SYSTEM, subtitleStatus = "系统音频捕获中...")
        if (!_uiState.value.subtitleEnabled) {
            toggleSubtitle()
        }
        subtitleProcessor.startSystemCapture(systemCapture)
        systemCapture.start(mp)
    }

    fun onSystemCaptureDenied() {
        _uiState.value = _uiState.value.copy(captureMode = CaptureMode.STREAM, subtitleStatus = "已取消系统捕获，请使用电台流模式")
    }

    override fun onCleared() { super.onCleared(); systemCapture.stop(); subtitleProcessor.stop(); player.release() }
}
