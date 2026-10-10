package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChatActivityState
import dev.opencode.mobile.ui.components.OpenCodePulsingDot
import dev.opencode.mobile.ui.components.OpenCodeWorkingIndicator

@Composable
fun WorkingIndicatorGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkingSample("Working") {
            OpenCodeWorkingIndicator(label = "Working", state = OpenCodeChatActivityState.Working)
        }
        WorkingSample("Idle") {
            OpenCodeWorkingIndicator(label = "Idle", state = OpenCodeChatActivityState.Idle)
        }
        WorkingSample("Expanded") {
            OpenCodeWorkingIndicator(label = "Retrying", state = OpenCodeChatActivityState.Expanded)
        }
        WorkingSample("Fading") {
            OpenCodeWorkingIndicator(label = "Idle", state = OpenCodeChatActivityState.Idle, fading = true)
        }
        WorkingSample("Collapsed") {
            OpenCodeWorkingIndicator(label = "Idle", state = OpenCodeChatActivityState.Collapsed)
        }
        OpenCodePulsingDot()
    }
}

@Composable
private fun WorkingSample(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
