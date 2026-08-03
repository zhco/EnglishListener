package com.englishlistener.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放器状态
 */
data class PlayerState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val stationName: String = "",
    val error: String? = null
)

/**
 * 电台播放器封装
 * 管理 ExoPlayer 实例，对外暴露 StateFlow 供 Compose 订阅
 */
class RadioPlayer(private val context: Context) {

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setLiveMinSpeed(1.0f)
        )
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _playerState.value = _playerState.value.copy(
                        isPlaying = playbackState == Player.STATE_READY && playWhenReady,
                        isLoading = playbackState == Player.STATE_BUFFERING
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _playerState.value = _playerState.value.copy(
                        error = error.localizedMessage ?: "播放出错"
                    )
                }
            })
        }

    fun play(stationName: String, streamUrl: String) {
        _playerState.value = PlayerState(
            isLoading = true,
            stationName = stationName
        )
        exoPlayer.apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }

    fun togglePlayPause() {
        val playing = exoPlayer.playWhenReady
        if (playing) exoPlayer.pause() else exoPlayer.play()
        exoPlayer.playWhenReady = !playing
    }

    fun release() {
        exoPlayer.release()
    }
}
