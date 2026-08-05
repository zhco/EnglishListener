package com.englishlistener.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
        val rf = if (audioProcessor != null) {
            object : RenderersFactory {
                override fun createRenderers(eh: Handler, vl: Any, al: Any, to: Any, mo: Any): Array<Renderer> {
                    val sink = DefaultAudioSink.Builder(appContext).setAudioProcessors(audioProcessor).build()
                    return arrayOf(MediaCodecAudioRenderer(appContext, MediaCodecSelector.DEFAULT, eh, al as? androidx.media3.exoplayer.audio.AudioRendererEventListener?, sink))
                }
            }
        } else {
            DefaultRenderersFactory(appContext).apply { setEnableDecoderFallback(true) }
        }
        return ExoPlayer.Builder(appContext, rf).setMediaSourceFactory(DefaultMediaSourceFactory(appContext)).setHandleAudioBecomingNoisy(true).build()
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
                    else -> {}
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
                    else -> {}
                }
            }
            override fun onPlayerError(err: PlaybackException) { _ps.value = _ps.value.copy(isLoading = false, error = err.localizedMessage ?: "Playback error") }
        })
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
    }

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun release() { player.release() }
}