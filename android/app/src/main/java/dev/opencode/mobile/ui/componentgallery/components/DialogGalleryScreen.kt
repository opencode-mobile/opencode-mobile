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
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField

@Composable
fun DialogGalleryScreen(modifier: Modifier = Modifier) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var showsSubtitle by rememberSaveable { mutableStateOf(true) }
    var showsTextField by rememberSaveable { mutableStateOf(false) }
    var optionsEnabled by rememberSaveable { mutableStateOf(true) }
    var destructivePrimary by rememberSaveable { mutableStateOf(true) }
    var draftTitle by rememberSaveable { mutableStateOf("Importing icons") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Dialog",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(
                    label = "Subtitle",
                    selected = showsSubtitle,
                    onClick = { showsSubtitle = !showsSubtitle },
                )
                OpenCodeChoiceBadge(
                    label = "Text field",
                    selected = showsTextField,
                    onClick = { showsTextField = !showsTextField },
                )
                OpenCodeChoiceBadge(
                    label = "Options enabled",
                    selected = optionsEnabled,
                    onClick = { optionsEnabled = !optionsEnabled },
                )
                OpenCodeChoiceBadge(
                    label = "Destructive",
                    selected = destructivePrimary && !showsTextField,
                    onClick = { destructivePrimary = !destructivePrimary },
                )
            }
        }

        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Show dialog"),
            onClick = { visible = true },
        )
    }

    OpenCodeDialog(
        visible = visible,
        title = if (showsTextField) "Rename session" else "Clear all data?",
        subtitle =
            when {
                !showsSubtitle -> null
                showsTextField -> "Choose a short title for this session."
                else -> "This cannot be undone."
            },
        primaryOption =
            OpenCodeDialogOption(
                title = if (showsTextField) "Save" else "Clear",
                enabled = optionsEnabled,
                destructive = destructivePrimary && !showsTextField,
                onClick = { visible = false },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                enabled = optionsEnabled,
                onClick = { visible = false },
            ),
        textField =
            if (showsTextField) {
                OpenCodeDialogTextField(
                    hint = "Title",
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    enabled = optionsEnabled,
                )
            } else {
                null
            },
        onDismissRequest = { visible = false },
    )
}
