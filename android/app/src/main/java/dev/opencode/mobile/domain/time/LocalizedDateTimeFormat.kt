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
 * themselves so they do not depend on the host. The pattern defaults are what Android gives
 * en-US. Machine formats (wire timestamps, file names, logs) do not belong here: they stay on
 * fixed formatters.
 */
class LocalizedDateTimeFormat(
    val locale: Locale,
    val zone: ZoneId,
    /**
     * The locale's pattern for month and day without the year (the "MMMd" skeleton):
     * "MMM d" in en-US, "d. MMM" in de-DE.
     */
    monthDayPattern: String = "MMM d",
    /** The "EEEEMMMd" skeleton: "EEEE, MMM d" in en-US, "EEEE, d. MMM" in de-DE. */
    weekdayMonthDayPattern: String = "EEEE, MMM d",
    /**
     * Hour and minute in the reader's 12/24-hour choice (the "hm" or "Hm" skeleton):
     * "h:mm a" or "HH:mm" in en-US.
     */
    timePattern: String = "h:mm a",
) {
    private val monthDay = formatter(monthDayPattern) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    private val weekdayMonthDay = formatter(weekdayMonthDayPattern) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    private val time = formatter(timePattern) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    fun format(
        epochMs: Long,
        style: DateTimeStyle,
    ): String {
        val moment = Instant.ofEpochMilli(epochMs).atZone(zone)
        return when (style) {
            DateTimeStyle.MonthDay -> moment.format(monthDay)
            DateTimeStyle.WeekdayMonthDay -> moment.format(weekdayMonthDay)
        }
    }

    /** Hour and minute of [epochMs]. */
    fun time(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(zone).format(time)

    private fun formatter(
        pattern: String,
        fallback: () -> DateTimeFormatter,
    ): DateTimeFormatter =
        try {
            DateTimeFormatter.ofPattern(pattern, locale)
        } catch (error: IllegalArgumentException) {
            // A platform pattern java.time cannot read (such as a day-period letter): the
            // locale's own localized style is still right for the reader, where a fixed English
            // pattern would not be.
            fallback().withLocale(locale)
        }
}
