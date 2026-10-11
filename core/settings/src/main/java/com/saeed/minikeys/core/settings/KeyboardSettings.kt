package com.saeed.minikeys.core.settings

/**
 * Everything the keyboard module needs to render itself. Immutable value object; the single source
 * of truth for defaults and valid ranges so the IME and the settings UI can never disagree.
 */
data class KeyboardSettings(
    val themeId: String = KeyboardTheme.DEFAULT_ID,
    val keyboardHeightDp: Int = DEFAULT_HEIGHT_DP,
    val keyTextSizeSp: Int = DEFAULT_TEXT_SIZE_SP,
) {
    val theme: KeyboardTheme get() = KeyboardTheme.byId(themeId)

    /** Clamps every field into its valid range and repairs unknown theme ids. */
    fun normalized(): KeyboardSettings = copy(
        themeId = if (KeyboardTheme.isKnownId(themeId)) themeId else KeyboardTheme.DEFAULT_ID,
        keyboardHeightDp = keyboardHeightDp.coerceIn(HEIGHT_RANGE),
        keyTextSizeSp = keyTextSizeSp.coerceIn(TEXT_SIZE_RANGE),
    )

    companion object {
        const val MIN_HEIGHT_DP = 160
        const val MAX_HEIGHT_DP = 360
        const val DEFAULT_HEIGHT_DP = 240

        const val MIN_TEXT_SIZE_SP = 12
        const val MAX_TEXT_SIZE_SP = 24
        const val DEFAULT_TEXT_SIZE_SP = 16

        val HEIGHT_RANGE = MIN_HEIGHT_DP..MAX_HEIGHT_DP
        val TEXT_SIZE_RANGE = MIN_TEXT_SIZE_SP..MAX_TEXT_SIZE_SP

        val DEFAULT = KeyboardSettings()
    }
}
