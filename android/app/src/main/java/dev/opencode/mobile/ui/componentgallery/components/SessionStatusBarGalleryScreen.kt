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
import dev.opencode.mobile.ui.components.OpenCodeSessionStatusBar

@Composable
fun SessionStatusBarGalleryScreen(modifier: Modifier = Modifier) {
    var contextAvailable by rememberSaveable { mutableStateOf(true) }
    var contextUsagePercent by rememberSaveable { mutableStateOf(50) }
    val contextUsageFraction = if (contextAvailable) contextUsagePercent / 100f else null

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

            ControlGroup(title = "Context usage") {
                listOf(0, 50, 70, 85).forEach { percent ->
                    OpenCodeChoiceBadge(
                        label = "$percent%",
                        selected = contextUsagePercent == percent,
                        enabled = contextAvailable,
                        onClick = { contextUsagePercent = percent },
                    )
                }
            }
        }

        OpenCodeSessionStatusBar(
            contextUsageFraction = contextUsageFraction,
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
