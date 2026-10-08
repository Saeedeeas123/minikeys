package com.saeed.minikeys.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** Whether MiniKeys is available to the system and currently chosen. */
data class ImeStatus(
    val enabled: Boolean,
    val selected: Boolean,
)

/**
 * Thin wrapper over the only public APIs Android exposes for this: the enabled-IME list and the
 * secure setting that names the active IME. Everything is defensive — a ROM refusing to hand out
 * the value must never crash the settings screen.
 */
object MiniKeysImeStatus {

    fun read(context: Context): ImeStatus = ImeStatus(
        enabled = isEnabled(context),
        selected = isSelected(context),
    )

    fun isEnabled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.enabledInputMethodList.any { it.packageName == context.packageName }
    }.getOrDefault(false)

    fun isSelected(context: Context): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(context.packageName) == true
    }.getOrDefault(false)

    /** System screen where the user ticks MiniKeys in the keyboard list. */
    fun openSystemKeyboardSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Opens the "use this keyboard" chooser the system shows for switching IMEs. */
    fun showKeyboardPicker(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showInputMethodPicker()
        }
    }
}