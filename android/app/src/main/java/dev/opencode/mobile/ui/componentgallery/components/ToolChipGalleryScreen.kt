package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import dev.opencode.mobile.ui.components.OpenCodeToolChip
import dev.opencode.mobile.ui.components.OpenCodeToolChipStatus
import dev.opencode.mobile.ui.components.OpenCodeToolChipVariant

private enum class ToolChipGalleryVariant(
    val label: String,
) {
    File("File"),
    Change("Change"),
    Command("Command"),
    Agent("Agent"),
    Skill("Skill"),
    More("More"),
}

private fun ToolChipGalleryVariant.toVariant(): OpenCodeToolChipVariant =
    when (this) {
        ToolChipGalleryVariant.File -> OpenCodeToolChipVariant.File("SessionChatTimelineView.kt")
        ToolChipGalleryVariant.Change -> OpenCodeToolChipVariant.File("SessionChatTimeline.kt", additions = 12, deletions = 3)
        ToolChipGalleryVariant.Command -> OpenCodeToolChipVariant.Command("./gradlew :app:testDebugUnitTest")
        ToolChipGalleryVariant.Agent -> OpenCodeToolChipVariant.Agent("Explore", "Find where the branch pill is rendered")
        ToolChipGalleryVariant.Skill -> OpenCodeToolChipVariant.Skill("/feature")
        ToolChipGalleryVariant.More -> OpenCodeToolChipVariant.More(3)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolChipGalleryScreen(modifier: Modifier = Modifier) {
    var variantName by rememberSaveable { mutableStateOf(ToolChipGalleryVariant.Change.name) }
    var statusName by rememberSaveable { mutableStateOf(OpenCodeToolChipStatus.Completed.name) }
    var showTrailing by rememberSaveable { mutableStateOf(false) }
    var tappable by rememberSaveable { mutableStateOf(true) }

    val variant =
        ToolChipGalleryVariant.entries.firstOrNull { it.name == variantName } ?: ToolChipGalleryVariant.Change
    val status =
        OpenCodeToolChipStatus.entries.firstOrNull { it.name == statusName } ?: OpenCodeToolChipStatus.Completed

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Variant",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolChipGalleryVariant.entries.forEach { value ->
                    OpenCodeChoiceBadge(
                        label = value.label,
                        selected = variant == value,
                        onClick = { variantName = value.name },
                    )
                }
            }
        }

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
                OpenCodeToolChipStatus.entries.forEach { value ->
                    OpenCodeChoiceBadge(
                        label = value.name,
                        selected = status == value,
                        onClick = { statusName = value.name },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Options",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(
                    label = "Trailing text",
                    selected = showTrailing,
                    onClick = { showTrailing = !showTrailing },
                )
                OpenCodeChoiceBadge(
                    label = "Tappable",
                    selected = tappable,
                    onClick = { tappable = !tappable },
                )
            }
        }

        OpenCodeToolChip(
            variant = variant.toVariant(),
            status = status,
            trailingText = if (showTrailing) "42s" else null,
            onClick = if (tappable) ({}) else null,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Every variant × status",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OpenCodeToolChipStatus.entries.forEach { rowStatus ->
                Text(
                    text = rowStatus.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ToolChipGalleryVariant.entries.forEach { rowVariant ->
                        OpenCodeToolChip(
                            variant = rowVariant.toVariant(),
                            status = rowStatus,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}
