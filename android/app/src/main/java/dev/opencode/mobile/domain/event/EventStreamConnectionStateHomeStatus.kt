package dev.opencode.mobile.domain.event

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.opencode.mobile.theme.DarkStatusSuccess
import dev.opencode.mobile.theme.DarkStatusWarning
import dev.opencode.mobile.theme.LightStatusSuccess
import dev.opencode.mobile.theme.LightStatusWarning

val EventStreamConnectionState.homeStatusLabel: String
    get() =
        when (this) {
            EventStreamConnectionState.Idle -> "Live updates off"
            EventStreamConnectionState.Connecting -> "Connecting live updates…"
            EventStreamConnectionState.Connected -> "Live updates"
            EventStreamConnectionState.Disconnected -> "Live updates disconnected"
            EventStreamConnectionState.Reconnecting -> "Reconnecting live updates…"
        }

@Composable
fun EventStreamConnectionState.homeStatusTint(): Color {
    val isDark = isSystemInDarkTheme()
    return when (this) {
        EventStreamConnectionState.Connected ->
            if (isDark) DarkStatusSuccess else LightStatusSuccess
        EventStreamConnectionState.Connecting, EventStreamConnectionState.Reconnecting ->
            if (isDark) DarkStatusWarning else LightStatusWarning
        EventStreamConnectionState.Idle, EventStreamConnectionState.Disconnected ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}
