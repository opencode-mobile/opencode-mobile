package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.agent.AgentMention
import dev.opencode.mobile.domain.agent.FileMention
import dev.opencode.mobile.domain.agent.MentionableAgent
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.agent.SkillMention
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.ComposerShellCommand
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.command.ServerCommandSource
import dev.opencode.mobile.domain.event.LiveUpdatesLabel
import dev.opencode.mobile.domain.mcp.McpServer
import dev.opencode.mobile.domain.mcp.McpServerSummary
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.SessionRetryCard
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionLeadingIcon
import dev.opencode.mobile.ui.icons.OpenCodeFileIconKind
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMoveUiState

data class SessionChatComposerState(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val selectedModel: ModelSelection? = null,
    val modelOptions: List<ModelSelectionOption> = emptyList(),
    val selectedMode: String = SelectableAgent.DefaultId,
    val selectableAgents: List<SelectableAgent> = SelectableAgent.Fallback,
    val selectedVariant: String? = null,
    val modelNotice: String? = null,
    val isModelSelectionReady: Boolean = false,
    val modelCatalogError: String? = null,
    val agentMentions: List<AgentMention> = emptyList(),
    val fileMentions: List<FileMention> = emptyList(),
    val skillMentions: List<SkillMention> = emptyList(),
    val cursor: Int = 0,
    val selectionRevision: Int = 0,
    val followUpDelivery: FollowUpDelivery = FollowUpDelivery.Steer,
    val supportsFollowUpDelivery: Boolean = false,
) {
    val selectedModeLabel: String
        get() =
            selectableAgents.firstOrNull { it.id.equals(selectedMode, ignoreCase = true) }?.displayName
                ?: SelectableAgent.displayNameFor(selectedMode)

    val selectedModelLabel: String
        get() = if (isModelSelectionReady) selectedModel?.modelLabel ?: "Model" else "Loading model…"

    val selectedVariantLabel: String
        get() = TitleCaseIdentifier.displayName(selectedVariant ?: ModelSelection.DefaultVariantLabel)

    val variantOptions: List<String>
        get() = listOf(ModelSelection.DefaultVariantLabel) + selectedModel?.option?.variants.orEmpty()

    val canSelectVariant: Boolean
        get() = selectedModel?.option?.supportsVariants == true

    /** A leading `!` makes Send run the draft as a shell command; see [ComposerShellCommand]. */
    val isShellMode: Boolean
        get() = ComposerShellCommand.isShellMode(text)

    val canSend: Boolean
        get() =
            if (isShellMode) {
                ComposerShellCommand.command(text) != null
            } else {
                text.isNotBlank() || attachments.isNotEmpty()
            }

    val canAttach: Boolean
        get() = selectedModel?.option?.canAttach == true

    val mentionRanges: List<IntRange>
        get() =
            (
                agentMentions.map { it.start until it.end } +
                    fileMentions.map { it.start until it.end } +
                    skillMentions.map { it.start until it.end }
            ).sortedBy { it.first }

    val canAttachPhotos: Boolean
        get() = selectedModel?.option?.canAttachPhotos == true

    val canAttachFiles: Boolean
        get() = selectedModel?.option?.canAttachFiles == true

    fun attachmentSources(developerToolsUnlocked: Boolean): ComposerAttachmentSources =
        ComposerAttachmentSources(
            photos = canAttachPhotos,
            files = canAttachFiles,
            logFile = developerToolsUnlocked,
        )
}

data class ComposerAttachmentSources(
    val photos: Boolean,
    val files: Boolean,
    val logFile: Boolean,
) {
    val isAttachEnabled: Boolean
        get() = photos || files || logFile

    val showsSourceSheet: Boolean
        get() = logFile || (photos && files)
}

data class SessionRenameUiState(
    val isDialogVisible: Boolean = false,
    val draftTitle: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

enum class SessionChatSnackbarTone {
    Default,
    Error,
}

data class SessionChatSnackbar(
    val message: String,
    val tone: SessionChatSnackbarTone,
    val id: Long,
)

enum class ComposerDictationPhase {
    Listening,

    /** The recognizer stopped on its own; the transcript is ready to insert. */
    Stopped,
    Finishing,
    PermissionDenied,
    ModelNotInstalled,
    ModelDownloading,
    ModelDownloadQueued,
    ModelDownloadFailed,
    RecognizerError,
    ;

    /** A card about the on-device speech model; download updates may replace it. */
    val isModelPhase: Boolean
        get() =
            this == ModelNotInstalled ||
                this == ModelDownloading ||
                this == ModelDownloadQueued ||
                this == ModelDownloadFailed

    /** Phases that open the card without listening. */
    val isUnavailable: Boolean
        get() = this == PermissionDenied || this == RecognizerError || isModelPhase
}

data class ComposerDictationUiState(
    val phase: ComposerDictationPhase,
    val committed: String = "",
    val volatile: String = "",
    val level: Float = 0f,
    val languageLabel: String = "",
    val onDevice: Boolean = true,
    val startedAtEpochMs: Long = 0L,
    val errorMessage: String? = null,
    /** Model download progress, 0..1, while [phase] is ModelDownloading; null when unknown. */
    val downloadProgress: Float? = null,
)

data class SessionChatUiState(
    val sessionId: String,
    val sessionTitle: String,
    val timeline: SessionChatTimelineState,
    val messages: List<SessionChatMessageItem> = emptyList(),
    val pendingPrompts: List<PendingPrompt> = emptyList(),
    /** Last projected timeline frame; reused when only non-timeline regions republish. */
    val timelineFrame: TimelineFrame? = null,
    val composer: SessionChatComposerState = SessionChatComposerState(),
    val isSending: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isAborting: Boolean = false,
    val sessionStatusLabel: String? = null,
    /** In-chat Working/Thinking/Retrying label for an active turn. */
    val workingIndicatorLabel: String? = null,
    /** Live rate-limit countdown. Hidden for content-filter retries. */
    val retryCard: SessionRetryCard? = null,
    /** True after the Idle hold when the reserved footer has collapsed. */
    val workingIndicatorCollapsed: Boolean = false,
    /** True while the reserved Working lamp/label is fading through the idle hold. */
    val workingIndicatorFading: Boolean = false,
    /** Top Syncing pill label while cached or catching-up messages wait for network confirmation. */
    val syncingIndicatorLabel: String? = null,
    val sessionTodos: List<SessionTodo> = emptyList(),
    val sessionTodosVisible: Boolean = false,
    val sessionTodosFinished: Boolean = false,
    val sessionTodoActivePreview: SessionTodo? = null,
    val sessionTodoCompletedCount: Int = 0,
    val sessionTodoTotalCount: Int = 0,
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
    val canMutate: Boolean = false,
    val composerEnabled: Boolean = false,
    val pendingSend: PendingSend? = null,
    val messagesFromCache: Boolean = false,
    val contextUsageFraction: Float? = null,
    val errorMessage: String? = null,
    val snackbar: SessionChatSnackbar? = null,
    val isLifecycleActionInFlight: Boolean = false,
    val shouldLeaveSession: Boolean = false,
    val permissionRequest: PermissionRequest? = null,
    val permissionQueueCount: Int = 0,
    /** The permission dock is collapsed to a "Review permission" pill above the composer. */
    val permissionDockHidden: Boolean = false,
    val permissionSubmitting: Boolean = false,
    val permissionErrorMessage: String? = null,
    val questionRequest: QuestionRequest? = null,
    val questionWizardIndex: Int = 0,
    val questionSelectedLabels: List<String> = emptyList(),
    val questionCustomText: String = "",
    val questionCustomSelected: Boolean = false,
    val questionSubmitting: Boolean = false,
    val questionErrorMessage: String? = null,
    val reviewChangesMessageId: String? = null,
    val reviewChangesSummary: ReviewChangesSummary? = null,
    /** Parent of the loaded session; a child opened without a navigation parent still locks the composer. */
    val sessionParentId: String? = null,
    val sessionBranchPill: SessionBranchPillUiState? = null,
    val supportsSessionMessageDiff: Boolean = true,
    val supportsSessionArchive: Boolean = true,
    val rename: SessionRenameUiState = SessionRenameUiState(),
    val slashSuggestionsVisible: Boolean = false,
    val slashSuggestions: List<SessionChatSlashSuggestion> = emptyList(),
    val atSuggestionsVisible: Boolean = false,
    val atSuggestions: List<SessionChatAtSuggestion> = emptyList(),
    val commandsSheetVisible: Boolean = false,
    val visibleClientSlashActions: List<ClientSlashAction> =
        listOf(ClientSlashAction.New, ClientSlashAction.Terminal, ClientSlashAction.Files, ClientSlashAction.Mcp),
    val mcpPageVisible: Boolean = false,
    val mcpServers: List<McpServer> = emptyList(),
    val mcpLoading: Boolean = false,
    val mcpErrorMessage: String? = null,
    val mcpSummary: McpServerSummary = McpServerSummary.Empty,
    val forkDialogVisible: Boolean = false,
    val forkDraftTitle: String = "",
    val forkSubmitting: Boolean = false,
    val forkErrorMessage: String? = null,
    val forkResultVisible: Boolean = false,
    val forkedSessionTitle: String = "",
    val pendingSessionReplace: ChildSessionNavigationTarget? = null,
    /** "Move to worktree…": entry availability, the sheet, and its rows. */
    val moveToWorktree: SessionChatWorktreeMoveUiState = SessionChatWorktreeMoveUiState(),
    val pendingTerminalOpen: PendingTerminalOpen? = null,
    /** Worktree to open in the File Browser; consumed by the screen once it navigates. */
    val pendingFilesOpen: String? = null,
    val hasPendingRevert: Boolean = false,
    /** Smart project label for top-bar chrome; null when worktree missing. */
    val projectLabel: String? = null,
    val projectLetter: Char? = null,
    val projectLetterAccent: dev.opencode.mobile.theme.LetterAccent? = null,
    /** True when the subtitle is a worktree checkout name instead of the project letter. */
    val worktreeCheckout: Boolean = false,
    /**
     * Chip-timeline paragraphs expanded past `+N more`:
     * [SessionChatTimelineBlock.StepParagraph.expansionID] to the chip count at tap time.
     */
    val expandedStepParagraphs: Map<String, Int> = emptyMap(),
    /** Step rails unfolded past their `N earlier steps` row, by rail id (#779). */
    val unfoldedStepRails: Set<String> = emptySet(),
    /** Activity card whose live sheet is open, by block id (#779). */
    val presentedActivityCard: String? = null,
    /** Step of [presentedActivityCard] whose detail the sheet shows in place of its list (#779). */
    val activityCardDetailStepId: String? = null,
    /** What running child sessions on activity cards are doing, by child session id (#779). */
    val activityCardChildren: Map<String, SessionChatChildActivity> = emptyMap(),
    /** What each live activity card's footer band shows after the footer hold, by block id (#779). */
    val activityCardFooters: Map<String, SessionChatActivityFooter> = emptyMap(),
    /** In-flight steps not rendered yet: seen running for less than the entry debounce (#779). */
    val debouncedStepIds: Set<String> = emptySet(),
    /** One-time Chip timeline intro sheet (OPE-270). */
    val chipTimelineIntroVisible: Boolean = false,
    /** Null while idle. A value shows the dictation card in place of prompt entry. */
    val dictation: ComposerDictationUiState? = null,
    /** Directory the session runs in now; differs from the route's after `session.moved`. Empty until bound. */
    val worktree: String = "",
) {
    /** A permission, question, or Screen Model-owned sheet or dialog would sit under the intro sheet. */
    fun blocksChipTimelineIntro(): Boolean =
        permissionRequest != null ||
            questionRequest != null ||
            commandsSheetVisible ||
            mcpPageVisible ||
            forkDialogVisible ||
            forkResultVisible ||
            rename.isDialogVisible

    val latestCompletedUserMessageId: String?
        get() {
            var latest: String? = null
            var candidate: SessionChatMessageItem? = null
            messages.forEach { message ->
                if (message.role == MessageRole.User) {
                    candidate = message.takeUnless { it.isPending || it.isStreaming }
                } else if (message.role != MessageRole.Notice &&
                    candidate != null &&
                    !message.isPending &&
                    !message.isStreaming
                ) {
                    latest = candidate?.id
                }
            }
            return latest
        }

    fun changesNavigationTarget(worktree: String) =
        ChangesNavigationTarget(
            sessionId = sessionId,
            worktree = worktree,
            messageId = latestCompletedUserMessageId,
            initialSource = ChangeSource.WorkingTree,
        )

    fun reviewChangesNavigationTarget(worktree: String): ChangesNavigationTarget? {
        if (!supportsSessionMessageDiff) return null
        val messageId = reviewChangesMessageId ?: return null
        if ((reviewChangesSummary?.fileCount ?: 0) <= 0) return null
        return ChangesNavigationTarget(
            sessionId = sessionId,
            worktree = worktree,
            messageId = messageId,
            initialSource = ChangeSource.LastTurn,
        )
    }

    fun owningTurnMessageId(assistantMessageId: String): String? {
        var candidate: String? = null
        for (message in messages) {
            if (message.id == assistantMessageId) {
                return candidate
            }
            if (message.role == MessageRole.User && !message.isPending && !message.isStreaming) {
                candidate = message.id
            }
        }
        return null
    }

    fun fileChangeNavigationTarget(
        worktree: String,
        assistantMessageId: String,
        initialFilePath: String?,
    ): ChangesNavigationTarget? {
        if (!supportsSessionMessageDiff) return null
        val messageId = owningTurnMessageId(assistantMessageId) ?: return null
        return ChangesNavigationTarget(
            sessionId = sessionId,
            worktree = worktree,
            messageId = messageId,
            initialSource = ChangeSource.LastTurn,
            initialFilePath = initialFilePath,
        )
    }

    companion object {
        fun initial(
            sessionId: String,
            sessionTitle: String,
            timeline: SessionChatTimelineState = SessionChatTimelineState.Loading,
        ): SessionChatUiState =
            SessionChatUiState(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                timeline = timeline,
            )
    }
}

enum class SessionChatComposerKind {
    Permission,
    Question,
    ChildLockout,
    Prompt,
    ;

    companion object {
        fun resolve(
            isChildSession: Boolean,
            hasPermission: Boolean,
            hasQuestion: Boolean,
        ): SessionChatComposerKind =
            when {
                hasPermission -> Permission
                hasQuestion -> Question
                isChildSession -> ChildLockout
                else -> Prompt
            }
    }
}

object ReviewChangesPillVisibility {
    fun shouldShow(summary: ReviewChangesSummary?): Boolean = (summary?.fileCount ?: 0) > 0
}

object SessionBranchPillVisibility {
    fun shouldShow(
        pill: SessionBranchPillUiState?,
        hideFloatStack: Boolean,
    ): Boolean = !hideFloatStack && !pill?.branch.isNullOrEmpty()
}

/**
 * The one pill shown at the top of the Session Chat timeline.
 * Priority: Syncing, then ToDo, then Branch / Changes.
 */
sealed interface SessionChatTopPill {
    data class Syncing(
        val label: String,
    ) : SessionChatTopPill

    /**
     * Counts live on the pill so the outgoing ToDo in a kind crossfade keeps
     * the last positive total after the session list is cleared.
     */
    data class Todos(
        val completedCount: Int,
        val totalCount: Int,
        val activePreview: String?,
    ) : SessionChatTopPill

    data class Branch(
        val pill: SessionBranchPillUiState,
    ) : SessionChatTopPill

    companion object {
        /**
         * Syncing waits this long before it takes the slot, so a fast catch-up
         * does not flash the ToDo or Branch pill out and back in.
         */
        const val SYNCING_SHOW_DELAY_MS = 400L

        /** Once shown, Syncing keeps the slot at least this long. */
        const val SYNCING_MINIMUM_VISIBLE_MS = 600L

        fun resolve(
            syncingLabel: String?,
            todosVisible: Boolean,
            todoTotalCount: Int,
            branchPill: SessionBranchPillUiState?,
            hideFloatStack: Boolean,
            todoCompletedCount: Int = 0,
            todoActivePreview: String? = null,
        ): SessionChatTopPill? =
            when {
                syncingLabel != null -> Syncing(syncingLabel)
                !hideFloatStack && todosVisible && todoTotalCount > 0 ->
                    Todos(
                        completedCount = todoCompletedCount,
                        totalCount = todoTotalCount,
                        activePreview = todoActivePreview,
                    )
                branchPill != null && SessionBranchPillVisibility.shouldShow(branchPill, hideFloatStack) ->
                    Branch(branchPill)
                else -> null
            }
    }
}

data class ReviewChangesSummary(
    val fileCount: Int,
    val additions: Int,
    val deletions: Int,
    val messageId: String? = null,
) {
    companion object {
        fun from(
            files: List<FileDiff>,
            messageId: String? = null,
        ): ReviewChangesSummary? =
            files.takeIf { it.isNotEmpty() }?.let {
                ReviewChangesSummary(
                    fileCount = it.size,
                    additions = it.sumOf(FileDiff::additions),
                    deletions = it.sumOf(FileDiff::deletions),
                    messageId = messageId,
                )
            }
    }
}

data class ChangesNavigationTarget(
    val sessionId: String,
    val worktree: String,
    val messageId: String?,
    val initialSource: ChangeSource = ChangeSource.WorkingTree,
    val initialFilePath: String? = null,
)

data class ChildSessionNavigationTarget(
    val sessionId: String,
    val sessionTitle: String,
    val worktree: String,
    val optimisticNavigationInstanceId: String? = null,
)

data class PendingTerminalOpen(
    val worktree: String,
    val projectTitle: String,
)

data class SessionChatTimelineRegionState(
    val timeline: SessionChatTimelineState = SessionChatTimelineState.Loading,
    val messages: List<SessionChatMessageItem> = emptyList(),
    val timelineFrame: TimelineFrame? = null,
    val isRefreshing: Boolean = false,
    val messagesFromCache: Boolean = false,
    val isSyncingMessages: Boolean = false,
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val errorMessage: String? = null,
)

/** Child session ids visible in the timeline (running subagent/task rows). */
fun SessionChatTimelineRegionState.knownChildSessionIds(): Set<String> =
    messages
        .flatMap { message ->
            message.blocks.flatMap { block ->
                when (block) {
                    is SessionChatTimelineBlock.ChildSessionRow -> listOfNotNull(block.childSessionId)
                    is SessionChatTimelineBlock.ToolCard -> listOfNotNull(block.liveChildSessionId())
                    is SessionChatTimelineBlock.StepParagraph ->
                        block.chips.mapNotNull { chip -> chip.card.liveChildSessionId() }
                    else -> emptyList()
                }
            }
        }.toSet()

private fun SessionChatTimelineBlock.ToolCard.liveChildSessionId(): String? =
    childSessionId?.takeIf { status == ToolStatus.Pending || status == ToolStatus.Running }

data class SessionChatSlashSuggestion(
    val id: String,
    val title: String,
    val subtitle: String?,
    val badge: String?,
    val commandName: String,
)

data class SessionChatAtSuggestion(
    val id: String,
    val title: String,
    val subtitle: String?,
    val agentName: String? = null,
    val filePath: String? = null,
    val isDirectory: Boolean = false,
) {
    /** File suggestions show their file-type icon; agent suggestions show the agent glyph. */
    val leadingIcon: OpenCodeComposerSuggestionLeadingIcon?
        get() =
            when {
                filePath != null ->
                    OpenCodeComposerSuggestionLeadingIcon.File(
                        path = filePath,
                        kind = if (isDirectory) OpenCodeFileIconKind.Directory else OpenCodeFileIconKind.File,
                    )
                agentName != null -> OpenCodeComposerSuggestionLeadingIcon.Icon(OpenCodeIcons.Agent)
                else -> null
            }
}

data class SessionChatComposerRegionState(
    val composer: SessionChatComposerState = SessionChatComposerState(),
    val isSending: Boolean = false,
    val sendProgressPercent: Int? = null,
    val isAborting: Boolean = false,
    val errorMessage: String? = null,
    val abortErrorMessage: String? = null,
    val serverCommands: List<ServerCommand> = emptyList(),
    val mentionableAgents: List<MentionableAgent> = emptyList(),
    val slashSuggestionsVisible: Boolean = false,
    val slashSuggestions: List<SessionChatSlashSuggestion> = emptyList(),
    val atSuggestionsVisible: Boolean = false,
    val atSuggestions: List<SessionChatAtSuggestion> = emptyList(),
    val fileSuggestions: List<SessionChatAtSuggestion> = emptyList(),
    val fileSuggestionQuery: String? = null,
    val followUpNotice: String? = null,
)

fun ServerCommand.toSlashSuggestion(): SessionChatSlashSuggestion =
    SessionChatSlashSuggestion(
        id = name,
        title = "/$name",
        subtitle = description,
        badge =
            when (source) {
                ServerCommandSource.Skill -> "skill"
                ServerCommandSource.Mcp -> "mcp"
                ServerCommandSource.Command -> null
            },
        commandName = name,
    )

fun MentionableAgent.toAtSuggestion(): SessionChatAtSuggestion =
    SessionChatAtSuggestion(
        id = name,
        title = "@$name",
        subtitle = description,
        agentName = name,
    )

fun dev.opencode.mobile.domain.filesystem.ProjectEntry.toFileSuggestion(): SessionChatAtSuggestion =
    SessionChatAtSuggestion(
        id = "file:$relativePath",
        title = relativePath,
        subtitle = "File",
        filePath = relativePath,
        isDirectory = isDirectory,
    )

data class PendingSend(
    val queuedAtMs: Long,
)

data class SessionInfoRegionState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    /** The loaded session's parent; set even when navigation did not say it is a child. */
    val parentId: String? = null,
    val sessionStatusLabel: String? = null,
    val retryCard: SessionRetryCard? = null,
    val hasResolvedSessionStatus: Boolean = false,
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
    val canMutate: Boolean = false,
    val composerEnabled: Boolean = false,
    val rename: SessionRenameUiState = SessionRenameUiState(),
    val isLifecycleActionInFlight: Boolean = false,
    val lifecycleErrorMessage: String? = null,
    val shouldLeaveSession: Boolean = false,
    val errorMessage: String? = null,
)

fun SessionInfoRegionState.withStatus(
    authoritative: String?,
    hasResolved: Boolean = true,
    retryCard: SessionRetryCard? = null,
): SessionInfoRegionState =
    copy(
        sessionStatusLabel = authoritative,
        hasResolvedSessionStatus = hasResolved,
        retryCard = retryCard,
    )

data class SessionChatReviewChangesRegionState(
    val messageId: String? = null,
    val summary: ReviewChangesSummary? = null,
)

data class SessionChatRepoStripRegionState(
    /** False until the server reports a current branch for the worktree. */
    val isAvailable: Boolean = false,
    val branch: String = "",
    val defaultBranch: String? = null,
    val files: List<VcsFileStatus> = emptyList(),
    val isExpanded: Boolean = false,
)

data class SessionBranchPillUiState(
    val branch: String,
    val additions: Int,
    val deletions: Int,
)

fun SessionChatRepoStripRegionState.toSessionBranchPill(): SessionBranchPillUiState? {
    if (!isAvailable || branch.isEmpty()) return null
    return SessionBranchPillUiState(
        branch = branch,
        additions = files.sumOf { it.additions }.coerceAtLeast(0),
        deletions = files.sumOf { it.deletions }.coerceAtLeast(0),
    )
}

data class SessionChatInboxRegionState(
    val permissionRequests: List<PermissionRequest> = emptyList(),
    /** The pending permission the user tucked away to reach the composer (never rejects it). */
    val permissionHiddenId: String? = null,
    val permissionSubmittingId: String? = null,
    val permissionError: String? = null,
    val permissionTopologyError: String? = null,
    val questionRequests: List<QuestionRequest> = emptyList(),
    val questionSubmittingId: String? = null,
    val questionError: String? = null,
    val questionTopologyError: String? = null,
    val questionWizardIndex: Int = 0,
    val questionDrafts: Map<Int, QuestionAnswerDraft> = emptyMap(),
    val sessionTodos: List<SessionTodo> = emptyList(),
) {
    val visibleQuestionRequest: QuestionRequest?
        get() = if (permissionRequests.isNotEmpty()) null else questionRequests.firstOrNull()

    /** True while the first pending permission is the one the user hid; a new request shows again. */
    val isPermissionDockHidden: Boolean
        get() = permissionHiddenId != null && permissionRequests.firstOrNull()?.id == permissionHiddenId

    val questionDraft: QuestionAnswerDraft
        get() = questionDrafts[questionWizardIndex] ?: QuestionAnswerDraft()
}

data class SessionChatCommandsRegionState(
    val commandsSheetVisible: Boolean = false,
    val visibleActions: List<ClientSlashAction> =
        listOf(ClientSlashAction.New, ClientSlashAction.Terminal, ClientSlashAction.Files, ClientSlashAction.Mcp),
    val mcpPageVisible: Boolean = false,
    val mcpServers: List<McpServer> = emptyList(),
    val mcpLoading: Boolean = false,
    val mcpErrorMessage: String? = null,
    val mcpSummary: McpServerSummary = McpServerSummary.Empty,
    val hasPendingRevert: Boolean = false,
    /** First reverted message while a revert is pending; the timeline hides it and later turns. */
    val revertMessageId: String? = null,
    val forkDialogVisible: Boolean = false,
    val forkDraftTitle: String = "",
    val forkSubmitting: Boolean = false,
    val forkErrorMessage: String? = null,
    val forkResultVisible: Boolean = false,
    val forkedSessionTitle: String = "",
    val isCompacting: Boolean = false,
    val actionErrorMessage: String? = null,
    val actionSuccessMessage: String? = null,
    val pendingSessionReplace: ChildSessionNavigationTarget? = null,
    val pendingTerminalOpen: PendingTerminalOpen? = null,
    /** Worktree to open in the File Browser; consumed by the screen once it navigates. */
    val pendingFilesOpen: String? = null,
)

data class LatestModelHint(
    val providerId: String,
    val modelId: String,
    val variant: String?,
    val sentAtEpochMs: Long?,
    val source: ModelSelectionHistorySource,
)

data class LatestAgentHint(
    val mode: String,
    val sentAtEpochMs: Long?,
)

data class ModelSelectionHistoryHints(
    val user: LatestModelHint?,
    val assistant: LatestModelHint?,
    val userAgent: LatestAgentHint? = null,
    val assistantAgent: LatestAgentHint? = null,
)

enum class ModelSelectionHistorySource {
    User,
    Assistant,
}

enum class SessionConnectionStatus(
    val label: String,
) {
    Live("live"),
    Connecting("connecting"),
    Offline("offline"),
    AuthFailed("Authentication failed"),
}

fun LiveUpdatesLabel.toSessionConnectionStatus(): SessionConnectionStatus =
    when (this) {
        LiveUpdatesLabel.Live -> SessionConnectionStatus.Live
        LiveUpdatesLabel.Connecting -> SessionConnectionStatus.Connecting
        LiveUpdatesLabel.Offline -> SessionConnectionStatus.Offline
        LiveUpdatesLabel.AuthFailed -> SessionConnectionStatus.AuthFailed
    }

enum class SessionChatTimelineState {
    Creating,
    Loading,
    Empty,
    Ready,
    Error,
}

data class PendingPrompt(
    val id: String,
    val text: String,
    val delivery: FollowUpDelivery,
    val isSettling: Boolean = false,
)

data class SessionChatMessageItem(
    val id: String,
    val role: MessageRole,
    val text: String,
    val metadataLabel: String,
    val pendingDelivery: FollowUpDelivery? = null,
    val isPending: Boolean = false,
    val isStreaming: Boolean = false,
    val renderedMarkdown: MarkdownDocument? = null,
    val blocks: List<SessionChatTimelineBlock> = emptyList(),
    val attachments: List<SessionChatAttachmentItem> = emptyList(),
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val contextInputTokens: Long? = null,
)

data class SessionChatAttachmentItem(
    val id: String,
    val filename: String,
    val variant: OpenCodeAttachmentVariant,
    val signature: String,
    val imageBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionChatAttachmentItem &&
            id == other.id &&
            filename == other.filename &&
            variant == other.variant &&
            signature == other.signature &&
            imageBytes.contentEquals(other.imageBytes)

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * id.hashCode() + filename.hashCode()) + variant.hashCode()) + signature.hashCode()) +
            imageBytes.contentHashCode()
}

fun MessageRole.displayLabel(): String =
    when (this) {
        MessageRole.User -> "You"
        MessageRole.Assistant -> "Assistant"
        MessageRole.Command -> "Command"
        MessageRole.Tool -> "Tool"
        MessageRole.Notice -> "Notice"
        MessageRole.Unknown -> "Message"
    }
