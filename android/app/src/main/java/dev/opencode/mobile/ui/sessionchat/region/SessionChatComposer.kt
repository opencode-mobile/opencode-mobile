package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.PromptModelSelection
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.LatestModelHint
import dev.opencode.mobile.ui.sessionchat.ModelSelectionHistoryHints
import dev.opencode.mobile.ui.sessionchat.ModelSelectionHistorySource
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
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
    private var newSessionPreference: SessionModelSelection? = null
    private var initialSelectionState = InitialSelectionState.LoadingCache
    private var latestTimelineState: SessionChatTimelineRegionState? = null
    private var latestHistoryHints: ModelSelectionHistoryHints? = null
    private var lastSelectionTimestamp = 0L

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId &&
            boundWorktree == worktree &&
            _state.value.composer.modelOptions
                .isNotEmpty()
        ) {
            return
        }
        boundSessionId = sessionId
        boundWorktree = worktree
        loadedCatalog = null
        cachedSelection = null
        newSessionPreference = null
        initialSelectionState = InitialSelectionState.LoadingCache
        latestTimelineState = null
        latestHistoryHints = null
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
                if (boundWorktree == worktree) {
                    updateComposer {
                        it.copy(
                            isModelSelectionReady = false,
                            modelCatalogError = "Unable to load models. Tap Model to retry.",
                            modelNotice = "Unable to load models. Tap Model to retry.",
                        )
                    }
                }
            }
        }
    }

    fun tryApplyInitialSelection(
        timelineState: SessionChatTimelineRegionState,
        historyHints: ModelSelectionHistoryHints,
    ) {
        latestTimelineState = timelineState
        latestHistoryHints = historyHints
        applyInitialSelectionIfReady()
    }

    private fun applyInitialSelectionIfReady() {
        if (initialSelectionState == InitialSelectionState.Resolved ||
            initialSelectionState == InitialSelectionState.FailedWithFallback
        ) {
            return
        }
        if (initialSelectionState == InitialSelectionState.LoadingCache) return
        val catalog = loadedCatalog ?: return
        if (catalog.options.isEmpty()) return
        val timelineState = latestTimelineState ?: return
        val historyHints = latestHistoryHints ?: return

        when (timelineState.timeline) {
            SessionChatTimelineState.Loading -> return
            SessionChatTimelineState.Error,
            SessionChatTimelineState.Empty,
            SessionChatTimelineState.Ready,
            -> resolveAndPublishInitialSelection(catalog, historyHints)
        }
    }

    private fun resolveAndPublishInitialSelection(
        catalog: ModelSelectionCatalog,
        historyHints: ModelSelectionHistoryHints,
    ) {
        val staleCached = staleCachedSelection()
        val validCached = cachedSelection?.takeIf { staleCached == null }
        val validUserHint = historyHints.user?.takeIf { catalog.option(it.providerId, it.modelId) != null }
        val validAssistantHint = historyHints.assistant?.takeIf { catalog.option(it.providerId, it.modelId) != null }
        val validPreference = newSessionPreference?.takeIf { catalog.option(it.providerId, it.modelId) != null }
        val chosen = chooseInitialSelection(validCached, validUserHint, validAssistantHint, validPreference)
        val option =
            when (chosen) {
                is InitialSelection.Cached -> catalog.option(chosen.value.providerId, chosen.value.modelId)
                is InitialSelection.History -> catalog.option(chosen.value.providerId, chosen.value.modelId)
                is InitialSelection.Preference -> catalog.option(chosen.value.providerId, chosen.value.modelId)
                null -> null
            } ?: catalog.serverDefault ?: catalog.options.first()
        val rememberedVariant =
            when (chosen) {
                is InitialSelection.Cached -> chosen.value.variant
                is InitialSelection.History -> chosen.value.variant
                is InitialSelection.Preference -> chosen.value.variant
                null -> null
            }
        val variant = resolvedVariant(rememberedVariant, option)
        val variantNotice =
            rememberedVariant
                ?.takeIf { it.isNotBlank() && it !in option.variants }
                ?.let { "Remembered Variant is unavailable. Using Default." }
        initialSelectionState =
            if (initialSelectionState == InitialSelectionState.WaitingForFallback) {
                InitialSelectionState.FailedWithFallback
            } else {
                InitialSelectionState.Resolved
            }
        _state.update { state ->
            state.copy(
                composer =
                    state.composer.copy(
                        modelOptions = catalog.options,
                        selectedModel = ModelSelection(option, variant),
                        selectedVariant = variant,
                        isModelSelectionReady = true,
                        modelCatalogError = null,
                        modelNotice =
                            when {
                                staleCached != null -> "Selected model is unavailable. Using ${option.modelLabel}."
                                variantNotice != null -> variantNotice
                                else -> null
                            },
                    ),
            )
        }
        logI("modelSelection source=${chosen?.sourceName ?: if (catalog.serverDefault != null) "server-default" else "first-usable"}")
        if (chosen !is InitialSelection.Cached && staleCached == null) {
            persistCurrentSelection(updatePreference = false)
        }
    }

    private fun chooseInitialSelection(
        cached: SessionModelSelection?,
        userHint: LatestModelHint?,
        assistantHint: LatestModelHint?,
        preference: SessionModelSelection?,
    ): InitialSelection? {
        if (userHint != null) {
            if (cached == null) return InitialSelection.History(userHint)
            if (cached.savedAtEpochMs == null) return InitialSelection.History(userHint)
            val historyTime = userHint.sentAtEpochMs ?: return InitialSelection.Cached(cached)
            return if (historyTime > cached.savedAtEpochMs) {
                InitialSelection.History(userHint)
            } else {
                InitialSelection.Cached(cached)
            }
        }
        if (cached != null) return InitialSelection.Cached(cached)
        return assistantHint?.let(InitialSelection::History) ?: preference?.let(InitialSelection::Preference)
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
        persistCurrentSelection(updatePreference = true)
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
        persistCurrentSelection(updatePreference = true)
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
        if (prompt.isEmpty() || _state.value.isSending || !composer.isModelSelectionReady) return

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
                persistCurrentSelection(updatePreference = false)
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
        val profile =
            connectionSessionCoordinator.currentProfile() ?: run {
                initialSelectionState = InitialSelectionState.WaitingForInputs
                applyInitialSelectionIfReady()
                return
            }
        val identity = ModelSelectionConnectionIdentity(profile.baseUrl, profile.username)
        scope.launch {
            val loaded =
                withContext(ioDispatcher) {
                    val session =
                        runCatching {
                            sessionModelSelectionStore.loadSession(identity, worktree, sessionId)
                        }
                    val preference =
                        runCatching {
                            sessionModelSelectionStore.loadNewSessionPreference(identity, worktree)
                        }
                    session.exceptionOrNull()?.let { logW("loadSessionSelection failed error=${it.message ?: it::class.simpleName}") }
                    preference.exceptionOrNull()?.let {
                        logW(
                            "loadNewSessionPreference failed error=${it.message ?: it::class.simpleName}",
                        )
                    }
                    LoadedSelections(
                        session = session.getOrNull(),
                        preference = preference.getOrNull(),
                        hadStorageFailure = session.isFailure || preference.isFailure,
                    )
                }
            cachedSelection = loaded.session
            newSessionPreference = loaded.preference
            initialSelectionState =
                if (loaded.hadStorageFailure) InitialSelectionState.WaitingForFallback else InitialSelectionState.WaitingForInputs
            applyInitialSelectionIfReady()
        }
    }

    private fun persistCurrentSelection(updatePreference: Boolean) {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        val profile = connectionSessionCoordinator.currentProfile() ?: return
        val identity = ModelSelectionConnectionIdentity(profile.baseUrl, profile.username)
        val selected = _state.value.composer.selectedModel ?: return
        val variant = _state.value.composer.selectedVariant
        val timestamp = maxOf(System.currentTimeMillis(), lastSelectionTimestamp + 1)
        lastSelectionTimestamp = timestamp
        scope.launch {
            val selection =
                SessionModelSelection(
                    providerId = selected.option.providerId,
                    modelId = selected.option.modelId,
                    variant = variant,
                    savedAtEpochMs = timestamp,
                )
            runCatching {
                withContext(ioDispatcher) {
                    sessionModelSelectionStore.saveSession(
                        connectionIdentity = identity,
                        worktree = worktree,
                        sessionId = sessionId,
                        selection = selection,
                    )
                    if (updatePreference) {
                        sessionModelSelectionStore.saveNewSessionPreference(identity, worktree, selection)
                    }
                }
            }.onFailure { logW("saveModelSelection failed error=${it.message ?: it::class.simpleName}") }
            cachedSelection = selection
        }
    }

    private data class LoadedSelections(
        val session: SessionModelSelection?,
        val preference: SessionModelSelection?,
        val hadStorageFailure: Boolean,
    )

    private enum class InitialSelectionState {
        LoadingCache,
        WaitingForInputs,
        WaitingForFallback,
        Resolved,
        FailedWithFallback,
    }

    private fun staleCachedSelection(): SessionModelSelection? {
        val cached = cachedSelection ?: return null
        val isAvailable =
            _state.value.composer.modelOptions.any {
                it.providerId == cached.providerId && it.modelId == cached.modelId
            }
        return if (isAvailable) null else cached
    }

    private fun isCurrentBinding(
        sessionId: String,
        worktree: String,
    ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

    private fun applyCatalog(catalog: ModelSelectionCatalog) {
        if (catalog.options.isEmpty()) {
            updateComposer {
                it.copy(
                    isModelSelectionReady = false,
                    modelCatalogError = "No usable models are available. Tap Model to retry.",
                    modelNotice = "No usable models are available. Tap Model to retry.",
                )
            }
            return
        }
        loadedCatalog = catalog
        updateComposer { it.copy(modelOptions = catalog.options, modelCatalogError = null) }
        applyInitialSelectionIfReady()
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

    private sealed interface InitialSelection {
        val sourceName: String

        data class Cached(
            val value: SessionModelSelection,
        ) : InitialSelection {
            override val sourceName = "session-cache"
        }

        data class History(
            val value: LatestModelHint,
        ) : InitialSelection {
            override val sourceName =
                if (value.source == ModelSelectionHistorySource.User) "user-history" else "assistant-history"
        }

        data class Preference(
            val value: SessionModelSelection,
        ) : InitialSelection {
            override val sourceName = "new-session-preference"
        }
    }
}
