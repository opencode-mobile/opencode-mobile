package dev.opencode.mobile.domain.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

object RelativeTimeFormatting {
    private val absoluteFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun format(
        epochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String {
        val deltaMs = (nowEpochMs - epochMs).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
        val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
        return when {
            minutes < 1L -> "now"
            minutes < 60L -> "${minutes}m ago"
            hours < 24L -> "${hours}h ago"
            days < 7L -> "${days}d ago"
            else ->
                Instant
                    .ofEpochMilli(epochMs)
                    .atZone(ZoneId.systemDefault())
                    .format(absoluteFormatter)
        }
    }
}
