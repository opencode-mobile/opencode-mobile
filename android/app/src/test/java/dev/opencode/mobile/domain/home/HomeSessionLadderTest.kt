package dev.opencode.mobile.domain.home

import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.text.EnglishStrings
import dev.opencode.mobile.ui.text.GermanFormatsStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class HomeSessionLadderTest {
    private data class Item(
        val id: String,
        val updatedAtEpochMs: Long?,
        val attention: Boolean = false,
    )

    private val zone = ZoneId.of("America/Los_Angeles")

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
    fun sanitizeWindowHours_tiePrefersCloserToDefaultThenLarger() {
        // Midpoint 3 is equidistant from 2 and 4 → prefer 4 (closer to default).
        assertEquals(4, HomeSessionLadder.sanitizeWindowHours(3))
        // Midpoint 6 is equidistant from 4 and 8 → prefer 4 (closer to default).
        assertEquals(4, HomeSessionLadder.sanitizeWindowHours(6))
        // Midpoint 10 is equidistant from 8 and 12 → prefer 8 (closer to default).
        assertEquals(8, HomeSessionLadder.sanitizeWindowHours(10))
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
        assertEquals(listOf("Today", "Yesterday"), sections.map { it.title.resolve(EnglishStrings) })
        assertEquals(listOf("today"), sections[0].items.map { it.id })
        assertEquals(listOf("yesterday"), sections[1].items.map { it.id })
    }

    @Test
    fun group_namesOlderDaysInTheReadersLocaleWhenShown() {
        // Three days before Wednesday 2026-08-05: Sunday 2026-08-02.
        val sections =
            group(listOf(Item(id = "sunday", updatedAtEpochMs = now - TimeUnit.DAYS.toMillis(3))), windowHours = 1)

        assertEquals("Sunday, Aug 2", sections.single().title.resolve(EnglishStrings))
        assertEquals("Sonntag, 2. Aug.", sections.single().title.resolve(GermanFormatsStrings))
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
        // 2026-07-22 is a Wednesday: its week runs Monday Jul 20 to Sunday Jul 26.
        assertEquals("Week of Jul 20 – Jul 26", sections[0].title.resolve(EnglishStrings))
        assertTrue(sections[0].title.resolve(GermanFormatsStrings).contains("20. Juli"))
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
        )
}
