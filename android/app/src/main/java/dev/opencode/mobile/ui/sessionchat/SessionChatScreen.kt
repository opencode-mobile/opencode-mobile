package dev.opencode.mobile.ui.sessionchat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSessionStatusBar
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.copySessionIdToClipboard
import dev.opencode.mobile.ui.sessionchat.view.ComposerPickerKind
import dev.opencode.mobile.ui.sessionchat.view.SessionChatComposerView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatComposerViewProps
import dev.opencode.mobile.ui.sessionchat.view.SessionChatPickerSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineViewProps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionChatScreen(
    sessionId: String,
    sessionTitle: String,
    worktree: String,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onSessionContextClick: () -> Unit = {},
    viewModel: SessionChatViewModel = hiltViewModel(key = "$sessionId-$worktree"),
) {
    val context = LocalContext.current

    LaunchedEffect(sessionId, sessionTitle, worktree) {
        viewModel.bind(sessionId, sessionTitle, worktree)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }
    var activeModelProviderId by remember { mutableStateOf<String?>(null) }
    var modelFilterText by remember { mutableStateOf("") }

    LaunchedEffect(activePicker) {
        if (activePicker != ComposerPickerKind.Model) {
            activeModelProviderId = null
            modelFilterText = ""
        }
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = "Session",
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                showDivider = true,
            )
            SessionStatusBar(
                sessionId = uiState.sessionId,
                connectionStatus = uiState.connectionStatus,
                contextUsageFraction = uiState.contextUsageFraction,
                onCopySessionId = { copySessionIdToClipboard(context, uiState.sessionId) },
                onConnectionClick = {},
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
                        onRefresh = viewModel::refreshMessages,
                        onRestoreComposerText = viewModel::restoreComposerText,
                    ),
                modifier = Modifier.weight(1f),
            )

            SessionChatComposerView(
                props =
                    SessionChatComposerViewProps(
                        text = uiState.composer.text,
                        selectedModelLabel = uiState.composer.selectedModelLabel,
                        selectedMode = uiState.composer.selectedMode,
                        selectedVariantLabel = uiState.composer.selectedVariantLabel,
                        showVariant = uiState.composer.canSelectVariant,
                        sendEnabled = uiState.composer.text.isNotBlank() && uiState.composer.selectedModel != null && !uiState.isSending,
                        showStop = uiState.sessionStatusLabel != null,
                        stopEnabled = !uiState.isAborting,
                    ),
                onTextChange = viewModel::updateComposerText,
                onModelClick = {
                    activeModelProviderId = uiState.composer.selectedModel?.option?.providerId
                    activePicker = ComposerPickerKind.Model
                },
                onModeClick = { activePicker = ComposerPickerKind.Mode },
                onVariantClick = { activePicker = ComposerPickerKind.Variant },
                onAttachClick = {},
                onSendClick = viewModel::sendPrompt,
                onStopClick = viewModel::abort,
            )
        }

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
    sessionId: String,
    connectionStatus: SessionConnectionStatus,
    contextUsageFraction: Float?,
    onCopySessionId: () -> Unit,
    onConnectionClick: () -> Unit,
    onContextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeSessionStatusBar(
        sessionId = sessionId,
        connectionStatus = connectionStatus.toOpenCodeSessionConnectionStatus(),
        contextUsageFraction = contextUsageFraction,
        onCopySessionId = onCopySessionId,
        onConnectionClick = onConnectionClick,
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
