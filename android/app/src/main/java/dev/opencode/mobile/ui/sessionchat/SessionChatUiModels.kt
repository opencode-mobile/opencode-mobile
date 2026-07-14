package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer

data class SessionChatComposerState(
    val text: String = "",
    val selectedModel: ModelSelection? = null,
    val modelOptions: List<ModelSelectionOption> = emptyList(),
    val selectedMode: String = SessionChatComposer.AvailableModes.first(),
    val selectedVariant: String? = null,
    val modelNotice: String? = null,
) {
    val selectedModelLabel: String
        get() = selectedModel?.modelLabel ?: "Model"

    val selectedVariantLabel: String
        get() = selectedVariant ?: ModelSelection.DefaultVariantLabel

    val variantOptions: List<String>
        get() = listOf(ModelSelection.DefaultVariantLabel) + selectedModel?.option?.variants.orEmpty()

    val canSelectVariant: Boolean
        get() = selectedModel?.option?.supportsVariants == true
}

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
    val permissionRequest: PermissionRequest? = null,
    val permissionQueueCount: Int = 0,
    val permissionSubmitting: Boolean = false,
    val permissionErrorMessage: String? = null,
    val reviewChangesMessageId: String? = null,
    val reviewChangesSummary: ReviewChangesSummary? = null,
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
)

data class SessionChatTimelineRegionState(
    val timeline: SessionChatTimelineState = SessionChatTimelineState.Loading,
    val messages: List<SessionChatMessageItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

data class SessionChatComposerRegionState(
    val composer: SessionChatComposerState = SessionChatComposerState(),
    val isSending: Boolean = false,
    val isAborting: Boolean = false,
    val errorMessage: String? = null,
    val abortErrorMessage: String? = null,
)

data class SessionInfoRegionState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    val sessionStatusLabel: String? = null,
    val hasResolvedSessionStatus: Boolean = false,
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
)

data class SessionChatReviewChangesRegionState(
    val messageId: String? = null,
    val summary: ReviewChangesSummary? = null,
)

data class LatestModelHint(
    val providerId: String,
    val modelId: String,
    val variant: String?,
)

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
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val contextInputTokens: Long? = null,
)

fun MessageRole.displayLabel(): String =
    when (this) {
        MessageRole.User -> "You"
        MessageRole.Assistant -> "Assistant"
        MessageRole.Command -> "Command"
        MessageRole.Tool -> "Tool"
        MessageRole.Unknown -> "Message"
    }
