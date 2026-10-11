package com.saeed.minikeys.core.settings

/**
 * Which geometry the keyboard renders itself in.
 *
 * NORMAL  — the classic bottom strip of horizontal rows.
 * VERTICAL — two scrollable columns hugging the left and right screen edges, with the middle of
 *            the screen left free for the app (the idea from Saeed's 2026-10-09 sketch).
 */
enum class KeyboardMode(val id: String, val label: String) {
    NORMAL("normal", "Normal (bottom strip)"),
    VERTICAL("vertical", "Vertical (two side columns)");

    companion object {
        val ALL: List<KeyboardMode> = listOf(NORMAL, VERTICAL)
        val DEFAULT: KeyboardMode = NORMAL
        val DEFAULT_ID: String = DEFAULT.id

        fun byId(id: String): KeyboardMode = ALL.firstOrNull { it.id == id } ?: DEFAULT

        fun isKnownId(id: String): Boolean = ALL.any { it.id == id }
    }
}
