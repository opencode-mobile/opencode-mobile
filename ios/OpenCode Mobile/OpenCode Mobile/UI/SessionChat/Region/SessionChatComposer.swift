import Foundation
import OSLog

@MainActor
final class SessionChatComposer {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatComposerRegionState()

    private let sessionID: String
    private let worktree: String
    private let messageRepository: any MessageRepository
    private let modelRepository: any ModelRepository
    private let sessionRepository: any SessionRepository
    private let sessionModelSelectionStore: any SessionModelSelectionStore
    private let activeConnectionManager: ActiveConnectionManager
    private let sentTimeFormatter: DateFormatter
    private var loadedCatalog: ModelSelectionCatalog?
    private var cachedSelection: SessionModelSelection?
    private var newSessionPreference: SessionModelSelection?
    private var initialSelectionState: InitialSelectionState = .loadingCache
    private var latestTimelineState: SessionChatTimelineRegionState?
    private var latestHistoryHints: ModelSelectionHistoryHints?
    private var lastSelectionTimestamp: Int64 = 0
    private var draftRevision = 0
    private var attachmentRevision = 0
    private var attachmentRevisions: [String: Int] = [:]

    init(
        sessionID: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelRepository: any ModelRepository,
        sessionRepository: any SessionRepository,
        sessionModelSelectionStore: any SessionModelSelectionStore,
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
        self.modelRepository = modelRepository
        self.sessionRepository = sessionRepository
        self.sessionModelSelectionStore = sessionModelSelectionStore
        self.activeConnectionManager = activeConnectionManager
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        self.sentTimeFormatter = formatter
        Task { await self.loadCachedSelection() }
    }

    func reset() {
        loadedCatalog = nil
        cachedSelection = nil
        newSessionPreference = nil
        initialSelectionState = .loadingCache
        latestTimelineState = nil
        latestHistoryHints = nil
        draftRevision = 0
        attachmentRevision = 0
        attachmentRevisions = [:]
        state = SessionChatComposerRegionState()
        notifyChange()
    }

    func loadCatalog() async {
        do {
            let catalog = try await modelRepository.listModelOptions(directory: worktree)
            applyCatalog(catalog)
        } catch {
            OpenCodeLog.sessionChat.warning("loadCatalog failed error=\(error.localizedDescription, privacy: .public)")
            updateComposer {
                $0.isModelSelectionReady = false
                $0.modelCatalogError = "Unable to load models. Tap Model to retry."
                $0.modelNotice = "Unable to load models. Tap Model to retry."
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
        guard let catalog = loadedCatalog, !catalog.options.isEmpty else { return }
        guard let timelineState = latestTimelineState else { return }

        switch timelineState.timeline {
        case .loading:
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
            !$0.isEmpty && !option.variants.contains($0) ? "Remembered Variant is unavailable. Using Default." : nil
        }
        initialSelectionState = initialSelectionState == .waitingForFallback ? .failedWithFallback : .resolved
        updateComposer {
            $0.modelOptions = catalog.options
            $0.selectedModel = ModelSelection(option: option, selectedVariant: variant)
            $0.selectedVariant = variant
            $0.isModelSelectionReady = true
            $0.modelCatalogError = nil
            if staleCached != nil {
                $0.modelNotice = "Selected model is unavailable. Using \(option.modelLabel)."
            } else {
                $0.modelNotice = variantNotice
            }
        }
        let source = chosen?.sourceName ?? (catalog.serverDefault == nil ? "first-usable" : "server-default")
        OpenCodeLog.sessionChat.info("modelSelection source=\(source, privacy: .public)")
        if case .cached? = chosen {
            // Keep the original timestamp when the session cache wins.
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

    func updateComposerText(_ text: String) {
        draftRevision += 1
        updateComposer { $0.text = text }
    }

    func addAttachments(_ attachments: [Attachment]) {
        guard !attachments.isEmpty else { return }
        updateComposer { composer in
            var ordered = composer.attachments
            for attachment in attachments {
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

    func removeAttachment(id: String) {
        attachmentRevisions[id] = nil
        updateComposer { $0.attachments.removeAll { $0.id == id } }
    }

    func addAttachmentData(_ data: Data, filename: String, declaredMime: String?) {
        do {
            let attachment = try AttachmentLoader().load(data: data, filename: filename, declaredMime: declaredMime)
            addAttachments([attachment])
            OpenCodeLog.sessionChat.info("attachment accepted mime=\(attachment.mime, privacy: .public) bytes=\(data.count) count=1")
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
        if let failure = failures.first {
            setAttachmentError(failure.localizedDescription)
        } else if !attachments.isEmpty {
            state.errorMessage = nil
            notifyChange()
        }
        OpenCodeLog.sessionChat.info("attachments selected accepted=\(attachments.count) rejected=\(result.count - attachments.count)")
    }

    private func setAttachmentError(_ message: String) {
        state.errorMessage = message
        OpenCodeLog.sessionChat.warning("attachment rejected error=\(message, privacy: .public)")
        notifyChange()
    }

    func selectModel(_ option: ModelSelectionOption) {
        updateComposer { composer in
            let previousVariant = composer.selectedVariant
            composer.selectedModel = ModelSelection(
                option: option,
                selectedVariant: previousVariant.flatMap { option.variants.contains($0) ? $0 : nil }
            )
            composer.selectedVariant = composer.selectedModel?.selectedVariant
            composer.modelNotice = nil
        }
        persistCurrentSelection(updatePreference: true)
    }

    func selectMode(_ mode: String) {
        guard SessionChatPickers.modes.contains(mode) else { return }
        updateComposer { $0.selectedMode = mode }
    }

    func selectVariant(_ variant: String) {
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
        updateComposer { $0.text = text }
    }

    func sendPrompt(
        sessionID: String,
        onOptimisticRows: ([SessionChatRow]) async -> Void,
        onSendAccepted: () async -> Void,
        onSendFailed: (String, String) -> Void
    ) async {
        let submittedText = state.composer.text
        let trimmed = submittedText.trimmingCharacters(in: .whitespacesAndNewlines)
        let submittedAttachments = state.composer.attachments
        let submittedDraftRevision = draftRevision
        let submittedAttachmentRevisions = Dictionary(
            uniqueKeysWithValues: submittedAttachments.compactMap { attachment in
                attachmentRevisions[attachment.id].map { (attachment.id, $0) }
            }
        )
        guard (!trimmed.isEmpty || !submittedAttachments.isEmpty),
              let selectedModel = state.composer.selectedModel,
              state.composer.isModelSelectionReady,
              !state.isSending
        else { return }

        let composer = state.composer

        let attachmentBlocks = submittedAttachments.map { attachment in
            SessionChatTimelineBlock.attachment(
                FilePart(
                    id: attachment.id,
                    filename: attachment.filename,
                    mime: attachment.mime,
                    kind: attachment.kind,
                    dataURL: attachment.dataURL
                )
            )
        }
        let optimisticBlocks = (trimmed.isEmpty ? [] : [SessionChatTimelineBlock.text(trimmed)]) + attachmentBlocks

        let optimistic = SessionChatRow(
            id: "pending-\(UUID().uuidString)",
            role: .user,
            text: trimmed,
            metadataLabel: metadataLabel(
                mode: composer.selectedMode,
                model: composer.selectedModel?.modelLabel,
                variant: composer.selectedVariant
            ),
            isPending: true,
            isStreaming: false,
            renderedMarkdown: nil,
            blocks: optimisticBlocks,
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )

        let prepared = await messageRepository.prepareOptimisticUserMessage(id: optimistic.id, text: trimmed)
        let enriched = [row(from: prepared, optimistic: optimistic)]
        state.isSending = true
        state.errorMessage = nil
        notifyChange()
        await onOptimisticRows(enriched)

        do {
            try await messageRepository.sendPromptAsync(
                sessionID: sessionID,
                options: PromptSendOptions(
                    text: trimmed,
                    attachments: submittedAttachments,
                    model: PromptModelSelection(
                        providerID: selectedModel.option.providerID,
                        modelID: selectedModel.option.modelID
                    ),
                    variant: composer.selectedVariant,
                    mode: agentMode(mode: composer.selectedMode)
                ),
                directory: worktree
            )
            clearAcceptedSubmission(
                textRevision: submittedDraftRevision,
                attachmentRevisions: submittedAttachmentRevisions
            )
            persistCurrentSelection(updatePreference: false)
            await onSendAccepted()
        } catch {
            let message = errorMessage(error)
            setSendError(message)
            onSendFailed(optimistic.id, message)
        }
    }

    func clearSending() {
        state.isSending = false
        notifyChange()
    }

    func setSendError(_ error: String) {
        state.isSending = false
        state.errorMessage = error
        notifyChange()
    }

    func abortSession(timelineIsReady: Bool) async {
        state.isAborting = true
        state.abortErrorMessage = nil
        notifyChange()
        do {
            _ = try await sessionRepository.abortSession(id: sessionID, directory: worktree)
            state.isAborting = false
            state.abortErrorMessage = nil
        } catch {
            state.isAborting = false
            if timelineIsReady {
                state.abortErrorMessage = errorMessage(error)
            }
        }
        notifyChange()
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
            cachedSelection = await session
            newSessionPreference = await preference
        }
        initialSelectionState = .waitingForInputs
        applyInitialSelectionIfReady()
        notifyChange()
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
            savedAtEpochMs: timestamp
        )
        cachedSelection = selection
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
        applyInitialSelectionIfReady()
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
        [mode, model, variant, formatSentTime(Date())].compactMap { $0 }.joined(separator: " · ")
    }

    private func agentMode(mode: String) -> String {
        switch mode {
        case "Plan": return "plan"
        default: return "build"
        }
    }

    private func formatSentTime(_ date: Date) -> String {
        sentTimeFormatter.string(from: date)
    }

    private func updateComposer(_ update: (inout SessionChatComposerState) -> Void) {
        var composer = state.composer
        update(&composer)
        state.composer = composer
        notifyChange()
    }

    private func clearAcceptedSubmission(
        textRevision: Int,
        attachmentRevisions submittedAttachmentRevisions: [String: Int]
    ) {
        var composer = state.composer
        if draftRevision == textRevision {
            composer.text = ""
        }
        composer.attachments.removeAll { attachment in
            guard let submittedRevision = submittedAttachmentRevisions[attachment.id] else { return false }
            return attachmentRevisions[attachment.id] == submittedRevision
        }
        for (id, revision) in submittedAttachmentRevisions where attachmentRevisions[id] == revision {
            attachmentRevisions[id] = nil
        }
        state.composer = composer
        notifyChange()
    }

    private func errorMessage(_ error: Error) -> String {
        if let messageError = error as? MessageError {
            return messageError.localizedDescription
        }
        return error.localizedDescription
    }

    private func notifyChange() {
        onChange?()
    }

    private func row(from prepared: DisplayMessage, optimistic: SessionChatRow) -> SessionChatRow {
        SessionChatRow(
            id: optimistic.id,
            role: optimistic.role,
            text: optimistic.text,
            metadataLabel: optimistic.metadataLabel,
            isPending: optimistic.isPending,
            isStreaming: optimistic.isStreaming,
            renderedMarkdown: prepared.renderedMarkdown,
            blocks: optimistic.blocks,
            modelProviderID: optimistic.modelProviderID,
            modelID: optimistic.modelID,
            contextInputTokens: optimistic.contextInputTokens
        )
    }

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
