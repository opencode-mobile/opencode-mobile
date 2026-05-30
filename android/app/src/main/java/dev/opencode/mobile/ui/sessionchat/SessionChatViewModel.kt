package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.live.MessageLiveStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptModelSelection
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class SessionChatViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val modelRepository: ModelRepository,
        private val sessionRepository: SessionRepository,
        private val messageLiveStore: MessageLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val eventDomainAdapter: EventDomainAdapter,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(SessionChatUiState.initial(sessionId = "", sessionTitle = ""))
        val uiState: StateFlow<SessionChatUiState> = _uiState.asStateFlow()

        private var boundSessionId: String? = null
        private var boundWorktree: String? = null
        private var observeJob: Job? = null

        fun bind(
            sessionId: String,
            sessionTitle: String,
            worktree: String,
        ) {
            if (boundSessionId == sessionId && boundWorktree == worktree && _uiState.value.sessionTitle == sessionTitle) {
                return
            }
            observeJob?.cancel()
            boundSessionId = sessionId
            boundWorktree = worktree
            _uiState.value = SessionChatUiState.initial(sessionId, sessionTitle)
            startLiveObservation(sessionId, worktree)
            loadMessages()
        }

        private fun startLiveObservation(
            sessionId: String,
            worktree: String,
        ) {
            observeJob =
                viewModelScope.launch {
                    combine(
                        messageLiveStore.observeMessages(sessionId, worktree),
                        sessionStatusLiveStore.observeStatuses(worktree).map { statuses -> statuses[sessionId] },
                    ) { messages, status ->
                        messages to status
                    }.collect { (messages, status) ->
                        applyLoadedMessages(messages, fromQuietRefresh = true)
                        _uiState.update {
                            it.copy(sessionStatusLabel = status?.let(eventDomainAdapter::statusLabel))
                        }
                    }
                }
        }

        fun loadMessages() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(timeline = TimelineState.Loading, errorMessage = null) }
                runCatching {
                    val messages = messageRepository.listMessages(sessionId, worktree)
                    runCatching { modelRepository.listModelOptions(worktree) }
                        .onSuccess { catalog -> applyModelCatalog(catalog, messages) }
                    messages
                }.onSuccess { messages -> applyLoadedMessages(messages) }
                    .onFailure { throwable -> applyLoadError(throwable) }
            }
        }

        fun refreshMessages() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
                runCatching { messageRepository.listMessages(sessionId, worktree) }
                    .onSuccess { messages -> applyLoadedMessages(messages, isRefreshing = false) }
                    .onFailure { throwable ->
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                errorMessage = errorMessage(throwable),
                            )
                        }
                    }
            }
        }

        fun updateComposerText(text: String) {
            _uiState.update { it.copy(composerText = text) }
        }

        fun selectModel(model: String) {
            val option = _uiState.value.modelOptions.firstOrNull { it.id == model } ?: return
            selectModel(option)
        }

        fun selectModel(option: ModelSelectionOption) {
            _uiState.update { state ->
                val previousVariant = state.selectedVariant
                val variant = previousVariant?.takeIf { it in option.variants }
                state.copy(
                    selectedModel = ModelSelection(option, variant),
                    selectedVariant = variant,
                    modelNotice = null,
                )
            }
        }

        fun selectMode(mode: String) {
            if (mode in AvailableModes) {
                _uiState.update { it.copy(selectedMode = mode) }
            }
        }

        fun selectVariant(variant: String) {
            _uiState.update { state ->
                if (variant == ModelSelection.DefaultVariantLabel) {
                    return@update state.copy(
                        selectedModel = state.selectedModel?.copy(selectedVariant = null),
                        selectedVariant = null,
                    )
                }
                val selectedModel = state.selectedModel ?: return@update state
                if (variant !in selectedModel.option.variants) return@update state
                state.copy(
                    selectedModel = selectedModel.copy(selectedVariant = variant),
                    selectedVariant = variant,
                )
            }
        }

        fun restoreComposerText(text: String) {
            _uiState.update { it.copy(composerText = text) }
        }

        fun sendPrompt() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            val prompt = _uiState.value.composerText.trim()
            val selectedModel = _uiState.value.selectedModel ?: return
            if (prompt.isEmpty() || _uiState.value.isSending) return

            val optimistic =
                SessionChatMessageItem(
                    id = "pending-${UUID.randomUUID()}",
                    role = MessageRole.User,
                    text = prompt,
                    metadataLabel = composerMetadataLabel(),
                    isPending = true,
                )

            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        composerText = "",
                        isSending = true,
                        errorMessage = null,
                        messages = it.messages + optimistic,
                        timeline = TimelineState.Ready,
                    )
                }
                try {
                    messageRepository.sendPromptAsync(
                        sessionId = sessionId,
                        options =
                            PromptSendOptions(
                                text = prompt,
                                model = PromptModelSelection(selectedModel.option.providerId, selectedModel.option.modelId),
                                variant = _uiState.value.selectedVariant,
                            ),
                        directory = worktree,
                    )
                    refreshSessionStatus()
                    try {
                        val messages = messageRepository.listMessages(sessionId, worktree)
                        applyLoadedMessages(messages)
                    } catch (_: Throwable) {
                        // Prompt was accepted; live store or pull-to-refresh will sync the timeline.
                        _uiState.update { it.copy(isSending = false) }
                    }
                } catch (throwable: Throwable) {
                    logSendFailure(throwable)
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            messages = it.messages.filterNot { item -> item.id == optimistic.id },
                            errorMessage = errorMessage(throwable),
                        )
                    }
                }
            }
        }

        private fun logSendFailure(throwable: Throwable) {
            runCatching { Log.e(TAG, "sendPromptAsync failed", throwable) }
        }

        private fun applyLoadedMessages(
            messages: List<Message>,
            isRefreshing: Boolean = false,
            fromQuietRefresh: Boolean = false,
        ) {
            _uiState.update { state ->
                val serverItems = timelineMessages(messages)
                val merged =
                    if (fromQuietRefresh && state.isSending) {
                        mergeWithPending(serverItems, state.messages.filter { it.isPending })
                    } else {
                        serverItems
                    }
                state.copy(
                    timeline = if (merged.isEmpty()) TimelineState.Empty else TimelineState.Ready,
                    messages = merged,
                    isSending = if (fromQuietRefresh) state.isSending else false,
                    isRefreshing = isRefreshing,
                    errorMessage = if (fromQuietRefresh) state.errorMessage else null,
                )
            }
        }

        private fun mergeWithPending(
            serverItems: List<SessionChatMessageItem>,
            pendingItems: List<SessionChatMessageItem>,
        ): List<SessionChatMessageItem> {
            if (pendingItems.isEmpty()) return serverItems
            val pendingToKeep =
                pendingItems.filter { pending ->
                    !serverItems.any { it.role == MessageRole.User && it.text == pending.text }
                }
            return serverItems + pendingToKeep
        }

        private fun timelineMessages(messages: List<Message>): List<SessionChatMessageItem> =
            messages
                .sortedWith(
                    compareBy<Message>({ it.sentAtEpochMs ?: Long.MAX_VALUE }, { it.id }),
                ).map(::toMessageItem)

        private fun applyLoadError(throwable: Throwable) {
            _uiState.update {
                it.copy(
                    timeline = TimelineState.Error,
                    messages = emptyList(),
                    isSending = false,
                    errorMessage = errorMessage(throwable),
                )
            }
        }

        private fun toMessageItem(message: Message): SessionChatMessageItem =
            SessionChatMessageItem(
                id = message.id,
                role = message.role,
                text = message.text,
                metadataLabel = metadataLabel(message),
            )

        private fun metadataLabel(message: Message): String {
            val timeLabel = message.sentAtEpochMs?.let(::formatSentTime)
            return listOfNotNull(message.mode, message.model, message.variant, timeLabel).joinToString(" · ")
        }

        private fun composerMetadataLabel(): String {
            val state = _uiState.value
            return listOfNotNull(
                state.selectedMode,
                state.selectedModel?.modelLabel,
                state.selectedVariant,
                formatSentTime(System.currentTimeMillis()),
            )
                .joinToString(" · ")
        }

        private fun applyModelCatalog(
            catalog: ModelSelectionCatalog,
            messages: List<Message>,
        ) {
            if (catalog.options.isEmpty()) return
            _uiState.update { state ->
                val latestModelMessage =
                    messages
                        .sortedWith(compareByDescending<Message> { it.sentAtEpochMs ?: Long.MIN_VALUE }.thenByDescending { it.id })
                        .firstOrNull { it.modelProviderId != null && it.modelId != null }
                val currentOption =
                    state.selectedModel?.let { catalog.option(it.option.providerId, it.option.modelId) }
                val desired =
                    currentOption ?: latestModelMessage?.let { message ->
                        catalog.option(message.modelProviderId.orEmpty(), message.modelId.orEmpty())
                    }
                val fallback = desired ?: catalog.serverDefault ?: catalog.options.first()
                val notice =
                    if (state.selectedModel != null &&
                        catalog.option(state.selectedModel.option.providerId, state.selectedModel.option.modelId) == null
                    ) {
                        "Selected model is unavailable. Using ${fallback.modelLabel}."
                    } else {
                        null
                    }
                val variant = resolvedVariant(state.selectedVariant ?: latestModelMessage?.variant, fallback)
                state.copy(
                    modelOptions = catalog.options,
                    selectedModel = ModelSelection(fallback, variant),
                    selectedVariant = variant,
                    modelNotice = notice,
                )
            }
        }

        private fun resolvedVariant(
            remembered: String?,
            option: ModelSelectionOption,
        ): String? = remembered?.takeIf { it.isNotBlank() && it in option.variants }

        private fun formatSentTime(epochMs: Long): String =
            Instant
                .ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(SentTimeFormatter)

        private fun errorMessage(throwable: Throwable): String =
            when (throwable) {
                is MessageError.Http -> throwable.message ?: "Request failed"
                is MessageError.Transport -> "Unable to reach the server"
                is MessageError.Decoding -> "Unable to read server response"
                else -> throwable.message ?: "Something went wrong"
            }

        private fun refreshSessionStatus() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            viewModelScope.launch {
                runCatching { sessionRepository.getAllSessionStatuses(directory = worktree) }
                    .onSuccess { statuses ->
                        val status = statuses.firstOrNull { it.sessionId == sessionId }
                        val label = status?.let(eventDomainAdapter::statusLabel)
                        _uiState.update { it.copy(sessionStatusLabel = label) }
                    }
            }
        }

        companion object {
            private const val TAG = "SessionChat"
            val AvailableModes = listOf("Build", "Plan")
            private val SentTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        }
    }

data class SessionChatUiState(
    val sessionId: String,
    val sessionTitle: String,
    val timeline: TimelineState,
    val messages: List<SessionChatMessageItem>,
    val composerText: String,
    val selectedModel: ModelSelection?,
    val modelOptions: List<ModelSelectionOption>,
    val selectedMode: String,
    val selectedVariant: String?,
    val modelNotice: String?,
    val isSending: Boolean = false,
    val isRefreshing: Boolean = false,
    val sessionStatusLabel: String? = null,
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
                timeline = TimelineState.Loading,
                messages = emptyList(),
                composerText = "",
                selectedModel = null,
                modelOptions = emptyList(),
                selectedMode = SessionChatViewModel.AvailableModes.first(),
                selectedVariant = null,
                modelNotice = null,
            )
    }

    val selectedModelLabel: String
        get() = selectedModel?.modelLabel ?: "Model"

    val selectedVariantLabel: String
        get() = selectedVariant ?: ModelSelection.DefaultVariantLabel

    val variantOptions: List<String>
        get() = listOf(ModelSelection.DefaultVariantLabel) + selectedModel?.option?.variants.orEmpty()

    val canSelectVariant: Boolean
        get() = selectedModel?.option?.supportsVariants == true
}

enum class TimelineState {
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
)

fun MessageRole.displayLabel(): String =
    when (this) {
        MessageRole.User -> "You"
        MessageRole.Assistant -> "Assistant"
        MessageRole.Command -> "Command"
        MessageRole.Tool -> "Tool"
        MessageRole.Unknown -> "Message"
    }
