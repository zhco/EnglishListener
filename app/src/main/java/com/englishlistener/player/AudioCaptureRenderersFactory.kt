package com.englishlistener.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener

class AudioCaptureRenderersFactory(
    context: Context,
    private val audioProcessor: AudioCaptureProcessor
) : DefaultRenderersFactory(context) {

    override fun createRenderers(
        eventHandler: Handler,
        videoListener: VideoRendererEventListener?,
        audioListener: AudioRendererEventListener?,
        textOutput: TextOutput?,
        metadataOutput: MetadataOutput?
    ): Array<Renderer> {
        val renderers = super.createRenderers(
            eventHandler, videoListener, audioListener,
            textOutput, metadataOutput
        )
        return renderers.map { renderer ->
            if (renderer is MediaCodecAudioRenderer) {
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(audioProcessor))
                    .build()
                MediaCodecAudioRenderer(
                    context, MediaCodecSelector.DEFAULT,
                    eventHandler, audioListener, sink
                )
            } else {
                renderer
            }
        }.toTypedArray()
    }
}
