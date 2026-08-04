package com.englishlistener.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor : BaseAudioProcessor() {
    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return AudioProcessor.AudioFormat(16000, 1, 2)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val count = inputBuffer.remaining() / 2
        val pcm = FloatArray(count)
        val sb = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until count) pcm[i] = sb.get() / 32768f
        for (l in listeners) try { l(pcm) } catch (_: Exception) {}
        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }
}