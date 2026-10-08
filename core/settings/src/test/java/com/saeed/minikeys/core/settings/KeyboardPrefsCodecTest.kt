package com.saeed.minikeys.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPrefsCodecTest {

    @Test
    fun `empty storage falls back to defaults`() {
        val settings = KeyboardPrefsCodec.decode(emptyMap<String, Any>())

        assertEquals(KeyboardTheme.DEFAULT_ID, settings.themeId)
        assertEquals(KeyboardSettings.DEFAULT_HEIGHT_DP, settings.keyboardHeightDp)
        assertEquals(KeyboardSettings.DEFAULT_TEXT_SIZE_SP, settings.keyTextSizeSp)
    }

    @Test
    fun `explicit values survive a round trip`() {
        val original = KeyboardSettings(
            themeId = "ocean",
            keyboardHeightDp = 300,
            keyTextSizeSp = 20,
        )

        val restored = KeyboardPrefsCodec.decode(KeyboardPrefsCodec.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun `values below the allowed range are clamped up`() {
        val settings = KeyboardPrefsCodec.decode(
            mapOf(
                KeyboardPrefsCodec.KEY_KEYBOARD_HEIGHT_DP to 40,
                KeyboardPrefsCodec.KEY_KEY_TEXT_SIZE_SP to 2,
            )
        )

        assertEquals(KeyboardSettings.MIN_HEIGHT_DP, settings.keyboardHeightDp)
        assertEquals(KeyboardSettings.MIN_TEXT_SIZE_SP, settings.keyTextSizeSp)
    }

    @Test
    fun `values above the allowed range are clamped down`() {
        val settings = KeyboardPrefsCodec.decode(
            mapOf(
                KeyboardPrefsCodec.KEY_KEYBOARD_HEIGHT_DP to 5000,
                KeyboardPrefsCodec.KEY_KEY_TEXT_SIZE_SP to 99,
            )
        )

        assertEquals(KeyboardSettings.MAX_HEIGHT_DP, settings.keyboardHeightDp)
        assertEquals(KeyboardSettings.MAX_TEXT_SIZE_SP, settings.keyTextSizeSp)
    }

    @Test
    fun `unknown theme id is repaired to the default theme`() {
        val settings = KeyboardPrefsCodec.decode(
            mapOf(KeyboardPrefsCodec.KEY_THEME_ID to "theme-from-a-removed-module")
        )

        assertEquals(KeyboardTheme.DEFAULT_ID, settings.themeId)
        assertTrue(KeyboardTheme.isKnownId(settings.themeId))
    }

    @Test
    fun `numeric strings and foreign types do not crash the decoder`() {
        val fromStrings = KeyboardPrefsCodec.decode(
            mapOf(
                KeyboardPrefsCodec.KEY_KEYBOARD_HEIGHT_DP to "275",
                KeyboardPrefsCodec.KEY_KEY_TEXT_SIZE_SP to "18",
            )
        )
        assertEquals(275, fromStrings.keyboardHeightDp)
        assertEquals(18, fromStrings.keyTextSizeSp)

        val fromGarbage = KeyboardPrefsCodec.decode(
            mapOf(
                KeyboardPrefsCodec.KEY_KEYBOARD_HEIGHT_DP to "tall",
                KeyboardPrefsCodec.KEY_KEY_TEXT_SIZE_SP to null,
            )
        )
        assertEquals(KeyboardSettings.DEFAULT_HEIGHT_DP, fromGarbage.keyboardHeightDp)
        assertEquals(KeyboardSettings.DEFAULT_TEXT_SIZE_SP, fromGarbage.keyTextSizeSp)
    }

    @Test
    fun `normalized repairs every field at once`() {
        val broken = KeyboardSettings(themeId = "nope", keyboardHeightDp = 1, keyTextSizeSp = 999)

        val fixed = broken.normalized()

        assertEquals(KeyboardTheme.DEFAULT_ID, fixed.themeId)
        assertEquals(KeyboardSettings.MIN_HEIGHT_DP, fixed.keyboardHeightDp)
        assertEquals(KeyboardSettings.MAX_TEXT_SIZE_SP, fixed.keyTextSizeSp)
        assertFalse(KeyboardTheme.isKnownId("nope"))
    }

    @Test
    fun `theme lookup returns the requested preset or the default`() {
        assertEquals("sunset", KeyboardTheme.byId("sunset").id)
        assertEquals(KeyboardTheme.DEFAULT_ID, KeyboardTheme.byId(null).id)
        assertEquals(KeyboardTheme.DEFAULT_ID, KeyboardTheme.byId("bogus").id)
    }
}
