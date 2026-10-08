package com.saeed.minikeys.core.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the non-secret part of the LLM configuration. The API key deliberately does NOT live
 * here — [LlmKeyVault] encrypts it with the Android Keystore instead.
 */
class LlmConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
    )

    fun save(baseUrl: String, model: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_MODEL, model.trim())
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "minikeys_llm_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
    }
}