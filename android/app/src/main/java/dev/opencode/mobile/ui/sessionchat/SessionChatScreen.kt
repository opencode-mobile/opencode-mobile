package dev.opencode.mobile.ui.sessionchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.domain.message.AttachmentIngestionService
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionItem
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionOverlay
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val coroutineScope = rememberCoroutineScope()
    val attachmentIngestion = remember(context) { AttachmentIngestionService(context.contentResolver) }

    LaunchedEffect(sessionId, sessionTitle, worktree) {
        viewModel.bind(sessionId, sessionTitle, worktree)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }
    var activeModelProviderId by remember { mutableStateOf<String?>(null) }
    var modelFilterText by remember { mutableStateOf("") }
    var messageActionsTarget by remember { mutableStateOf<SessionChatMessageActionsTarget?>(null) }
    var toolDetailTarget by remember { mutableStateOf<SessionChatToolDetailTarget?>(null) }
    val lifecycleActionsEnabled = !uiState.isLifecycleActionInFlight
    var showAttachmentSource by rememberSaveable { mutableStateOf(false) }

    fun ingestAttachments(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { attachmentIngestion.ingest(uris) }
            viewModel.addAttachments(result.attachments)
            result.errors.firstOrNull()?.let(viewModel::reportAttachmentError)
        }
    }

    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            ingestAttachments(uris)
        }
    val documentPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            ingestAttachments(uris)
        }

    LaunchedEffect(activePicker) {
        if (activePicker != ComposerPickerKind.Model) {
            activeModelProviderId = null
            modelFilterText = ""
        }
    }

    LaunchedEffect(uiState.shouldLeaveSession) {
        if (uiState.shouldLeaveSession) {
            onNavigateBack()
        }
    }

    OpenCodeDialog(
        visible = showDeleteConfirmation,
        title = "Delete session",
        subtitle = "Delete session “${uiState.sessionTitle}”?",
        primaryOption =
            OpenCodeDialogOption(
                title = "Delete",
                enabled = lifecycleActionsEnabled,
                onClick = {
                    showDeleteConfirmation = false
                    viewModel.deleteSession()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                enabled = lifecycleActionsEnabled,
                onClick = { showDeleteConfirmation = false },
            ),
        onDismissRequest = {
            if (lifecycleActionsEnabled) {
                showDeleteConfirmation = false
            }
        },
    )

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBarOverflowHost(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                entries =
                    listOf(
                        OpenCodeOverflowMenuEntry(
                            title = "Rename session",
                            icon = OpenCodeIcons.Edit,
                            onClick = {
                                menuExpanded = false
                                viewModel.openRenameSession()
                            },
                        ),
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
                        OpenCodeOverflowMenuEntry(
                            title = "Archive",
                            icon = OpenCodeIcons.Archive,
                            enabled = lifecycleActionsEnabled,
                            onClick = {
                                menuExpanded = false
                                viewModel.archiveSession()
                            },
                        ),
                        OpenCodeOverflowMenuEntry(
                            title = "Delete",
                            icon = OpenCodeIcons.Close,
                            enabled = lifecycleActionsEnabled,
                            destructive = true,
                            onClick = {
                                menuExpanded = false
                                showDeleteConfirmation = true
                            },
                        ),
                    ),
            ) {
                OpenCodeTopBar(
                    title = uiState.sessionTitle.ifBlank { sessionTitle },
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

            Box(modifier = Modifier.weight(1f)) {
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
                            onChangesReviewTap = { assistantMessageId, initialFilePath ->
                                uiState
                                    .fileChangeNavigationTarget(
                                        worktree = worktree,
                                        assistantMessageId = assistantMessageId,
                                        initialFilePath = initialFilePath,
                                    )?.let(onReviewChangesClick)
                            },
                        ),
                    modifier = Modifier.fillMaxSize(),
                )

                if (uiState.slashSuggestionsVisible && uiState.permissionRequest == null) {
                    OpenCodeComposerSuggestionOverlay(
                        items =
                            uiState.slashSuggestions.map { suggestion ->
                                OpenCodeComposerSuggestionItem(
                                    id = suggestion.id,
                                    title = suggestion.title,
                                    subtitle = suggestion.subtitle,
                                    badge = suggestion.badge,
                                )
                            },
                        onSelect = { item ->
                            viewModel.selectSlashSuggestion(item.id)
                        },
                        emptyMessage = "No commands",
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

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
                    attachments =
                        uiState.composer.attachments.map(AttachmentPresentationMapper::from),
                    selectedModelLabel = uiState.composer.selectedModelLabel,
                    selectedModeLabel = uiState.composer.selectedMode,
                    selectedVariantLabel = uiState.composer.selectedVariantLabel,
                    showVariant = uiState.composer.canSelectVariant,
                    sendEnabled =
                        uiState.composer.canSend &&
                            uiState.composer.selectedModel != null &&
                            uiState.composer.isModelSelectionReady &&
                            !uiState.isSending,
                    attachEnabled = uiState.composer.canAttach && uiState.composer.isModelSelectionReady,
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
                        OpenCodeSessionComposerAction.Attach -> {
                            val photos = uiState.composer.canAttachPhotos
                            val files = uiState.composer.canAttachFiles
                            when {
                                photos && files -> showAttachmentSource = true
                                photos ->
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                files -> documentPicker.launch(arrayOf("*/*"))
                            }
                        }
                        is OpenCodeSessionComposerAction.RemoveAttachment -> viewModel.removeAttachment(action.id)
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

        OpenCodeBottomSheet(
            visible = showAttachmentSource,
            title = "Add attachment",
            onDismissRequest = { showAttachmentSource = false },
        ) {
            if (uiState.composer.canAttachPhotos) {
                OpenCodeBottomSheetEntry(
                    title = "Photos",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Description),
                    onClick = {
                        showAttachmentSource = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            if (uiState.composer.canAttachFiles) {
                OpenCodeBottomSheetEntry(
                    title = "Files",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder),
                    onClick = {
                        showAttachmentSource = false
                        documentPicker.launch(arrayOf("*/*"))
                    },
                )
            }
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

        OpenCodeDialog(
            visible = uiState.rename.isDialogVisible,
            title = "Rename session",
            primaryOption =
                OpenCodeDialogOption(
                    title = "Save",
                    enabled = !uiState.rename.isSubmitting,
                    onClick = viewModel::confirmRenameSession,
                ),
            secondaryOption =
                OpenCodeDialogOption(
                    title = "Cancel",
                    enabled = !uiState.rename.isSubmitting,
                    onClick = viewModel::cancelRenameSession,
                ),
            textField =
                OpenCodeDialogTextField(
                    value = uiState.rename.draftTitle,
                    onValueChange = viewModel::updateRenameDraft,
                    hint = "Title",
                    enabled = !uiState.rename.isSubmitting,
                    errorMessage = uiState.rename.errorMessage,
                ),
            onDismissRequest = {
                if (!uiState.rename.isSubmitting) {
                    viewModel.cancelRenameSession()
                }
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
