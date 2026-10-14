package dev.opencode.mobile.domain.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class HomeSettleFeedTest {
    private data class Row(
        val id: String,
        val settled: Boolean = false,
        val archived: Boolean = false,
        val settledAt: Long? = null,
    )

    private val zone = ZoneOffset.UTC
    private val locale = Locale.US

    @Test
    fun split_keepsTodaysConcatenationWhenThePageIsOff() {
        val active = listOf(Row("live"))
        val rest = listOf(Row("settled", settled = true, settledAt = 2), Row("archived", archived = true))
        val split =
            HomeSettleFeed.split(
                active = active,
                archivedAndSettled = rest,
                showsSettledPage = false,
                isSettled = Row::settled,
                isArchived = Row::archived,
                settledAtEpochMs = Row::settledAt,
            )
        assertEquals(listOf("live", "settled", "archived"), split.ladder.map { it.id })
        assertTrue(split.settled.isEmpty())
    }

    @Test
    fun split_putsSettledOnThePageAndKeepsArchivedOnTheLadder() {
        val split =
            HomeSettleFeed.split(
                active = listOf(Row("live"), Row("snoozed")),
                archivedAndSettled =
                    listOf(
                        Row("older", settled = true, settledAt = 10),
                        Row("archived", archived = true, settled = true, settledAt = 50),
                        Row("newer", settled = true, settledAt = 40),
                    ),
                showsSettledPage = true,
                isSettled = Row::settled,
                isArchived = Row::archived,
                settledAtEpochMs = Row::settledAt,
            )
        assertEquals(listOf("live", "snoozed", "archived"), split.ladder.map { it.id })
        assertEquals(listOf("newer", "older"), split.settled.map { it.id })
    }

    @Test
    fun gestures_followTheRowAndStayEmptyWithoutSettle() {
        assertEquals(emptyList<HomeSettleGesture>(), HomeSettleFeed.gestures(false, false, false, false))
        assertEquals(
            listOf(HomeSettleGesture.Settle, HomeSettleGesture.Snooze),
            HomeSettleFeed.gestures(true, isSettled = false, isSnoozed = false, snoozeBlocked = false),
        )
        assertEquals(
            emptyList<HomeSettleGesture>(),
            HomeSettleFeed.gestures(true, isSettled = false, isSnoozed = false, snoozeBlocked = true),
        )
        assertEquals(
            listOf(HomeSettleGesture.Wake),
            HomeSettleFeed.gestures(true, isSettled = false, isSnoozed = true, snoozeBlocked = false),
        )
        assertEquals(
            listOf(HomeSettleGesture.Unsettle),
            HomeSettleFeed.gestures(true, isSettled = true, isSnoozed = false, snoozeBlocked = false),
        )
        assertEquals(
            emptyList<HomeSettleGesture>(),
            HomeSettleFeed.gestures(true, isSettled = false, isSnoozed = false, snoozeBlocked = false, isArchived = true),
        )
    }

    @Test
    fun isSnoozed_requiresAFutureWakeAndAnUnsettledRow() {
        assertTrue(HomeSettleFeed.isSnoozed(100, nowEpochMs = 99, isSettled = false))
        assertFalse(HomeSettleFeed.isSnoozed(100, nowEpochMs = 100, isSettled = false))
        assertFalse(HomeSettleFeed.isSnoozed(100, nowEpochMs = 50, isSettled = true))
        assertFalse(HomeSettleFeed.isSnoozed(null, nowEpochMs = 50, isSettled = false))
    }

    @Test
    fun snoozePresets_sundayAfternoonKeepsEveningAndDropsNextWeek() {
        val now = Instant.parse("2026-10-04T15:00:00Z").toEpochMilli()
        val presets = HomeSettleFeed.snoozePresets(now, zone, locale)
        assertEquals(listOf("hour", "three-hours", "evening", "tomorrow"), presets.map { it.id })
        assertEquals(Instant.parse("2026-10-04T18:00:00Z").toEpochMilli(), presets.first { it.id == "evening" }.atEpochMs)
        assertEquals(Instant.parse("2026-10-05T09:00:00Z").toEpochMilli(), presets.first { it.id == "tomorrow" }.atEpochMs)
        assertEquals("9:00 AM", presets.first { it.id == "tomorrow" }.whenLabel)
    }

    @Test
    fun snoozePresets_sundayEveningDropsThisEvening() {
        val now = Instant.parse("2026-10-04T17:30:00Z").toEpochMilli()
        val ids = HomeSettleFeed.snoozePresets(now, zone, locale).map { it.id }
        assertEquals(listOf("hour", "three-hours", "tomorrow"), ids)
    }

    @Test
    fun snoozePresets_mondayIncludesNextWeek() {
        val now = Instant.parse("2026-10-05T10:00:00Z").toEpochMilli()
        val presets = HomeSettleFeed.snoozePresets(now, zone, locale)
        assertEquals(listOf("hour", "three-hours", "evening", "tomorrow", "next-week"), presets.map { it.id })
        assertEquals(Instant.parse("2026-10-12T09:00:00Z").toEpochMilli(), presets.first { it.id == "next-week" }.atEpochMs)
        assertTrue(presets.first { it.id == "next-week" }.whenLabel.startsWith("Mon"))
    }

    @Test
    fun untilLabel_namesTomorrowAndALaterWeekday() {
        val now = Instant.parse("2026-10-05T10:00:00Z").toEpochMilli()
        val tomorrow = Instant.parse("2026-10-06T09:00:00Z").toEpochMilli()
        val later = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli()
        assertTrue(HomeSettleFeed.untilLabel(tomorrow, now, zone, locale).startsWith("Tomorrow"))
        assertTrue(HomeSettleFeed.untilLabel(later, now, zone, locale).startsWith("Mon"))
    }

    @Test
    fun wakeInLabel_roundsUpMinutesHoursAndDays() {
        val now = 1_000_000L
        assertEquals("now", HomeSettleFeed.wakeInLabel(now, now))
        assertEquals("1m", HomeSettleFeed.wakeInLabel(now + 1_000L, now))
        assertEquals("30m", HomeSettleFeed.wakeInLabel(now + 30 * 60_000L, now))
        assertEquals("2h", HomeSettleFeed.wakeInLabel(now + 90 * 60_000L, now))
        assertEquals("2d", HomeSettleFeed.wakeInLabel(now + 26 * 3_600_000L, now))
    }

    @Test
    fun snoozeUntilFromDuration_rejectsNonPositiveAmounts() {
        assertNull(HomeSettleFeed.snoozeUntilFromDuration(0, SnoozeUnit.Hours, 1_000L))
        assertEquals(1_000L + 2 * 3_600_000L, HomeSettleFeed.snoozeUntilFromDuration(2, SnoozeUnit.Hours, 1_000L))
    }
}
