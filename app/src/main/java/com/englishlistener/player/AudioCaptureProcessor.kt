package com.englishlistener.player

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor : AudioProcessor {
    companion object {
        private const val TAG = "AudioCapture"
        private const val TARGET_SR = 16000
    }

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private var inputFormat: AudioFormat? = null
    private var outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var ended = false

    // AudioProcessor — pass-through, capture copy for ASR
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        inputFormat = inputAudioFormat
        Log.i(TAG, "configure sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} enc=${inputAudioFormat.encoding}")
        return inputAudioFormat
    }

    override fun isActive(): Boolean = listeners.isNotEmpty()

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (isActive()) {
            val copy = ByteArray(inputBuffer.remaining())
            inputBuffer.get(copy); inputBuffer.rewind()
            val fmt = inputFormat ?: return
            processBuffer(copy, fmt.sampleRate, fmt.channelCount)
        }
        // passthrough to speaker
        outputBuffer = ByteBuffer.allocateDirect(inputBuffer.remaining())
        outputBuffer.put(inputBuffer); outputBuffer.flip()
        inputBuffer.rewind()
    }

    override fun queueEndOfStream() { ended = true }
    override fun getOutput(): ByteBuffer { val b = outputBuffer; outputBuffer = ByteBuffer.allocateDirect(0); return b }
    override fun isEnded(): Boolean = ended
    override fun flush() { outputBuffer.clear() }
    override fun reset() { flush(); ended = false; inputFormat = null }

    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }

    private fun processBuffer(pcmBytes: ByteArray, sampleRate: Int, channels: Int) {
        val shorts = ShortArray(pcmBytes.size / 2)
        ByteBuffer.wrap(pcmBytes).asShortBuffer().get(shorts)
        val monoLen = if (channels >= 2) shorts.size / channels else shorts.size
        val mono = FloatArray(monoLen)
        if (channels >= 2) {
            for (i in 0 until monoLen) mono[i] = (shorts[i * channels] + shorts[i * channels + 1]) / 65536f
        } else {
            for (i in 0 until monoLen) mono[i] = shorts[i] / 32768f
        }
        val resampled = resampleLinear(mono, sampleRate, TARGET_SR)
        if (resampled.isNotEmpty()) { for (l in listeners) { try { l(resampled) } catch (_: Exception) {} } }
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toFloat() / dstRate.toFloat()
        val outCount = ((input.size - 1) / ratio).toInt()
        if (outCount <= 0) return FloatArray(0)
        val output = FloatArray(outCount)
        for (i in 0 until outCount) {
            val pos = i * ratio; val idx = pos.toInt(); val frac = pos - idx
            output[i] = if (idx + 1 < input.size) input[idx] * (1f - frac) + input[idx + 1] * frac else input[idx]
        }
        return output
    }
}
