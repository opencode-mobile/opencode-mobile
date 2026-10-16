package dev.opencode.mobile.ui.sessionchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.message.FailureAction
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.SessionToolGroup
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogAttachment
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.message.AttachmentIngestionService
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.SessionTodoList
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeActionTile
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadge
import dev.opencode.mobile.ui.components.OpenCodeActionTileGrid
import dev.opencode.mobile.ui.components.OpenCodeBottomFade
import dev.opencode.mobile.ui.components.OpenCodeBottomFadeOverhang
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionItem
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionOverlay
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeLayout
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodePendingPromptsStrip
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.ComposerDictationCardModel
import dev.opencode.mobile.ui.components.OpenCodeDictationCardVariant
import dev.opencode.mobile.ui.components.OpenCodeDictationUnavailableReason
import dev.opencode.mobile.ui.components.OpenCodeSessionComposer
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerAction
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSessionStatusBar
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleLeading
import dev.opencode.mobile.ui.components.copySessionIdToClipboard
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.components.rememberOpenCodeSessionComposerFocus
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutAction
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutHandler
import dev.opencode.mobile.ui.sessionchat.view.ComposerPickerKind
import dev.opencode.mobile.ui.sessionchat.view.SessionRetryCountdown
import dev.opencode.mobile.ui.sessionchat.view.sessionFailureDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMcpServersSheetContent
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatPendingPromptsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatPickerSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineViewProps
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTodosSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatChipTimelineIntroModal
import dev.opencode.mobile.ui.sessionchat.view.SessionChatSkillsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatSkillsTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget
import dev.opencode.mobile.ui.sessionchat.dictation.ComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.DictationModelDownloader
import dev.opencode.mobile.ui.sessionchat.dictation.DictationSupport
import dev.opencode.mobile.ui.sessionchat.dictation.SystemComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.formatDictationElapsed
import dev.opencode.mobile.ui.sessionchat.dictation.probeDictationSupport
import dev.opencode.mobile.ui.sessionchat.view.toActionTileBadge
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
    onTerminalRequested: (worktree: String, projectTitle: String) -> Unit = { _, _ -> },
    onFilesRequested: (worktree: String) -> Unit = {},
    detailControl: OpenCodeSplitPaneDetailControl? = null,
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
    // The entry stays on the back stack (and keeps its ViewModel) while a child route
    // is on top, so clear Ready focus whenever the screen leaves composition, not only
    // in onCleared (parity with iOS stopObserving onDisappear).
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.cancelDictationForDismiss()
            viewModel.clearSessionReadyFocus()
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val attachmentSources = uiState.composer.attachmentSources(DeveloperToolsPreferences.unlocked)
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }
    var activeModelProviderId by remember { mutableStateOf<String?>(null) }
    var modelFilterText by remember { mutableStateOf("") }
    var messageActionsTarget by remember { mutableStateOf<SessionChatMessageActionsTarget?>(null) }
    var showPendingPromptsSheet by rememberSaveable { mutableStateOf(false) }
    var toolDetailTarget by remember { mutableStateOf<SessionChatToolDetailTarget?>(null) }
    var skillsTarget by remember { mutableStateOf<SessionChatSkillsTarget?>(null) }
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
    val sessionCreated = !OptimisticSessionCreate.isPlaceholder(uiState.sessionId)
    val lifecycleActionsEnabled = sessionCreated && !uiState.isLifecycleActionInFlight
    var showAttachmentSource by rememberSaveable { mutableStateOf(false) }

    val blocksSessionTodosSheet = uiState.permissionRequest != null || uiState.questionRequest != null
    val sessionTodosPillVisible = uiState.sessionTodosVisible || holdingFinishedTodos

    LaunchedEffect(uiState.permissionRequest?.id, uiState.questionRequest?.id) {
        if (blocksSessionTodosSheet) {
            showSessionTodosSheet = false
        }
    }

    LaunchedEffect(uiState.pendingPrompts.size) {
        if (uiState.pendingPrompts.isEmpty()) {
            showPendingPromptsSheet = false
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

    fun attachDiagnosticLog() {
        coroutineScope.launch {
            val attachment =
                runCatching {
                    withContext(Dispatchers.Default) {
                        DiagnosticLogAttachment.from(DiagnosticLogBuffer.shared)
                    }
                }.getOrElse {
                    viewModel.reportAttachmentError(AttachmentIngestionService.READ_ERROR)
                    return@launch
                }
            viewModel.addAttachments(listOf(attachment))
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
    val dictationSession = remember(context) { SystemComposerDictationSession(context) }
    val dictationModelDownloader = remember(context) { DictationModelDownloader(context) }
    var dictationSupport by remember { mutableStateOf<DictationSupport>(DictationSupport.Hidden) }
    var dictationNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val dictationPhase = uiState.dictation?.phase
    val dictationTicking =
        dictationPhase == ComposerDictationPhase.Listening || dictationPhase == ComposerDictationPhase.Finishing

    fun startReadyDictation(support: DictationSupport.Ready) {
        viewModel.beginDictation(support.languageLabel, support.onDevice)
        if (viewModel.uiState.value.dictation?.phase != ComposerDictationPhase.Listening) return
        dictationSession.start(
            object : ComposerDictationSession.Listener {
                override fun onPartial(
                    committed: String,
                    volatile: String,
                ) {
                    viewModel.dictationPartial(committed, volatile)
                }

                override fun onLevel(level: Float) {
                    viewModel.dictationLevel(level)
                }

                override fun onStopped() {
                    viewModel.dictationStopped()
                }

                override fun onUnavailable(
                    phase: ComposerDictationPhase,
                    message: String?,
                ) {
                    viewModel.beginDictationUnavailable(phase, message)
                }
            },
        )
    }

    fun applyProbedDictation(
        support: DictationSupport,
        startIfReady: Boolean,
    ) {
        dictationSupport = support
        if (!startIfReady) return
        when (support) {
            is DictationSupport.Ready -> startReadyDictation(support)
            DictationSupport.ModelNotInstalled ->
                viewModel.beginDictationUnavailable(ComposerDictationPhase.ModelNotInstalled)
            DictationSupport.PermissionDenied ->
                viewModel.beginDictationUnavailable(ComposerDictationPhase.PermissionDenied)
            DictationSupport.Hidden -> viewModel.dictationCancel()
        }
    }

    val recordAudioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                coroutineScope.launch {
                    applyProbedDictation(probeDictationSupport(context), startIfReady = true)
                }
            } else {
                viewModel.beginDictationUnavailable(ComposerDictationPhase.PermissionDenied)
            }
        }

    fun requestDictationOrStart() {
        when (val support = dictationSupport) {
            DictationSupport.Hidden -> Unit
            DictationSupport.ModelNotInstalled ->
                viewModel.beginDictationUnavailable(ComposerDictationPhase.ModelNotInstalled)
            DictationSupport.PermissionDenied,
            is DictationSupport.Ready,
            -> {
                val granted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                when {
                    !granted -> recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    support is DictationSupport.Ready -> startReadyDictation(support)
                    else ->
                        coroutineScope.launch {
                            applyProbedDictation(probeDictationSupport(context), startIfReady = true)
                        }
                }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.cancelDictationForBackground()
    }
    LaunchedEffect(Unit) {
        dictationSupport = probeDictationSupport(context)
    }
    LaunchedEffect(uiState.dictation == null, dictationPhase) {
        if (uiState.dictation == null || dictationPhase != ComposerDictationPhase.Listening) {
            dictationSession.stop()
        }
    }
    LaunchedEffect(uiState.dictation?.startedAtEpochMs, dictationTicking) {
        if (!dictationTicking) return@LaunchedEffect
        dictationNowMs = System.currentTimeMillis()
        while (true) {
            delay(1000)
            dictationNowMs = System.currentTimeMillis()
        }
    }
    val composerKindForDictation =
        SessionChatComposerKind.resolve(
            isChildSession = parentId != null,
            hasPermission = uiState.permissionRequest != null && !uiState.permissionDockHidden,
            hasQuestion = uiState.questionRequest != null,
        )
    LaunchedEffect(composerKindForDictation) {
        if (composerKindForDictation != SessionChatComposerKind.Prompt) {
            viewModel.cancelDictationForModeChange()
        }
    }
    DisposableEffect(dictationSession, dictationModelDownloader) {
        onDispose {
            dictationSession.stop()
            dictationModelDownloader.close()
        }
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

    LaunchedEffect(uiState.pendingFilesOpen) {
        uiState.pendingFilesOpen?.let { target ->
            onFilesRequested(target)
            viewModel.consumePendingFilesOpen()
        }
    }

    LaunchedEffect(uiState.pendingTerminalOpen) {
        uiState.pendingTerminalOpen?.let { target ->
            onTerminalRequested(target.worktree, target.projectTitle)
            viewModel.consumePendingTerminalOpen()
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

    Box(modifier = modifier.fillMaxSize()) {
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
                                enabled = sessionCreated,
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
                                enabled = sessionCreated,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_REVIEW_CHANGES)
                                    menuExpanded = false
                                    onReviewChangesClick(uiState.changesNavigationTarget(worktree))
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Fork session",
                                icon = OpenCodeIcons.CallSplit,
                                enabled = sessionCreated,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openForkSession()
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Browse files",
                                icon = OpenCodeIcons.Folder,
                                enabled = sessionCreated,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_BROWSE_FILES)
                                    menuExpanded = false
                                    onFilesRequested(worktree)
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = "Session context",
                                icon = OpenCodeIcons.Architecture,
                                enabled = sessionCreated,
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
                                enabled = sessionCreated,
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
                        if (uiState.worktreeCheckout) {
                            OpenCodeTopBarSubtitleLeading.Worktree
                        } else {
                            uiState.projectLetter?.let { letter ->
                                uiState.projectLetterAccent?.let { accent ->
                                    OpenCodeTopBarSubtitleLeading.Letter(letter, accent)
                                }
                            }
                        },
                    subtitleAccessory =
                        OpenCodeTopBarSubtitleAccessory.StatusDot(
                            uiState.connectionStatus.toOpenCodeSessionConnectionStatus(),
                        ),
                    leading =
                        openCodeDetailLeading(detailControl) {
                            logChatAction(ProductAnalytics.Action.CHAT_BACK)
                            onNavigateBack()
                        },
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
                OpenCodeInlineNotice(
                    message = notice,
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = OpenCodeLayout.ContentMaxWidth)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                )
            }

            OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
                SessionChatTimelineView(
                    props =
                        SessionChatTimelineViewProps(
                            timeline = uiState.timeline,
                            messages = uiState.messages,
                            timelineFrame = uiState.timelineFrame,
                            isRefreshing = uiState.isRefreshing,
                            hasOlder = uiState.hasOlder,
                            isLoadingOlder = uiState.isLoadingOlder,
                            errorMessage = uiState.errorMessage,
                            sessionBranchPill = uiState.sessionBranchPill,
                            syncingIndicatorLabel = uiState.syncingIndicatorLabel,
                            workingIndicatorLabel = uiState.workingIndicatorLabel,
                            workingIndicatorCollapsed = uiState.workingIndicatorCollapsed,
                            workingIndicatorFading = uiState.workingIndicatorFading,
                            sessionTodosVisible = sessionTodosPillVisible,
                            sessionTodoCompletedCount = uiState.sessionTodoCompletedCount,
                            sessionTodoTotalCount = uiState.sessionTodoTotalCount,
                            sessionTodoActivePreview = uiState.sessionTodoActivePreview?.content,
                            hideFloatStack = showSessionTodosSheet,
                            hideLiveRetryFailures = uiState.retryCard != null,
                            // Composer + 16 end-pin gap; Scroll to end uses this as-is
                            // (no extra scrollToEndPillBottomPadding).
                            bottomOverlayInset = timelineBottomInset,
                            onRefresh = viewModel::refreshMessages,
                            onRetry = viewModel::retryFailedTimeline,
                            onLoadOlder = viewModel::loadOlderMessages,
                            onSessionBranchClick = {
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHANGES)
                                onReviewChangesClick(uiState.changesNavigationTarget(worktree))
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
                            onSkillsTap = {
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_SKILLS)
                                skillsTarget = it
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
                            expandedStepParagraphs = uiState.expandedStepParagraphs,
                            onExpandStepParagraph = viewModel::expandStepParagraph,
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
                            .fillMaxWidth(),
                ) {
                    val overlayComposerKind =
                        SessionChatComposerKind.resolve(
                            isChildSession = parentId != null,
                            hasPermission = uiState.permissionRequest != null && !uiState.permissionDockHidden,
                            hasQuestion = uiState.questionRequest != null,
                        )
                    if (overlayComposerKind == SessionChatComposerKind.Prompt) {
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
                                    emptyMessage = "No matches",
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

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    val height = coordinates.size.height
                                    if (height != composerOverlayHeightPx) {
                                        composerOverlayHeightPx = height
                                    }
                                }.windowInsetsPadding(
                                    WindowInsets.ime.union(WindowInsets.navigationBars),
                                ),
                    ) {
                        // Suggestions float above this column. Keep the pending strip
                        // in the measured height so the timeline inset does not jump.
                        if (overlayComposerKind == SessionChatComposerKind.Prompt &&
                            uiState.permissionDockHidden
                        ) {
                            OpenCodeActionPill(
                                variant = OpenCodeActionPillVariant.ReviewPermission,
                                onClick = viewModel::showPermissionDock,
                                modifier =
                                    Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(bottom = 6.dp),
                            )
                        }
                        if (overlayComposerKind == SessionChatComposerKind.Prompt &&
                            uiState.pendingPrompts.isNotEmpty()
                        ) {
                            OpenCodePendingPromptsStrip(
                                prompts = uiState.pendingPrompts,
                                onClick = { showPendingPromptsSheet = true },
                                modifier =
                                    Modifier
                                        .padding(horizontal = 12.dp)
                                        .padding(bottom = 6.dp),
                            )
                        }

                        val composerKind =
                            SessionChatComposerKind.resolve(
                                isChildSession = parentId != null,
                                hasPermission = uiState.permissionRequest != null && !uiState.permissionDockHidden,
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
                                        files =
                                            request?.fileChanges.orEmpty().map { file ->
                                                OpenCodeSessionComposerState.PermissionFile(
                                                    path = file.path,
                                                    additions = file.additions,
                                                    deletions = file.deletions,
                                                )
                                            },
                                        alwaysPatterns = request?.always.orEmpty(),
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
                                        question != null &&
                                            isQuestionAnswerValid(
                                                question,
                                                QuestionAnswerDraft(
                                                    selectedLabels = uiState.questionSelectedLabels,
                                                    customText = uiState.questionCustomText,
                                                    customSelected = uiState.questionCustomSelected,
                                                ),
                                            )
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
                                        minSelections = question?.minSelections,
                                        maxSelections = question?.maxSelections,
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
                                        mentionRanges = uiState.composer.mentionRanges,
                                        selectionStart = uiState.composer.cursor,
                                        selectionEnd = uiState.composer.cursor,
                                        selectionRevision = uiState.composer.selectionRevision,
                                        attachments =
                                            uiState.composer.attachments.map(AttachmentPresentationMapper::from),
                                        showPendingRevert = uiState.hasPendingRevert,
                                        selectedModelLabel = uiState.composer.selectedModelLabel,
                                        selectedModeLabel = uiState.composer.selectedModeLabel,
                                        selectedVariantLabel = uiState.composer.selectedVariantLabel,
                                        showVariant = uiState.composer.canSelectVariant,
                                        sendEnabled =
                                            uiState.composer.canSend &&
                                                uiState.composer.selectedModel != null &&
                                                uiState.composer.isModelSelectionReady &&
                                                !uiState.isSending &&
                                                sessionCreated &&
                                                uiState.timeline != SessionChatTimelineState.Creating &&
                                                uiState.composerEnabled,
                                        attachEnabled =
                                            attachmentSources.isAttachEnabled &&
                                                sessionCreated &&
                                                uiState.composer.isModelSelectionReady,
                                        showStop = uiState.sessionStatusLabel != null,
                                        stopEnabled =
                                            !uiState.isAborting &&
                                                uiState.canMutate,
                                        followUpDelivery =
                                            uiState.composer.followUpDelivery.takeIf {
                                                uiState.composer.supportsFollowUpDelivery
                                            },
                                        showDictationButton =
                                            uiState.composerDictationEnabled && dictationSupport.showsMic,
                                    )
                            }
                        val composerFocus = rememberOpenCodeSessionComposerFocus()
                        KeyboardShortcutHandler(
                            KeyboardShortcutAction.FocusComposer,
                            enabled = composerKind == SessionChatComposerKind.Prompt,
                        ) {
                            composerFocus.requestFocus()
                        }
                        val dictationCard =
                            uiState.dictation?.toCardModel(
                                elapsedLabel =
                                    formatDictationElapsed(
                                        startedAtEpochMs = uiState.dictation?.startedAtEpochMs ?: 0L,
                                        nowEpochMs = dictationNowMs,
                                    ),
                            )
                        OpenCodeSessionComposer(
                            state = composerState,
                            focus = composerFocus,
                            dictation = dictationCard,
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
                                        when {
                                            attachmentSources.showsSourceSheet -> showAttachmentSource = true
                                            attachmentSources.photos ->
                                                photoPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                )
                                            attachmentSources.files -> documentPicker.launch(arrayOf("*/*"))
                                        }
                                    }
                                    is OpenCodeSessionComposerAction.RemoveAttachment -> viewModel.removeAttachment(action.id)
                                    OpenCodeSessionComposerAction.CancelRevert -> viewModel.cancelRevert()
                                    OpenCodeSessionComposerAction.Send -> viewModel.sendPrompt()
                                    OpenCodeSessionComposerAction.ToggleFollowUp -> viewModel.toggleFollowUp()
                                    OpenCodeSessionComposerAction.Stop -> viewModel.abort()
                                    is OpenCodeSessionComposerAction.AllowPermissionOnce ->
                                        viewModel.allowPermissionOnce(action.requestId, action.sessionId)
                                    is OpenCodeSessionComposerAction.DenyPermission ->
                                        viewModel.denyPermission(action.requestId, action.sessionId)
                                    is OpenCodeSessionComposerAction.AlwaysAllowPermission ->
                                        viewModel.alwaysAllowPermission(action.requestId, action.sessionId)
                                    is OpenCodeSessionComposerAction.HidePermission ->
                                        viewModel.hidePermissionDock(action.requestId)
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
                                    OpenCodeSessionComposerAction.BeginDictation -> requestDictationOrStart()
                                    OpenCodeSessionComposerAction.DictationCancel -> viewModel.dictationCancel()
                                    OpenCodeSessionComposerAction.DictationInsert -> viewModel.dictationInsert()
                                    OpenCodeSessionComposerAction.DictationOpenSettings -> {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                            },
                                        )
                                    }
                                    OpenCodeSessionComposerAction.DictationDownload ->
                                        dictationModelDownloader.trigger()
                                    OpenCodeSessionComposerAction.DictationRetry -> {
                                        coroutineScope.launch {
                                            val support = probeDictationSupport(context)
                                            dictationSupport = support
                                            when (support) {
                                                is DictationSupport.Ready -> requestDictationOrStart()
                                                DictationSupport.ModelNotInstalled ->
                                                    viewModel.beginDictationUnavailable(
                                                        ComposerDictationPhase.ModelNotInstalled,
                                                    )
                                                DictationSupport.PermissionDenied -> requestDictationOrStart()
                                                DictationSupport.Hidden -> viewModel.dictationCancel()
                                            }
                                        }
                                    }
                                }
                            },
                            status = uiState.retryCard?.let { card ->
                                {
                                    val linkContext = LocalContext.current
                                    val liveBody =
                                        SessionChatTimelineBlocks.newestLiveRetryProviderBody(uiState.messages)
                                    SessionRetryCountdown(
                                        card = card,
                                        onOpenLink = { openSessionExternalLink(linkContext, it) },
                                        onOpenBody =
                                            liveBody?.let { body ->
                                                {
                                                    toolDetailTarget =
                                                        sessionFailureDetailTarget(
                                                            text = card.message,
                                                            providerBody = body,
                                                            action = null,
                                                            actionLabel = null,
                                                            linkLabel = card.actionLabel,
                                                            link = card.actionLink,
                                                        )
                                                }
                                            },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        SessionChatMessageActionsSheet(
            target = messageActionsTarget,
            onDismiss = { messageActionsTarget = null },
            onRevertMessage = viewModel::revertMessage,
            onForkFromMessage = viewModel::forkFromMessage,
        )

        SessionChatPendingPromptsSheet(
            visible = showPendingPromptsSheet,
            prompts = uiState.pendingPrompts,
            onDismiss = { showPendingPromptsSheet = false },
            onSteer = viewModel::steerPendingPrompt,
            onQueue = viewModel::queuePendingPrompt,
            onDelete = viewModel::cancelPendingPrompt,
            onClearAll = viewModel::clearPendingPrompts,
        )

        val sheetContext = LocalContext.current
        SessionChatToolDetailSheet(
            target = toolDetailTarget,
            onDismiss = { toolDetailTarget = null },
            onFailureAction = { action ->
                when (action) {
                    FailureAction.PickModel ->
                        activePicker = ComposerPickerKind.Model
                    FailureAction.Compact -> viewModel.compactCurrentSession()
                    FailureAction.Copy -> Unit
                }
            },
            onOpenLink = { openSessionExternalLink(sheetContext, it) },
        )

        SessionChatSkillsSheet(
            target = skillsTarget,
            onDismiss = { skillsTarget = null },
        )

        // Waits for any screen-local sheet to close so the intro never stacks on one.
        val screenSheetOpen =
            messageActionsTarget != null ||
                showPendingPromptsSheet ||
                toolDetailTarget != null ||
                skillsTarget != null ||
                showSessionTodosSheet ||
                showAttachmentSource ||
                activePicker != null ||
                showDeleteConfirmation
        SessionChatChipTimelineIntroModal(
            visible = uiState.chipTimelineIntroVisible && !screenSheetOpen,
            onEnable = viewModel::enableChipTimelineFromIntro,
            onLater = viewModel::postponeChipTimelineIntro,
            onNever = viewModel::dismissChipTimelineIntro,
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
            if (attachmentSources.photos) {
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
            if (attachmentSources.files) {
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
            if (attachmentSources.logFile) {
                OpenCodeBottomSheetEntry(
                    title = "Log file",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.List),
                    onClick = {
                        logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_PICK_LOG_FILE)
                        showAttachmentSource = false
                        attachDiagnosticLog()
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
            selectedVariant = uiState.composer.selectedVariant,
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
            title = if (uiState.mcpPageVisible) "MCP servers" else "Commands",
            onDismissRequest = viewModel::dismissCommands,
            headerLeadingAction =
                if (uiState.mcpPageVisible) {
                    OpenCodeBottomSheetHeaderAction(
                        imageVector = OpenCodeIcons.ArrowBack,
                        contentDescription = "Back to commands",
                        onClick = viewModel::closeMcpServers,
                    )
                } else {
                    null
                },
        ) {
            if (uiState.mcpPageVisible) {
                BackHandler(onBack = viewModel::closeMcpServers)
                SessionChatMcpServersSheetContent(
                    servers = uiState.mcpServers,
                    loading = uiState.mcpLoading,
                    errorMessage = uiState.mcpErrorMessage,
                    summary = uiState.mcpSummary,
                )
            } else {
                CommandsActionTileGrid(
                    actions = uiState.visibleClientSlashActions,
                    canMutate = sessionCreated && uiState.canMutate,
                    mcpBadge = uiState.mcpSummary.toActionTileBadge(),
                    onAction = viewModel::runClientSlashAction,
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

private fun openSessionExternalLink(
    context: android.content.Context,
    link: String,
) {
    val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
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
        SessionConnectionStatus.AuthFailed -> OpenCodeSessionConnectionStatus.AuthFailed
    }

private fun ComposerDictationUiState.toCardModel(elapsedLabel: String): ComposerDictationCardModel {
    val (variant, reason) =
        when (phase) {
            ComposerDictationPhase.Listening -> OpenCodeDictationCardVariant.Listening to null
            ComposerDictationPhase.Stopped -> OpenCodeDictationCardVariant.Stopped to null
            ComposerDictationPhase.Finishing -> OpenCodeDictationCardVariant.Finishing to null
            ComposerDictationPhase.PermissionDenied ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.PermissionDenied
            ComposerDictationPhase.ModelNotInstalled ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelNotInstalled
            ComposerDictationPhase.RecognizerError ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.RecognizerError
        }
    return ComposerDictationCardModel(
        variant = variant,
        committed = committed,
        volatileTail = volatile,
        level = level,
        languageLabel = languageLabel,
        onDevice = onDevice,
        elapsedLabel = elapsedLabel,
        unavailableReason = reason,
        errorMessage = errorMessage,
    )
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

@Composable
private fun CommandsActionTileGrid(
    actions: List<ClientSlashAction>,
    canMutate: Boolean,
    mcpBadge: OpenCodeActionTileBadge?,
    onAction: (ClientSlashAction) -> Unit,
) {
    OpenCodeActionTileGrid(
        itemCount = actions.size,
        modifier = Modifier.padding(vertical = 4.dp),
    ) { index ->
        val action = actions[index]
        val requiresLive =
            action == ClientSlashAction.New ||
                action == ClientSlashAction.Fork ||
                action == ClientSlashAction.Terminal ||
                action == ClientSlashAction.Files
        OpenCodeActionTile(
            icon = action.tileIcon(),
            label = action.tileLabel,
            onClick = { onAction(action) },
            enabled = !requiresLive || canMutate,
            accessibilityLabel = action.description,
            badge = if (action == ClientSlashAction.Mcp) mcpBadge else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ClientSlashAction.tileIcon(): ImageVector =
    when (this) {
        ClientSlashAction.New -> OpenCodeIcons.Add
        ClientSlashAction.Terminal -> OpenCodeIcons.Terminal
        ClientSlashAction.Files -> OpenCodeIcons.Folder
        ClientSlashAction.Fork -> OpenCodeIcons.CallSplit
        ClientSlashAction.Undo -> OpenCodeIcons.Undo
        ClientSlashAction.Redo -> OpenCodeIcons.Redo
        ClientSlashAction.Compact -> OpenCodeIcons.VerticalAlignCenter
        ClientSlashAction.Mcp -> OpenCodeIcons.Plug
    }

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

