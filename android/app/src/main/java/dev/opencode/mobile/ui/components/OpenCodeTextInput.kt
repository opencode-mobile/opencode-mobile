package dev.opencode.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

enum class OpenCodeTextInputKind {
    Text,
    Url,
    Username,
    Password,
}

enum class OpenCodeTextInputSubmitAction {
    Next,
    Done,
}

@Composable
fun OpenCodeTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    accessibilityLabel: String? = null,
    kind: OpenCodeTextInputKind = OpenCodeTextInputKind.Text,
    enabled: Boolean = true,
    errorMessage: String? = null,
    submitAction: OpenCodeTextInputSubmitAction = OpenCodeTextInputSubmitAction.Done,
) {
    val resolvedAccessibilityLabel = resolveOpenCodeTextInputAccessibilityLabel(hint, accessibilityLabel)
    requireNotNull(resolvedAccessibilityLabel) {
        "OpenCodeTextInput requires a non-blank hint or accessibilityLabel"
    }
    val focusManager = LocalFocusManager.current
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = resolvedAccessibilityLabel },
            enabled = enabled,
            singleLine = true,
            placeholder =
                if (hint.isEmpty()) {
                    null
                } else {
                    { Text(hint) }
                },
            isError = errorMessage != null,
            supportingText =
                errorMessage?.let { message ->
                    { Text(message) }
                },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(8.dp),
            visualTransformation =
                if (kind ==
                    OpenCodeTextInputKind.Password
                ) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            keyboardOptions =
                KeyboardOptions(
                    imeAction = if (submitAction == OpenCodeTextInputSubmitAction.Next) ImeAction.Next else ImeAction.Done,
                    keyboardType =
                        when (kind) {
                            OpenCodeTextInputKind.Url -> KeyboardType.Uri
                            OpenCodeTextInputKind.Password -> KeyboardType.Password
                            OpenCodeTextInputKind.Text, OpenCodeTextInputKind.Username -> KeyboardType.Text
                        },
                    autoCorrectEnabled = kind == OpenCodeTextInputKind.Text,
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                    onDone = { focusManager.clearFocus() },
                ),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    errorBorderColor = MaterialTheme.colorScheme.error,
                ),
        )
    }
}

internal fun resolveOpenCodeTextInputAccessibilityLabel(
    hint: String?,
    accessibilityLabel: String?,
): String? = accessibilityLabel?.takeIf { it.isNotBlank() } ?: hint?.takeIf { it.isNotBlank() }
