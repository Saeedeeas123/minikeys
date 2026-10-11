package com.saeed.minikeys.core.settings

/**
 * Pure translation between [OverlaySettings] and the raw preference map — same rules as the keyboard
 * codec: missing, foreign or out-of-range values degrade to a valid setting instead of throwing.
 */
object OverlayPrefsCodec {

    const val KEY_ENABLED = "overlay_enabled"
    const val KEY_COLUMN_WIDTH_PERCENT = "overlay_column_width_percent"
    const val KEY_KEY_HEIGHT_DP = "overlay_key_height_dp"
    const val KEY_KEY_TEXT_SIZE_SP = "overlay_key_text_size_sp"

    fun encode(settings: OverlaySettings): Map<String, Any> = mapOf(
        KEY_ENABLED to settings.enabled,
        KEY_COLUMN_WIDTH_PERCENT to settings.columnWidthPercent,
        KEY_KEY_HEIGHT_DP to settings.keyHeightDp,
        KEY_KEY_TEXT_SIZE_SP to settings.keyTextSizeSp,
    )

    fun decode(values: Map<String, *>): OverlaySettings = OverlaySettings(
        enabled = readBoolean(values[KEY_ENABLED], OverlaySettings.DEFAULT.enabled),
        columnWidthPercent = readInt(
            raw = values[KEY_COLUMN_WIDTH_PERCENT],
            range = OverlaySettings.COLUMN_WIDTH_RANGE,
            fallback = OverlaySettings.DEFAULT_COLUMN_WIDTH_PERCENT,
        ),
        keyHeightDp = readInt(
            raw = values[KEY_KEY_HEIGHT_DP],
            range = OverlaySettings.KEY_HEIGHT_RANGE,
            fallback = OverlaySettings.DEFAULT_KEY_HEIGHT_DP,
        ),
        keyTextSizeSp = readInt(
            raw = values[KEY_KEY_TEXT_SIZE_SP],
            range = OverlaySettings.KEY_TEXT_SIZE_RANGE,
            fallback = OverlaySettings.DEFAULT_KEY_TEXT_SIZE_SP,
        ),
    )

    private fun readBoolean(raw: Any?, fallback: Boolean): Boolean = when (raw) {
        is Boolean -> raw
        is String -> when (raw.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> fallback
        }

        is Number -> raw.toInt() != 0
        else -> fallback
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
