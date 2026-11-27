package dev.opencode.mobile.domain.text

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Writes [UiText.Number] in one locale: its digits, decimal separator and grouping (#886). */
object LocalizedNumberFormat {
    fun format(
        number: UiText.Number,
        locale: Locale,
    ): String {
        val format =
            (if (number.percent) NumberFormat.getPercentInstance(locale) else NumberFormat.getNumberInstance(locale)).apply {
                minimumFractionDigits = number.minFractionDigits
                maximumFractionDigits = number.maxFractionDigits
                // Half up, like the String.format("%.1f") it replaces.
                roundingMode = RoundingMode.HALF_UP
            }
        val value = if (number.percent) number.value / 100 else number.value
        return format.format(value)
    }
}
