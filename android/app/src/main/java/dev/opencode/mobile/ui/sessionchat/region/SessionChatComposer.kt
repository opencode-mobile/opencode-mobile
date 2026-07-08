package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.ui.sessionchat.LatestModelHint
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.PromptModelSelection
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class SessionChatComposer(
    private val messageRepository: MessageRepository,
    private val modelRepository: ModelRepository,
    private val sessionRepository: SessionRepository,
    private val connectionSessionCoordinator: ConnectionSessionCoordinator,
    private val sessionModelSelectionStore: SessionModelSelectionStore,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatComposerRegionState())
    val state: StateFlow<SessionChatComposerRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var loadedCatalog: ModelSelectionCatalog? = null
    private var cachedSelection: SessionModelSelection? = null
    private var cacheResolved = false
    private var appliedInitialModelSelection = false

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId &&
            boundWorktree == worktree &&
            _state.value.composer.modelOptions.isNotEmpty()
        ) {
            return
        }
        boundSessionId = sessionId
        boundWorktree = worktree
        loadedCatalog = null
        cachedSelection = null
        cacheResolved = false
        appliedInitialModelSelection = false
        _state.value = SessionChatComposerRegionState()
        logI("bind session=$sessionId worktree=$worktree")
        loadCachedSelection()
    }

    fun loadCatalog() {
        val worktree = boundWorktree ?: return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    modelRepository.listModelOptions(worktree)
                }
            }.onSuccess { catalog ->
                if (boundWorktree == worktree) {
                    logI(
                        "loadCatalog options=${catalog.options.size} " +
                            "default=${catalog.serverDefault?.modelLabel ?: "-"}",
                    )
                    applyCatalog(catalog)
                }
            }.onFailure { throwable ->
                logW("loadCatalog failed error=${throwable.message ?: throwable::class.simpleName}")
            }
        }
    }

    fun applyLatestModelHint(hint: LatestModelHint?) {
        val catalog = loadedCatalog ?: return
        if (catalog.options.isEmpty()) return
        val latestOption = hint?.let { catalog.option(it.providerId, it.modelId) } ?: return
        updateFromCatalog(catalog, latestOption, hint.variant)
    }

    fun tryApplyInitialSelection(
        timelineState: SessionChatTimelineRegionState,
        messageHint: LatestModelHint?,
    ) {
        if (appliedInitialModelSelection) return
        if (!cacheResolved) return
        if (_state.value.composer.modelOptions.isEmpty()) return

        when (timelineState.timeline) {
            SessionChatTimelineState.Loading -> return
            SessionChatTimelineState.Error,
            SessionChatTimelineState.Empty,
            -> applyResolvedInitialSelection(messageHint, markAppliedWithoutHint = true)
            SessionChatTimelineState.Ready -> {
                val staleCached = staleCachedSelection()
                cachedSelection?.takeIf { staleCached == null }?.let { cached ->
                    appliedInitialModelSelection = true
                    applyLatestModelHint(
                        LatestModelHint(
                            providerId = cached.providerId,
                            modelId = cached.modelId,
                            variant = cached.variant,
                        ),
                    )
                    return
                }
                messageHint?.let { hint ->
                    appliedInitialModelSelection = true
                    applyLatestModelHint(hint)
                    staleCached?.let { applyStaleCacheNotice(it) }
                } ?: run {
                    if (timelineState.messages.isNotEmpty()) {
                        appliedInitialModelSelection = true
                        staleCached?.let { applyStaleCacheNotice(it) }
                    }
                }
            }
        }
    }

    private fun applyResolvedInitialSelection(
        messageHint: LatestModelHint?,
        markAppliedWithoutHint: Boolean,
    ) {
        val staleCached = staleCachedSelection()
        cachedSelection?.takeIf { staleCached == null }?.let { cached ->
            appliedInitialModelSelection = true
            applyLatestModelHint(
                LatestModelHint(
                    providerId = cached.providerId,
                    modelId = cached.modelId,
                    variant = cached.variant,
                ),
            )
            return
        }
        messageHint?.let { hint ->
            appliedInitialModelSelection = true
            applyLatestModelHint(hint)
            staleCached?.let { applyStaleCacheNotice(it) }
            return
        }
        if (markAppliedWithoutHint) {
            appliedInitialModelSelection = true
            staleCached?.let { applyStaleCacheNotice(it) }
        }
    }

    fun updateComposerText(text: String) {
        updateComposer { it.copy(text = text) }
    }

    fun selectModel(option: ModelSelectionOption) {
        updateComposer { composer ->
            val previousVariant = composer.selectedVariant
            val variant = previousVariant?.takeIf { it in option.variants }
            composer.copy(
                selectedModel = ModelSelection(option, variant),
                selectedVariant = variant,
                modelNotice = null,
            )
        }
        persistCurrentSelection()
    }

    fun selectMode(mode: String) {
        if (mode in AvailableModes) {
            updateComposer { it.copy(selectedMode = mode) }
        }
    }

    fun selectVariant(variant: String) {
        updateComposer { composer ->
            if (variant == ModelSelection.DefaultVariantLabel) {
                return@updateComposer composer.copy(
                    selectedModel = composer.selectedModel?.copy(selectedVariant = null),
                    selectedVariant = null,
                )
            }
            val selectedModel = composer.selectedModel ?: return@updateComposer composer
            if (variant !in selectedModel.option.variants) return@updateComposer composer
            composer.copy(
                selectedModel = selectedModel.copy(selectedVariant = variant),
                selectedVariant = variant,
            )
        }
        persistCurrentSelection()
    }

    fun restoreComposerText(text: String) {
        updateComposer { it.copy(text = text) }
    }

    fun sendPrompt(
        sessionId: String,
        worktree: String,
        onOptimisticRows: suspend (List<SessionChatMessageItem>) -> Unit,
        onSendAccepted: suspend () -> Unit,
        onSendFailed: (optimisticId: String, error: String) -> Unit,
    ) {
        val composer = _state.value.composer
        val prompt = composer.text.trim()
        val selectedModel = composer.selectedModel ?: return
        if (prompt.isEmpty() || _state.value.isSending) return

        scope.launch {
            val display =
                withContext(ioDispatcher) {
                    messageRepository.prepareOptimisticUserMessage(
                        id = "pending-${UUID.randomUUID()}",
                        text = prompt,
                    )
                }
            val enriched =
                listOf(
                    SessionChatMessageItem(
                        id = display.id,
                        role = dev.opencode.mobile.domain.message.MessageRole.User,
                        text = display.text,
                        metadataLabel = composerMetadataLabel(composer),
                        isPending = true,
                        isStreaming = false,
                        renderedMarkdown = display.renderedMarkdown,
                    ),
                )
            _state.update {
                it.copy(
                    composer = composer.copy(text = ""),
                    isSending = true,
                    errorMessage = null,
                )
            }
            onOptimisticRows(enriched)
            try {
                messageRepository.sendPromptAsync(
                    sessionId = sessionId,
                    options =
                        PromptSendOptions(
                            text = prompt,
                            model = PromptModelSelection(selectedModel.option.providerId, selectedModel.option.modelId),
                            variant = composer.selectedVariant,
                            mode = agentMode(composer.selectedMode),
                        ),
                    directory = worktree,
                )
                persistCurrentSelection()
                onSendAccepted()
            } catch (throwable: Throwable) {
                logSendFailure(throwable)
                _state.update { it.copy(isSending = false) }
                onSendFailed(display.id, errorMessage(throwable))
            }
        }
    }

    fun clearSending() {
        _state.update { it.copy(isSending = false) }
    }

    fun abort() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch {
            _state.update { it.copy(isAborting = true, abortErrorMessage = null) }
            runCatching { sessionRepository.abortSession(sessionId, directory = worktree) }
                .onFailure { throwable ->
                    if (isCurrentBinding(sessionId, worktree)) {
                        _state.update {
                            it.copy(
                                isAborting = false,
                                abortErrorMessage = errorMessage(throwable),
                            )
                        }
                    }
                }.onSuccess {
                    if (isCurrentBinding(sessionId, worktree)) {
                        _state.update { it.copy(isAborting = false, abortErrorMessage = null) }
                    }
                }
        }
    }

    private fun loadCachedSelection() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        val serverBaseUrl = connectionSessionCoordinator.currentProfile()?.baseUrl ?: run {
            cacheResolved = true
            return
        }
        scope.launch {
            cachedSelection =
                withContext(ioDispatcher) {
                    sessionModelSelectionStore.load(
                        serverBaseUrl = serverBaseUrl,
                        worktree = worktree,
                        sessionId = sessionId,
                    )
                }
            cacheResolved = true
        }
    }

    private fun persistCurrentSelection() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        val serverBaseUrl = connectionSessionCoordinator.currentProfile()?.baseUrl ?: return
        val selected = _state.value.composer.selectedModel ?: return
        val variant = _state.value.composer.selectedVariant
        scope.launch {
            val selection =
                SessionModelSelection(
                    providerId = selected.option.providerId,
                    modelId = selected.option.modelId,
                    variant = variant,
                )
            withContext(ioDispatcher) {
                sessionModelSelectionStore.save(
                    serverBaseUrl = serverBaseUrl,
                    worktree = worktree,
                    sessionId = sessionId,
                    selection = selection,
                )
            }
            cachedSelection = selection
        }
    }

    private fun staleCachedSelection(): SessionModelSelection? {
        val cached = cachedSelection ?: return null
        val isAvailable =
            _state.value.composer.modelOptions.any {
                it.providerId == cached.providerId && it.modelId == cached.modelId
            }
        return if (isAvailable) null else cached
    }

    private fun applyStaleCacheNotice(cached: SessionModelSelection) {
        val fallbackLabel = _state.value.composer.selectedModel?.modelLabel ?: cached.modelId
        updateComposer { it.copy(modelNotice = "Selected model is unavailable. Using $fallbackLabel.") }
    }

    private fun isCurrentBinding(
        sessionId: String,
        worktree: String,
    ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

    private fun applyCatalog(catalog: ModelSelectionCatalog) {
        if (catalog.options.isEmpty()) return
        loadedCatalog = catalog
        updateFromCatalog(catalog, latestOption = null, latestVariant = null)
    }

    private fun updateFromCatalog(
        catalog: ModelSelectionCatalog,
        latestOption: ModelSelectionOption?,
        latestVariant: String?,
    ) {
        _state.update { state ->
            val currentOption =
                state.composer.selectedModel?.let { catalog.option(it.option.providerId, it.option.modelId) }
            val desired = latestOption ?: currentOption
            val fallback = desired ?: catalog.serverDefault ?: catalog.options.first()
            val notice =
                if (state.composer.selectedModel != null &&
                    catalog.option(
                        state.composer.selectedModel.option.providerId,
                        state.composer.selectedModel.option.modelId,
                    ) == null
                ) {
                    "Selected model is unavailable. Using ${fallback.modelLabel}."
                } else {
                    null
                }
            val variant = resolvedVariant(state.composer.selectedVariant ?: latestVariant, fallback)
            val selectedModel = ModelSelection(fallback, variant)
            state.copy(
                composer =
                    state.composer.copy(
                        modelOptions = catalog.options,
                        selectedModel = selectedModel,
                        selectedVariant = variant,
                        modelNotice = notice,
                    ),
            )
        }
    }

    private fun resolvedVariant(
        remembered: String?,
        option: ModelSelectionOption,
    ): String? = remembered?.takeIf { it.isNotBlank() && it in option.variants }

    private fun composerMetadataLabel(composer: SessionChatComposerState): String =
        listOfNotNull(
            composer.selectedMode,
            composer.selectedModel?.modelLabel,
            composer.selectedVariant,
            formatSentTime(System.currentTimeMillis()),
        ).joinToString(" · ")

    private fun agentMode(mode: String): String =
        when (mode) {
            "Plan" -> "plan"
            else -> "build"
        }

    private fun updateComposer(transform: (SessionChatComposerState) -> SessionChatComposerState) {
        _state.update { it.copy(composer = transform(it.composer)) }
    }

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

    private fun logSendFailure(throwable: Throwable) {
        runCatching { Log.e(TAG, "sendPromptAsync failed", throwable) }
    }

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "SessionChatComposer"
        val AvailableModes = listOf("Build", "Plan")
        const val ModelFilterThreshold = 10
        private val SentTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    }
}
