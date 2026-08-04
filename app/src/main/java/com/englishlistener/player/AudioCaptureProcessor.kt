package com.englishlistener.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor {
    companion object {
        private const val TAG = "AudioCapture"
        private const val TARGET_SR = 16000
    }

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }

    fun start(streamUrl: String) {
        stop()
        job = scope.launch {
            try {
                val resolvedUrl = resolveM3u8(streamUrl)
                Log.i(TAG, "resolved: $streamUrl -> $resolvedUrl")
                processStream(resolvedUrl)
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "fatal", e)
            }
        }
    }

    private fun resolveM3u8(url: String): String {
        if (!url.endsWith(".m3u8", ignoreCase = true)) return url
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "EnglishListener/1.0")
            }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val lines = text.lines().map { it.trim() }.filter { it.startsWith("http") }
            if (lines.isEmpty()) return url
            if (text.contains("#EXT-X-STREAM-INF")) {
                Log.i(TAG, "master playlist, picking variant: ${lines[0]}")
                return resolveM3u8(lines[0])
            }
            Log.i(TAG, "segment playlist, using: ${lines[0]}")
            lines[0]
        } catch (e: Exception) {
            Log.e(TAG, "m3u8 resolve failed", e)
            url
        }
    }

    private fun processStream(streamUrl: String) {
        val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 60000
            setRequestProperty("User-Agent", "EnglishListener/1.0")
            setRequestProperty("Icy-MetaData", "1")
            instanceFollowRedirects = true
        }
        if (conn.responseCode != 200) { Log.e(TAG, "HTTP ${conn.responseCode}"); conn.disconnect(); return }

        val icyMetaInt = conn.getHeaderField("Icy-MetaInt")?.toIntOrNull() ?: 0
        val input = BufferedInputStream(conn.inputStream)

        val buf = ByteArray(8192)
        val initial = ByteArrayOutputStream()
        var totalRead = 0
        while (initial.size() < 524288) {
            val n = input.read(buf)
            if (n <= 0) break
            if (icyMetaInt > 0 && totalRead > 0 && totalRead % icyMetaInt == 0) {
                val metaLen = input.read() * 16
                if (metaLen > 0) skipBytes(input, metaLen)
            }
            initial.write(buf, 0, n); totalRead += n
        }
        val initialBytes = initial.toByteArray()
        initial.close()
        if (initialBytes.isEmpty()) { input.close(); return }
        Log.i(TAG, "initial chunk: ${initialBytes.size} bytes")

        val mime = "audio/mpeg"

        val codec = try {
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(MediaFormat.createAudioFormat(mime, 44100, 2), null, null, 0)
            c.start(); c
        } catch (e: Exception) {
            Log.e(TAG, "codec fail", e); input.close(); return
        }

        val info = MediaCodec.BufferInfo()
        feedCodec(codec, ByteBuffer.wrap(initialBytes), 0)

        var leftover = FloatArray(0)
        var codecStopped = false

        while (!codecStopped) {
            val n = input.read(buf)
            if (n > 0) {
                totalRead += n
                if (icyMetaInt > 0 && totalRead % icyMetaInt == 0) {
                    val metaLen = input.read() * 16
                    if (metaLen > 0) skipBytes(input, metaLen)
                }
                feedCodec(codec, ByteBuffer.wrap(buf, 0, n), 0)
            }

            var oi = codec.dequeueOutputBuffer(info, 5000)
            while (oi >= 0) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { codecStopped = true; break }
                val ob = codec.getOutputBuffer(oi)
                if (ob != null && info.size > 1) {
                    ob.position(info.offset)
                    val frameCount = info.size / 2
                    val shorts = ShortArray(frameCount)
                    ob.asShortBuffer().get(shorts)

                    val outFmt = try { codec.outputFormat } catch (_: Exception) { null }
                    val actualSr = outFmt?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
                    val actualCh = outFmt?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 2

                    val monoLen = if (actualCh >= 2) frameCount / actualCh else frameCount
                    val mono = FloatArray(monoLen)
                    if (actualCh >= 2) {
                        for (i in 0 until monoLen) mono[i] = (shorts[i * actualCh].toInt() + shorts[i * actualCh + 1].toInt()) / 65536f
                    } else {
                        for (i in 0 until monoLen) mono[i] = shorts[i].toInt() / 32768f
                    }

                    val combined = leftover + mono
                    val (resampled, rem) = resampleLinear(combined, actualSr, TARGET_SR)
                    leftover = rem
                    if (resampled.isNotEmpty()) {
                        for (l in listeners) { try { l(resampled) } catch (_: Exception) {} }
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                oi = codec.dequeueOutputBuffer(info, 0)
            }
        }

        try { codec.stop(); codec.release() } catch (_: Exception) {}
        input.close()
        Log.i(TAG, "stream ended")
    }

    private fun skipBytes(input: java.io.InputStream, n: Int) {
        val sbuf = ByteArray(minOf(n, 4096))
        var remain = n
        while (remain > 0) {
            val r = input.read(sbuf, 0, minOf(sbuf.size, remain))
            if (r <= 0) break
            remain -= r
        }
    }

    private fun feedCodec(codec: MediaCodec, data: ByteBuffer, flags: Int) {
        var ii = codec.dequeueInputBuffer(10000)
        while (ii >= 0 && data.hasRemaining()) {
            val ib = codec.getInputBuffer(ii) ?: break
            val n = minOf(ib.remaining(), data.remaining())
            if (n > 0) {
                val slice = data.slice(); slice.limit(n)
                ib.put(slice); data.position(data.position() + n)
            }
            codec.queueInputBuffer(ii, 0, n, 0, flags)
            if (!data.hasRemaining()) break
            ii = codec.dequeueInputBuffer(10000)
        }
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): Pair<FloatArray, FloatArray> {
        if (srcRate == dstRate) return Pair(input, FloatArray(0))
        val ratio = srcRate.toFloat() / dstRate.toFloat()
        val outCount = ((input.size - 1) / ratio).toInt()
        if (outCount <= 0) return Pair(FloatArray(0), input)
        val output = FloatArray(outCount)
        for (i in 0 until outCount) {
            val pos = i * ratio; val idx = pos.toInt(); val frac = pos - idx
            output[i] = if (idx + 1 < input.size) input[idx] * (1f - frac) + input[idx + 1] * frac else input[idx]
        }
        val consumed = (outCount * ratio).toInt()
        return Pair(output, if (consumed < input.size) input.copyOfRange(consumed, input.size) else FloatArray(0))
    }

    fun stop() { job?.cancel(); job = null }
}
