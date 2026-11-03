package dev.opencode.mobile.ui.main

import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeMenuTest {
    @Test
    fun settingsAloneHasNoDivider() {
        val entries = HomeMenu.entries(features = emptyList(), settingsTitle = "Settings", onSettingsClick = {})

        assertEquals(listOf("Settings"), entries.map { it.title })
        assertEquals(OpenCodeIcons.Settings, entries.single().icon)
        assertEquals(HomeMenu.SETTINGS_TEST_TAG, entries.single().testTag)
        assertFalse(entries.single().dividerAbove)
    }

    @Test
    fun featureEntriesKeepTheirOrderAndSettingsClosesAfterADivider() {
        val entries =
            HomeMenu.entries(
                features = listOf(feature("Search"), feature("Archived"), feature("Worktrees")),
                settingsTitle = "Settings",
                onSettingsClick = {},
            )

        assertEquals(listOf("Search", "Archived", "Worktrees", "Settings"), entries.map { it.title })
        assertEquals(listOf(false, false, false, true), entries.map { it.dividerAbove })
    }

    @Test
    fun settingsEntryOpensSettings() {
        var opened = 0
        val entries = HomeMenu.entries(features = emptyList(), settingsTitle = "Settings", onSettingsClick = { opened++ })

        entries.last().onClick()

        assertEquals(1, opened)
    }

    private fun feature(title: String) = OpenCodeOverflowMenuEntry(title = title, icon = OpenCodeIcons.Search, onClick = {})
}
