package com.saeed.minikeys.core.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for [OverlaySettings]. Writes are synchronous (`commit`) so a read-back always tells
 * the truth — the settings screen shows the value that really landed on disk.
 */
class OverlaySettingsStore private constructor(
    private val prefs: SharedPreferences,
) {

    fun load(): OverlaySettings = OverlayPrefsCodec.decode(rawValues())

    fun save(settings: OverlaySettings) {
        val normalized = settings.normalized()
        prefs.edit().apply {
            OverlayPrefsCodec.encode(normalized).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                }
            }
        }.commit()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(OverlayPrefsCodec.KEY_ENABLED, enabled).commit()
    }

    fun reset() {
        prefs.edit().clear().commit()
    }

    fun rawValues(): Map<String, *> = prefs.all

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "minikeys_overlay"

        fun from(context: Context): OverlaySettingsStore =
            OverlaySettingsStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
