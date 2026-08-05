package com.englishlistener.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor {
    companion object {
        private const val TAG = "AudioCapture"
        private const val TARGET_SR = 16000
        private const val BUFFER_SIZE = 4096
    }

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile var isRunning = false
    private var callCount = 0
    private var fwdCount = 0L

    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }
    fun addStatusListener(l: (String) -> Unit) { statusListeners.add(l) }
    fun removeStatusListener(l: (String) -> Unit) { statusListeners.remove(l) }

    private fun emitStatus(s: String) {
        Log.d(TAG, s)
        for (l in statusListeners) { try { l(s) } catch (e: Exception) {} }
    }

    fun start(streamUrl: String) {
        if (isRunning) return
        isRunning = true
        callCount = 0; fwdCount = 0
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        job = scope!!.launch {
            emitStatus("connecting")
            try { processStream(streamUrl) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "stream error", e)
                emitStatus("error: ${e.message}")
            }
            if (isRunning) emitStatus("disconnected")
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        scope?.cancel()
        job = null; scope = null
        emitStatus("stopped")
    }

    private suspend fun processStream(streamUrl: String) = withContext(Dispatchers.IO) {
        emitStatus("resolving")
        val resolved = resolveStream(streamUrl)
        Log.d(TAG, "resolved: ${resolved.url} type=${resolved.type}")

        emitStatus("connecting: ${resolved.url.take(50)}")
        val conn = URL(resolved.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Icy-MetaData", "0")

        val code = conn.responseCode
        if (code !in 200..299) {
            emitStatus("HTTP $code")
            conn.disconnect()
            return@withContext
        }

        val input = conn.inputStream
        val contentType = conn.contentType ?: ""
        emitStatus("streaming (${resolved.type})")

        if (resolved.isTS) {
            decodeTS(input)
        } else {
            decodeMPEG(input)
        }
        try { input.close() } catch (_: Exception) {}
    }

    data class ResolvedStream(val url: String, val isTS: Boolean, val type: String)

    private fun resolveStream(url: String, depth: Int = 0): ResolvedStream {
        if (depth > 3) return ResolvedStream(url, url.endsWith(".ts"), "depth_limit")
        if (url.endsWith(".ts")) return ResolvedStream(url, true, "ts")
        if (url.endsWith(".mp3") || url.endsWith(".mp2")) return ResolvedStream(url, false, "audio/mpeg")

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc != null) return resolveStream(loc, depth + 1)
            }

            val ct = conn.contentType ?: ""
            if (ct.startsWith("audio/")) {
                conn.disconnect()
                return ResolvedStream(url, false, ct)
            }

            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (text.contains("#EXTM3U")) {
                var bestUrl: String? = null; var bestBw = 0
                for (line in text.lines()) {
                    val t = line.trim()
                    if (t.startsWith("#EXT-X-STREAM-INF")) {
                        val bw = Regex("BANDWIDTH=(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (bw > bestBw) bestBw = bw
                    } else if (t.isNotEmpty() && !t.startsWith("#")) {
                        bestUrl = if (t.startsWith("http")) t else URL(URL(url), t).toString()
                        if (bestBw > 0) break
                    }
                }
                if (bestUrl != null) return resolveStream(bestUrl, depth + 1)
                for (line in text.lines()) {
                    val t = line.trim()
                    if (t.isNotEmpty() && !t.startsWith("#")) {
                        val seg = if (t.startsWith("http")) t else URL(URL(url), t).toString()
                        return resolveStream(seg, depth + 1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed: ${e.message}")
        }
        return ResolvedStream(url, false, "unknown")
    }

    private suspend fun decodeMPEG(input: InputStream) {
        Log.d(TAG, "decodeMPEG start")
        val codec = try { MediaCodec.createDecoderByType("audio/mpeg") }
        catch (e: Exception) { Log.e(TAG, "no audio/mpeg decoder", e); emitStatus("no codec"); return }
        codec.configure(MediaFormat.createAudioFormat("audio/mpeg", 44100, 2), null, null, 0)
        codec.start()

        val bufInfo = MediaCodec.BufferInfo()
        val rawBuf = ByteArray(BUFFER_SIZE)
        var leftover = FloatArray(0)
        val readBuf = ByteBuffer.allocate(BUFFER_SIZE)

        while (isRunning) {
            val read = input.read(rawBuf)
            if (read < 0) break
            if (read == 0) { delay(10); continue }

            readBuf.clear(); readBuf.put(rawBuf, 0, read); readBuf.flip()

            val idx = codec.dequeueInputBuffer(10000)
            if (idx >= 0) {
                codec.getInputBuffer(idx)?.clear()
                codec.getInputBuffer(idx)?.put(readBuf)
                codec.queueInputBuffer(idx, 0, read, 0, 0)
            }

            val outIdx = codec.dequeueOutputBuffer(bufInfo, 10000)
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx) ?: continue
                val pair = processPCM(outBuf, bufInfo, leftover)
                leftover = pair.second
                if (pair.first.isNotEmpty()) {
                    fwdCount += pair.first.size
                    callCount++
                    if (callCount == 1) emitStatus("receiving audio")
                    if (callCount % 100 == 0) emitStatus("audio chunks: $callCount")
                    notifyListeners(pair.first)
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
        }
        codec.stop(); codec.release()
    }

    private suspend fun decodeTS(input: InputStream) = withContext(Dispatchers.IO) {
        Log.d(TAG, "decodeTS start")
        try {
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            val pumpJob = launch(Dispatchers.IO) {
                try {
                    val out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
                    val buf = ByteArray(BUFFER_SIZE * 4)
                    var tw = 0L
                    while (isRunning) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        tw += n
                        if (tw > 512 * 1024) { delay(5) }
                    }
                    out.close()
                } catch (e: Exception) { Log.w(TAG, "pipe writer: ${e.message}") }
            }

            val extractor = MediaExtractor()
            extractor.setDataSource(readFd.fileDescriptor, 0, Long.MAX_VALUE)

            var audioTrack = -1; var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i; format = fmt; break
                }
            }
            if (audioTrack < 0 || format == null) {
                Log.w(TAG, "no audio track"); pumpJob.cancel(); extractor.release(); readFd.close()
                emitStatus("no audio track")
                return@withContext
            }

            extractor.selectTrack(audioTrack)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufInfo = MediaCodec.BufferInfo()
            var leftover = FloatArray(0)
            var eos = false

            while (isRunning && !eos) {
                val idx = codec.dequeueInputBuffer(5000)
                if (idx >= 0) {
                    val inBuf = codec.getInputBuffer(idx)!!
                    val sz = extractor.readSampleData(inBuf, 0)
                    if (sz < 0) { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else { codec.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                }
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 5000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx) ?: continue
                    val pair = processPCM(outBuf, bufInfo, leftover)
                    leftover = pair.second
                    if (pair.first.isNotEmpty()) {
                        fwdCount += pair.first.size; callCount++
                        if (callCount == 1) emitStatus("receiving audio")
                        if (callCount % 100 == 0) emitStatus("audio chunks: $callCount")
                        notifyListeners(pair.first)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            codec.stop(); codec.release(); extractor.release()
            pumpJob.cancel(); readFd.close()
        } catch (e: Exception) { Log.e(TAG, "TS error", e); emitStatus("decode error: ${e.message}") }
    }

    private fun processPCM(outBuf: ByteBuffer, bufInfo: MediaCodec.BufferInfo, leftover: FloatArray): Pair<FloatArray, FloatArray> {
        val size = bufInfo.size
        if (size <= 0) return Pair(FloatArray(0), leftover)
        val srcSR = 44100; val srcCh = 2
        val shorts = ShortArray(size / 2)
        outBuf.position(bufInfo.offset)
        outBuf.asShortBuffer().apply { position(0); get(shorts) }
        val monoLen = size / 2 / srcCh
        val mono = FloatArray(monoLen)
        for (i in 0 until monoLen) {
            var sum = 0
            for (ch in 0 until srcCh) sum += shorts[i * srcCh + ch].toInt()
            mono[i] = sum / (32768f * srcCh)
        }
        return resampleLinear(leftover + mono, srcSR, TARGET_SR)
    }

    private fun resampleLinear(input: FloatArray, src: Int, dst: Int): Pair<FloatArray, FloatArray> {
        if (src == dst) return Pair(input, FloatArray(0))
        val r = src.toFloat() / dst
        val n = ((input.size - 1) / r).toInt()
        if (n <= 0) return Pair(FloatArray(0), input)
        val o = FloatArray(n)
        for (i in 0 until n) { val p = i * r; val j = p.toInt(); val f = p - j; o[i] = if (j + 1 < input.size) input[j] * (1f - f) + input[j + 1] * f else input[j] }
        val c = (n * r).toInt()
        return Pair(o, if (c < input.size) input.copyOfRange(c, input.size) else FloatArray(0))
    }

    private fun notifyListeners(samples: FloatArray) {
        for (l in listeners) { try { l(samples) } catch (e: Exception) { Log.e(TAG, "listener err", e) } }
    }
}
