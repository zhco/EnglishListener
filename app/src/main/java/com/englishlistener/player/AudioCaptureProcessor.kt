package com.englishlistener.player

import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor : BaseAudioProcessor() {
    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }

    override fun onConfigure(inputFormat: AudioFormat): AudioFormat = AudioFormat(16000, 1, 2)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pcm = FloatArray(inputBuffer.remaining() / 2)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { sb ->
            for (i in pcm.indices) pcm[i] = sb.get() / 32768f
        }
        for (l in listeners) try { l(pcm) } catch (_: Exception) {}
        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer); out.flip()
    }
}