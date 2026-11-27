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
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = number.minFractionDigits
                maximumFractionDigits = number.maxFractionDigits
                // Half up, like the String.format("%.1f") it replaces.
                roundingMode = RoundingMode.HALF_UP
            }
        return format.format(number.value) + number.suffix
    }
}
