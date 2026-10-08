package com.saeed.minikeys.core.settings

/**
 * Translates [KeyboardSettings] to and from the raw key/value map stored in SharedPreferences.
 *
 * Deliberately a pure object (no Android types) so the persistence rules — defaults, clamping and
 * repair of stale/foreign values — are covered by JVM unit tests instead of device-only checks.
 */
object KeyboardPrefsCodec {

    const val KEY_THEME_ID = "theme_id"
    const val KEY_KEYBOARD_HEIGHT_DP = "keyboard_height_dp"
    const val KEY_KEY_TEXT_SIZE_SP = "key_text_size_sp"

    fun encode(settings: KeyboardSettings): Map<String, Any> = mapOf(
        KEY_THEME_ID to settings.themeId,
        KEY_KEYBOARD_HEIGHT_DP to settings.keyboardHeightDp,
        KEY_KEY_TEXT_SIZE_SP to settings.keyTextSizeSp,
    )

    /** Never throws: any missing, foreign or out-of-range value degrades to a valid setting. */
    fun decode(values: Map<String, *>): KeyboardSettings {
        val themeId = (values[KEY_THEME_ID] as? String)
            ?.takeIf { KeyboardTheme.isKnownId(it) }
            ?: KeyboardTheme.DEFAULT_ID

        return KeyboardSettings(
            themeId = themeId,
            keyboardHeightDp = readInt(
                raw = values[KEY_KEYBOARD_HEIGHT_DP],
                range = KeyboardSettings.HEIGHT_RANGE,
                fallback = KeyboardSettings.DEFAULT_HEIGHT_DP,
            ),
            keyTextSizeSp = readInt(
                raw = values[KEY_KEY_TEXT_SIZE_SP],
                range = KeyboardSettings.TEXT_SIZE_RANGE,
                fallback = KeyboardSettings.DEFAULT_TEXT_SIZE_SP,
            ),
        )
    }

    private fun readInt(raw: Any?, range: IntRange, fallback: Int): Int {
        val parsed = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        } ?: return fallback
        return parsed.coerceIn(range.first, range.last)
    }
}
