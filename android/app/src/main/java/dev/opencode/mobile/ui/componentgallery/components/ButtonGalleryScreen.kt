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
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private enum class GalleryButtonVariant {
    FullWidthText,
    CompactText,
    IconSquare,
    IconCircle,
}

@Composable
fun ButtonGalleryScreen(modifier: Modifier = Modifier) {
    var variantName by rememberSaveable { mutableStateOf(GalleryButtonVariant.FullWidthText.name) }
    var sizeName by rememberSaveable { mutableStateOf(OpenCodeButtonSize.Medium.name) }
    var contrastName by rememberSaveable { mutableStateOf(OpenCodeButtonContrast.High.name) }
    var enabled by rememberSaveable { mutableStateOf(true) }

    val variantSelection =
        try {
            GalleryButtonVariant.valueOf(variantName)
        } catch (_: IllegalArgumentException) {
            GalleryButtonVariant.FullWidthText
        }

    val variant: OpenCodeButtonVariant =
        when (variantSelection) {
            GalleryButtonVariant.FullWidthText -> OpenCodeButtonVariant.FullWidthText("Run")
            GalleryButtonVariant.CompactText -> OpenCodeButtonVariant.CompactText("Build")
            GalleryButtonVariant.IconSquare -> OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowUpward, "Send")
            GalleryButtonVariant.IconCircle -> OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send")
        }
    val contrastSelection =
        try {
            OpenCodeButtonContrast.valueOf(contrastName)
        } catch (_: IllegalArgumentException) {
            OpenCodeButtonContrast.High
        }

    val sizeSelection =
        try {
            OpenCodeButtonSize.valueOf(sizeName)
        } catch (_: IllegalArgumentException) {
            OpenCodeButtonSize.Large
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Button", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryButtonVariant.entries.forEach { option ->
                    OpenCodeChoiceBadge(
                        label =
                            when (option) {
                                GalleryButtonVariant.FullWidthText -> "Full width"
                                GalleryButtonVariant.CompactText -> "Compact"
                                GalleryButtonVariant.IconSquare -> "Icon"
                                GalleryButtonVariant.IconCircle -> "Circle"
                            },
                        selected = option == variantSelection,
                        onClick = { variantName = option.name },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeButtonContrast.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label =
                        when (option) {
                            OpenCodeButtonContrast.High -> "High contrast"
                            OpenCodeButtonContrast.Low -> "Low contrast"
                            OpenCodeButtonContrast.Accent -> "Accent"
                            OpenCodeButtonContrast.Destructive -> "Destructive"
                        },
                    selected = option == contrastSelection,
                    onClick = { contrastName = option.name },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeButtonSize.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label =
                        when (option) {
                            OpenCodeButtonSize.Large -> "Large"
                            OpenCodeButtonSize.Medium -> "Medium"
                        },
                    selected = option == sizeSelection,
                    onClick = { sizeName = option.name },
                )
            }
        }

        OpenCodeButton(
            variant = variant,
            enabled = enabled,
            contrast = contrastSelection,
            size = sizeSelection,
            onClick = {},
        )
    }
}
