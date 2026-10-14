package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeDictationCard
import dev.opencode.mobile.ui.components.OpenCodeDictationCardVariant
import dev.opencode.mobile.ui.components.OpenCodeDictationUnavailableReason

private enum class DictationCardGalleryVariant(
    val label: String,
) {
    Listening("Listening"),
    Stopped("Stopped"),
    Finishing("Finishing"),
    PermissionDenied("Permission denied"),
    ModelNotInstalled("Model not installed"),
    RecognizerError("Recognizer error"),
}

@Composable
fun DictationCardGalleryScreen(modifier: Modifier = Modifier) {
    var variantName by rememberSaveable { mutableStateOf(DictationCardGalleryVariant.Listening.name) }
    var level by rememberSaveable { mutableFloatStateOf(0.4f) }
    val variant =
        DictationCardGalleryVariant.entries.firstOrNull { it.name == variantName }
            ?: DictationCardGalleryVariant.Listening
    val cardVariant =
        when (variant) {
            DictationCardGalleryVariant.Listening -> OpenCodeDictationCardVariant.Listening
            DictationCardGalleryVariant.Stopped -> OpenCodeDictationCardVariant.Stopped
            DictationCardGalleryVariant.Finishing -> OpenCodeDictationCardVariant.Finishing
            DictationCardGalleryVariant.PermissionDenied,
            DictationCardGalleryVariant.ModelNotInstalled,
            DictationCardGalleryVariant.RecognizerError,
            -> OpenCodeDictationCardVariant.Unavailable
        }
    val unavailableReason =
        when (variant) {
            DictationCardGalleryVariant.PermissionDenied -> OpenCodeDictationUnavailableReason.PermissionDenied
            DictationCardGalleryVariant.ModelNotInstalled -> OpenCodeDictationUnavailableReason.ModelNotInstalled
            DictationCardGalleryVariant.RecognizerError -> OpenCodeDictationUnavailableReason.RecognizerError
            DictationCardGalleryVariant.Listening,
            DictationCardGalleryVariant.Stopped,
            DictationCardGalleryVariant.Finishing,
            -> null
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DictationCardGalleryVariant.entries.forEach { value ->
                OpenCodeChoiceBadge(
                    label = value.label,
                    selected = variant == value,
                    onClick = { variantName = value.name },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Level",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = level,
                onValueChange = { level = it },
            )
        }
        OpenCodeDictationCard(
            variant = cardVariant,
            committed = if (cardVariant == OpenCodeDictationCardVariant.Unavailable) "" else "Add a dictation card",
            volatileTail = if (cardVariant == OpenCodeDictationCardVariant.Listening) " to the composer" else "",
            level = level,
            languageLabel = "English",
            onDevice = true,
            elapsedLabel = "0:12",
            unavailableReason = unavailableReason,
            errorMessage = "Dictation stopped",
            onCancel = {},
            onInsert = {},
            onOpenSettings = {},
            onDownload = {},
            onRetry = {},
        )
    }
}
