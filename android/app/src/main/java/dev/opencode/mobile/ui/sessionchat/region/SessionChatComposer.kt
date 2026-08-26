package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.agent.ComposerMentionRouting
import dev.opencode.mobile.domain.agent.ComposerSuggestionMode
import dev.opencode.mobile.domain.command.CommandInvocationCollapser
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.command.CommandRunOptions
import dev.opencode.mobile.domain.command.ComposerSubmitIntent
import dev.opencode.mobile.domain.command.ComposerSubmitRouting
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.PromptModelSelection
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.AttachmentPresentationMapper
import dev.opencode.mobile.ui.sessionchat.LatestModelHint
import dev.opencode.mobile.ui.sessionchat.ModelSelectionHistoryHints
import dev.opencode.mobile.ui.sessionchat.ModelSelectionHistorySource
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatComposerState
import dev.opencode.mobile.ui.sessionchat.PresentationBlockId
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.knownChildSessionIds
import dev.opencode.mobile.ui.sessionchat.toAtSuggestion
import dev.opencode.mobile.ui.sessionchat.toSlashSuggestion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val commandRepository: CommandRepository,
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository,
    private val connectionSessionCoordinator: ConnectionSessionCoordinator,
    private val sessionModelSelectionStore: SessionModelSelectionStore,
    private val composerDraftStore: ComposerDraftStore,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatComposerRegionState())
    val state: StateFlow<SessionChatComposerRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var loadedCatalog: ModelSelectionCatalog? = null
    private var loadedSelectableAgents = false
    private var cachedSelection: SessionModelSelection? = null
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
        boundSessionId = sessionId
        boundWorktree = worktree
        loadedCatalog = null
        loadedSelectableAgents = false
        cachedSelection = null
        newSessionPreference = null
        initialSelectionState = InitialSelectionState.LoadingCache
        latestTimelineState = null
        latestHistoryHints = null
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
        loadCachedSelection()
        loadServerCommands()
        loadMentionableAgents()
        loadSelectableAgents()
        applyOrLoadDraft(preserve)
    }

    fun loadServerCommands(force: Boolean = false) {
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
        val worktree = boundWorktree ?: return
        if (!force && loadedSelectableAgents) return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    agentRepository.listSelectableAgents(directory = worktree)
                }
            }.onSuccess { agents ->
                if (boundWorktree != worktree) return@onSuccess
                loadedSelectableAgents = true
                applySelectableAgents(agents)
            }.onFailure { throwable ->
                if (boundWorktree != worktree) return@onFailure
                logW("selectable agent catalog failed: ${throwable.message}")
            }
        }
    }

    fun loadMentionableAgents(force: Boolean = false) {
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
            val keptAttachments = state.composer.attachments.filter { option.acceptsAttachmentKind(it.kind) }
            state.composer.attachments
                .filterNot { option.acceptsAttachmentKind(it.kind) }
                .forEach { attachmentRevisions.remove(it.id) }
            state.copy(
                composer =
                    state.composer.copy(
                        modelOptions = catalog.options,
                        selectedModel = ModelSelection(option, variant),
                        selectedVariant = variant,
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

    fun updateComposerText(
        text: String,
        cursor: Int = text.length,
    ) {
        val previous = _state.value.composer
        if (text == previous.text) {
            val mode =
                ComposerMentionRouting.suggestionMode(
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
            ComposerMentionRouting.suggestionMode(
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
        val option = _state.value.composer.selectedModel?.option
        val accepted = attachments.filter { option?.acceptsAttachmentKind(it.kind) != false }
        val rejected = attachments.filter { option?.acceptsAttachmentKind(it.kind) == false }
        if (accepted.isNotEmpty()) {
            updateComposer { composer ->
                val ordered = composer.attachments.toMutableList()
                accepted.forEach { attachment ->
                    attachmentRevisions[attachment.id] = nextAttachmentRevision++
                    val index = ordered.indexOfFirst { it.id == attachment.id }
                    if (index >= 0) {
                        ordered[index] = attachment
                    } else {
                        ordered.add(attachment)
                    }
                }
                composer.copy(attachments = ordered)
            }
        }
        if (rejected.isNotEmpty()) {
            reportAttachmentError(unsupportedAttachmentMessage(rejected))
        } else if (accepted.isNotEmpty()) {
            _state.update { it.copy(errorMessage = null) }
        }
    }

    fun removeAttachment(id: String) {
        attachmentRevisions.remove(id)
        updateComposer { composer -> composer.copy(attachments = composer.attachments.filterNot { it.id == id }) }
    }

    fun reportAttachmentError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    fun selectModel(option: ModelSelectionOption) {
        updateComposer { composer ->
            val previousVariant = composer.selectedVariant
            val variant = previousVariant?.takeIf { it in option.variants }
            val keptAttachments = composer.attachments.filter { option.acceptsAttachmentKind(it.kind) }
            composer.attachments
                .filterNot { option.acceptsAttachmentKind(it.kind) }
                .forEach { attachmentRevisions.remove(it.id) }
            composer.copy(
                selectedModel = ModelSelection(option, variant),
                selectedVariant = variant,
                attachments = keptAttachments,
                modelNotice = null,
            )
        }
        persistCurrentSelection(updatePreference = true)
    }

    fun selectMode(mode: String) {
        val match =
            _state.value.composer.selectableAgents.firstOrNull { it.id.equals(mode, ignoreCase = true) }
                ?: return
        updateComposer { it.copy(selectedMode = match.id) }
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
        val next = _state.value.composer.followUpDelivery.toggled()
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
    ) {
        val composer = _state.value.composer
        val (prompt, mentionsForSend) =
            ComposerMentionRouting.trimForSend(composer.text, composer.agentMentions)
        val attachments = composer.attachments
        val submittedDraftRevision = draftRevision
        val submittedAttachmentRevisions =
            attachments.mapNotNull { attachment ->
                attachmentRevisions[attachment.id]?.let { attachment.id to it }
            }.toMap()
        val selectedModel = composer.selectedModel ?: return
        if ((prompt.isEmpty() && attachments.isEmpty()) || _state.value.isSending || !composer.isModelSelectionReady) return

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
                            waitingCaption =
                                composer.followUpDelivery.waitingCaption.takeIf {
                                    supportsFollowUp && isSessionBusy
                                },
                            waitingDelivery =
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
            _state.update {
                it.copy(
                    isSending = true,
                    errorMessage = null,
                )
            }
            onOptimisticRows(enriched)
            try {
                val intent =
                    ComposerSubmitRouting.resolve(
                        text = prompt,
                        serverCommandNames = _state.value.serverCommands.map { it.name }.toSet(),
                    )
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
                                        PromptModelSelection(
                                            selectedModel.option.providerId,
                                            selectedModel.option.modelId,
                                        ),
                                    variant = composer.selectedVariant,
                                    mode = composer.selectedMode,
                                    delivery = composer.followUpDelivery,
                                ),
                            directory = worktree,
                        )
                    is ComposerSubmitIntent.Prompt -> {
                        messageRepository.sendPromptAsync(
                            sessionId = sessionId,
                            options =
                                PromptSendOptions(
                                    text = intent.text,
                                    attachments = attachments,
                                    model =
                                        PromptModelSelection(
                                            selectedModel.option.providerId,
                                            selectedModel.option.modelId,
                                        ),
                                    variant = composer.selectedVariant,
                                    mode = composer.selectedMode,
                                    agentMentions = mentionsForSend,
                                    delivery = composer.followUpDelivery,
                                ),
                            directory = worktree,
                        )
                        if (mentionsForSend.isNotEmpty()) {
                            logI("prompt send agentParts=${mentionsForSend.size}")
                        }
                    }
                }
                clearAcceptedSubmission(
                    textRevision = submittedDraftRevision,
                    submittedAttachmentRevisions = submittedAttachmentRevisions,
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
        _state.update { it.copy(isSending = false) }
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
            composer.selectedModeLabel,
            composer.selectedModel?.modelLabel,
            composer.selectedVariant,
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
    ): SessionChatComposerRegionState {
        return when (val mode = ComposerMentionRouting.suggestionMode(text, cursor, mentions)) {
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
    }

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
        private const val DRAFT_PERSIST_DEBOUNCE_MS = 300L
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
