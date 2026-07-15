package dev.opencode.mobile.ui.sessionchat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeSessionComposer
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerAction
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSessionStatusBar
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.ui.components.copySessionIdToClipboard
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.view.ComposerPickerKind
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatPickerSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineViewProps
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionChatScreen(
    sessionId: String,
    sessionTitle: String,
    worktree: String,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onSessionContextClick: () -> Unit = {},
    onReviewChangesClick: (ChangesNavigationTarget) -> Unit = {},
    onChildSessionClick: (ChildSessionNavigationTarget) -> Unit = {},
    viewModel: SessionChatViewModel = hiltViewModel(key = "$sessionId-$worktree"),
) {
    val context = LocalContext.current

    LaunchedEffect(sessionId, sessionTitle, worktree) {
        viewModel.bind(sessionId, sessionTitle, worktree)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }
    var activeModelProviderId by remember { mutableStateOf<String?>(null) }
    var modelFilterText by remember { mutableStateOf("") }
    var messageActionsTarget by remember { mutableStateOf<SessionChatMessageActionsTarget?>(null) }
    var toolDetailTarget by remember { mutableStateOf<SessionChatToolDetailTarget?>(null) }

    LaunchedEffect(activePicker) {
        if (activePicker != ComposerPickerKind.Model) {
            activeModelProviderId = null
            modelFilterText = ""
        }
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBarOverflowHost(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                entries =
                    listOf(
                        OpenCodeOverflowMenuEntry(
                            title = "Review changes",
                            icon = OpenCodeIcons.Description,
                            onClick = {
                                menuExpanded = false
                                onReviewChangesClick(uiState.changesNavigationTarget(worktree))
                            },
                        ),
                        OpenCodeOverflowMenuEntry(
                            title = "Session context",
                            icon = OpenCodeIcons.Architecture,
                            onClick = {
                                menuExpanded = false
                                onSessionContextClick()
                            },
                        ),
                        OpenCodeOverflowMenuEntry(
                            title = "Copy session ID",
                            icon = OpenCodeIcons.ContentCopy,
                            onClick = {
                                menuExpanded = false
                                copySessionIdToClipboard(context, uiState.sessionId)
                            },
                        ),
                    ),
            ) {
                OpenCodeTopBar(
                    title = "Session",
                    subtitleAccessory =
                        OpenCodeTopBarSubtitleAccessory.StatusDot(
                            uiState.connectionStatus.toOpenCodeSessionConnectionStatus(),
                        ),
                    leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                    actions =
                        listOf(
                            OpenCodeTopBarAction.Icon(
                                imageVector = OpenCodeIcons.MoreVert,
                                contentDescription = "More options",
                                onClick = { menuExpanded = true },
                            ),
                        ),
                    showDivider = true,
                )
            }
            SessionStatusBar(
                contextUsageFraction = uiState.contextUsageFraction,
                onContextClick = onSessionContextClick,
            )
            uiState.composer.modelNotice?.let { notice ->
                Text(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.timeline != SessionChatTimelineState.Error) {
                uiState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SessionChatTimelineView(
                props =
                    SessionChatTimelineViewProps(
                        timeline = uiState.timeline,
                        messages = uiState.messages,
                        isRefreshing = uiState.isRefreshing,
                        errorMessage = uiState.errorMessage,
                        reviewChangesSummary = uiState.reviewChangesSummary,
                        onRefresh = viewModel::refreshMessages,
                        onReviewChangesClick = {
                            uiState.reviewChangesNavigationTarget(worktree)?.let(onReviewChangesClick)
                        },
                        onMessageActionsTap = { messageActionsTarget = it },
                        onToolDetailTap = { toolDetailTarget = it },
                        onChildSessionTap = { childSessionId, childTitle ->
                            onChildSessionClick(
                                ChildSessionNavigationTarget(
                                    sessionId = childSessionId,
                                    sessionTitle = childTitle,
                                    worktree = worktree,
                                ),
                            )
                        },
                    ),
                modifier = Modifier.weight(1f),
            )

            val composerState =
                uiState.permissionRequest?.let { request ->
                    OpenCodeSessionComposerState.Permission(
                        requestId = request.id,
                        sessionId = request.sessionId,
                        permission = request.permission,
                        patterns = request.patterns,
                        queueCount = uiState.permissionQueueCount,
                        submitting = uiState.permissionSubmitting,
                        errorMessage = uiState.permissionErrorMessage,
                    )
                } ?: OpenCodeSessionComposerState.Prompt(
                    text = uiState.composer.text,
                    selectedModelLabel = uiState.composer.selectedModelLabel,
                    selectedModeLabel = uiState.composer.selectedMode,
                    selectedVariantLabel = uiState.composer.selectedVariantLabel,
                    showVariant = uiState.composer.canSelectVariant,
                    sendEnabled =
                        uiState.composer.text.isNotBlank() &&
                            uiState.composer.selectedModel != null &&
                            uiState.composer.isModelSelectionReady &&
                            !uiState.isSending,
                    showStop = uiState.sessionStatusLabel != null,
                    stopEnabled = !uiState.isAborting,
                )
            OpenCodeSessionComposer(
                state = composerState,
                onAction = { action ->
                    when (action) {
                        is OpenCodeSessionComposerAction.TextChanged -> viewModel.updateComposerText(action.text)
                        OpenCodeSessionComposerAction.SelectModel -> {
                            if (uiState.composer.modelOptions.isEmpty()) {
                                viewModel.retryModelCatalog()
                                return@OpenCodeSessionComposer
                            }
                            activeModelProviderId =
                                uiState.composer.selectedModel
                                    ?.option
                                    ?.providerId
                            activePicker = ComposerPickerKind.Model
                        }
                        OpenCodeSessionComposerAction.SelectMode -> activePicker = ComposerPickerKind.Mode
                        OpenCodeSessionComposerAction.SelectVariant -> activePicker = ComposerPickerKind.Variant
                        OpenCodeSessionComposerAction.Attach -> Unit
                        OpenCodeSessionComposerAction.Send -> viewModel.sendPrompt()
                        OpenCodeSessionComposerAction.Stop -> viewModel.abort()
                        is OpenCodeSessionComposerAction.AllowPermissionOnce ->
                            viewModel.allowPermissionOnce(action.requestId, action.sessionId)
                        is OpenCodeSessionComposerAction.DenyPermission ->
                            viewModel.denyPermission(action.requestId, action.sessionId)
                    }
                },
            )
        }

        SessionChatMessageActionsSheet(
            target = messageActionsTarget,
            onDismiss = { messageActionsTarget = null },
            onRestoreToComposer = viewModel::restoreComposerText,
        )

        SessionChatToolDetailSheet(
            target = toolDetailTarget,
            onDismiss = { toolDetailTarget = null },
        )

        SessionChatPickerSheet(
            activePicker = activePicker,
            activeModelProviderId = activeModelProviderId,
            modelFilterText = modelFilterText,
            modelOptions = uiState.composer.modelOptions,
            selectedModel = uiState.composer.selectedModel?.option,
            selectedMode = uiState.composer.selectedMode,
            selectedVariantLabel = uiState.composer.selectedVariantLabel,
            variantOptions = uiState.composer.variantOptions,
            onDismiss = {
                activePicker = null
                activeModelProviderId = null
                modelFilterText = ""
            },
            onActiveModelProviderIdChange = { activeModelProviderId = it },
            onModelFilterTextChange = { modelFilterText = it },
            onSelectModel = { model ->
                viewModel.selectModel(model)
                activePicker = null
                activeModelProviderId = null
                modelFilterText = ""
            },
            onSelectMode = { mode ->
                viewModel.selectMode(mode)
                activePicker = null
            },
            onSelectVariant = { variant ->
                viewModel.selectVariant(variant)
                activePicker = null
            },
        )
    }
}

@Composable
private fun SessionStatusBar(
    contextUsageFraction: Float?,
    onContextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeSessionStatusBar(
        contextUsageFraction = contextUsageFraction,
        onContextClick = onContextClick,
        modifier = modifier,
    )
}

private fun SessionConnectionStatus.toOpenCodeSessionConnectionStatus(): OpenCodeSessionConnectionStatus =
    when (this) {
        SessionConnectionStatus.Live -> OpenCodeSessionConnectionStatus.Live
        SessionConnectionStatus.Connecting -> OpenCodeSessionConnectionStatus.Connecting
        SessionConnectionStatus.Offline -> OpenCodeSessionConnectionStatus.Offline
    }

@Preview(showBackground = true)
@Composable
private fun SessionChatScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        SessionChatScreen(
            sessionId = "1",
            sessionTitle = "Importing icons explanation",
            worktree = "/Users/demo/opencode-mobile",
        )
    }
}
