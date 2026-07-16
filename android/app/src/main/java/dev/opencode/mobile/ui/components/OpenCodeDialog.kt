package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.mobile.theme.OpenCodeTheme

private val DialogCornerRadius = RoundedCornerShape(16.dp)
private val DialogMaxWidth = 360.dp
private val DialogPaddingHorizontal = 20.dp
private val DialogPaddingVertical = 20.dp
private val DialogContentSpacing = 16.dp
private val DialogTitleSubtitleSpacing = 8.dp
private val DialogOptionSpacing = 8.dp

data class OpenCodeDialogOption(
    val title: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

data class OpenCodeDialogTextField(
    val value: String,
    val onValueChange: (String) -> Unit,
    val hint: String = "",
    val accessibilityLabel: String? = null,
    val kind: OpenCodeTextInputKind = OpenCodeTextInputKind.Text,
    val enabled: Boolean = true,
    val errorMessage: String? = null,
)

@Composable
fun OpenCodeDialog(
    visible: Boolean,
    title: String,
    primaryOption: OpenCodeDialogOption,
    secondaryOption: OpenCodeDialogOption,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    textField: OpenCodeDialogTextField? = null,
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier =
                modifier
                    .widthIn(max = DialogMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .imePadding(),
            shape = DialogCornerRadius,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = DialogPaddingHorizontal,
                            vertical = DialogPaddingVertical,
                        ),
                verticalArrangement = Arrangement.spacedBy(DialogContentSpacing),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DialogTitleSubtitleSpacing),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (textField != null) {
                    OpenCodeTextInput(
                        value = textField.value,
                        onValueChange = textField.onValueChange,
                        hint = textField.hint,
                        accessibilityLabel = textField.accessibilityLabel,
                        kind = textField.kind,
                        enabled = textField.enabled,
                        errorMessage = textField.errorMessage,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DialogOptionSpacing),
                ) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText(secondaryOption.title),
                        onClick = secondaryOption.onClick,
                        enabled = secondaryOption.enabled,
                        contrast = OpenCodeButtonContrast.Low,
                        modifier = Modifier.weight(1f),
                    )
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText(primaryOption.title),
                        onClick = primaryOption.onClick,
                        enabled = primaryOption.enabled,
                        contrast = OpenCodeButtonContrast.High,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeDialogConfirmationPreview() {
    OpenCodeTheme {
        OpenCodeDialog(
            visible = true,
            title = "Clear all data?",
            subtitle = "This cannot be undone.",
            primaryOption =
                OpenCodeDialogOption(
                    title = "Clear",
                    onClick = {},
                ),
            secondaryOption =
                OpenCodeDialogOption(
                    title = "Cancel",
                    onClick = {},
                ),
            onDismissRequest = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeDialogFormPreview() {
    OpenCodeTheme {
        OpenCodeDialog(
            visible = true,
            title = "Rename session",
            primaryOption =
                OpenCodeDialogOption(
                    title = "Save",
                    onClick = {},
                ),
            secondaryOption =
                OpenCodeDialogOption(
                    title = "Cancel",
                    onClick = {},
                ),
            textField =
                OpenCodeDialogTextField(
                    hint = "Title",
                    value = "Importing icons",
                    onValueChange = {},
                ),
            onDismissRequest = {},
        )
    }
}
