package dev.opencode.mobile.ui.serverhealth

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.domain.serverhealth.ServerHealthFailureCategory
import dev.opencode.mobile.domain.serverhealth.ServerHealthRange
import dev.opencode.mobile.domain.text.LocalizedNumberFormat
import dev.opencode.mobile.domain.text.UiText
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** Numbers as Server Health writes them. */
object ServerHealthFormat {
    fun milliseconds(value: Int): String = "$value ms"

    /**
     * `4.2 s` under 10 s, `42 s` under a minute, `12 m`, `2 h 18 m`, then `3 d 4 h`. The tenths
     * of a second use [locale]'s decimal separator ("4,2 s" in de-DE).
     */
    fun duration(
        ms: Long,
        locale: Locale,
    ): String {
        val safe = ms.coerceAtLeast(0)
        return when {
            safe < 10_000 ->
                LocalizedNumberFormat.format(UiText.Number(safe / 100 / 10.0, 1, 1), locale) + " s"
            safe < 60_000 -> "${safe / 1_000} s"
            safe < 3_600_000 -> "${safe / 60_000} m"
            safe < 86_400_000 -> "${safe / 3_600_000} h ${(safe % 3_600_000) / 60_000} m"
            else -> "${safe / 86_400_000} d ${(safe % 86_400_000) / 3_600_000} h"
        }
    }

    /** [duration] for a time axis, without a zero trailing unit: `1 h`, `7 d`, but `1 h 5 m`. */
    fun axisDuration(
        ms: Long,
        locale: Locale,
    ): String = duration(ms, locale).removeSuffix(" 0 m").removeSuffix(" 0 h")

    /** The measured time to show, or null when nothing was measured. */
    fun measuredDuration(
        observedMs: Long,
        locale: Locale,
    ): String? = if (observedMs > 0) duration(observedMs, locale) else null

    /**
     * One decimal; two from 99.95 up to (not including) 100, so 99.97% never reads as 100.0%.
     * Written in the reader's locale when resolved ("99,9 %" in de-DE).
     */
    fun percent(value: Double): UiText {
        val twoDecimals = value >= 99.95 && value < 100.0
        if (twoDecimals) {
            val truncated = floor(value * 100 + 1e-6) / 100
            return UiText.percent(truncated, fractionDigits = 2)
        }
        val rounded = (value * 10).roundToLong() / 10.0
        return UiText.percent(rounded, fractionDigits = 1)
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
