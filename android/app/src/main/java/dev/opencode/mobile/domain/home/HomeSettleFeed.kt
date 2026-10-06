package dev.opencode.mobile.domain.home

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Swipe and sheet actions for a home row. Empty means the row does not swipe. */
enum class HomeSettleGesture {
    Settle,
    Snooze,
    Wake,
    Unsettle,
}

enum class SnoozeUnit {
    Minutes,
    Hours,
    Days,
    ;

    val label: String
        get() =
            when (this) {
                Minutes -> "Minutes"
                Hours -> "Hours"
                Days -> "Days"
            }
}

data class SnoozePreset(
    val id: String,
    val label: String,
    val whenLabel: String,
    val atEpochMs: Long,
)

data class HomeSettleSplit<T>(
    val ladder: List<T>,
    val settled: List<T>,
)

/**
 * Splits the home feed and resolves snooze choices.
 *
 * The home screen calls this only when settle is available. With the flag off,
 * [split] keeps today's concatenation and [gestures] returns nothing.
 */
object HomeSettleFeed {
    const val SETTLED_PAGE_SIZE: Int = 25

    private const val MINUTE_MS: Long = 60_000L
    private const val HOUR_MS: Long = 60 * MINUTE_MS
    private const val DAY_MS: Long = 24 * HOUR_MS
    private const val EVENING_HOUR: Int = 18
    private const val MORNING_HOUR: Int = 9

    fun <T> split(
        active: List<T>,
        archivedAndSettled: List<T>,
        showsSettledPage: Boolean,
        isSettled: (T) -> Boolean,
        isArchived: (T) -> Boolean,
        settledAtEpochMs: (T) -> Long?,
    ): HomeSettleSplit<T> {
        if (!showsSettledPage) {
            return HomeSettleSplit(ladder = active + archivedAndSettled, settled = emptyList())
        }
        val settled =
            archivedAndSettled
                .filter { isSettled(it) && !isArchived(it) }
                .sortedByDescending { settledAtEpochMs(it) ?: Long.MIN_VALUE }
        val archivedOnly = archivedAndSettled.filter { isArchived(it) || !isSettled(it) }
        return HomeSettleSplit(ladder = active + archivedOnly, settled = settled)
    }

    fun gestures(
        showsSettle: Boolean,
        isSettled: Boolean,
        isSnoozed: Boolean,
        snoozeBlocked: Boolean,
        isArchived: Boolean = false,
    ): List<HomeSettleGesture> {
        if (!showsSettle || isArchived) return emptyList()
        if (isSettled) return listOf(HomeSettleGesture.Unsettle)
        if (isSnoozed) return listOf(HomeSettleGesture.Wake)
        return if (snoozeBlocked) {
            listOf(HomeSettleGesture.Settle)
        } else {
            listOf(HomeSettleGesture.Settle, HomeSettleGesture.Snooze)
        }
    }

    fun isSnoozed(
        snoozedUntilEpochMs: Long?,
        nowEpochMs: Long,
        isSettled: Boolean,
    ): Boolean = !isSettled && snoozedUntilEpochMs != null && snoozedUntilEpochMs > nowEpochMs

    fun gestureLabel(gesture: HomeSettleGesture): String =
        when (gesture) {
            HomeSettleGesture.Settle -> "Settle"
            HomeSettleGesture.Snooze -> "Snooze"
            HomeSettleGesture.Wake -> "Wake"
            HomeSettleGesture.Unsettle -> "Unsettle"
        }

    /** `9:00 AM` today, `Tomorrow 9:00 AM`, otherwise `Mon 9:00 AM`. */
    fun untilLabel(
        epochMs: Long,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val wake = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        val time = clockLabel(epochMs, zoneId, locale)
        val wakeDate = wake.toLocalDate()
        val today = now.toLocalDate()
        return when {
            wakeDate == today -> time
            wakeDate == today.plusDays(1) -> "Tomorrow $time"
            else -> DateTimeFormatter.ofPattern("EEE", locale).format(wake) + " " + time
        }
    }

    /** Compact remaining time: `30m`, `2h`, `3d`, or `now` once the wake has passed. */
    fun wakeInLabel(
        snoozedUntilEpochMs: Long,
        nowEpochMs: Long,
    ): String {
        val remaining = snoozedUntilEpochMs - nowEpochMs
        if (remaining <= 0L) return "now"
        if (remaining < HOUR_MS) return "${maxOf(1L, (remaining + MINUTE_MS - 1) / MINUTE_MS)}m"
        if (remaining < DAY_MS) return "${(remaining + HOUR_MS - 1) / HOUR_MS}h"
        return "${(remaining + DAY_MS - 1) / DAY_MS}d"
    }

    fun snoozeUntilFromDuration(
        amount: Int,
        unit: SnoozeUnit,
        nowEpochMs: Long,
    ): Long? {
        if (amount < 1) return null
        val span =
            when (unit) {
                SnoozeUnit.Minutes -> amount * MINUTE_MS
                SnoozeUnit.Hours -> amount * HOUR_MS
                SnoozeUnit.Days -> amount * DAY_MS
            }
        return nowEpochMs + span
    }

    fun isoTimestamp(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    /**
     * In 1 hour, In 3 hours, This evening (only while it is more than an hour away),
     * Tomorrow 9:00, and Next week Monday 9:00. Sunday collapses Tomorrow and Next week.
     */
    fun snoozePresets(
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<SnoozePreset> {
        val presets = mutableListOf<SnoozePreset>()
        presets += clockPreset("hour", "In 1 hour", nowEpochMs + HOUR_MS, zoneId, locale)
        presets += clockPreset("three-hours", "In 3 hours", nowEpochMs + 3 * HOUR_MS, zoneId, locale)

        val evening = atLocalHour(nowEpochMs, zoneId, plusDays = 0, hour = EVENING_HOUR)
        if (evening - nowEpochMs > HOUR_MS) {
            presets += clockPreset("evening", "This evening", evening, zoneId, locale)
        }

        val tomorrow = atLocalHour(nowEpochMs, zoneId, plusDays = 1, hour = MORNING_HOUR)
        presets += clockPreset("tomorrow", "Tomorrow", tomorrow, zoneId, locale)

        val nextWeek = atLocalHour(nowEpochMs, zoneId, plusDays = daysUntilNextMonday(nowEpochMs, zoneId), hour = MORNING_HOUR)
        if (nextWeek != tomorrow) {
            presets += weekdayPreset("next-week", "Next week", nextWeek, zoneId, locale)
        }
        return presets
    }

    private fun clockPreset(
        id: String,
        label: String,
        atEpochMs: Long,
        zoneId: ZoneId,
        locale: Locale,
    ): SnoozePreset = SnoozePreset(id, label, clockLabel(atEpochMs, zoneId, locale), atEpochMs)

    private fun weekdayPreset(
        id: String,
        label: String,
        atEpochMs: Long,
        zoneId: ZoneId,
        locale: Locale,
    ): SnoozePreset {
        val whenLabel =
            if (id == "next-week") {
                val wake = Instant.ofEpochMilli(atEpochMs).atZone(zoneId)
                val day = DateTimeFormatter.ofPattern("EEE", locale).format(wake)
                val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(wake)
                "$day $time"
            } else {
                val wake = Instant.ofEpochMilli(atEpochMs).atZone(zoneId)
                DateTimeFormatter.ofPattern("h:mm a", locale).format(wake)
            }
        return SnoozePreset(id, label, whenLabel, atEpochMs)
    }

    private fun clockLabel(
        epochMs: Long,
        zoneId: ZoneId,
        locale: Locale,
    ): String = DateTimeFormatter.ofPattern("h:mm a", locale).format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

    private fun atLocalHour(
        nowEpochMs: Long,
        zoneId: ZoneId,
        plusDays: Int,
        hour: Int,
    ): Long {
        val date = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate().plusDays(plusDays.toLong())
        return date.atTime(hour, 0).atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun daysUntilNextMonday(
        nowEpochMs: Long,
        zoneId: ZoneId,
    ): Int {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).dayOfWeek
        val delta = (DayOfWeek.MONDAY.value - today.value + 7) % 7
        return if (delta == 0) 7 else delta
    }
}
