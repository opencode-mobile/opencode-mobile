package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustration
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic

@Composable
fun SkeletonIllustrationGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OpenCodeSkeletonIllustrationTopic.entries.forEach { topic ->
            Text(
                text = topic.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OpenCodeSkeletonIllustration(
                topic = topic,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, openCodeColor("border-weaker-base"), RoundedCornerShape(20.dp)),
            )
        }
    }
}
