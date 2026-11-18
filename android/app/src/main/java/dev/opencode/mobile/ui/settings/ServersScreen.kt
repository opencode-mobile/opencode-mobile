package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
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
import dev.opencode.mobile.ui.serverhealth.ServerHealthQuickViewFooter
import dev.opencode.mobile.ui.text.resolve

@Composable
fun ServersScreen(
    uiState: ServersUiState,
    onNavigateBack: () -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    onAddServer: () -> Unit,
    onSelectProfile: (ConnectionProfile) -> Unit,
    onEditProfile: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHealth: (String) -> Unit = {},
) {
    val activeLabel = stringResource(R.string.servers_status_active)
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
                        OpenCodeSectionHeader(title = stringResource(R.string.servers_saved))
                        uiState.profiles.forEach { profile ->
                            val isActive = profile.id == uiState.activeId
                            OpenCodeListItem(
                                title = profile.displayName,
                                subtitle = ConnectionProfileIdentity.hostLabel(profile.baseUrl),
                                caption = profile.listCaption(isActive = isActive),
                                status = profile.listStatus(isActive = isActive, activeLabel = activeLabel),
                                style = OpenCodeListItemStyle.Card,
                                startDecorator =
                                    StartDecorator.Letter(
                                        letterCharacterForProject(profile.displayName),
                                        letterAccentForWorktree(profile.id.ifBlank { profile.displayName }),
                                    ),
                                // A tap makes the server active; Edit (and Remove inside it) is the trailing button.
                                onClick = if (isActive) null else ({ onSelectProfile(profile) }),
                                trailing = {
                                    OpenCodeButton(
                                        variant =
                                            OpenCodeButtonVariant.IconCircle(
                                                OpenCodeIcons.Edit,
                                                stringResource(R.string.servers_edit_profile, profile.displayName),
                                            ),
                                        contrast = OpenCodeButtonContrast.Low,
                                        onClick = { onEditProfile(profile) },
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                                footer =
                                    uiState.healthQuickView?.takeIf { isActive }?.let { quickView ->
                                        { ServerHealthQuickViewFooter(quickView, onClick = { onOpenHealth(profile.id) }) }
                                    },
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
}
