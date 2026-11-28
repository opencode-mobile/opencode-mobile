package dev.opencode.mobile.ui.sessioncontext

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * A Session Context stat value. Numbers, costs and dates stay unformatted in UI state; the
 * screen writes them with [SessionContextStatFormat] in the current locale (#886).
 */
sealed interface SessionContextStatValue {
    /** Session content (title, provider, model), shown as is. Null shows a dash. */
    data class Text(
        val value: String?,
    ) : SessionContextStatValue

    data class Count(
        val value: Long?,
    ) : SessionContextStatValue

    /** Two counts, such as cache reads and writes. */
    data class CountPair(
        val first: Long?,
        val second: Long?,
    ) : SessionContextStatValue

    /** A whole percentage, such as 45 for 45%. */
    data class Percent(
        val value: Int?,
    ) : SessionContextStatValue

    /** A cost in US dollars, the currency providers report. */
    data class Cost(
        val usd: Double,
    ) : SessionContextStatValue

    data class Moment(
        val epochMs: Long?,
    ) : SessionContextStatValue
}

/** Writes [SessionContextStatValue]s in one [locale] and [zone]. */
class SessionContextStatFormat(
    private val locale: Locale,
    private val zone: ZoneId,
) {
    private val integers = NumberFormat.getIntegerInstance(locale)
    private val percents = NumberFormat.getPercentInstance(locale)
    private val dollars =
        NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("USD") }
    private val moments = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)

    fun format(value: SessionContextStatValue): String =
        when (value) {
            is SessionContextStatValue.Text -> value.value ?: MISSING
            is SessionContextStatValue.Count -> count(value.value)
            is SessionContextStatValue.CountPair -> "${count(value.first)} / ${count(value.second)}"
            is SessionContextStatValue.Percent -> value.value?.let { percents.format(it / 100.0) } ?: MISSING
            is SessionContextStatValue.Cost -> dollars.format(value.usd)
            is SessionContextStatValue.Moment ->
                value.epochMs?.let { Instant.ofEpochMilli(it).atZone(zone).format(moments) } ?: MISSING
        }

    private fun count(value: Long?): String = value?.let(integers::format) ?: MISSING

    private companion object {
        const val MISSING = "—"
    }
}
