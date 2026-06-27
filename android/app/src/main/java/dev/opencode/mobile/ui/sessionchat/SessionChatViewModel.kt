package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
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
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.ui.markdown.RenderedMarkdownMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

private const val STREAMING_MARKDOWN_PARSE_DEBOUNCE_MS = 40L

@HiltViewModel
class SessionChatViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val modelRepository: ModelRepository,
        private val sessionRepository: SessionRepository,
        private val messageLiveStore: MessageLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val foregroundEventStream: ForegroundEventStream,
        private val eventDomainAdapter: EventDomainAdapter,
        private val renderedMarkdownMapper: RenderedMarkdownMapper,
    ) : ViewModel() {
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        private val _uiState =
            MutableStateFlow(SessionChatUiState.initial(sessionId = "", sessionTitle = ""))
        val uiState: StateFlow<SessionChatUiState> = _uiState.asStateFlow()

        private var boundSessionId: String? = null
        private var boundWorktree: String? = null
        private var observeJob: Job? = null
        private var transformJob: Job? = null

        fun bind(
            sessionId: String,
            sessionTitle: String,
            worktree: String,
        ) {
            if (boundSessionId == sessionId && boundWorktree == worktree && _uiState.value.sessionTitle == sessionTitle) {
                return
            }
            observeJob?.cancel()
            transformJob?.cancel()
            boundSessionId = sessionId
            boundWorktree = worktree
            logI("bind session=$sessionId worktree=$worktree title=$sessionTitle")
            _uiState.value = SessionChatUiState.initial(sessionId, sessionTitle)
            logI("startLiveObservation session=$sessionId")
            startLiveObservation(sessionId, worktree)
            logI("loadMessages scheduled session=$sessionId")
            loadMessages()
        }

        private fun startLiveObservation(
            sessionId: String,
            worktree: String,
        ) {
            observeJob =
                viewModelScope.launch {
                    coroutineScope {
                        launch {
                            combine(
                                messageLiveStore.observeMessages(sessionId, worktree),
                                sessionStatusLiveStore.observeStatuses(worktree).map { statuses -> statuses[sessionId] },
                                foregroundEventStream.connectionState,
                            ) { messages, status, connectionState ->
                                Triple(messages, status, connectionState)
                            }.collect { (messages, status, connectionState) ->
                                if (!isCurrentBinding(sessionId, worktree)) return@collect
                                logD(
                                    "live state session=$sessionId messages=${messages.size} " +
                                        "status=${status?.raw?.get("type") ?: "-"} connection=$connectionState",
                                )
                        applyLoadedMessages(
                            messages = messages,
                            sourceSessionId = sessionId,
                            sourceWorktree = worktree,
                            fromQuietRefresh = true,
                            source = "live-store",
                        )
                                _uiState.update {
                                    it.copy(
                                        sessionStatusLabel = status?.let(eventDomainAdapter::statusLabel),
                                        connectionStatus = connectionState.toSessionConnectionStatus(),
                                    )
                                }
                            }
                        }
                        launch {
                            foregroundEventStream.events.collect { event ->
                                val scope =
                                    when (event.sessionId) {
                                        sessionId -> "bound"
                                        null -> "global"
                                        else -> "other"
                                    }
                                logD(
                                    "SSE event observed session=$sessionId type=${event.type} " +
                                        "eventSession=${event.sessionId ?: "-"} scope=$scope",
                                )
                            }
                        }
                    }
                }
        }

        fun loadMessages() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            viewModelScope.launch {
                if (!isCurrentBinding(sessionId, worktree)) return@launch
                logI("loadMessages start session=$sessionId worktree=$worktree")
                _uiState.update { it.copy(timeline = TimelineState.Loading, errorMessage = null) }
                runCatching {
                    val messages =
                        withContext(ioDispatcher) {
                            messageRepository.listMessages(sessionId, worktree)
                        }
                    logI("loadMessages fetched session=$sessionId count=${messages.size}")
                    runCatching {
                        withContext(ioDispatcher) {
                            modelRepository.listModelOptions(worktree)
                        }
                    }
                        .onSuccess { catalog ->
                            if (isCurrentBinding(sessionId, worktree)) {
                                logI(
                                    "loadMessages models session=$sessionId options=${catalog.options.size} " +
                                        "default=${catalog.serverDefault?.modelLabel ?: "-"}",
                                )
                                applyModelCatalog(catalog, messages)
                            }
                        }
                        .onFailure { throwable ->
                            logW("loadMessages models failed session=$sessionId error=${throwable.message ?: throwable::class.simpleName}")
                        }
                    messages
                }.onSuccess { messages ->
                    applyLoadedMessages(
                        messages = messages,
                        sourceSessionId = sessionId,
                        sourceWorktree = worktree,
                        source = "viewmodel-load",
                    )
                }.onFailure { throwable ->
                    logE("loadMessages failed session=$sessionId", throwable)
                    if (isCurrentBinding(sessionId, worktree)) {
                        applyLoadError(throwable)
                    }
                }
            }
        }

        fun refreshMessages() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            viewModelScope.launch {
                if (!isCurrentBinding(sessionId, worktree)) return@launch
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
                runCatching { messageRepository.listMessages(sessionId, worktree) }
                    .onSuccess { messages ->
                        applyLoadedMessages(
                            messages = messages,
                            sourceSessionId = sessionId,
                            sourceWorktree = worktree,
                            isRefreshing = false,
                            source = "refresh",
                        )
                    }
                    .onFailure { throwable ->
                        if (isCurrentBinding(sessionId, worktree)) {
                            _uiState.update {
                                it.copy(
                                    isRefreshing = false,
                                    errorMessage = errorMessage(throwable),
                                )
                            }
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

            viewModelScope.launch {
                if (!isCurrentBinding(sessionId, worktree)) return@launch
                val optimistic =
                    SessionChatMessageItem(
                        id = "pending-${UUID.randomUUID()}",
                        role = MessageRole.User,
                        text = prompt,
                        metadataLabel = composerMetadataLabel(),
                        isPending = true,
                        isStreaming = false,
                    )
                val enriched =
                    renderedMarkdownMapper.enrichRows(
                        rows = listOf(optimistic),
                        previousById = emptyMap(),
                    )
                _uiState.update {
                    it.copy(
                        composerText = "",
                        isSending = true,
                        errorMessage = null,
                        messages = it.messages + enriched,
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
                    if (isCurrentBinding(sessionId, worktree)) {
                        refreshSessionStatus()
                    }
                    try {
                        val messages = messageRepository.listMessages(sessionId, worktree)
                        applyLoadedMessages(
                            messages = messages,
                            sourceSessionId = sessionId,
                            sourceWorktree = worktree,
                            source = "send-prompt",
                        )
                    } catch (_: Throwable) {
                        // Prompt was accepted; live store or pull-to-refresh will sync the timeline.
                        if (isCurrentBinding(sessionId, worktree)) {
                            _uiState.update { it.copy(isSending = false) }
                        }
                    }
                } catch (throwable: Throwable) {
                    logSendFailure(throwable)
                    if (isCurrentBinding(sessionId, worktree)) {
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
        }

        fun abort() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(isAborting = true) }
                runCatching { sessionRepository.abortSession(sessionId, directory = worktree) }
                    .onFailure { throwable ->
                        _uiState.update {
                            it.copy(isAborting = false, errorMessage = errorMessage(throwable))
                        }
                    }.onSuccess {
                        _uiState.update { it.copy(isAborting = false) }
                    }
            }
        }

        private fun logSendFailure(throwable: Throwable) {
            runCatching { Log.e(TAG, "sendPromptAsync failed", throwable) }
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun logD(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        private fun logW(message: String) {
            runCatching { Log.w(TAG, message) }
        }

        private fun logE(
            message: String,
            throwable: Throwable,
        ) {
            runCatching { Log.e(TAG, message, throwable) }
        }

        private fun applyLoadedMessages(
            messages: List<Message>,
            sourceSessionId: String,
            sourceWorktree: String,
            isRefreshing: Boolean = false,
            fromQuietRefresh: Boolean = false,
            source: String,
        ) {
            if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return
            logI(
                "applyLoadedMessages start session=$sourceSessionId source=$source " +
                    "count=${messages.size} quietRefresh=$fromQuietRefresh refreshing=$isRefreshing",
            )
            transformJob?.cancel()
            transformJob =
                viewModelScope.launch {
                    if (fromQuietRefresh && hasStreamingDelta(messages)) {
                        logD("applyLoadedMessages debounce session=$sourceSessionId ms=$STREAMING_MARKDOWN_PARSE_DEBOUNCE_MS")
                        delay(STREAMING_MARKDOWN_PARSE_DEBOUNCE_MS)
                    }
                    if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return@launch
                    val stateSnapshot = _uiState.value
                    val previousById = stateSnapshot.messages.associateBy { it.id }
                    var structuralMs = 0L
                    var enrichMs = 0L
                    val structuralStartedAt = System.nanoTime()
                    val structural = timelineMessages(messages, previousById)
                    structuralMs = (System.nanoTime() - structuralStartedAt) / 1_000_000
                    val merged =
                        if (fromQuietRefresh && stateSnapshot.isSending) {
                            mergeWithPending(structural, stateSnapshot.messages.filter { it.isPending })
                        } else {
                            structural
                        }
                    val enrichStartedAt = System.nanoTime()
                    val enriched = renderedMarkdownMapper.enrichRows(merged, previousById)
                    enrichMs = (System.nanoTime() - enrichStartedAt) / 1_000_000
                    if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return@launch
                    val timeline =
                        when {
                            enriched.isEmpty() -> TimelineState.Empty
                            else -> TimelineState.Ready
                        }
                    val resolvedTimeline =
                        if (fromQuietRefresh && stateSnapshot.timeline == TimelineState.Error && enriched.isEmpty()) {
                            TimelineState.Error
                        } else {
                            timeline
                        }
                    logI(
                        "applyLoadedMessages ready session=$sourceSessionId source=$source " +
                            "timeline=$timeline messages=${enriched.size} structuralMs=$structuralMs enrichMs=$enrichMs",
                    )
                    _uiState.update { state ->
                        state.copy(
                            timeline = resolvedTimeline,
                            messages = enriched,
                            isSending = if (fromQuietRefresh) state.isSending else false,
                            isRefreshing = isRefreshing,
                            contextUsageFraction = contextUsageFraction(enriched, state.modelOptions, state.selectedModel),
                            errorMessage =
                                if (fromQuietRefresh) {
                                    if (enriched.isNotEmpty() && state.timeline == TimelineState.Error) {
                                        null
                                    } else {
                                        state.errorMessage
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                }
        }

        private fun isCurrentBinding(
            sessionId: String,
            worktree: String,
        ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

        private fun hasStreamingDelta(messages: List<Message>): Boolean =
            messages.any { message ->
                message.parts.any { part -> part is dev.opencode.mobile.domain.message.MessagePart.Text && part.isStreaming }
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

        private fun timelineMessages(
            messages: List<Message>,
            previousById: Map<String, SessionChatMessageItem>,
        ): List<SessionChatMessageItem> =
            messages
                .withIndex()
                .sortedWith { left, right ->
                    val leftTime = left.value.sentAtEpochMs
                    val rightTime = right.value.sentAtEpochMs
                    when {
                        leftTime != null && rightTime != null && leftTime != rightTime -> leftTime.compareTo(rightTime)
                        leftTime != null && rightTime != null -> left.value.id.compareTo(right.value.id)
                        else -> left.index.compareTo(right.index)
                    }
                }.map { toMessageItem(it.value, previousById[it.value.id]) }

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

        internal fun toMessageItem(
            message: Message,
            previous: SessionChatMessageItem? = null,
        ): SessionChatMessageItem {
            val isStreaming = message.parts.any { it is dev.opencode.mobile.domain.message.MessagePart.Text && it.isStreaming }
            val text = message.text
            val shouldRenderMarkdown = OpenCodeMarkdownDetector.shouldRender(text)
            val preservedMarkdown =
                previous?.renderedMarkdown?.takeIf {
                    shouldRenderMarkdown && (previous.text == text || previous.isStreaming)
                }
            return SessionChatMessageItem(
                id = message.id,
                role = message.role,
                text = text,
                metadataLabel = metadataLabel(message),
                isStreaming = isStreaming,
                renderedMarkdown = if (shouldRenderMarkdown) preservedMarkdown else null,
                modelProviderId = message.modelProviderId,
                modelId = message.modelId,
                contextInputTokens = message.contextInputTokens,
            )
        }

        private fun contextUsageFraction(
            messages: List<SessionChatMessageItem>,
            modelOptions: List<ModelSelectionOption>,
            selectedModel: ModelSelection?,
        ): Float? {
            val latestAssistantWithUsage =
                messages
                    .asReversed()
                    .firstOrNull { it.role == MessageRole.Assistant && it.contextInputTokens != null }
                    ?: return null
            val inputTokens = latestAssistantWithUsage.contextInputTokens ?: return null
            val messageHasModel = latestAssistantWithUsage.modelProviderId != null && latestAssistantWithUsage.modelId != null
            val contextLimit: Long =
                modelLimit(modelOptions, latestAssistantWithUsage)
                    ?: (if (messageHasModel) null else selectedModel?.option?.contextLimitTokens)
                    ?: return null
            if (contextLimit <= 0L) return null
            return (inputTokens.toFloat() / contextLimit.toFloat()).coerceIn(0f, 1f)
        }

        private fun modelLimit(
            modelOptions: List<ModelSelectionOption>,
            message: SessionChatMessageItem,
        ): Long? {
            val providerId = message.modelProviderId ?: return null
            val modelId = message.modelId ?: return null
            return modelOptions
                .firstOrNull { it.providerId == providerId && it.modelId == modelId }
                ?.contextLimitTokens
        }

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
            ).joinToString(" · ")
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
                    contextUsageFraction = contextUsageFraction(state.messages, catalog.options, ModelSelection(fallback, variant)),
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

        @Suppress("ktlint:standard:property-naming")
        companion object {
            private const val TAG = "SessionChat"
            val AvailableModes = listOf("Build", "Plan")
            const val ModelFilterThreshold = 10
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
