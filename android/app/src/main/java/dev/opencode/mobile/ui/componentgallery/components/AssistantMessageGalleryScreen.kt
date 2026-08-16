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
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessage
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge

private enum class AssistantMessageSample(
    val label: String,
    val text: String,
) {
    Short(
        label = "Short",
        text = "The Alleskoenner is the right class for that parcel.",
    ),
    Long(
        label = "Long",
        text =
            "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner " +
                "package to the smaller parcel options. Include the likely dimensions, weight " +
                "limits, and the tradeoffs a customer should consider before choosing it.",
    ),
    Empty(
        label = "Empty",
        text = "",
    ),
}

@Composable
fun AssistantMessageGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableStateOf(AssistantMessageSample.Short) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Switch samples to review short, long, and empty assistant messages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantMessageSample.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.label,
                    selected = sample == option,
                    onClick = { sample = option },
                )
            }
        }

        OpenCodeAssistantMessage(text = sample.text)
    }
}
