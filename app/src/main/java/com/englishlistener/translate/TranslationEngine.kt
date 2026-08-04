package com.englishlistener.translate

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TranslationEngine(private val modelFile: File) {
    private var bridge: LlamaBridge? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var onTranslation: ((String, String) -> Unit)? = null

    fun initialize(): Boolean {
        if (!modelFile.exists()) return false
        return try {
            bridge = LlamaBridge(modelFile.absolutePath)
            running.set(true); startWorker()
            Log.i("TransEngine", "ready"); true
        } catch (e: Exception) { Log.e("TransEngine", "init fail", e); false }
    }

    fun submit(text: String) { if (text.isNotBlank()) queue.offer(text) }

    private fun startWorker() {
        scope.launch {
            while (running.get()) {
                val text = queue.poll() ?: run { delay(50); continue }
                try {
                    val sys = "<|system|>You are a professional translator. Translate English to Chinese. Output ONLY Chinese.</s>"
                    val user = "<|user|>$text</s>"
                    val prompt = "$sys
$user
<|assistant|>"
                    val r = bridge?.generate(prompt, 256)?.trim() ?: ""
                    if (r.isNotEmpty()) { val cb = onTranslation ?: continue; cb(text, r) }
                } catch (e: Exception) { Log.e("TransEngine", "err", e) }
            }
        }
    }

    fun release() { running.set(false); bridge?.release(); bridge = null }
    fun destroy() { running.set(false); scope.cancel(); bridge?.release(); bridge = null }
}