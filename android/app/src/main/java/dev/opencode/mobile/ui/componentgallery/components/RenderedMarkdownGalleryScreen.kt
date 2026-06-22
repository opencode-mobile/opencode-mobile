package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown

@Composable
fun RenderedMarkdownGalleryScreen(modifier: Modifier = Modifier) {
    val samples = RenderedMarkdownGalleryMocks.samples
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    val selected = samples[selectedIndex]

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            samples.forEachIndexed { index, sample ->
                OpenCodeChoiceBadge(
                    label = sample.label,
                    selected = index == selectedIndex,
                    onClick = { selectedIndex = index },
                )
            }
        }

        OpenCodeRenderedMarkdown(
            document = selected.document,
            variant = selected.variant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
        )
    }
}
