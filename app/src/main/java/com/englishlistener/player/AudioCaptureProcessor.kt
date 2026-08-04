package com.englishlistener.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

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
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) { Log.e(TAG, "HTTP ${conn.responseCode}"); return@launch }

                val input = conn.inputStream
                val extractor = MediaExtractor()
                val bufs = mutableListOf<ByteArray>()
                val buf = ByteArray(65536)

                // Download enough data for extractor to detect format
                var total = 0
                while (isActive && total < 524288) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    bufs.add(buf.copyOf(n)); total += n
                }
                if (!isActive || total == 0) { input.close(); conn.disconnect(); return@launch }

                // Write initial buffer to temp and set data source
                val combined = ByteArray(total)
                var off = 0
                for (b in bufs) { System.arraycopy(b, 0, combined, off, b.size); off += b.size }
                extractor.setDataSource(
                    androidx.media3.common.util.Util.castNonNull(
                        java.io.FileInputStream(
                            java.io.File.createTempFile("el_stream", ".tmp").also { it.writeBytes(combined) }
                        ).fd
                    )
                )

                // Find audio track
                var audioIdx = -1
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { audioIdx = i; break }
                }
                if (audioIdx < 0) { Log.e(TAG, "No audio track"); extractor.release(); input.close(); return@launch }

                val fmt = extractor.getTrackFormat(audioIdx)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                Log.i(TAG, "Audio: $mime ${sr}Hz ${ch}ch")

                // Configure decoder
                val decoder = MediaCodec.createDecoderByType(mime)
                val outFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, TARGET_SR, 1)
                outFmt.setInteger(MediaFormat.KEY_PCM_ENCODING, MediaCodecInfo.CodecCapabilities.COLUMN_TYPE_FLOAT)
                decoder.configure(fmt, null, null, 0)
                decoder.start()
                extractor.selectTrack(audioIdx)

                val info = MediaCodec.BufferInfo()
                var eos = false
                val resampleBuf = FloatArray(4096) // large enough

                // Start a coroutine to keep feeding the extractor
                launch {
                    try {
                        while (isActive) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            // Can't feed extractor directly, it needs file descriptor
                        }
                    } catch (_: Exception) {}
                }

                while (isActive && !eos) {
                    // Despite the fd limitation, try to decode what we have
                    val inIdx = decoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val sampleSize = extractor.readSampleData(decoder.getInputBuffer(inIdx)!!, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outIdx = decoder.dequeueOutputBuffer(info, 10000)
                    if (outIdx >= 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)!!
                        val floatCount = info.size / 4
                        if (floatCount > 0) {
                            val ratio = sr.toFloat() / TARGET_SR
                            val outFloats = FloatArray((floatCount / ch / ratio).toInt().coerceAtLeast(1))
                            var wi = 0
                            var srcIdx = 0
                            while (srcIdx < floatCount - ch + 1 && wi < outFloats.size) {
                                // Mix down to mono
                                var sum = 0f; var c = 0
                                while (c < ch && srcIdx + c < floatCount) {
                                    sum += outBuf.getFloat((srcIdx + c) * 4); c++
                                }
                                outFloats[wi++] = sum / ch
                                srcIdx += (ch * ratio).toInt().coerceAtLeast(ch)
                            }
                            if (wi > 0) {
                                val result = outFloats.copyOf(wi)
                                for (l in listeners) try { l(result) } catch (_: Exception) {}
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                }
                decoder.stop(); decoder.release(); extractor.release(); input.close()
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "decode err", e)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
