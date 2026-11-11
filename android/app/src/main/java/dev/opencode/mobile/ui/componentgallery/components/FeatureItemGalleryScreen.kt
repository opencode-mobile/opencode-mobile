package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeFeatureItem
import dev.opencode.mobile.ui.components.OpenCodeFeatureItemTone
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun FeatureItemGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Neutral", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Column {
            OpenCodeFeatureItem(
                title = "Start a session as a draft",
                body = "Write first, then pick the project and worktree. The session is created when you send.",
                icon = OpenCodeIcons.Edit,
            )
            OpenCodeFeatureItem(
                title = "Title only",
                icon = OpenCodeIcons.Star,
            )
        }
        Text(text = "Success", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Column {
            OpenCodeSectionHeader(title = "Fixes")
            OpenCodeFeatureItem(
                title = "Back gesture fix",
                body = "Going back no longer opens message actions.",
                icon = OpenCodeIcons.Check,
                tone = OpenCodeFeatureItemTone.Success,
            )
        }
    }
}
