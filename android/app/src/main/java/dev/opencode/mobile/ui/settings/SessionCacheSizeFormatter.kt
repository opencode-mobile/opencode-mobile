package dev.opencode.mobile.ui.settings

import java.util.Locale
import kotlin.math.roundToInt

object SessionCacheSizeFormatter {
    fun format(bytes: Long): String {
        if (bytes <= 0L) return "Empty"
        if (bytes < 1024L) return "$bytes B"
        if (bytes < 1024L * 1024L) {
            val kb = (bytes / 1024.0).roundToInt().coerceAtLeast(1)
            return "$kb KB"
        }
        if (bytes < 1024L * 1024L * 1024L) {
            val mb = bytes / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB", mb)
        }
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f GB", gb)
    }
}
