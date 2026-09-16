package dev.opencode.mobile.domain.home

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class HomeSessionLadderKind {
    Recent,
    Day,
    Week,
}

data class HomeSessionLadderSection<T>(
    val id: String,
    val kind: HomeSessionLadderKind,
    val title: String,
    val hint: String? = null,
    val items: List<T>,
)

object HomeSessionLadder {
    val ALLOWED_WINDOW_HOURS: List<Int> = listOf(2, 4, 8, 12, 24)
    const val DEFAULT_WINDOW_HOURS: Int = 4
    const val RECENT_HINT: String = "Needs a glance"
    private const val DAY_WINDOW_COUNT: Int = 7

    fun sanitizeWindowHours(hours: Int): Int {
        if (hours in ALLOWED_WINDOW_HOURS) return hours
        // Nearest allowed; ties prefer closer to default 4, then the larger value.
        return ALLOWED_WINDOW_HOURS.minWith(
            compareBy<Int> { abs(it - hours) }
                .thenBy { abs(it - DEFAULT_WINDOW_HOURS) }
                .thenByDescending { it },
        )
    }

    fun formatWindowHoursLabel(hours: Int): String {
        val sanitized = sanitizeWindowHours(hours)
        return if (sanitized == 1) "1 hour" else "$sanitized hours"
    }

    fun <T> group(
        sessions: List<T>,
        updatedAtEpochMs: (T) -> Long?,
        isAttention: (T) -> Boolean,
        recentWindowHours: Int,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<HomeSessionLadderSection<T>> {
        if (sessions.isEmpty()) return emptyList()

        val windowHours = sanitizeWindowHours(recentWindowHours)
        val cutoff = nowEpochMs - TimeUnit.HOURS.toMillis(windowHours.toLong())
        val sorted =
            sessions.sortedByDescending { updatedAtEpochMs(it) ?: Long.MIN_VALUE }

        val recent =
            sorted.filter { session ->
                isAttention(session) || (updatedAtEpochMs(session) ?: Long.MIN_VALUE) >= cutoff
            }
        val recentIds = recent.mapTo(mutableSetOf()) { it }
        val rest = sorted.filterNot { it in recentIds }

        val sections = mutableListOf<HomeSessionLadderSection<T>>()
        if (recent.isNotEmpty()) {
            sections +=
                HomeSessionLadderSection(
                    id = "recent",
                    kind = HomeSessionLadderKind.Recent,
                    title = "Recent",
                    hint = RECENT_HINT,
                    items = recent,
                )
        }

        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val dayFormatter =
            DateTimeFormatter.ofPattern("EEEE, MMM d", locale)
        for (offset in 0 until DAY_WINDOW_COUNT) {
            val day = today.minusDays(offset.toLong())
            val startMs = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endMs =
                day
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val items =
                rest.filter { session ->
                    val updated = updatedAtEpochMs(session) ?: return@filter false
                    updated in startMs until endMs
                }
            if (items.isEmpty()) continue
            val title =
                when (offset) {
                    0 -> "Today"
                    1 -> "Yesterday"
                    else -> day.format(dayFormatter)
                }
            sections +=
                HomeSessionLadderSection(
                    id = "day-$day",
                    kind = HomeSessionLadderKind.Day,
                    title = title,
                    items = items,
                )
        }

        val oldestDayStart =
            today
                .minusDays((DAY_WINDOW_COUNT - 1).toLong())
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val older =
            rest.filter { session ->
                (updatedAtEpochMs(session) ?: Long.MIN_VALUE) < oldestDayStart
            }
        if (older.isEmpty()) return sections

        val weekFormatter = DateTimeFormatter.ofPattern("MMM d", locale)
        val weekBuckets = linkedMapOf<LocalDate, MutableList<T>>()
        for (session in older) {
            val updated = updatedAtEpochMs(session) ?: Long.MIN_VALUE
            val localDate =
                if (updated == Long.MIN_VALUE) {
                    LocalDate.of(1970, 1, 1)
                } else {
                    Instant.ofEpochMilli(updated).atZone(zoneId).toLocalDate()
                }
            val weekStart = localDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            weekBuckets.getOrPut(weekStart) { mutableListOf() }.add(session)
        }

        weekBuckets.entries
            .sortedByDescending { it.key }
            .forEach { (weekStart, items) ->
                val weekEnd = weekStart.plusDays(6)
                val title =
                    "Week of ${weekStart.format(weekFormatter)} – ${weekEnd.format(weekFormatter)}"
                sections +=
                    HomeSessionLadderSection(
                        id = "week-$weekStart",
                        kind = HomeSessionLadderKind.Week,
                        title = title,
                        items = items,
                    )
            }

        return sections
    }
}
