package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSessionStatusBar

@Composable
fun SessionStatusBarGalleryScreen(modifier: Modifier = Modifier) {
    var statusName by rememberSaveable { mutableStateOf(OpenCodeSessionConnectionStatus.Live.name) }
    var contextAvailable by rememberSaveable { mutableStateOf(true) }
    val status =
        runCatching { OpenCodeSessionConnectionStatus.valueOf(statusName) }
            .getOrDefault(OpenCodeSessionConnectionStatus.Live)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Session status bar",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ControlGroup(title = "Connection") {
                OpenCodeSessionConnectionStatus.entries.forEach { option ->
                    OpenCodeChoiceBadge(
                        label = option.label,
                        selected = option == status,
                        onClick = { statusName = option.name },
                    )
                }
            }

            ControlGroup(title = "Context") {
                OpenCodeChoiceBadge(
                    label = "Available",
                    selected = contextAvailable,
                    onClick = { contextAvailable = true },
                )
                OpenCodeChoiceBadge(
                    label = "Unavailable",
                    selected = !contextAvailable,
                    onClick = { contextAvailable = false },
                )
            }
        }

        OpenCodeSessionStatusBar(
            sessionId = "ses_01HZX9TvS3EfE4",
            connectionStatus = status,
            contextUsageFraction = if (contextAvailable) 0.42f else null,
            onCopySessionId = {},
            onConnectionClick = {},
            onContextClick = {},
        )
    }
}

@Composable
private fun ControlGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}
