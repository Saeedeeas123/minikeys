package com.saeed.minikeys.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the OpenAI-compatible client. Response parsing (org.json) is device-only and
 * covered by the on-device test button instead.
 */
class OpenAiCompatibleClientTest {

    private val client = OpenAiCompatibleClient()

    @Test
    fun `bare host gets the standard v1 path`() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", client.chatUrl("https://api.deepseek.com"))
    }

    @Test
    fun `provider paths are respected without adding a second v1`() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions", client.chatUrl("https://openrouter.ai/api/v1"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            client.chatUrl("https://generativelanguage.googleapis.com/v1beta/openai"),
        )
        assertEquals("https://api.groq.com/openai/v1/chat/completions", client.chatUrl("https://api.groq.com/openai/v1/"))
    }

    @Test
    fun `a full endpoint url is used as-is`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            client.chatUrl("https://api.deepseek.com/chat/completions"),
        )
    }

    @Test
    fun `all shipped presets resolve to plausible endpoints`() {
        LlmProviderPreset.ALL.forEach { preset ->
            val url = client.chatUrl(preset.baseUrl)
            assertTrue("preset ${preset.id} -> $url", url.endsWith("/chat/completions"))
            assertTrue("preset ${preset.id} must keep the host", url.startsWith("http"))
        }
    }

    @Test
    fun `request body escapes quotes newlines and backslashes`() {
        val body = client.buildRequestBody(
            model = "deepseek-flash",
            messages = listOf(
                ChatMessage("system", "Say \"hi\"\nLine2 \\ done"),
                ChatMessage("user", "Tab\there"),
            ),
        )

        assertEquals(
            "{\"model\":\"deepseek-flash\",\"messages\":" +
                "[{\"role\":\"system\",\"content\":\"Say \\\"hi\\\"\\nLine2 \\\\ done\"}," +
                "{\"role\":\"user\",\"content\":\"Tab\\there\"}]}",
            body,
        )
    }

    @Test
    fun `control characters are unicode-escaped`() {
        assertEquals("\"a\\u0001b\"", client.jsonString("a\u0001b"))
    }
}