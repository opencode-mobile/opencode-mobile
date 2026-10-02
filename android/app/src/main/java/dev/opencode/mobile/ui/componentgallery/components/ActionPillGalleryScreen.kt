package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge

@Composable
fun ActionPillGalleryScreen(modifier: Modifier = Modifier) {
    var enabled by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Floating pill for contextual overlay actions such as scroll to end.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OpenCodeChoiceBadge(
            label = "Enabled",
            selected = enabled,
            onClick = { enabled = !enabled },
        )

        OpenCodeActionPillVariant.entries.forEach { variant ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OpenCodeActionPill(
                    variant = variant,
                    enabled = enabled,
                    onClick = {},
                )
            }
        }
    }
}
