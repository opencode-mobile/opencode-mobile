import Foundation

@MainActor
final class SessionChatComposer {
    /// Server catalog events after which the model list is refetched. A freshly started
    /// server can answer the first `GET model` before its catalog is ready, then announce it.
    static let catalogUpdateEvents: Set<String> = ["model.updated", "provider.updated"]
    /// Stream (re)connects after which the model list is refetched: catalog events the server
    /// sent while the stream was down (a restart) are never replayed.
    static let catalogReconnectEvents: Set<String> = ["server.connected", "stream.reconnected"]
    /// Backoff for refetching a catalog that came back empty (a server still loading providers).
    nonisolated static let defaultCatalogRetryDelaysMs = [1_000, 2_000, 4_000]

    var onChange: (() -> Void)?

    private(set) var state = SessionChatComposerRegionState()

    private var sessionID: String
    private let worktree: String
    private let messageRepository: any MessageRepository
    private let modelCatalogStore: ModelCatalogStore
    private let commandRepository: any CommandRepository
    private let agentRepository: any AgentRepository
    private let sessionRepository: any SessionRepository
    private let sessionModelSelectionStore: any SessionModelSelectionStore
    private let composerDraftStore: any ComposerDraftStore
    private let projectFilesRepository: (any ProjectFilesRepository)?
    private let activeConnectionManager: ActiveConnectionManager
    private let foregroundEventStream: (any ForegroundEventStream)?
    private let sentTimeFormatter: DateFormatter
    private var loadedCatalog: ModelSelectionCatalog?
    private var cachedSelection: SessionModelSelection?
    private var newSessionPreference: SessionModelSelection?
    private var initialSelectionState: InitialSelectionState = .loadingCache
    /// A selection seeded from the shared catalog is shown; the history reconcile is pending.
    private var isSelectionProvisional = false
    private var latestTimelineState: SessionChatTimelineRegionState?
    private var latestHistoryHints: ModelSelectionHistoryHints?
    private var lastSelectionTimestamp: Int64 = 0
    private var draftRevision = 0
    private var draftLoadGeneration = 0
    private var persistDraftTask: Task<Void, Never>?
    private var attachmentRevision = 0
    private var attachmentRevisions: [String: Int] = [:]
    private var serverCommandsTask: Task<Void, Never>?
    private var mentionableAgentsTask: Task<Void, Never>?
    private var selectableAgentsTask: Task<Void, Never>?
    private var loadedSelectableAgents = false
    private var seededSelectableAgents = false
    private var catalogLoadGeneration = 0
    private var catalogEventTask: Task<Void, Never>?
    private var catalogRefreshTask: Task<Void, Never>?
    private var catalogRetryTask: Task<Void, Never>?
    private let catalogRetryDelaysMs: [Int]
    private var fileSearchTask: Task<Void, Never>?
    private var fileSearchGeneration = 0
    var fileSearchDelayMs = 200

    init(
        sessionID: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelCatalogStore: ModelCatalogStore,
        commandRepository: any CommandRepository,
        agentRepository: any AgentRepository,
        sessionRepository: any SessionRepository,
        sessionModelSelectionStore: any SessionModelSelectionStore,
        composerDraftStore: any ComposerDraftStore,
        activeConnectionManager: ActiveConnectionManager,
        foregroundEventStream: (any ForegroundEventStream)? = nil,
        projectFilesRepository: (any ProjectFilesRepository)? = nil,
        catalogRetryDelaysMs: [Int] = SessionChatComposer.defaultCatalogRetryDelaysMs
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
        self.modelCatalogStore = modelCatalogStore
        self.commandRepository = commandRepository
        self.agentRepository = agentRepository
        self.sessionRepository = sessionRepository
        self.sessionModelSelectionStore = sessionModelSelectionStore
        self.composerDraftStore = composerDraftStore
        self.projectFilesRepository = projectFilesRepository
        self.activeConnectionManager = activeConnectionManager
        self.foregroundEventStream = foregroundEventStream
        self.catalogRetryDelaysMs = catalogRetryDelaysMs
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        self.sentTimeFormatter = formatter
        seedFromCatalogStore()
        Task {
            await self.loadCachedSelection()
            self.applyOrLoadDraft(preserve: nil)
        }
    }

    /// Catalog loads that used to run from `init` — keep them off View construction.
    /// `SessionChatView` init can run on every parent body refresh while `@State` keeps
    /// one live ViewModel; orphan inits must not hit the network.
    func rebind(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        let previousSessionID = self.sessionID
        let preserve: ComposerDraft? =
            if OptimisticSessionCreate.isPlaceholder(previousSessionID),
               !OptimisticSessionCreate.isPlaceholder(sessionID) {
                currentDraft()
            } else {
                nil
            }
        persistDraftTask?.cancel()
        persistDraft(sessionID: previousSessionID, draft: currentDraft())
        discardStoredAttachments(state.composer.attachments)
        self.sessionID = sessionID
        loadedCatalog = nil
        loadedSelectableAgents = false
        seededSelectableAgents = false
        cachedSelection = nil
        newSessionPreference = nil
        initialSelectionState = .loadingCache
        isSelectionProvisional = false
        latestTimelineState = nil
        latestHistoryHints = nil
        draftRevision = 0
        attachmentRevision = 0
        attachmentRevisions = [:]
        state = SessionChatComposerRegionState()
        state.composer.followUpDelivery = SessionChatDisplayPreferences.shared.followUpDelivery
        seedFromCatalogStore()
        notifyChange()
        Task {
            await self.loadCachedSelection()
            self.applyOrLoadDraft(preserve: preserve)
        }
        loadServerCommands()
        loadMentionableAgents()
        loadSelectableAgents()
    }

    func startCatalogLoads() {
        loadServerCommands()
        loadMentionableAgents()
        loadSelectableAgents()
        startCatalogEventRefresh()
    }

    func stopCatalogEventRefresh() {
        catalogEventTask?.cancel()
        catalogEventTask = nil
        catalogRefreshTask?.cancel()
        catalogRefreshTask = nil
        catalogRetryTask?.cancel()
        catalogRetryTask = nil
    }

    /// Refetches the model catalog when the server announces a provider or model change, or
    /// when the stream reconnects (the server may have restarted and announced its catalog
    /// while the stream was down), so an empty answer recovers without a manual retry.
    private func startCatalogEventRefresh() {
        guard let foregroundEventStream, catalogEventTask == nil else { return }
        catalogEventTask = Task { [weak self] in
            for await event in foregroundEventStream.events() {
                if Task.isCancelled { return }
                guard Self.catalogUpdateEvents.contains(event.type)
                    || Self.catalogReconnectEvents.contains(event.type)
                else { continue }
                self?.refreshCatalogAfterServerUpdate(reason: event.type)
            }
        }
    }

    private func refreshCatalogAfterServerUpdate(reason: String) {
        OpenCodeLog.sessionChat.info("model catalog refresh reason=\(reason)")
        catalogRefreshTask?.cancel()
        catalogRefreshTask = Task { [weak self] in
            await self?.loadCatalog(force: true, emptyRetryAttempt: 0)
        }
    }

    func reset() {
        catalogRetryTask?.cancel()
        catalogRetryTask = nil
        loadedCatalog = nil
        loadedSelectableAgents = false
        seededSelectableAgents = false
        cachedSelection = nil
        newSessionPreference = nil
        initialSelectionState = .loadingCache
        isSelectionProvisional = false
        latestTimelineState = nil
        latestHistoryHints = nil
        draftRevision = 0
        attachmentRevision = 0
        attachmentRevisions = [:]
        discardStoredAttachments(state.composer.attachments)
        state = SessionChatComposerRegionState()
        state.composer.followUpDelivery = SessionChatDisplayPreferences.shared.followUpDelivery
        notifyChange()
    }

    /// `force` asks the shared catalog for a fresh answer instead of joining one in flight.
    func loadCatalog(force: Bool = false) async {
        await loadCatalog(force: force, emptyRetryAttempt: 0)
    }

    /// `emptyRetryAttempt` is non-zero only for a backoff retry of an empty answer.
    private func loadCatalog(force: Bool, emptyRetryAttempt: Int) async {
        catalogLoadGeneration += 1
        let generation = catalogLoadGeneration
        if emptyRetryAttempt == 0 {
            catalogRetryTask?.cancel()
            catalogRetryTask = nil
        }
        do {
            let catalog = try await modelCatalogStore.refreshCatalog(worktree: worktree, force: force)
            // An older request must not overwrite the answer to a newer one.
            guard generation == catalogLoadGeneration else { return }
            if catalog.options.isEmpty {
                scheduleEmptyCatalogRetry(attempt: emptyRetryAttempt)
                // A server still loading must not hide a catalog this session already has.
                if loadedCatalog?.options.isEmpty == false { return }
            }
            applyCatalog(catalog)
        } catch {
            // A superseded refresh is cancelled; its failure is not the catalog's state.
            guard generation == catalogLoadGeneration, !Task.isCancelled else { return }
            OpenCodeLog.sessionChat.warning("loadCatalog failed error=\(error.localizedDescription)")
            if emptyRetryAttempt > 0 {
                scheduleEmptyCatalogRetry(attempt: emptyRetryAttempt)
            }
            // A failed refresh keeps the catalog and selection already shown.
            if loadedCatalog?.options.isEmpty == false { return }
            updateComposer {
                $0.isModelSelectionReady = false
                $0.modelCatalogError = "Unable to load models. Tap Model to retry."
                $0.modelNotice = "Unable to load models. Tap Model to retry."
            }
        }
    }

    private func scheduleEmptyCatalogRetry(attempt: Int) {
        guard attempt < catalogRetryDelaysMs.count else { return }
        let delayMs = catalogRetryDelaysMs[attempt]
        OpenCodeLog.sessionChat.info("model catalog empty; retry attempt=\(attempt + 1) delayMs=\(delayMs)")
        catalogRetryTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(delayMs))
            guard !Task.isCancelled else { return }
            await self?.loadCatalog(force: true, emptyRetryAttempt: attempt + 1)
        }
    }

    func loadServerCommands(force: Bool = false) {
        if !force, !state.serverCommands.isEmpty { return }
        serverCommandsTask?.cancel()
        serverCommandsTask = Task {
            do {
                let commands = try await commandRepository.listCommands(directory: worktree)
                guard !Task.isCancelled else { return }
                state.serverCommands = commands
                refreshComposerSuggestions(for: state.composer.text, cursor: state.composer.cursor)
                notifyChange()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "server command catalog failed error=\(error.localizedDescription)"
                )
                // Keep any previously loaded catalog on transient refresh failures.
                refreshComposerSuggestions(for: state.composer.text, cursor: state.composer.cursor)
                notifyChange()
            }
        }
    }

    func loadSelectableAgents(force: Bool = false) {
        if !force, loadedSelectableAgents { return }
        selectableAgentsTask?.cancel()
        selectableAgentsTask = Task {
            do {
                let agents = try await modelCatalogStore.refreshSelectableAgents(worktree: worktree, force: force)
                guard !Task.isCancelled else { return }
                loadedSelectableAgents = true
                applySelectableAgents(agents)
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "selectable agent catalog failed error=\(error.localizedDescription)"
                )
                loadedSelectableAgents = true
                applyInitialSelectionIfReady()
            }
        }
    }

    func loadMentionableAgents(force: Bool = false) {
        if !force, !state.mentionableAgents.isEmpty { return }
        mentionableAgentsTask?.cancel()
        mentionableAgentsTask = Task {
            do {
                let agents = try await agentRepository.listMentionableAgents(directory: worktree)
                guard !Task.isCancelled else { return }
                state.mentionableAgents = agents
                refreshComposerSuggestions(for: state.composer.text, cursor: state.composer.cursor)
                notifyChange()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "mentionable agent catalog failed error=\(error.localizedDescription)"
                )
                // Keep any previously loaded catalog on transient refresh failures.
                refreshComposerSuggestions(for: state.composer.text, cursor: state.composer.cursor)
                notifyChange()
            }
        }
    }

    func tryApplyInitialSelection(
        timelineState: SessionChatTimelineRegionState,
        historyHints: ModelSelectionHistoryHints
    ) {
        latestTimelineState = timelineState
        latestHistoryHints = historyHints
        applyInitialSelectionIfReady()
    }

    private func applyInitialSelectionIfReady() {
        guard initialSelectionState != .resolved, initialSelectionState != .failedWithFallback else { return }
        guard initialSelectionState != .loadingCache else { return }
        guard loadedSelectableAgents || seededSelectableAgents else { return }
        guard let catalog = loadedCatalog, !catalog.options.isEmpty else { return }
        guard let timelineState = latestTimelineState else { return }

        switch timelineState.timeline {
        case .creating, .loading:
            return
        case .error, .empty, .ready:
            guard let historyHints = latestHistoryHints else { return }
            resolveAndPublishInitialSelection(catalog: catalog, historyHints: historyHints)
        }
    }

    private func resolveAndPublishInitialSelection(
        catalog: ModelSelectionCatalog,
        historyHints: ModelSelectionHistoryHints
    ) {
        let staleCached = staleCachedSelection()
        let validCached = staleCached == nil ? cachedSelection : nil
        let validUserHint = historyHints.user.flatMap { hint in
            catalog.option(providerID: hint.providerID, modelID: hint.modelID) == nil ? nil : hint
        }
        let validAssistantHint = historyHints.assistant.flatMap { hint in
            catalog.option(providerID: hint.providerID, modelID: hint.modelID) == nil ? nil : hint
        }
        let validPreference = newSessionPreference.flatMap { value in
            catalog.option(providerID: value.providerID, modelID: value.modelID) == nil ? nil : value
        }
        let chosen = chooseInitialSelection(
            cached: validCached,
            userHint: validUserHint,
            assistantHint: validAssistantHint,
            preference: validPreference
        )
        let chosenOption: ModelSelectionOption?
        let rememberedVariant: String?
        switch chosen {
        case let .cached(value):
            chosenOption = catalog.option(providerID: value.providerID, modelID: value.modelID)
            rememberedVariant = value.variant
        case let .history(value):
            chosenOption = catalog.option(providerID: value.providerID, modelID: value.modelID)
            rememberedVariant = value.variant
        case let .preference(value):
            chosenOption = catalog.option(providerID: value.providerID, modelID: value.modelID)
            rememberedVariant = value.variant
        case nil:
            chosenOption = nil
            rememberedVariant = nil
        }
        let option = chosenOption ?? catalog.serverDefault ?? catalog.options[0]
        let variant = resolvedVariant(remembered: rememberedVariant, for: option)
        let variantNotice = rememberedVariant.flatMap {
            !ModelVariants.isDefault($0, available: option.variants) && !option.variants.contains($0)
                ? "Remembered Variant is unavailable. Using Default."
                : nil
        }
        initialSelectionState = initialSelectionState == .waitingForFallback ? .failedWithFallback : .resolved
        let wasProvisional = isSelectionProvisional
        isSelectionProvisional = false
        let droppedForModel = state.composer.attachments.filter { !option.acceptsAttachmentKind($0.kind) }
        for attachment in droppedForModel {
            attachmentRevisions[attachment.id] = nil
        }
        updateComposer {
            $0.attachments.removeAll { !option.acceptsAttachmentKind($0.kind) }
            $0.modelOptions = catalog.options
            $0.selectedModel = ModelSelection(option: option, selectedVariant: variant)
            $0.selectedVariant = variant
            $0.selectedMode = resolveSelectedAgentID(
                chooseInitialAgent(
                    cached: validCached?.agentID,
                    cachedAt: validCached?.savedAtEpochMs,
                    userHint: historyHints.userAgent,
                    assistantHint: historyHints.assistantAgent
                ) ?? $0.selectedMode,
                in: $0.selectableAgents
            )
            $0.isModelSelectionReady = true
            $0.modelCatalogError = nil
            if staleCached != nil {
                $0.modelNotice = "Selected model is unavailable. Using \(option.modelLabel)."
            } else {
                $0.modelNotice = variantNotice
            }
        }
        discardStoredAttachments(droppedForModel)
        let source = chosen?.sourceName ?? (catalog.serverDefault == nil ? "first-usable" : "server-default")
        OpenCodeLog.sessionChat.info("modelSelection source=\(source) reconciled=\(wasProvisional)")
        if case let .cached(value)? = chosen {
            // Keep the original timestamp when the session cache wins.
            recordLastSelection(value)
        } else if staleCached == nil {
            persistCurrentSelection(updatePreference: false)
        }
    }

    private func chooseInitialSelection(
        cached: SessionModelSelection?,
        userHint: LatestModelHint?,
        assistantHint: LatestModelHint?,
        preference: SessionModelSelection?
    ) -> InitialSelection? {
        if let userHint {
            guard let cached else { return .history(userHint) }
            guard let savedAt = cached.savedAtEpochMs else { return .history(userHint) }
            guard let historyTime = userHint.sentAtEpochMs else { return .cached(cached) }
            return historyTime > savedAt ? .history(userHint) : .cached(cached)
        }
        if let cached { return .cached(cached) }
        return assistantHint.map(InitialSelection.history) ?? preference.map(InitialSelection.preference)
    }

    private func chooseInitialAgent(
        cached: String?,
        cachedAt: Int64?,
        userHint: LatestAgentHint?,
        assistantHint: LatestAgentHint?
    ) -> String? {
        if let userHint {
            guard let cached, let cachedAt else { return userHint.mode }
            guard let historyTime = userHint.sentAtEpochMs else { return cached }
            return historyTime > cachedAt ? userHint.mode : cached
        }
        if let cached { return cached }
        return assistantHint?.mode
    }

    func updateComposerText(_ text: String, cursor: Int? = nil) {
        let cursor = cursor ?? text.count
        let previous = state.composer
        if text == previous.text {
            ensureSuggestionCatalogLoaded(
                for: ComposerMentionRouting.suggestionMode(
                    text: text,
                    cursor: cursor,
                    mentions: previous.agentMentions,
                    fileMentions: previous.fileMentions,
                    skillMentions: previous.skillMentions
                )
            )
            let clampedCursor = max(0, min(cursor, text.count))
            state.composer.cursor = clampedCursor
            refreshComposerSuggestions(
                for: text,
                cursor: clampedCursor,
                mentions: previous.agentMentions,
                fileMentions: previous.fileMentions,
                skillMentions: previous.skillMentions
            )
            notifyChange()
            scheduleFileSearch()
            return
        }

        draftRevision += 1
        let reconciled = ComposerMentionRouting.reconcileAfterEdit(
            previousText: previous.text,
            previousMentions: previous.agentMentions,
            proposedText: text,
            proposedCursor: cursor,
            previousFileMentions: previous.fileMentions,
            previousSkillMentions: previous.skillMentions
        )
        ensureSuggestionCatalogLoaded(
            for: ComposerMentionRouting.suggestionMode(
                text: reconciled.text,
                cursor: reconciled.cursor,
                mentions: reconciled.mentions,
                fileMentions: reconciled.fileMentions,
                skillMentions: reconciled.skillMentions
            )
        )
        let selectionChanged = reconciled.text != text || reconciled.cursor != cursor
        state.composer.text = reconciled.text
        state.composer.agentMentions = reconciled.mentions
        state.composer.fileMentions = reconciled.fileMentions
        state.composer.skillMentions = reconciled.skillMentions
        state.composer.cursor = reconciled.cursor
        if selectionChanged {
            state.composer.selectionRevision += 1
        }
        refreshComposerSuggestions(
            for: reconciled.text,
            cursor: reconciled.cursor,
            mentions: reconciled.mentions,
            fileMentions: reconciled.fileMentions,
            skillMentions: reconciled.skillMentions
        )
        notifyChange()
        scheduleFileSearch()
        schedulePersistDraft()
    }

    /// Inserts the pick at the caret, keeping the rest of the draft: a v2 skill becomes a `/name`
    /// span, a command becomes the leading `/name ` token. A draft without an open `/` query
    /// (the Commands sheet) gets the pick at the caret too.
    func selectSlashSuggestion(commandName: String) {
        let composer = state.composer
        let command = state.serverCommands.first { $0.name == commandName }
        var text = composer.text
        var cursor = max(0, min(composer.cursor, text.count))
        if ComposerMentionRouting.slashSuggestionQuery(
            text,
            cursor: cursor,
            mentions: composer.agentMentions,
            fileMentions: composer.fileMentions,
            skillMentions: composer.skillMentions
        ) == nil {
            // No open query at the caret: open one so the insert replaces only itself.
            let needsSpace = cursor > 0 && !text[text.index(text.startIndex, offsetBy: cursor - 1)].isWhitespace
            let opener = needsSpace ? " /" : "/"
            text.insert(contentsOf: opener, at: text.index(text.startIndex, offsetBy: cursor))
            cursor += opener.count
        }
        let result: MentionInsertResult
        if let command, ComposerSubmitRouting.isPromptSkill(command), let skillID = command.skillID {
            result = ComposerMentionRouting.insertSkillMention(
                text: text,
                cursor: cursor,
                skillID: skillID,
                name: command.name,
                mentionID: UUID().uuidString,
                existingMentions: composer.agentMentions,
                existingFileMentions: composer.fileMentions,
                existingSkillMentions: composer.skillMentions
            )
        } else {
            result = ComposerMentionRouting.insertCommandSelection(
                text: text,
                cursor: cursor,
                commandName: commandName,
                existingMentions: composer.agentMentions,
                existingFileMentions: composer.fileMentions,
                existingSkillMentions: composer.skillMentions
            )
        }
        applyInsert(result)
    }

    private func applyInsert(_ result: MentionInsertResult) {
        draftRevision += 1
        fileSearchTask?.cancel()
        fileSearchGeneration += 1
        fileSearchTask = nil
        state.composer.text = result.text
        state.composer.agentMentions = result.mentions
        state.composer.fileMentions = result.fileMentions
        state.composer.skillMentions = result.skillMentions
        state.composer.cursor = result.cursor
        state.composer.selectionRevision += 1
        // Close after select; the inserted token would otherwise still match the trigger.
        state.atSuggestionsVisible = false
        state.atSuggestions = []
        state.slashSuggestionsVisible = false
        state.slashSuggestions = []
        state.fileSuggestions = []
        state.fileSuggestionQuery = nil
        notifyChange()
        schedulePersistDraft()
    }

    func selectAtSuggestion(agentName: String) {
        let composer = state.composer
        let suggestion = state.atSuggestions.first { $0.id == agentName || $0.agentName == agentName }
        let result: MentionInsertResult
        if let filePath = suggestion?.filePath {
            result = ComposerMentionRouting.insertFileMention(
                text: composer.text,
                cursor: composer.cursor,
                path: filePath,
                mentionID: UUID().uuidString,
                existingMentions: composer.agentMentions,
                existingFileMentions: composer.fileMentions,
                existingSkillMentions: composer.skillMentions
            )
        } else {
            result = ComposerMentionRouting.insertMention(
                text: composer.text,
                cursor: composer.cursor,
                agentName: suggestion?.agentName ?? agentName,
                mentionID: UUID().uuidString,
                existingMentions: composer.agentMentions,
                existingFileMentions: composer.fileMentions,
                existingSkillMentions: composer.skillMentions
            )
        }
        applyInsert(result)
    }

    func dismissSuggestions() {
        state.slashSuggestionsVisible = false
        state.slashSuggestions = []
        state.atSuggestionsVisible = false
        state.atSuggestions = []
        notifyChange()
    }

    private func ensureSuggestionCatalogLoaded(for mode: ComposerSuggestionMode) {
        switch mode {
        case .at:
            if state.mentionableAgents.isEmpty { loadMentionableAgents(force: true) }
        case .slash:
            if state.serverCommands.isEmpty { loadServerCommands(force: true) }
        case .none:
            break
        }
    }

    func addAttachments(_ attachments: [Attachment]) {
        guard !attachments.isEmpty else { return }
        let option = state.composer.selectedModel?.option
        let accepted = attachments.filter { option?.acceptsAttachmentKind($0.kind) != false }
        let rejected = attachments.filter { option?.acceptsAttachmentKind($0.kind) == false }
        if !accepted.isEmpty {
            updateComposer { composer in
                var ordered = composer.attachments
                for attachment in accepted {
                    attachmentRevision += 1
                    attachmentRevisions[attachment.id] = attachmentRevision
                    if let index = ordered.firstIndex(where: { $0.id == attachment.id }) {
                        ordered[index] = attachment
                    } else {
                        ordered.append(attachment)
                    }
                }
                composer.attachments = ordered
            }
        }
        if !rejected.isEmpty {
            discardStoredAttachments(rejected)
            setAttachmentError(Self.unsupportedAttachmentMessage(for: rejected))
        } else if !accepted.isEmpty {
            state.errorMessage = nil
            notifyChange()
        }
    }

    func removeAttachment(id: String) {
        let removed = state.composer.attachments.filter { $0.id == id }
        attachmentRevisions[id] = nil
        updateComposer { $0.attachments.removeAll { $0.id == id } }
        discardStoredAttachments(removed)
    }

    func addAttachmentData(_ data: Data, filename: String, declaredMime: String?) {
        do {
            let attachment = try AttachmentLoader().load(data: data, filename: filename, declaredMime: declaredMime)
            addAttachments([attachment])
            if state.composer.attachments.contains(where: { $0.id == attachment.id }) {
                OpenCodeLog.sessionChat.info("attachment accepted mime=\(attachment.mime) bytes=\(data.count) count=1")
            }
        } catch {
            setAttachmentError(error.localizedDescription)
        }
    }

    func reportAttachmentReadFailure() {
        setAttachmentError(AttachmentLoadError.unreadableFile.localizedDescription)
    }

    func addAttachmentFiles(_ urls: [URL]) async {
        guard !urls.isEmpty else { return }
        let result = await Task.detached(priority: .userInitiated) {
            urls.map { url -> Result<Attachment, Error> in
                Result { try AttachmentLoader().load(fileURL: url) }
            }
        }.value
        let attachments = result.compactMap { try? $0.get() }
        addAttachments(attachments)
        let failures = result.compactMap { result -> Error? in
            guard case let .failure(error) = result else { return nil }
            return error
        }
        if let failure = failures.first, state.errorMessage == nil {
            setAttachmentError(failure.localizedDescription)
        }
        OpenCodeLog.sessionChat.info("attachments selected accepted=\(attachments.count) rejected=\(result.count - attachments.count)")
    }

    private func setAttachmentError(_ message: String) {
        state.errorMessage = message
        OpenCodeLog.sessionChat.warning("attachment rejected error=\(message)")
        notifyChange()
    }

    private static func unsupportedAttachmentMessage(for rejected: [Attachment]) -> String {
        let kinds = Set(rejected.map(\.kind))
        if kinds == [.image] {
            return "This model does not support image attachments."
        }
        if kinds == [.pdf] {
            return "This model does not support PDF attachments."
        }
        return "This model does not support some of the selected attachments."
    }

    func selectModel(_ option: ModelSelectionOption) {
        settleProvisionalSelection()
        let dropped = state.composer.attachments.filter { !option.acceptsAttachmentKind($0.kind) }
        for attachment in dropped {
            attachmentRevisions[attachment.id] = nil
        }
        updateComposer { composer in
            let previousVariant = composer.selectedVariant
            composer.attachments.removeAll { !option.acceptsAttachmentKind($0.kind) }
            composer.selectedModel = ModelSelection(
                option: option,
                selectedVariant: previousVariant.flatMap { option.variants.contains($0) ? $0 : nil }
            )
            composer.selectedVariant = composer.selectedModel?.selectedVariant
            composer.modelNotice = nil
        }
        persistCurrentSelection(updatePreference: true)
        discardStoredAttachments(dropped)
    }

    func selectMode(_ mode: String) {
        guard let match = state.composer.selectableAgents.first(where: {
            $0.id.compare(mode, options: .caseInsensitive) == .orderedSame
        }) else { return }
        settleProvisionalSelection()
        updateComposer { $0.selectedMode = match.id }
        persistCurrentSelection(updatePreference: false)
    }

    func selectVariant(_ variant: String) {
        settleProvisionalSelection()
        updateComposer { composer in
            if variant == ModelSelection.defaultVariantLabel {
                composer.selectedVariant = nil
                composer.selectedModel = composer.selectedModel.map { ModelSelection(option: $0.option, selectedVariant: nil) }
                return
            }
            guard composer.selectedModel?.option.variants.contains(variant) == true else { return }
            composer.selectedVariant = variant
            composer.selectedModel = composer.selectedModel.map { ModelSelection(option: $0.option, selectedVariant: variant) }
        }
        persistCurrentSelection(updatePreference: true)
    }

    func restoreComposerText(_ text: String) {
        draftRevision += 1
        updateComposer {
            $0.text = text
            $0.agentMentions = []
            $0.fileMentions = []
            $0.skillMentions = []
            $0.cursor = text.count
            $0.selectionRevision += 1
        }
        schedulePersistDraft()
    }

    @discardableResult
    func toggleFollowUp() -> FollowUpDelivery {
        let next = state.composer.followUpDelivery.toggled
        state.composer.followUpDelivery = next
        state.followUpNotice = next.switchedSnackbar
        notifyChange()
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(2200))
            if state.followUpNotice == next.switchedSnackbar {
                state.followUpNotice = nil
                notifyChange()
            }
        }
        return next
    }

    func sendPrompt(
        sessionID: String,
        onOptimisticRows: ([SessionChatRow]) async -> Void,
        onSendAccepted: () async -> Void,
        onSendFailed: (String, String) -> Void,
        isSessionBusy: Bool,
        supportsFollowUp: Bool,
        waitToSend: (() async -> Bool)? = nil,
        serverAlreadyAccepted: () -> Bool = { false },
        beforePrompt: () async throws -> Void = {}
    ) async {
        if ComposerShellCommand.isShellMode(state.composer.text) {
            runShellCommand(sessionID: sessionID, isSessionBusy: isSessionBusy, supportsFollowUp: supportsFollowUp)
            return
        }
        let submittedText = state.composer.text
        let submittedMentions = state.composer.agentMentions
        let submittedFileMentions = state.composer.fileMentions
        let submittedSkillMentions = state.composer.skillMentions
        let trimmedPrompt = ComposerMentionRouting.trimForSend(
            text: submittedText,
            mentions: state.composer.agentMentions,
            fileMentions: state.composer.fileMentions,
            skillMentions: state.composer.skillMentions
        )
        let trimmed = trimmedPrompt.text
        let mentionsForSend = trimmedPrompt.agentMentions
        let filesForSend = trimmedPrompt.fileMentions
        let skillsForSend = Self.admitLeadingSkill(
            text: trimmed,
            skillMentions: trimmedPrompt.skillMentions,
            commands: state.serverCommands
        )
        let submittedAttachments = state.composer.attachments
        let submittedDraftRevision = draftRevision
        let submittedAttachmentRevisions = Dictionary(
            uniqueKeysWithValues: submittedAttachments.compactMap { attachment in
                attachmentRevisions[attachment.id].map { (attachment.id, $0) }
            }
        )
        guard !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        guard (!trimmed.isEmpty || !submittedAttachments.isEmpty),
              let selectedModel = state.composer.selectedModel,
              state.composer.isModelSelectionReady,
              !state.isSending
        else { return }

        let composer = state.composer

        let attachmentBlocks = submittedAttachments.map { attachment in
            SessionChatTimelineBlock.attachment(
                blockID: PresentationBlockId.direct(attachment.id),
                file: FilePart(
                    id: attachment.id,
                    filename: attachment.filename,
                    mime: attachment.mime,
                    kind: attachment.kind,
                    dataURL: "",
                    previewData: attachment.previewData
                )
            )
        }
        let collapsedDisplay =
            CommandInvocationCollapser.collapseSkillMentions(text: trimmed, skillMentions: skillsForSend)
                ?? CommandInvocationCollapser.collapse(text: trimmed, commands: state.serverCommands)
        let displayText = collapsedDisplay?.text ?? trimmed
        let optimisticBlocks =
            (displayText.isEmpty
                ? []
                : [
                    SessionChatTimelineBlock.text(
                        blockID: PresentationBlockId.direct("optimistic-text"),
                        text: displayText,
                        renderedMarkdown: nil
                    ),
                ]) + attachmentBlocks

        let optimistic = SessionChatRow(
            id: "pending-\(UUID().uuidString)",
            role: .user,
            text: displayText,
            metadataLabel: metadataLabel(
                mode: composer.selectedModeLabel,
                model: composer.selectedModel?.modelLabel,
                variant: composer.selectedVariant
            ),
            pendingDelivery: (supportsFollowUp && isSessionBusy) ? composer.followUpDelivery : nil,
            isPending: true,
            isStreaming: false,
            renderedMarkdown: nil,
            blocks: optimisticBlocks,
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )

        let prepared = await messageRepository.prepareOptimisticUserMessage(id: optimistic.id, text: displayText)
        // Keep collapsed slash tokens as plain text so `[/name]` can use
        // syntax-type highlight instead of markdown paint.
        let enriched = [
            row(
                from: prepared,
                optimistic: optimistic,
                preferPlainCommandHighlight: collapsedDisplay != nil
            ),
        ]
        clearAcceptedSubmission(
            textRevision: submittedDraftRevision,
            attachmentRevisions: submittedAttachmentRevisions
        )
        state.isSending = true
        state.errorMessage = nil
        notifyChange()
        await onOptimisticRows(enriched)

        if let waitToSend {
            let proceed = await waitToSend()
            if !proceed {
                restoreFailedSubmission(
                    textRevision: submittedDraftRevision,
                    submittedText: submittedText,
                    submittedMentions: submittedMentions,
                    submittedFileMentions: submittedFileMentions,
                    submittedSkillMentions: submittedSkillMentions,
                    submittedAttachments: submittedAttachments,
                    submittedAttachmentRevisions: submittedAttachmentRevisions
                )
                let message = "Couldn't send. Retry when the connection is live."
                setSendError(message)
                onSendFailed(optimistic.id, message)
                return
            }
        }

        do {
            let intent = ComposerSubmitRouting.resolve(
                text: trimmed,
                commands: state.serverCommands
            )
            let hasAttachments = !submittedAttachments.isEmpty
            try await PromptSendProgressReporting.$handler.withValue({ [weak self] sent, total in
                Task { @MainActor in
                    self?.applySendProgress(sent: sent, total: total, hasAttachments: hasAttachments)
                }
            }) {
                @MainActor
                func sendPrompt(
                    text: String,
                    skillMentions: [SkillMention],
                    mentions: [AgentMention],
                    fileMentions: [FileMention],
                    sessionID: String,
                    attachments: [Attachment],
                    model: ModelSelection,
                    worktree: String?
                ) async throws {
                    try await beforePrompt()
                    func send(delivery: FollowUpDelivery) async throws {
                        try await messageRepository.sendPromptAsync(
                            sessionID: sessionID,
                            options: PromptSendOptions(
                                text: text,
                                attachments: attachments,
                                model: PromptModelSelection(
                                    providerID: model.option.providerID,
                                    modelID: model.option.modelID
                                ),
                                variant: composer.selectedVariant,
                                mode: composer.selectedMode,
                                agentMentions: mentions,
                                fileMentions: fileMentions,
                                worktree: worktree,
                                skillMentions: skillMentions,
                                delivery: delivery
                            ),
                            directory: worktree
                        )
                    }
                    do {
                        try await send(delivery: composer.followUpDelivery)
                    } catch {
                        if supportsFollowUp,
                           composer.followUpDelivery != .queue,
                           errorMessage(error) == "Session is busy"
                        {
                            try await send(delivery: .queue)
                        } else {
                            throw error
                        }
                    }
                    if !mentions.isEmpty || !fileMentions.isEmpty || !skillMentions.isEmpty {
                        OpenCodeLog.sessionChat.info(
                            "prompt send agentParts=\(mentions.count) fileParts=\(fileMentions.count) skills=\(skillMentions.count)"
                        )
                    }
                }
                switch intent {
                case let .serverCommand(command, arguments):
                    try await commandRepository.runCommand(
                        sessionID: sessionID,
                        options: CommandRunOptions(
                            command: command,
                            arguments: arguments,
                            attachments: submittedAttachments,
                            model: PromptModelSelection(
                                providerID: selectedModel.option.providerID,
                                modelID: selectedModel.option.modelID
                            ),
                            variant: composer.selectedVariant,
                            mode: composer.selectedMode,
                            delivery: composer.followUpDelivery,
                            skillIDs: ComposerMentionRouting.skillParts(text: trimmed, mentions: skillsForSend).map(\.skillID)
                        ),
                        directory: worktree
                    )
                case let .prompt(text):
                    try await sendPrompt(
                        text: text,
                        skillMentions: skillsForSend,
                        mentions: mentionsForSend,
                        fileMentions: filesForSend,
                        sessionID: sessionID,
                        attachments: submittedAttachments,
                        model: selectedModel,
                        worktree: worktree
                    )
                }
            }
            persistCurrentSelection(updatePreference: false)
            state.sendProgressPercent = nil
            notifyChange()
            await onSendAccepted()
            discardStoredAttachments(submittedAttachments)
        } catch {
            if isTransportFailure(error), serverAlreadyAccepted() {
                OpenCodeLog.sessionChat.info(
                    "sendPrompt transport error ignored; server already accepted the send"
                )
                persistCurrentSelection(updatePreference: false)
                state.sendProgressPercent = nil
                notifyChange()
                await onSendAccepted()
                discardStoredAttachments(submittedAttachments)
                return
            }
            restoreFailedSubmission(
                textRevision: submittedDraftRevision,
                submittedText: submittedText,
                submittedMentions: submittedMentions,
                submittedFileMentions: submittedFileMentions,
                submittedSkillMentions: submittedSkillMentions,
                submittedAttachments: submittedAttachments,
                submittedAttachmentRevisions: submittedAttachmentRevisions
            )
            DiagnosticLog.warning(.chat, "sendPrompt failed", error: error)
            let message = errorMessage(error)
            setSendError(message)
            onSendFailed(optimistic.id, message)
        }
    }

    /// Runs a shell-mode draft (`!command`) instead of sending a prompt, like the TUI and web app.
    /// The output arrives through the session's shell or message events, so there is no optimistic
    /// row and no send progress: the server answers only when the command ends, so the request runs in its
    /// own task and the composer stays free meanwhile. Attachments stay in the composer because a
    /// command cannot carry them. A failure restores the draft (unless it was edited since) and
    /// shows the error the way a failed prompt does.
    private func runShellCommand(sessionID: String, isSessionBusy: Bool, supportsFollowUp: Bool) {
        let composer = state.composer
        guard let command = ComposerShellCommand.command(composer.text),
              !OptimisticSessionCreate.isPlaceholder(sessionID),
              composer.isModelSelectionReady,
              !state.isSending
        else { return }
        // Like the TUI and web app, a command runs now or not at all; it cannot wait in the queue.
        if supportsFollowUp, isSessionBusy, composer.followUpDelivery == .queue {
            state.errorMessage = Self.shellCannotQueueError
            notifyChange()
            return
        }
        let submittedText = composer.text
        let submittedMentions = composer.agentMentions
        let submittedFileMentions = composer.fileMentions
        let submittedSkillMentions = composer.skillMentions
        let submittedDraftRevision = draftRevision
        let options = ShellCommandOptions(
            command: command,
            agent: composer.selectedMode,
            model: composer.selectedModel.map {
                PromptModelSelection(providerID: $0.option.providerID, modelID: $0.option.modelID)
            }
        )
        clearAcceptedSubmission(textRevision: submittedDraftRevision, attachmentRevisions: [:])
        state.errorMessage = nil
        notifyChange()
        let messageRepository = messageRepository
        let worktree = worktree
        Task { [weak self] in
            do {
                try await messageRepository.runShellCommand(sessionID: sessionID, options: options, directory: worktree)
                DiagnosticLog.info(.chat, "shell command accepted session=\(sessionID)")
            } catch {
                DiagnosticLog.warning(.chat, "runShellCommand failed", error: error)
                guard let self else { return }
                self.restoreFailedSubmission(
                    textRevision: submittedDraftRevision,
                    submittedText: submittedText,
                    submittedMentions: submittedMentions,
                    submittedFileMentions: submittedFileMentions,
                    submittedSkillMentions: submittedSkillMentions,
                    submittedAttachments: [],
                    submittedAttachmentRevisions: [:]
                )
                self.state.errorMessage = self.errorMessage(error)
                self.notifyChange()
            }
        }
    }

    /// Leaves shell mode by dropping the leading `!`; the rest of the draft stays.
    func exitShellMode() {
        let composer = state.composer
        guard ComposerShellCommand.isShellMode(composer.text) else { return }
        updateComposerText(ComposerShellCommand.exitShellMode(composer.text), cursor: max(0, composer.cursor - 1))
    }

    func clearSending() {
        state.isSending = false
        state.sendProgressPercent = nil
        notifyChange()
    }

    func clearTransientErrors() {
        state.errorMessage = nil
        state.abortErrorMessage = nil
        notifyChange()
    }

    func setSendError(_ error: String) {
        state.isSending = false
        state.sendProgressPercent = nil
        state.errorMessage = error
        notifyChange()
    }

    private func discardStoredAttachments(_ attachments: [Attachment]) {
        attachments.forEach { AttachmentLimits.discard($0.fileURL) }
    }

    private func applySendProgress(sent: Int64, total: Int64, hasAttachments: Bool) {
        guard PromptSendProgress.shouldShow(bytesTotal: total, hasAttachments: hasAttachments) else { return }
        let percent = PromptSendProgress.percent(bytesSent: sent, bytesTotal: total)
        guard state.sendProgressPercent != percent else { return }
        state.sendProgressPercent = percent
        notifyChange()
    }

    func abortSession(timelineIsReady: Bool) async {
        state.isAborting = true
        state.abortErrorMessage = nil
        notifyChange()
        let extraIDs = latestTimelineState?.knownChildSessionIDs.subtracting([sessionID]) ?? []
        OpenCodeLog.sessionChat.info(
            "abort start session=\(self.sessionID) children=\(extraIDs.sorted().joined(separator: ","))"
        )
        do {
            _ = try await sessionRepository.abortSession(id: sessionID, directory: worktree)
            for childID in extraIDs {
                do {
                    _ = try await sessionRepository.abortSession(id: childID, directory: worktree)
                } catch {
                    OpenCodeLog.sessionChat.error(
                        "abort child failed session=\(self.sessionID) child=\(childID) message=\(error.localizedDescription)"
                    )
                }
            }
            state.isAborting = false
            state.abortErrorMessage = nil
            OpenCodeLog.sessionChat.info("abort success session=\(self.sessionID)")
        } catch {
            OpenCodeLog.sessionChat.error(
                "abort failed session=\(self.sessionID) message=\(error.localizedDescription)"
            )
            state.isAborting = false
            if timelineIsReady {
                state.abortErrorMessage = errorMessage(error)
            }
        }
        notifyChange()
    }

    private func currentDraft() -> ComposerDraft {
        ComposerDraft(
            text: state.composer.text,
            agentMentions: state.composer.agentMentions,
            fileMentions: state.composer.fileMentions,
            skillMentions: state.composer.skillMentions
        )
    }

    private func applyDraft(_ draft: ComposerDraft) {
        state.composer.text = draft.text
        state.composer.agentMentions = draft.agentMentions
        state.composer.fileMentions = draft.fileMentions
        state.composer.skillMentions = draft.skillMentions
        state.composer.cursor = draft.text.count
        state.composer.selectionRevision += 1
        refreshComposerSuggestions(
            for: draft.text,
            cursor: draft.text.count,
            mentions: draft.agentMentions,
            fileMentions: draft.fileMentions,
            skillMentions: draft.skillMentions
        )
        notifyChange()
        scheduleFileSearch()
    }

    private func applyOrLoadDraft(preserve: ComposerDraft?) {
        if OptimisticSessionCreate.isPlaceholder(sessionID) { return }
        if let preserve, !preserve.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            applyDraft(preserve)
            draftRevision += 1
            schedulePersistDraft()
            return
        }
        draftLoadGeneration += 1
        let generation = draftLoadGeneration
        guard let identity = currentIdentity() else { return }
        let loaded = composerDraftStore.load(
            connectionIdentity: identity,
            worktree: worktree,
            sessionID: sessionID
        )
        guard generation == draftLoadGeneration, draftRevision == 0 else { return }
        if let loaded, !loaded.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            applyDraft(loaded)
        }
    }

    private func schedulePersistDraft() {
        persistDraftTask?.cancel()
        let sessionID = sessionID
        persistDraftTask = Task {
            try? await Task.sleep(for: .milliseconds(Self.draftPersistDebounceMs))
            guard !Task.isCancelled else { return }
            persistDraft(sessionID: sessionID, draft: currentDraft())
        }
    }

    private func persistDraft(sessionID: String, draft: ComposerDraft) {
        if OptimisticSessionCreate.isPlaceholder(sessionID) { return }
        guard let identity = currentIdentity() else { return }
        if draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            composerDraftStore.delete(
                connectionIdentity: identity,
                worktree: worktree,
                sessionID: sessionID
            )
        } else {
            composerDraftStore.save(
                connectionIdentity: identity,
                worktree: worktree,
                sessionID: sessionID,
                draft: draft
            )
        }
    }

    private func currentIdentity() -> ModelSelectionConnectionIdentity? {
        guard let profile = activeConnectionManager.currentProfile() else { return nil }
        return ModelSelectionConnectionIdentity(serverBaseURL: profile.baseURL, username: profile.username)
    }

    private func loadCachedSelection() async {
        if let profile = activeConnectionManager.currentProfile() {
            let identity = ModelSelectionConnectionIdentity(serverBaseURL: profile.baseURL, username: profile.username)
            async let session = sessionModelSelectionStore.loadSession(
                connectionIdentity: identity,
                worktree: worktree,
                sessionID: sessionID
            )
            async let preference = sessionModelSelectionStore.loadNewSessionPreference(
                connectionIdentity: identity,
                worktree: worktree
            )
            let persisted = await session
            newSessionPreference = await preference
            // A pick made on the provisional selection already settled this session.
            guard initialSelectionState == .loadingCache else { return }
            cachedSelection = newerSelection(
                persisted,
                modelCatalogStore.lastSelection(worktree: worktree, sessionID: sessionID)
            )
        }
        guard initialSelectionState == .loadingCache else { return }
        initialSelectionState = .waitingForInputs
        applyInitialSelectionIfReady()
        notifyChange()
    }

    private func newerSelection(
        _ first: SessionModelSelection?,
        _ second: SessionModelSelection?
    ) -> SessionModelSelection? {
        guard let first else { return second }
        guard let second else { return first }
        return (second.savedAtEpochMs ?? .min) > (first.savedAtEpochMs ?? .min) ? second : first
    }

    /// Seeds the catalog and agents from the shared catalog, plus a provisional selection when
    /// this session has one of its own, so a reopened session never shows "Loading model…".
    /// A session opened for the first time waits for history instead of flashing a default.
    /// The network refresh and the history reconcile follow quietly.
    private func seedFromCatalogStore() {
        guard let entry = modelCatalogStore.peek(worktree: worktree) else { return }
        if let agents = entry.selectableAgents {
            seededSelectableAgents = true
            let catalog = agents.isEmpty ? SelectableAgent.fallback : agents
            state.composer.selectableAgents = catalog
            state.composer.selectedMode = resolveSelectedAgentID(state.composer.selectedMode, in: catalog)
        }
        guard let catalog = entry.catalog, !catalog.options.isEmpty else { return }
        loadedCatalog = catalog
        state.composer.modelOptions = catalog.options
        let identity = currentIdentity()
        let remembered = modelCatalogStore.lastSelection(worktree: worktree, sessionID: sessionID)
        let persisted = identity.flatMap {
            sessionModelSelectionStore.peekSession(connectionIdentity: $0, worktree: worktree, sessionID: sessionID)
        }
        // An optimistic session has no history; it gets the same default the create resolver seeds.
        let isOptimistic = OptimisticSessionCreate.isPlaceholder(sessionID)
        let preference = isOptimistic ? identity.flatMap {
            sessionModelSelectionStore.peekNewSessionPreference(connectionIdentity: $0, worktree: worktree)
        } : nil
        let candidate = [remembered, persisted, preference].compactMap { $0 }.first {
            catalog.option(providerID: $0.providerID, modelID: $0.modelID) != nil
        }
        guard candidate != nil || isOptimistic else { return }
        let option = candidate.flatMap { catalog.option(providerID: $0.providerID, modelID: $0.modelID) }
            ?? catalog.serverDefault
            ?? catalog.options[0]
        let variant = resolvedVariant(remembered: candidate?.variant, for: option)
        let agentID = (remembered ?? persisted)?.agentID
        isSelectionProvisional = true
        state.composer.selectedModel = ModelSelection(option: option, selectedVariant: variant)
        state.composer.selectedVariant = variant
        state.composer.selectedMode = resolveSelectedAgentID(
            agentID ?? state.composer.selectedMode,
            in: state.composer.selectableAgents
        )
        state.composer.isModelSelectionReady = true
        state.composer.modelCatalogError = nil
        let source = if candidate == nil {
            catalog.serverDefault == nil ? "first-usable" : "server-default"
        } else if candidate == remembered {
            "last-selection"
        } else if candidate == persisted {
            "session-cache"
        } else {
            "new-session-preference"
        }
        OpenCodeLog.sessionChat.info("modelSelection provisional source=\(source)")
    }

    /// An explicit pick on the provisional selection wins over the pending history reconcile.
    private func settleProvisionalSelection() {
        guard isSelectionProvisional else { return }
        isSelectionProvisional = false
        initialSelectionState = .resolved
    }

    private func recordLastSelection(_ selection: SessionModelSelection) {
        guard !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        modelCatalogStore.recordSelection(selection, worktree: worktree, sessionID: sessionID)
    }

    private func persistCurrentSelection(updatePreference: Bool) {
        guard let profile = activeConnectionManager.currentProfile(),
              let selectedModel = state.composer.selectedModel
        else { return }
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: profile.baseURL, username: profile.username)
        let timestamp = max(Int64(Date().timeIntervalSince1970 * 1_000), lastSelectionTimestamp + 1)
        lastSelectionTimestamp = timestamp
        let selection = SessionModelSelection(
            providerID: selectedModel.option.providerID,
            modelID: selectedModel.option.modelID,
            variant: state.composer.selectedVariant,
            agentID: state.composer.selectedMode,
            savedAtEpochMs: timestamp
        )
        cachedSelection = selection
        recordLastSelection(selection)
        Task {
            await sessionModelSelectionStore.saveSession(
                connectionIdentity: identity,
                worktree: worktree,
                sessionID: sessionID,
                selection: selection
            )
            if updatePreference {
                await sessionModelSelectionStore.saveNewSessionPreference(
                    connectionIdentity: identity,
                    worktree: worktree,
                    selection: selection
                )
            }
        }
    }

    private func staleCachedSelection() -> SessionModelSelection? {
        guard let cachedSelection else { return nil }
        let isAvailable = state.composer.modelOptions.contains {
            $0.providerID == cachedSelection.providerID && $0.modelID == cachedSelection.modelID
        }
        return isAvailable ? nil : cachedSelection
    }

    private func applyCatalog(_ catalog: ModelSelectionCatalog) {
        guard !catalog.options.isEmpty else {
            updateComposer {
                $0.isModelSelectionReady = false
                $0.modelCatalogError = "No usable models are available. Tap Model to retry."
                $0.modelNotice = "No usable models are available. Tap Model to retry."
            }
            return
        }
        loadedCatalog = catalog
        updateComposer {
            $0.modelOptions = catalog.options
            $0.modelCatalogError = nil
        }
        revalidateSelectedModel(in: catalog)
        applyInitialSelectionIfReady()
    }

    /// A refreshed catalog replaces the shown option and falls back when it dropped the model.
    private func revalidateSelectedModel(in catalog: ModelSelectionCatalog) {
        guard let selected = state.composer.selectedModel else { return }
        if let refreshed = catalog.option(providerID: selected.option.providerID, modelID: selected.option.modelID) {
            guard refreshed != selected.option else { return }
            let variant = resolvedVariant(remembered: state.composer.selectedVariant, for: refreshed)
            updateComposer {
                $0.selectedModel = ModelSelection(option: refreshed, selectedVariant: variant)
                $0.selectedVariant = variant
            }
            return
        }
        let fallback = catalog.serverDefault ?? catalog.options[0]
        OpenCodeLog.sessionChat.info("modelSelection refreshed catalog dropped selected model")
        let dropped = state.composer.attachments.filter { !fallback.acceptsAttachmentKind($0.kind) }
        for attachment in dropped {
            attachmentRevisions[attachment.id] = nil
        }
        updateComposer {
            $0.attachments.removeAll { !fallback.acceptsAttachmentKind($0.kind) }
            $0.selectedModel = ModelSelection(option: fallback, selectedVariant: nil)
            $0.selectedVariant = nil
            $0.modelNotice = "Selected model is unavailable. Using \(fallback.modelLabel)."
        }
        discardStoredAttachments(dropped)
    }

    private func resolvedVariant(remembered: String?, for option: ModelSelectionOption) -> String? {
        guard let remembered, !remembered.isEmpty else { return nil }
        return option.variants.contains(remembered) ? remembered : nil
    }

    private enum InitialSelectionState {
        case loadingCache
        case waitingForInputs
        case waitingForFallback
        case resolved
        case failedWithFallback
    }

    private func metadataLabel(mode: String, model: String?, variant: String?) -> String {
        [
            TitleCaseIdentifier.displayName(for: mode),
            model.map { TitleCaseIdentifier.displayName(for: $0) },
            variant.map { TitleCaseIdentifier.displayName(for: $0) },
            formatSentTime(Date()),
        ].compactMap { $0 }.joined(separator: " · ")
    }

    private func applySelectableAgents(_ agents: [SelectableAgent]) {
        let catalog = agents.isEmpty ? SelectableAgent.fallback : agents
        updateComposer { composer in
            composer.selectableAgents = catalog
            composer.selectedMode = resolveSelectedAgentID(composer.selectedMode, in: catalog)
        }
        applyInitialSelectionIfReady()
    }

    private func resolveSelectedAgentID(_ current: String, in catalog: [SelectableAgent]) -> String {
        if let match = catalog.first(where: { $0.id.compare(current, options: .caseInsensitive) == .orderedSame }) {
            return match.id
        }
        if let build = catalog.first(where: { $0.id.compare(SelectableAgent.defaultID, options: .caseInsensitive) == .orderedSame }) {
            return build.id
        }
        return catalog[0].id
    }

    private func formatSentTime(_ date: Date) -> String {
        sentTimeFormatter.string(from: date)
    }

    private func updateComposer(_ update: (inout SessionChatComposerState) -> Void) {
        var composer = state.composer
        update(&composer)
        state.composer = composer
        refreshComposerSuggestions(
            for: composer.text,
            cursor: composer.cursor,
            mentions: composer.agentMentions,
            fileMentions: composer.fileMentions,
            skillMentions: composer.skillMentions
        )
        notifyChange()
        scheduleFileSearch()
    }

    private func refreshComposerSuggestions(
        for text: String,
        cursor: Int,
        mentions: [AgentMention]? = nil,
        fileMentions: [FileMention]? = nil,
        skillMentions: [SkillMention]? = nil
    ) {
        let mentions = mentions ?? state.composer.agentMentions
        let fileMentions = fileMentions ?? state.composer.fileMentions
        let skillMentions = skillMentions ?? state.composer.skillMentions
        switch ComposerMentionRouting.suggestionMode(
            text: text,
            cursor: cursor,
            mentions: mentions,
            fileMentions: fileMentions,
            skillMentions: skillMentions
        ) {
        case let .at(query):
            let files = state.fileSuggestionQuery == query ? state.fileSuggestions : []
            state.atSuggestionsVisible = true
            state.atSuggestions = ComposerMentionRouting
                .filterAgents(state.mentionableAgents, query: query)
                .map { $0.toAtSuggestion() } + files
            state.slashSuggestionsVisible = false
            state.slashSuggestions = []
        case let .slash(query):
            state.slashSuggestionsVisible = true
            state.slashSuggestions = ComposerSubmitRouting
                .slashSuggestions(
                    state.serverCommands,
                    query: query,
                    isLeading: ComposerMentionRouting.slashQueryIsLeading(text, cursor: cursor),
                    skillMentions: skillMentions
                )
                .map { $0.toSlashSuggestion() }
            state.atSuggestionsVisible = false
            state.atSuggestions = []
        case .none:
            state.slashSuggestionsVisible = false
            state.slashSuggestions = []
            state.atSuggestionsVisible = false
            state.atSuggestions = []
        }
    }

    private func scheduleFileSearch() {
        let composer = state.composer
        let mode = ComposerMentionRouting.suggestionMode(
            text: composer.text,
            cursor: composer.cursor,
            mentions: composer.agentMentions,
            fileMentions: composer.fileMentions,
            skillMentions: composer.skillMentions
        )
        let query: String?
        if case let .at(value) = mode {
            query = value
        } else {
            query = nil
        }
        guard let query else {
            fileSearchTask?.cancel()
            fileSearchGeneration += 1
            fileSearchTask = nil
            if state.fileSuggestionQuery != nil || !state.fileSuggestions.isEmpty {
                state.fileSuggestions = []
                state.fileSuggestionQuery = nil
            }
            return
        }
        if query == state.fileSuggestionQuery, fileSearchTask == nil {
            return
        }
        guard let repository = projectFilesRepository else { return }
        let worktree = worktree
        fileSearchTask?.cancel()
        fileSearchGeneration += 1
        let generation = fileSearchGeneration
        let delay = fileSearchDelayMs
        fileSearchTask = Task { @MainActor [weak self] in
            if delay > 0 {
                try? await Task.sleep(for: .milliseconds(delay))
            }
            guard let self, !Task.isCancelled, generation == self.fileSearchGeneration else { return }
            let results: [ProjectEntry]
            do {
                results = try await repository.findFiles(
                    worktree: worktree,
                    query: query,
                    limit: ProjectFileLimits.findLimit
                )
            } catch is CancellationError {
                return
            } catch {
                results = []
            }
            guard generation == self.fileSearchGeneration else { return }
            let current = self.state.composer
            let currentMode = ComposerMentionRouting.suggestionMode(
                text: current.text,
                cursor: current.cursor,
                mentions: current.agentMentions,
                fileMentions: current.fileMentions,
                skillMentions: current.skillMentions
            )
            guard case let .at(currentQuery) = currentMode, currentQuery == query else { return }
            self.state.fileSuggestions = results.map { $0.toFileSuggestion() }
            self.state.fileSuggestionQuery = query
            self.refreshComposerSuggestions(
                for: current.text,
                cursor: current.cursor,
                mentions: current.agentMentions,
                fileMentions: current.fileMentions,
                skillMentions: current.skillMentions
            )
            self.notifyChange()
            if generation == self.fileSearchGeneration {
                self.fileSearchTask = nil
            }
        }
    }

    private func clearAcceptedSubmission(
        textRevision: Int,
        attachmentRevisions submittedAttachmentRevisions: [String: Int]
    ) {
        var composer = state.composer
        if draftRevision == textRevision {
            composer.text = ""
            composer.agentMentions = []
            composer.fileMentions = []
            composer.skillMentions = []
            composer.cursor = 0
            composer.selectionRevision += 1
        }
        composer.attachments.removeAll { attachment in
            guard let submittedRevision = submittedAttachmentRevisions[attachment.id] else { return false }
            return attachmentRevisions[attachment.id] == submittedRevision
        }
        for (id, revision) in submittedAttachmentRevisions where attachmentRevisions[id] == revision {
            attachmentRevisions[id] = nil
        }
        state.composer = composer
        refreshComposerSuggestions(
            for: composer.text,
            cursor: composer.cursor,
            mentions: composer.agentMentions,
            fileMentions: composer.fileMentions,
            skillMentions: composer.skillMentions
        )
        notifyChange()
        scheduleFileSearch()
        persistDraftTask?.cancel()
        persistDraft(sessionID: sessionID, draft: currentDraft())
    }

    private func restoreFailedSubmission(
        textRevision: Int,
        submittedText: String,
        submittedMentions: [AgentMention],
        submittedFileMentions: [FileMention],
        submittedSkillMentions: [SkillMention] = [],
        submittedAttachments: [Attachment],
        submittedAttachmentRevisions: [String: Int]
    ) {
        for (id, revision) in submittedAttachmentRevisions where attachmentRevisions[id] == nil {
            attachmentRevisions[id] = revision
        }
        var composer = state.composer
        if draftRevision == textRevision {
            composer.text = submittedText
            composer.agentMentions = submittedMentions
            composer.fileMentions = submittedFileMentions
            composer.skillMentions = submittedSkillMentions
            composer.cursor = submittedText.count
            composer.selectionRevision += 1
        }
        let currentIDs = Set(composer.attachments.map(\.id))
        composer.attachments = submittedAttachments.filter { !currentIDs.contains($0.id) } + composer.attachments
        state.composer = composer
        refreshComposerSuggestions(
            for: composer.text,
            cursor: composer.cursor,
            mentions: composer.agentMentions,
            fileMentions: composer.fileMentions,
            skillMentions: composer.skillMentions
        )
        notifyChange()
        scheduleFileSearch()
        persistDraftTask?.cancel()
        persistDraft(sessionID: sessionID, draft: currentDraft())
    }

    func flushDraft() {
        persistDraftTask?.cancel()
        persistDraftTask = nil
        persistDraft(sessionID: sessionID, draft: currentDraft())
    }

    private func isTransportFailure(_ error: Error) -> Bool {
        if let messageError = error as? MessageError, case .transport = messageError {
            return true
        }
        if let commandError = error as? CommandError, case .transport = commandError {
            return true
        }
        return false
    }

    private func errorMessage(_ error: Error) -> String {
        if let messageError = error as? MessageError {
            return messageError.localizedDescription
        }
        if let commandError = error as? CommandError {
            switch commandError {
            case let .http(_, message):
                return message ?? "Request failed"
            case .transport:
                return "Unable to reach the server"
            case .decoding:
                return "Unable to read server response"
            }
        }
        if let sessionError = error as? SessionError {
            switch sessionError {
            case let .http(_, message):
                return message ?? "Request failed"
            case .transport:
                return "Unable to reach the server"
            case .decoding:
                return "Unable to read server response"
            }
        }
        return error.localizedDescription
    }

    private func notifyChange() {
        onChange?()
    }

    /// A hand-typed leading `/name` that names a v2 skill is admitted as a span when no span
    /// already starts the draft, so the old single-token form still sends the skill.
    static func admitLeadingSkill(
        text: String,
        skillMentions: [SkillMention],
        commands: [ServerCommand]
    ) -> [SkillMention] {
        guard !skillMentions.contains(where: { $0.start == 0 }),
              let skill = ComposerSubmitRouting.leadingSkill(text: text, commands: commands),
              let skillID = skill.skillID
        else { return skillMentions }
        let token = "/\(skill.name)"
        let leading = SkillMention(id: "leading-skill", skillID: skillID, name: skill.name, start: 0, end: token.count)
        return ([leading] + skillMentions).sorted { $0.start < $1.start }
    }

    private func row(
        from prepared: DisplayMessage,
        optimistic: SessionChatRow,
        preferPlainCommandHighlight: Bool = false
    ) -> SessionChatRow {
        SessionChatRow(
            id: optimistic.id,
            role: optimistic.role,
            text: optimistic.text,
            metadataLabel: optimistic.metadataLabel,
            pendingDelivery: optimistic.pendingDelivery,
            isPending: optimistic.isPending,
            isStreaming: optimistic.isStreaming,
            renderedMarkdown: preferPlainCommandHighlight ? nil : prepared.renderedMarkdown,
            blocks: optimistic.blocks,
            modelProviderID: optimistic.modelProviderID,
            modelID: optimistic.modelID,
            contextInputTokens: optimistic.contextInputTokens
        )
    }

    private static let draftPersistDebounceMs = 300
    static let shellCannotQueueError = "Shell commands can't be queued. Switch to Steer to run it now."

    private enum InitialSelection {
        case cached(SessionModelSelection)
        case history(LatestModelHint)
        case preference(SessionModelSelection)

        var sourceName: String {
            switch self {
            case .cached: "session-cache"
            case let .history(value): value.source == .user ? "user-history" : "assistant-history"
            case .preference: "new-session-preference"
            }
        }
    }
}
