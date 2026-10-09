package com.saeed.minikeys.core.mediapipe

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.saeed.minikeys.core.llm.ChatMessage
import com.saeed.minikeys.core.llm.LlmEngine
import com.saeed.minikeys.core.llm.LlmReply
import java.io.File

/**
 * Fully offline inference through MediaPipe's LlmInference (MediaPipe Tasks GenAI).
 *
 * API verified from the cached 0.10.27 AAR (javap, 2026-10-09): createFromOptions(Context, Options)
 * with setModelPath/setMaxTokens/setPreferredBackend, sync generateResponse(String), close().
 *
 * NOT thread-safe upstream — [chat] is synchronized. Loading the model is slow (seconds): keep the
 * engine alive (see LocalLlmRuntime) instead of recreating it per message.
 */
class MediapipeLlmEngine(
    context: Context,
    val modelPath: String,
    maxTokens: Int = 512,
) : LlmEngine, AutoCloseable {

    private val llm: LlmInference

    init {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .build()
        llm = LlmInference.createFromOptions(context.applicationContext, options)
    }

    override fun chat(messages: List<ChatMessage>): LlmReply {
        val prompt = LocalPromptBuilder.toPrompt(messages)
        val startedAt = System.currentTimeMillis()
        synchronized(llm) {
            val text = llm.generateResponse(prompt)
            return LlmReply(
                text = text,
                model = "local:" + File(modelPath).name,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    override fun close() {
        llm.close()
    }
}

/**
 * Plain-text chat rendering for engines without built-in templates. MediaPipe applies the model's
 * own template when the bundle contains one; this builder only guarantees the roles are visible.
 */
object LocalPromptBuilder {
    fun toPrompt(messages: List<ChatMessage>): String = buildString {
        messages.forEach { message ->
            when (message.role.lowercase()) {
                "system" -> {
                    append("System: ")
                    appendLine(message.content)
                }

                "user" -> {
                    append("User: ")
                    appendLine(message.content)
                }

                "assistant" -> {
                    append("Assistant: ")
                    appendLine(message.content)
                }

                else -> appendLine(message.content)
            }
        }
        if (messages.lastOrNull()?.role?.lowercase() == "user") {
            append("Assistant:")
        }
    }
}