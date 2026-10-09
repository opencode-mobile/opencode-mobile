package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeSelectableCard

@Composable
fun SelectableCardGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeSelectableCard(
            title = "Porcelain",
            blurb = "Warm paper and ink.",
            selected = false,
            onClick = {},
            preview = { PreviewSlot() },
        )
        OpenCodeSelectableCard(
            title = "Porcelain",
            blurb = "Warm paper and ink.",
            selected = true,
            onClick = {},
            preview = { PreviewSlot() },
        )
    }
}

@Composable
private fun PreviewSlot() {
    Text(
        text = "Preview",
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(openCodeColor("surface-base"))
                .padding(8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = openCodeColor("text-base"),
    )
}
