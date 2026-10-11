package com.saeed.minikeys.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPrefsCodecTest {

    @Test
    fun `defaults survive a round trip`() {
        val settings = OverlaySettings.DEFAULT
        assertEquals(settings, OverlayPrefsCodec.decode(OverlayPrefsCodec.encode(settings)))
    }

    @Test
    fun `enabled round trips`() {
        val settings = OverlaySettings(enabled = true, columnWidthPercent = 12)
        val back = OverlayPrefsCodec.decode(OverlayPrefsCodec.encode(settings))
        assertTrue(back.enabled)
        assertEquals(12, back.columnWidthPercent)
    }

    @Test
    fun `out of range values clamp`() {
        val decoded = OverlayPrefsCodec.decode(
            mapOf(
                OverlayPrefsCodec.KEY_COLUMN_WIDTH_PERCENT to 99,
                OverlayPrefsCodec.KEY_KEY_HEIGHT_DP to 5,
                OverlayPrefsCodec.KEY_KEY_TEXT_SIZE_SP to 100,
            )
        )
        assertEquals(OverlaySettings.MAX_COLUMN_WIDTH_PERCENT, decoded.columnWidthPercent)
        assertEquals(OverlaySettings.MIN_KEY_HEIGHT_DP, decoded.keyHeightDp)
        assertEquals(OverlaySettings.MAX_KEY_TEXT_SIZE_SP, decoded.keyTextSizeSp)
    }

    @Test
    fun `foreign types degrade instead of throwing`() {
        val decoded = OverlayPrefsCodec.decode(
            mapOf(
                OverlayPrefsCodec.KEY_ENABLED to "maybe",
                OverlayPrefsCodec.KEY_COLUMN_WIDTH_PERCENT to listOf(1, 2, 3),
                OverlayPrefsCodec.KEY_KEY_HEIGHT_DP to "46",
            )
        )
        assertFalse(decoded.enabled)
        assertEquals(OverlaySettings.DEFAULT_COLUMN_WIDTH_PERCENT, decoded.columnWidthPercent)
        assertEquals(46, decoded.keyHeightDp)
    }

    @Test
    fun `string booleans are understood`() {
        assertTrue(OverlayPrefsCodec.decode(mapOf(OverlayPrefsCodec.KEY_ENABLED to "yes")).enabled)
        assertFalse(OverlayPrefsCodec.decode(mapOf(OverlayPrefsCodec.KEY_ENABLED to "no")).enabled)
        assertTrue(OverlayPrefsCodec.decode(mapOf(OverlayPrefsCodec.KEY_ENABLED to 1)).enabled)
    }

    @Test
    fun `normalized repairs every field`() {
        val wild = OverlaySettings(
            columnWidthPercent = 0,
            keyHeightDp = 1000,
            keyTextSizeSp = -4,
        ).normalized()
        assertEquals(OverlaySettings.MIN_COLUMN_WIDTH_PERCENT, wild.columnWidthPercent)
        assertEquals(OverlaySettings.MAX_KEY_HEIGHT_DP, wild.keyHeightDp)
        assertEquals(OverlaySettings.MIN_KEY_TEXT_SIZE_SP, wild.keyTextSizeSp)
    }
}
