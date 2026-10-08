package com.saeed.minikeys.keyboard

/**
 * What a key press means. The keyboard view never talks to the editor directly — it only reports
 * these intents to its [MiniKeyboardView.Listener], which keeps the layout data free of input logic.
 */
sealed interface KeyAction {

    /** [letter] keys follow the shift state; punctuation keys are emitted exactly as written. */
    data class Text(val value: String, val letter: Boolean) : KeyAction

    object Shift : KeyAction
    object Backspace : KeyAction
    object Enter : KeyAction
    object ShowSymbols : KeyAction
    object ShowLetters : KeyAction
}

/** One key: an intent, the glyph drawn on it and its relative width inside its row. */
data class KeyDef(
    val action: KeyAction,
    val label: String,
    val weight: Float = 1f,
) {
    /** Special keys get a different background, the way stock keyboards render modifier keys. */
    val special: Boolean
        get() = when (action) {
            is KeyAction.Text -> false
            KeyAction.Shift,
            KeyAction.Backspace,
            KeyAction.Enter,
            KeyAction.ShowSymbols,
            KeyAction.ShowLetters -> true
        }
}

enum class KeyboardPage { LETTERS, SYMBOLS }

/**
 * Layout data only. Adding a future module (Persian layout, voice key, emoji page…) means adding a
 * row list here — no change to the drawing or input code.
 */
object KeyboardLayouts {

    fun rowsFor(page: KeyboardPage): List<List<KeyDef>> = when (page) {
        KeyboardPage.LETTERS -> LETTERS
        KeyboardPage.SYMBOLS -> SYMBOLS
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
}