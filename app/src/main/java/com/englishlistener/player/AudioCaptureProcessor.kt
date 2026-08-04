package com.englishlistener.player

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor : AudioProcessor {
    companion object { private const val TAG = "AudioCapture" }

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private var inputFormat: AudioFormat? = null
    private var active = false

    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }

    fun setActive(a: Boolean) { active = a }

    override fun configure(inputFormat: AudioFormat): AudioFormat {
        this.inputFormat = inputFormat
        Log.i(TAG, "configure: sr=${inputFormat.sampleRate} ch=${inputFormat.channelCount} enc=${inputFormat.encoding}")
        // Force 16-bit integer output, keep sample rate and channels as is
        return AudioFormat(inputFormat.sampleRate, inputFormat.channelCount, AudioFormat.ENCODING_PCM_16BIT)
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active || listeners.isEmpty() || inputFormat == null) return
        val fmt = inputFormat ?: return
        val sr = fmt.sampleRate
        val ch = fmt.channelCount

        // Read 16-bit PCM samples
        val shortCount = inputBuffer.remaining() / 2
        if (shortCount == 0) return
        val shorts = ShortArray(shortCount)
        val buf16 = inputBuffer.asShortBuffer()
        buf16.get(shorts)

        // Mix down to mono and convert to float
        val frameCount = shortCount / ch
        val mono = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until ch) sum += shorts[i * ch + c] / 32768f
            mono[i] = sum / ch
        }

        // Dispatch to listeners
        for (l in listeners) {
            try { l(mono) } catch (_: Exception) {}
        }
    }

    override fun queueEndOfStream() {}
    override fun flush() {}
    override fun reset() {}
}
