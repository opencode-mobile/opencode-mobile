package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodePendingPromptsStrip
import dev.opencode.mobile.ui.sessionchat.PendingPrompt

private enum class PendingPromptsStripSample(
    val label: String,
    val prompts: List<PendingPrompt>,
) {
    SingleSteer(
        label = "Single steer",
        prompts =
            listOf(
                PendingPrompt("1", "Also check the compact path.", FollowUpDelivery.Steer),
            ),
    ),
    SingleQueued(
        label = "Single queued",
        prompts =
            listOf(
                PendingPrompt("1", "Also check the compact path.", FollowUpDelivery.Queue),
            ),
    ),
    SteerPlusQueued(
        label = "Steer + 2 queued",
        prompts =
            listOf(
                PendingPrompt("1", "Steer this first.", FollowUpDelivery.Steer),
                PendingPrompt("2", "Second queued prompt", FollowUpDelivery.Queue),
                PendingPrompt("3", "Third queued prompt", FollowUpDelivery.Queue),
            ),
    ),
    LongText(
        label = "Long text",
        prompts =
            listOf(
                PendingPrompt(
                    "1",
                    "First queued prompt\nwith a second line that should collapse and ellipsize",
                    FollowUpDelivery.Queue,
                ),
                PendingPrompt("2", "Another", FollowUpDelivery.Queue),
            ),
    ),
}

@Composable
fun PendingPromptsStripGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableStateOf(PendingPromptsStripSample.SingleSteer) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Switch samples to review steer, queued, mixed, and long-text pending prompt strips.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PendingPromptsStripSample.entries.take(2).forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.label,
                    selected = sample == option,
                    onClick = { sample = option },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PendingPromptsStripSample.entries.drop(2).forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.label,
                    selected = sample == option,
                    onClick = { sample = option },
                )
            }
        }

        OpenCodePendingPromptsStrip(
            prompts = sample.prompts,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
