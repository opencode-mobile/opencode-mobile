import Foundation
import Observation

struct SessionChatRow: Identifiable, Equatable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
    let isPending: Bool
    let isStreaming: Bool
    let modelProviderID: String?
    let modelID: String?
    let contextInputTokens: Int64?
}

struct SessionChatComposerState: Equatable {
    var text: String
    var selectedModel: ModelSelection?
    var modelOptions: [ModelSelectionOption]
    var selectedMode: String
    var selectedVariant: String?
    var modelNotice: String?

    var selectedModelLabel: String {
        selectedModel?.modelLabel ?? "Model"
    }

    var selectedVariantLabel: String {
        selectedVariant ?? ModelSelection.defaultVariantLabel
    }

    var variantOptions: [String] {
        [ModelSelection.defaultVariantLabel] + (selectedModel?.option.variants ?? [])
    }

    var canSelectVariant: Bool {
        selectedModel?.option.supportsVariants == true
    }
}

enum SessionChatTimelineState: Equatable {
    case loading
    case empty
    case ready
    case error
}

enum SessionChatScreenState: Equatable {
    case loading(sessionID: String, sessionTitle: String, composer: SessionChatComposerState)
    case empty(sessionID: String, sessionTitle: String, composer: SessionChatComposerState)
    case loaded(
        sessionID: String,
        sessionTitle: String,
        messages: [SessionChatRow],
        composer: SessionChatComposerState,
        isSending: Bool,
        isRefreshing: Bool,
        errorBanner: String?
    )
    case error(sessionID: String, sessionTitle: String, message: String, composer: SessionChatComposerState)

    var sessionID: String {
        switch self {
        case let .loading(id, _, _), let .empty(id, _, _), let .loaded(id, _, _, _, _, _, _), let .error(id, _, _, _):
            id
        }
    }

    var sessionTitle: String {
        switch self {
        case let .loading(_, title, _), let .empty(_, title, _), let .loaded(_, title, _, _, _, _, _), let .error(_, title, _, _):
            title
        }
    }

    var composer: SessionChatComposerState {
        switch self {
        case let .loading(_, _, composer), let .empty(_, _, composer), let .loaded(_, _, _, composer, _, _, _), let .error(_, _, _, composer):
            composer
        }
    }
}

enum SessionConnectionStatus: String, Equatable, Sendable {
    case live
    case connecting
    case offline

    init(connectionState: EventStreamConnectionState) {
        switch connectionState {
        case .connected:
            self = .live
        case .connecting, .reconnecting:
            self = .connecting
        case .idle, .disconnected:
            self = .offline
        }
    }
}

@MainActor
@Observable
final class SessionChatViewModel {
    private(set) var state: SessionChatScreenState
    private(set) var sessionStatusLabel: String?
    private(set) var connectionStatus: SessionConnectionStatus = .offline
    private(set) var contextUsageFraction: Double?
    private(set) var isAborting = false
    private let sessionID: String
    private let worktree: String
    private let messageRepository: any MessageRepository
    private let modelRepository: any ModelRepository
    private let sessionRepository: any SessionRepository
    private let messageLiveStore: any MessageLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let foregroundEventStream: any ForegroundEventStream
    private let eventAdapter = EventDomainAdapter()
    private let sentTimeFormatter: DateFormatter
    private var liveObservationTask: Task<Void, Never>?

    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelRepository: any ModelRepository,
        sessionRepository: any SessionRepository,
        messageLiveStore: any MessageLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
        self.modelRepository = modelRepository
        self.sessionRepository = sessionRepository
        self.messageLiveStore = messageLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.foregroundEventStream = foregroundEventStream
        self.state = .loading(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            composer: Self.defaultComposer()
        )
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        self.sentTimeFormatter = formatter
    }

    func startObserving() {
        liveObservationTask?.cancel()
        liveObservationTask = Task {
            async let messageLoop: Void = observeMessages()
            async let statusLoop: Void = observeStatuses()
            async let connectionLoop: Void = observeConnectionState()
            _ = await (messageLoop, statusLoop, connectionLoop)
        }
    }

    func loadMessages() async {
        let sessionID = state.sessionID
        let sessionTitle = state.sessionTitle
        let composer = state.composer
        state = .loading(sessionID: sessionID, sessionTitle: sessionTitle, composer: composer)

        do {
            async let messagesLoad = messageRepository.listMessages(sessionID: sessionID, directory: worktree)
            async let modelsLoad = modelRepository.listModelOptions(directory: worktree)
            let messages = try await messagesLoad
            if let catalog = try? await modelsLoad {
                applyModelCatalog(catalog, messages: messages)
            }
            applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
            await refreshSessionStatus()
        } catch {
            applyError(error, sessionID: sessionID)
        }
    }

    func refreshMessages() async {
        let sessionID = state.sessionID
        let sessionTitle = state.sessionTitle
        let composer = state.composer

        if case let .loaded(_, _, messages, _, isSending, _, _) = state {
            state = .loaded(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                messages: messages,
                composer: composer,
                isSending: isSending,
                isRefreshing: true,
                errorBanner: nil
            )
        }

        do {
            let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
            applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
        } catch {
            if case let .loaded(_, _, messages, _, _, _, _) = state {
                state = .loaded(
                    sessionID: sessionID,
                    sessionTitle: sessionTitle,
                    messages: messages,
                    composer: composer,
                    isSending: false,
                    isRefreshing: false,
                    errorBanner: errorMessage(error)
                )
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
    }

    func restoreComposerText(_ text: String) {
        updateComposer { $0.text = text }
    }

    func sendPrompt() async {
        let trimmed = state.composer.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard let selectedModel = state.composer.selectedModel else { return }

        let sessionID = state.sessionID
        let sessionTitle = state.sessionTitle
        var composer = state.composer
        composer.text = ""

        let optimistic = SessionChatRow(
            id: "pending-\(UUID().uuidString)",
            role: .user,
            text: trimmed,
            metadataLabel: metadataLabel(mode: composer.selectedMode, model: composer.selectedModel?.modelLabel, variant: composer.selectedVariant),
            isPending: true,
            isStreaming: false,
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )

        let existing = messagesFromCurrentState()
        state = .loaded(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            messages: existing + [optimistic],
            composer: composer,
            isSending: true,
            isRefreshing: false,
            errorBanner: nil
        )

        do {
            try await messageRepository.sendPromptAsync(
                sessionID: sessionID,
                options: PromptSendOptions(
                    text: trimmed,
                    model: PromptModelSelection(
                        providerID: selectedModel.option.providerID,
                        modelID: selectedModel.option.modelID
                    ),
                    variant: composer.selectedVariant
                ),
                directory: worktree
            )
            await refreshSessionStatus()
            do {
                let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
                applyLoadedMessages(messages, sessionID: sessionID, isRefreshing: false)
            } catch {
                // Prompt was accepted; live store or pull-to-refresh will sync the timeline.
                if case let .loaded(id, title, messages, composer, _, _, _) = state {
                    state = .loaded(
                        sessionID: id,
                        sessionTitle: title,
                        messages: messages,
                        composer: composer,
                        isSending: false,
                        isRefreshing: false,
                        errorBanner: nil
                    )
                }
            }
        } catch {
            if case let .loaded(id, title, messages, composer, _, _, _) = state {
                state = .loaded(
                    sessionID: id,
                    sessionTitle: title,
                    messages: messages.filter { $0.id != optimistic.id },
                    composer: composer,
                    isSending: false,
                    isRefreshing: false,
                    errorBanner: errorMessage(error)
                )
            } else {
                applyError(error, sessionID: sessionID)
            }
        }
    }

    func abortSession() async {
        isAborting = true
        do {
            _ = try await sessionRepository.abortSession(id: sessionID, directory: worktree)
            isAborting = false
        } catch {
            isAborting = false
            if case let .loaded(id, title, messages, composer, isSending, isRefreshing, _) = state {
                state = .loaded(
                    sessionID: id,
                    sessionTitle: title,
                    messages: messages,
                    composer: composer,
                    isSending: isSending,
                    isRefreshing: isRefreshing,
                    errorBanner: errorMessage(error)
                )
            }
        }
    }

    func stopObserving() {
        liveObservationTask?.cancel()
        liveObservationTask = nil
    }

    private func observeMessages() async {
        for await messages in messageLiveStore.observeMessages(sessionID: sessionID, directory: worktree) {
            applyLoadedMessages(
                messages,
                sessionID: sessionID,
                isRefreshing: false,
                fromQuietRefresh: true
            )
        }
    }

    private func observeStatuses() async {
        for await statuses in sessionStatusLiveStore.observeStatuses(directory: worktree) {
            let status = statuses[sessionID]
            sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
        }
    }

    private func observeConnectionState() async {
        connectionStatus = SessionConnectionStatus(connectionState: foregroundEventStream.connectionState)
        for await state in foregroundEventStream.connectionStateUpdates() {
            connectionStatus = SessionConnectionStatus(connectionState: state)
        }
    }

    private func applyLoadedMessages(
        _ messages: [Message],
        sessionID: String,
        isRefreshing: Bool,
        fromQuietRefresh: Bool = false
    ) {
        let sessionTitle = state.sessionTitle
        let composer = state.composer
        let isSending = fromQuietRefresh ? isSendingFromCurrentState() : false
        let errorBanner = fromQuietRefresh ? errorBannerFromCurrentState() : nil
        let serverRows =
            messages
                .sorted {
                    let leftTime = $0.sentAtEpochMs ?? Int64.max
                    let rightTime = $1.sentAtEpochMs ?? Int64.max
                    if leftTime != rightTime { return leftTime < rightTime }
                    return $0.id < $1.id
                }
                .map(row(from:))
        let rows =
            if fromQuietRefresh, isSending {
                mergeWithPending(serverRows, pendingRowsFromCurrentState())
            } else {
                serverRows
            }
        if rows.isEmpty {
            state = .empty(sessionID: sessionID, sessionTitle: sessionTitle, composer: composer)
        } else {
            state = .loaded(
                sessionID: sessionID,
                sessionTitle: sessionTitle,
                messages: rows,
                composer: composer,
                isSending: fromQuietRefresh ? isSending : false,
                isRefreshing: isRefreshing,
                errorBanner: fromQuietRefresh ? errorBanner : nil
            )
        }
        refreshContextUsage()
    }

    private func mergeWithPending(_ serverRows: [SessionChatRow], _ pendingRows: [SessionChatRow]) -> [SessionChatRow] {
        guard !pendingRows.isEmpty else { return serverRows }
        let pendingToKeep =
            pendingRows.filter { pending in
                !serverRows.contains(where: { $0.role == .user && $0.text == pending.text })
            }
        return serverRows + pendingToKeep
    }

    private func pendingRowsFromCurrentState() -> [SessionChatRow] {
        if case let .loaded(_, _, messages, _, _, _, _) = state {
            return messages.filter(\.isPending)
        }
        return []
    }

    private func isSendingFromCurrentState() -> Bool {
        if case let .loaded(_, _, _, _, isSending, _, _) = state {
            return isSending
        }
        return false
    }

    private func errorBannerFromCurrentState() -> String? {
        if case let .loaded(_, _, _, _, _, _, errorBanner) = state {
            return errorBanner
        }
        return nil
    }

    private func applyError(_ error: Error, sessionID: String) {
        state = .error(
            sessionID: sessionID,
            sessionTitle: state.sessionTitle,
            message: errorMessage(error),
            composer: state.composer
        )
    }

    func row(from message: Message) -> SessionChatRow {
        let isStreaming = message.parts.contains { part in
            if case .text(let t) = part, t.isStreaming { true } else { false }
        }
        return SessionChatRow(
            id: message.id,
            role: message.role,
            text: message.text,
            metadataLabel: metadataLabel(
                mode: message.mode,
                model: message.model,
                variant: message.variant,
                sentAtEpochMs: message.sentAtEpochMs
            ),
            isPending: false,
            isStreaming: isStreaming,
            modelProviderID: message.modelProviderID,
            modelID: message.modelID,
            contextInputTokens: message.contextInputTokens
        )
    }

    private func applyModelCatalog(_ catalog: ModelSelectionCatalog, messages: [Message]) {
        guard !catalog.options.isEmpty else { return }
        let latestModelMessage = messages
            .sorted {
                let leftTime = $0.sentAtEpochMs ?? Int64.min
                let rightTime = $1.sentAtEpochMs ?? Int64.min
                if leftTime != rightTime { return leftTime > rightTime }
                return $0.id > $1.id
            }
            .first { $0.modelProviderID != nil && $0.modelID != nil }
        let current = state.composer.selectedModel
        let currentOption = current.flatMap {
            catalog.option(providerID: $0.option.providerID, modelID: $0.option.modelID)
        }
        let desired = currentOption ?? latestModelMessage.flatMap { message in
            guard let providerID = message.modelProviderID, let modelID = message.modelID else { return nil }
            return catalog.option(providerID: providerID, modelID: modelID)
        }
        let fallback = desired ?? catalog.serverDefault ?? catalog.options[0]
        let notice: String? = {
            guard let current, catalog.option(providerID: current.option.providerID, modelID: current.option.modelID) == nil else { return nil }
            return "Selected model is unavailable. Using \(fallback.modelLabel)."
        }()
        let variant = resolvedVariant(
            remembered: state.composer.selectedVariant ?? latestModelMessage?.variant,
            for: fallback
        )
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

    private func metadataLabel(mode: String?, model: String?, variant: String? = nil, sentAtEpochMs: Int64? = nil) -> String {
        let timeLabel = sentAtEpochMs.map { sentTimeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval($0) / 1000)) }
        return [mode, model, variant, timeLabel].compactMap { $0 }.joined(separator: " · ")
    }

    private func messagesFromCurrentState() -> [SessionChatRow] {
        if case let .loaded(_, _, messages, _, _, _, _) = state {
            return messages
        }
        return []
    }

    private func updateComposer(_ update: (inout SessionChatComposerState) -> Void) {
        var composer = state.composer
        update(&composer)
        switch state {
        case let .loading(id, title, _):
            state = .loading(sessionID: id, sessionTitle: title, composer: composer)
        case let .empty(id, title, _):
            state = .empty(sessionID: id, sessionTitle: title, composer: composer)
        case let .loaded(id, title, messages, _, isSending, isRefreshing, errorBanner):
            state = .loaded(
                sessionID: id,
                sessionTitle: title,
                messages: messages,
                composer: composer,
                isSending: isSending,
                isRefreshing: isRefreshing,
                errorBanner: errorBanner
            )
        case let .error(id, title, message, _):
            state = .error(sessionID: id, sessionTitle: title, message: message, composer: composer)
        }
        refreshContextUsage()
    }

    private func refreshContextUsage() {
        contextUsageFraction = Self.contextUsageFraction(
            messages: messagesFromCurrentState(),
            modelOptions: state.composer.modelOptions,
            selectedModel: state.composer.selectedModel
        )
    }

    static func contextUsageFraction(
        messages: [SessionChatRow],
        modelOptions: [ModelSelectionOption],
        selectedModel: ModelSelection?
    ) -> Double? {
        guard let latest = messages.reversed().first(where: { $0.role == .assistant && $0.contextInputTokens != nil }),
              let inputTokens = latest.contextInputTokens
        else { return nil }
        let messageHasModel = latest.modelProviderID != nil && latest.modelID != nil
        let messageLimit = modelOptions.first {
            $0.providerID == latest.modelProviderID && $0.modelID == latest.modelID
        }?.contextLimitTokens
        guard let contextLimit = messageLimit ?? (messageHasModel ? nil : selectedModel?.option.contextLimitTokens),
              contextLimit > 0
        else { return nil }
        return min(max(Double(inputTokens) / Double(contextLimit), 0), 1)
    }

    private func errorMessage(_ error: Error) -> String {
        if let messageError = error as? MessageError {
            return messageError.localizedDescription
        }
        return error.localizedDescription
    }

    private static func defaultComposer() -> SessionChatComposerState {
        SessionChatComposerState(
            text: "",
            selectedModel: nil,
            modelOptions: [],
            selectedMode: SessionChatPickers.modes[0],
            selectedVariant: nil,
            modelNotice: nil
        )
    }

    private func refreshSessionStatus() async {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: worktree) else { return }
        let status = statuses.first(where: { $0.sessionID == sessionID })
        sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
    }
}

enum SessionChatPickers {
    static let modes = ["Build", "Plan"]
    static let modelFilterThreshold = 10
}
