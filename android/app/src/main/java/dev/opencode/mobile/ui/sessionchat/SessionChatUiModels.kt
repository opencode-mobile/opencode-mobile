package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.markdown.MarkdownDocument

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
) {
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
    val connectionStatus: SessionConnectionStatus = SessionConnectionStatus.Offline,
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
