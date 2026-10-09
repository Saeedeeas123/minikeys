package com.saeed.minikeys.core.llm

/**
 * One inference surface with one job: turn chat messages into a reply.
 *
 * Implementations so far: [CloudLlmEngine] (OpenAI-compatible HTTP) and, in :core:mediapipe,
 * a fully offline engine. The keyboard's AI features only ever see this interface.
 */
interface LlmEngine {
    fun chat(messages: List<ChatMessage>): LlmReply
}

/** Cloud engine: an OpenAI-compatible endpoint whose configuration is fixed at construction. */
class CloudLlmEngine(private val config: LlmConfig) : LlmEngine {

    private val client = OpenAiCompatibleClient()

    override fun chat(messages: List<ChatMessage>): LlmReply = client.chat(config, messages)
}