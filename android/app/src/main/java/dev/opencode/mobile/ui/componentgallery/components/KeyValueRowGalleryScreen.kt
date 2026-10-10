package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeKeyValueRow
import dev.opencode.mobile.ui.components.OpenCodeKeyValueStyle

@Composable
fun KeyValueRowGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeKeyValueRow(label = "Outcome", value = "Highlighted")
        OpenCodeKeyValueRow(label = "Backend", value = "native")
        OpenCodeKeyValueRow(label = "Tokens", value = "12.4k", style = OpenCodeKeyValueStyle.Stacked)
        OpenCodeKeyValueRow(label = "Cost", value = "$0.02", style = OpenCodeKeyValueStyle.Stacked)
    }
}
