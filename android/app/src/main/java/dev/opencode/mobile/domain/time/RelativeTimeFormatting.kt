package dev.opencode.mobile.domain.time

import dev.opencode.mobile.R
import dev.opencode.mobile.ui.text.UiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Short relative time ("now", "5m ago", "2d ago"), then the month and day after a week. */
object RelativeTimeFormatting {
    private val absoluteFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.US)

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
            else ->
                UiText.verbatim(
                    Instant
                        .ofEpochMilli(epochMs)
                        .atZone(ZoneId.systemDefault())
                        .format(absoluteFormatter),
                )
        }
    }
}
