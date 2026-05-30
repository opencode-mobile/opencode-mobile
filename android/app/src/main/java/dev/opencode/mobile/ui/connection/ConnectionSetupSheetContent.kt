package dev.opencode.mobile.ui.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant

@Composable
fun ConnectionSetupSheetContent(
    uiState: ConnectionSetupUiState,
    onBaseUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
    ) {
        ConnectionField(
            label = "Server URL",
            value = uiState.baseUrl,
            onValueChange = onBaseUrlChanged,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ConnectionField(
            label = "Username",
            value = uiState.username,
            onValueChange = onUsernameChanged,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ConnectionField(
            label = "Password",
            value = uiState.password,
            onValueChange = onPasswordChanged,
            secure = true,
        )
        if (uiState.testResultMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = uiState.testResultMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Test connection"),
            enabled = !uiState.isTesting && !uiState.isSaving,
            onClick = onTestConnection,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Save"),
            enabled = uiState.canSave && !uiState.isSaving,
            onClick = onSave,
        )
    }
}

@Composable
private fun ConnectionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secure: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (secure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (secure) KeyboardType.Password else KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
        )
    }
}
