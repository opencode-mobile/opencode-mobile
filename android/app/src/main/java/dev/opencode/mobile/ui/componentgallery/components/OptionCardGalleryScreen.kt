package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeOptionCard
import dev.opencode.mobile.ui.components.OpenCodeOptionCardPreview
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic
import dev.opencode.mobile.ui.components.OpenCodeTagTone

@Composable
fun OptionCardGalleryScreen(modifier: Modifier = Modifier) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GallerySectionTitle("Single choice with previews")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeOptionCard(
                title = "Activity Card",
                subtitle = "Running steps on one live card that folds into a summary",
                tag = "Recommended",
                tagTone = OpenCodeTagTone.Accent,
                preview = OpenCodeOptionCardPreview.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineActivityCard),
                selected = selectedIndex == 0,
                onClick = { selectedIndex = 0 },
            )
            OpenCodeOptionCard(
                title = "Standard",
                subtitle = "Tool cards and groups between the agent's messages",
                tag = "Current",
                preview = OpenCodeOptionCardPreview.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineStandard),
                selected = selectedIndex == 1,
                onClick = { selectedIndex = 1 },
            )
            OpenCodeOptionCard(
                title = "Step Rail",
                subtitle = "One line per step on a rail, live while the agent works",
                preview = OpenCodeOptionCardPreview.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineStepRail),
                selected = selectedIndex == 2,
                onClick = { selectedIndex = 2 },
            )
        }

        GallerySectionTitle("Long title: the tag moves under it")
        OpenCodeOptionCard(
            title = "Activity Card with a longer title",
            subtitle = "Running steps on one live card that folds into a summary when the turn ends",
            tag = "Recommended",
            tagTone = OpenCodeTagTone.Accent,
            preview = OpenCodeOptionCardPreview.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineActivityCard),
            onClick = {},
        )

        GallerySectionTitle("Without preview")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeOptionCard(title = "Title only", selected = true, onClick = {})
            OpenCodeOptionCard(title = "With subtitle and tag", subtitle = "A short description", tag = "Beta", onClick = {})
        }

        GallerySectionTitle("Disabled")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeOptionCard(
                title = "Chips",
                subtitle = "Tools as chips under the agent's narration",
                preview = OpenCodeOptionCardPreview.Illustration(OpenCodeSkeletonIllustrationTopic.TimelineChips),
                enabled = false,
                onClick = {},
            )
            OpenCodeOptionCard(title = "Selected and disabled", selected = true, enabled = false, onClick = {})
        }
    }
}

@Composable
private fun GallerySectionTitle(title: String) {
    Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}
