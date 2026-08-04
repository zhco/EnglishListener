package com.englishlistener.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englishlistener.SubtitleProcessor
import com.englishlistener.data.*
import com.englishlistener.player.PlayerState
import com.englishlistener.player.RadioPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppScreen { SETUP, MAIN }

data class UiState(
    val screen: AppScreen = AppScreen.SETUP,
    val stations: List<RadioStation> = RadioStations.all,
    val currentStation: RadioStation? = null,
    val playerState: PlayerState = PlayerState(),
    val downloadState: DownloadState = DownloadState(),
    val subtitleEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val englishSubtitles: List<String> = emptyList(),
    val chineseSubtitles: List<String> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val player = RadioPlayer(application)
    val modelManager = ModelManager(application)
    val subtitleProcessor = SubtitleProcessor(modelManager.asrDir, modelManager.translationModelFile)

    init {
        viewModelScope.launch { if (modelManager.areAllModelsReady()) { _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN, isLoading = true) } }
        viewModelScope.launch { player.playerState.collect { ps -> _uiState.value = _uiState.value.copy(playerState = ps) } }
        viewModelScope.launch {
            modelManager.downloadState.collect { ds ->
                _uiState.value = _uiState.value.copy(downloadState = ds, isLoading = ds.phase != Phase.COMPLETED)
                if (ds.phase == Phase.COMPLETED && _uiState.value.screen == AppScreen.SETUP) { delay(1200); _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN, isLoading = false) }
            }
        }
        viewModelScope.launch {
            subtitleProcessor.lines.collect { lines ->
                _uiState.value = _uiState.value.copy(englishSubtitles = lines.map { it.english }, chineseSubtitles = lines.map { it.chinese })
            }
        }
    }

    fun selectStation(station: RadioStation) {
        _uiState.value = _uiState.value.copy(currentStation = station, isLoading = true)
        player.subtitleAudioProcessor = if (_uiState.value.subtitleEnabled) subtitleProcessor.audioProcessor else null
        player.play(station.name, station.streamUrl); _uiState.value = _uiState.value.copy(isLoading = false)
    }

    fun togglePlayPause() { player.togglePlayPause() }
    fun startDownload() { _uiState.value = _uiState.value.copy(isLoading = true); viewModelScope.launch { modelManager.downloadAllModels() } }
    fun skipDownload() { _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN) }

    fun toggleSubtitle() {
        val enabled = !_uiState.value.subtitleEnabled
        _uiState.value = _uiState.value.copy(subtitleEnabled = enabled, isLoading = true)
        if (enabled) {
            val ok = subtitleProcessor.start()
            if (!ok) { _uiState.value = _uiState.value.copy(subtitleEnabled = false, isLoading = false); return }
            player.subtitleAudioProcessor = subtitleProcessor.audioProcessor
        } else { subtitleProcessor.stop(); player.subtitleAudioProcessor = null }
        _uiState.value = _uiState.value.copy(isLoading = false)
        if (_uiState.value.currentStation != null && _uiState.value.playerState.isPlaying) {
            val s = _uiState.value.currentStation!!
            player.play(s.name, s.streamUrl)
        }
    }

    override fun onCleared() { super.onCleared(); subtitleProcessor.stop(); player.release() }
}