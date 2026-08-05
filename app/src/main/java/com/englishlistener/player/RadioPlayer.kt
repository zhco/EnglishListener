package com.englishlistener.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

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
        val factory = if (audioProcessor != null) {
            RenderersFactory { handler, _, audioListener, _, _ ->
                val chain = object : AudioProcessorChain {
                    override fun apply(input: AudioProcessor): AudioProcessor {
                        return object : AudioProcessor {
                            override fun configure(f: AudioProcessor.AudioFormat) = f
                            override fun isActive() = false
                            override fun queueInput(b: ByteBuffer) {}
                            override fun queueEndOfStream() {}
                            override fun getOutput() = ByteBuffer.allocateDirect(0)
                            override fun isEnded() = true
                            override fun flush() {}
                            override fun reset() {}
                        }
                    }
                    override fun apply(skip: Boolean, procs: Array<out AudioProcessor>): Array<AudioProcessor> {
                        return (procs.toList() + audioProcessor!!).toTypedArray()
                    }
                    override fun getMediaDuration(speed: Float): Long = -1
                }
                val sink = DefaultAudioSink.Builder(appContext).setAudioProcessorChain(chain).build()
                arrayOf(MediaCodecAudioRenderer(appContext, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
            }
        } else null
        return if (factory != null) ExoPlayer.Builder(appContext, factory).setHandleAudioBecomingNoisy(true).build()
        else ExoPlayer.Builder(appContext).setHandleAudioBecomingNoisy(true).build()
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