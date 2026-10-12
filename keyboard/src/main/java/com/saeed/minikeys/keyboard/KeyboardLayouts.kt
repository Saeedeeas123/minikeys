package com.saeed.minikeys.keyboard

import android.view.KeyEvent
import com.saeed.minikeys.perception.ActivityShortcuts

/**
 * What a key press means. The keyboard views never talk to the editor directly — they report these
 * intents to [KeyDispatcher], which keeps the layout data free of input logic.
 */
sealed interface KeyAction {

    /** [letter] keys follow the caps/shift state; punctuation keys are emitted exactly as written. */
    data class Text(val value: String, val letter: Boolean) : KeyAction

    /**
     * A raw key event, exactly as a hardware keyboard would send it — modifiers included.
     *
     * This is what makes the edge overlay behave like a keyboard plugged into the USB port: the key
     * event goes to whichever app owns the focused field, so if that app handles the combination it
     * works, and if it does not, nothing happens (which is also what a real keyboard does).
     */
    data class Hardware(val keyCode: Int, val metaState: Int = 0) : KeyAction

    /** One-shot shift (horizontal keyboard); caps lock is [CapsToggle] (vertical overlay). */
    object Shift : KeyAction

    /** Caps lock: the whole alphabet is displayed — and emitted — in upper case until tapped again. */
    object CapsToggle : KeyAction

    object Backspace : KeyAction
    object Enter : KeyAction

    object ShowSymbols : KeyAction
    object ShowLetters : KeyAction
    object ShowEmoji : KeyAction
    object ShowShortcuts : KeyAction

    /**
     * An **activity key**: run the function with this index of the activity that is currently in
     * front, through the screen reader — the real element is pressed, nothing is typed.
     *
     * This is the whole point of the shortcut page being per-activity: the page's content is not a
     * constant list any more, it is whatever the app in front offers (see [KeyboardLayouts]).
     */
    data class RunActivityFunction(val index: Int) : KeyAction
}

/** One key: an intent, the glyph drawn on it and its relative width inside its row. */
data class KeyDef(
    val action: KeyAction,
    val label: String,
    val weight: Float = 1f,
) {
    /** Special keys get a different background, the way stock keyboards render modifier keys. */
    val special: Boolean
        get() = action !is KeyAction.Text
}

enum class KeyboardPage { LETTERS, SYMBOLS, EMOJI, SHORTCUTS }

/**
 * Layout data only. Two geometries read from here:
 *
 *  - the horizontal keyboard (`MiniKeyboardView`) uses [rowsFor], row by row;
 *  - the vertical edge overlay uses [verticalKeys], ONE flat sequence that is then dealt out
 *    alternately: first key left, second key right, third key left … so the two screen edges together
 *    reproduce the whole keyboard.
 *
 * Adding a page means adding a content list here — no drawing or input code changes.
 */
object KeyboardLayouts {

    // --- horizontal geometry ---------------------------------------------------------------------

    fun rowsFor(page: KeyboardPage): List<List<KeyDef>> = when (page) {
        KeyboardPage.LETTERS -> LETTERS
        KeyboardPage.SYMBOLS -> SYMBOLS
        // Not used by the horizontal keyboard today; chunked so it degrades instead of crashing.
        KeyboardPage.EMOJI -> EMOJI.chunked(KEYS_PER_HORIZONTAL_ROW)
        KeyboardPage.SHORTCUTS -> shortcutKeys().chunked(KEYS_PER_HORIZONTAL_ROW)
    }

    private fun letter(ch: String) = KeyDef(KeyAction.Text(ch, letter = true), ch)

    private fun symbol(text: String) = KeyDef(KeyAction.Text(text, letter = false), text)

    private fun space(weight: Float) = KeyDef(KeyAction.Text(" ", letter = false), "", weight)

    private val LETTERS: List<List<KeyDef>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map(::letter),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map(::letter),
        listOf(
            KeyDef(KeyAction.Shift, "⇧", 1.5f),
            letter("z"), letter("x"), letter("c"), letter("v"), letter("b"), letter("n"), letter("m"),
            KeyDef(KeyAction.Backspace, "⌫", 1.5f),
        ),
        listOf(
            KeyDef(KeyAction.ShowSymbols, "?123", 1.5f),
            symbol(","),
            space(4f),
            symbol("."),
            KeyDef(KeyAction.Enter, "⏎", 1.5f),
        ),
    )

    private val SYMBOLS: List<List<KeyDef>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map(::symbol),
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/").map(::symbol),
        listOf("*", "\"", "'", ":", ";", "!", "?").map(::symbol) +
            KeyDef(KeyAction.Backspace, "⌫", 1.5f),
        listOf(
            KeyDef(KeyAction.ShowLetters, "ABC", 1.5f),
            symbol(","),
            space(4f),
            symbol("."),
            KeyDef(KeyAction.Enter, "⏎", 1.5f),
        ),
    )

    // --- vertical geometry (edge overlay) --------------------------------------------------------

    /**
     * The whole page as ONE sequence, in reading order of a normal QWERTY keyboard:
     * content first (letters, or numbers and symbols, or emoji, or shortcuts), then the function
     * keys, and finally the four page keys. The overlay deals this out left/right alternately, so
     * index 0 (the top-left key) is the first key of the page.
     */
    fun verticalKeys(page: KeyboardPage, caps: Boolean): List<KeyDef> =
        contentFor(page, caps) + functionKeys(page)

    private fun contentFor(page: KeyboardPage, caps: Boolean): List<KeyDef> = when (page) {
        KeyboardPage.LETTERS -> QWERTY_ORDER.map { ch ->
            val text = (if (caps) ch.uppercaseChar() else ch).toString()
            KeyDef(KeyAction.Text(text, letter = true), text)
        }

        KeyboardPage.SYMBOLS -> SYMBOL_ORDER.map(::symbol)
        KeyboardPage.EMOJI -> EMOJI
        KeyboardPage.SHORTCUTS -> shortcutKeys()
    }

    /**
     * The shortcut page: **the activity's own keys first, then the fixed Windows combinations.**
     *
     * The top block is rebuilt from whatever app is in front (see `ActivityShortcuts`), so pressing
     * the ⌃ key shows the actions of the screen you are on — "Send", "Switch model", "Terminal" — and
     * the keyboard's key set changes with the activity. The Windows combinations stay below as the
     * constants that are always available, and when the screen reader is off the page is exactly what
     * it was before: only those constants.
     */
    private fun shortcutKeys(): List<KeyDef> {
        val activity = ActivityShortcuts.keys.map { item ->
            // Wider than a letter key: activity labels are words ("Switch model"), not glyphs.
            KeyDef(KeyAction.RunActivityFunction(item.index), item.label, weight = 2f)
        }
        return activity + WINDOWS_SHORTCUTS
    }

    /** Function keys, in the order they were asked for: space, backspace, delete, enter, … */
    private fun functionKeys(page: KeyboardPage): List<KeyDef> = listOf(
        KeyDef(KeyAction.Text(" ", letter = false), "space"),
        KeyDef(KeyAction.Backspace, "⌫"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_FORWARD_DEL), "del"),
        KeyDef(KeyAction.Enter, "⏎"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_TAB), "tab"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_ESCAPE), "esc"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_DPAD_LEFT), "←"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_DPAD_RIGHT), "→"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_DPAD_UP), "↑"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_DPAD_DOWN), "↓"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_MOVE_HOME), "home"),
        KeyDef(KeyAction.Hardware(KeyEvent.KEYCODE_MOVE_END), "end"),
        KeyDef(KeyAction.CapsToggle, "⇪"),
        pageKey(page),
        KeyDef(KeyAction.ShowEmoji, "☺"),
        KeyDef(KeyAction.ShowShortcuts, "⌃"),
    )

    private fun pageKey(page: KeyboardPage): KeyDef = when (page) {
        KeyboardPage.LETTERS -> KeyDef(KeyAction.ShowSymbols, "?123")
        else -> KeyDef(KeyAction.ShowLetters, "abc")
    }

    private const val KEYS_PER_HORIZONTAL_ROW = 8

    /** QWERTY order, which is what the alternating edges reproduce: q left, w right, e left, r right… */
    private const val QWERTY_ORDER = "qwertyuiopasdfghjklzxcvbnm"

    /** Digits first, then punctuation — dealt out alternately (1 left, 2 right, 3 left, 4 right…). */
    private val SYMBOL_ORDER: List<String> = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "-", "/", ":", ";", "(", ")", "$", "&", "@", "\"",
        ".", ",", "?", "!", "'", "+", "=", "*", "%", "#",
        "[", "]", "{", "}", "<", ">", "~", "^", "_", "|",
    )

    private val EMOJI: List<KeyDef> = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃", "😉",
        "😊", "😇", "🥰", "😍", "😘", "😋", "😜", "🤪", "🤗", "🤔",
        "🤨", "😐", "😑", "😴", "😎", "🥳", "😭", "😢", "😡", "🤯",
        "🥺", "😱", "👍", "👎", "👏", "🙏", "💪", "🔥", "✨", "🎉",
        "❤", "💔", "✅", "❌", "⭐", "⚡", "🚀", "☕", "🍕", "🎵",
        "📌", "💡", "🔔", "📷", "📎", "🕐", "➡", "⬅", "⬆", "⬇",
    ).map { KeyDef(KeyAction.Text(it, letter = false), it) }

    /**
     * The common Windows shortcut combinations, implemented in full and independently of what the
     * current activity happens to be. Each one is sent as a real key event with its modifier state;
     * if the app in front handles it, it works, and if it does not, nothing happens.
     *
     * These are the *constants* of the shortcut page; the activity's own keys are added above them by
     * [shortcutKeys].
     */
    private val WINDOWS_SHORTCUTS: List<KeyDef> = listOf(
        combo("⌃A", KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
        combo("⌃C", KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
        combo("⌃V", KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
        combo("⌃X", KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON),
        combo("⌃Z", KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
        combo("⌃Y", KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON),
        combo("⌃S", KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON),
        combo("⌃F", KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
        combo("⌃P", KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON),
        combo("⌃N", KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
        combo("⌃O", KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON),
        combo("⌃W", KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON),
        combo("⌃T", KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON),
        combo("⌃R", KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON),
        combo("⌃B", KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON),
        combo("⌃I", KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON),
        combo("⌃U", KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON),
        combo("⌃H", KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON),
        combo("⌃K", KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON),
        combo("⌃D", KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON),
        combo("⌃L", KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON),
        combo("⌃E", KeyEvent.KEYCODE_E, KeyEvent.META_CTRL_ON),
        combo("⌃G", KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON),
        combo("⌃J", KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON),
        combo("⌃Q", KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON),
        combo("⌃⇧Z", KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        combo("⌃⇧S", KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        combo("⌃⇧N", KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        combo("⌃⇧T", KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        combo("⌃⇧V", KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        combo("⌃⇧esc", KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        combo("⌃esc", KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON),
        combo("⌃←", KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON),
        combo("⌃→", KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON),
        combo("⌃↑", KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON),
        combo("⌃↓", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_CTRL_ON),
        combo("⇧←", KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON),
        combo("⇧→", KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON),
        combo("⇧↑", KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_SHIFT_ON),
        combo("⇧↓", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_SHIFT_ON),
        combo("⇧home", KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_SHIFT_ON),
        combo("⇧end", KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_SHIFT_ON),
        combo("⌃home", KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON),
        combo("⌃end", KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON),
        combo("⌃+", KeyEvent.KEYCODE_PLUS, KeyEvent.META_CTRL_ON),
        combo("⌃-", KeyEvent.KEYCODE_MINUS, KeyEvent.META_CTRL_ON),
        combo("⌃0", KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON),
        combo("F5", KeyEvent.KEYCODE_F5, 0),
        combo("Alt+Tab", KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON),
        combo("Alt+F4", KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON),
        combo("Alt+←", KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_ALT_ON),
        combo("Alt+→", KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_ALT_ON),
    )

    private fun combo(label: String, keyCode: Int, metaState: Int) =
        KeyDef(KeyAction.Hardware(keyCode, metaState), label)
}