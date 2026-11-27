package dev.opencode.mobile.domain.time

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.DateTimeStyle
import dev.opencode.mobile.domain.text.UiText
import java.util.concurrent.TimeUnit

/**
 * Short relative time ("now", "5m ago", "2d ago"), then the month and day after a week. The
 * month and day is a [UiText.DateTime], written in the reader's locale when resolved.
 */
object RelativeTimeFormatting {
    fun format(
        epochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): UiText {
        val deltaMs = (nowEpochMs - epochMs).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
        val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
        return when {
            minutes < 1L -> UiText.resource(R.string.relative_time_now)
            minutes < 60L -> UiText.plural(R.plurals.relative_time_minutes_ago, minutes.toInt())
            hours < 24L -> UiText.plural(R.plurals.relative_time_hours_ago, hours.toInt())
            days < 7L -> UiText.plural(R.plurals.relative_time_days_ago, days.toInt())
            else -> UiText.dateTime(epochMs, DateTimeStyle.MonthDay)
        }
    }
}
