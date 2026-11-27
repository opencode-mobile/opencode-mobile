package dev.opencode.mobile.ui.components

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.time.LocalizedDateTimeFormat
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class OpenCodeMessageTimeRevealTest {
    private val utc: ZoneId = ZoneOffset.UTC

    /** Thursday 2026-10-29 15:00 UTC. */
    private val now = at(2026, 10, 29, 15, 0)

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()

    private fun time(
        sentAt: Long,
        is24Hour: Boolean = false,
    ) = OpenCodeMessageTime.of(
        sentAt,
        nowEpochMs = now,
        // en-US patterns as Android's CLDR data gives them for "hm" and "Hm".
        formats = LocalizedDateTimeFormat(Locale.US, utc, timePattern = if (is24Hour) "HH:mm" else "h:mm a"),
        yesterday = YESTERDAY,
    )

    @Test
    fun todayShowsOnlyTheTime() {
        val label = time(at(2026, 10, 29, 9, 5))
        assertEquals("9:05 AM", label.time)
        assertNull(label.day)
    }

    @Test
    fun timeFollowsThe24HourSetting() {
        assertEquals("21:05", time(at(2026, 10, 29, 21, 5), is24Hour = true).time)
        assertEquals("9:05 PM", time(at(2026, 10, 29, 21, 5)).time)
    }

    @Test
    fun yesterdayShowsYesterday() {
        assertEquals("Yesterday", time(at(2026, 10, 28, 23, 59)).day)
    }

    @Test
    fun thisWeekShowsTheWeekday() {
        assertEquals("Tuesday", time(at(2026, 10, 27, 10, 0)).day)
        assertEquals("Friday", time(at(2026, 10, 23, 10, 0)).day)
    }

    @Test
    fun olderShowsAShortDate() {
        assertEquals("Oct 22", time(at(2026, 10, 22, 10, 0)).day)
        assertEquals("Dec 31, 2025", time(at(2025, 12, 31, 10, 0)).day)
    }

    @Test
    fun olderShortDateFollowsTheLocalesPattern() {
        assertEquals("22. Okt.", german(at(2026, 10, 22, 10, 0)).day)
        assertEquals("10:00", german(at(2026, 10, 22, 10, 0)).time)
        assertEquals("Dienstag", german(at(2026, 10, 27, 10, 0)).day)
        assertEquals("Gestern", german(at(2026, 10, 28, 21, 5)).day)
    }

    @Test
    fun timeFollowsTheLocalesPattern() {
        // Korean puts the day period first ("a h:mm" for the "hm" skeleton).
        val korean =
            OpenCodeMessageTime.of(
                at(2026, 10, 29, 21, 5),
                nowEpochMs = now,
                formats = LocalizedDateTimeFormat(Locale.KOREA, utc, timePattern = "a h:mm"),
                yesterday = YESTERDAY,
            )
        assertEquals("오후 9:05", korean.time)
    }

    @Test
    fun yesterdayIsTheKeyedString() {
        assertEquals("Yesterday", UiText.resource(R.string.component_message_time_yesterday).english())
    }

    private fun german(sentAt: Long) =
        OpenCodeMessageTime.of(
            sentAt,
            nowEpochMs = now,
            formats = LocalizedDateTimeFormat(Locale.GERMANY, utc, monthDayPattern = "d. MMM", timePattern = "HH:mm"),
            yesterday = "Gestern",
        )

    private companion object {
        const val YESTERDAY = "Yesterday"
    }

    @Test
    fun futureTimesCountAsToday() {
        assertNull(time(at(2026, 10, 30, 1, 0)).day)
    }

    @Test
    fun dragFollowsTheFingerThenRubberBandsToTheCap() {
        assertEquals(18f, OpenCodeTimeRevealDrag.offset(-20f), 0.001f)
        assertEquals(36f, OpenCodeTimeRevealDrag.offset(-40f), 0.001f)
        val past = OpenCodeTimeRevealDrag.offset(-100f)
        assertTrue(past > 52f && past < 72f)
        assertTrue(OpenCodeTimeRevealDrag.offset(-100f) < OpenCodeTimeRevealDrag.offset(-200f))
        assertTrue(OpenCodeTimeRevealDrag.offset(-2000f) <= OpenCodeTimeRevealDrag.CAP)
        assertTrue(OpenCodeTimeRevealDrag.offset(-2000f) > 71.9f)
    }

    @Test
    fun dragRightStaysAtZero() {
        assertEquals(0f, OpenCodeTimeRevealDrag.offset(0f), 0f)
        assertEquals(0f, OpenCodeTimeRevealDrag.offset(50f), 0f)
    }

    @Test
    fun timeFadesInWithTheOffset() {
        assertEquals(0f, OpenCodeTimeRevealDrag.opacity(0f), 0f)
        assertEquals(0f, OpenCodeTimeRevealDrag.opacity(12f), 0f)
        assertEquals(0.5f, OpenCodeTimeRevealDrag.opacity(32f), 0.001f)
        assertEquals(1f, OpenCodeTimeRevealDrag.opacity(52f), 0f)
        assertEquals(1f, OpenCodeTimeRevealDrag.opacity(72f), 0f)
    }

    @Test
    fun directionLocksAfterTheSlop() {
        assertNull(OpenCodeTimeRevealDrag.direction(-5f, 0f))
        assertEquals(OpenCodeTimeRevealDrag.Direction.Reveal, OpenCodeTimeRevealDrag.direction(-12f, 3f))
        assertEquals(OpenCodeTimeRevealDrag.Direction.Other, OpenCodeTimeRevealDrag.direction(12f, 0f))
        assertEquals(OpenCodeTimeRevealDrag.Direction.Other, OpenCodeTimeRevealDrag.direction(-10f, 10f))
        assertEquals(OpenCodeTimeRevealDrag.Direction.Other, OpenCodeTimeRevealDrag.direction(0f, -20f))
    }

    @Test
    fun accessibilityValueKeepsAgentModelAndTime() {
        assertEquals(
            "Build · DeepSeek V4 Flash · 9:05 PM",
            outgoingMessageAccessibilityValue("Build · DeepSeek V4 Flash", time(at(2026, 10, 29, 21, 5))),
        )
        val yesterday = time(at(2026, 10, 28, 21, 5))
        assertEquals("Yesterday, Build · 9:05 PM", outgoingMessageAccessibilityValue("Build", yesterday))
        assertEquals("Yesterday, 9:05 PM", outgoingMessageAccessibilityValue("", yesterday))
        assertEquals("Build", outgoingMessageAccessibilityValue("Build", null))
    }
}
