package dev.opencode.mobile.domain.time

import dev.opencode.mobile.domain.text.DateTimeStyle
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.text.EnglishStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RelativeTimeFormattingTest {
    private val now = 1_700_000_000_000L

    @Test
    fun formatsNowUnderOneMinute() {
        assertEquals("now", RelativeTimeFormatting.format(now - 30_000L, now).resolve(EnglishStrings))
        assertEquals("now", RelativeTimeFormatting.format(now, now).resolve(EnglishStrings))
    }

    @Test
    fun formatsMinutesAgo() {
        assertEquals("1m ago", RelativeTimeFormatting.format(now - TimeUnit.MINUTES.toMillis(1), now).resolve(EnglishStrings))
        assertEquals("59m ago", RelativeTimeFormatting.format(now - TimeUnit.MINUTES.toMillis(59), now).resolve(EnglishStrings))
    }

    @Test
    fun formatsHoursAgo() {
        assertEquals("1h ago", RelativeTimeFormatting.format(now - TimeUnit.HOURS.toMillis(1), now).resolve(EnglishStrings))
        assertEquals("23h ago", RelativeTimeFormatting.format(now - TimeUnit.HOURS.toMillis(23), now).resolve(EnglishStrings))
    }

    @Test
    fun formatsDaysAgo() {
        assertEquals("1d ago", RelativeTimeFormatting.format(now - TimeUnit.DAYS.toMillis(1), now).resolve(EnglishStrings))
        assertEquals("6d ago", RelativeTimeFormatting.format(now - TimeUnit.DAYS.toMillis(6), now).resolve(EnglishStrings))
    }

    @Test
    fun formatsAbsoluteDateBeyondOneWeek() {
        // 2023-11-06T22:13:20Z; EnglishStrings writes dates in en-US and UTC.
        val epochMs = now - TimeUnit.DAYS.toMillis(8)
        val text = RelativeTimeFormatting.format(epochMs, now)

        // Left unformatted in UI state, so the view writes it in the current locale.
        assertEquals(UiText.dateTime(epochMs, DateTimeStyle.MonthDay), text)
        assertEquals("Nov 6", text.resolve(EnglishStrings))
    }
}
