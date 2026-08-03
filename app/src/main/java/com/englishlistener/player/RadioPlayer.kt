package com.englishlistener.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val isPlaying: Boolean = false,
    val stationName: String = "",
    val streamTitle: String = "",
    val error: String? = null
)

class RadioPlayer(context: Context) {
    companion object { private const val TAG = "RadioPlayer" }

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    var subtitleAudioProcessor: AudioProcessor? = null

    @OptIn(UnstableApi::class)
    private val player: ExoPlayer

    init {
        val rf = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context, rf).setMediaSourceFactory(DefaultMediaSourceFactory(context)).setHandleAudioBecomingNoisy(true).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _playerState.value = _playerState.value.copy(isPlaying = isPlaying) }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) _playerState.value = _playerState.value.copy(error = null)
            }
            override fun onPlayerError(err: PlaybackException) { _playerState.value = _playerState.value.copy(error = err.localizedMessage ?: "Playback error") }
        })
    }

    fun play(name: String, url: String) {
        _playerState.value = _playerState.value.copy(stationName = name, error = null)
        val builders = ArrayList<AudioProcessor>().apply { subtitleAudioProcessor?.let { add(it) } }
        val sink = DefaultAudioSink.Builder(context).setAudioProcessors(builders.toTypedArray()).build()
        player.setAudioSink(sink)
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
    }

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun release() { player.release() }
}