package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeModal
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustration
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic

@Composable
fun ModalGalleryScreen(modifier: Modifier = Modifier) {
    var visible by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Modal",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Centered card with a close button and custom content. Scrim tap, back, and close dismiss.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Show modal"),
            onClick = { visible = true },
        )
    }

    OpenCodeModal(visible = visible, onDismissRequest = { visible = false }) {
        OpenCodeSkeletonIllustration(
            topic = OpenCodeSkeletonIllustrationTopic.ChipTimelineGlance,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "See the work at a glance",
            style = MaterialTheme.typography.titleLarge,
            color = openCodeColor("text-strong"),
        )
        Spacer(Modifier.height(16.dp))
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Got it"),
            onClick = { visible = false },
        )
    }
}
