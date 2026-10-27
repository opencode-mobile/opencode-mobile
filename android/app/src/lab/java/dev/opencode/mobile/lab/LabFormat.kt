package dev.opencode.mobile.lab

import java.util.Locale

/** Number formats shared by the Lab's screens and reports. */
internal object LabFormat {
    fun ms(value: Double): String =
        when {
            value < 10 -> String.format(Locale.US, "%.2f ms", value)
            value < 100 -> String.format(Locale.US, "%.1f ms", value)
            else -> String.format(Locale.US, "%,.0f ms", value)
        }

    fun count(value: Int): String = String.format(Locale.US, "%,d", value)

    fun rate(value: Double): String = String.format(Locale.US, if (value < 100) "%.1f" else "%,.0f", value)

    fun percent(share: Double): String = String.format(Locale.US, "%.2f%%", share * 100)

    fun ratio(value: Double): String = String.format(Locale.US, "%.2f×", value)
}
