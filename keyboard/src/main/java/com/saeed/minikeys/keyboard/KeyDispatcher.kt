package com.saeed.minikeys.keyboard

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.saeed.minikeys.core.settings.MiniKeysLog
import com.saeed.minikeys.perception.ActivityShortcuts
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The single door between a key press and the app in front of us.
 *
 * The overlay's keys live in an overlay window, which by construction has no `InputConnection` — an
 * overlay can never type into another app. The connection belongs to the IME, which stays bound to
 * the focused field (and shows nothing while the strips are on screen). So the IME publishes its
 * connection here and the strips push their key intents through it.
 *
 * Two kinds of output, matching what a keyboard does:
 *  - characters and deletions go through `commitText` / `deleteSurroundingText`, which every app
 *    understands (this is what all soft keyboards do), and
 *  - everything else is sent as a REAL key event with its modifier state, so the focused app's own
 *    key handling decides: Ctrl+A selects all where that is meaningful, arrows move the caret,
 *    Enter fires the editor action, and a combination the app does not implement does nothing —
 *    exactly like a keyboard plugged into the USB port.
 */
object KeyDispatcher {

    @Volatile
    private var connection: InputConnection? = null

    /** Notified when the connection opens or closes, so a UI can say whether typing is possible. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addConnectionListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeConnectionListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Called by the IME whenever the focused editor changes. */
    fun attach(connection: InputConnection?) {
        val changed = (this.connection == null) != (connection == null)
        this.connection = connection
        MiniKeysLog.line("dispatcher: editor connection " + (if (connection == null) "closed" else "open"))
        if (changed) listeners.toList().forEach { runCatching { it() } }
    }

    /** True while a field of some other app is focused and reachable. */
    val hasConnection: Boolean get() = connection != null

    /**
     * Sends one key intent. Returns a short human-readable result — the caller writes it to the log,
     * so the log says for every press what was sent and whether the app took it.
     */
    fun dispatch(action: KeyAction): String {
        // An activity key is the one action that needs NO text field: it presses a real element of the
        // app in front through the screen reader, so it must be handled before the connection check
        // below — otherwise the keys would silently do nothing whenever the screen has no input focus.
        if (action is KeyAction.RunActivityFunction) return ActivityShortcuts.run(action.index)

        val editor = connection ?: return "no text field focused — nothing sent"

        return try {
            when (action) {
                is KeyAction.Text -> {
                    editor.commitText(action.value, 1)
                    "typed '" + action.value + "'"
                }

                KeyAction.Backspace -> {
                    if (editor.getSelectedText(0).isNullOrEmpty()) {
                        editor.deleteSurroundingText(1, 0)
                    } else {
                        editor.commitText("", 1)
                    }
                    "backspace"
                }

                KeyAction.Enter -> sendKey(editor, KeyEvent.KEYCODE_ENTER, 0)

                is KeyAction.Hardware -> sendKey(editor, action.keyCode, action.metaState)

                else -> "no input meaning (page / caps key)"
            }
        } catch (t: Throwable) {
            "FAILED " + t.javaClass.simpleName + ": " + t.message
        }
    }

    private fun sendKey(editor: InputConnection, keyCode: Int, metaState: Int): String {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState)

        val downHandled = editor.sendKeyEvent(down)
        val upHandled = editor.sendKeyEvent(up)

        val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        val combo = modifierPrefix(metaState) + name
        return if (downHandled || upHandled) {
            "sent " + combo + " (handled by the app)"
        } else {
            "sent " + combo + " (the app ignored it)"
        }
    }

    private fun modifierPrefix(metaState: Int): String = buildString {
        if (metaState and KeyEvent.META_CTRL_ON != 0) append("Ctrl+")
        if (metaState and KeyEvent.META_ALT_ON != 0) append("Alt+")
        if (metaState and KeyEvent.META_SHIFT_ON != 0) append("Shift+")
    }
}