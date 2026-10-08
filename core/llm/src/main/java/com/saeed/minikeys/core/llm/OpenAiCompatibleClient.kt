package com.saeed.minikeys.core.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

/**
 * Minimal OpenAI-compatible /chat/completions client.
 *
 * Deliberately dependency-free: HttpURLConnection and org.json are both platform-provided, so this
 * module drops into any app of the project with zero third-party cost.
 *
 * Synchronous on purpose — callers own the threading. A keyboard must never call this on the main
 * thread.
 */
class OpenAiCompatibleClient {

    fun chat(config: LlmConfig, messages: List<ChatMessage>): LlmReply {
        require(config.isComplete()) { "LLM endpoint is not configured (base URL / model missing)" }
        require(messages.isNotEmpty()) { "at least one message is required" }

        val startedAt = System.currentTimeMillis()
        val connection = URL(chatUrl(config.baseUrl)).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (config.apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }

            connection.outputStream.use { out ->
                out.write(buildRequestBody(config.model, messages).toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                throw IOException(httpErrorMessage(code, body))
            }

            return LlmReply(
                text = parseReplyText(body),
                model = config.model,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Endpoint resolution rules, kept pure so they are unit-tested:
     *  - ".../chat/completions"      -> used as-is
     *  - any provider-specific path  -> + "/chat/completions"   (…/v1, …/api/v1, …/v1beta/openai)
     *  - bare host                   -> + "/v1/chat/completions" (provider defaults, e.g. DeepSeek)
     */
    fun chatUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) return trimmed

        val hasProviderPath = try {
            URI(trimmed).path?.trim('/')?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }

        return if (hasProviderPath) "$trimmed/chat/completions" else "$trimmed/v1/chat/completions"
    }

    fun buildRequestBody(model: String, messages: List<ChatMessage>): String = buildString {
        append("{\"model\":").append(jsonString(model))
        append(",\"messages\":[")
        messages.forEachIndexed { index, message ->
            if (index > 0) append(',')
            append("{\"role\":").append(jsonString(message.role))
            append(",\"content\":").append(jsonString(message.content))
            append('}')
        }
        append("]}")
    }

    fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** org.json is platform-provided, hence device-only (not covered by JVM tests). */
    fun parseReplyText(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: throw IOException("response has no choices array")
        val first = choices.optJSONObject(0) ?: throw IOException("response has no message entry")
        return first.optJSONObject("message")?.optString("content").orEmpty()
    }

    fun httpErrorMessage(code: Int, body: String): String {
        val detail = try {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        return "HTTP $code${detail?.let { " — $it" }.orEmpty()}"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
    }
}