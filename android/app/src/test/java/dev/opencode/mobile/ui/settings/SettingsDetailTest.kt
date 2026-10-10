package dev.opencode.mobile.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDetailTest {
    @Test
    fun resolved_unlocked_keepsEveryDetail() {
        SettingsDetail.entries.forEach { detail ->
            assertEquals(detail, detail.resolved(developerToolsUnlocked = true))
        }
    }

    @Test
    fun resolved_locked_fallsBackToServersForDeveloperTools() {
        val developerTools =
            listOf(
                SettingsDetail.ComponentGallery,
                SettingsDetail.TextMateLanguages,
                SettingsDetail.TextMateLogbook,
                SettingsDetail.FeatureFlags,
            )
        developerTools.forEach { detail ->
            assertEquals(SettingsDetail.Servers, detail.resolved(developerToolsUnlocked = false))
        }
    }

    @Test
    fun resolved_locked_keepsRegularDetails() {
        listOf(SettingsDetail.Servers, SettingsDetail.Appearance, SettingsDetail.DiagnosticLogs).forEach { detail ->
            assertEquals(detail, detail.resolved(developerToolsUnlocked = false))
        }
    }
}
