package com.englishlistener.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val stationName: String = "",
    val streamTitle: String = "",
    val error: String? = null
)

class RadioPlayer(context: Context, private val audioProcessor: AudioCaptureProcessor? = null) {
    private val appContext = context.applicationContext
    private val _ps = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _ps.asStateFlow()

    private fun buildPlayer(): ExoPlayer {
        val ap = audioProcessor
        return if (ap != null) {
            val factory = AudioCaptureRenderersFactory(appContext, ap)
            ExoPlayer.Builder(appContext, factory)
        } else {
            ExoPlayer.Builder(appContext)
        }.setHandleAudioBecomingNoisy(true).build()
    }

    private var player: ExoPlayer = buildPlayer()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _ps.value = _ps.value.copy(isPlaying = isPlaying) }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> _ps.value = _ps.value.copy(isLoading = true)
                    Player.STATE_READY -> _ps.value = _ps.value.copy(isLoading = false, error = null)
                    Player.STATE_IDLE -> _ps.value = _ps.value.copy(isLoading = false)
                }
            }
            override fun onPlayerError(err: PlaybackException) { _ps.value = _ps.value.copy(isLoading = false, error = err.localizedMessage ?: "Playback error") }
        })
    }

    fun play(name: String, url: String) {
        _ps.value = _ps.value.copy(stationName = name, isLoading = true, error = null)
        player.stop(); player.release(); player = buildPlayer()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _ps.value = _ps.value.copy(isPlaying = isPlaying) }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> _ps.value = _ps.value.copy(isLoading = true)
                    Player.STATE_READY -> _ps.value = _ps.value.copy(isLoading = false, error = null)
                    Player.STATE_IDLE -> _ps.value = _ps.value.copy(isLoading = false)
                }
            }
            override fun onPlayerError(err: PlaybackException) { _ps.value = _ps.value.copy(isLoading = false, error = err.localizedMessage ?: "Playback error") }
        })
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
    }

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun release() { player.release() }
}