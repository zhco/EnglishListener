package com.englishlistener.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

class AsrEngine(private val asrDir: File) {
    companion object { private const val TAG = "AsrEngine" }
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastPartial = ""

    fun initialize(): Boolean {
        val enc = File(asrDir, "encoder-epoch-99-avg-1.int8.onnx")
        val dec = File(asrDir, "decoder-epoch-99-avg-1.int8.onnx")
        val joi = File(asrDir, "joiner-epoch-99-avg-1.int8.onnx")
        val tok = File(asrDir, "tokens.txt")
        Log.d(TAG, "asrDir=${asrDir.absolutePath} exists=${asrDir.exists()}")
        for ((name, f) in listOf("encoder" to enc, "decoder" to dec, "joiner" to joi, "tokens" to tok)) {
            Log.d(TAG, "  $name: path=${f.absolutePath} exists=${f.exists()} size=${f.length()}")
        }
        val missing = listOf("encoder" to enc, "decoder" to dec, "joiner" to joi, "tokens" to tok)
            .filter { !it.second.exists() }.map { it.first }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "Missing ASR files: $missing")
            return false
        }
        return try {
            val cfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = enc.absolutePath, decoder = dec.absolutePath, joiner = joi.absolutePath),
                    tokens = tok.absolutePath, numThreads = 2, provider = "cpu", modelType = "zipformer"),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.2f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f)),
                enableEndpoint = true)
            recognizer = OnlineRecognizer(config = cfg)
            stream = recognizer!!.createStream()
            Log.d(TAG, "ASR initialized OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ASR init exception: ${e.message}", e)
            false
        }
    }

    fun processSamples(samples: FloatArray): String? {
        val rec = recognizer ?: return null
        val s = stream ?: return null
        try {
            s.acceptWaveform(samples, 16000)
            while (rec.isReady(s)) rec.decode(s)
            val text = rec.getResult(s).text.trim()
            if (text.isNotEmpty() && text != lastPartial) { lastPartial = text; return text }
            if (rec.isEndpoint(s)) rec.reset(s)
        } catch (e: Exception) { Log.e(TAG, "processSamples error", e) }
        return null
    }

    fun release() {
        recognizer?.let { rec -> stream?.let { s -> try { rec.reset(s) } catch (_: Exception) {} } }
        try { stream?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        stream = null; recognizer = null
    }
}
