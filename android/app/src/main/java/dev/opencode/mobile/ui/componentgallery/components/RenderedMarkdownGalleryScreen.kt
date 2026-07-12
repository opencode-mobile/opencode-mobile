package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown

@Composable
fun RenderedMarkdownGalleryScreen(
    modifier: Modifier = Modifier,
    viewModel: RenderedMarkdownGalleryViewModel = viewModel(),
) {
    val samples = RenderedMarkdownGalleryMocks.samples
    val selected = viewModel.selectedSample

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(samples, key = { it.id }) { sample ->
                OpenCodeChoiceBadge(
                    label = sample.label,
                    selected = sample.id == selected.id,
                    onClick = { viewModel.selectSample(sample.id) },
                )
            }
        }

        if (selected.usesOutgoingMessage) {
            OpenCodeOutgoingMessage(
                text = selected.rawMarkdown,
                renderedMarkdown = selected.document,
                metadataLabel = "Build · Claude Sonnet · just now",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OpenCodeRenderedMarkdown(
                document = selected.document,
                variant = selected.variant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
