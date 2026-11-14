package dev.opencode.mobile.ui.serverhealth

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.domain.serverhealth.ServerHealthFailureCategory
import dev.opencode.mobile.domain.serverhealth.ServerHealthRange
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** Numbers as Server Health writes them. */
object ServerHealthFormat {
    fun milliseconds(value: Int): String = "$value ms"

    /** `4.2 s` under 10 s, `42 s` under a minute, `12 m`, `2 h 18 m`, then `3 d 4 h`. */
    fun duration(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        return when {
            safe < 10_000 -> "${safe / 1_000}.${(safe % 1_000) / 100} s"
            safe < 60_000 -> "${safe / 1_000} s"
            safe < 3_600_000 -> "${safe / 60_000} m"
            safe < 86_400_000 -> "${safe / 3_600_000} h ${(safe % 3_600_000) / 60_000} m"
            else -> "${safe / 86_400_000} d ${(safe % 86_400_000) / 3_600_000} h"
        }
    }

    /** One decimal; two from 99.95 up to (not including) 100, so 99.97% never reads as 100.0%. */
    fun percent(value: Double): String {
        val twoDecimals = value >= 99.95 && value < 100.0
        if (twoDecimals) {
            val truncated = floor(value * 100 + 1e-6) / 100
            return String.format(Locale.ROOT, "%.2f%%", truncated)
        }
        val rounded = (value * 10).roundToLong() / 10.0
        return String.format(Locale.ROOT, "%.1f%%", rounded)
    }

    fun statusLabel(state: ServerHealthConnectionState): Int =
        when (state) {
            ServerHealthConnectionState.Live -> R.string.server_health_status_live
            ServerHealthConnectionState.Connecting -> R.string.server_health_status_connecting
            ServerHealthConnectionState.Reconnecting -> R.string.server_health_status_reconnecting
            ServerHealthConnectionState.Offline -> R.string.server_health_status_offline
            ServerHealthConnectionState.AuthFailed -> R.string.server_health_status_auth_failed
            ServerHealthConnectionState.Stopped -> R.string.server_health_status_stopped
        }

    fun categoryLabel(category: ServerHealthFailureCategory): Int =
        when (category) {
            ServerHealthFailureCategory.Network -> R.string.server_health_category_network
            ServerHealthFailureCategory.Timeout -> R.string.server_health_category_timeout
            ServerHealthFailureCategory.Heartbeat -> R.string.server_health_category_heartbeat
            ServerHealthFailureCategory.Server -> R.string.server_health_category_server
            ServerHealthFailureCategory.Auth -> R.string.server_health_category_auth
            ServerHealthFailureCategory.Other -> R.string.server_health_category_other
        }

    fun rangeLabel(range: ServerHealthRange): Int =
        when (range) {
            ServerHealthRange.Hour -> R.string.server_health_range_hour
            ServerHealthRange.Day -> R.string.server_health_range_day
            ServerHealthRange.Week -> R.string.server_health_range_week
        }
}
