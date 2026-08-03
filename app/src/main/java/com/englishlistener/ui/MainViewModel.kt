package com.englishlistener.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.englishlistener.data.*
import com.englishlistener.player.PlayerState
import com.englishlistener.player.RadioPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class AppScreen {
    SETUP,    // 首次启动：模型下载引导
    MAIN      // 主页：频道列表 + 播放
}

data class UiState(
    val screen: AppScreen = AppScreen.SETUP,
    val stations: List<RadioStation> = RadioStations.all,
    val currentStation: RadioStation? = null,
    val playerState: PlayerState = PlayerState(),
    val downloadState: DownloadState = DownloadState(),
    val subtitleEnabled: Boolean = false,
    val englishSubtitles: List<String> = emptyList(),
    val chineseSubtitles: List<String> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val player = RadioPlayer(application)
    val modelManager = ModelManager(application)

    init {
        // 检查模型是否已下载
        viewModelScope.launch {
            if (modelManager.areAllModelsReady()) {
                _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN)
            }
        }

        // 订阅播放状态
        viewModelScope.launch {
            player.playerState.collect { ps ->
                _uiState.value = _uiState.value.copy(playerState = ps)
            }
        }

        // 订阅下载状态
        viewModelScope.launch {
            modelManager.downloadState.collect { ds ->
                _uiState.value = _uiState.value.copy(downloadState = ds)
                // 下载完成 1 秒后自动跳转
                if (ds.phase == Phase.COMPLETED) {
                    delay(1200)
                    _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN)
                }
            }
        }
    }

    // ---- 播放 ----

    fun selectStation(station: RadioStation) {
        _uiState.value = _uiState.value.copy(currentStation = station)
        player.play(station.name, station.streamUrl)
    }

    fun togglePlayPause() {
        player.togglePlayPause()
    }

    // ---- 下载 ----

    fun startDownload() {
        viewModelScope.launch {
            modelManager.downloadAllModels()
        }
    }

    fun skipDownload() {
        _uiState.value = _uiState.value.copy(screen = AppScreen.MAIN)
    }

    // ----

    fun toggleSubtitle() {
        _uiState.value = _uiState.value.copy(
            subtitleEnabled = !_uiState.value.subtitleEnabled
        )
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}