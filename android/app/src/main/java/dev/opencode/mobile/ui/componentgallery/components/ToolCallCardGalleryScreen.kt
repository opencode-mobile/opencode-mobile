package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus

@Composable
fun ToolCallCardGalleryScreen(modifier: Modifier = Modifier) {
    var statusName by rememberSaveable { mutableStateOf(OpenCodeToolCallCardStatus.Completed.name) }
    var showSubtitle by rememberSaveable { mutableStateOf(true) }
    var showDiffCounts by rememberSaveable { mutableStateOf(true) }
    var showErrorText by rememberSaveable { mutableStateOf(false) }
    var tappable by rememberSaveable { mutableStateOf(false) }

    val status =
        try {
            OpenCodeToolCallCardStatus.valueOf(statusName)
        } catch (_: IllegalArgumentException) {
            OpenCodeToolCallCardStatus.Completed
        }

    val title =
        when (status) {
            OpenCodeToolCallCardStatus.Pending -> "Called bash"
            OpenCodeToolCallCardStatus.Running -> "Called task"
            OpenCodeToolCallCardStatus.Completed -> "Called edit"
            OpenCodeToolCallCardStatus.Error -> "Called webfetch"
        }
    val subtitle = if (showSubtitle) "OpenCodeListItem.kt" else null
    val additions = if (showDiffCounts) 12 else null
    val deletions = if (showDiffCounts) 3 else null
    val errorText =
        if (showErrorText) {
            "Request failed with status 403"
        } else {
            null
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeToolCallCardStatus.entries.forEach { value ->
                    OpenCodeChoiceBadge(
                        label = value.name.lowercase().replaceFirstChar { it.titlecase() },
                        selected = status == value,
                        onClick = {
                            statusName = value.name
                            if (value == OpenCodeToolCallCardStatus.Error) {
                                showErrorText = true
                            }
                        },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Content",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(
                    label = "Subtitle",
                    selected = showSubtitle,
                    onClick = { showSubtitle = !showSubtitle },
                )
                OpenCodeChoiceBadge(
                    label = "+/−",
                    selected = showDiffCounts,
                    onClick = { showDiffCounts = !showDiffCounts },
                )
                OpenCodeChoiceBadge(
                    label = "Error text",
                    selected = showErrorText,
                    onClick = { showErrorText = !showErrorText },
                )
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

        OpenCodeToolCallCard(
            status = status,
            title = title,
            subtitle = subtitle,
            additions = additions,
            deletions = deletions,
            errorText = errorText,
            onClick = if (tappable) ({}) else null,
        )
    }
}
