package com.englishlistener.translate

import android.util.Log
import com.sun.jna.*

interface LlamaCLib : Library {
    fun llama_model_load_from_file(path: String, params: LlamaModelParams.ByValue): Pointer
    fun llama_new_context_with_model(model: Pointer, params: LlamaContextParams.ByValue): Pointer
    fun llama_free_model(model: Pointer)
    fun llama_free(ctx: Pointer)
    fun llama_decode(ctx: Pointer, batch: LlamaBatch.ByValue): Int
    fun llama_get_logits_ith(ctx: Pointer, i: Int): Pointer
    fun llama_tokenize(model: Pointer, text: String, addBos: Boolean, special: Boolean): LlamaTokens.ByValue
    fun llama_token_to_piece(ctx: Pointer, token: Int, special: Boolean): String
    fun llama_n_vocab(model: Pointer): Int
    fun llama_eos_token(model: Pointer): Int

    @Structure.FieldOrder(("n_gpu_layers","split_mode","main_gpu","tensor_split","progress_callback","progress_callback_user_data","kv_overrides","vocab_only","use_mmap","use_mlock","check_tensors"))
    class LlamaModelParams : Structure() {
        @JvmField var n_gpu_layers: Int = 0; @JvmField var split_mode: Int = 0; @JvmField var main_gpu: Int = 0
        @JvmField var tensor_split: Pointer? = null; @JvmField var progress_callback: Pointer? = null; @JvmField var progress_callback_user_data: Pointer? = null
        @JvmField var kv_overrides: Pointer? = null; @JvmField var vocab_only: Boolean = false; @JvmField var use_mmap: Boolean = true
        @JvmField var use_mlock: Boolean = false; @JvmField var check_tensors: Boolean = false
    }

    @Structure.FieldOrder(("n_ctx","n_batch","n_ubatch","n_seq_max","n_threads","n_threads_batch","rope_scaling_type","pooling_type","rope_freq_base","rope_freq_scale","yarn_ext_factor","yarn_attn_factor","yarn_beta_fast","yarn_beta_slow","yarn_orig_ctx","logits_all","embeddings","offload_kqv","flash_attn","no_perf","abort_callback","abort_callback_data"))
    class LlamaContextParams : Structure() {
        @JvmField var n_ctx: Int = 2048; @JvmField var n_batch: Int = 512; @JvmField var n_ubatch: Int = 512; @JvmField var n_seq_max: Int = 1
        @JvmField var n_threads: Int = 4; @JvmField var n_threads_batch: Int = 4; @JvmField var rope_scaling_type: Int = 0; @JvmField var pooling_type: Int = 0
        @JvmField var rope_freq_base: Float = 0f; @JvmField var rope_freq_scale: Float = 0f; @JvmField var yarn_ext_factor: Float = -1f; @JvmField var yarn_attn_factor: Float = 1f
        @JvmField var yarn_beta_fast: Float = 32f; @JvmField var yarn_beta_slow: Float = 1f; @JvmField var yarn_orig_ctx: Int = 0
        @JvmField var logits_all: Boolean = false; @JvmField var embeddings: Boolean = false; @JvmField var offload_kqv: Boolean = true
        @JvmField var flash_attn: Boolean = false; @JvmField var no_perf: Boolean = true; @JvmField var abort_callback: Pointer? = null; @JvmField var abort_callback_data: Pointer? = null
    }

    @Structure.FieldOrder(("n_tokens","token","embd","pos","n_seq_id","seq_id","logits"))
    class LlamaBatch : Structure() {
        @JvmField var n_tokens: Int = 0; @JvmField var token: Pointer? = null; @JvmField var embd: Pointer? = null
        @JvmField var pos: Pointer? = null; @JvmField var n_seq_id: Pointer? = null; @JvmField var seq_id: Pointer? = null; @JvmField var logits: Pointer? = null
    }

    @Structure.FieldOrder(("size","data"))
    class LlamaTokens : Structure() {
        @JvmField var size: Int = 0; @JvmField var data: Pointer? = null
    }
}

class LlamaBridge(modelPath: String) {
    companion object { private const val TAG = "LlamaBridge" }
    private val lib = Native.load("llama", LlamaCLib::class.java)
    private val model: Pointer
    private val ctx: Pointer
    private val vocabSize: Int
    private val eosToken: Int

    init {
        model = lib.llama_model_load_from_file(modelPath, LlamaCLib.LlamaModelParams()) ?: throw RuntimeException("llama: load fail")
        ctx = lib.llama_new_context_with_model(model, LlamaCLib.LlamaContextParams().apply { n_ctx = 2048; n_threads = 4 }) ?: throw RuntimeException("llama: ctx fail")
        vocabSize = lib.llama_n_vocab(model); eosToken = lib.llama_eos_token(model)
        Log.i(TAG, "llama ready: vocab=$vocabSize")
    }

    fun generate(prompt: String, maxTokens: Int = 256): String {
        val toks = lib.llama_tokenize(model, prompt, true, true)
        val tm = Memory(toks.size.toLong() * Native.getNativeSize(Int::class.java).toLong())
        val ta = tm.shared.subBuffer(0, toks.size * Native.getNativeSize(Int::class.java))
        for (i in 0 until toks.size) ta.setInt(i.toLong() * Native.getNativeSize(Int::class.java), toks.data!!.getByteArray(0, toks.size * Native.getNativeSize(Int::class.java)).let { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()[i] })
        val b0 = LlamaCLib.LlamaBatch().apply { n_tokens = toks.size; token = tm }
        lib.llama_decode(ctx, b0)

        val sb = StringBuilder()
        for (i in 0 until maxTokens) {
            val logits = lib.llama_get_logits_ith(ctx, i + toks.size - 1)
            val nt = sample(logits, vocabSize)
            if (nt == eosToken) break
            sb.append(lib.llama_token_to_piece(ctx, nt, false))
            lib.llama_decode(ctx, LlamaCLib.LlamaBatch().apply { n_tokens = 1; token = Pointer.NULL })
        }
        return sb.toString().trim()
    }

    private fun sample(p: Pointer, n: Int): Int {
        var best = 0; var bv = Float.NEGATIVE_INFINITY
        for (i in 0 until n) { val v = p.getFloat(i.toLong() * 4); if (v > bv) { bv = v; best = i } }
        return best
    }

    fun release() { try { lib.llama_free(ctx) } catch (_: Exception) {}; try { lib.llama_free_model(model) } catch (_: Exception) {} }
}