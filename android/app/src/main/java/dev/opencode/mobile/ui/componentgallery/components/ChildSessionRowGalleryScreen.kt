package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChildSessionRow
import dev.opencode.mobile.ui.components.OpenCodeChildSessionRowStatus
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge

@Composable
fun ChildSessionRowGalleryScreen(modifier: Modifier = Modifier) {
    var showCaption by rememberSaveable { mutableStateOf(false) }
    var showSubtitle by rememberSaveable { mutableStateOf(true) }
    var showElapsed by rememberSaveable { mutableStateOf(true) }
    var tappable by rememberSaveable { mutableStateOf(true) }
    var status by rememberSaveable { mutableStateOf<OpenCodeChildSessionRowStatus?>(null) }

    val caption = if (showCaption) "background" else null
    val subtitle = if (showSubtitle) "Standards review of OPE-144" else null
    val elapsed = if (showElapsed) "1:04" else null

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Content",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(
                    label = "Caption",
                    selected = showCaption,
                    onClick = { showCaption = !showCaption },
                )
                OpenCodeChoiceBadge(
                    label = "Subtitle",
                    selected = showSubtitle,
                    onClick = { showSubtitle = !showSubtitle },
                )
                OpenCodeChoiceBadge(
                    label = "Elapsed",
                    selected = showElapsed,
                    onClick = { showElapsed = !showElapsed },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeChoiceBadge(label = "None", selected = status == null, onClick = { status = null })
                OpenCodeChildSessionRowStatus.entries.forEach { option ->
                    OpenCodeChoiceBadge(
                        label = option.label.replaceFirstChar { it.uppercaseChar() },
                        selected = status == option,
                        onClick = { status = option },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Interaction",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OpenCodeChoiceBadge(
                label = "Tappable",
                selected = tappable,
                onClick = { tappable = !tappable },
            )
        }

        OpenCodeChildSessionRow(
            title = "explore",
            caption = caption,
            subtitle = subtitle,
            elapsed = elapsed,
            status = status,
            onClick = if (tappable) ({}) else null,
        )
    }
}
