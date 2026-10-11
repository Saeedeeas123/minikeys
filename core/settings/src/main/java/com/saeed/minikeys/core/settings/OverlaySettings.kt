package com.saeed.minikeys.core.settings

/**
 * Settings of the always-on EDGE OVERLAY (the shortcut surface), deliberately separate from the
 * keyboard: the IME is the normal keyboard again, while this describes the two key strips that sit
 * on top of every app.
 */
data class OverlaySettings(
    /** Master switch: the overlay runs until it is turned off from its notification. */
    val enabled: Boolean = false,
    /** Width of ONE strip, in percent of the screen width. */
    val columnWidthPercent: Int = DEFAULT_COLUMN_WIDTH_PERCENT,
    /** Height of a single key. */
    val keyHeightDp: Int = DEFAULT_KEY_HEIGHT_DP,
    /** Label size inside a key. */
    val keyTextSizeSp: Int = DEFAULT_KEY_TEXT_SIZE_SP,
) {
    fun normalized(): OverlaySettings = copy(
        columnWidthPercent = columnWidthPercent.coerceIn(COLUMN_WIDTH_RANGE),
        keyHeightDp = keyHeightDp.coerceIn(KEY_HEIGHT_RANGE),
        keyTextSizeSp = keyTextSizeSp.coerceIn(KEY_TEXT_SIZE_RANGE),
    )

    companion object {
        const val MIN_COLUMN_WIDTH_PERCENT = 5
        const val MAX_COLUMN_WIDTH_PERCENT = 15
        const val DEFAULT_COLUMN_WIDTH_PERCENT = 10

        const val MIN_KEY_HEIGHT_DP = 30
        const val MAX_KEY_HEIGHT_DP = 72
        const val DEFAULT_KEY_HEIGHT_DP = 46

        const val MIN_KEY_TEXT_SIZE_SP = 10
        const val MAX_KEY_TEXT_SIZE_SP = 20
        const val DEFAULT_KEY_TEXT_SIZE_SP = 14

        val COLUMN_WIDTH_RANGE = MIN_COLUMN_WIDTH_PERCENT..MAX_COLUMN_WIDTH_PERCENT
        val KEY_HEIGHT_RANGE = MIN_KEY_HEIGHT_DP..MAX_KEY_HEIGHT_DP
        val KEY_TEXT_SIZE_RANGE = MIN_KEY_TEXT_SIZE_SP..MAX_KEY_TEXT_SIZE_SP

        val DEFAULT = OverlaySettings()
    }
}
