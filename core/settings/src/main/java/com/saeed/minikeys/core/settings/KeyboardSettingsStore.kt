package com.saeed.minikeys.core.settings

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** What is actually on disk right now — rendered by the "Saved settings" panel. */
data class KeyboardSettingsSnapshot(
    val settings: KeyboardSettings,
    val rawEntries: Map<String, String>,
    val filePath: String,
    val fileExists: Boolean,
    val savedAtMillis: Long,
)

/**
 * The only writer of keyboard settings. Both the IME service and the settings UI build the same
 * store (same package ⇒ same SharedPreferences file), so a change is visible to the keyboard
 * immediately.
 */
class KeyboardSettingsStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): KeyboardSettings = KeyboardPrefsCodec.decode(prefs.all)

    /** Synchronous write on purpose: the UI can prove persistence immediately after this returns. */
    fun save(settings: KeyboardSettings) {
        val editor = prefs.edit()
        KeyboardPrefsCodec.encode(settings.normalized()).forEach { (key, value) ->
            when (value) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> editor.putString(key, value.toString())
            }
        }
        editor.commit()
    }

    fun reset() {
        prefs.edit().clear().commit()
    }

    fun snapshot(): KeyboardSettingsSnapshot {
        val file = prefsFile()
        return KeyboardSettingsSnapshot(
            settings = load(),
            rawEntries = prefs.all.entries
                .sortedBy { it.key }
                .associate { it.key to (it.value?.toString() ?: "null") },
            filePath = file.path,
            fileExists = file.exists(),
            savedAtMillis = if (file.exists()) file.lastModified() else 0L,
        )
    }

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefsFile(): File =
        File(File(appContext.filesDir.parentFile, "shared_prefs"), "$PREFS_NAME.xml")

    companion object {
        const val PREFS_NAME = "minikeys_settings"

        @Volatile
        private var instance: KeyboardSettingsStore? = null

        fun from(context: Context): KeyboardSettingsStore =
            instance ?: synchronized(this) {
                instance ?: KeyboardSettingsStore(context).also { instance = it }
            }
    }
}
