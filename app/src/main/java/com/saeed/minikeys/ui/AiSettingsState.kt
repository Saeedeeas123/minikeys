package com.saeed.minikeys.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saeed.minikeys.core.llm.ChatMessage
import com.saeed.minikeys.core.llm.LlmConfig
import com.saeed.minikeys.core.llm.LlmConfigStore
import com.saeed.minikeys.core.llm.LlmKeyVault
import com.saeed.minikeys.core.llm.LlmProviderPreset
import com.saeed.minikeys.core.llm.OpenAiCompatibleClient

/**
 * State of the AI provider card.
 *
 * The connectivity test runs on a background thread (network calls must never touch the main
 * thread); the result is written into Compose state, which is safe to update from any thread.
 */
class AiSettingsState(context: Context) {

    private val appContext: Context = context.applicationContext
    private val configStore = LlmConfigStore(appContext)
    private val keyVault = LlmKeyVault(appContext)
    private val client = OpenAiCompatibleClient()

    private val stored = configStore.load()

    var baseUrl: String by mutableStateOf(stored.baseUrl)
    var model: String by mutableStateOf(stored.model)
    var apiKey: String by mutableStateOf(keyVault.load())

    var testing: Boolean by mutableStateOf(false)
        private set

    var testStatus: String by mutableStateOf(defaultStatus(stored.baseUrl))
        private set

    fun applyPreset(preset: LlmProviderPreset) {
        baseUrl = preset.baseUrl
        if (model.isBlank()) model = preset.exampleModel
    }

    fun saveConfig() {
        configStore.save(baseUrl, model)
        keyVault.save(apiKey)
    }

    fun runTest() {
        if (testing) return
        val config = LlmConfig(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            apiKey = apiKey.trim(),
        )
        if (!config.isComplete()) {
            testStatus = "Fill in base URL and model first."
            return
        }

        saveConfig()
        testing = true
        testStatus = "Testing ${config.model} @ ${config.baseUrl} …"

        Thread {
            val result = try {
                val reply = client.chat(
                    config = config,
                    messages = listOf(
                        ChatMessage("system", "You are the AI of the MiniKeys keyboard. Answer in one short sentence."),
                        ChatMessage("user", "Reply with exactly: OK"),
                    ),
                )
                "CONNECTED — ${reply.model} answered in ${reply.latencyMs} ms: " +
                    "\"${reply.text.trim().take(120)}\""
            } catch (e: Exception) {
                "FAILED — ${e.message ?: e.javaClass.simpleName}"
            }
            testStatus = result
            testing = false
        }.start()
    }

    private fun defaultStatus(url: String): String =
        if (url.isBlank()) "No provider configured yet — pick a preset or type a base URL." else ""
}