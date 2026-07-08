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
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private enum class GalleryTopBarLeading {
    None,
    Back,
    Close,
}

private enum class GalleryTopBarActions {
    None,
    Icons,
    Label,
}

@Composable
fun TopBarGalleryScreen(modifier: Modifier = Modifier) {
    var leadingName by rememberSaveable { mutableStateOf(GalleryTopBarLeading.None.name) }
    var actionsName by rememberSaveable { mutableStateOf(GalleryTopBarActions.None.name) }
    var showDivider by rememberSaveable { mutableStateOf(false) }
    var showTwoLine by rememberSaveable { mutableStateOf(false) }
    var showStatusDot by rememberSaveable { mutableStateOf(false) }
    var statusName by rememberSaveable { mutableStateOf(OpenCodeSessionConnectionStatus.Live.name) }
    var actionsEnabled by rememberSaveable { mutableStateOf(true) }

    val leadingSelection =
        try {
            GalleryTopBarLeading.valueOf(leadingName)
        } catch (_: IllegalArgumentException) {
            GalleryTopBarLeading.None
        }
    val actionsSelection =
        try {
            GalleryTopBarActions.valueOf(actionsName)
        } catch (_: IllegalArgumentException) {
            GalleryTopBarActions.None
        }
    val title =
        when (actionsSelection) {
            GalleryTopBarActions.None -> "Component Gallery"
            GalleryTopBarActions.Icons -> "Session actions"
            GalleryTopBarActions.Label -> "Project settings"
        }
    val subtitle = if (showTwoLine && !showStatusDot) "Importing icons explanation" else null
    val connectionStatus =
        runCatching { OpenCodeSessionConnectionStatus.valueOf(statusName) }
            .getOrDefault(OpenCodeSessionConnectionStatus.Live)
    val subtitleAccessory =
        if (showStatusDot) {
            OpenCodeTopBarSubtitleAccessory.StatusDot(connectionStatus)
        } else {
            null
        }
    val leading =
        when (leadingSelection) {
            GalleryTopBarLeading.None -> OpenCodeTopBarLeading.None
            GalleryTopBarLeading.Back -> OpenCodeTopBarLeading.Back(onClick = {})
            GalleryTopBarLeading.Close -> OpenCodeTopBarLeading.Close(onClick = {})
        }
    val actions =
        when (actionsSelection) {
            GalleryTopBarActions.None -> emptyList()
            GalleryTopBarActions.Icons ->
                listOf(
                    OpenCodeTopBarAction.Icon(OpenCodeIcons.Search, "Search", enabled = actionsEnabled, onClick = {}),
                    OpenCodeTopBarAction.Icon(OpenCodeIcons.MoreVert, "More actions", enabled = actionsEnabled, onClick = {}),
                )
            GalleryTopBarActions.Label ->
                listOf(OpenCodeTopBarAction.Label("Done", enabled = actionsEnabled, onClick = {}))
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Top bar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)

            ControlGroup(title = "Leading") {
                GalleryTopBarLeading.entries.forEach { option ->
                    OpenCodeChoiceBadge(
                        label =
                            when (option) {
                                GalleryTopBarLeading.None -> "None"
                                GalleryTopBarLeading.Back -> "Back"
                                GalleryTopBarLeading.Close -> "Close"
                            },
                        selected = option == leadingSelection,
                        onClick = { leadingName = option.name },
                    )
                }
            }

            ControlGroup(title = "Actions") {
                GalleryTopBarActions.entries.forEach { option ->
                    OpenCodeChoiceBadge(
                        label =
                            when (option) {
                                GalleryTopBarActions.None -> "None"
                                GalleryTopBarActions.Icons -> "Icons"
                                GalleryTopBarActions.Label -> "Label"
                            },
                        selected = option == actionsSelection,
                        onClick = { actionsName = option.name },
                    )
                }
            }

            ControlGroup(title = "State") {
                OpenCodeChoiceBadge(label = "Two-line", selected = showTwoLine, onClick = { showTwoLine = !showTwoLine })
                OpenCodeChoiceBadge(label = "Status dot", selected = showStatusDot, onClick = { showStatusDot = !showStatusDot })
                OpenCodeChoiceBadge(label = "Divider", selected = showDivider, onClick = { showDivider = !showDivider })
                OpenCodeChoiceBadge(
                    label = "Actions enabled",
                    selected = actionsEnabled,
                    onClick = { actionsEnabled = !actionsEnabled },
                )
            }

            if (showStatusDot) {
                ControlGroup(title = "Connection") {
                    OpenCodeSessionConnectionStatus.entries.forEach { option ->
                        OpenCodeChoiceBadge(
                            label = option.label,
                            selected = option == connectionStatus,
                            onClick = { statusName = option.name },
                        )
                    }
                }
            }

            Text(
                text =
                    "Switch controls to review title-only, two-line, status-dot subtitle, back, close, icon-action, " +
                        "labeled-action, divider, and disabled-action states.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OpenCodeTopBar(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            subtitle = subtitle,
            subtitleAccessory = subtitleAccessory,
            leading = leading,
            actions = actions,
            showDivider = showDivider,
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
