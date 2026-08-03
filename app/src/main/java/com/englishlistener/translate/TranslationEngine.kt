package com.englishlistener.translate

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TranslationEngine(modelFile: File) {
    companion object { private const val TAG = "TranslationEngine"; private const val SP = "<|system|>You are a professional translator. Translate the following English text to Chinese. Output ONLY the Chinese translation.</s>" }
    private var bridge: LlamaBridge? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var onTranslation: ((String, String) -> Unit)? = null

    fun initialize(): Boolean {
        if (!modelFile.exists()) return false
        return try { bridge = LlamaBridge(modelFile.absolutePath); running.set(true); startWorker(); Log.i(TAG, "ready"); true }
        catch (e: Exception) { Log.e(TAG, "init fail", e); false }
    }

    fun submit(text: String) { if (text.isNotBlank()) queue.offer(text) }

    private fun startWorker() {
        scope.launch {
            while (running.get()) {
                val text = queue.poll() ?: run { delay(50); continue }
                try {
                    val r = bridge?.generate("$SP
<|user|>$text</s>
<|assistant|>", 256) ?: continue
                    if (r.isNotEmpty()) onTranslation?.invoke(text, r)
                } catch (e: Exception) { Log.e(TAG, "trans err", e) }
            }
        }
    }

    fun release() { running.set(false); bridge?.release(); bridge = null }
    fun destroy() { running.set(false); scope.cancel(); bridge?.release(); bridge = null }
}