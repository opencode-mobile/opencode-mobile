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
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.SessionToolGroup
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.message.AttachmentIngestionService
import dev.opencode.mobile.domain.session.SessionTodoList
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomFade
import dev.opencode.mobile.ui.components.OpenCodeBottomFadeOverhang
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionItem
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionOverlay
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
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
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleLeading
import dev.opencode.mobile.ui.components.copySessionIdToClipboard
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.view.ComposerPickerKind
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatPickerSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineViewProps
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTodosSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.toRoute
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
    optimisticNavigationInstanceId: String? = null,
    parentId: String? = null,
    onNavigateBack: () -> Unit = {},
    onSessionContextClick: () -> Unit = {},
    onReviewChangesClick: (ChangesNavigationTarget) -> Unit = {},
    onChildSessionClick: (ChildSessionNavigationTarget) -> Unit = {},
    onToolGroupClick: (SessionToolGroup) -> Unit = {},
    onSessionReplaceRequested: (ChildSessionNavigationTarget) -> Unit = {},
    viewModel: SessionChatViewModel =
        hiltViewModel(
            key = sessionChatViewModelKey(sessionId, worktree, optimisticNavigationInstanceId),
        ),
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
    var sawUnfinishedTodos by remember { mutableStateOf(false) }
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
            sawUnfinishedTodos = false
            holdingFinishedTodos = false
            showSessionTodosSheet = false
            return@LaunchedEffect
        }
        if (!uiState.sessionTodosFinished) {
            sawUnfinishedTodos = true
            holdingFinishedTodos = false
            return@LaunchedEffect
        }
        if (!SessionTodoList.shouldHoldFinished(sawUnfinishedTodos, uiState.sessionTodos)) {
            holdingFinishedTodos = false
            return@LaunchedEffect
        }
        holdingFinishedTodos = true
        delay(1_500)
        holdingFinishedTodos = false
        showSessionTodosSheet = false
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

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_OVERFLOW,
        presentation = ProductAnalytics.Presentation.OVERLAY,
        visible = menuExpanded,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_COMMANDS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = uiState.commandsSheetVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_RENAME,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = uiState.rename.isDialogVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_FORK,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = uiState.forkDialogVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_FORK_RESULT,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = uiState.forkResultVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_DELETE_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = showDeleteConfirmation,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_ATTACHMENT_SOURCE,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = showAttachmentSource,
    )

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
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SESSION_DELETE_CONFIRM,
                        actionId = ProductAnalytics.Action.CHAT_DELETE_CANCEL,
                    )
                    showDeleteConfirmation = false
                },
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
                    buildList {
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Rename session",
                                icon = OpenCodeIcons.Edit,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openRenameSession()
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Review changes",
                                icon = OpenCodeIcons.Description,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_REVIEW_CHANGES)
                                    menuExpanded = false
                                    onReviewChangesClick(uiState.changesNavigationTarget(worktree))
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Session context",
                                icon = OpenCodeIcons.Architecture,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_SESSION_CONTEXT)
                                    menuExpanded = false
                                    onSessionContextClick()
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Copy session ID",
                                icon = OpenCodeIcons.ContentCopy,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_COPY_SESSION_ID)
                                    menuExpanded = false
                                    copySessionIdToClipboard(context, uiState.sessionId)
                                },
                            ),
                        )
                        if (uiState.supportsSessionArchive) {
                            add(
                                OpenCodeOverflowMenuEntry(
                                    title = "Archive",
                                    icon = OpenCodeIcons.Archive,
                                    enabled = lifecycleActionsEnabled,
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.archiveSession()
                                    },
                                ),
                            )
                        }
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Delete",
                                icon = OpenCodeIcons.Close,
                                enabled = lifecycleActionsEnabled,
                                destructive = true,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_DELETE)
                                    menuExpanded = false
                                    showDeleteConfirmation = true
                                },
                            ),
                        )
                    },
            ) {
                OpenCodeTopBar(
                    title = uiState.sessionTitle.ifBlank { sessionTitle },
                    subtitle = uiState.projectLabel,
                    subtitleLeading =
                        uiState.projectLetter?.let { letter ->
                            uiState.projectLetterAccent?.let { accent ->
                                OpenCodeTopBarSubtitleLeading.Letter(letter, accent)
                            }
                        },
                    subtitleAccessory =
                        OpenCodeTopBarSubtitleAccessory.StatusDot(
                            uiState.connectionStatus.toOpenCodeSessionConnectionStatus(),
                        ),
                    leading =
                        OpenCodeTopBarLeading.Back(
                            onClick = {
                                logChatAction(ProductAnalytics.Action.CHAT_BACK)
                                onNavigateBack()
                            },
                        ),
                    actions =
                        listOf(
                            OpenCodeTopBarAction.Icon(
                                imageVector = OpenCodeIcons.MoreVert,
                                contentDescription = "More options",
                                onClick = {
                                    logChatAction(ProductAnalytics.Action.CHAT_OPEN_OVERFLOW)
                                    menuExpanded = true
                                },
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
                    onContextClick = {
                        logChatAction(ProductAnalytics.Action.CHAT_OPEN_SESSION_CONTEXT)
                        onSessionContextClick()
                    },
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
                            syncingIndicatorLabel = uiState.syncingIndicatorLabel,
                            workingIndicatorLabel = uiState.workingIndicatorLabel,
                            sessionTodosVisible = sessionTodosPillVisible,
                            sessionTodoCompletedCount = uiState.sessionTodoCompletedCount,
                            sessionTodoTotalCount = uiState.sessionTodoTotalCount,
                            sessionTodoActivePreview = uiState.sessionTodoActivePreview?.content,
                            hideFloatStack = showSessionTodosSheet,
                            // Composer + 16 end-pin gap; Scroll to end uses this as-is
                            // (no extra scrollToEndPillBottomPadding).
                            bottomOverlayInset = timelineBottomInset,
                            onRefresh = viewModel::refreshMessages,
                            onReviewChangesClick = {
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHANGES)
                                uiState.reviewChangesNavigationTarget(worktree)?.let(onReviewChangesClick)
                            },
                            onSessionTodosClick = {
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_TODOS)
                                if (!blocksSessionTodosSheet) {
                                    showSessionTodosSheet = true
                                }
                            },
                            onMessageActionsTap = {
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_MESSAGE_ACTIONS)
                                messageActionsTarget = it
                            },
                            onToolDetailTap = {
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_TOOL_DETAIL)
                                toolDetailTarget = it
                            },
                            onToolGroupTap = { target ->
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_TOOL_GROUP)
                                onToolGroupClick(
                                    target.toRoute(
                                        sessionId = sessionId,
                                        worktree = worktree,
                                        parentId = parentId,
                                        owningTurnMessageId = uiState.owningTurnMessageId(target.messageId),
                                        supportsSessionMessageDiff = uiState.supportsSessionMessageDiff,
                                    ),
                                )
                            },
                            onChildSessionTap = { childSessionId, childTitle ->
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHILD_SESSION)
                                onChildSessionClick(
                                    ChildSessionNavigationTarget(
                                        sessionId = childSessionId,
                                        sessionTitle = childTitle,
                                        worktree = worktree,
                                    ),
                                )
                            },
                            onChangesReviewTap = { assistantMessageId, initialFilePath ->
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHANGES_FROM_TOOL)
                                uiState
                                    .fileChangeNavigationTarget(
                                        worktree = worktree,
                                        assistantMessageId = assistantMessageId,
                                        initialFilePath = initialFilePath,
                                    )?.let(onReviewChangesClick)
                            },
                            supportsSessionMessageDiff = uiState.supportsSessionMessageDiff,
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
                    if (SessionChatComposerKind.resolve(
                            isChildSession = parentId != null,
                            hasPermission = uiState.permissionRequest != null,
                            hasQuestion = uiState.questionRequest != null,
                        ) == SessionChatComposerKind.Prompt
                    ) {
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

                    val composerKind =
                        SessionChatComposerKind.resolve(
                            isChildSession = parentId != null,
                            hasPermission = uiState.permissionRequest != null,
                            hasQuestion = uiState.questionRequest != null,
                        )
                    val composerState =
                        when (composerKind) {
                            SessionChatComposerKind.Permission -> {
                                val request = uiState.permissionRequest
                                OpenCodeSessionComposerState.Permission(
                                    requestId = request?.id.orEmpty(),
                                    sessionId = request?.sessionId.orEmpty(),
                                    permission = request?.permission.orEmpty(),
                                    patterns = request?.patterns.orEmpty(),
                                    queueCount = uiState.permissionQueueCount,
                                    submitting = uiState.permissionSubmitting,
                                    actionsEnabled = uiState.canMutate,
                                    errorMessage = uiState.permissionErrorMessage,
                                )
                            }
                            SessionChatComposerKind.Question -> {
                                val request = uiState.questionRequest
                                val question = request?.questions?.getOrNull(uiState.questionWizardIndex)
                                val questionCount = request?.questions?.size?.coerceAtLeast(1) ?: 1
                                val index = uiState.questionWizardIndex.coerceIn(0, questionCount - 1)
                                val answered =
                                    if (uiState.questionCustomSelected) {
                                        uiState.questionCustomText.isNotBlank()
                                    } else {
                                        uiState.questionSelectedLabels.isNotEmpty()
                                    }
                                val submitting = uiState.questionSubmitting
                                OpenCodeSessionComposerState.Question(
                                    requestId = request?.id.orEmpty(),
                                    sessionId = request?.sessionId.orEmpty(),
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
                            }
                            SessionChatComposerKind.ChildLockout ->
                                OpenCodeSessionComposerState.ChildLockout(
                                    selectedModelLabel = uiState.composer.selectedModelLabel,
                                    selectedVariantLabel = uiState.composer.selectedVariantLabel,
                                    showVariant = uiState.composer.canSelectVariant,
                                )
                            SessionChatComposerKind.Prompt ->
                                OpenCodeSessionComposerState.Prompt(
                                    text = uiState.composer.text,
                                    mentionRanges = uiState.composer.agentMentions.map { it.start until it.end },
                                    selectionStart = uiState.composer.cursor,
                                    selectionEnd = uiState.composer.cursor,
                                    selectionRevision = uiState.composer.selectionRevision,
                                    attachments =
                                        uiState.composer.attachments.map(AttachmentPresentationMapper::from),
                                    selectedModelLabel = uiState.composer.selectedModelLabel,
                                    selectedModeLabel = uiState.composer.selectedModeLabel,
                                    selectedVariantLabel = uiState.composer.selectedVariantLabel,
                                    showVariant = uiState.composer.canSelectVariant,
                                    sendEnabled =
                                        uiState.composer.canSend &&
                                            uiState.composer.selectedModel != null &&
                                            uiState.composer.isModelSelectionReady &&
                                            !uiState.isSending &&
                                            uiState.timeline != SessionChatTimelineState.Creating &&
                                            uiState.canMutate,
                                    attachEnabled = uiState.composer.canAttach && uiState.composer.isModelSelectionReady,
                                    showStop = uiState.sessionStatusLabel != null,
                                    stopEnabled =
                                        !uiState.isAborting &&
                                            uiState.canMutate,
                                    followUpDelivery =
                                        uiState.composer.followUpDelivery.takeIf {
                                            uiState.composer.supportsFollowUpDelivery
                                        },
                                )
                        }
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
                                    logChatAction(ProductAnalytics.Action.COMPOSER_OPEN_MODEL_PICKER)
                                    activeModelProviderId =
                                        uiState.composer.selectedModel
                                            ?.option
                                            ?.providerId
                                    activePicker = ComposerPickerKind.Model
                                }
                                OpenCodeSessionComposerAction.SelectMode -> {
                                    logChatAction(ProductAnalytics.Action.COMPOSER_OPEN_MODE_PICKER)
                                    activePicker = ComposerPickerKind.Mode
                                }
                                OpenCodeSessionComposerAction.SelectVariant -> {
                                    logChatAction(ProductAnalytics.Action.COMPOSER_OPEN_VARIANT_PICKER)
                                    activePicker = ComposerPickerKind.Variant
                                }
                                OpenCodeSessionComposerAction.OpenCommands -> viewModel.openCommands()
                                OpenCodeSessionComposerAction.Attach -> {
                                    logChatAction(ProductAnalytics.Action.COMPOSER_ATTACH)
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
                                OpenCodeSessionComposerAction.ToggleFollowUp -> viewModel.toggleFollowUp()
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
                                OpenCodeSessionComposerAction.BackToMainSession -> {
                                    logChatAction(ProductAnalytics.Action.CHAT_BACK)
                                    onNavigateBack()
                                }
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
            onDismissRequest = {
                logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_SOURCE_DISMISS)
                showAttachmentSource = false
            },
        ) {
            if (uiState.composer.canAttachPhotos) {
                OpenCodeBottomSheetEntry(
                    title = "Photos",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Description),
                    onClick = {
                        logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_PICK_PHOTOS)
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
                        logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_PICK_FILES)
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
            selectableAgents = uiState.composer.selectableAgents,
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
                            uiState.canMutate,
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
                        !requiresLive || uiState.canMutate,
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
                            uiState.canMutate,
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

private fun logChatAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_CHAT,
        actionId = actionId,
    )
}

private fun logOverflowAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_OVERFLOW,
        actionId = actionId,
    )
}

private fun logAttachmentSourceAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_ATTACHMENT_SOURCE,
        actionId = actionId,
    )
}

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

internal fun sessionChatViewModelKey(
    sessionId: String,
    worktree: String,
    optimisticNavigationInstanceId: String?,
): String = optimisticNavigationInstanceId?.let { "$sessionId-$worktree-$it" } ?: "$sessionId-$worktree"

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
