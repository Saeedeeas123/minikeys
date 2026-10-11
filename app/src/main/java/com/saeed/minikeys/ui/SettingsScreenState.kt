package com.saeed.minikeys.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saeed.minikeys.core.settings.KeyboardSettings
import com.saeed.minikeys.core.settings.KeyboardSettingsSnapshot
import com.saeed.minikeys.core.settings.KeyboardSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen state holder.
 *
 * Deliberately not a ViewModel: it needs no coroutines and no lifecycle scope, because every write
 * goes through [KeyboardSettingsStore], which commits synchronously. After each write the screen
 * re-reads what is actually on disk — so what the user sees is never an optimistic guess.
 */
class SettingsScreenState(context: Context) {

    private val appContext: Context = context.applicationContext

    private val store: KeyboardSettingsStore = KeyboardSettingsStore.from(appContext)

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    var settings: KeyboardSettings by mutableStateOf(store.load())
        private set

    var savedSnapshot: KeyboardSettingsSnapshot by mutableStateOf(store.snapshot())
        private set

    var imeStatus: ImeStatus by mutableStateOf(ImeStatus(enabled = false, selected = false))
        private set

    fun selectTheme(themeId: String) = persist(settings.copy(themeId = themeId))

    fun setKeyboardHeightDp(heightDp: Int) = persist(settings.copy(keyboardHeightDp = heightDp))

    fun setKeyTextSizeSp(textSizeSp: Int) = persist(settings.copy(keyTextSizeSp = textSizeSp))

    fun resetToDefaults() {
        store.reset()
        refreshFromDisk()
    }

    /** Re-reads the preferences file: this is what proves the change really landed on disk. */
    fun refreshFromDisk() {
        settings = store.load()
        savedSnapshot = store.snapshot()
    }

    fun refreshImeStatus() {
        imeStatus = MiniKeysImeStatus.read(appContext)
    }

    fun openSystemKeyboardSettings() = MiniKeysImeStatus.openSystemKeyboardSettings(appContext)

    fun showKeyboardPicker() = MiniKeysImeStatus.showKeyboardPicker(appContext)

    fun savedAtText(): String =
        if (savedSnapshot.savedAtMillis <= 0L) {
            "not written yet"
        } else {
            timestampFormat.format(Date(savedSnapshot.savedAtMillis))
        }

    private fun persist(updated: KeyboardSettings) {
        store.save(updated)
        refreshFromDisk()
    }
}