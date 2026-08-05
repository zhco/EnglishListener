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
        private const val TS_PACKET_SIZE = 188
    }

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile var isRunning = false
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

    fun start(streamUrl: String) {
        if (isRunning) return
        isRunning = true
        callCount = 0; fwdCount = 0
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        job = scope!!.launch {
            Log.d(TAG, "start: $streamUrl")
            try { processStream(streamUrl) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "stream error", e) }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        scope?.cancel()
        job = null; scope = null
        Log.d(TAG, "stopped")
    }

    private suspend fun processStream(streamUrl: String) = withContext(Dispatchers.IO) {
        val resolved = resolveStream(streamUrl)
        Log.d(TAG, "resolved: $resolved (type=${resolved.type})")
        val conn = URL(resolved.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val input = conn.inputStream

        if (resolved.isTS) {
            decodeTS(input)
        } else {
            decodeMPEG(input)
        }
        input.close()
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
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // M3U8 playlist
            if (text.contains("#EXTM3U")) {
                var bestUrl: String? = null
                var bestBandwidth = 0
                for (line in text.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                        val bw = Regex("BANDWIDTH=(\d+)").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (bw > bestBandwidth) bestBandwidth = bw
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        bestUrl = if (trimmed.startsWith("http")) trimmed
                        else URL(URL(url), trimmed).toString()
                        if (bestBandwidth > 0) break
                    }
                }
                if (bestUrl != null) return resolveStream(bestUrl, depth + 1)

                // No variant playlist, pick first segment
                for (line in text.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val segUrl = if (trimmed.startsWith("http")) trimmed
                        else URL(URL(url), trimmed).toString()
                        return resolveStream(segUrl, depth + 1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed: ${e.message}")
        }
        return ResolvedStream(url, false, "unknown")
    }

    private fun decodeMPEG(input: InputStream) {
        Log.d(TAG, "decodeMPEG start")
        val codec = try {
            MediaCodec.createDecoderByType("audio/mpeg")
        } catch (e: Exception) {
            Log.e(TAG, "no audio/mpeg decoder", e); return
        }
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
                    if (callCount % 50 == 0) Log.d(TAG, "mp3 fwd ${pair.first.size}smp total=$fwdCount")
                    notifyListeners(pair.first)
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
        }
        codec.stop(); codec.release()
        Log.d(TAG, "decodeMPEG done")
    }

    private fun decodeTS(input: InputStream) {
        Log.d(TAG, "decodeTS start")
        val tempFile = java.io.File.createTempFile("ts_seg", ".ts")
        try {
            val output = tempFile.outputStream()
            val buf = ByteArray(BUFFER_SIZE)
            var read: Int
            while (isRunning) {
                read = input.read(buf)
                if (read < 0) break
                output.write(buf, 0, read)
            }
            output.close()

            if (!tempFile.exists() || tempFile.length() < TS_PACKET_SIZE) return

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i; format = fmt; break
                }
            }
            if (audioTrack < 0 || format == null) {
                Log.w(TAG, "no audio track in TS"); extractor.release(); return
            }
            Log.d(TAG, "TS audio: ${format.getString(MediaFormat.KEY_MIME)} sr=${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} ch=${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}")

            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufInfo = MediaCodec.BufferInfo()
            var leftover = FloatArray(0)
            var eos = false

            while (isRunning && !eos) {
                val idx = codec.dequeueInputBuffer(10000)
                if (idx >= 0) {
                    val inBuf = codec.getInputBuffer(idx)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(idx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx) ?: continue
                    val pair = processPCM(outBuf, bufInfo, leftover)
                    leftover = pair.second
                    if (pair.first.isNotEmpty()) {
                        fwdCount += pair.first.size
                        callCount++
                        if (callCount % 50 == 0) Log.d(TAG, "ts fwd ${pair.first.size}smp total=$fwdCount")
                        notifyListeners(pair.first)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            codec.stop(); codec.release()
            extractor.release()
        } catch (e: Exception) {
            Log.e(TAG, "TS decode error", e)
        } finally {
            try { tempFile.delete() } catch (_: Exception) {}
        }
        Log.d(TAG, "decodeTS done")
    }

    private fun processPCM(outBuf: ByteBuffer, bufInfo: MediaCodec.BufferInfo, leftover: FloatArray): Pair<FloatArray, FloatArray> {
        val size = bufInfo.size
        if (size <= 0) return Pair(FloatArray(0), leftover)
        val srcSR = 44100 // default, MediaCodec typically outputs this for MP3
        val srcCh = 2
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
        val combined = leftover + mono
        return resampleLinear(combined, srcSR, TARGET_SR)
    }

    private fun resampleLinear(input: FloatArray, src: Int, dst: Int): Pair<FloatArray, FloatArray> {
        if (src == dst) return Pair(input, FloatArray(0))
        val r = src.toFloat() / dst
        val n = ((input.size - 1) / r).toInt()
        if (n <= 0) return Pair(FloatArray(0), input)
        val o = FloatArray(n)
        for (i in 0 until n) {
            val p = i * r; val j = p.toInt(); val f = p - j
            o[i] = if (j + 1 < input.size) input[j] * (1f - f) + input[j + 1] * f else input[j]
        }
        val c = (n * r).toInt()
        return Pair(o, if (c < input.size) input.copyOfRange(c, input.size) else FloatArray(0))
    }

    private fun notifyListeners(samples: FloatArray) {
        for (l in listeners) {
            try { l(samples) } catch (e: Exception) { Log.e(TAG, "l err", e) }
        }
    }
}