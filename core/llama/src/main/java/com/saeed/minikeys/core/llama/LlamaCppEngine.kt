package com.saeed.minikeys.core.llama

import com.saeed.minikeys.core.llm.ChatMessage
import com.saeed.minikeys.core.llm.LlmEngine
import com.saeed.minikeys.core.llm.LlmReply
import java.io.File

/**
 * Offline GGUF engine implementing the shared [LlmEngine] contract, so cloud and local llama.cpp
 * are interchangeable for every consumer of the modular project.
 *
 * []
 */
class LlamaCppEngine(
    val modelPath: String,
    options: LlamaCppOptions = LlamaCppOptions(),
) : LlmEngine, AutoCloseable {

    private val normalized = options.normalized()
    private val handle: Long
    val nativeDescription: String

    init {
        handle = LlamaCppJni.loadModel(modelPath, normalized.context, normalized.threads)
        if (handle == 0L) throw IllegalStateException("llama.cpp returned a zero handle for $modelPath")
        nativeDescription = runCatching { LlamaCppJni.describe(handle) }
            .getOrDefault(File(modelPath).name)
    }

    override fun chat(messages: List<ChatMessage>): LlmReply {
        if (messages.isEmpty()) throw IllegalArgumentException("at least one message is required")
        val roles = messages.map { it.role }.toTypedArray()
        val contents = messages.map { it.content }.toTypedArray()
        val startedAt = System.currentTimeMillis()
        val text = LlamaCppJni.chat(
            handle = handle,
            roles = roles,
            contents = contents,
            maxTokens = normalized.maxTokens,
            temp = normalized.temp,
            topK = normalized.topK,
            topP = normalized.topP,
            penaltyLastN = normalized.penaltyLastN,
            repeatPenalty = normalized.repeatPenalty,
            seed = normalized.seed,
        )
        return LlmReply(
            text = text,
            model = "llama:" + File(modelPath).name,
            latencyMs = System.currentTimeMillis() - startedAt,
        )
    }

    override fun close() {
        if (handle != 0L) LlamaCppJni.free(handle)
    }
}
