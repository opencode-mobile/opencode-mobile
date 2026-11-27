package dev.opencode.mobile.domain.time

import dev.opencode.mobile.domain.text.DateTimeStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Writes moments for people in one [locale] and [zone] (#886).
 *
 * The app builds it from the platform's CLDR patterns for the current configuration
 * (`platformDateTimeFormat` in `ui.text`); unit tests pass the locale, zone and patterns
 * themselves so they do not depend on the host. Machine formats (wire timestamps, file names,
 * logs) do not belong here: they stay on fixed formatters.
 */
class LocalizedDateTimeFormat(
    val locale: Locale,
    val zone: ZoneId,
    /**
     * The locale's pattern for month and day without the year (the "MMMd" skeleton):
     * "MMM d" in en-US, "d. MMM" in de-DE.
     */
    monthDayPattern: String,
) {
    private val monthDay: DateTimeFormatter =
        try {
            DateTimeFormatter.ofPattern(monthDayPattern, locale)
        } catch (error: IllegalArgumentException) {
            // A platform pattern java.time cannot read: the locale's medium date (with the
            // year) is still right for the reader, where a fixed English pattern would not be.
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        }

    fun format(
        epochMs: Long,
        style: DateTimeStyle,
    ): String {
        val moment = Instant.ofEpochMilli(epochMs).atZone(zone)
        return when (style) {
            DateTimeStyle.MonthDay -> moment.format(monthDay)
        }
    }
}
