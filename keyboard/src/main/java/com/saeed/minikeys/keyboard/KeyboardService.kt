package com.saeed.minikeys.keyboard

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import com.saeed.minikeys.core.settings.KeyboardSettingsStore

/**
 * The IME itself.
 *
 * It owns no layout code: it builds one [MiniKeyboardView], keeps it in sync with the persisted
 * settings (including changes made while the keyboard is on screen) and applies the resulting text
 * edits to the editor through the input connection.
 */
class KeyboardService : InputMethodService(), MiniKeyboardView.Listener {

    private lateinit var store: KeyboardSettingsStore
    private var keyboardView: MiniKeyboardView? = null

    /** Lets the settings UI retheme/resize the keyboard live, without restarting the IME. */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applySavedSettings()
    }

    override fun onCreate() {
        super.onCreate()
        store = KeyboardSettingsStore.from(this)
        store.addListener(settingsListener)
    }

    override fun onCreateInputView(): View {
        val view = MiniKeyboardView(this).also { it.listener = this }
        keyboardView = view
        applySavedSettings()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.setEnterLabel(enterLabelFor(info))
        applySavedSettings()
    }

    override fun onDestroy() {
        store.removeListener(settingsListener)
        keyboardView = null
        super.onDestroy()
    }

    /** Never take over the whole screen; the app stays visible behind the keyboard. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    // --- MiniKeyboardView.Listener -------------------------------------------------------------

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onBackspace() {
        val connection = currentInputConnection ?: return
        if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingText(1, 0)
        }
    }

    override fun onEnter() {
        val connection = currentInputConnection ?: return
        val multiline = currentInputEditorInfo?.inputType
            ?.and(InputType.TYPE_TEXT_FLAG_MULTI_LINE) == InputType.TYPE_TEXT_FLAG_MULTI_LINE

        if (multiline || !sendDefaultEditorAction(true)) {
            connection.commitText("\n", 1)
        }
    }

    // --- internals ----------------------------------------------------------------------------

    private fun applySavedSettings() {
        val view = keyboardView ?: return
        view.applySettings(store.load())

        // A height change must reach the IME window, not just the view inside it.
        if (isInputViewShown) {
            view.requestLayout()
            window?.window?.decorView?.let {
                it.requestLayout()
                it.invalidate()
            }
        }
    }

    private fun enterLabelFor(info: EditorInfo?): String {
        return when (info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> if (isMultiline(info)) "⏎" else "Done"
        }
    }

    private fun isMultiline(info: EditorInfo?): Boolean =
        info?.inputType?.and(InputType.TYPE_TEXT_FLAG_MULTI_LINE) == InputType.TYPE_TEXT_FLAG_MULTI_LINE
}