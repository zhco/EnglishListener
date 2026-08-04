package com.englishlistener.asr

import com.k2fsa.sherpa.onnx.*
import java.io.File

class AsrEngine(private val asrDir: File) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastPartial = ""

    fun initialize(): Boolean {
        val enc = File(asrDir, "encoder-epoch-99-avg-1.int8.onnx")
        val dec = File(asrDir, "decoder-epoch-99-avg-1.int8.onnx")
        val joi = File(asrDir, "joiner-epoch-99-avg-1.int8.onnx")
        val tok = File(asrDir, "tokens.txt")
        if (!enc.exists() || !dec.exists() || !joi.exists() || !tok.exists()) return false
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
            true
        } catch (e: Exception) { e.printStackTrace(); false }
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
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    fun release() {
        recognizer?.let { rec -> stream?.let { s -> try { rec.reset(s) } catch (_: Exception) {} } }
        try { stream?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        stream = null; recognizer = null
    }
}