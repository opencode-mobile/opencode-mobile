package dev.opencode.mobile.ui.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.PairingQRPayload
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodeServerAddressInput
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTextInputSubmitAction
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.resolve

internal fun qrAddressHint(urls: List<String>): UiText {
    val tunnel = urls.filter(PairingQRPayload::isRemoteTunnelUrl)
    val tailscale = urls.filter(PairingQRPayload::isTailscaleUrl)
    val local =
        urls.filter { url ->
            !PairingQRPayload.isTailscaleUrl(url) && !PairingQRPayload.isRemoteTunnelUrl(url)
        }
    if (tunnel.isNotEmpty() && tailscale.isEmpty() && local.isEmpty()) {
        return UiText.resource(R.string.connection_setup_addresses_hint_tunnel_only)
    }
    if (tailscale.isNotEmpty() && local.isNotEmpty() && tunnel.isEmpty()) {
        return UiText.resource(R.string.connection_setup_addresses_hint_tailscale_fallback)
    }
    return UiText.resource(R.string.connection_setup_addresses_hint_default)
}

internal fun qrAddressTitle(url: String): UiText =
    UiText.resource(
        when {
            PairingQRPayload.isTailscaleUrl(url) -> R.string.connection_setup_address_tailscale
            PairingQRPayload.isRemoteTunnelUrl(url) -> R.string.connection_setup_address_remote_tunnel
            else -> R.string.connection_setup_address_local_network
        },
    )

internal data class QrAddressRowUi(
    val title: UiText,
    val subtitle: UiText,
    val badge: UiText,
    val dimmed: Boolean,
)

internal fun qrAddressRows(uiState: ConnectionSetupUiState): List<QrAddressRowUi> {
    val chosen = if (uiState.addressProbeFinished) uiState.chosenUrl else null
    return uiState.serverUrls.map { url ->
        val normalized = ConnectionProfileIdentity.normalizeBaseUrl(url)
        val unreachable = uiState.addressProbeFinished && normalized in uiState.unreachableUrls
        val isChosen = chosen != null && ConnectionProfileIdentity.normalizeBaseUrl(chosen) == normalized
        val host = UiText.verbatim(ConnectionProfileIdentity.hostLabel(url))
        QrAddressRowUi(
            title = qrAddressTitle(url),
            subtitle = if (unreachable) UiText.resource(R.string.connection_setup_address_not_reachable, host) else host,
            badge =
                UiText.resource(
                    if (isChosen) R.string.connection_setup_address_using_now else R.string.connection_setup_address_saved,
                ),
            dimmed = unreachable,
        )
    }
}

@Composable
fun ConnectionSetupFields(
    uiState: ConnectionSetupUiState,
    onNameChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onCredentialSegmentChanged: (ConnectionCredentialSegment) -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onPasswordFocusConsumed: () -> Unit,
    showManualForm: Boolean,
    modifier: Modifier = Modifier,
) {
    val passwordFocusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.requestPasswordFocus, uiState.credentialSegment, showManualForm) {
        if (
            uiState.requestPasswordFocus &&
            uiState.credentialSegment == ConnectionCredentialSegment.Password &&
            !uiState.usesQrLayout &&
            showManualForm
        ) {
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
        val pairAgainMessage = uiState.pairAgainMessage
        if (pairAgainMessage != null && uiState.usesQrLayout) {
            Text(
                text = pairAgainMessage.resolve(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OpenCodeCodeSnippet(code = "opencode pair --remote")
            return@Column
        }
        if (uiState.usesQrLayout) {
            Text(
                text = qrAddressHint(uiState.serverUrls).resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            QrAddressGroup(qrAddressRows(uiState))
            Spacer(modifier = Modifier.height(12.dp))
            OpenCodeTextInput(
                value = uiState.name,
                onValueChange = onNameChanged,
                hint = stringResource(R.string.connection_setup_name_hint),
                submitAction = OpenCodeTextInputSubmitAction.Next,
                enabled = !uiState.isBusy,
            )
            return@Column
        }
        if (!showManualForm) {
            PairingInstructions()
            return@Column
        }
        OpenCodeServerAddressInput(
            value = uiState.baseUrl,
            onValueChange = onBaseUrlChanged,
            hint = stringResource(R.string.connection_setup_host_hint),
            accessibilityLabel = stringResource(R.string.connection_setup_host_label),
            submitAction = OpenCodeTextInputSubmitAction.Next,
            enabled = !uiState.isBusy,
        )
        Text(
            text = stringResource(R.string.connection_setup_host_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OpenCodeTextInput(
            value = uiState.name,
            onValueChange = onNameChanged,
            hint = stringResource(R.string.connection_setup_name_hint),
            submitAction = OpenCodeTextInputSubmitAction.Next,
            enabled = !uiState.isBusy,
        )
        Spacer(modifier = Modifier.height(12.dp))
        CredentialSegmentControl(
            selected = uiState.credentialSegment,
            enabled = !uiState.isBusy,
            onSelected = onCredentialSegmentChanged,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when (uiState.credentialSegment) {
            ConnectionCredentialSegment.Password -> {
                OpenCodeTextInput(
                    value = uiState.username,
                    onValueChange = onUsernameChanged,
                    hint = stringResource(R.string.connection_setup_username_hint),
                    kind = OpenCodeTextInputKind.Username,
                    submitAction = OpenCodeTextInputSubmitAction.Next,
                    enabled = !uiState.isBusy,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OpenCodeTextInput(
                    value = uiState.password,
                    onValueChange = onPasswordChanged,
                    hint = stringResource(R.string.connection_setup_password_hint),
                    kind = OpenCodeTextInputKind.Password,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.focusRequester(passwordFocusRequester),
                )
                Text(
                    text = stringResource(R.string.connection_setup_password_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
            ConnectionCredentialSegment.PairingCode -> {
                OpenCodeTextInput(
                    value = uiState.pairingCode,
                    onValueChange = onPairingCodeChanged,
                    hint = stringResource(R.string.connection_setup_pairing_code_hint),
                    kind = OpenCodeTextInputKind.Url,
                    enabled = !uiState.isBusy,
                )
                Text(
                    text = stringResource(R.string.connection_setup_pairing_code_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PairingInstructions() {
    Text(
        text = stringResource(R.string.connection_setup_instructions_run),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OpenCodeCodeSnippet(code = "opencode pair --remote")
    Text(
        text = stringResource(R.string.connection_setup_instructions_first_run),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.connection_setup_instructions_same_wifi_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 20.dp),
    )
    Text(
        text = stringResource(R.string.connection_setup_instructions_same_wifi_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    OpenCodeCodeSnippet(code = "opencode service set hostname 0.0.0.0")
    Spacer(modifier = Modifier.height(8.dp))
    OpenCodeCodeSnippet(code = "opencode pair")
}

@Composable
private fun CredentialSegmentControl(
    selected: ConnectionCredentialSegment,
    enabled: Boolean,
    onSelected: (ConnectionCredentialSegment) -> Unit,
) {
    val isDark = LocalOpenCodeDarkTheme.current
    val track = openCodeColor("surface-raised-base")
    val selectedFill = if (isDark) openCodeColor("text-strong") else openCodeColor("surface-base")
    val selectedText = if (isDark) openCodeColor("background-base") else openCodeColor("text-strong")
    val unselectedText = openCodeColor("text-base")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(track)
                .padding(2.dp),
    ) {
        listOf(
            ConnectionCredentialSegment.Password to stringResource(R.string.connection_setup_segment_password),
            ConnectionCredentialSegment.PairingCode to stringResource(R.string.connection_setup_segment_pairing_code),
        ).forEach { (segment, label) ->
            val isSelected = segment == selected
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(OpenCodeSmallShape)
                        .background(if (isSelected) selectedFill else Color.Transparent)
                        .clickable(
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelected(segment) },
                        ).semantics { this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) selectedText else unselectedText,
                )
            }
        }
    }
}

@Composable
private fun QrAddressGroup(rows: List<QrAddressRowUi>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = Dp.Hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                QrAddressRow(row)
            }
        }
    }
}

@Composable
private fun QrAddressRow(row: QrAddressRowUi) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .alpha(if (row.dimmed) 0.38f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title.resolve(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = row.subtitle.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = row.badge.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
