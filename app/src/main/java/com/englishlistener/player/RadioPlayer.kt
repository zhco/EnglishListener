package com.englishlistener.player

import android.content.Context
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
    val isPlaying: Boolean = false, val stationName: String = "",
    val streamTitle: String = "", val error: String? = null
)

class RadioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val _ps = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _ps.asStateFlow()
    var subtitleAudioProcessor: AudioProcessor? = null

    @OptIn(UnstableApi::class)
    private val player: ExoPlayer

    init {
        val rf = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(appContext, rf).setMediaSourceFactory(DefaultMediaSourceFactory(appContext)).setHandleAudioBecomingNoisy(true).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _ps.value = _ps.value.copy(isPlaying = isPlaying) }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) _ps.value = _ps.value.copy(error = null) }
            override fun onPlayerError(err: PlaybackException) { _ps.value = _ps.value.copy(error = err.localizedMessage ?: "Playback error") }
        })
    }

    @OptIn(UnstableApi::class)
    fun play(name: String, url: String) {
        _ps.value = _ps.value.copy(stationName = name, error = null)
        val procs = ArrayList<AudioProcessor>()
        subtitleAudioProcessor?.let { procs.add(it) }
        player.setAudioSink(DefaultAudioSink.Builder(appContext).setAudioProcessors(procs.toTypedArray()).build())
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
    }

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun release() { player.release() }
}