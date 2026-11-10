package dev.opencode.mobile.ui.settings

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTextTest {
    @Test
    fun serversSummary_noProfiles() {
        assertEquals(UiText.resource(R.string.settings_servers_summary_none), ServersSummary().subtitle)
        assertEquals("No servers saved", ServersSummary().subtitle.english())
    }

    @Test
    fun serversSummary_activeName_staysVerbatim() {
        val summary = ServersSummary(count = 2, activeName = "servers.title")

        assertEquals(
            UiText.resource(R.string.settings_servers_summary_saved_active, 2, UiText.verbatim("servers.title")),
            summary.subtitle,
        )
        assertEquals("2 saved · servers.title active", summary.subtitle.english())
    }

    @Test
    fun serversSummary_noActive_countsSaved() {
        assertEquals(UiText.plural(R.plurals.settings_servers_summary_saved, 3), ServersSummary(count = 3).subtitle)
        assertEquals("1 saved", ServersSummary(count = 1).subtitle.english())
    }

    @Test
    fun recentWindowHours_pluralAndSanitized() {
        assertEquals(UiText.plural(R.plurals.settings_recent_window_hours, 4), recentWindowHoursLabel(4))
        assertEquals("4 hours", recentWindowHoursLabel(4).english())
        // 1 is not an allowed window and sanitizes to the nearest one.
        assertEquals("2 hours", recentWindowHoursLabel(1).english())
        assertEquals("1 hour", UiText.plural(R.plurals.settings_recent_window_hours, 1).english())
    }

    @Test
    fun diagnosticsCounts_useSingularForOne() {
        assertEquals("Last 1 minute", UiText.plural(R.plurals.diagnostics_last_minutes, 1).english())
        assertEquals("Last 15 minutes", UiText.plural(R.plurals.diagnostics_last_minutes, 15).english())
        assertEquals(
            "1 redacted line in the kept window",
            UiText.plural(R.plurals.diagnostics_entries_count, 1).english(),
        )
    }
}
