package com.englishlistener.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.AudioSink
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink

class CaptureRenderersFactory(
    context: Context,
    private val captureProcessor: AudioProcessor
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(object : AudioProcessorChain {
                override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(captureProcessor)
                override fun getMediaDurationUs(): Long = C.TIME_UNSET
                override fun getSkippedOutputFrameCount(): Int = 0
                override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters = playbackParameters
                override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = skipSilenceEnabled
            })
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
