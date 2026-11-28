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
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageContent
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageLimit
import dev.opencode.mobile.ui.components.OpenCodeTruncationRow
import dev.opencode.mobile.ui.components.OpenCodeTruncationRowVariant

private val TruncationRowGallerySample =
    (1..25).joinToString("\n") { "09:41:${"%02d".format(it)} INFO worker $it finished step ok" }

@Composable
fun TruncationRowGalleryScreen(modifier: Modifier = Modifier) {
    var taps by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Full message", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Sits at the cut of a long user message and opens the whole text in the Select text sheet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeTruncationRow(
            variant = OpenCodeTruncationRowVariant.FullMessage(shownCharacters = 1_000, totalCharacters = 588_638),
            onClick = { taps += 1 },
        )
        Text(
            text = "Tapped $taps times",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(text = "In a message", style = MaterialTheme.typography.titleMedium)
        OpenCodeOutgoingMessage(
            text = TruncationRowGallerySample,
            metadataLabel = "Build · DeepSeek V4 Flash",
            content =
                listOf(
                    OpenCodeOutgoingMessageContent.Text(
                        text = TruncationRowGallerySample,
                        limit =
                            OpenCodeOutgoingMessageLimit(
                                shownCharacters = TruncationRowGallerySample.length,
                                totalCharacters = 588_638,
                                onShowFull = { taps += 1 },
                            ),
                    ),
                ),
            modifier = Modifier.fillMaxWidth(),
            onClick = {},
        )
    }
}
