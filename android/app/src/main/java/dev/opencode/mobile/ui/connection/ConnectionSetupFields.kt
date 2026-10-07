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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.PairingQRPayload
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeServerAddressInput
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTextInputSubmitAction

internal const val QrAddressHint =
    "Both addresses are saved. Nothing to pick. The app uses Tailscale when it answers, " +
        "and the local address when it does not. No password."

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
            title = if (PairingQRPayload.isTailscaleUrl(url)) "Tailscale" else "Local network",
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
    onPasswordFocusConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val passwordFocusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.requestPasswordFocus, uiState.credentialSegment) {
        if (
            uiState.requestPasswordFocus &&
            uiState.credentialSegment == ConnectionCredentialSegment.Password &&
            !uiState.usesQrLayout
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
        if (uiState.usesQrLayout) {
            Text(
                text = QrAddressHint,
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
