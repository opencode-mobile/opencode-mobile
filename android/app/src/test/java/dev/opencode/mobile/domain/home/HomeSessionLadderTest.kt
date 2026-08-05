package dev.opencode.mobile.domain.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeSessionLadderTest {
    private data class Item(
        val id: String,
        val updatedAtEpochMs: Long?,
        val attention: Boolean = false,
    )

    private val zone = ZoneId.of("America/Los_Angeles")
    private val locale = Locale.US

    // Wednesday 2026-08-05 15:00 local PDT (UTC-7)
    private val now = 1_785_967_200_000L

    @Test
    fun sanitizeWindowHours_keepsAllowedValues() {
        assertEquals(4, HomeSessionLadder.sanitizeWindowHours(4))
        assertEquals(24, HomeSessionLadder.sanitizeWindowHours(24))
    }

    @Test
    fun sanitizeWindowHours_coercesToNearestAllowed() {
        assertEquals(2, HomeSessionLadder.sanitizeWindowHours(1))
        assertEquals(8, HomeSessionLadder.sanitizeWindowHours(9))
        assertEquals(24, HomeSessionLadder.sanitizeWindowHours(100))
    }

    @Test
    fun group_putsRunningAndReadyInRecentEvenWhenOld() {
        val sessions =
            listOf(
                Item(id = "old-ready", updatedAtEpochMs = now - TimeUnit.DAYS.toMillis(3), attention = true),
                Item(id = "old-idle", updatedAtEpochMs = now - TimeUnit.DAYS.toMillis(3), attention = false),
            )

        val sections = group(sessions, windowHours = 4)
        assertEquals(
            listOf(HomeSessionLadderKind.Recent, HomeSessionLadderKind.Day),
            sections.map { it.kind },
        )
        assertEquals(listOf("old-ready"), sections.first { it.kind == HomeSessionLadderKind.Recent }.items.map { it.id })
        assertEquals(listOf("old-idle"), sections.first { it.kind == HomeSessionLadderKind.Day }.items.map { it.id })
    }

    @Test
    fun group_putsFreshIdleInRecentByWindow() {
        val sessions =
            listOf(
                Item(id = "fresh", updatedAtEpochMs = now - TimeUnit.HOURS.toMillis(2), attention = false),
                Item(id = "stale", updatedAtEpochMs = now - TimeUnit.HOURS.toMillis(5), attention = false),
            )

        val sections = group(sessions, windowHours = 4)
        assertEquals(listOf("fresh"), sections.single { it.kind == HomeSessionLadderKind.Recent }.items.map { it.id })
        assertTrue(sections.none { section -> section.items.any { it.id == "fresh" && section.kind != HomeSessionLadderKind.Recent } })
        assertEquals(listOf("stale"), sections.filter { it.kind != HomeSessionLadderKind.Recent }.flatMap { it.items }.map { it.id })
    }

    @Test
    fun group_sortsRecentByUpdatedAtDescending() {
        val sessions =
            listOf(
                Item(id = "older", updatedAtEpochMs = now - TimeUnit.HOURS.toMillis(3), attention = true),
                Item(id = "newer", updatedAtEpochMs = now - TimeUnit.MINUTES.toMillis(10), attention = true),
            )

        val recent = group(sessions).single { it.kind == HomeSessionLadderKind.Recent }
        assertEquals(listOf("newer", "older"), recent.items.map { it.id })
    }

    @Test
    fun group_omitsEmptySectionsAndBuildsDayLabels() {
        val todayStart =
            java.time.Instant
                .ofEpochMilli(now)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        val sessions =
            listOf(
                Item(id = "today", updatedAtEpochMs = todayStart + TimeUnit.HOURS.toMillis(10)),
                Item(id = "yesterday", updatedAtEpochMs = todayStart - TimeUnit.HOURS.toMillis(2)),
            )

        val sections = group(sessions, windowHours = 1)
        assertTrue(sections.none { it.kind == HomeSessionLadderKind.Recent })
        assertEquals(listOf("Today", "Yesterday"), sections.map { it.title })
        assertEquals(listOf("today"), sections[0].items.map { it.id })
        assertEquals(listOf("yesterday"), sections[1].items.map { it.id })
    }

    @Test
    fun group_bucketsOlderSessionsByWeek() {
        val todayStart =
            java.time.Instant
                .ofEpochMilli(now)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        val twoWeeksAgo = todayStart - TimeUnit.DAYS.toMillis(14)
        val sections =
            group(
                listOf(Item(id = "old", updatedAtEpochMs = twoWeeksAgo)),
                windowHours = 1,
            )
        assertEquals(1, sections.size)
        assertEquals(HomeSessionLadderKind.Week, sections[0].kind)
        assertTrue(sections[0].title.startsWith("Week of "))
        assertEquals(listOf("old"), sections[0].items.map { it.id })
    }

    @Test
    fun group_returnsEmptyForEmptyInput() {
        assertTrue(group(emptyList()).isEmpty())
    }

    private fun group(
        sessions: List<Item>,
        windowHours: Int = 4,
    ): List<HomeSessionLadderSection<Item>> =
        HomeSessionLadder.group(
            sessions = sessions,
            updatedAtEpochMs = { it.updatedAtEpochMs },
            isAttention = { it.attention },
            recentWindowHours = windowHours,
            nowEpochMs = now,
            zoneId = zone,
            locale = locale,
        )
}
