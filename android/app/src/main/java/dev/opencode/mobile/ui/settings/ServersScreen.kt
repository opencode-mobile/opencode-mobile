package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.connection.listCaption
import dev.opencode.mobile.ui.connection.listStatus
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ServersScreen(
    uiState: ServersUiState,
    onNavigateBack: () -> Unit,
    onAddServer: () -> Unit,
    onEditProfile: (ConnectionProfile) -> Unit,
    onRequestRemove: (ConnectionProfile) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val countLabel =
        if (uiState.profiles.size == 1) {
            "1 Connection Profile"
        } else {
            "${uiState.profiles.size} Connection Profiles"
        }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = "Servers",
                subtitle = countLabel,
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                showDivider = true,
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "SAVED",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                uiState.profiles.forEach { profile ->
                    val isActive = profile.id == uiState.activeId
                    OpenCodeListItem(
                        title = profile.displayName,
                        subtitle = ConnectionProfileIdentity.hostLabel(profile.baseUrl),
                        caption = profile.listCaption(isActive = isActive),
                        status = profile.listStatus(isActive = isActive),
                        style = OpenCodeListItemStyle.Card,
                        startDecorator =
                            StartDecorator.Letter(
                                letterCharacterForProject(profile.displayName),
                                letterAccentForWorktree(profile.id.ifBlank { profile.displayName }),
                            ),
                        onClick = { onEditProfile(profile) },
                        trailing = {
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Close, "Remove ${profile.displayName}"),
                                contrast = OpenCodeButtonContrast.Low,
                                onClick = { onRequestRemove(profile) },
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Add server"),
            onClick = onAddServer,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 24.dp),
        )
    }

    val pending = uiState.pendingRemoval
    OpenCodeDialog(
        visible = pending != null,
        title = pending?.let { "Remove “${it.displayName}”?" } ?: "Remove server?",
        subtitle =
            "This device forgets the Connection Profile. Sessions stay on that server. If it’s the active server, you’ll pick another — or land on Connect if none remain.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Remove",
                destructive = true,
                onClick = onConfirmRemove,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = onDismissRemove,
            ),
        onDismissRequest = onDismissRemove,
    )
}
