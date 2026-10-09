package com.saeed.minikeys.core.llama

/**
 * JNI surface of libllama_jni.so (llama.cpp core, CPU backend, built from upstream source).
 * One native session per loaded model; a handle of 0 means nothing is loaded.
 */
object LlamaCppJni {
    init {
        System.loadLibrary("llama_jni")
    }

    /** Loads a GGUF model file; throws RuntimeException with a readable message on failure. */
    external fun loadModel(path: String, context: Int, threads: Int): Long

    /** Short native description of the loaded model. */
    external fun describe(handle: Long): String

    /**
     * Renders the chat template, prefills the prompt and generates up to maxTokens tokens
     * (stops early on end-of-generation). roles/contents must be equal-length, non-empty.
     */
    external fun chat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temp: Float,
        topK: Int,
        topP: Float,
        penaltyLastN: Int,
        repeatPenalty: Float,
        seed: Long,
    ): String

    /** Releases the model, context and surrounding native memory. */
    external fun free(handle: Long)
}
