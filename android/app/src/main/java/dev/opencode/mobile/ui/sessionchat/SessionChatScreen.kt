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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.SessionToolGroup
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.connection.ExternalLinkPolicy
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogAttachment
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.feedback.FeedbackScreen
import dev.opencode.mobile.domain.message.AttachmentDropContext
import dev.opencode.mobile.domain.message.AttachmentDropRouting
import dev.opencode.mobile.domain.message.AttachmentDropSummary
import dev.opencode.mobile.domain.message.AttachmentIngestionService
import dev.opencode.mobile.domain.message.FailureAction
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.SessionTodoList
import dev.opencode.mobile.domain.session.SideQuestion
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.ComposerDictationCardModel
import dev.opencode.mobile.ui.components.LocalOpenCodeAttachmentImageLoader
import dev.opencode.mobile.ui.components.OpenCodeActionTile
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadge
import dev.opencode.mobile.ui.components.OpenCodeActionTileGrid
import dev.opencode.mobile.ui.components.OpenCodeAttachmentImageSource
import dev.opencode.mobile.ui.components.OpenCodeBottomFade
import dev.opencode.mobile.ui.components.OpenCodeBottomFadeOverhang
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeComposerPastedContent
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionItem
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionOverlay
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField
import dev.opencode.mobile.ui.components.OpenCodeDictationCardVariant
import dev.opencode.mobile.ui.components.OpenCodeDictationUnavailableReason
import dev.opencode.mobile.ui.components.OpenCodeDropOverlay
import dev.opencode.mobile.ui.components.OpenCodeDropOverlayVariant
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeLayout
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
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
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.copySessionIdToClipboard
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.components.rememberOpenCodeSessionComposerFocus
import dev.opencode.mobile.ui.feedback.FeedbackSheet
import dev.opencode.mobile.ui.feedback.rememberFeedbackSheetState
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.keyboard.KeyboardInterruptHandler
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutAction
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutHandler
import dev.opencode.mobile.ui.keyboard.rememberKeyboardInterruptArmed
import dev.opencode.mobile.ui.sessionchat.dictation.ComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.DictationModelDownloader
import dev.opencode.mobile.ui.sessionchat.dictation.DictationSupport
import dev.opencode.mobile.ui.sessionchat.dictation.SystemComposerDictationSession
import dev.opencode.mobile.ui.sessionchat.dictation.cardPhase
import dev.opencode.mobile.ui.sessionchat.dictation.dictationLanguageLabel
import dev.opencode.mobile.ui.sessionchat.dictation.formatDictationElapsed
import dev.opencode.mobile.ui.sessionchat.dictation.probeDictationSupport
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMoveCopy
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMoveTags
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMoveUiState
import dev.opencode.mobile.ui.sessionchat.view.ComposerPickerKind
import dev.opencode.mobile.ui.sessionchat.view.SessionChatChipTimelineIntroModal
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMcpServersSheetContent
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatMessageActionsTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatPendingPromptsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatPickerSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatSideQuestionSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatSkillsSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatSkillsTarget
import dev.opencode.mobile.ui.sessionchat.view.sessionChatPanelPulse
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineStylePickerSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineViewProps
import dev.opencode.mobile.ui.sessionchat.view.SessionChatBackgroundWorkSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatShellOutputSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTodosSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionRetryCountdown
import dev.opencode.mobile.ui.sessionchat.view.SessionToolGroupImages
import dev.opencode.mobile.ui.sessionchat.view.sessionFailureDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.toActionTileBadge
import dev.opencode.mobile.ui.sessionchat.view.toRoute
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.resolve
import dev.opencode.mobile.ui.worktree.NewWorktreeRow
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormActions
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormBody
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormCallbacks
import dev.opencode.mobile.ui.worktree.WorktreeCreateTags
import dev.opencode.mobile.ui.worktree.worktreeCreateBackAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A title that replaces the session title and opens a menu when tapped. The Session Chat
 * Playground names its scenario this way; the app passes none.
 */
data class SessionChatTitleMenu(
    val title: String,
    val onClick: () -> Unit,
)

/** Session Chat; gives the timeline the loader for images that live on the server. */
@Composable
fun SessionChatScreen(
    sessionId: String,
    sessionTitle: String,
    worktree: String,
    modifier: Modifier = Modifier,
    optimisticNavigationInstanceId: String? = null,
    parentId: String? = null,
    onNavigateBack: () -> Unit = {},
    /** Ctrl+N: open a new session in [worktree] the way Home's New session does; null turns Ctrl+N off. */
    onNewSessionRequested: ((worktree: String) -> Unit)? = null,
    onSessionContextClick: () -> Unit = {},
    onReviewChangesClick: (ChangesNavigationTarget) -> Unit = {},
    onChildSessionClick: (ChildSessionNavigationTarget) -> Unit = {},
    onToolGroupClick: (SessionToolGroup) -> Unit = {},
    onSessionReplaceRequested: (ChildSessionNavigationTarget) -> Unit = {},
    onTerminalRequested: (worktree: String, projectTitle: String) -> Unit = { _, _ -> },
    onFilesRequested: (worktree: String) -> Unit = {},
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    /** Replaces the "Report a problem" sheet; the Session Chat Playground sends no reports. Null opens the sheet. */
    onReportProblem: (() -> Unit)? = null,
    titleMenu: SessionChatTitleMenu? = null,
    viewModel: SessionChatViewModel =
        hiltViewModel(
            key = sessionChatViewModelKey(sessionId, worktree, optimisticNavigationInstanceId),
        ),
) {
    val attachmentImageLoader =
        remember(viewModel) {
            val loader: suspend (OpenCodeAttachmentImageSource.ServerPath) -> ByteArray? =
                { source -> viewModel.loadAttachmentPreview(source.path) }
            loader
        }
    CompositionLocalProvider(LocalOpenCodeAttachmentImageLoader provides attachmentImageLoader) {
        SessionChatScreenBody(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            worktree = worktree,
            modifier = modifier,
            optimisticNavigationInstanceId = optimisticNavigationInstanceId,
            parentId = parentId,
            onNavigateBack = onNavigateBack,
            onNewSessionRequested = onNewSessionRequested,
            onSessionContextClick = onSessionContextClick,
            onReviewChangesClick = onReviewChangesClick,
            onChildSessionClick = onChildSessionClick,
            onToolGroupClick = onToolGroupClick,
            onSessionReplaceRequested = onSessionReplaceRequested,
            onTerminalRequested = onTerminalRequested,
            onFilesRequested = onFilesRequested,
            detailControl = detailControl,
            onReportProblem = onReportProblem,
            titleMenu = titleMenu,
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionChatScreenBody(
    sessionId: String,
    sessionTitle: String,
    worktree: String,
    modifier: Modifier,
    optimisticNavigationInstanceId: String?,
    parentId: String?,
    onNavigateBack: () -> Unit,
    onNewSessionRequested: ((worktree: String) -> Unit)?,
    onSessionContextClick: () -> Unit,
    onReviewChangesClick: (ChangesNavigationTarget) -> Unit,
    onChildSessionClick: (ChildSessionNavigationTarget) -> Unit,
    onToolGroupClick: (SessionToolGroup) -> Unit,
    onSessionReplaceRequested: (ChildSessionNavigationTarget) -> Unit,
    onTerminalRequested: (worktree: String, projectTitle: String) -> Unit,
    onFilesRequested: (worktree: String) -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl?,
    onReportProblem: (() -> Unit)?,
    titleMenu: SessionChatTitleMenu?,
    viewModel: SessionChatViewModel,
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
            viewModel.leaveSideQuestions()
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // After `session.moved` the route still names the old directory; actions follow the session.
    val currentWorktree = uiState.worktree.ifEmpty { worktree }
    val attachmentSources = uiState.composer.attachmentSources(DeveloperToolsPreferences.unlocked)
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val feedbackSheet = rememberFeedbackSheetState()
    var activePicker by remember { mutableStateOf<ComposerPickerKind?>(null) }
    var activeModelProviderId by remember { mutableStateOf<String?>(null) }
    var modelFilterText by remember { mutableStateOf("") }
    var messageActionsTarget by remember { mutableStateOf<SessionChatMessageActionsTarget?>(null) }
    var showPendingPromptsSheet by rememberSaveable { mutableStateOf(false) }
    var toolDetailTarget by remember { mutableStateOf<SessionChatToolDetailTarget?>(null) }
    var skillsTarget by remember { mutableStateOf<SessionChatSkillsTarget?>(null) }
    var showSessionTodosSheet by rememberSaveable { mutableStateOf(false) }
    var showSubagentsSheet by rememberSaveable { mutableStateOf(false) }
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
    // A shown snackbar floats above the composer; lift the end-pinned last row
    // over it so the row keeps the same end gap instead of sitting under the card.
    var snackbarOverlayHeight by remember { mutableStateOf(0.dp) }
    val animatedSnackbarOverlayHeight by animateDpAsState(
        targetValue = snackbarOverlayHeight,
        animationSpec = tween(durationMillis = 200),
        label = SNACKBAR_INSET_ANIMATION_LABEL,
    )
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
        val uploadAvailable = viewModel.attachmentUploadAvailable()
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { attachmentIngestion.ingest(uris, uploadAvailable) }
            viewModel.addAttachments(result.attachments)
            result.errors.firstOrNull()?.let(viewModel::reportAttachmentError)
        }
    }

    fun attachPasted(content: OpenCodeComposerPastedContent) {
        val uploadAvailable = viewModel.attachmentUploadAvailable()
        val names =
            viewModel.uiState.value.composer.attachments
                .map { it.filename }
                .toMutableList()
        coroutineScope.launch {
            val (folders, result) =
                try {
                    withContext(Dispatchers.IO) {
                        val (folders, uris) = content.uris.partition { context.contentResolver.isFolder(it) }
                        folders to
                            attachmentIngestion.ingest(uris, uploadAvailable) { mime ->
                                // An unnamed pasted image is `Pasted image N.png`; other content keeps its URI name.
                                if (mime?.startsWith("image/") != true) return@ingest null
                                AttachmentDropRouting.pastedImageName(names).also { names += it }
                            }
                    }
                } finally {
                    content.release()
                }
            if (viewModel.addPastedOrDroppedAttachments(result, skippedFolders = folders.size)) {
                logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_PASTE)
            }
        }
    }

    fun attachDropped(files: SessionChatDroppedFiles) {
        val uploadAvailable = viewModel.attachmentUploadAvailable()
        coroutineScope.launch {
            val (folders, result) =
                try {
                    withContext(Dispatchers.IO) {
                        val (folders, uris) = files.uris.partition { context.contentResolver.isFolder(it) }
                        folders to attachmentIngestion.ingest(uris, uploadAvailable)
                    }
                } finally {
                    files.release()
                }
            if (viewModel.addPastedOrDroppedAttachments(result, skippedFolders = folders.size)) {
                logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_DROP)
            }
        }
    }

    var attachDropEnabled by remember { mutableStateOf(false) }
    var attachDropSummary by remember { mutableStateOf<AttachmentDropSummary?>(null) }
    LaunchedEffect(attachDropEnabled) {
        if (!attachDropEnabled) attachDropSummary = null
    }

    fun attachmentDropContext(): AttachmentDropContext {
        val composer = viewModel.uiState.value.composer
        val option = composer.selectedModel?.option
        return AttachmentDropContext(
            imagesSupported = option?.supportsImageInput != false,
            pdfSupported = option?.supportsPdfInput != false,
            uploadAvailable = viewModel.attachmentUploadAvailable(),
            draft = composer.attachments,
        )
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
    val dictationModelDownloader =
        remember(context, coroutineScope) { DictationModelDownloader.system(context, coroutineScope) }
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
            languageTag = support.languageTag,
        )
    }

    fun showDictationModelCard(downloading: Boolean) {
        viewModel.beginDictationUnavailable(
            if (downloading) ComposerDictationPhase.ModelDownloading else ComposerDictationPhase.ModelNotInstalled,
            languageLabel = dictationLanguageLabel(),
        )
    }

    // Assigned below: an installed model re-enters applyProbedDictation, which can start a watch.
    lateinit var startDictationModelDownload: (Boolean) -> Unit

    fun applyProbedDictation(
        support: DictationSupport,
        startIfReady: Boolean,
    ) {
        dictationSupport = support
        if (!startIfReady) return
        when (support) {
            is DictationSupport.Ready -> startReadyDictation(support)
            is DictationSupport.ModelNotInstalled -> showDictationModelCard(downloading = false)
            is DictationSupport.ModelDownloading -> {
                showDictationModelCard(downloading = true)
                startDictationModelDownload(true)
            }
            DictationSupport.PermissionDenied ->
                viewModel.beginDictationUnavailable(ComposerDictationPhase.PermissionDenied)
            DictationSupport.Hidden -> viewModel.dictationCancel()
        }
    }

    startDictationModelDownload = { alreadyPending ->
        dictationModelDownloader.start(dictationSupport.languageTag, alreadyPending) { update ->
            val card = update.cardPhase()
            if (card != null) {
                viewModel.dictationModelDownload(card.first, card.second)
            } else if (viewModel.uiState.value.dictation
                    ?.phase
                    ?.isModelPhase == true
            ) {
                coroutineScope.launch {
                    applyProbedDictation(probeDictationSupport(context), startIfReady = true)
                }
            }
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
            is DictationSupport.ModelNotInstalled -> showDictationModelCard(downloading = false)
            is DictationSupport.ModelDownloading -> {
                showDictationModelCard(downloading = true)
                startDictationModelDownload(true)
            }
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
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { dictationModelDownloader.setHostPaused(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dictationModelDownloader.setHostPaused(false) }
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
    val isChildSession = parentId != null || uiState.sessionParentId != null
    val composerKindForDictation =
        SessionChatComposerKind.resolve(
            isChildSession = isChildSession,
            hasPermission = uiState.permissionRequest != null && !uiState.permissionDockHidden,
            hasQuestion = uiState.questionRequest != null && !uiState.questionDockHidden,
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

    FeedbackSheet(
        sheetState = feedbackSheet,
        screen = FeedbackScreen.SessionChat,
        captureScreen = true,
    )

    OpenCodeDialog(
        visible = showDeleteConfirmation,
        title = stringResource(R.string.session_chat_delete_dialog_title),
        subtitle = stringResource(R.string.session_chat_delete_dialog_message, uiState.sessionTitle),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.session_chat_delete_dialog_confirm),
                enabled = lifecycleActionsEnabled,
                onClick = {
                    showDeleteConfirmation = false
                    viewModel.deleteSession()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
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

    SessionChatMoveToWorktreeSheet(
        state = uiState.moveToWorktree,
        onDismiss = viewModel::dismissMoveToWorktree,
        onMove = viewModel::moveToWorktree,
        onNewWorktree = viewModel::openMoveToNewWorktree,
        formCallbacks =
            WorktreeCreateFormCallbacks(
                onNameChange = viewModel::updateNewWorktreeName,
                onShowBasePicker = viewModel::showNewWorktreeBasePicker,
                onHideBasePicker = viewModel::hideNewWorktreeBasePicker,
                onSelectBase = viewModel::selectNewWorktreeBase,
                onSubmit = viewModel::submitNewWorktree,
                onBack = viewModel::closeNewWorktreeForm,
            ),
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .sessionChatAttachmentDropTarget(
                    enabled = attachDropEnabled,
                    dropContext = ::attachmentDropContext,
                    onSummaryChange = { attachDropSummary = it },
                    onDrop = ::attachDropped,
                ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBarOverflowHost(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                entries =
                    buildList {
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = stringResource(R.string.session_chat_menu_rename),
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
                                title = stringResource(R.string.session_chat_menu_review_changes),
                                icon = OpenCodeIcons.Description,
                                enabled = sessionCreated,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_REVIEW_CHANGES)
                                    menuExpanded = false
                                    onReviewChangesClick(uiState.changesNavigationTarget(currentWorktree))
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = stringResource(R.string.session_chat_menu_fork),
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
                                title = SessionChatWorktreeMoveCopy.MENU_TITLE,
                                icon = OpenCodeIcons.CallSplit,
                                enabled = uiState.moveToWorktree.availability.enabled,
                                subtitle = uiState.moveToWorktree.availability.subtitle,
                                testTag = SessionChatWorktreeMoveTags.MENU_ENTRY,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openMoveToWorktree()
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = stringResource(R.string.session_chat_menu_browse_files),
                                icon = OpenCodeIcons.Folder,
                                enabled = sessionCreated,
                                onClick = {
                                    logOverflowAction(ProductAnalytics.Action.CHAT_OVERFLOW_BROWSE_FILES)
                                    menuExpanded = false
                                    onFilesRequested(currentWorktree)
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = stringResource(R.string.session_chat_menu_session_context),
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
                                title = stringResource(R.string.session_chat_menu_copy_session_id),
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
                                    title = stringResource(R.string.session_chat_menu_archive),
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
                                title = stringResource(R.string.session_chat_menu_report_problem),
                                icon = OpenCodeIcons.Edit,
                                onClick = {
                                    menuExpanded = false
                                    if (onReportProblem != null) onReportProblem() else feedbackSheet.open()
                                },
                            ),
                        )
                        add(
                            OpenCodeOverflowMenuEntry(
                                title = stringResource(R.string.session_chat_menu_delete),
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
                    title = titleMenu?.title ?: uiState.sessionTitle.ifBlank { sessionTitle },
                    onTitleClick = titleMenu?.onClick,
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
                                contentDescription = stringResource(R.string.session_chat_more_options),
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
            uiState.archivedAtEpochMs?.let { archivedAt ->
                OpenCodeInlineNotice(
                    message = stringResource(R.string.session_chat_archived_banner, RelativeTimeFormatting.format(archivedAt).resolve()),
                    variant = OpenCodeInlineNoticeVariant.Banner,
                    action =
                        if (uiState.supportsArchivedSessions) {
                            OpenCodeInlineNoticeAction(
                                title = stringResource(R.string.archived_sessions_unarchive),
                                onClick = viewModel::unarchiveSession,
                            )
                        } else {
                            null
                        },
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = OpenCodeLayout.ContentMaxWidth)
                            .padding(horizontal = 14.dp)
                            .padding(top = 8.dp, bottom = 8.dp)
                            .testTag(SessionChatArchivedBannerTag),
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

            val modelOptions = uiState.composer.modelOptions
            val noticeModelNames = remember(modelOptions) { SessionChatNoticeModelNames.from(modelOptions) }
            // The keyboard above the navigation bar lifts the whole chat area. Inset padding updates
            // in layout only, so a keyboard animation never recomposes the screen. It also consumes
            // that part of the inset: the composer below keeps a constant measured height instead of
            // writing a new one into composerOverlayHeightPx on every frame.
            OpenCodeReadableColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
            ) {
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
                            hideFloatStack = showSessionTodosSheet || showSubagentsSheet || uiState.shellOutput != null,
                            hideLiveRetryFailures = uiState.retryCard != null,
                            hideRetryCountdown = uiState.isRetrying,
                            noticeModelNames = noticeModelNames,
                            // Composer (+ shown snackbar) + 16 end-pin gap; Scroll to end
                            // uses this as-is (no extra scrollToEndPillBottomPadding).
                            bottomOverlayInset = timelineBottomInset + animatedSnackbarOverlayHeight,
                            activityItems =
                                sessionChatActivityItems(
                                    uiState = uiState,
                                    composerKind =
                                        SessionChatComposerKind.resolve(
                                            isChildSession = isChildSession,
                                            hasPermission = uiState.permissionRequest != null && !uiState.permissionDockHidden,
                                            hasQuestion = uiState.questionRequest != null && !uiState.questionDockHidden,
                                        ),
                                ),
                            keyboardVisible = WindowInsets.isImeVisible,
                            onActivityClick = { item ->
                                when (item) {
                                    SessionChatActivityItem.ReviewPermission -> viewModel.showPermissionDock()
                                    SessionChatActivityItem.AnswerQuestion -> viewModel.showQuestionDock()
                                    is SessionChatActivityItem.Subagents -> {
                                        if (!item.pill.needsInput || !viewModel.openWaitingSubagentRequest()) {
                                            logChatAction(ProductAnalytics.Action.CHAT_OPEN_SUBAGENTS)
                                            showSubagentsSheet = true
                                        }
                                    }
                                    is SessionChatActivityItem.SideQuestionsFailed -> viewModel.retrySideQuestions()
                                    is SessionChatActivityItem.SideQuestionsReady -> viewModel.openSideQuestionAnswer()
                                    is SessionChatActivityItem.Queued -> showPendingPromptsSheet = true
                                    else -> Unit
                                }
                            },
                            onActivityClose = { item ->
                                if (item is SessionChatActivityItem.SideQuestionsFailed) {
                                    viewModel.dismissFailedSideQuestions()
                                }
                            },
                            onRefresh = viewModel::refreshMessages,
                            onRetry = viewModel::retryFailedTimeline,
                            onLoadOlder = viewModel::loadOlderMessages,
                            onSessionBranchClick = {
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHANGES)
                                onReviewChangesClick(uiState.changesNavigationTarget(currentWorktree))
                            },
                            onSessionBranchCopied = viewModel::branchNameCopied,
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
                                SessionToolGroupImages.put(sessionId, target)
                                onToolGroupClick(
                                    target.toRoute(
                                        sessionId = sessionId,
                                        worktree = currentWorktree,
                                        parentId = parentId ?: uiState.sessionParentId,
                                        owningTurnMessageId = uiState.owningTurnMessageId(target.messageId),
                                        supportsSessionMessageDiff = uiState.supportsSessionMessageDiff,
                                        lastTurnDiffUnavailable =
                                            uiState.owningTurnMessageId(target.messageId)
                                                ?.let(uiState::lastTurnSpansLocationChange) == true,
                                    ),
                                )
                            },
                            onChildSessionTap = { childSessionId, childTitle ->
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHILD_SESSION)
                                onChildSessionClick(
                                    ChildSessionNavigationTarget(
                                        sessionId = childSessionId,
                                        sessionTitle = childTitle,
                                        worktree = currentWorktree,
                                    ),
                                )
                            },
                            onChangesReviewTap = changesReview@{ assistantMessageId, initialFilePath ->
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHANGES_FROM_TOOL)
                                val target =
                                    uiState.fileChangeNavigationTarget(
                                        worktree = currentWorktree,
                                        assistantMessageId = assistantMessageId,
                                        initialFilePath = initialFilePath,
                                    ) ?: return@changesReview
                                if (target.messageId?.let(uiState::lastTurnSpansLocationChange) == true) {
                                    viewModel.presentTurnDiffUnavailable()
                                    return@changesReview
                                }
                                onReviewChangesClick(target)
                            },
                            supportsSessionMessageDiff = uiState.supportsSessionMessageDiff,
                            expandedStepParagraphs = uiState.expandedStepParagraphs,
                            onExpandStepParagraph = viewModel::expandStepParagraph,
                            unfoldedStepRails = uiState.unfoldedStepRails,
                            onToggleStepRailFold = viewModel::toggleStepRailFold,
                            presentedActivityCard = uiState.presentedActivityCard,
                            activityCardChildren = uiState.activityCardChildren,
                            activityCardFooters = uiState.activityCardFooters,
                            debouncedStepIds = uiState.debouncedStepIds,
                            onOpenActivityCard = { blockId ->
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_TOOL_DETAIL)
                                viewModel.openActivityCard(blockId)
                            },
                            onDismissActivityCard = viewModel::dismissActivityCard,
                            activityCardDetailStepId = uiState.activityCardDetailStepId,
                            onOpenActivityCardStep = { stepId ->
                                logChatAction(ProductAnalytics.Action.CHAT_OPEN_TOOL_DETAIL)
                                viewModel.openActivityCardStep(stepId)
                            },
                            onCloseActivityCardStep = viewModel::closeActivityCardStep,
                            imageViewer = uiState.imageViewer,
                            onOpenImageViewer = viewModel::openImageViewer,
                            onImageViewerPageChange = viewModel::showImageViewerPage,
                            onDismissImageViewer = viewModel::dismissImageViewer,
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
                    onAction = { message ->
                        val snackbar = uiState.snackbar?.takeIf { it.id == message.id }
                        when (val action = snackbar?.action) {
                            is SessionChatSnackbarAction.RetrySideQuestion -> viewModel.retrySideQuestions(action.id)
                            null -> Unit
                        }
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = composerOverlayHeight),
                    onHeightChange = { snackbarOverlayHeight = it },
                )

                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                ) {
                    val overlayComposerKind =
                        SessionChatComposerKind.resolve(
                            isChildSession = isChildSession,
                            hasPermission = uiState.permissionRequest != null && !uiState.permissionDockHidden,
                            hasQuestion = uiState.questionRequest != null && !uiState.questionDockHidden,
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
                                                subtitle =
                                                    suggestion.subtitle
                                                        ?: if (suggestion.filePath != null) stringResource(R.string.session_chat_suggestion_file) else null,
                                                leadingIcon = suggestion.leadingIcon,
                                            )
                                        },
                                    onSelect = { item ->
                                        viewModel.selectAtSuggestion(item.id)
                                    },
                                    emptyMessage = stringResource(R.string.session_chat_suggestion_no_matches),
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
                                                badge = suggestion.badge?.resolve(),
                                            )
                                        },
                                    onSelect = { item ->
                                        viewModel.selectSlashSuggestion(item.id)
                                    },
                                    emptyMessage = stringResource(R.string.session_chat_suggestion_no_commands),
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
                        // Suggestions float above this column. Corner pills float over the
                        // timeline, which reserves their row (SessionChatCornerSlotsLayout).
                        if (overlayComposerKind == SessionChatComposerKind.Prompt) {
                            uiState.sideQuestions.unsupportedNotice?.let { notice ->
                                OpenCodeInlineNotice(
                                    message = notice,
                                    variant = OpenCodeInlineNoticeVariant.Banner,
                                    modifier =
                                        Modifier
                                            .padding(horizontal = 12.dp)
                                            .padding(bottom = 6.dp),
                                )
                            }
                        }

                        val sideQuestionMode = uiState.sideQuestions.composerMode
                        val blockedSideQuestion =
                            !uiState.sideQuestions.supported &&
                                SideQuestion.questionInDraft(uiState.composer.text) != null
                        val composerKind =
                            SessionChatComposerKind.resolve(
                                isChildSession = isChildSession,
                                hasPermission = uiState.permissionRequest != null && !uiState.permissionDockHidden,
                                hasQuestion = uiState.questionRequest != null && !uiState.questionDockHidden,
                            )
                        // This session's sent prompts, oldest first, for ↑ / ↓ recall in the composer.
                        val promptHistory =
                            remember(uiState.messages) {
                                uiState.messages
                                    .filter { it.role == MessageRole.User && !it.isPending && it.text.isNotBlank() }
                                    .map { it.text }
                            }
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
                                        queuePosition = uiState.permissionQueuePosition,
                                        queueCount = uiState.permissionQueueCount,
                                        attribution =
                                            uiState.permissionAttribution?.let { attribution ->
                                                attribution.toComposerAttribution(
                                                    attribution.permissionSummary(
                                                        permission = request?.permission.orEmpty(),
                                                        target =
                                                            request?.fileChanges?.firstOrNull()?.path
                                                                ?: request?.patterns?.firstOrNull(),
                                                    ),
                                                )
                                            },
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
                                        requestPosition = uiState.questionQueuePosition,
                                        requestCount = uiState.questionQueueCount,
                                        attribution =
                                            uiState.questionAttribution?.let {
                                                it.toComposerAttribution(it.questionSummary)
                                            },
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
                                            (
                                                if (sideQuestionMode) {
                                                    // A side question answers with the session's own model.
                                                    uiState.composer.text.isNotBlank()
                                                } else {
                                                    uiState.composer.canSend &&
                                                        uiState.composer.selectedModel != null &&
                                                        uiState.composer.isModelSelectionReady &&
                                                        !uiState.isSending &&
                                                        !blockedSideQuestion
                                                }
                                            ) &&
                                                sessionCreated &&
                                                uiState.timeline != SessionChatTimelineState.Creating &&
                                                uiState.composerEnabled,
                                        attachEnabled =
                                            attachmentSources.isAttachEnabled &&
                                                sessionCreated &&
                                                uiState.composer.isModelSelectionReady &&
                                                !uiState.composer.isShellMode &&
                                                !sideQuestionMode,
                                        showStop = uiState.sessionStatusLabel != null,
                                        stopEnabled =
                                            !uiState.isAborting &&
                                                uiState.canMutate,
                                        followUpDelivery =
                                            uiState.composer.followUpDelivery.takeIf {
                                                uiState.composer.supportsFollowUpDelivery && !sideQuestionMode
                                            },
                                        showDictationButton = dictationSupport.showsMic,
                                        shellMode = uiState.composer.isShellMode && !sideQuestionMode,
                                        sideQuestionMode = sideQuestionMode,
                                        suggestionsOpen = uiState.atSuggestionsVisible || uiState.slashSuggestionsVisible,
                                        promptHistory = promptHistory,
                                    )
                            }
                        // Drops attach only where the Attach button would: a prompt composer that takes attachments.
                        val promptAttachEnabled =
                            (composerState as? OpenCodeSessionComposerState.Prompt)?.attachEnabled == true &&
                                uiState.dictation == null
                        SideEffect { attachDropEnabled = promptAttachEnabled }
                        val composerFocus = rememberOpenCodeSessionComposerFocus()
                        KeyboardShortcutHandler(
                            KeyboardShortcutAction.FocusComposer,
                            enabled = composerKind == SessionChatComposerKind.Prompt,
                        ) {
                            composerFocus.requestFocus()
                        }
                        // Esc twice interrupts a busy turn, through the same abort as Stop.
                        KeyboardInterruptHandler(
                            enabled = uiState.sessionStatusLabel != null && !uiState.isAborting && uiState.canMutate,
                        ) {
                            viewModel.abort()
                        }
                        val interruptArmed = rememberKeyboardInterruptArmed()
                        val openModelPicker: () -> Unit = {
                            if (uiState.composer.modelOptions.isEmpty()) {
                                viewModel.retryModelCatalog()
                            } else {
                                logChatAction(ProductAnalytics.Action.COMPOSER_OPEN_MODEL_PICKER)
                                activeModelProviderId =
                                    uiState.composer.selectedModel
                                        ?.option
                                        ?.providerId
                                activePicker = ComposerPickerKind.Model
                            }
                        }
                        val promptComposerShown = composerKind == SessionChatComposerKind.Prompt && uiState.dictation == null
                        // Ctrl+N: a new session in this chat's project (a subagent's too), opened like
                        // Home's New session. Home stands down while this chat shows beside it.
                        KeyboardShortcutHandler(
                            KeyboardShortcutAction.NewSession,
                            enabled = onNewSessionRequested != null && uiState.canMutate && currentWorktree.isNotEmpty(),
                        ) {
                            onNewSessionRequested?.invoke(currentWorktree)
                        }
                        KeyboardShortcutHandler(KeyboardShortcutAction.CommandPalette, enabled = promptComposerShown) {
                            viewModel.openCommands()
                        }
                        KeyboardShortcutHandler(KeyboardShortcutAction.ModelPicker, enabled = promptComposerShown) {
                            openModelPicker()
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
                            modifier = Modifier.sessionChatPanelPulse(uiState.inboxPanelPulse),
                            focus = composerFocus,
                            dictation = dictationCard,
                            interruptArmed = interruptArmed,
                            onAction = { action ->
                                when (action) {
                                    is OpenCodeSessionComposerAction.TextChanged ->
                                        viewModel.updateComposerText(action.text, action.selectionStart)
                                    OpenCodeSessionComposerAction.SelectModel -> openModelPicker()
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
                                            attachmentSources.photos -> photoPicker.launch(attachmentSources.galleryRequest())
                                            attachmentSources.files -> documentPicker.launch(arrayOf("*/*"))
                                        }
                                    }
                                    is OpenCodeSessionComposerAction.RemoveAttachment -> viewModel.removeAttachment(action.id)
                                    is OpenCodeSessionComposerAction.PasteContent -> attachPasted(action.content)
                                    OpenCodeSessionComposerAction.CancelRevert -> viewModel.cancelRevert()
                                    OpenCodeSessionComposerAction.ExitShellMode -> viewModel.exitShellMode()
                                    OpenCodeSessionComposerAction.ExitSideQuestionMode -> viewModel.exitSideQuestionMode()
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
                                    is OpenCodeSessionComposerAction.HideQuestion ->
                                        viewModel.hideQuestionDock(action.requestId)
                                    is OpenCodeSessionComposerAction.PageRequest ->
                                        if (composerKind == SessionChatComposerKind.Permission) {
                                            viewModel.pagePermission(action.delta)
                                        } else {
                                            viewModel.pageQuestion(action.delta)
                                        }
                                    is OpenCodeSessionComposerAction.OpenRequestSession -> {
                                        val attribution =
                                            listOfNotNull(uiState.permissionAttribution, uiState.questionAttribution)
                                                .firstOrNull { it.sessionId == action.sessionId }
                                        if (attribution != null) {
                                            logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHILD_SESSION)
                                            onChildSessionClick(
                                                ChildSessionNavigationTarget(
                                                    sessionId = attribution.sessionId,
                                                    sessionTitle = attribution.sessionTitle,
                                                    worktree = currentWorktree,
                                                ),
                                            )
                                        }
                                    }
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
                                        startDictationModelDownload(false)
                                    OpenCodeSessionComposerAction.DictationRetry -> {
                                        coroutineScope.launch {
                                            val support = probeDictationSupport(context)
                                            dictationSupport = support
                                            when (support) {
                                                is DictationSupport.Ready -> requestDictationOrStart()
                                                is DictationSupport.ModelNotInstalled ->
                                                    showDictationModelCard(downloading = false)
                                                is DictationSupport.ModelDownloading -> {
                                                    showDictationModelCard(downloading = true)
                                                    startDictationModelDownload(true)
                                                }
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
                                                            text = UiText.verbatim(card.message),
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

        SessionChatSideQuestionSheet(
            answer = uiState.sideQuestions.presentedAnswer,
            onCopy = viewModel::sideQuestionAnswerCopied,
            onClose = viewModel::closeSideQuestionAnswer,
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

        // Waits for any screen-local sheet to close so the intro and the Timeline Style
        // picker never stack on one.
        val screenSheetOpen =
            messageActionsTarget != null ||
                showPendingPromptsSheet ||
                toolDetailTarget != null ||
                skillsTarget != null ||
                showSessionTodosSheet ||
                showSubagentsSheet ||
                uiState.shellOutput != null ||
                showAttachmentSource ||
                activePicker != null ||
                showDeleteConfirmation
        LaunchedEffect(screenSheetOpen) { viewModel.setScreenSheetOpen(screenSheetOpen) }
        SessionChatChipTimelineIntroModal(
            visible = uiState.chipTimelineIntroVisible && !screenSheetOpen,
            onEnable = viewModel::enableChipTimelineFromIntro,
            onLater = viewModel::postponeChipTimelineIntro,
            onNever = viewModel::dismissChipTimelineIntro,
        )
        SessionChatTimelineStylePickerSheet(
            visible = uiState.timelineStylePickerVisible && !screenSheetOpen,
            options = uiState.timelineStylePickerOptions,
            onUse = viewModel::chooseTimelineStyleFromPicker,
            onDismiss = viewModel::dismissTimelineStylePicker,
        )

        SessionChatBackgroundWorkSheet(
            visible = showSubagentsSheet,
            subagents = uiState.subagents,
            shells = uiState.shells,
            onOpenShell = { shell -> viewModel.openShellOutput(shell.id) },
            onOpenSubagent = { subagent ->
                val childId = subagent.childSessionId ?: return@SessionChatBackgroundWorkSheet
                logChatAction(ProductAnalytics.Action.CHAT_OPEN_CHILD_SESSION)
                onChildSessionClick(
                    ChildSessionNavigationTarget(
                        sessionId = childId,
                        sessionTitle = subagent.title ?: subagent.agentName,
                        worktree = currentWorktree,
                    ),
                )
            },
            onDismiss = { showSubagentsSheet = false },
            doneUnseen = uiState.backgroundWorkDoneUnseen,
            onDoneVisibleChange = viewModel::setBackgroundWorkDoneVisible,
        )

        SessionChatShellOutputSheet(
            sheet = uiState.shellOutput,
            onCopy = viewModel::copyShellOutput,
            onStop = viewModel::stopShell,
            onObservedChange = viewModel::setShellOutputObserved,
            onDismiss = viewModel::closeShellOutput,
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
            title = stringResource(R.string.session_chat_attachment_source_title),
            onDismissRequest = {
                logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_SOURCE_DISMISS)
                showAttachmentSource = false
            },
        ) {
            if (attachmentSources.photos) {
                OpenCodeBottomSheetEntry(
                    title =
                        stringResource(
                            if (attachmentSources.acceptsAnyType) {
                                R.string.session_chat_attachment_source_media
                            } else {
                                R.string.session_chat_attachment_source_photos
                            },
                        ),
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Description),
                    onClick = {
                        logAttachmentSourceAction(ProductAnalytics.Action.ATTACH_PICK_PHOTOS)
                        showAttachmentSource = false
                        photoPicker.launch(attachmentSources.galleryRequest())
                    },
                )
            }
            if (attachmentSources.files) {
                OpenCodeBottomSheetEntry(
                    title = stringResource(R.string.session_chat_attachment_source_files),
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
                    title = stringResource(R.string.session_chat_attachment_source_log_file),
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
            title = stringResource(R.string.session_chat_rename_dialog_title),
            primaryOption =
                OpenCodeDialogOption(
                    title = stringResource(R.string.session_chat_rename_dialog_save),
                    enabled =
                        !uiState.rename.isSubmitting &&
                            uiState.canMutate,
                    onClick = viewModel::confirmRenameSession,
                ),
            secondaryOption =
                OpenCodeDialogOption(
                    title = stringResource(R.string.common_cancel),
                    enabled = !uiState.rename.isSubmitting,
                    onClick = viewModel::cancelRenameSession,
                ),
            textField =
                OpenCodeDialogTextField(
                    value = uiState.rename.draftTitle,
                    onValueChange = viewModel::updateRenameDraft,
                    hint = stringResource(R.string.session_chat_title_hint),
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
            title =
                stringResource(
                    if (uiState.mcpPageVisible) R.string.session_chat_commands_mcp_title else R.string.session_chat_commands_title,
                ),
            onDismissRequest = viewModel::dismissCommands,
            headerLeadingAction =
                if (uiState.mcpPageVisible) {
                    OpenCodeBottomSheetHeaderAction(
                        imageVector = OpenCodeIcons.ArrowBack,
                        contentDescription = stringResource(R.string.session_chat_commands_back),
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
            title = stringResource(R.string.session_chat_fork_dialog_title),
            primaryOption =
                OpenCodeDialogOption(
                    title = stringResource(R.string.session_chat_fork_dialog_confirm),
                    enabled =
                        !uiState.forkSubmitting &&
                            uiState.canMutate,
                    onClick = viewModel::confirmFork,
                ),
            secondaryOption =
                OpenCodeDialogOption(
                    title = stringResource(R.string.common_cancel),
                    enabled = !uiState.forkSubmitting,
                    onClick = viewModel::cancelForkDialog,
                ),
            textField =
                OpenCodeDialogTextField(
                    value = uiState.forkDraftTitle,
                    onValueChange = viewModel::updateForkDraft,
                    hint = stringResource(R.string.session_chat_title_hint),
                    enabled = !uiState.forkSubmitting,
                    errorMessage = uiState.forkErrorMessage?.resolve(),
                ),
            onDismissRequest = {
                if (!uiState.forkSubmitting) {
                    viewModel.cancelForkDialog()
                }
            },
        )

        OpenCodeDialog(
            visible = uiState.forkResultVisible,
            title = stringResource(R.string.session_chat_fork_result_title),
            subtitle = stringResource(R.string.session_chat_fork_result_message_ready, uiState.forkedSessionTitle),
            primaryOption =
                OpenCodeDialogOption(
                    title = stringResource(R.string.session_chat_fork_result_open),
                    onClick = viewModel::chooseForkOpen,
                ),
            secondaryOption =
                OpenCodeDialogOption(
                    title = stringResource(R.string.session_chat_fork_result_stay_here),
                    onClick = viewModel::chooseForkStay,
                ),
            onDismissRequest = viewModel::chooseForkStay,
        )

        AnimatedVisibility(
            visible = attachDropSummary != null,
            enter = fadeIn(tween(durationMillis = 120)),
            exit = fadeOut(tween(durationMillis = 120)),
        ) {
            // Keeps the last summary while fading out.
            val summary = remember { mutableStateOf<AttachmentDropSummary?>(null) }
            attachDropSummary?.let { summary.value = it }
            summary.value?.let { shown ->
                OpenCodeDropOverlay(
                    variant = if (shown.rejected) OpenCodeDropOverlayVariant.Reject else OpenCodeDropOverlayVariant.Accept,
                    title = shown.title,
                    subtitle = shown.subtitle,
                    previews = shown.previews.map { it.toAttachmentVariant() },
                )
            }
        }
    }
}

private fun openSessionExternalLink(
    context: android.content.Context,
    link: String,
) {
    // Server-supplied links open as web pages only (http/https), like markdown links.
    if (!ExternalLinkPolicy.isOpenable(link)) return
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

/** Animation label for tooling; not shown to users. */
private const val SNACKBAR_INSET_ANIMATION_LABEL = "sessionChatSnackbarInset"

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
            ComposerDictationPhase.ModelDownloading ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelDownloading
            ComposerDictationPhase.ModelDownloadQueued ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelDownloadQueued
            ComposerDictationPhase.ModelDownloadFailed ->
                OpenCodeDictationCardVariant.Unavailable to OpenCodeDictationUnavailableReason.ModelDownloadFailed
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
        downloadProgress = downloadProgress,
    )
}

@Composable
private fun SessionChatSnackbar.toOpenCodeSnackbarMessage(): OpenCodeSnackbarMessage =
    OpenCodeSnackbarMessage(
        text = message.resolve(),
        variant =
            when (tone) {
                SessionChatSnackbarTone.Default -> OpenCodeSnackbarVariant.Default
                SessionChatSnackbarTone.Error -> OpenCodeSnackbarVariant.Error
            },
        actionLabel = action?.label,
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
            label = stringResource(action.tileLabelRes()),
            onClick = { onAction(action) },
            enabled = !requiresLive || canMutate,
            accessibilityLabel = stringResource(action.descriptionRes()),
            badge = if (action == ClientSlashAction.Mcp) mcpBadge else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ClientSlashAction.tileLabelRes(): Int =
    when (this) {
        ClientSlashAction.New -> R.string.session_chat_commands_new_label
        ClientSlashAction.Fork -> R.string.session_chat_commands_fork_label
        ClientSlashAction.Undo -> R.string.session_chat_commands_undo_label
        ClientSlashAction.Redo -> R.string.session_chat_commands_redo_label
        ClientSlashAction.Compact -> R.string.session_chat_commands_compact_label
        ClientSlashAction.Terminal -> R.string.session_chat_commands_terminal_label
        ClientSlashAction.Files -> R.string.session_chat_commands_files_label
        ClientSlashAction.Mcp -> R.string.session_chat_commands_mcp_label
        ClientSlashAction.Btw -> R.string.session_chat_commands_btw_label
    }

private fun ClientSlashAction.descriptionRes(): Int =
    when (this) {
        ClientSlashAction.New -> R.string.session_chat_commands_new_description
        ClientSlashAction.Fork -> R.string.session_chat_commands_fork_description
        ClientSlashAction.Undo -> R.string.session_chat_commands_undo_description
        ClientSlashAction.Redo -> R.string.session_chat_commands_redo_description
        ClientSlashAction.Compact -> R.string.session_chat_commands_compact_description
        ClientSlashAction.Terminal -> R.string.session_chat_commands_terminal_description
        ClientSlashAction.Files -> R.string.session_chat_commands_files_description
        ClientSlashAction.Mcp -> R.string.session_chat_commands_mcp_description
        ClientSlashAction.Btw -> R.string.session_chat_commands_btw_description
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
        ClientSlashAction.Btw -> OpenCodeIcons.SubdirectoryArrowRight
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

/**
 * Session Chat ⋯ → "Move to worktree…": the project's worktrees, the current one checked, then
 * "New worktree…", whose form replaces the list in the same sheet.
 */
@Composable
private fun SessionChatMoveToWorktreeSheet(
    state: SessionChatWorktreeMoveUiState,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
    onNewWorktree: () -> Unit,
    formCallbacks: WorktreeCreateFormCallbacks,
) {
    val form = state.form
    OpenCodeBottomSheet(
        visible = state.sheetVisible,
        title = if (form != null) stringResource(R.string.worktree_form_title) else SessionChatWorktreeMoveCopy.SHEET_TITLE,
        onDismissRequest = onDismiss,
        headerTrailingAction = form?.let { worktreeCreateBackAction(it, formCallbacks) },
        keyboard = if (form != null && !form.basePickerVisible) OpenCodeBottomSheetKeyboard.Keep else OpenCodeBottomSheetKeyboard.Dismiss,
        pinnedBottom = if (form != null) ({ WorktreeCreateFormActions(form, formCallbacks) }) else null,
    ) {
        if (form != null) {
            WorktreeCreateFormBody(form, formCallbacks)
        } else {
            SessionChatMoveToWorktreeRows(state, onMove, onNewWorktree)
        }
    }
}

@Composable
private fun SessionChatMoveToWorktreeRows(
    state: SessionChatWorktreeMoveUiState,
    onMove: (String) -> Unit,
    onNewWorktree: () -> Unit,
) {
    state.rows.forEach { row ->
        OpenCodeListItem(
            title = row.title,
            subtitle = row.subtitle,
            selected = row.isCurrent,
            enabled = state.submittingDirectory == null || state.submittingDirectory == row.directory,
            startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, null),
            onClick = if (row.isCurrent || state.submittingDirectory != null) null else ({ onMove(row.directory) }),
            modifier = Modifier.testTag(SessionChatWorktreeMoveTags.row(row.title)),
            trailing =
                if (row.isCurrent) {
                    {
                        Icon(
                            imageVector = OpenCodeIcons.Check,
                            contentDescription = stringResource(R.string.session_chat_move_worktree_current),
                            tint = openCodeColor("icon-strong-base"),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
        )
    }
    NewWorktreeRow(
        // Inert like the other rows while a move is in flight.
        availability = if (state.submittingDirectory != null) state.newWorktree.copy(enabled = false) else state.newWorktree,
        testTag = WorktreeCreateTags.MOVE_ROW,
        onClick = onNewWorktree,
    )
}

private fun InboxRequestAttribution.toComposerAttribution(summary: String) =
    OpenCodeSessionComposerState.RequestAttribution(sessionId = sessionId, letter = letter, summary = summary)

/**
 * Bottom-left activity the screen owns: collapsed requests, subagents, `/btw` side questions and queued prompts.
 * Collapsed requests show whatever the composer shows, so a child session's lockout cannot strand one.
 */
internal fun sessionChatActivityItems(
    uiState: SessionChatUiState,
    composerKind: SessionChatComposerKind,
): List<SessionChatActivityItem> =
    buildList {
        if (uiState.permissionDockHidden) add(SessionChatActivityItem.ReviewPermission)
        if (uiState.questionDockHidden) add(SessionChatActivityItem.AnswerQuestion)
        uiState.backgroundWorkPill?.let { add(SessionChatActivityItem.Subagents(it)) }
        if (composerKind == SessionChatComposerKind.Prompt) {
            val sideQuestions = uiState.sideQuestions
            if (sideQuestions.failedCount > 0) add(SessionChatActivityItem.SideQuestionsFailed(sideQuestions.failedCount))
            if (sideQuestions.readyCount > 0) add(SessionChatActivityItem.SideQuestionsReady(sideQuestions.readyCount))
            if (sideQuestions.pendingCount > 0) add(SessionChatActivityItem.SideQuestionsRunning(sideQuestions.pendingCount))
            if (uiState.pendingPrompts.isNotEmpty()) add(SessionChatActivityItem.Queued(uiState.pendingPrompts.size))
        }
    }

/** Test tag of Session Chat's archived banner. */
internal const val SessionChatArchivedBannerTag = "sessionChatArchivedBanner"

/** The gallery adds videos once streamed uploads take any file. */
private fun ComposerAttachmentSources.galleryRequest(): PickVisualMediaRequest =
    PickVisualMediaRequest(
        if (acceptsAnyType) {
            ActivityResultContracts.PickVisualMedia.ImageAndVideo
        } else {
            ActivityResultContracts.PickVisualMedia.ImageOnly
        },
    )
