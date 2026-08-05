package com.englishlistener.player

import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor : AudioProcessor {
    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private var inputFormat: AudioProcessor.AudioFormat? = null
    private var outBuf = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var leftover = FloatArray(0)

    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }
    override fun isActive(): Boolean = listeners.isNotEmpty()
    override fun getOutput(): ByteBuffer = outBuf
    override fun isEnded(): Boolean = false
    override fun queueEndOfStream() {}
    override fun flush() { leftover = FloatArray(0) }
    override fun reset() { flush(); outBuf.clear() }

    override fun configure(fmt: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = fmt; return fmt
    }

    override fun queueInput(inputBuf: ByteBuffer) {
        val size = inputBuf.remaining()
        if (size == 0 || listeners.isEmpty()) return
        if (outBuf.capacity() < size) outBuf = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        outBuf.clear(); outBuf.put(inputBuf.duplicate()); outBuf.flip()
        val fmt = inputFormat ?: return
        val sr = fmt.sampleRate; val ch = fmt.channelCount
        val shorts = ShortArray(size / 2)
        outBuf.duplicate().asShortBuffer().get(shorts)
        val monoLen = if (ch >= 2) size / 2 / ch else size / 2
        val mono = FloatArray(monoLen)
        if (ch >= 2) { for (i in 0 until monoLen) mono[i] = (shorts[i*ch].toInt()+shorts[i*ch+1].toInt())/65536f }
        else { for (i in 0 until monoLen) mono[i] = shorts[i].toInt()/32768f }
        val combined = leftover + mono
        val (resampled, rem) = resampleLinear(combined, sr, 16000)
        leftover = rem
        if (resampled.isNotEmpty()) { for (l in listeners) { try { l(resampled) } catch (_: Exception) {} } }
    }

    private fun resampleLinear(input: FloatArray, src: Int, dst: Int): Pair<FloatArray, FloatArray> {
        if (src == dst) return Pair(input, FloatArray(0))
        val r = src.toFloat()/dst; val n = ((input.size-1)/r).toInt()
        if (n <= 0) return Pair(FloatArray(0), input)
        val o = FloatArray(n)
        for (i in 0 until n) { val p=i*r; val j=p.toInt(); val f=p-j; o[i]=if(j+1<input.size)input[j]*(1f-f)+input[j+1]*f else input[j] }
        val c = (n*r).toInt()
        return Pair(o, if(c<input.size) input.copyOfRange(c, input.size) else FloatArray(0))
    }
}
