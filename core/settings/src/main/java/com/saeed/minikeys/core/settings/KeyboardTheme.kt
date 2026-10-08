package com.saeed.minikeys.core.settings

/**
 * A visual preset for the keyboard.
 *
 * Colors are plain ARGB ints on purpose: this model must stay free of Android framework types so
 * it is unit-testable on the JVM and reusable by any future module (settings UI, themed preview…).
 */
data class KeyboardTheme(
    val id: String,
    val label: String,
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyBackgroundPressed: Int,
    val specialKeyBackground: Int,
    val keyText: Int,
    val accent: Int,
) {
    companion object {
        val DARK = KeyboardTheme(
            id = "dark",
            label = "Dark",
            keyboardBackground = 0xFF17171C.toInt(),
            keyBackground = 0xFF2C2C34.toInt(),
            keyBackgroundPressed = 0xFF4A4A57.toInt(),
            specialKeyBackground = 0xFF212128.toInt(),
            keyText = 0xFFECEFF4.toInt(),
            accent = 0xFF6FD3FF.toInt(),
        )

        val LIGHT = KeyboardTheme(
            id = "light",
            label = "Light",
            keyboardBackground = 0xFFD6DAE3.toInt(),
            keyBackground = 0xFFFFFFFF.toInt(),
            keyBackgroundPressed = 0xFFB4BDCC.toInt(),
            specialKeyBackground = 0xFFC2C8D4.toInt(),
            keyText = 0xFF1A1C22.toInt(),
            accent = 0xFF1A73E8.toInt(),
        )

        val OCEAN = KeyboardTheme(
            id = "ocean",
            label = "Ocean",
            keyboardBackground = 0xFF09202D.toInt(),
            keyBackground = 0xFF12455C.toInt(),
            keyBackgroundPressed = 0xFF1F6C88.toInt(),
            specialKeyBackground = 0xFF0E3648.toInt(),
            keyText = 0xFFE4F6FF.toInt(),
            accent = 0xFF67E8F9.toInt(),
        )

        val SUNSET = KeyboardTheme(
            id = "sunset",
            label = "Sunset",
            keyboardBackground = 0xFF26122E.toInt(),
            keyBackground = 0xFF472054.toInt(),
            keyBackgroundPressed = 0xFF6B3179.toInt(),
            specialKeyBackground = 0xFF34183F.toInt(),
            keyText = 0xFFFFF1F7.toInt(),
            accent = 0xFFFFB86B.toInt(),
        )

        /** Order shown in the settings UI. */
        val ALL: List<KeyboardTheme> = listOf(DARK, LIGHT, OCEAN, SUNSET)

        const val DEFAULT_ID = "dark"

        val DEFAULT: KeyboardTheme = DARK

        fun byId(id: String?): KeyboardTheme = ALL.firstOrNull { it.id == id } ?: DEFAULT

        fun isKnownId(id: String?): Boolean = id != null && ALL.any { it.id == id }
    }
}
