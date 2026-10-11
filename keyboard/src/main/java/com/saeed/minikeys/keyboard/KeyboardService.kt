package com.saeed.minikeys.keyboard

import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.saeed.minikeys.core.settings.KeyboardMode
import com.saeed.minikeys.core.settings.KeyboardSettingsStore
import com.saeed.minikeys.core.settings.MiniKeysLog

/**
 * The IME itself.
 *
 * It owns no layout code: it builds the keyboard view that matches the stored mode — the classic
 * [MiniKeyboardView] strip, or the two-column [EdgeKeyboardView] — keeps it in sync with the
 * persisted settings (including changes made while the keyboard is on screen) and applies the
 * resulting text edits to the editor through the input connection.
 *
 * Vertical mode also reshapes the IME window: full height, and a touchable region that covers ONLY
 * the two side columns, so the middle of the screen stays usable by the app behind the keyboard.
 */
class KeyboardService : InputMethodService(), MiniKeyboardView.Listener {

    private lateinit var store: KeyboardSettingsStore
    private var currentView: View? = null
    private var currentMode: KeyboardMode? = null

    /** Lets the settings UI retheme/resize/reswitch the keyboard live, without restarting the IME. */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applySavedSettings()
    }

    override fun onCreate() {
        super.onCreate()
        store = KeyboardSettingsStore.from(this)
        store.addListener(settingsListener)
    }

    override fun onCreateInputView(): View {
        val settings = store.load()
        val view = createViewFor(settings.mode)
        currentView = view
        currentMode = settings.mode
        applySettingsToCurrentView()
        return view
    }

    private fun createViewFor(mode: KeyboardMode): View = when (mode) {
        KeyboardMode.VERTICAL -> EdgeKeyboardView(this).also { it.listener = this }
        KeyboardMode.NORMAL -> MiniKeyboardView(this).also { it.listener = this }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setEnterLabel(enterLabelFor(info))
        applySavedSettings()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applyWindowShape()
        // The first insets pass can run before the view has a real size: re-apply once the layout
        // has settled so the two-strip touchable region comes from real measurements.
        window?.window?.decorView?.postDelayed({ applyWindowShape() }, 250L)
    }

    override fun onDestroy() {
        store.removeListener(settingsListener)
        currentView = null
        currentMode = null
        super.onDestroy()
    }

    /** Never take over the whole screen with the extract view; the app stays visible behind. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** The vertical keyboard needs the whole screen; the normal keyboard keeps its strip height. */
    private fun applyWindowShape() {
        val params = window?.window?.attributes ?: return
        val vertical = currentMode == KeyboardMode.VERTICAL
        params.height = if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        window?.window?.attributes = params
        window?.window?.decorView?.requestLayout()
        MiniKeysLog.line(
            "VERTICAL window: mode=" + currentMode + " h=" + params.height +
                " flags=0x" + Integer.toHexString(params.flags)
        )
    }

    /**
     * Only the two side columns are touchable in vertical mode: the middle of the screen keeps
     * receiving touches, so the app behind the keyboard remains usable while typing.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val settings = store.load()
        if (settings.mode != KeyboardMode.VERTICAL) return
        val metrics = resources.displayMetrics
        val view = currentView
        // Real measurements when the view is attached; screen size before the first layout, so the
        // two strips are right from the very first insets pass (an empty region would mean
        // whole-window touchable and the middle would swallow taps).
        val width = view?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = view?.height?.takeIf { it > 0 } ?: metrics.heightPixels
        if (width <= 0 || height <= 0) return
        val margin = (metrics.density * 6f).toInt()
        val columnPx = (width * settings.columnWidthPercent / 100f).toInt() + margin
        val region: Region = outInsets.touchableRegion
        region.setEmpty()
        region.union(Rect(0, 0, columnPx, height))
        region.union(Rect(width - columnPx, 0, width, height))
        MiniKeysLog.line(
            "VERTICAL insets: viewW=" + (view?.width ?: 0) + " usedW=" + width +
                " usedH=" + height + " strip=" + columnPx + "px " +
                settings.columnWidthPercent + "% bounds=" + region.bounds
        )
        // The app is considered fully visible: the keyboard only overlays the two side strips.
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
    }

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
        val settings = store.load()
        if (currentMode != null && currentMode != settings.mode) {
            // Mode switch while the keyboard is on screen: swap the whole input view.
            val view = createViewFor(settings.mode)
            currentView = view
            currentMode = settings.mode
            setInputView(view)
            applyWindowShape()
        }
        applySettingsToCurrentView()
    }

    private fun applySettingsToCurrentView() {
        val settings = store.load()
        when (val view = currentView) {
            is MiniKeyboardView -> view.applySettings(settings)
            is EdgeKeyboardView -> view.applySettings(settings)
        }
        if (isInputViewShown) {
            currentView?.requestLayout()
            window?.window?.decorView?.let {
                it.requestLayout()
                it.invalidate()
            }
        }
    }

    private fun setEnterLabel(label: String) {
        when (val view = currentView) {
            is MiniKeyboardView -> view.setEnterLabel(label)
            is EdgeKeyboardView -> view.setEnterLabel(label)
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
