package dev.opencode.mobile.domain.time

import dev.opencode.mobile.domain.text.DateTimeStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class LocalizedDateTimeFormatTest {
    // 2026-11-27T23:30:00Z
    private val epochMs = 1_795_822_200_000L

    @Test
    fun monthDay_followsTheLocalesPattern() {
        // Patterns as Android's CLDR data gives them for the "MMMd" skeleton.
        val english = LocalizedDateTimeFormat(Locale.US, ZoneOffset.UTC, monthDayPattern = "MMM d")
        val german = LocalizedDateTimeFormat(Locale.GERMANY, ZoneOffset.UTC, monthDayPattern = "d. MMM")

        assertEquals("Nov 27", english.format(epochMs, DateTimeStyle.MonthDay))
        assertEquals("27. Nov.", german.format(epochMs, DateTimeStyle.MonthDay))
    }

    @Test
    fun monthDay_usesTheGivenTimeZone() {
        val tokyo = LocalizedDateTimeFormat(Locale.US, ZoneId.of("Asia/Tokyo"), monthDayPattern = "MMM d")

        assertEquals("Nov 28", tokyo.format(epochMs, DateTimeStyle.MonthDay))
    }

    @Test
    fun monthDay_unreadablePattern_fallsBackToTheLocalesMediumDate() {
        val german = LocalizedDateTimeFormat(Locale.GERMANY, ZoneOffset.UTC, monthDayPattern = "d. MMM {")

        assertEquals("27.11.2026", german.format(epochMs, DateTimeStyle.MonthDay))
    }
}
