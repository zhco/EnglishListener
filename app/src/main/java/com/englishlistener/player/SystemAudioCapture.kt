package com.englishlistener.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

class SystemAudioCapture {
    companion object {
        private const val TAG = "SystemAudioCapture"
        private const val SAMPLE_RATE = 16000
    }

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile var isRunning = false
    var callCount = 0; private set

    fun addListener(l: (FloatArray) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FloatArray) -> Unit) { listeners.remove(l) }
    fun addStatusListener(l: (String) -> Unit) { statusListeners.add(l) }
    fun removeStatusListener(l: (String) -> Unit) { statusListeners.remove(l) }

    private fun emitStatus(s: String) {
        Log.d(TAG, s)
        for (l in statusListeners) try { l(s) } catch (_: Exception) {}
    }

    fun start(mediaProjection: MediaProjection) {
        if (isRunning) return
        isRunning = true
        callCount = 0
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        job = scope!!.launch {
            emitStatus("starting system capture")
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(SAMPLE_RATE * 2)
                    .build()

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    emitStatus("error: AudioRecord init failed")
                    return@launch
                }

                audioRecord?.startRecording()
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    emitStatus("error: recording failed to start")
                    return@launch
                }

                emitStatus("capturing system audio")
                val buf = ShortArray(SAMPLE_RATE / 10)
                while (isActive && isRunning) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                    if (n <= 0) continue
                    callCount++
                    val samples = FloatArray(n) { buf[it] / 32768f }
                    if (callCount % 50 == 0) emitStatus("captured: $callCount chunks")
                    for (l in listeners) try { l(samples) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture error", e)
                emitStatus("error: ${e.message}")
            }
            emitStatus("capture stopped")
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        scope?.cancel()
        job = null; scope = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        emitStatus("stopped")
    }
}
