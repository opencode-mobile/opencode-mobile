package dev.opencode.mobile.domain.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class RelativeTimeFormattingTest {
    private val now = 1_700_000_000_000L

    @Test
    fun formatsNowUnderOneMinute() {
        assertEquals("now", RelativeTimeFormatting.format(now - 30_000L, now))
        assertEquals("now", RelativeTimeFormatting.format(now, now))
    }

    @Test
    fun formatsMinutesAgo() {
        assertEquals("1m ago", RelativeTimeFormatting.format(now - TimeUnit.MINUTES.toMillis(1), now))
        assertEquals("59m ago", RelativeTimeFormatting.format(now - TimeUnit.MINUTES.toMillis(59), now))
    }

    @Test
    fun formatsHoursAgo() {
        assertEquals("1h ago", RelativeTimeFormatting.format(now - TimeUnit.HOURS.toMillis(1), now))
        assertEquals("23h ago", RelativeTimeFormatting.format(now - TimeUnit.HOURS.toMillis(23), now))
    }

    @Test
    fun formatsDaysAgo() {
        assertEquals("1d ago", RelativeTimeFormatting.format(now - TimeUnit.DAYS.toMillis(1), now))
        assertEquals("6d ago", RelativeTimeFormatting.format(now - TimeUnit.DAYS.toMillis(6), now))
    }

    @Test
    fun formatsAbsoluteDateBeyondOneWeek() {
        val epochMs = now - TimeUnit.DAYS.toMillis(8)
        val expected =
            Instant
                .ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        assertEquals(expected, RelativeTimeFormatting.format(epochMs, now))
    }
}
