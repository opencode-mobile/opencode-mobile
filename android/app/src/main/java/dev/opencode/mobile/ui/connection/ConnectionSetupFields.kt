package dev.opencode.mobile.ui.connection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeServerAddressInput
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTextInputSubmitAction
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ConnectionSetupFields(
    uiState: ConnectionSetupUiState,
    onNameChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onCredentialsExpandedChanged: (Boolean) -> Unit,
    onPasswordFocusConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val passwordFocusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.requestPasswordFocus, uiState.credentialsExpanded) {
        if (uiState.requestPasswordFocus && uiState.credentialsExpanded) {
            // Wait a frame so AnimatedVisibility has composed the password field.
            kotlinx.coroutines.yield()
            passwordFocusRequester.requestFocus()
            onPasswordFocusConsumed()
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
    ) {
        OpenCodeServerAddressInput(
            value = uiState.baseUrl,
            onValueChange = onBaseUrlChanged,
            hint = "macbook.local:4096",
            accessibilityLabel = "Host",
            submitAction = OpenCodeTextInputSubmitAction.Next,
            enabled = !uiState.isBusy,
        )
        Text(
            text = "LAN, Tailscale MagicDNS, or tunnel — not localhost on a phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OpenCodeTextInput(
            value = uiState.name,
            onValueChange = onNameChanged,
            hint = "Optional name",
            submitAction = OpenCodeTextInputSubmitAction.Next,
            enabled = !uiState.isBusy,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !uiState.isBusy,
                        role = Role.Button,
                        onClick = { onCredentialsExpandedChanged(!uiState.credentialsExpanded) },
                    ).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Username & password",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector =
                    if (uiState.credentialsExpanded) {
                        OpenCodeIcons.CollapseContent
                    } else {
                        OpenCodeIcons.ExpandContent
                    },
                contentDescription = if (uiState.credentialsExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = uiState.credentialsExpanded) {
            Column {
                OpenCodeTextInput(
                    value = uiState.username,
                    onValueChange = onUsernameChanged,
                    hint = "Username",
                    submitAction = OpenCodeTextInputSubmitAction.Next,
                    enabled = !uiState.isBusy,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OpenCodeTextInput(
                    value = uiState.password,
                    onValueChange = onPasswordChanged,
                    hint = "Password",
                    kind = OpenCodeTextInputKind.Password,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.focusRequester(passwordFocusRequester),
                )
                Text(
                    text = "Default user is opencode. Password from OPENCODE_SERVER_PASSWORD.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
        }
    }
}
