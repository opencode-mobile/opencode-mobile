package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.command.ServerCommandSource
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer

data class SessionChatComposerState(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val selectedModel: ModelSelection? = null,
    val modelOptions: List<ModelSelectionOption> = emptyList(),
    val selectedMode: String = SessionChatComposer.AvailableModes.first(),
    val selectedVariant: String? = null,
    val modelNotice: String? = null,
    val isModelSelectionReady: Boolean = false,
    val modelCatalogError: String? = null,
) {
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

data class SessionChatUiState(
    val sessionId: String,
    val sessionTitle: String,
    val timeline: SessionChatTimelineState,
    val messages: List<SessionChatMessageItem> = emptyList(),
    val composer: SessionChatComposerState = SessionChatComposerState(),
    val isSending: Boolean = false,
    val isRefreshing: Boolean = false,
    val isAborting: Boolean = false,
    val sessionStatusLabel: String? = null,
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
    val contextUsageFraction: Float? = null,
    val errorMessage: String? = null,
    val isLifecycleActionInFlight: Boolean = false,
    val shouldLeaveSession: Boolean = false,
    val permissionRequest: PermissionRequest? = null,
    val permissionQueueCount: Int = 0,
    val permissionSubmitting: Boolean = false,
    val permissionErrorMessage: String? = null,
    val reviewChangesMessageId: String? = null,
    val reviewChangesSummary: ReviewChangesSummary? = null,
    val rename: SessionRenameUiState = SessionRenameUiState(),
    val slashSuggestionsVisible: Boolean = false,
    val slashSuggestions: List<SessionChatSlashSuggestion> = emptyList(),
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
        ): SessionChatUiState =
            SessionChatUiState(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                timeline = SessionChatTimelineState.Loading,
            )
    }
}

object ReviewChangesPillVisibility {
    fun shouldShow(
        summary: ReviewChangesSummary?,
        isPinnedToEnd: Boolean,
        showScrollToEnd: Boolean,
    ): Boolean = (summary?.fileCount ?: 0) > 0 && isPinnedToEnd && !showScrollToEnd
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
)

data class SessionChatTimelineRegionState(
    val timeline: SessionChatTimelineState = SessionChatTimelineState.Loading,
    val messages: List<SessionChatMessageItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

data class SessionChatSlashSuggestion(
    val id: String,
    val title: String,
    val subtitle: String?,
    val badge: String?,
    val commandName: String,
)

data class SessionChatComposerRegionState(
    val composer: SessionChatComposerState = SessionChatComposerState(),
    val isSending: Boolean = false,
    val isAborting: Boolean = false,
    val errorMessage: String? = null,
    val abortErrorMessage: String? = null,
    val serverCommands: List<ServerCommand> = emptyList(),
    val slashSuggestionsVisible: Boolean = false,
    val slashSuggestions: List<SessionChatSlashSuggestion> = emptyList(),
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

data class SessionInfoRegionState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    val sessionStatusLabel: String? = null,
    val hasResolvedSessionStatus: Boolean = false,
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
    val rename: SessionRenameUiState = SessionRenameUiState(),
    val isLifecycleActionInFlight: Boolean = false,
    val lifecycleErrorMessage: String? = null,
    val shouldLeaveSession: Boolean = false,
    val errorMessage: String? = null,
)

data class SessionChatReviewChangesRegionState(
    val messageId: String? = null,
    val summary: ReviewChangesSummary? = null,
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

fun EventStreamConnectionState.toSessionConnectionStatus(): SessionConnectionStatus =
    when (this) {
        EventStreamConnectionState.Connected -> SessionConnectionStatus.Live
        EventStreamConnectionState.Connecting,
        EventStreamConnectionState.Reconnecting,
        -> SessionConnectionStatus.Connecting
        EventStreamConnectionState.Idle,
        EventStreamConnectionState.Disconnected,
        -> SessionConnectionStatus.Offline
    }

enum class SessionChatTimelineState {
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
