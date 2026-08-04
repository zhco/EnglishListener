package com.englishlistener.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

class AudioCaptureProcessor {
    companion object { private const val TAG = "AudioCapture"; private const val TARGET_SR = 16000 }

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }

    fun start(streamUrl: String) {
        stop()
        job = scope.launch {
            try {
                val conn = URL(streamUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "EnglishListener/1.0")
                conn.setRequestProperty("Icy-MetaData", "1")
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) { Log.e(TAG, "HTTP ${conn.responseCode}"); return@launch }

                val icyMetaInt = conn.getHeaderField("Icy-MetaInt")?.toIntOrNull() ?: 0
                val input = BufferedInputStream(conn.inputStream)
                var codec: MediaCodec? = null
                val buf = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0
                val initialChunk = ByteArray(524288)
                var initLen = 0

                // Read initial chunk to detect format
                while (isActive && initLen < 524288) {
                    bytesRead = input.read(buf)
                    if (bytesRead <= 0) break
                    // Skip ICY metadata
                    if (icyMetaInt > 0 && totalRead > 0 && totalRead % (8192 * 16) == 0) {
                        val metaLen = input.read() * 16
                        if (metaLen > 0) { val skip = ByteArray(metaLen); input.read(skip) }
                    }
                    System.arraycopy(buf, 0, initialChunk, initLen, bytesRead)
                    initLen += bytesRead; totalRead += bytesRead
                }

                if (initLen == 0) { input.close(); conn.disconnect(); return@launch }

                // Detect codec from stream data
                val mime = detectMime(initialChunk, initLen)
                Log.i(TAG, "Detected: $mime")

                // Try to configure decoder
                codec = try {
                    val c = MediaCodec.createDecoderByType(mime)
                    c.configure(MediaFormat.createAudioFormat(mime, 44100, 2), null, null, 0)
                    c.start(); c
                } catch (e: Exception) {
                    Log.e(TAG, "Codec config failed for $mime", e)
                    try { codec?.release() } catch (_: Exception) {}
                    input.close(); return@launch
                }

                // Feed initial chunk
                feedDecoder(codec, ByteBuffer.wrap(initialChunk, 0, initLen), 0)
                val info = MediaCodec.BufferInfo()

                // Keep feeding and reading output
                while (isActive) {
                    // Feed more data
                    bytesRead = input.read(buf)
                    if (bytesRead <= 0) break
                    totalRead += bytesRead
                    feedDecoder(codec, ByteBuffer.wrap(buf, 0, bytesRead), 0)

                    // Read decoded output
                    var outIdx = codec.dequeueOutputBuffer(info, 5000)
                    while (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        processPcm(outBuf, info) { floats -> for (l in listeners) try { l(floats) } catch (_: Exception) {} }
                        codec.releaseOutputBuffer(outIdx, false)
                        outIdx = codec.dequeueOutputBuffer(info, 0)
                    }
                }

                // Flush remaining
                feedDecoder(codec, ByteBuffer.allocate(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                var outIdx = codec.dequeueOutputBuffer(info, 10000)
                while (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    processPcm(outBuf, info) { floats -> for (l in listeners) try { l(floats) } catch (_: Exception) {} }
                    codec.releaseOutputBuffer(outIdx, false)
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }

                codec.stop(); codec.release(); input.close(); conn.disconnect()
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "capture err", e)
            }
        }
    }

    private fun detectMime(data: ByteArray, len: Int): String {
        // MP3 sync: 0xFF 0xFB/E/F2/F3/F4/F5/F6/F7/FA
        if (len >= 2) {
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return "audio/mpeg"
        }
        // ADTS AAC: 0xFF 0xF1/F9
        if (len >= 2) {
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 == 0xF1.toInt() || b1 == 0xF9.toInt())) return "audio/mp4a-latm"
        }
        // Default to MP3
        return "audio/mpeg"
    }

    private fun feedDecoder(codec: MediaCodec, data: ByteBuffer, flags: Int) {
        var inIdx = codec.dequeueInputBuffer(10000)
        while (inIdx >= 0 && data.hasRemaining()) {
            val inBuf = codec.getInputBuffer(inIdx)!!
            val toCopy = minOf(inBuf.remaining(), data.remaining())
            if (toCopy > 0) {
                val slice = data.slice()
                slice.limit(toCopy)
                inBuf.put(slice)
                data.position(data.position() + toCopy)
            }
            codec.queueInputBuffer(inIdx, 0, toCopy, 0, flags)
            if (!data.hasRemaining()) break
            inIdx = codec.dequeueInputBuffer(10000)
        }
    }

    private fun processPcm(buf: ByteBuffer, info: MediaCodec.BufferInfo, cb: (FloatArray) -> Unit) {
        // MediaCodec outputs 16-bit PCM by default
        val frameCount = info.size / 2
        if (frameCount == 0) return
        buf.position(info.offset)
        val shorts = ShortArray(frameCount)
        val sb = buf.asShortBuffer()
        sb.get(shorts)

        val outFloats = FloatArray(frameCount)
        for (i in 0 until frameCount) outFloats[i] = shorts[i] / 32768f
        cb(outFloats)
    }

    fun stop() { job?.cancel(); job = null }
}
