package dev.opencode.mobile.ui.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTextInputSubmitAction

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
                .padding(horizontal = 20.dp),
    ) {
        OpenCodeTextInput(
            value = uiState.baseUrl,
            onValueChange = onBaseUrlChanged,
            hint = "Server URL",
            submitAction = OpenCodeTextInputSubmitAction.Next,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OpenCodeTextInput(
            value = uiState.username,
            onValueChange = onUsernameChanged,
            hint = "Username",
            submitAction = OpenCodeTextInputSubmitAction.Next,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OpenCodeTextInput(
            value = uiState.password,
            onValueChange = onPasswordChanged,
            hint = "Password",
            kind = OpenCodeTextInputKind.Password,
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
