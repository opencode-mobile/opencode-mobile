package dev.opencode.mobile.ui.sessionchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.message.AttachmentIngestionService
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomFade
import dev.opencode.mobile.ui.components.OpenCodeBottomFadeOverhang
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
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
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
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTodosSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    onSessionReplaceRequested: (ChildSessionNavigationTarget) -> Unit = {},
    viewModel: SessionChatViewModel = hiltViewModel(key = "$sessionId-$worktree"),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val attachmentIngestion = remember(context) { AttachmentIngestionService(context.contentResolver) }

    LaunchedEffect(sessionId, sessionTitle, worktree) {
        viewModel.bind(sessionId, sessionTitle, worktree)
    }
    // Nav keeps the chat ViewModel on the back stack; clear Ready focus when the
    // screen leaves composition (parity with iOS stopObserving onDisappear).
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clearSessionReadyFocus()
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }
    var activeModelProviderId by remember { mutableStateOf<String?>(null) }
    var modelFilterText by remember { mutableStateOf("") }
    var messageActionsTarget by remember { mutableStateOf<SessionChatMessageActionsTarget?>(null) }
    var toolDetailTarget by remember { mutableStateOf<SessionChatToolDetailTarget?>(null) }
    var showSessionTodosSheet by rememberSaveable { mutableStateOf(false) }
    var holdingFinishedTodos by remember { mutableStateOf(false) }
    var composerOverlayHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val composerOverlayHeight = with(density) { composerOverlayHeightPx.toDp() }
    val timelineBottomInset =
        if (composerOverlayHeight > 0.dp) {
            composerOverlayHeight + OpenCodeBottomFadeOverhang
        } else {
            0.dp
        }
    val lifecycleActionsEnabled = !uiState.isLifecycleActionInFlight
    var showAttachmentSource by rememberSaveable { mutableStateOf(false) }

    val blocksSessionTodosSheet = uiState.permissionRequest != null || uiState.questionRequest != null
    val sessionTodosPillVisible = uiState.sessionTodosVisible || holdingFinishedTodos

    LaunchedEffect(uiState.permissionRequest?.id, uiState.questionRequest?.id) {
        if (blocksSessionTodosSheet) {
            showSessionTodosSheet = false
        }
    }

    LaunchedEffect(uiState.sessionTodosFinished, uiState.sessionTodos.isEmpty()) {
        if (uiState.sessionTodos.isEmpty()) {
            holdingFinishedTodos = false
            showSessionTodosSheet = false
            return@LaunchedEffect
        }
        if (uiState.sessionTodosFinished) {
            holdingFinishedTodos = true
            delay(1_500)
            holdingFinishedTodos = false
            showSessionTodosSheet = false
        } else {
            holdingFinishedTodos = false
        }
    }

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

    LaunchedEffect(uiState.pendingSessionReplace) {
        uiState.pendingSessionReplace?.let { target ->
            onSessionReplaceRequested(target)
            viewModel.consumePendingSessionReplace()
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
            // Hidden for now: status bar currently only shows context usage.
            // Session context remains available from the overflow menu.
            if (ShowsSessionStatusBar) {
                SessionStatusBar(
                    contextUsageFraction = uiState.contextUsageFraction,
                    onContextClick = onSessionContextClick,
                )
            }
            uiState.composer.modelNotice?.let { notice ->
                Text(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                SessionChatTimelineView(
                    props =
                        SessionChatTimelineViewProps(
                            timeline = uiState.timeline,
                            messages = uiState.messages,
                            timelineFrame = uiState.timelineFrame,
                            isRefreshing = uiState.isRefreshing,
                            errorMessage = uiState.errorMessage,
                            reviewChangesSummary = uiState.reviewChangesSummary,
                            sessionTodosVisible = sessionTodosPillVisible,
                            sessionTodoCompletedCount = uiState.sessionTodoCompletedCount,
                            sessionTodoTotalCount = uiState.sessionTodoTotalCount,
                            sessionTodoActivePreview = uiState.sessionTodoActivePreview?.content,
                            hideFloatStack = showSessionTodosSheet,
                            // Composer + 16 end-pin gap; float stack uses this as-is
                            // (no extra scrollToEndPillBottomPadding).
                            bottomOverlayInset = timelineBottomInset,
                            onRefresh = viewModel::refreshMessages,
                            onReviewChangesClick = {
                                uiState.reviewChangesNavigationTarget(worktree)?.let(onReviewChangesClick)
                            },
                            onSessionTodosClick = {
                                if (!blocksSessionTodosSheet) {
                                    showSessionTodosSheet = true
                                }
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

                if (timelineBottomInset > 0.dp) {
                    OpenCodeBottomFade(
                        height = timelineBottomInset,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                OpenCodeSnackbarHost(
                    message = uiState.snackbar?.toOpenCodeSnackbarMessage(),
                    onDismissed = viewModel::consumeSnackbar,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = composerOverlayHeight),
                )

                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                val height = coordinates.size.height
                                if (height != composerOverlayHeightPx) {
                                    composerOverlayHeightPx = height
                                }
                            },
                ) {
                    if (uiState.permissionRequest == null && uiState.questionRequest == null) {
                        when {
                            uiState.atSuggestionsVisible ->
                                OpenCodeComposerSuggestionOverlay(
                                    items =
                                        uiState.atSuggestions.map { suggestion ->
                                            OpenCodeComposerSuggestionItem(
                                                id = suggestion.id,
                                                title = suggestion.title,
                                                subtitle = suggestion.subtitle,
                                            )
                                        },
                                    onSelect = { item ->
                                        viewModel.selectAtSuggestion(item.id)
                                    },
                                    emptyMessage = "No agents",
                                    modifier =
                                        Modifier
                                            .padding(horizontal = 12.dp)
                                            .padding(bottom = 4.dp),
                                )
                            uiState.slashSuggestionsVisible ->
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
                                            .padding(horizontal = 12.dp)
                                            .padding(bottom = 4.dp),
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
                                actionsEnabled = uiState.connectionStatus == SessionConnectionStatus.Live,
                                errorMessage = uiState.permissionErrorMessage,
                            )
                        } ?: uiState.questionRequest?.let { request ->
                            val question = request.questions.getOrNull(uiState.questionWizardIndex)
                            val questionCount = request.questions.size.coerceAtLeast(1)
                            val index = uiState.questionWizardIndex.coerceIn(0, questionCount - 1)
                            val answered =
                                if (uiState.questionCustomSelected) {
                                    uiState.questionCustomText.isNotBlank()
                                } else {
                                    uiState.questionSelectedLabels.isNotEmpty()
                                }
                            val submitting = uiState.questionSubmitting
                            OpenCodeSessionComposerState.Question(
                                requestId = request.id,
                                sessionId = request.sessionId,
                                questionIndex = index,
                                questionCount = questionCount,
                                header = question?.header.orEmpty(),
                                question = question?.question.orEmpty(),
                                options =
                                    question?.options.orEmpty().map { option ->
                                        OpenCodeSessionComposerState.QuestionOption(
                                            label = option.label,
                                            description = option.description,
                                        )
                                    },
                                multiple = question?.multiple == true,
                                customAllowed = question?.custom != false,
                                selectedLabels = uiState.questionSelectedLabels,
                                customText = uiState.questionCustomText,
                                customSelected = uiState.questionCustomSelected,
                                backEnabled = index > 0 && !submitting,
                                nextEnabled = answered && index < questionCount - 1 && !submitting,
                                submitEnabled = answered && index == questionCount - 1 && !submitting,
                                submitting = submitting,
                                errorMessage = uiState.questionErrorMessage,
                            )
                        } ?: OpenCodeSessionComposerState.Prompt(
                            text = uiState.composer.text,
                            mentionRanges = uiState.composer.agentMentions.map { it.start until it.end },
                            selectionStart = uiState.composer.cursor,
                            selectionEnd = uiState.composer.cursor,
                            selectionRevision = uiState.composer.selectionRevision,
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
                                    !uiState.isSending &&
                                    uiState.connectionStatus == SessionConnectionStatus.Live,
                            attachEnabled = uiState.composer.canAttach && uiState.composer.isModelSelectionReady,
                            showStop = uiState.sessionStatusLabel != null,
                            stopEnabled =
                                !uiState.isAborting &&
                                    uiState.connectionStatus == SessionConnectionStatus.Live,
                        )
                    OpenCodeSessionComposer(
                        state = composerState,
                        onAction = { action ->
                            when (action) {
                                is OpenCodeSessionComposerAction.TextChanged ->
                                    viewModel.updateComposerText(action.text, action.selectionStart)
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
                                OpenCodeSessionComposerAction.OpenCommands -> viewModel.openCommands()
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
                                is OpenCodeSessionComposerAction.ToggleQuestionOption ->
                                    viewModel.toggleQuestionOption(action.requestId, action.sessionId, action.label)
                                is OpenCodeSessionComposerAction.ToggleQuestionCustom ->
                                    viewModel.toggleQuestionCustom(action.requestId, action.sessionId)
                                is OpenCodeSessionComposerAction.QuestionCustomTextChanged ->
                                    viewModel.updateQuestionCustomText(action.requestId, action.sessionId, action.text)
                                is OpenCodeSessionComposerAction.QuestionBack ->
                                    viewModel.questionBack(action.requestId, action.sessionId)
                                is OpenCodeSessionComposerAction.QuestionNext ->
                                    viewModel.questionNext(action.requestId, action.sessionId)
                                is OpenCodeSessionComposerAction.SubmitQuestion ->
                                    viewModel.submitQuestion(action.requestId, action.sessionId)
                                is OpenCodeSessionComposerAction.DismissQuestion ->
                                    viewModel.dismissQuestion(action.requestId, action.sessionId)
                            }
                        },
                    )
                }
            }
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

        SessionChatTodosSheet(
            visible = showSessionTodosSheet,
            todos = uiState.sessionTodos,
            completedCount = uiState.sessionTodoCompletedCount,
            totalCount = maxOf(uiState.sessionTodoTotalCount, 1),
            onDismiss = { showSessionTodosSheet = false },
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
                    enabled =
                        !uiState.rename.isSubmitting &&
                            uiState.connectionStatus == SessionConnectionStatus.Live,
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

        OpenCodeBottomSheet(
            visible = uiState.commandsSheetVisible,
            title = "Commands",
            onDismissRequest = viewModel::dismissCommands,
        ) {
            uiState.visibleClientSlashActions.forEach { action ->
                val requiresLive =
                    action == ClientSlashAction.New || action == ClientSlashAction.Fork
                OpenCodeBottomSheetEntry(
                    title = action.rowTitle,
                    enabled =
                        !requiresLive || uiState.connectionStatus == SessionConnectionStatus.Live,
                    onClick = { viewModel.runClientSlashAction(action) },
                )
            }
        }

        OpenCodeDialog(
            visible = uiState.forkDialogVisible,
            title = "Fork session",
            primaryOption =
                OpenCodeDialogOption(
                    title = "Fork",
                    enabled =
                        !uiState.forkSubmitting &&
                            uiState.connectionStatus == SessionConnectionStatus.Live,
                    onClick = viewModel::confirmFork,
                ),
            secondaryOption =
                OpenCodeDialogOption(
                    title = "Cancel",
                    enabled = !uiState.forkSubmitting,
                    onClick = viewModel::cancelForkDialog,
                ),
            textField =
                OpenCodeDialogTextField(
                    value = uiState.forkDraftTitle,
                    onValueChange = viewModel::updateForkDraft,
                    hint = "Title",
                    enabled = !uiState.forkSubmitting,
                    errorMessage = uiState.forkErrorMessage,
                ),
            onDismissRequest = {
                if (!uiState.forkSubmitting) {
                    viewModel.cancelForkDialog()
                }
            },
        )

        OpenCodeDialog(
            visible = uiState.forkResultVisible,
            title = "Session forked",
            subtitle = "“${uiState.forkedSessionTitle}” is ready.",
            primaryOption = OpenCodeDialogOption(title = "Open", onClick = viewModel::chooseForkOpen),
            secondaryOption = OpenCodeDialogOption(title = "Stay here", onClick = viewModel::chooseForkStay),
            onDismissRequest = viewModel::chooseForkStay,
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

/** Temporarily off: bar currently only shows context usage. */
private const val ShowsSessionStatusBar = false

private fun SessionConnectionStatus.toOpenCodeSessionConnectionStatus(): OpenCodeSessionConnectionStatus =
    when (this) {
        SessionConnectionStatus.Live -> OpenCodeSessionConnectionStatus.Live
        SessionConnectionStatus.Connecting -> OpenCodeSessionConnectionStatus.Connecting
        SessionConnectionStatus.Offline -> OpenCodeSessionConnectionStatus.Offline
    }

private fun SessionChatSnackbar.toOpenCodeSnackbarMessage(): OpenCodeSnackbarMessage =
    OpenCodeSnackbarMessage(
        text = message,
        variant =
            when (tone) {
                SessionChatSnackbarTone.Default -> OpenCodeSnackbarVariant.Default
                SessionChatSnackbarTone.Error -> OpenCodeSnackbarVariant.Error
            },
        id = id,
    )

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
