package com.englishlistener.player

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureProcessor {
    companion object { private const val TAG = "AudioCapture" }
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
                conn.connectTimeout = 10000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "EnglishListener/1.0")
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code != 200) { Log.e(TAG, "HTTP $code"); return@launch }
                val input: InputStream = conn.inputStream
                val buf = ByteArray(4096)
                var bytesRead: Int
                while (isActive) {
                    bytesRead = input.read(buf)
                    if (bytesRead <= 0) break
                    val samples = FloatArray(bytesRead / 2)
                    for (i in samples.indices) {
                        val lo = buf[i * 2].toInt() and 0xFF
                        val hi = buf[i * 2 + 1].toInt() and 0xFF
                        samples[i] = ((hi shl 8) or lo).toShort() / 32768f
                    }
                    for (l in listeners) try { l(samples) } catch (_: Exception) {}
                }
                input.close(); conn.disconnect()
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "stream err", e)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}