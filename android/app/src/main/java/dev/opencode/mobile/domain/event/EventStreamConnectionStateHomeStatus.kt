package dev.opencode.mobile.domain.event

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.opencode.mobile.theme.openCodeColor

val EventStreamConnectionState.homeStatusLabel: String
    get() =
        when (this) {
            EventStreamConnectionState.Idle -> "Live updates off"
            EventStreamConnectionState.Connecting -> "Connecting live updates\u2026"
            EventStreamConnectionState.Connected -> "Live updates"
            EventStreamConnectionState.Disconnected -> "Live updates disconnected"
            EventStreamConnectionState.Reconnecting -> "Reconnecting live updates\u2026"
        }

@Composable
fun EventStreamConnectionState.homeStatusTint(): Color {
    val isDark = isSystemInDarkTheme()
    return when (this) {
        EventStreamConnectionState.Connected ->
            openCodeColor("success", isDark)
        EventStreamConnectionState.Connecting, EventStreamConnectionState.Reconnecting ->
            openCodeColor("warning", isDark)
        EventStreamConnectionState.Idle, EventStreamConnectionState.Disconnected ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}
