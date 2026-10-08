package com.saeed.minikeys.core.llm

/** Wire configuration for an OpenAI-compatible chat endpoint. */
data class LlmConfig(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
) {
    fun isComplete(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()
}

/** One chat message; roles follow the OpenAI schema ("system" / "user" / "assistant"). */
data class ChatMessage(
    val role: String,
    val content: String,
)

/** Result of a successful call. */
data class LlmReply(
    val text: String,
    val model: String,
    val latencyMs: Long,
)

/**
 * Curated presets of endpoints that accept a plain Bearer API key and speak the OpenAI
 * /chat/completions schema — cloud providers and local servers alike. Example models are editable
 * defaults, not recommendations frozen in time.
 */
data class LlmProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val exampleModel: String,
    val note: String,
) {
    companion object {

        val ALL: List<LlmProviderPreset> = listOf(
            LlmProviderPreset(
                id = "deepseek",
                label = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                exampleModel = "deepseek-flash",
                note = "Officially OpenAI-compatible (verified against DeepSeek docs 2026-10-08)",
            ),
            LlmProviderPreset(
                id = "openrouter",
                label = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                exampleModel = "meta-llama/llama-3.3-70b-instruct",
                note = "One key, hundreds of models",
            ),
            LlmProviderPreset(
                id = "openai",
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                exampleModel = "gpt-4o-mini",
                note = "Reference implementation of the schema",
            ),
            LlmProviderPreset(
                id = "groq",
                label = "Groq",
                baseUrl = "https://api.groq.com/openai/v1",
                exampleModel = "llama-3.3-70b-versatile",
                note = "Very fast open-model inference",
            ),
            LlmProviderPreset(
                id = "mistral",
                label = "Mistral",
                baseUrl = "https://api.mistral.ai/v1",
                exampleModel = "mistral-small-latest",
                note = "European provider",
            ),
            LlmProviderPreset(
                id = "xai",
                label = "xAI (Grok)",
                baseUrl = "https://api.x.ai/v1",
                exampleModel = "grok-3-mini",
                note = "Grok models",
            ),
            LlmProviderPreset(
                id = "together",
                label = "Together AI",
                baseUrl = "https://api.together.xyz/v1",
                exampleModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                note = "Open-model hosting",
            ),
            LlmProviderPreset(
                id = "gemini",
                label = "Google Gemini (OpenAI-compat)",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                exampleModel = "gemini-2.0-flash",
                note = "Google's OpenAI-compatible surface",
            ),
            LlmProviderPreset(
                id = "cerebras",
                label = "Cerebras",
                baseUrl = "https://api.cerebras.ai/v1",
                exampleModel = "llama3.1-8b",
                note = "Wafer-scale speed",
            ),
            LlmProviderPreset(
                id = "local-llamacpp",
                label = "Local llama.cpp server",
                baseUrl = "http://127.0.0.1:8080/v1",
                exampleModel = "local-model",
                note = "llama.cpp built with its OpenAI-compatible server",
            ),
            LlmProviderPreset(
                id = "local-ollama",
                label = "Local Ollama",
                baseUrl = "http://127.0.0.1:11434/v1",
                exampleModel = "qwen2.5:0.5b",
                note = "Ollama's OpenAI-compatible endpoint",
            ),
            LlmProviderPreset(
                id = "local-lan-lmstudio",
                label = "Local LM Studio (LAN)",
                baseUrl = "http://192.168.1.10:1234/v1",
                exampleModel = "local-model",
                note = "Edit the IP to your PC/other device",
            ),
        )

        fun byId(id: String): LlmProviderPreset? = ALL.firstOrNull { it.id == id }
    }
}