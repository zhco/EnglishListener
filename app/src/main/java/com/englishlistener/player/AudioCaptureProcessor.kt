package com.englishlistener.player

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor : AudioProcessor {
    companion object { private const val TAG = "AudioCapture" }
    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private var inputFormat: AudioProcessor.AudioFormat? = null
    private var outBuf = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var leftover = FloatArray(0)
    private var callCount = 0
    private var fwdCount = 0L

    fun addListener(l: (FloatArray) -> Unit) {
        listeners.add(l)
        Log.d(TAG, "listener +, total=${listeners.size}")
    }
    fun removeListener(l: (FloatArray) -> Unit) {
        listeners.remove(l)
        Log.d(TAG, "listener -, total=${listeners.size}")
    }

    // Always true - audio passes through even without listeners
    override fun isActive(): Boolean = true
    override fun getOutput(): ByteBuffer = outBuf
    override fun isEnded(): Boolean = false
    override fun queueEndOfStream() { Log.d(TAG, "EOS") }
    override fun flush() { leftover = FloatArray(0) }
    override fun reset() {
        flush(); outBuf.clear()
        callCount = 0; fwdCount = 0
        Log.d(TAG, "reset")
    }

    override fun configure(fmt: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        Log.d(TAG, "cfg: sr=${fmt.sampleRate} ch=${fmt.channelCount} enc=${fmt.encoding}")
        inputFormat = fmt; return fmt
    }

    override fun queueInput(inputBuf: ByteBuffer) {
        val size = inputBuf.remaining()
        callCount++
        if (callCount <= 3 || callCount % 100 == 0) {
            Log.d(TAG, "in #$callCount ${size}B listen=${listeners.size}")
        }
        if (size == 0) return
        if (outBuf.capacity() < size) outBuf = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        outBuf.clear(); outBuf.put(inputBuf.duplicate()); outBuf.flip()

        val fmt = inputFormat ?: return
        if (listeners.isEmpty()) return

        val sr = fmt.sampleRate; val ch = fmt.channelCount
        val shorts = ShortArray(size / 2)
        outBuf.duplicate().asShortBuffer().get(shorts)
        val monoLen = if (ch >= 2) size / 2 / ch else size / 2
        val mono = FloatArray(monoLen)
        if (ch >= 2) {
            for (i in 0 until monoLen) mono[i] = (shorts[i*ch].toInt()+shorts[i*ch+1].toInt())/65536f
        } else {
            for (i in 0 until monoLen) mono[i] = shorts[i].toInt()/32768f
        }
        val combined = leftover + mono
        val (resampled, rem) = resampleLinear(combined, sr, 16000)
        leftover = rem
        if (resampled.isNotEmpty()) {
            fwdCount += resampled.size
            if (callCount % 100 == 0) Log.d(TAG, "fwd ${resampled.size}smp total=$fwdCount")
            for (l in listeners) {
                try { l(resampled) } catch (e: Exception) { Log.e(TAG, "l err", e) }
            }
        }
    }

    private fun resampleLinear(input: FloatArray, src: Int, dst: Int): Pair<FloatArray, FloatArray> {
        if (src == dst) return Pair(input, FloatArray(0))
        val r = src.toFloat()/dst
        val n = ((input.size-1)/r).toInt()
        if (n <= 0) return Pair(FloatArray(0), input)
        val o = FloatArray(n)
        for (i in 0 until n) {
            val p = i*r; val j = p.toInt(); val f = p-j
            o[i] = if (j+1 < input.size) input[j]*(1f-f) + input[j+1]*f else input[j]
        }
        val c = (n*r).toInt()
        return Pair(o, if (c < input.size) input.copyOfRange(c, input.size) else FloatArray(0))
    }
}