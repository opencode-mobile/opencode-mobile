package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.agent.ComposerMentionRouting
import dev.opencode.mobile.domain.agent.ComposerSuggestionMode
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.command.CommandError
import dev.opencode.mobile.domain.command.CommandInvocationCollapser
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.command.CommandRunOptions
import dev.opencode.mobile.domain.command.ComposerSubmitIntent
import dev.opencode.mobile.domain.command.ComposerSubmitRouting
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.engine.SessionCapabilities
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.AttachmentLimits
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.PromptModelSelection
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.PromptSendProgress
import dev.opencode.mobile.domain.message.PromptSendProgressReporter
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.ModelVariants
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.t3.T3Errors
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import dev.opencode.mobile.ui.sessionchat.AttachmentPresentationMapper
import dev.opencode.mobile.ui.sessionchat.LatestAgentHint
import dev.opencode.mobile.ui.sessionchat.LatestModelHint
import dev.opencode.mobile.ui.sessionchat.ModelSelectionHistoryHints
import dev.opencode.mobile.ui.sessionchat.ModelSelectionHistorySource
import dev.opencode.mobile.ui.sessionchat.PresentationBlockId
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.knownChildSessionIds
import dev.opencode.mobile.ui.sessionchat.toAtSuggestion
import dev.opencode.mobile.ui.sessionchat.toSlashSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
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
    private val modelCatalogStore: ModelCatalogStore,
    private val commandRepository: CommandRepository,
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository,
    private val connectionSessionCoordinator: ConnectionSessionCoordinator,
    private val sessionModelSelectionStore: SessionModelSelectionStore,
    private val composerDraftStore: ComposerDraftStore,
    private val scope: CoroutineScope,
    private val foregroundEventStream: ForegroundEventStream? = null,
) {
    var ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    var capabilities: SessionCapabilities = SessionCapabilities.OpenCode
    var allowsMentions: Boolean = true
    var allowsSlashCommands: Boolean = true

    /** Backoff for refetching a catalog that came back empty (a server still loading providers). */
    var catalogRetryDelaysMs: List<Long> = DEFAULT_CATALOG_RETRY_DELAYS_MS

    private val _state = MutableStateFlow(SessionChatComposerRegionState())
    val state: StateFlow<SessionChatComposerRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var loadedCatalog: ModelSelectionCatalog? = null
    private var loadedSelectableAgents = false
    private var cachedSelection: SessionModelSelection? = null

    /** The selection the server records on the session (T3); outranks every local hint. */
    private var sessionSelection: SessionModelSelection? = null
    private var newSessionPreference: SessionModelSelection? = null
    private var initialSelectionState = InitialSelectionState.LoadingCache
    private var latestTimelineState: SessionChatTimelineRegionState? = null
    private var latestHistoryHints: ModelSelectionHistoryHints? = null
    private var lastSelectionTimestamp = 0L
    private var draftRevision = 0
    private var draftLoadGeneration = 0
    private var persistDraftJob: Job? = null
    private var nextAttachmentRevision = 1
    private val attachmentRevisions = mutableMapOf<String, Int>()
    private var catalogLoadGeneration = 0
    private var catalogEventJob: Job? = null
    private var catalogRetryJob: Job? = null

    /** The chip shows a selection seeded from [ModelCatalogStore] that the gate has not reconciled yet. */
    private var provisionalSelection = false

    /** The user picked a model, variant, or agent while the selection was provisional; reconcile keeps it. */
    private var pickedDuringProvisional = false

    /** The catalog came from [ModelCatalogStore]; a failed or empty refresh must not hide it. */
    private var catalogSeededFromCache = false

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        val sameIdentity = boundSessionId == sessionId && boundWorktree == worktree
        if (sameIdentity) {
            if (_state.value.composer.modelOptions
                    .isEmpty()
            ) {
                loadCachedSelection()
                loadServerCommands()
                loadMentionableAgents()
                loadSelectableAgents()
            }
            return
        }
        val previousSessionId = boundSessionId
        val previousWorktree = boundWorktree
        val preserve =
            if (previousSessionId != null &&
                OptimisticSessionCreate.isPlaceholder(previousSessionId) &&
                !OptimisticSessionCreate.isPlaceholder(sessionId)
            ) {
                currentDraft()
            } else {
                null
            }
        if (previousSessionId != null && previousWorktree != null) {
            persistDraftJob?.cancel()
            val snapshot = currentDraft()
            scope.launch { persistDraft(previousSessionId, previousWorktree, snapshot) }
        }
        discardStoredAttachments(_state.value.composer.attachments)
        boundSessionId = sessionId
        boundWorktree = worktree
        loadedCatalog = null
        loadedSelectableAgents = false
        cachedSelection = null
        sessionSelection = null
        newSessionPreference = null
        initialSelectionState = InitialSelectionState.LoadingCache
        latestTimelineState = null
        latestHistoryHints = null
        provisionalSelection = false
        pickedDuringProvisional = false
        catalogSeededFromCache = false
        draftRevision = 0
        nextAttachmentRevision = 1
        attachmentRevisions.clear()
        _state.value =
            SessionChatComposerRegionState(
                composer =
                    SessionChatComposerState(
                        followUpDelivery = SessionChatDisplayPreferences.followUpDelivery,
                    ),
            )
        logI("bind session=$sessionId worktree=$worktree")
        seedFromCatalogStore(sessionId, worktree)
        loadCachedSelection()
        loadServerCommands()
        loadMentionableAgents()
        loadSelectableAgents(force = true)
        applyOrLoadDraft(preserve)
        startCatalogEventRefresh()
    }

    /**
     * Shows the store's catalog and agents at once, and for a session with a remembered selection
     * also publishes that selection, so switching back to it never shows "Loading model…". The
     * gate reconciles it later against the persisted selection and history. A session without one
     * keeps waiting for the gate: a guessed default would flip once history arrives.
     */
    private fun seedFromCatalogStore(
        sessionId: String,
        worktree: String,
    ) {
        // A session still being created keeps waiting; its promotion rebinds with the seeded selection.
        if (OptimisticSessionCreate.isPlaceholder(sessionId)) return
        val entry = modelCatalogStore.peek(worktree) ?: return
        val catalog = entry.catalog?.takeIf { it.options.isNotEmpty() } ?: return
        val agents = entry.selectableAgents?.ifEmpty { SelectableAgent.Fallback }
        loadedCatalog = catalog
        catalogSeededFromCache = true
        if (agents != null) loadedSelectableAgents = true
        updateComposer { composer ->
            val selectableAgents = agents ?: composer.selectableAgents
            composer.copy(
                modelOptions = catalog.options,
                selectableAgents = selectableAgents,
                selectedMode = resolveSelectedAgentId(composer.selectedMode, selectableAgents),
            )
        }
        val remembered = modelCatalogStore.lastSelection(worktree, sessionId) ?: return
        val option = catalog.option(remembered.providerId, remembered.modelId) ?: return
        val variant = resolvedVariant(remembered.variant, option)
        provisionalSelection = true
        updateComposer { composer ->
            composer.copy(
                selectedModel = ModelSelection(option, variant),
                selectedVariant = variant,
                selectedMode = resolveSelectedAgentId(remembered.agentId ?: composer.selectedMode, composer.selectableAgents),
                isModelSelectionReady = true,
            )
        }
        logI("modelSelection provisional source=memory")
    }

    /**
     * Refetches the model catalog when the server announces a provider or model change, or
     * when the stream reconnects (the server may have restarted and announced its catalog
     * while the stream was down), so an empty answer recovers without a manual retry.
     */
    private fun startCatalogEventRefresh() {
        val stream = foregroundEventStream ?: return
        if (catalogEventJob?.isActive == true) return
        catalogEventJob =
            scope.launch {
                stream.events
                    .filter { it.type in CATALOG_UPDATE_EVENTS || it.type in CATALOG_RECONNECT_EVENTS }
                    .collect { event ->
                        logI("model catalog refresh reason=${event.type}")
                        loadCatalog(keepLoadedCatalogOnFailure = true, force = true)
                    }
            }
    }

    fun loadServerCommands(force: Boolean = false) {
        if (!allowsSlashCommands) return
        val worktree = boundWorktree ?: return
        if (!force && _state.value.serverCommands.isNotEmpty()) return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    commandRepository.listCommands(directory = worktree)
                }
            }.onSuccess { commands ->
                if (boundWorktree != worktree) return@onSuccess
                _state.update { state ->
                    state.copy(serverCommands = commands).withComposerSuggestions(
                        text = state.composer.text,
                        cursor = state.composer.cursor,
                        mentions = state.composer.agentMentions,
                    )
                }
            }.onFailure { throwable ->
                if (boundWorktree != worktree) return@onFailure
                logW("server command catalog failed: ${throwable.message}")
                // Keep any previously loaded catalog on transient refresh failures.
                _state.update { state ->
                    state.withComposerSuggestions(
                        text = state.composer.text,
                        cursor = state.composer.cursor,
                        mentions = state.composer.agentMentions,
                    )
                }
            }
        }
    }

    fun loadSelectableAgents(force: Boolean = false) {
        // Selectable agents are an OpenCode catalog; an engine without agents has none to fetch.
        if (!capabilities.showsAgent) {
            loadedSelectableAgents = true
            applyInitialSelectionIfReady()
            return
        }
        val worktree = boundWorktree ?: return
        if (!force && loadedSelectableAgents) return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    modelCatalogStore.refreshSelectableAgents(worktree)
                }
            }.onSuccess { agents ->
                if (boundWorktree != worktree) return@onSuccess
                loadedSelectableAgents = true
                applySelectableAgents(agents)
            }.onFailure { throwable ->
                if (boundWorktree != worktree) return@onFailure
                logW("selectable agent catalog failed: ${throwable.message}")
                loadedSelectableAgents = true
                applyInitialSelectionIfReady()
            }
        }
    }

    fun loadMentionableAgents(force: Boolean = false) {
        if (!allowsMentions) return
        val worktree = boundWorktree ?: return
        if (!force && _state.value.mentionableAgents.isNotEmpty()) return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    agentRepository.listMentionableAgents(directory = worktree)
                }
            }.onSuccess { agents ->
                if (boundWorktree != worktree) return@onSuccess
                _state.update { state ->
                    state.copy(mentionableAgents = agents).withComposerSuggestions(
                        text = state.composer.text,
                        cursor = state.composer.cursor,
                        mentions = state.composer.agentMentions,
                    )
                }
            }.onFailure { throwable ->
                if (boundWorktree != worktree) return@onFailure
                logW("mentionable agent catalog failed: ${throwable.message}")
                _state.update { state ->
                    state.withComposerSuggestions(
                        text = state.composer.text,
                        cursor = state.composer.cursor,
                        mentions = state.composer.agentMentions,
                    )
                }
            }
        }
    }

    fun loadCatalog() {
        if (!capabilities.showsComposerModel) {
            updateComposer {
                it.copy(
                    isModelSelectionReady = true,
                    modelCatalogError = null,
                    modelNotice = null,
                )
            }
            return
        }
        loadCatalog(keepLoadedCatalogOnFailure = false)
    }

    /**
     * [emptyRetryAttempt] is non-zero only for a backoff retry of an empty answer. [force] asks
     * the store for an answer newer than a fetch already in flight (server catalog events).
     */
    private fun loadCatalog(
        keepLoadedCatalogOnFailure: Boolean,
        emptyRetryAttempt: Int = 0,
        force: Boolean = false,
    ) {
        val worktree = boundWorktree ?: return
        val keepLoaded = keepLoadedCatalogOnFailure || catalogSeededFromCache
        val generation = ++catalogLoadGeneration
        if (emptyRetryAttempt == 0) {
            catalogRetryJob?.cancel()
            catalogRetryJob = null
        }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    modelCatalogStore.refreshCatalog(worktree, force = force)
                }
            }.onSuccess { catalog ->
                // An older request must not overwrite the answer to a newer one.
                if (generation != catalogLoadGeneration) return@onSuccess
                if (boundWorktree == worktree) {
                    logI(
                        "loadCatalog options=${catalog.options.size} " +
                            "default=${catalog.serverDefault?.modelLabel ?: "-"}",
                    )
                    if (catalog.options.isEmpty()) {
                        scheduleEmptyCatalogRetry(emptyRetryAttempt)
                        // A server still loading must not hide a catalog this session already has.
                        if (keepLoaded && loadedCatalog?.options?.isNotEmpty() == true) return@onSuccess
                    }
                    applyCatalog(catalog)
                }
            }.onFailure { throwable ->
                if (generation != catalogLoadGeneration) return@onFailure
                logW("loadCatalog failed error=${throwable.message ?: throwable::class.simpleName}")
                if (emptyRetryAttempt > 0) scheduleEmptyCatalogRetry(emptyRetryAttempt)
                if (keepLoaded && loadedCatalog?.options?.isNotEmpty() == true) return@onFailure
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

    private fun scheduleEmptyCatalogRetry(attempt: Int) {
        val delayMs = catalogRetryDelaysMs.getOrNull(attempt) ?: return
        logI("model catalog empty; retry attempt=${attempt + 1} delayMs=$delayMs")
        catalogRetryJob =
            scope.launch {
                delay(delayMs)
                loadCatalog(keepLoadedCatalogOnFailure = true, emptyRetryAttempt = attempt + 1)
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
        if (!loadedSelectableAgents) return
        val catalog = loadedCatalog ?: return
        if (catalog.options.isEmpty()) return
        val timelineState = latestTimelineState ?: return
        val historyHints = latestHistoryHints ?: return

        when (timelineState.timeline) {
            SessionChatTimelineState.Creating,
            SessionChatTimelineState.Loading,
            -> return
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
        if (provisionalSelection) {
            provisionalSelection = false
            if (pickedDuringProvisional) {
                initialSelectionState = resolvedInitialSelectionState()
                logI("modelSelection reconcile kept user pick")
                return
            }
        }
        val authoritative = sessionSelection?.takeIf { catalog.option(it.providerId, it.modelId) != null }
        val staleCached = if (authoritative != null) null else staleCachedSelection()
        val validCached = cachedSelection?.takeIf { staleCached == null }
        val validUserHint = historyHints.user?.takeIf { catalog.option(it.providerId, it.modelId) != null }
        val validAssistantHint = historyHints.assistant?.takeIf { catalog.option(it.providerId, it.modelId) != null }
        val validPreference = newSessionPreference?.takeIf { catalog.option(it.providerId, it.modelId) != null }
        val chosen =
            authoritative?.let { InitialSelection.Session(it) }
                ?: chooseInitialSelection(validCached, validUserHint, validAssistantHint, validPreference)
        val option =
            when (chosen) {
                is InitialSelection.Cached -> catalog.option(chosen.value.providerId, chosen.value.modelId)
                is InitialSelection.History -> catalog.option(chosen.value.providerId, chosen.value.modelId)
                is InitialSelection.Preference -> catalog.option(chosen.value.providerId, chosen.value.modelId)
                is InitialSelection.Session -> catalog.option(chosen.value.providerId, chosen.value.modelId)
                null -> null
            } ?: catalog.serverDefault ?: catalog.options.first()
        val rememberedVariant =
            when (chosen) {
                is InitialSelection.Cached -> chosen.value.variant
                is InitialSelection.History -> chosen.value.variant
                is InitialSelection.Preference -> chosen.value.variant
                is InitialSelection.Session -> cachedSelection?.variant
                null -> null
            }
        val variant = resolvedVariant(rememberedVariant, option)
        val variantNotice =
            rememberedVariant
                ?.takeIf { !ModelVariants.isDefault(it, option.variants) && it !in option.variants }
                ?.let { "Remembered Variant is unavailable. Using Default." }
        initialSelectionState = resolvedInitialSelectionState()
        val droppedForModel =
            _state.value.composer.attachments
                .filterNot { option.acceptsAttachmentKind(it.kind) }
        droppedForModel.forEach { attachmentRevisions.remove(it.id) }
        _state.update { state ->
            val keptAttachments = state.composer.attachments.filter { option.acceptsAttachmentKind(it.kind) }
            state.copy(
                composer =
                    state.composer.copy(
                        modelOptions = catalog.options,
                        selectedModel = ModelSelection(option, variant),
                        selectedVariant = variant,
                        selectedMode =
                            resolveSelectedAgentId(
                                chooseInitialAgent(
                                    cached = validCached?.agentId,
                                    cachedAt = validCached?.savedAtEpochMs,
                                    userHint = historyHints.userAgent,
                                    assistantHint = historyHints.assistantAgent,
                                ) ?: state.composer.selectedMode,
                                state.composer.selectableAgents,
                            ),
                        attachments = keptAttachments,
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
        discardStoredAttachments(droppedForModel)
        logI("modelSelection source=${chosen?.sourceName ?: if (catalog.serverDefault != null) "server-default" else "first-usable"}")
        if (chosen !is InitialSelection.Cached && staleCached == null) {
            persistCurrentSelection(updatePreference = false)
        } else if (chosen is InitialSelection.Cached) {
            boundSessionId?.let { modelCatalogStore.rememberSelection(boundWorktree, it, chosen.value) }
        }
    }

    private fun resolvedInitialSelectionState(): InitialSelectionState =
        if (initialSelectionState == InitialSelectionState.WaitingForFallback) {
            InitialSelectionState.FailedWithFallback
        } else {
            InitialSelectionState.Resolved
        }

    /**
     * Pins the chip to the model the server records on the session (T3 threads keep
     * their own selection; sends never carry the composer's). Applies now when the
     * catalogue is loaded, else when the initial selection resolves.
     */
    fun applySessionSelection(
        providerId: String?,
        modelId: String?,
    ) {
        if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return
        sessionSelection = SessionModelSelection(providerId = providerId, modelId = modelId)
        if (initialSelectionState != InitialSelectionState.Resolved &&
            initialSelectionState != InitialSelectionState.FailedWithFallback
        ) {
            applyInitialSelectionIfReady()
            return
        }
        val option = loadedCatalog?.option(providerId, modelId) ?: return
        if (_state.value.composer.selectedModel
                ?.option == option
        ) {
            return
        }
        updateComposer { composer ->
            composer.copy(
                selectedModel = ModelSelection(option, null),
                selectedVariant = null,
                modelNotice = null,
            )
        }
        persistCurrentSelection(updatePreference = false)
    }

    /** Shows [notice] under the composer until the next model change. */
    fun showModelNotice(notice: String) {
        updateComposer { it.copy(modelNotice = notice) }
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

    private fun chooseInitialAgent(
        cached: String?,
        cachedAt: Long?,
        userHint: LatestAgentHint?,
        assistantHint: LatestAgentHint?,
    ): String? {
        if (userHint != null) {
            if (cached == null || cachedAt == null) return userHint.mode
            val historyTime = userHint.sentAtEpochMs ?: return cached
            return if (historyTime > cachedAt) userHint.mode else cached
        }
        if (cached != null) return cached
        return assistantHint?.mode
    }

    fun updateComposerText(
        text: String,
        cursor: Int = text.length,
    ) {
        val previous = _state.value.composer
        if (text == previous.text) {
            val mode =
                suggestionMode(
                    text = text,
                    cursor = cursor,
                    mentions = previous.agentMentions,
                )
            when (mode) {
                is ComposerSuggestionMode.At -> {
                    if (_state.value.mentionableAgents.isEmpty()) {
                        loadMentionableAgents(force = true)
                    }
                }
                is ComposerSuggestionMode.Slash -> {
                    if (_state.value.serverCommands.isEmpty()) {
                        loadServerCommands(force = true)
                    }
                }
                ComposerSuggestionMode.None -> Unit
            }
            _state.update { state ->
                state
                    .copy(composer = state.composer.copy(cursor = cursor.coerceIn(0, text.length)))
                    .withComposerSuggestions(
                        text = text,
                        cursor = cursor.coerceIn(0, text.length),
                        mentions = previous.agentMentions,
                    )
            }
            return
        }

        draftRevision += 1
        val reconciled =
            ComposerMentionRouting.reconcileAfterEdit(
                previousText = previous.text,
                previousMentions = previous.agentMentions,
                proposedText = text,
                proposedCursor = cursor,
            )
        val mode =
            suggestionMode(
                text = reconciled.text,
                cursor = reconciled.cursor,
                mentions = reconciled.mentions,
            )
        when (mode) {
            is ComposerSuggestionMode.At -> {
                if (_state.value.mentionableAgents.isEmpty()) {
                    loadMentionableAgents(force = true)
                }
            }
            is ComposerSuggestionMode.Slash -> {
                if (_state.value.serverCommands.isEmpty()) {
                    loadServerCommands(force = true)
                }
            }
            ComposerSuggestionMode.None -> Unit
        }
        val selectionChanged = reconciled.text != text || reconciled.cursor != cursor
        _state.update { state ->
            state
                .copy(
                    composer =
                        state.composer.copy(
                            text = reconciled.text,
                            agentMentions = reconciled.mentions,
                            cursor = reconciled.cursor,
                            selectionRevision =
                                if (selectionChanged) {
                                    state.composer.selectionRevision + 1
                                } else {
                                    state.composer.selectionRevision
                                },
                        ),
                ).withComposerSuggestions(
                    text = reconciled.text,
                    cursor = reconciled.cursor,
                    mentions = reconciled.mentions,
                )
        }
        schedulePersistDraft()
    }

    fun dismissSuggestions() {
        _state.update {
            it.copy(
                slashSuggestionsVisible = false,
                slashSuggestions = emptyList(),
                atSuggestionsVisible = false,
                atSuggestions = emptyList(),
            )
        }
    }

    fun selectSlashSuggestion(commandName: String) {
        val inserted = ComposerSubmitRouting.insertSelection(commandName)
        updateComposerText(inserted, cursor = inserted.length)
    }

    fun selectAtSuggestion(agentName: String) {
        val composer = _state.value.composer
        val result =
            ComposerMentionRouting.insertMention(
                text = composer.text,
                cursor = composer.cursor,
                agentName = agentName,
                mentionId = UUID.randomUUID().toString(),
                existingMentions = composer.agentMentions,
            )
        draftRevision += 1
        _state.update { state ->
            state.copy(
                composer =
                    state.composer.copy(
                        text = result.text,
                        agentMentions = result.mentions,
                        cursor = result.cursor,
                        selectionRevision = state.composer.selectionRevision + 1,
                    ),
                // Close after select; `@name` would otherwise still match the at-trigger.
                atSuggestionsVisible = false,
                atSuggestions = emptyList(),
                slashSuggestionsVisible = false,
                slashSuggestions = emptyList(),
            )
        }
        schedulePersistDraft()
    }

    fun addAttachments(attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        val option =
            _state.value.composer.selectedModel
                ?.option
        val accepted = attachments.filter { option?.acceptsAttachmentKind(it.kind) != false }
        val rejected = attachments.filter { option?.acceptsAttachmentKind(it.kind) == false }
        if (accepted.isNotEmpty()) {
            updateComposer { composer ->
                val ordered = composer.attachments.toMutableList()
                accepted.forEach { attachment ->
                    attachmentRevisions[attachment.id] = nextAttachmentRevision++
                    val index = ordered.indexOfFirst { it.id == attachment.id }
                    if (index >= 0) {
                        val previous = ordered[index]
                        if (previous.localPath != attachment.localPath) {
                            AttachmentLimits.discard(previous.localPath)
                        }
                        ordered[index] = attachment
                    } else {
                        ordered.add(attachment)
                    }
                }
                composer.copy(attachments = ordered)
            }
        }
        if (accepted.isNotEmpty()) {
            DiagnosticLog.info(
                DiagnosticLogArea.Chat,
                "attachments selected accepted=${accepted.size} rejected=${rejected.size}",
            )
        }
        if (rejected.isNotEmpty()) {
            discardStoredAttachments(rejected)
            reportAttachmentError(unsupportedAttachmentMessage(rejected))
        } else if (accepted.isNotEmpty()) {
            _state.update { it.copy(errorMessage = null) }
        }
    }

    fun removeAttachment(id: String) {
        val removed =
            _state.value.composer.attachments
                .filter { it.id == id }
        attachmentRevisions.remove(id)
        updateComposer { composer -> composer.copy(attachments = composer.attachments.filterNot { it.id == id }) }
        discardStoredAttachments(removed)
    }

    fun reportAttachmentError(message: String) {
        _state.update { it.copy(errorMessage = message) }
        DiagnosticLog.warning(DiagnosticLogArea.Chat, "attachment rejected error=$message")
    }

    fun selectModel(option: ModelSelectionOption) {
        val dropped =
            _state.value.composer.attachments
                .filterNot { option.acceptsAttachmentKind(it.kind) }
        dropped.forEach { attachmentRevisions.remove(it.id) }
        updateComposer { composer ->
            val previousVariant = composer.selectedVariant
            val variant = previousVariant?.takeIf { it in option.variants }
            val keptAttachments = composer.attachments.filter { option.acceptsAttachmentKind(it.kind) }
            composer.copy(
                selectedModel = ModelSelection(option, variant),
                selectedVariant = variant,
                attachments = keptAttachments,
                modelNotice = null,
            )
        }
        persistCurrentSelection(updatePreference = true)
        discardStoredAttachments(dropped)
    }

    fun selectMode(mode: String) {
        val match =
            _state.value.composer.selectableAgents
                .firstOrNull { it.id.equals(mode, ignoreCase = true) }
                ?: return
        updateComposer { it.copy(selectedMode = match.id) }
        persistCurrentSelection(updatePreference = false)
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
        draftRevision += 1
        updateComposer {
            it.copy(
                text = text,
                agentMentions = emptyList(),
                cursor = text.length,
                selectionRevision = it.selectionRevision + 1,
            )
        }
        schedulePersistDraft()
    }

    fun toggleFollowUp(): FollowUpDelivery {
        val next =
            _state.value.composer.followUpDelivery
                .toggled()
        updateComposer { it.copy(followUpDelivery = next) }
        _state.update { it.copy(followUpNotice = next.switchedSnackbar) }
        scope.launch {
            delay(2_200)
            _state.update { current ->
                if (current.followUpNotice == next.switchedSnackbar) {
                    current.copy(followUpNotice = null)
                } else {
                    current
                }
            }
        }
        return next
    }

    fun sendPrompt(
        sessionId: String,
        worktree: String,
        onOptimisticRows: suspend (List<SessionChatMessageItem>) -> Unit,
        onSendAccepted: suspend () -> Unit,
        onSendFailed: (optimisticId: String, error: String) -> Unit,
        isSessionBusy: Boolean,
        supportsFollowUp: Boolean,
        holdUntil: suspend () -> Boolean = { true },
        serverAlreadyAccepted: () -> Boolean = { false },
    ) {
        val composer = _state.value.composer
        val submittedText = composer.text
        val submittedMentions = composer.agentMentions
        val mentionPrepared =
            if (allowsMentions) {
                composer.text to composer.agentMentions
            } else {
                ComposerMentionRouting.withoutMentionTokens(composer.text, composer.agentMentions) to emptyList()
            }
        val (prompt, mentionsForSend) =
            ComposerMentionRouting.trimForSend(mentionPrepared.first, mentionPrepared.second)
        val attachments = composer.attachments
        val submittedDraftRevision = draftRevision
        val submittedAttachmentRevisions =
            attachments
                .mapNotNull { attachment ->
                    attachmentRevisions[attachment.id]?.let { attachment.id to it }
                }.toMap()
        val selectedModel = composer.selectedModel
        if (capabilities.requiresComposerModel && selectedModel == null) return
        if (OptimisticSessionCreate.isPlaceholder(sessionId)) return
        if ((prompt.isEmpty() && attachments.isEmpty()) || _state.value.isSending) return
        if (capabilities.requiresComposerModel && !composer.isModelSelectionReady) return
        if (
            ComposerSubmitRouting.resolve(
                text = prompt,
                commands = emptyList(),
                allowsSlashCommands = allowsSlashCommands,
            ) is ComposerSubmitIntent.Ignored
        ) {
            return
        }

        val collapsedDisplay =
            CommandInvocationCollapser.collapse(
                text = prompt,
                commands = _state.value.serverCommands,
            )
        val displayText = collapsedDisplay?.text ?: prompt
        scope.launch {
            val display =
                withContext(ioDispatcher) {
                    messageRepository.prepareOptimisticUserMessage(
                        id = "pending-${UUID.randomUUID()}",
                        text = displayText,
                        attachments = attachments,
                    )
                }
            // Keep collapsed slash tokens as plain text so `[/name]` can use
            // syntax-type highlight instead of markdown paint.
            val optimisticMarkdown = display.renderedMarkdown.takeUnless { collapsedDisplay != null }
            val enriched =
                attachments.map(AttachmentPresentationMapper::toItem).let { attachmentItems ->
                    listOf(
                        SessionChatMessageItem(
                            id = display.id,
                            role = dev.opencode.mobile.domain.message.MessageRole.User,
                            text = display.text,
                            metadataLabel = composerMetadataLabel(composer),
                            pendingDelivery =
                                composer.followUpDelivery.takeIf { supportsFollowUp && isSessionBusy },
                            isPending = true,
                            isStreaming = false,
                            renderedMarkdown = optimisticMarkdown,
                            attachments = attachmentItems,
                            blocks =
                                buildList {
                                    if (display.text.isNotBlank()) {
                                        add(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.messageFallback(display.id),
                                                text = display.text,
                                                renderedMarkdown = optimisticMarkdown,
                                            ),
                                        )
                                    }
                                    attachmentItems.forEach { item ->
                                        add(
                                            SessionChatTimelineBlock.Attachment(
                                                blockID = PresentationBlockId.direct(item.id),
                                                attachment = item,
                                            ),
                                        )
                                    }
                                },
                        ),
                    )
                }
            clearAcceptedSubmission(
                textRevision = submittedDraftRevision,
                submittedAttachmentRevisions = submittedAttachmentRevisions,
            )
            _state.update {
                it.copy(
                    isSending = true,
                    sendProgressPercent = 0,
                    errorMessage = null,
                )
            }
            onOptimisticRows(enriched)
            try {
                if (!holdUntil()) {
                    restoreFailedSubmission(
                        textRevision = submittedDraftRevision,
                        submittedText = submittedText,
                        submittedMentions = submittedMentions,
                        submittedAttachments = attachments,
                        submittedAttachmentRevisions = submittedAttachmentRevisions,
                    )
                    _state.update { it.copy(isSending = false, sendProgressPercent = null) }
                    onSendFailed(display.id, PENDING_SEND_TIMEOUT_ERROR)
                    return@launch
                }
                val intent =
                    ComposerSubmitRouting.resolve(
                        text = prompt,
                        commands = _state.value.serverCommands,
                        allowsSlashCommands = allowsSlashCommands,
                    )
                val hasAttachments = attachments.isNotEmpty()
                val progress =
                    PromptSendProgressReporter { sent, total ->
                        applySendProgress(sent = sent, total = total, hasAttachments = hasAttachments)
                    }
                withContext(NonCancellable + progress) {
                    suspend fun sendPrompt(
                        text: String,
                        skillIds: List<String>,
                        mentions: List<dev.opencode.mobile.domain.agent.AgentMention>,
                    ) {
                        suspend fun send(delivery: FollowUpDelivery) {
                            messageRepository.sendPromptAsync(
                                sessionId = sessionId,
                                options =
                                    PromptSendOptions(
                                        text = text,
                                        attachments = attachments,
                                        model =
                                            selectedModel?.let {
                                                PromptModelSelection(
                                                    it.option.providerId,
                                                    it.option.modelId,
                                                )
                                            },
                                        variant = composer.selectedVariant,
                                        mode = composer.selectedMode,
                                        agentMentions = mentions,
                                        skillIds = skillIds,
                                        delivery = delivery,
                                    ),
                                directory = worktree,
                            )
                        }
                        try {
                            send(composer.followUpDelivery)
                        } catch (busy: CancellationException) {
                            throw busy
                        } catch (busy: Throwable) {
                            if (
                                supportsFollowUp &&
                                composer.followUpDelivery != FollowUpDelivery.Queue &&
                                errorMessage(busy) == "Session is busy"
                            ) {
                                send(FollowUpDelivery.Queue)
                            } else {
                                throw busy
                            }
                        }
                        if (mentions.isNotEmpty()) {
                            logI("prompt send agentParts=${mentions.size}")
                        }
                    }
                    when (intent) {
                        is ComposerSubmitIntent.ServerCommand ->
                            commandRepository.runCommand(
                                sessionId = sessionId,
                                options =
                                    CommandRunOptions(
                                        command = intent.command,
                                        arguments = intent.arguments,
                                        attachments = attachments,
                                        model =
                                            selectedModel?.let {
                                                PromptModelSelection(
                                                    it.option.providerId,
                                                    it.option.modelId,
                                                )
                                            },
                                        variant = composer.selectedVariant,
                                        mode = composer.selectedMode,
                                        delivery = composer.followUpDelivery,
                                    ),
                                directory = worktree,
                            )
                        is ComposerSubmitIntent.Prompt ->
                            sendPrompt(
                                text = intent.text,
                                skillIds = emptyList(),
                                mentions = mentionsForSend,
                            )
                        is ComposerSubmitIntent.SkillPrompt ->
                            sendPrompt(
                                text = intent.text,
                                skillIds = listOf(intent.skillId),
                                mentions = emptyList(),
                            )
                        ComposerSubmitIntent.Ignored ->
                            error("Slash commands are not available")
                    }
                    persistCurrentSelection(updatePreference = false)
                    _state.update { it.copy(sendProgressPercent = null) }
                    onSendAccepted()
                    discardStoredAttachments(attachments)
                }
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                if (isTransportFailure(throwable) && serverAlreadyAccepted()) {
                    logI("sendPrompt transport error ignored; server already accepted the send")
                    withContext(NonCancellable) {
                        persistCurrentSelection(updatePreference = false)
                        _state.update { it.copy(sendProgressPercent = null) }
                        onSendAccepted()
                        discardStoredAttachments(attachments)
                    }
                    return@launch
                }
                logSendFailure(throwable)
                restoreFailedSubmission(
                    textRevision = submittedDraftRevision,
                    submittedText = submittedText,
                    submittedMentions = submittedMentions,
                    submittedAttachments = attachments,
                    submittedAttachmentRevisions = submittedAttachmentRevisions,
                )
                _state.update { it.copy(isSending = false, sendProgressPercent = null) }
                onSendFailed(display.id, errorMessage(throwable))
            }
        }
    }

    private fun clearAcceptedSubmission(
        textRevision: Int,
        submittedAttachmentRevisions: Map<String, Int>,
    ) {
        _state.update { state ->
            val keptAttachments =
                state.composer.attachments.filterNot { current ->
                    val submittedRevision = submittedAttachmentRevisions[current.id] ?: return@filterNot false
                    attachmentRevisions[current.id] == submittedRevision
                }
            submittedAttachmentRevisions.forEach { (id, revision) ->
                if (attachmentRevisions[id] == revision) {
                    attachmentRevisions.remove(id)
                }
            }
            val clearedText = if (draftRevision == textRevision) "" else state.composer.text
            val clearedMentions = if (draftRevision == textRevision) emptyList() else state.composer.agentMentions
            val clearedCursor = if (draftRevision == textRevision) 0 else state.composer.cursor
            state
                .copy(
                    composer =
                        state.composer.copy(
                            text = clearedText,
                            attachments = keptAttachments,
                            agentMentions = clearedMentions,
                            cursor = clearedCursor,
                            selectionRevision = state.composer.selectionRevision + 1,
                        ),
                ).withComposerSuggestions(
                    text = clearedText,
                    cursor = clearedCursor,
                    mentions = clearedMentions,
                )
        }
        persistDraftJob?.cancel()
        scope.launch { persistDraft(boundSessionId, boundWorktree, currentDraft()) }
    }

    private fun restoreFailedSubmission(
        textRevision: Int,
        submittedText: String,
        submittedMentions: List<dev.opencode.mobile.domain.agent.AgentMention>,
        submittedAttachments: List<Attachment>,
        submittedAttachmentRevisions: Map<String, Int>,
    ) {
        submittedAttachmentRevisions.forEach { (id, revision) ->
            if (attachmentRevisions[id] == null) {
                attachmentRevisions[id] = revision
            }
        }
        _state.update { state ->
            val restoreText = draftRevision == textRevision
            val text = if (restoreText) submittedText else state.composer.text
            val mentions = if (restoreText) submittedMentions else state.composer.agentMentions
            val cursor = if (restoreText) submittedText.length else state.composer.cursor
            val currentIds =
                state.composer.attachments
                    .map { it.id }
                    .toSet()
            val restoredAttachments =
                submittedAttachments.filter { it.id !in currentIds } + state.composer.attachments
            state
                .copy(
                    composer =
                        state.composer.copy(
                            text = text,
                            attachments = restoredAttachments,
                            agentMentions = mentions,
                            cursor = cursor,
                            selectionRevision = state.composer.selectionRevision + 1,
                        ),
                ).withComposerSuggestions(
                    text = text,
                    cursor = cursor,
                    mentions = mentions,
                )
        }
        persistDraftJob?.cancel()
        scope.launch { persistDraft(boundSessionId, boundWorktree, currentDraft()) }
    }

    fun flushDraft() {
        persistDraftJob?.cancel()
        persistDraftJob = null
        val sessionId = boundSessionId
        val worktree = boundWorktree
        if (sessionId == null || worktree == null) return
        val snapshot = currentDraft()
        kotlinx.coroutines.runBlocking { persistDraft(sessionId, worktree, snapshot) }
    }

    fun clearSending() {
        _state.update { it.copy(isSending = false, sendProgressPercent = null) }
    }

    private fun discardStoredAttachments(attachments: Collection<Attachment>) {
        attachments.forEach { AttachmentLimits.discard(it.localPath) }
    }

    private fun applySendProgress(
        sent: Long,
        total: Long,
        hasAttachments: Boolean,
    ) {
        if (!PromptSendProgress.shouldShow(total, hasAttachments)) return
        val percent = PromptSendProgress.percent(sent, total)
        val current = _state.value.sendProgressPercent
        if (current == percent) return
        _state.update { it.copy(sendProgressPercent = percent) }
    }

    fun clearTransientErrors() {
        _state.update { it.copy(errorMessage = null, abortErrorMessage = null) }
    }

    fun abort(onFinished: (success: Boolean) -> Unit = {}) {
        val sessionId = boundSessionId
        if (sessionId == null) {
            logW("abort skipped unbound")
            onFinished(false)
            return
        }
        val worktree = boundWorktree
        val extraIds = latestTimelineState?.knownChildSessionIds().orEmpty() - sessionId
        logI("abort start session=$sessionId children=${extraIds.joinToString(",")}")
        scope.launch {
            _state.update { it.copy(isAborting = true, abortErrorMessage = null) }
            val parentResult =
                runCatching {
                    sessionRepository.abortSession(sessionId, directory = worktree)
                }
            extraIds.forEach { childId ->
                runCatching {
                    sessionRepository.abortSession(childId, directory = worktree)
                }.onFailure { throwable ->
                    logW("abort child failed session=$sessionId child=$childId message=${throwable.message}")
                }
            }
            parentResult
                .onFailure { throwable ->
                    logW("abort failed session=$sessionId message=${throwable.message}")
                    if (boundSessionId == sessionId && boundWorktree == worktree) {
                        _state.update {
                            it.copy(
                                isAborting = false,
                                abortErrorMessage = errorMessage(throwable),
                            )
                        }
                    }
                    onFinished(false)
                }.onSuccess {
                    logI("abort success session=$sessionId")
                    if (boundSessionId == sessionId && boundWorktree == worktree) {
                        _state.update { it.copy(isAborting = false, abortErrorMessage = null) }
                    }
                    onFinished(true)
                }
        }
    }

    private fun currentDraft(): ComposerDraft {
        val composer = _state.value.composer
        return ComposerDraft(text = composer.text, agentMentions = composer.agentMentions)
    }

    private fun applyDraft(draft: ComposerDraft) {
        _state.update { state ->
            state
                .copy(
                    composer =
                        state.composer.copy(
                            text = draft.text,
                            agentMentions = draft.agentMentions,
                            cursor = draft.text.length,
                            selectionRevision = state.composer.selectionRevision + 1,
                        ),
                ).withComposerSuggestions(
                    text = draft.text,
                    cursor = draft.text.length,
                    mentions = draft.agentMentions,
                )
        }
    }

    private fun applyOrLoadDraft(preserve: ComposerDraft?) {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (OptimisticSessionCreate.isPlaceholder(sessionId)) return
        if (preserve != null && preserve.text.isNotBlank()) {
            applyDraft(preserve)
            draftRevision += 1
            schedulePersistDraft()
            return
        }
        val generation = ++draftLoadGeneration
        val identity = currentIdentity() ?: return
        scope.launch {
            val loaded =
                runCatching {
                    withContext(ioDispatcher) {
                        composerDraftStore.load(identity, worktree, sessionId)
                    }
                }.onFailure {
                    logW("loadComposerDraft failed error=${it.message ?: it::class.simpleName}")
                }.getOrNull()
            if (generation != draftLoadGeneration) return@launch
            if (draftRevision != 0) return@launch
            if (loaded != null && loaded.text.isNotBlank()) {
                applyDraft(loaded)
            }
        }
    }

    private fun schedulePersistDraft() {
        val sessionId = boundSessionId
        val worktree = boundWorktree
        if (sessionId == null || worktree == null) return
        persistDraftJob?.cancel()
        persistDraftJob =
            scope.launch {
                delay(DRAFT_PERSIST_DEBOUNCE_MS)
                persistDraft(sessionId, worktree, currentDraft())
            }
    }

    private suspend fun persistDraft(
        sessionId: String?,
        worktree: String?,
        draft: ComposerDraft,
    ) {
        if (sessionId == null || worktree == null) return
        if (OptimisticSessionCreate.isPlaceholder(sessionId)) return
        val identity = currentIdentity() ?: return
        runCatching {
            withContext(ioDispatcher) {
                if (draft.text.isBlank()) {
                    composerDraftStore.delete(identity, worktree, sessionId)
                } else {
                    composerDraftStore.save(identity, worktree, sessionId, draft)
                }
            }
        }.onFailure {
            logW("saveComposerDraft failed error=${it.message ?: it::class.simpleName}")
        }
    }

    private fun currentIdentity(): ModelSelectionConnectionIdentity? {
        val profile = connectionSessionCoordinator.currentProfile() ?: return null
        return ModelSelectionConnectionIdentity(profile.baseUrl, profile.username)
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
            if (!isCurrentBinding(sessionId, worktree)) return@launch
            // The in-memory selection is newer when its write is still in flight or failed.
            val remembered = modelCatalogStore.lastSelection(worktree, sessionId)
            cachedSelection =
                listOfNotNull(loaded.session, remembered).maxByOrNull { it.savedAtEpochMs ?: Long.MIN_VALUE }
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
        // Only user picks and sends persist while the selection is provisional.
        if (provisionalSelection) pickedDuringProvisional = true
        val selection =
            SessionModelSelection(
                providerId = selected.option.providerId,
                modelId = selected.option.modelId,
                variant = variant,
                agentId = _state.value.composer.selectedMode,
                savedAtEpochMs = timestamp,
            )
        modelCatalogStore.rememberSelection(worktree, sessionId, selection)
        scope.launch {
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
        updateComposer {
            it.copy(
                modelOptions = catalog.options,
                modelProviderTraits = catalog.providerTraits,
                modelCatalogError = null,
            )
        }
        revalidateSelection(catalog)
        applyInitialSelectionIfReady()
    }

    /** A refreshed catalog that dropped the selected model falls back like a stale remembered one. */
    private fun revalidateSelection(catalog: ModelSelectionCatalog) {
        val selected = _state.value.composer.selectedModel ?: return
        val refreshed = catalog.option(selected.option.providerId, selected.option.modelId)
        if (refreshed == selected.option) return
        val option = refreshed ?: catalog.serverDefault ?: catalog.options.first()
        val variant = resolvedVariant(_state.value.composer.selectedVariant, option)
        val dropped =
            _state.value.composer.attachments
                .filterNot { option.acceptsAttachmentKind(it.kind) }
        dropped.forEach { attachmentRevisions.remove(it.id) }
        updateComposer { composer ->
            composer.copy(
                selectedModel = ModelSelection(option, variant),
                selectedVariant = variant,
                attachments = composer.attachments.filter { option.acceptsAttachmentKind(it.kind) },
                modelNotice =
                    if (refreshed == null) {
                        "Selected model is unavailable. Using ${option.modelLabel}."
                    } else {
                        composer.modelNotice
                    },
            )
        }
        discardStoredAttachments(dropped)
        if (refreshed == null) logI("modelSelection unavailable after refresh; using ${option.modelLabel}")
    }

    private fun resolvedVariant(
        remembered: String?,
        option: ModelSelectionOption,
    ): String? = remembered?.takeIf { it.isNotBlank() && it in option.variants }

    private fun composerMetadataLabel(composer: SessionChatComposerState): String =
        listOfNotNull(
            composer.selectedModeLabel,
            composer.selectedModel?.modelLabel,
            composer.selectedVariant?.let(TitleCaseIdentifier::displayName),
            formatSentTime(System.currentTimeMillis()),
        ).joinToString(" · ")

    private fun applySelectableAgents(agents: List<SelectableAgent>) {
        val catalog = agents.ifEmpty { SelectableAgent.Fallback }
        updateComposer { composer ->
            composer.copy(
                selectableAgents = catalog,
                selectedMode = resolveSelectedAgentId(composer.selectedMode, catalog),
            )
        }
        applyInitialSelectionIfReady()
    }

    private fun resolveSelectedAgentId(
        current: String,
        catalog: List<SelectableAgent>,
    ): String {
        catalog.firstOrNull { it.id.equals(current, ignoreCase = true) }?.let { return it.id }
        catalog.firstOrNull { it.id.equals(SelectableAgent.DefaultId, ignoreCase = true) }?.let { return it.id }
        return catalog.first().id
    }

    private fun updateComposer(transform: (SessionChatComposerState) -> SessionChatComposerState) {
        _state.update { state ->
            val composer = transform(state.composer)
            state.copy(composer = composer).withComposerSuggestions(
                text = composer.text,
                cursor = composer.cursor,
                mentions = composer.agentMentions,
            )
        }
    }

    private fun SessionChatComposerRegionState.withComposerSuggestions(
        text: String,
        cursor: Int,
        mentions: List<dev.opencode.mobile.domain.agent.AgentMention> = emptyList(),
    ): SessionChatComposerRegionState =
        when (val mode = suggestionMode(text, cursor, mentions)) {
            is ComposerSuggestionMode.At ->
                copy(
                    atSuggestionsVisible = true,
                    atSuggestions =
                        ComposerMentionRouting
                            .filterAgents(mentionableAgents, mode.query)
                            .map { it.toAtSuggestion() },
                    slashSuggestionsVisible = false,
                    slashSuggestions = emptyList(),
                )
            is ComposerSuggestionMode.Slash ->
                copy(
                    slashSuggestionsVisible = true,
                    slashSuggestions =
                        ComposerSubmitRouting
                            .filterCommands(serverCommands, mode.query)
                            .map { it.toSlashSuggestion() },
                    atSuggestionsVisible = false,
                    atSuggestions = emptyList(),
                )
            ComposerSuggestionMode.None ->
                copy(
                    slashSuggestionsVisible = false,
                    slashSuggestions = emptyList(),
                    atSuggestionsVisible = false,
                    atSuggestions = emptyList(),
                )
        }

    private fun suggestionMode(
        text: String,
        cursor: Int,
        mentions: List<dev.opencode.mobile.domain.agent.AgentMention>,
    ): ComposerSuggestionMode =
        ComposerMentionRouting.suggestionMode(
            text = text,
            cursor = cursor,
            mentions = mentions,
            allowsMentions = allowsMentions,
            allowsSlashCommands = allowsSlashCommands,
        )

    private fun formatSentTime(epochMs: Long): String =
        Instant
            .ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(SentTimeFormatter)

    private fun unsupportedAttachmentMessage(rejected: List<Attachment>): String {
        val kinds = rejected.map { it.kind }.toSet()
        return when (kinds) {
            setOf(AttachmentKind.Image) -> "This model does not support image attachments."
            setOf(AttachmentKind.Pdf) -> "This model does not support PDF attachments."
            else -> "This model does not support some of the selected attachments."
        }
    }

    private fun isTransportFailure(throwable: Throwable): Boolean =
        throwable is MessageError.Transport || throwable is CommandError.Transport

    private fun errorMessage(throwable: Throwable): String =
        when (throwable) {
            is MessageError.Http -> throwable.message ?: "Request failed"
            is MessageError.Transport -> "Unable to reach the server"
            is MessageError.Decoding -> "Unable to read server response"
            is CommandError.Http -> throwable.message ?: "Request failed"
            is CommandError.Transport -> "Unable to reach the server"
            is CommandError.Decoding -> "Unable to read server response"
            else -> throwable.message ?: "Something went wrong"
        }

    private fun logSendFailure(throwable: Throwable) {
        DiagnosticLog.error(DiagnosticLogArea.Chat, T3Errors.diagnosticMessage("sendPromptAsync failed", throwable), throwable)
    }

    private fun logI(message: String) {
        DiagnosticLog.info(DiagnosticLogArea.Chat, message)
    }

    private fun logW(message: String) {
        DiagnosticLog.warning(DiagnosticLogArea.Chat, message)
    }

    companion object {
        /**
         * Server catalog events after which the model list is refetched. A freshly started
         * server can answer the first `GET model` before its catalog is ready, then announce it.
         */
        val CATALOG_UPDATE_EVENTS = setOf("model.updated", "provider.updated")

        /**
         * Stream (re)connects after which the model list is refetched: catalog events the server
         * sent while the stream was down (a restart) are never replayed.
         */
        val CATALOG_RECONNECT_EVENTS = setOf("server.connected", "stream.reconnected")
        val DEFAULT_CATALOG_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)
        private const val DRAFT_PERSIST_DEBOUNCE_MS = 300L
        const val ModelFilterThreshold = 10
        const val PENDING_SEND_TIMEOUT_ERROR = "Couldn't send. Try again."
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

        data class Session(
            val value: SessionModelSelection,
        ) : InitialSelection {
            override val sourceName = "session-record"
        }
    }
}
