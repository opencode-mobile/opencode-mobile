package dev.opencode.mobile.ui.sessioncontext

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class SessionContextStatFormatTest {
    private val english = SessionContextStatFormat(Locale.US, ZoneOffset.UTC)
    private val german = SessionContextStatFormat(Locale.GERMANY, ZoneOffset.UTC)

    // 2026-11-27T15:04:00Z
    private val epochMs = 1_795_791_840_000L

    @Test
    fun englishKeepsTheUsFormats() {
        assertEquals("1,234,567", english.format(SessionContextStatValue.Count(1_234_567)))
        assertEquals("12,000 / 340", english.format(SessionContextStatValue.CountPair(12_000, 340)))
        assertEquals("45%", english.format(SessionContextStatValue.Percent(45)))
        assertEquals("$1,234.50", english.format(SessionContextStatValue.Cost(1_234.5)))
        assertEquals("Nov 27, 2026, 3:04 PM", english.format(SessionContextStatValue.Moment(epochMs)))
    }

    @Test
    fun germanUsesItsSeparatorsAndOrderButStaysInDollars() {
        // German puts a no-break space before % and the currency sign.
        assertEquals("1.234.567", german.format(SessionContextStatValue.Count(1_234_567)))
        assertEquals("45\u00A0%", german.format(SessionContextStatValue.Percent(45)))
        assertEquals("1.234,50\u00A0$", german.format(SessionContextStatValue.Cost(1_234.5)))
        assertEquals("27.11.2026, 15:04", german.format(SessionContextStatValue.Moment(epochMs)))
    }

    @Test
    fun missingValuesAndSessionTextStayAsIs() {
        assertEquals("—", english.format(SessionContextStatValue.Count(null)))
        assertEquals("—", english.format(SessionContextStatValue.Percent(null)))
        assertEquals("—", english.format(SessionContextStatValue.Moment(null)))
        assertEquals("—", english.format(SessionContextStatValue.Text(null)))
        assertEquals("1,234", german.format(SessionContextStatValue.Text("1,234")))
    }
}
