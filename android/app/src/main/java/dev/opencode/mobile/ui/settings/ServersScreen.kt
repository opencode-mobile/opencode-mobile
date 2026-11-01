package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.preferences.DeveloperToolsUnlock
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.connection.listCaption
import dev.opencode.mobile.ui.connection.listStatus
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.resolve

@Composable
fun ServersScreen(
    uiState: ServersUiState,
    onNavigateBack: () -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    onAddServer: () -> Unit,
    onEditProfile: (ConnectionProfile) -> Unit,
    onRequestRemove: (ConnectionProfile) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var headerTapCount by remember { mutableIntStateOf(0) }
    var lastHeaderTapAtMs by remember { mutableLongStateOf(0L) }
    val headerTapInteraction = remember { MutableInteractionSource() }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = stringResource(R.string.servers_title),
                subtitle = uiState.profileCountLabel.resolve(),
                leading = openCodeDetailLeading(detailControl, onNavigateBack),
                showDivider = true,
            )
            OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpenCodeSectionHeader(
                            title = stringResource(R.string.servers_saved),
                            modifier =
                                Modifier
                                    .clickable(
                                        interactionSource = headerTapInteraction,
                                        indication = null,
                                    ) {
                                        val nowMs = System.currentTimeMillis()
                                        val next =
                                            DeveloperToolsUnlock.nextCount(
                                                previousCount = headerTapCount,
                                                lastTapAtMs = lastHeaderTapAtMs,
                                                nowMs = nowMs,
                                            )
                                        headerTapCount = next
                                        lastHeaderTapAtMs = nowMs
                                        if (next >= DeveloperToolsUnlock.REQUIRED_TAPS) {
                                            DeveloperToolsPreferences.unlock()
                                        }
                                    },
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
                                        variant =
                                            OpenCodeButtonVariant.IconCircle(
                                                OpenCodeIcons.Close,
                                                stringResource(R.string.servers_remove_profile, profile.displayName),
                                            ),
                                        contrast = OpenCodeButtonContrast.Low,
                                        onClick = { onRequestRemove(profile) },
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.servers_add)),
                        onClick = onAddServer,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
        }
    }

    val pending = uiState.pendingRemoval
    OpenCodeDialog(
        visible = pending != null,
        title = uiState.removeDialogTitle.resolve(),
        subtitle = stringResource(R.string.servers_remove_dialog_message),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_remove),
                destructive = true,
                onClick = onConfirmRemove,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                onClick = onDismissRemove,
            ),
        onDismissRequest = onDismissRemove,
    )
}
