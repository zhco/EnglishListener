package com.englishlistener.translate

import com.sun.jna.*

interface LlamaC : Library {
    fun llama_model_load_from_file(path: String, params: LlamaModelParams): Pointer
    fun llama_new_context_with_model(model: Pointer, params: LlamaContextParams): Pointer
    fun llama_free_model(model: Pointer)
    fun llama_free(ctx: Pointer)
    fun llama_decode(ctx: Pointer, batch: LlamaBatch): Int
    fun llama_get_logits_ith(ctx: Pointer, i: Int): Pointer
    fun llama_tokenize(model: Pointer, text: String, add_bos: Int, special: Boolean): IntArray
    fun llama_token_to_piece(ctx: Pointer, token: Int, special: Boolean): String?
    fun llama_n_vocab(model: Pointer): Int
    fun llama_eos_token(model: Pointer): Int
}

@Structure.FieldOrder("n_gpu_layers","split_mode","main_gpu","tensor_split","progress_callback","progress_callback_user_data","kv_overrides","vocab_only","use_mmap","use_mlock","check_tensors")
class LlamaModelParams : Structure() {
    @JvmField var n_gpu_layers: Int = 0
    @JvmField var split_mode: Int = 0
    @JvmField var main_gpu: Int = 0
    @JvmField var tensor_split: Pointer? = null
    @JvmField var progress_callback: Pointer? = null
    @JvmField var progress_callback_user_data: Pointer? = null
    @JvmField var kv_overrides: Pointer? = null
    @JvmField var vocab_only: Boolean = false
    @JvmField var use_mmap: Boolean = true
    @JvmField var use_mlock: Boolean = false
    @JvmField var check_tensors: Boolean = false
}

@Structure.FieldOrder("n_ctx","n_batch","n_ubatch","n_seq_max","n_threads","n_threads_batch","rope_scaling_type","pooling_type","rope_freq_base","rope_freq_scale","yarn_ext_factor","yarn_attn_factor","yarn_beta_fast","yarn_beta_slow","yarn_orig_ctx","logits_all","embeddings","offload_kqv","flash_attn","no_perf","abort_callback","abort_callback_data")
class LlamaContextParams : Structure() {
    @JvmField var n_ctx: Int = 2048
    @JvmField var n_batch: Int = 512
    @JvmField var n_ubatch: Int = 512
    @JvmField var n_seq_max: Int = 1
    @JvmField var n_threads: Int = 4
    @JvmField var n_threads_batch: Int = 4
    @JvmField var rope_scaling_type: Int = 0
    @JvmField var pooling_type: Int = 0
    @JvmField var rope_freq_base: Float = 0f
    @JvmField var rope_freq_scale: Float = 0f
    @JvmField var yarn_ext_factor: Float = -1f
    @JvmField var yarn_attn_factor: Float = 1f
    @JvmField var yarn_beta_fast: Float = 32f
    @JvmField var yarn_beta_slow: Float = 1f
    @JvmField var yarn_orig_ctx: Int = 0
    @JvmField var logits_all: Boolean = false
    @JvmField var embeddings: Boolean = false
    @JvmField var offload_kqv: Boolean = true
    @JvmField var flash_attn: Boolean = false
    @JvmField var no_perf: Boolean = true
    @JvmField var abort_callback: Pointer? = null
    @JvmField var abort_callback_data: Pointer? = null
}

@Structure.FieldOrder("n_tokens","token","embd","pos","n_seq_id","seq_id","logits")
class LlamaBatch : Structure() {
    @JvmField var n_tokens: Int = 0
    @JvmField var token: Pointer? = null
    @JvmField var embd: Pointer? = null
    @JvmField var pos: Pointer? = null
    @JvmField var n_seq_id: Pointer? = null
    @JvmField var seq_id: Pointer? = null
    @JvmField var logits: Pointer? = null

    fun setTokenArray(vals: IntArray) {
        if (vals.isEmpty()) return
        val m = Memory((vals.size * Native.getNativeSize(Int::class.java).toLong()).toLong())
        for (i in vals.indices) m.setInt((i * Native.getNativeSize(Int::class.java)).toLong(), vals[i])
        token = m
    }
}

class LlamaBridge(modelPath: String) {
    private val lib = Native.load("llama", LlamaC::class.java)
    private val model: Pointer = lib.llama_model_load_from_file(modelPath, LlamaModelParams()) ?: throw RuntimeException("llama: load fail")
    private val ctx: Pointer = lib.llama_new_context_with_model(model, LlamaContextParams().apply { n_ctx = 2048; n_threads = 4 }) ?: throw RuntimeException("llama: ctx fail")
    private val vocabSize: Int = lib.llama_n_vocab(model)
    private val eosToken: Int = lib.llama_eos_token(model)

    fun generate(prompt: String, maxTokens: Int = 256): String {
        val toks = lib.llama_tokenize(model, prompt, 1, true)
        val batch = LlamaBatch()
        batch.n_tokens = toks.size
        batch.setTokenArray(toks)
        lib.llama_decode(ctx, batch)

        val sb = StringBuilder()
        var nt = 0
        for (i in 0 until maxTokens) {
            val logits = lib.llama_get_logits_ith(ctx, batch.n_tokens - 1 + i)
            nt = sample(logits)
            if (nt == eosToken) break
            val piece = lib.llama_token_to_piece(ctx, nt, false) ?: ""
            sb.append(piece)
            val b2 = LlamaBatch().apply { n_tokens = 1; setTokenArray(intArrayOf(nt)) }
            lib.llama_decode(ctx, b2)
        }
        return sb.toString().trim()
    }

    private fun sample(p: Pointer): Int {
        var best = 0; var bv = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) { val v = p.getFloat((i * Native.getNativeSize(Float::class.java)).toLong()); if (v > bv) { bv = v; best = i } }
        return best
    }

    fun release() { try { lib.llama_free(ctx) } catch (_: Exception) {}; try { lib.llama_free_model(model) } catch (_: Exception) {} }
}