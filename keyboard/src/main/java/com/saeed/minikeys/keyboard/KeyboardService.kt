package com.saeed.minikeys.keyboard

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.saeed.minikeys.core.settings.KeyboardSettingsStore
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.core.settings.MiniKeysRuntime
import com.saeed.minikeys.perception.ActivityShortcuts

/**
 * The IME. It has two faces, decided by whether the always-on edge overlay is attached:
 *
 *  - overlay OFF → the normal bottom keyboard ([MiniKeyboardView]) appears exactly as before.
 *  - overlay ON  → the strips on the screen edges ARE the keyboard, so this service renders an
 *    EMPTY, zero-height input view: no second keyboard slides up under the strips. The service stays
 *    bound to the focused field all the same, because it owns the `InputConnection` that the strips
 *    type through ([KeyDispatcher]).
 *
 * That split is the whole trick: an overlay window can never type into another app, and an IME can
 * never leave the middle of the screen interactive — together they cover both halves.
 */
class KeyboardService : InputMethodService(), MiniKeyboardView.Listener {

    private lateinit var store: KeyboardSettingsStore
    private var keyboardView: MiniKeyboardView? = null

    /** Lets the settings UI retheme/resize the keyboard live, without restarting the IME. */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applySavedSettings()
    }

    /** The edge overlay appeared or disappeared → swap between the silent and the visible face. */
    private val runtimeListener: () -> Unit = { refreshInputView() }

    /**
     * The activity in front changed, so the shortcut page has a different key set. The visible
     * keyboard must rebuild its geometry — the keys themselves are different, not just their colour.
     */
    private val activityKeysListener: () -> Unit = {
        activityKeysListenerTarget()?.refreshKeys()
    }

    private fun activityKeysListenerTarget(): MiniKeyboardView? = keyboardView

    override fun onCreate() {
        super.onCreate()
        MiniKeysLog.attach(this)
        store = KeyboardSettingsStore.from(this)
        store.addListener(settingsListener)
        MiniKeysRuntime.addListener(runtimeListener)
        ActivityShortcuts.addListener(activityKeysListener)
        MiniKeysLog.line("IME: service created (overlay attached=" + MiniKeysRuntime.overlayAttached + ")")
    }

    override fun onCreateInputView(): View {
        val view = buildInputView()
        keyboardView = view as? MiniKeyboardView
        applySavedSettings()
        return view
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        KeyDispatcher.attach(currentInputConnection)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.setEnterLabel(enterLabelFor(info))
        applySavedSettings()
        KeyDispatcher.attach(currentInputConnection)
    }

    override fun onFinishInput() {
        KeyDispatcher.attach(null)
        super.onFinishInput()
    }

    override fun onDestroy() {
        KeyDispatcher.attach(null)
        MiniKeysRuntime.removeListener(runtimeListener)
        ActivityShortcuts.removeListener(activityKeysListener)
        runCatching { store.removeListener(settingsListener) }
        keyboardView = null
        MiniKeysLog.line("IME: service destroyed")
        super.onDestroy()
    }

    /** Never take over the whole screen; the app stays visible behind the keyboard. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    // --- MiniKeyboardView.Listener (the horizontal keyboard only) --------------------------------

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

    /**
     * An activity key: the app in front has its real element pressed through the screen reader — the
     * keyboard offers the current activity's actions as keys. Nothing is typed.
     */
    override fun onActivityFunction(index: Int) {
        val result = KeyDispatcher.dispatch(KeyAction.RunActivityFunction(index))
        MiniKeysLog.line("IME: activity key #$index -> $result")
    }

    // --- internals ----------------------------------------------------------------------------

    /**
     * The empty face: a view ONE pixel tall. Not zero: a zero-height input view can make the framework
     * treat the IME as not shown and end the input session, which would take the `InputConnection` away
     * and the strips would stop typing. The IME window background is transparent, so one pixel of it is
     * invisible on screen while the session stays alive and the strips can type through it.
     */
    private fun buildInputView(): View = if (MiniKeysRuntime.overlayAttached) {
        View(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
    } else {
        MiniKeyboardView(this).also { it.listener = this }
    }

    private fun refreshInputView() {
        val view = buildInputView()
        keyboardView = view as? MiniKeyboardView
        setInputView(view)
        applySavedSettings()
        MiniKeysLog.line(
            "IME: input view -> " +
                if (MiniKeysRuntime.overlayAttached) "silent (edge overlay is the keyboard)" else "visible keyboard",
        )
    }

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