package dev.opencode.mobile.ui.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ConnectionSetupScreen(
    uiState: ConnectionSetupUiState,
    onNavigateBack: () -> Unit,
    onNameChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        OpenCodeTopBar(
            title = uiState.screenTitle,
            leading =
                OpenCodeTopBarLeading.Back(
                    onClick = {
                        ProductAnalytics.logUserAction(
                            screenId = ProductAnalytics.Screen.CONNECTION_SETUP,
                            actionId = ProductAnalytics.Action.CONNECTION_BACK_CANCEL,
                        )
                        onNavigateBack()
                    },
                ),
            actions =
                if (uiState.canRemove) {
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.Delete,
                            contentDescription = "Remove server",
                            enabled = !uiState.isTesting && !uiState.isSaving,
                            onClick = { showRemoveConfirm = true },
                        ),
                    )
                } else {
                    emptyList()
                },
            showDivider = true,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 16.dp),
        ) {
            ConnectionSetupFields(
                uiState = uiState,
                onNameChanged = onNameChanged,
                onBaseUrlChanged = onBaseUrlChanged,
                onUsernameChanged = onUsernameChanged,
                onPasswordChanged = onPasswordChanged,
            )
            ConnectionSetupStatus(
                uiState = uiState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp),
            )
            val testEnabled = !uiState.isTesting && !uiState.isSaving
            Text(
                text = if (uiState.state == ConnectionSetupState.TestSucceeded) "Test again" else "Test connection",
                style = MaterialTheme.typography.labelLarge,
                color = openCodeColor("text-interactive-base"),
                modifier =
                    Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp)
                        .defaultMinSize(minHeight = 40.dp)
                        .alpha(if (testEnabled) 1f else 0.4f)
                        .clickable(
                            enabled = testEnabled,
                            role = Role.Button,
                            onClick = onTestConnection,
                        ),
            )
        }
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Save"),
            enabled = uiState.canSave && !uiState.isSaving,
            onClick = onSave,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
        )
    }

    OpenCodeDialog(
        visible = showRemoveConfirm,
        title = "Remove “${uiState.name.ifBlank { "server" }}”?",
        subtitle =
            "This device forgets the Connection Profile. Sessions stay on that server. If it’s the active server, you’ll pick another — or land on Connect if none remain.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Remove",
                destructive = true,
                onClick = {
                    showRemoveConfirm = false
                    onRemove?.invoke()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = { showRemoveConfirm = false },
            ),
        onDismissRequest = { showRemoveConfirm = false },
    )
}

@Composable
private fun ConnectionSetupStatus(
    uiState: ConnectionSetupUiState,
    modifier: Modifier = Modifier,
) {
    val message =
        uiState.testResultMessage
            ?: if (uiState.mode == ConnectionSetupMode.Edit) {
                "Rename can Save now. Host or password changes need Test."
            } else {
                null
            }
    Text(
        text = message ?: " ",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.alpha(if (message == null) 0f else 1f),
    )
}

@Preview(showBackground = true)
@Composable
private fun ConnectionSetupScreenPreview() {
    OpenCodeTheme {
        ConnectionSetupScreen(
            uiState = ConnectionSetupUiState(),
            onNavigateBack = {},
            onNameChanged = {},
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
                    name = "Mac Mini V1",
                    state = ConnectionSetupState.TestSucceeded,
                    testResultMessage = "Server reachable",
                    mode = ConnectionSetupMode.Edit,
                    profileId = "profile-1",
                ),
            onNavigateBack = {},
            onNameChanged = {},
            onBaseUrlChanged = {},
            onUsernameChanged = {},
            onPasswordChanged = {},
            onTestConnection = {},
            onSave = {},
        )
    }
}
