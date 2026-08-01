package dev.opencode.mobile.ui.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeServerAddressInput
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTextInputSubmitAction

@Composable
fun ConnectionSetupFields(
    uiState: ConnectionSetupUiState,
    onBaseUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
    ) {
        OpenCodeServerAddressInput(
            value = uiState.baseUrl,
            onValueChange = onBaseUrlChanged,
            hint = "Host",
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
    }
}
