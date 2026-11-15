package dev.opencode.mobile.domain.preferences

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePreferencesTest {
    private val prefs = InMemoryPreferences()

    @After
    fun tearDown() {
        AppearancePreferences.initialize(InMemoryPreferences())
    }

    @Test
    fun `fresh install follows the phone at Default sizes`() {
        AppearancePreferences.initialize(prefs)

        assertEquals(AppearanceMode.System, AppearancePreferences.mode)
        assertEquals(AppearanceTextSizes.DEFAULT, AppearancePreferences.textSizes)
        assertTrue(AppearancePreferences.textSizes.allDefault)
    }

    @Test
    fun `mode and every size persist across a relaunch`() {
        AppearancePreferences.initialize(prefs)
        AppearancePreferences.updateMode(AppearanceMode.Dark)
        AppearancePreferences.updateTextSize(AppearanceTextRegion.HomeList, AppearanceTextSize.ExtraSmall)
        AppearancePreferences.updateTextSize(AppearanceTextRegion.SessionChat, AppearanceTextSize.Large)
        AppearancePreferences.updateTextSize(AppearanceTextRegion.Code, AppearanceTextSize.ExtraLarge)

        AppearancePreferences.initialize(InMemoryPreferences())
        AppearancePreferences.initialize(prefs)

        assertEquals(AppearanceMode.Dark, AppearancePreferences.mode)
        assertEquals(
            AppearanceTextSizes(
                homeList = AppearanceTextSize.ExtraSmall,
                sessionChat = AppearanceTextSize.Large,
                code = AppearanceTextSize.ExtraLarge,
            ),
            AppearancePreferences.textSizes,
        )
    }

    @Test
    fun `a size change touches only its own region`() {
        AppearancePreferences.initialize(prefs)
        AppearancePreferences.updateTextSize(AppearanceTextRegion.SessionChat, AppearanceTextSize.Small)

        assertEquals(AppearanceTextSize.Default, AppearancePreferences.textSizes.homeList)
        assertEquals(AppearanceTextSize.Small, AppearancePreferences.textSizes.sessionChat)
        assertEquals(AppearanceTextSize.Default, AppearancePreferences.textSizes.code)
    }

    @Test
    fun `unknown stored values fall back to the defaults`() {
        prefs.values["mode"] = "sepia"
        prefs.values["code_text_size"] = "huge"

        AppearancePreferences.initialize(prefs)

        assertEquals(AppearanceMode.System, AppearancePreferences.mode)
        assertEquals(AppearanceTextSize.Default, AppearancePreferences.textSizes.code)
    }

    @Test
    fun `reset text sizes keeps the mode`() {
        AppearancePreferences.initialize(prefs)
        AppearancePreferences.updateMode(AppearanceMode.Light)
        AppearancePreferences.updateTextSize(AppearanceTextRegion.Code, AppearanceTextSize.Large)

        AppearancePreferences.resetTextSizes()

        assertEquals(AppearanceMode.Light, AppearancePreferences.mode)
        assertTrue(AppearancePreferences.textSizes.allDefault)
        assertNull(prefs.values["code_text_size"])
        assertEquals("light", prefs.values["mode"])
    }

    @Test
    fun `reset all also puts the mode back to System`() {
        AppearancePreferences.initialize(prefs)
        AppearancePreferences.updateMode(AppearanceMode.Dark)
        AppearancePreferences.updateTextSize(AppearanceTextRegion.HomeList, AppearanceTextSize.Large)

        AppearancePreferences.resetAll()

        assertEquals(AppearanceMode.System, AppearancePreferences.mode)
        assertTrue(AppearancePreferences.textSizes.allDefault)
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun `clear all data forgets appearance`() {
        AppearancePreferences.initialize(prefs)
        AppearancePreferences.updateMode(AppearanceMode.Dark)

        AppearancePreferences.clear()
        AppearancePreferences.initialize(prefs)

        assertEquals(AppearanceMode.System, AppearancePreferences.mode)
    }

    @Test
    fun `mode decides dark over the phone setting`() {
        assertTrue(AppearanceMode.System.isDark(systemDark = true))
        assertFalse(AppearanceMode.System.isDark(systemDark = false))
        assertFalse(AppearanceMode.Light.isDark(systemDark = true))
        assertTrue(AppearanceMode.Dark.isDark(systemDark = false))
    }

    @Test
    fun `five steps from extra small to extra large with Default in the middle`() {
        assertEquals(
            listOf(0.85f, 0.92f, 1.0f, 1.12f, 1.26f),
            AppearanceTextSize.entries.map { it.multiplier },
        )
        assertEquals(AppearanceTextSize.Default, AppearanceTextSize.entries[AppearanceTextSize.entries.size / 2])
        assertEquals(AppearanceTextSize.ExtraSmall, AppearanceTextSize.atIndex(-3))
        assertEquals(AppearanceTextSize.ExtraLarge, AppearanceTextSize.atIndex(9))
    }
}
