package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.agent.AgentMention
import dev.opencode.mobile.domain.agent.MentionableAgent
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.command.ServerCommandSource
import dev.opencode.mobile.domain.event.LiveUpdatesLabel
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer

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
        get() = selectedVariant ?: ModelSelection.DefaultVariantLabel

    val variantOptions: List<String>
        get() = listOf(ModelSelection.DefaultVariantLabel) + selectedModel?.option?.variants.orEmpty()

    val canSelectVariant: Boolean
        get() = selectedModel?.option?.supportsVariants == true

    val canSend: Boolean
        get() = text.isNotBlank() || attachments.isNotEmpty()

    val canAttach: Boolean
        get() = selectedModel?.option?.canAttach == true

    val canAttachPhotos: Boolean
        get() = selectedModel?.option?.canAttachPhotos == true

    val canAttachFiles: Boolean
        get() = selectedModel?.option?.canAttachFiles == true
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

data class SessionChatUiState(
    val sessionId: String,
    val sessionTitle: String,
    val timeline: SessionChatTimelineState,
    val messages: List<SessionChatMessageItem> = emptyList(),
    /** Last projected timeline frame; reused when only non-timeline regions republish. */
    val timelineFrame: TimelineFrame? = null,
    val composer: SessionChatComposerState = SessionChatComposerState(),
    val isSending: Boolean = false,
    val isRefreshing: Boolean = false,
    val isAborting: Boolean = false,
    val sessionStatusLabel: String? = null,
    /** In-chat Working/Thinking/Retrying label for an active turn. */
    val workingIndicatorLabel: String? = null,
    /** Top Syncing pill label while cached messages wait for network confirmation. */
    val syncingIndicatorLabel: String? = null,
    val sessionTodos: List<SessionTodo> = emptyList(),
    val sessionTodosVisible: Boolean = false,
    val sessionTodosFinished: Boolean = false,
    val sessionTodoActivePreview: SessionTodo? = null,
    val sessionTodoCompletedCount: Int = 0,
    val sessionTodoTotalCount: Int = 0,
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
    val canMutate: Boolean = false,
    val messagesFromCache: Boolean = false,
    val contextUsageFraction: Float? = null,
    val errorMessage: String? = null,
    val snackbar: SessionChatSnackbar? = null,
    val isLifecycleActionInFlight: Boolean = false,
    val shouldLeaveSession: Boolean = false,
    val permissionRequest: PermissionRequest? = null,
    val permissionQueueCount: Int = 0,
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
    val supportsSessionMessageDiff: Boolean = true,
    val supportsSessionArchive: Boolean = true,
    val rename: SessionRenameUiState = SessionRenameUiState(),
    val slashSuggestionsVisible: Boolean = false,
    val slashSuggestions: List<SessionChatSlashSuggestion> = emptyList(),
    val atSuggestionsVisible: Boolean = false,
    val atSuggestions: List<SessionChatAtSuggestion> = emptyList(),
    val commandsSheetVisible: Boolean = false,
    val visibleClientSlashActions: List<ClientSlashAction> = listOf(ClientSlashAction.New),
    val forkDialogVisible: Boolean = false,
    val forkDraftTitle: String = "",
    val forkSubmitting: Boolean = false,
    val forkErrorMessage: String? = null,
    val forkResultVisible: Boolean = false,
    val forkedSessionTitle: String = "",
    val pendingSessionReplace: ChildSessionNavigationTarget? = null,
    /** Smart project label for top-bar chrome; null when worktree missing. */
    val projectLabel: String? = null,
    val projectLetter: Char? = null,
    val projectLetterAccent: dev.opencode.mobile.theme.LetterAccent? = null,
) {
    val latestCompletedUserMessageId: String?
        get() {
            var latest: String? = null
            var candidate: SessionChatMessageItem? = null
            messages.forEach { message ->
                if (message.role == MessageRole.User) {
                    candidate = message.takeUnless { it.isPending || it.isStreaming }
                } else if (candidate != null && !message.isPending && !message.isStreaming) {
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

data class ReviewChangesSummary(
    val fileCount: Int,
    val additions: Int,
    val deletions: Int,
) {
    companion object {
        fun from(files: List<FileDiff>): ReviewChangesSummary? =
            files.takeIf { it.isNotEmpty() }?.let {
                ReviewChangesSummary(
                    fileCount = it.size,
                    additions = it.sumOf(FileDiff::additions),
                    deletions = it.sumOf(FileDiff::deletions),
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

data class SessionChatTimelineRegionState(
    val timeline: SessionChatTimelineState = SessionChatTimelineState.Loading,
    val messages: List<SessionChatMessageItem> = emptyList(),
    val timelineFrame: TimelineFrame? = null,
    val isRefreshing: Boolean = false,
    val messagesFromCache: Boolean = false,
    val isSyncingMessages: Boolean = false,
    val errorMessage: String? = null,
)

/** Child session ids visible in the timeline (running subagent/task rows). */
fun SessionChatTimelineRegionState.knownChildSessionIds(): Set<String> =
    messages
        .flatMap { message ->
            message.blocks.mapNotNull { block ->
                when (block) {
                    is SessionChatTimelineBlock.ChildSessionRow -> block.childSessionId
                    is SessionChatTimelineBlock.ToolCard ->
                        block.childSessionId?.takeIf {
                            block.status == ToolStatus.Pending || block.status == ToolStatus.Running
                        }
                    else -> null
                }
            }
        }.toSet()

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
    val agentName: String,
)

data class SessionChatComposerRegionState(
    val composer: SessionChatComposerState = SessionChatComposerState(),
    val isSending: Boolean = false,
    val isAborting: Boolean = false,
    val errorMessage: String? = null,
    val abortErrorMessage: String? = null,
    val serverCommands: List<ServerCommand> = emptyList(),
    val mentionableAgents: List<MentionableAgent> = emptyList(),
    val slashSuggestionsVisible: Boolean = false,
    val slashSuggestions: List<SessionChatSlashSuggestion> = emptyList(),
    val atSuggestionsVisible: Boolean = false,
    val atSuggestions: List<SessionChatAtSuggestion> = emptyList(),
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

data class SessionInfoRegionState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    val sessionStatusLabel: String? = null,
    val authoritativeStatusLabel: String? = null,
    val optimisticBusy: Boolean = false,
    val hasResolvedSessionStatus: Boolean = false,
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
    val canMutate: Boolean = false,
    val rename: SessionRenameUiState = SessionRenameUiState(),
    val isLifecycleActionInFlight: Boolean = false,
    val lifecycleErrorMessage: String? = null,
    val shouldLeaveSession: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Effective Working/Retrying label: authoritative status, else optimistic busy.
 *
 * Non-null [authoritative] always clears optimistic. Resolved idle (`authoritative == null`
 * with [hasResolved]) also clears unless [optimisticBusy] is set explicitly (send mark).
 */
fun SessionInfoRegionState.withStatus(
    authoritative: String?,
    optimisticBusy: Boolean? = null,
    hasResolved: Boolean = hasResolvedSessionStatus || authoritative != null,
): SessionInfoRegionState {
    val nextOptimistic =
        when {
            authoritative != null -> false
            optimisticBusy != null -> optimisticBusy
            hasResolved -> false
            else -> this.optimisticBusy
        }
    return copy(
        authoritativeStatusLabel = authoritative,
        optimisticBusy = nextOptimistic,
        sessionStatusLabel = authoritative ?: if (nextOptimistic) "Working" else null,
        hasResolvedSessionStatus = hasResolved,
    )
}

data class SessionChatReviewChangesRegionState(
    val messageId: String? = null,
    val summary: ReviewChangesSummary? = null,
)

data class SessionChatCommandsRegionState(
    val commandsSheetVisible: Boolean = false,
    val visibleActions: List<ClientSlashAction> = listOf(ClientSlashAction.New),
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
)

data class LatestModelHint(
    val providerId: String,
    val modelId: String,
    val variant: String?,
    val sentAtEpochMs: Long?,
    val source: ModelSelectionHistorySource,
)

data class ModelSelectionHistoryHints(
    val user: LatestModelHint?,
    val assistant: LatestModelHint?,
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
}

fun LiveUpdatesLabel.toSessionConnectionStatus(): SessionConnectionStatus =
    when (this) {
        LiveUpdatesLabel.Live -> SessionConnectionStatus.Live
        LiveUpdatesLabel.Connecting -> SessionConnectionStatus.Connecting
        LiveUpdatesLabel.Offline -> SessionConnectionStatus.Offline
    }

enum class SessionChatTimelineState {
    Creating,
    Loading,
    Empty,
    Ready,
    Error,
}

data class SessionChatMessageItem(
    val id: String,
    val role: MessageRole,
    val text: String,
    val metadataLabel: String,
    val waitingCaption: String? = null,
    val waitingDelivery: FollowUpDelivery? = null,
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
        MessageRole.Unknown -> "Message"
    }
