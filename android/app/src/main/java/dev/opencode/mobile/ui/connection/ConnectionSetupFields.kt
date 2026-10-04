package dev.opencode.mobile.ui.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.PairingQRPayload
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodeServerAddressInput
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTextInputSubmitAction

internal fun qrAddressHint(urls: List<String>): String {
    val tunnel = urls.filter(PairingQRPayload::isRemoteTunnelUrl)
    val tailscale = urls.filter(PairingQRPayload::isTailscaleUrl)
    val local =
        urls.filter { url ->
            !PairingQRPayload.isTailscaleUrl(url) && !PairingQRPayload.isRemoteTunnelUrl(url)
        }
    if (tunnel.isNotEmpty() && tailscale.isEmpty() && local.isEmpty()) {
        return "Remote tunnel saved. Nothing else to pick. It stays until you turn remote access off."
    }
    if (tailscale.isNotEmpty() && local.isNotEmpty() && tunnel.isEmpty()) {
        return "Tailscale is used when it answers. Local network is the fallback."
    }
    return "Saved addresses are tried until one answers. Tailscale is preferred when it answers."
}

internal fun qrAddressTitle(url: String): String =
    when {
        PairingQRPayload.isTailscaleUrl(url) -> "Tailscale"
        PairingQRPayload.isRemoteTunnelUrl(url) -> "Remote tunnel"
        else -> "Local network"
    }

internal data class QrAddressRowUi(
    val title: String,
    val subtitle: String,
    val badge: String,
    val dimmed: Boolean,
)

internal fun qrAddressRows(uiState: ConnectionSetupUiState): List<QrAddressRowUi> {
    val chosen = if (uiState.addressProbeFinished) uiState.chosenUrl else null
    return uiState.serverUrls.map { url ->
        val normalized = ConnectionProfileIdentity.normalizeBaseUrl(url)
        val unreachable = uiState.addressProbeFinished && normalized in uiState.unreachableUrls
        val isChosen = chosen != null && ConnectionProfileIdentity.normalizeBaseUrl(chosen) == normalized
        val host = ConnectionProfileIdentity.hostLabel(url)
        QrAddressRowUi(
            title = qrAddressTitle(url),
            subtitle = if (unreachable) "$host · not reachable" else host,
            badge = if (isChosen) "Using now" else "Saved",
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
    onEngineChanged: (ConnectionEngine) -> Unit = {},
    onPasswordFocusConsumed: () -> Unit,
    showManualForm: Boolean,
    modifier: Modifier = Modifier,
) {
    val passwordFocusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.requestPasswordFocus, uiState.credentialSegment, showManualForm, uiState.engine) {
        if (
            uiState.requestPasswordFocus &&
            uiState.engine == ConnectionEngine.T3 &&
            showManualForm
        ) {
            passwordFocusRequester.requestFocus()
            onPasswordFocusConsumed()
        } else if (
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
                text = pairAgainMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OpenCodeCodeSnippet(code = "opencode pair --remote")
            return@Column
        }
        if (uiState.usesQrLayout) {
            Text(
                text = qrAddressHint(uiState.serverUrls),
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
                hint = "Optional name",
                submitAction = OpenCodeTextInputSubmitAction.Next,
                enabled = !uiState.isBusy,
            )
            return@Column
        }
        EngineChoiceRow(
            selected = uiState.engine,
            enabled = !uiState.isBusy,
            onSelected = onEngineChanged,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (uiState.engine == ConnectionEngine.T3) {
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
            Spacer(modifier = Modifier.height(12.dp))
            OpenCodeTextInput(
                value = uiState.password,
                onValueChange = onPasswordChanged,
                hint =
                    if (uiState.hasStoredT3Token) {
                        "Paste a new pairing token to re-pair"
                    } else {
                        "Pairing token"
                    },
                kind = OpenCodeTextInputKind.Password,
                enabled = !uiState.isBusy,
                modifier = Modifier.focusRequester(passwordFocusRequester),
            )
            Text(
                text =
                    if (uiState.hasStoredT3Token) {
                        "This environment is already paired. Leave blank to keep the current access, or paste a new pairing token to re-pair."
                    } else {
                        "Token from the T3 environment pairing screen."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
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
                    hint = "Username",
                    kind = OpenCodeTextInputKind.Username,
                    submitAction = OpenCodeTextInputSubmitAction.Next,
                    enabled = !uiState.isBusy,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OpenCodeTextInput(
                    value = uiState.password,
                    onValueChange = onPasswordChanged,
                    hint = "Password, optional",
                    kind = OpenCodeTextInputKind.Password,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.focusRequester(passwordFocusRequester),
                )
                Text(
                    text = "Default user is opencode. Password is optional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
            ConnectionCredentialSegment.PairingCode -> {
                OpenCodeTextInput(
                    value = uiState.pairingCode,
                    onValueChange = onPairingCodeChanged,
                    hint = "Pairing code",
                    kind = OpenCodeTextInputKind.Url,
                    enabled = !uiState.isBusy,
                )
                Text(
                    text = "From the pair link. Works once and expires in 5 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EngineChoiceRow(
    selected: ConnectionEngine,
    enabled: Boolean,
    onSelected: (ConnectionEngine) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected == ConnectionEngine.OPENCODE,
            onClick = { onSelected(ConnectionEngine.OPENCODE) },
            enabled = enabled,
            label = { Text("OpenCode") },
            colors = FilterChipDefaults.filterChipColors(),
        )
        FilterChip(
            selected = selected == ConnectionEngine.T3,
            onClick = { onSelected(ConnectionEngine.T3) },
            enabled = enabled,
            label = { Text("T3") },
            colors = FilterChipDefaults.filterChipColors(),
        )
    }
}

@Composable
private fun PairingInstructions() {
    Text(
        text = "On your computer, run this, then scan the QR.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OpenCodeCodeSnippet(code = "opencode pair --remote")
    Text(
        text =
            "First run takes about a minute. Needs OpenCode 2.0.25 or later. The service starts if it is not already running.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = "Same Wi-Fi instead",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 20.dp),
    )
    Text(
        text = "Plain pair shows a QR only after the server is reachable beyond localhost.",
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
            ConnectionCredentialSegment.Password to "Password",
            ConnectionCredentialSegment.PairingCode to "Pairing code",
        ).forEach { (segment, label) ->
            val isSelected = segment == selected
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
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
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = row.badge,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
