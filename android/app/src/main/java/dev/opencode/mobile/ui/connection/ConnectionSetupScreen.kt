package dev.opencode.mobile.ui.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
fun ConnectionSetupScreen(
    uiState: ConnectionSetupUiState,
    onNavigateBack: () -> Unit,
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
                .fillMaxSize()
                .imePadding(),
    ) {
        OpenCodeTopBar(
            title = "Connect to server",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )
        ConnectionSetupFields(
            uiState = uiState,
            onBaseUrlChanged = onBaseUrlChanged,
            onUsernameChanged = onUsernameChanged,
            onPasswordChanged = onPasswordChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 16.dp),
        )
        ConnectionSetupActions(
            uiState = uiState,
            onTestConnection = onTestConnection,
            onSave = onSave,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun ConnectionSetupActions(
    uiState: ConnectionSetupUiState,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Keep status height stable so clearing a field after Test does not
        // jump the layout and dismiss the text toolbar / Paste menu.
        Text(
            text = uiState.testResultMessage ?: " ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(bottom = 12.dp)
                    .alpha(if (uiState.testResultMessage == null) 0f else 1f),
        )
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

@Preview(showBackground = true)
@Composable
private fun ConnectionSetupScreenPreview() {
    OpenCodeTheme {
        ConnectionSetupScreen(
            uiState = ConnectionSetupUiState(),
            onNavigateBack = {},
            onBaseUrlChanged = {},
            onUsernameChanged = {},
            onPasswordChanged = {},
            onTestConnection = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionSetupScreenReachablePreview() {
    OpenCodeTheme {
        ConnectionSetupScreen(
            uiState =
                ConnectionSetupUiState(
                    state = ConnectionSetupState.TestSucceeded,
                    testResultMessage = "Server reachable",
                ),
            onNavigateBack = {},
            onBaseUrlChanged = {},
            onUsernameChanged = {},
            onPasswordChanged = {},
            onTestConnection = {},
            onSave = {},
        )
    }
}
