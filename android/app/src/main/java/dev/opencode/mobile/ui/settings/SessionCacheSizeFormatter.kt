package dev.opencode.mobile.ui.settings

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import kotlin.math.roundToInt

/**
 * The session cache size for the Settings row, in binary units ("512 B", "2 KB", "1.5 MB").
 * The number is a [UiText.Number], so it is written in the reader's locale when resolved.
 */
object SessionCacheSizeFormatter {
    private const val KIB = 1024L
    private const val MIB = KIB * 1024L
    private const val GIB = MIB * 1024L
    private const val TIB = GIB * 1024L

    fun format(bytes: Long): UiText {
        if (bytes <= 0L) return UiText.resource(R.string.settings_session_cache_empty)
        if (bytes < KIB) return UiText.resource(R.string.settings_session_cache_bytes, bytes)
        if (bytes < MIB) {
            val kb = (bytes / KIB.toDouble()).roundToInt().coerceAtLeast(1)
            return UiText.resource(R.string.byte_count_kilobytes, UiText.number(kb.toDouble()))
        }
        if (bytes < GIB) return UiText.resource(R.string.byte_count_megabytes, oneDecimal(bytes, MIB))
        if (bytes < TIB) return UiText.resource(R.string.byte_count_gigabytes, oneDecimal(bytes, GIB))
        return UiText.resource(R.string.byte_count_terabytes, oneDecimal(bytes, TIB))
    }

    private fun oneDecimal(
        bytes: Long,
        unit: Long,
    ): UiText = UiText.number(bytes / unit.toDouble(), minFractionDigits = 1, maxFractionDigits = 1)
}
