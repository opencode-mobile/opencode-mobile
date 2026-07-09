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
    private var cacheResolved = false
    private var appliedInitialModelSelection = false

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
        cacheResolved = false
        appliedInitialModelSelection = false
        state = SessionChatComposerRegionState()
        notifyChange()
    }

    func resetInitialModelSelection() {
        appliedInitialModelSelection = false
    }

    func loadCatalog() async {
        do {
            let catalog = try await modelRepository.listModelOptions(directory: worktree)
            applyCatalog(catalog)
        } catch {
            OpenCodeLog.sessionChat.warning("loadCatalog failed error=\(error.localizedDescription, privacy: .public)")
        }
    }

    func applyLatestModelHint(_ hint: LatestModelHint?) {
        guard let catalog = loadedCatalog, !catalog.options.isEmpty else { return }
        guard let hint else { return }
        guard let latestOption = catalog.option(providerID: hint.providerID, modelID: hint.modelID) else { return }
        updateFromCatalog(catalog, latestOption: latestOption, latestVariant: hint.variant)
    }

    func tryApplyInitialSelection(
        timelineState: SessionChatTimelineRegionState,
        messageHint: LatestModelHint?
    ) {
        guard !appliedInitialModelSelection else { return }
        guard cacheResolved else { return }
        guard !state.composer.modelOptions.isEmpty else { return }

        switch timelineState.timeline {
        case .loading:
            return
        case .error, .empty:
            applyResolvedInitialSelection(messageHint: messageHint, markAppliedWithoutHint: true)
        case .ready:
            let staleCached = staleCachedSelection()
            if let cachedSelection,
               staleCached == nil {
                appliedInitialModelSelection = true
                applyLatestModelHint(
                    LatestModelHint(
                        providerID: cachedSelection.providerID,
                        modelID: cachedSelection.modelID,
                        variant: cachedSelection.variant
                    )
                )
                return
            }
            if let messageHint {
                appliedInitialModelSelection = true
                applyLatestModelHint(messageHint)
                if let staleCached {
                    applyStaleCacheNotice(staleCached)
                }
            } else if !timelineState.messages.isEmpty {
                appliedInitialModelSelection = true
                if let staleCached {
                    applyStaleCacheNotice(staleCached)
                }
            }
        }
    }

    private func applyResolvedInitialSelection(
        messageHint: LatestModelHint?,
        markAppliedWithoutHint: Bool
    ) {
        let staleCached = staleCachedSelection()
        if let cachedSelection, staleCached == nil {
            appliedInitialModelSelection = true
            applyLatestModelHint(
                LatestModelHint(
                    providerID: cachedSelection.providerID,
                    modelID: cachedSelection.modelID,
                    variant: cachedSelection.variant
                )
            )
            return
        }
        if let messageHint {
            appliedInitialModelSelection = true
            applyLatestModelHint(messageHint)
            if let staleCached {
                applyStaleCacheNotice(staleCached)
            }
            return
        }
        if markAppliedWithoutHint {
            appliedInitialModelSelection = true
            if let staleCached {
                applyStaleCacheNotice(staleCached)
            }
        }
    }

    func updateComposerText(_ text: String) {
        updateComposer { $0.text = text }
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
        persistCurrentSelection()
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
        persistCurrentSelection()
    }

    func restoreComposerText(_ text: String) {
        updateComposer { $0.text = text }
    }

    func sendPrompt(
        sessionID: String,
        onOptimisticRows: ([SessionChatRow]) async -> Void,
        onSendAccepted: () async -> Void,
        onSendFailed: (String, String) -> Void
    ) async {
        let trimmed = state.composer.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let selectedModel = state.composer.selectedModel, !state.isSending else { return }

        var composer = state.composer
        composer.text = ""

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
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )

        let prepared = await messageRepository.prepareOptimisticUserMessage(id: optimistic.id, text: trimmed)
        let enriched = [row(from: prepared, optimistic: optimistic)]
        state.composer = composer
        state.isSending = true
        state.errorMessage = nil
        notifyChange()
        await onOptimisticRows(enriched)

        do {
            try await messageRepository.sendPromptAsync(
                sessionID: sessionID,
                options: PromptSendOptions(
                    text: trimmed,
                    model: PromptModelSelection(
                        providerID: selectedModel.option.providerID,
                        modelID: selectedModel.option.modelID
                    ),
                    variant: composer.selectedVariant,
                    mode: agentMode(mode: composer.selectedMode)
                ),
                directory: worktree
            )
            persistCurrentSelection()
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
        defer { cacheResolved = true }
        guard let serverBaseURL = activeConnectionManager.currentProfile()?.baseURL else { return }
        cachedSelection = await sessionModelSelectionStore.load(
            serverBaseURL: serverBaseURL,
            worktree: worktree,
            sessionID: sessionID
        )
        notifyChange()
    }

    private func persistCurrentSelection() {
        guard let serverBaseURL = activeConnectionManager.currentProfile()?.baseURL,
              let selectedModel = state.composer.selectedModel
        else { return }
        let selection = SessionModelSelection(
            providerID: selectedModel.option.providerID,
            modelID: selectedModel.option.modelID,
            variant: state.composer.selectedVariant
        )
        cachedSelection = selection
        Task {
            await sessionModelSelectionStore.save(
                serverBaseURL: serverBaseURL,
                worktree: worktree,
                sessionID: sessionID,
                selection: selection
            )
        }
    }

    private func staleCachedSelection() -> SessionModelSelection? {
        guard let cachedSelection else { return nil }
        let isAvailable = state.composer.modelOptions.contains {
            $0.providerID == cachedSelection.providerID && $0.modelID == cachedSelection.modelID
        }
        return isAvailable ? nil : cachedSelection
    }

    private func applyStaleCacheNotice(_ cached: SessionModelSelection) {
        let fallbackLabel = state.composer.selectedModel?.modelLabel ?? cached.modelID
        updateComposer {
            $0.modelNotice = "Selected model is unavailable. Using \(fallbackLabel)."
        }
    }

    private func applyCatalog(_ catalog: ModelSelectionCatalog) {
        guard !catalog.options.isEmpty else { return }
        loadedCatalog = catalog
        updateFromCatalog(catalog, latestOption: nil, latestVariant: nil)
    }

    private func updateFromCatalog(
        _ catalog: ModelSelectionCatalog,
        latestOption: ModelSelectionOption?,
        latestVariant: String?
    ) {
        let current = state.composer.selectedModel
        let currentOption = current.flatMap {
            catalog.option(providerID: $0.option.providerID, modelID: $0.option.modelID)
        }
        let desired = latestOption ?? currentOption
        let fallback = desired ?? catalog.serverDefault ?? catalog.options[0]
        let notice: String? = {
            guard let current, catalog.option(providerID: current.option.providerID, modelID: current.option.modelID) == nil else { return nil }
            return "Selected model is unavailable. Using \(fallback.modelLabel)."
        }()
        let variant = resolvedVariant(remembered: state.composer.selectedVariant ?? latestVariant, for: fallback)
        updateComposer { composer in
            composer.modelOptions = catalog.options
            composer.selectedModel = ModelSelection(option: fallback, selectedVariant: variant)
            composer.selectedVariant = variant
            composer.modelNotice = notice
        }
    }

    private func resolvedVariant(remembered: String?, for option: ModelSelectionOption) -> String? {
        guard let remembered, !remembered.isEmpty else { return nil }
        return option.variants.contains(remembered) ? remembered : nil
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
            modelProviderID: optimistic.modelProviderID,
            modelID: optimistic.modelID,
            contextInputTokens: optimistic.contextInputTokens
        )
    }
}
