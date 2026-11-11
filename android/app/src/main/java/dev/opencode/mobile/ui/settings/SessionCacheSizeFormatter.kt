package dev.opencode.mobile.ui.settings

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import java.util.Locale
import kotlin.math.roundToInt

object SessionCacheSizeFormatter {
    fun format(bytes: Long): UiText {
        if (bytes <= 0L) return UiText.resource(R.string.settings_session_cache_empty)
        if (bytes < 1024L) return UiText.resource(R.string.settings_session_cache_bytes, bytes)
        if (bytes < 1024L * 1024L) {
            val kb = (bytes / 1024.0).roundToInt().coerceAtLeast(1)
            return UiText.resource(R.string.settings_session_cache_kilobytes, kb)
        }
        if (bytes < 1024L * 1024L * 1024L) {
            val mb = bytes / (1024.0 * 1024.0)
            return UiText.resource(R.string.settings_session_cache_megabytes, String.format(Locale.US, "%.1f", mb))
        }
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return UiText.resource(R.string.settings_session_cache_gigabytes, String.format(Locale.US, "%.1f", gb))
    }
}
