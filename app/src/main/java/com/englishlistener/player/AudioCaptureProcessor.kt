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
                val conn = URL(streamUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "EnglishListener/1.0")
                conn.setRequestProperty("Icy-MetaData", "1")
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) {
                    Log.e(TAG, "HTTP ${conn.responseCode}")
                    return@launch
                }

                val icyMetaInt = conn.getHeaderField("Icy-MetaInt")?.toIntOrNull() ?: 0
                val input = BufferedInputStream(conn.inputStream)
                var codec: MediaCodec? = null
                val info = MediaCodec.BufferInfo()

                val buf = ByteArray(8192)
                var totalRead = 0
                val initialChunk = ByteArray(524288)
                var initLen = 0

                // Read initial chunk to detect MIME type
                while (isActive && initLen < 524288) {
                    val bytesRead = input.read(buf)
                    if (bytesRead <= 0) break
                    if (icyMetaInt > 0 && totalRead > 0 && totalRead % icyMetaInt == 0) {
                        val metaLen = input.read() * 16
                        if (metaLen > 0) {
                            val skip = ByteArray(metaLen)
                            var skipped = 0
                            while (skipped < metaLen) {
                                val r = input.read(skip, skipped, metaLen - skipped)
                                if (r <= 0) break
                                skipped += r
                            }
                        }
                    }
                    System.arraycopy(buf, 0, initialChunk, initLen, bytesRead)
                    initLen += bytesRead
                    totalRead += bytesRead
                }

                if (initLen == 0) {
                    Log.e(TAG, "no data received")
                    input.close(); conn.disconnect(); return@launch
                }

                // Detect MIME type: check for MPEG sync header (0xFF 0xE0)
                val mime = if ((initialChunk[0].toInt() and 0xFF) == 0xFF &&
                    (initialChunk[1].toInt() and 0xE0) == 0xE0) "audio/mpeg" else "audio/mpeg"
                Log.i(TAG, "detected mime: $mime")

                codec = try {
                    val c = MediaCodec.createDecoderByType(mime)
                    c.configure(MediaFormat.createAudioFormat(mime, 44100, 2), null, null, 0)
                    c.start()
                    Log.i(TAG, "MediaCodec started")
                    c
                } catch (e: Exception) {
                    Log.e(TAG, "codec create failed", e)
                    input.close(); return@launch
                }

                // Feed initial chunk
                feed(codec, ByteBuffer.wrap(initialChunk, 0, initLen), 0)

                // Resampler state: leftover samples between chunks
                var leftover = FloatArray(0)

                while (isActive) {
                    val bytesRead = input.read(buf)
                    if (bytesRead <= 0) break
                    totalRead += bytesRead

                    if (icyMetaInt > 0 && totalRead % icyMetaInt == 0) {
                        val metaLen = input.read() * 16
                        if (metaLen > 0) {
                            val skip = ByteArray(metaLen)
                            var s = 0
                            while (s < metaLen) {
                                val r = input.read(skip, s, metaLen - s)
                                if (r <= 0) break
                                s += r
                            }
                        }
                    }

                    feed(codec, ByteBuffer.wrap(buf, 0, bytesRead), 0)

                    var outputIndex = codec.dequeueOutputBuffer(info, 5000)
                    while (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        val frameCount = info.size / 2 // 16-bit PCM -> short count

                        if (frameCount > 0) {
                            outputBuffer.position(info.offset)
                            val shorts = ShortArray(frameCount)
                            outputBuffer.asShortBuffer().get(shorts)

                            // Get actual output format from codec
                            val outFmt = try { codec.outputFormat } catch (_: Exception) { null }
                            val actualSr = outFmt?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
                            val actualCh = outFmt?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 2

                            // Build mono float array (downmix if stereo)
                            val monoCount = if (actualCh == 2) frameCount / 2 else frameCount
                            val mono = FloatArray(monoCount)
                            if (actualCh == 2) {
                                for (i in 0 until monoCount) {
                                    mono[i] = (shorts[i * 2] + shorts[i * 2 + 1]) / 65536f
                                }
                            } else {
                                for (i in 0 until monoCount) {
                                    mono[i] = shorts[i] / 32768f
                                }
                            }

                            // Resample to TARGET_SR (16000)
                            val combined = leftover + mono
                            val resampled = resampleLinear(combined, actualSr, TARGET_SR)
                            leftover = resampled.second

                            if (resampled.first.isNotEmpty()) {
                                for (l in listeners) {
                                    try { l(resampled.first) } catch (_: Exception) {}
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputIndex, false)
                        outputIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }

                // Drain remaining output
                feed(codec, ByteBuffer.allocate(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                var oe = codec.dequeueOutputBuffer(info, 10000)
                while (oe >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    codec.releaseOutputBuffer(oe, false)
                    oe = codec.dequeueOutputBuffer(info, 0)
                }

                codec.stop(); codec.release()
                input.close(); conn.disconnect()
                Log.i(TAG, "stream ended")
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "stream error", e)
            }
        }
    }

    /**
     * Linear resampler from srcRate to dstRate.
     * Returns (resampled_output, leftover_samples).
     * leftover contains samples that didn't fill a complete output frame.
     */
    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): Pair<FloatArray, FloatArray> {
        if (srcRate == dstRate) return Pair(input, FloatArray(0))
        val ratio = srcRate.toFloat() / dstRate.toFloat()
        val outCount = ((input.size - 1) / ratio).toInt()
        if (outCount <= 0) return Pair(FloatArray(0), input)
        val output = FloatArray(outCount)
        for (i in 0 until outCount) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = pos - idx
            output[i] = if (idx + 1 < input.size) {
                input[idx] * (1f - frac) + input[idx + 1] * frac
            } else {
                input[idx]
            }
        }
        val consumed = (outCount * ratio).toInt()
        val leftover = if (consumed < input.size) {
            input.copyOfRange(consumed, input.size)
        } else {
            FloatArray(0)
        }
        return Pair(output, leftover)
    }

    private fun feed(codec: MediaCodec, data: ByteBuffer, flags: Int) {
        var inputIndex = codec.dequeueInputBuffer(10000)
        while (inputIndex >= 0 && data.hasRemaining()) {
            val inputBuffer = codec.getInputBuffer(inputIndex)!!
            val n = minOf(inputBuffer.remaining(), data.remaining())
            if (n > 0) {
                val slice = data.slice()
                slice.limit(n)
                inputBuffer.put(slice)
                data.position(data.position() + n)
            }
            codec.queueInputBuffer(inputIndex, 0, n, 0, flags)
            if (!data.hasRemaining()) break
            inputIndex = codec.dequeueInputBuffer(10000)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
