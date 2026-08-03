package com.englishlistener.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

class AsrEngine(private val asrDir: File) {
    companion object { private const val TAG = "AsrEngine"; private const val SAMPLE_RATE = 16000 }
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
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = enc.absolutePath, decoder = dec.absolutePath, joiner = joi.absolutePath),
                    tokens = tok.absolutePath, numThreads = 2, provider = "cpu"
                ),
                enableEndpoint = true, rule1MinTrailingSilence = 1.2f, rule2MinTrailingSilence = 0.5f, rule3MinUtteranceLength = 10.0f
            )
            recognizer = OnlineRecognizer(cfg)
            stream = recognizer?.createStream()
            Log.i(TAG, "ASR ready"); true
        } catch (e: Exception) { Log.e(TAG, "ASR init fail", e); false }
    }

    fun processSamples(samples: FloatArray): String? {
        val rec = recognizer ?: return null; val s = stream ?: return null
        try {
            s.acceptWaveform(samples, SAMPLE_RATE)
            while (rec.isReady(s)) rec.decode(s)
            val text = rec.getResult(s)?.text?.trim() ?: ""
            if (text.isNotEmpty() && text != lastPartial) { lastPartial = text; return text }
            if (rec.isEndpoint(s)) rec.reset(s)
        } catch (e: Exception) { Log.e(TAG, "proc err", e) }
        return null
    }

    fun reset() { try { recognizer?.reset(stream) } catch (_: Exception) {} }
    fun release() { try { stream?.release() } catch (_: Exception) {}; try { recognizer?.release() } catch (_: Exception) {}; stream = null; recognizer = null }
}