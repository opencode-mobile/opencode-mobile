package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage

private enum class OutgoingMessageSample(
    val label: String,
    val text: String,
    val attachments: List<OpenCodeAttachmentPresentation> = emptyList(),
) {
    Short(
        label = "Short",
        text = "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.",
    ),
    Long(
        label = "Long",
        text =
            "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options. " +
                "Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it. " +
                "Keep the answer practical and note where pricing depends on the booking channel or destination. " +
                "Then summarize the recommendation in one concise sentence for a mobile chat user.",
    ),
    Attachments(
        label = "Attachments",
        text = "",
        attachments =
            listOf(
                OpenCodeAttachmentPresentation("pdf", "requirements.pdf", OpenCodeAttachmentVariant.Pdf),
                OpenCodeAttachmentPresentation("text", "notes.md", OpenCodeAttachmentVariant.Text),
            ),
    ),
}

@Composable
fun OutgoingMessageGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableStateOf(OutgoingMessageSample.Short) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Switch samples to review short, long, and attachment outgoing messages at full height.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutgoingMessageSample.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.label,
                    selected = sample == option,
                    onClick = { sample = option },
                )
            }
        }

        OpenCodeOutgoingMessage(
            text = sample.text,
            metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
            sentAtEpochMs = System.currentTimeMillis() - 26 * 60 * 60 * 1000L,
            attachments = sample.attachments,
            modifier = Modifier.fillMaxWidth(),
            onClick = {},
        )
    }
}
