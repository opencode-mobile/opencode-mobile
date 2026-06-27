import Foundation
import Observation
import OSLog

struct SessionChatRow: Identifiable, Equatable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
    let isPending: Bool
    let isStreaming: Bool
    let renderedMarkdown: NativeMarkdownDocument?
    let modelProviderID: String?
    let modelID: String?
    let contextInputTokens: Int64?

    static func == (lhs: SessionChatRow, rhs: SessionChatRow) -> Bool {
        lhs.id == rhs.id &&
            lhs.role == rhs.role &&
            lhs.text == rhs.text &&
            lhs.metadataLabel == rhs.metadataLabel &&
            lhs.isPending == rhs.isPending &&
            lhs.isStreaming == rhs.isStreaming &&
            lhs.modelProviderID == rhs.modelProviderID &&
            lhs.modelID == rhs.modelID &&
            lhs.contextInputTokens == rhs.contextInputTokens &&
            lhs.renderedMarkdown?.sourceHash == rhs.renderedMarkdown?.sourceHash &&
            lhs.renderedMarkdown?.blocks.count == rhs.renderedMarkdown?.blocks.count
    }
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

struct SessionChatUiState: Equatable {
    var sessionID: String
    var sessionTitle: String
    var timeline: SessionChatTimelineState
    var messages: [SessionChatRow]
    var composer: SessionChatComposerState
    var isSending: Bool = false
    var isRefreshing: Bool = false
    var isAborting: Bool = false
    var sessionStatusLabel: String?
    var connectionStatus: SessionConnectionStatus = .offline
    var contextUsageFraction: Double?
    var errorMessage: String?

    static func initial(sessionID: String, sessionTitle: String) -> SessionChatUiState {
        SessionChatUiState(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            timeline: .loading,
            messages: [],
            composer: SessionChatComposerState(
                text: "",
                selectedModel: nil,
                modelOptions: [],
                selectedMode: SessionChatPickers.modes[0],
                selectedVariant: nil,
                modelNotice: nil
            )
        )
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
    private(set) var uiState: SessionChatUiState
    private let sessionID: String
    private let worktree: String
    private let messageRepository: any MessageRepository
    private let modelRepository: any ModelRepository
    private let sessionRepository: any SessionRepository
    private let messageLiveStore: any MessageLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let foregroundEventStream: any ForegroundEventStream
    private let eventAdapter = EventDomainAdapter()
    private let renderedMarkdownMapper: RenderedMarkdownMapper
    private let sentTimeFormatter: DateFormatter
    private var liveObservationTask: Task<Void, Never>?
    private var transformTask: Task<Void, Never>?
    private var isObservingMessages = false

    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelRepository: any ModelRepository,
        sessionRepository: any SessionRepository,
        messageLiveStore: any MessageLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream,
        renderedMarkdownMapper: RenderedMarkdownMapper = .shared
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
        self.modelRepository = modelRepository
        self.sessionRepository = sessionRepository
        self.messageLiveStore = messageLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.foregroundEventStream = foregroundEventStream
        self.renderedMarkdownMapper = renderedMarkdownMapper
        self.uiState = .initial(sessionID: sessionID, sessionTitle: sessionTitle)
        OpenCodeLog.sessionChat.info("bind session=\(sessionID, privacy: .public) worktree=\(worktree, privacy: .private) title=\(sessionTitle, privacy: .public)")
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        self.sentTimeFormatter = formatter
    }

    func startObserving() {
        liveObservationTask?.cancel()
        isObservingMessages = true
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID, privacy: .public)")
        liveObservationTask = Task {
            async let messageLoop: Void = observeMessages()
            async let statusLoop: Void = observeStatuses()
            async let connectionLoop: Void = observeConnectionState()
            async let eventLoop: Void = observeEvents()
            _ = await (messageLoop, statusLoop, connectionLoop, eventLoop)
        }
    }

    func loadMessages() async {
        SessionPerformanceProbe.emit("session_screen_started")
        uiState.timeline = .loading
        uiState.errorMessage = nil

        if let cachedMessages = messageLiveStore.cachedMessages(sessionID: sessionID, directory: worktree) {
            await applyLoadedMessagesTransform(cachedMessages, isRefreshing: true)
        }

        do {
            async let modelsLoad = modelRepository.listModelOptions(directory: worktree)
            if isObservingMessages {
                let catalog = try? await modelsLoad
                var messagesForCatalog = messageLiveStore.cachedMessages(sessionID: sessionID, directory: worktree) ?? []
                if let catalog {
                    applyModelCatalog(catalog, messages: messagesForCatalog)
                }
                if messagesForCatalog.isEmpty {
                    do {
                        _ = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
                        await awaitLiveStoreTimelineResolution()
                        if let catalog {
                            messagesForCatalog = messageLiveStore.cachedMessages(sessionID: sessionID, directory: worktree) ?? []
                            applyModelCatalog(catalog, messages: messagesForCatalog)
                        }
                    } catch {
                        applyError(error)
                    }
                } else if uiState.timeline == .loading {
                    await awaitLiveStoreTimelineResolution()
                }
            } else {
                let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
                if let catalog = try? await modelsLoad {
                    applyModelCatalog(catalog, messages: messages)
                }
                transformTask?.cancel()
                await applyLoadedMessagesTransform(messages, isRefreshing: false)
            }
            await refreshSessionStatus()
        } catch {
            applyError(error)
        }
    }

    func refreshMessages() async {
        if uiState.timeline == .ready {
            uiState.isRefreshing = true
            uiState.errorMessage = nil
        }

        do {
            let messages = try await messageRepository.listMessages(sessionID: sessionID, directory: worktree)
            transformTask?.cancel()
            await applyLoadedMessagesTransform(messages, isRefreshing: false)
        } catch {
            if uiState.timeline == .ready {
                uiState.isSending = false
                uiState.isRefreshing = false
                uiState.errorMessage = errorMessage(error)
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
        let trimmed = uiState.composer.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard let selectedModel = uiState.composer.selectedModel else { return }

        var composer = uiState.composer
        composer.text = ""

        let optimistic = SessionChatRow(
            id: "pending-\(UUID().uuidString)",
            role: .user,
            text: trimmed,
            metadataLabel: metadataLabel(mode: composer.selectedMode, model: composer.selectedModel?.modelLabel, variant: composer.selectedVariant),
            isPending: true,
            isStreaming: false,
            renderedMarkdown: nil,
            modelProviderID: nil,
            modelID: nil,
            contextInputTokens: nil
        )

        let existing = uiState.messages
        let enriched = await renderedMarkdownMapper.enrichRows([optimistic], previousByID: [:])
        uiState.composer = composer
        uiState.messages = existing + enriched
        uiState.timeline = .ready
        uiState.isSending = true
        uiState.isRefreshing = false
        uiState.errorMessage = nil
        refreshContextUsage()

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
                transformTask?.cancel()
                await applyLoadedMessagesTransform(messages, isRefreshing: false)
            } catch {
                // Prompt was accepted; live store or pull-to-refresh will sync the timeline.
                uiState.isSending = false
                uiState.isRefreshing = false
                uiState.errorMessage = nil
            }
        } catch {
            if uiState.timeline == .ready {
                uiState.messages = uiState.messages.filter { $0.id != optimistic.id }
                uiState.isSending = false
                uiState.isRefreshing = false
                uiState.errorMessage = errorMessage(error)
            } else {
                applyError(error)
            }
        }
    }

    func abortSession() async {
        uiState.isAborting = true
        do {
            _ = try await sessionRepository.abortSession(id: sessionID, directory: worktree)
            uiState.isAborting = false
        } catch {
            uiState.isAborting = false
            if uiState.timeline == .ready {
                uiState.errorMessage = errorMessage(error)
            }
        }
    }

    func stopObserving() {
        OpenCodeLog.sessionChat.info("stop observing session=\(self.sessionID, privacy: .public)")
        liveObservationTask?.cancel()
        liveObservationTask = nil
        isObservingMessages = false
    }

    private func observeMessages() async {
        for await messages in messageLiveStore.observeMessages(sessionID: sessionID, directory: worktree) {
            OpenCodeLog.sessionChat.debug("live messages session=\(self.sessionID, privacy: .public) count=\(messages.count)")
            applyLoadedMessages(messages, isRefreshing: false, fromQuietRefresh: true)
        }
    }

    private func observeStatuses() async {
        for await statuses in sessionStatusLiveStore.observeStatuses(directory: worktree) {
            let status = statuses[sessionID]
            OpenCodeLog.sessionChat.debug("live status session=\(self.sessionID, privacy: .public) type=\(status?.raw["type"] ?? "-", privacy: .public)")
            uiState.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
        }
    }

    private func observeConnectionState() async {
        uiState.connectionStatus = SessionConnectionStatus(connectionState: foregroundEventStream.connectionState)
        for await state in foregroundEventStream.connectionStateUpdates() {
            OpenCodeLog.sessionChat.debug("live connection session=\(self.sessionID, privacy: .public) state=\(String(describing: state), privacy: .public)")
            uiState.connectionStatus = SessionConnectionStatus(connectionState: state)
        }
    }

    private func observeEvents() async {
        for await event in foregroundEventStream.events() {
            let scope =
                if event.sessionID == sessionID {
                    "bound"
                } else if event.sessionID == nil {
                    "global"
                } else {
                    "other"
                }
            OpenCodeLog.sessionChat.debug(
                "SSE event observed session=\(self.sessionID, privacy: .public) type=\(event.type, privacy: .public) eventSession=\(event.sessionID ?? "-", privacy: .public) scope=\(scope, privacy: .public)"
            )
        }
    }

    private func applyLoadedMessages(
        _ messages: [Message],
        isRefreshing: Bool,
        fromQuietRefresh: Bool = false
    ) {
        transformTask?.cancel()
        transformTask = Task {
            await applyLoadedMessagesTransform(
                messages,
                isRefreshing: isRefreshing,
                fromQuietRefresh: fromQuietRefresh
            )
        }
    }

    private func applyLoadedMessagesTransform(
        _ messages: [Message],
        isRefreshing: Bool,
        fromQuietRefresh: Bool = false
    ) async {
        if fromQuietRefresh, hasStreamingDelta(messages) {
            try? await Task.sleep(nanoseconds: 40_000_000)
        }
        guard !Task.isCancelled else { return }

        let stateSnapshot = uiState
        let isSending = fromQuietRefresh ? stateSnapshot.isSending : false
        let previousErrorMessage = fromQuietRefresh ? stateSnapshot.errorMessage : nil
        let previousByID = Dictionary(uniqueKeysWithValues: stateSnapshot.messages.map { ($0.id, $0) })
        let serverRows =
            messages
                .enumerated()
                .sorted { left, right in
                    let leftTime = left.element.sentAtEpochMs
                    let rightTime = right.element.sentAtEpochMs
                    if let leftTime, let rightTime {
                        if leftTime != rightTime { return leftTime < rightTime }
                        return left.element.id < right.element.id
                    }
                    return left.offset < right.offset
                }
                .map { row(from: $0.element, previous: previousByID[$0.element.id]) }
        let rows =
            if fromQuietRefresh, isSending {
                mergeWithPending(serverRows, pendingRows: stateSnapshot.messages.filter(\.isPending))
            } else {
                serverRows
            }
        SessionPerformanceProbe.emit("markdown_preparation_started")
        let enriched = await renderedMarkdownMapper.enrichRows(rows, previousByID: previousByID)
        SessionPerformanceProbe.emit("markdown_preparation_finished")
        guard !Task.isCancelled else { return }

        let timeline: SessionChatTimelineState =
            if enriched.isEmpty {
                .empty
            } else {
                .ready
            }
        let resolvedTimeline: SessionChatTimelineState =
            if fromQuietRefresh, stateSnapshot.timeline == .error, enriched.isEmpty {
                .error
            } else {
                timeline
            }
        let resolvedErrorMessage: String? =
            if fromQuietRefresh {
                if !enriched.isEmpty, stateSnapshot.timeline == .error {
                    nil
                } else {
                    previousErrorMessage
                }
            } else {
                nil
            }

        uiState.timeline = resolvedTimeline
        uiState.messages = enriched
        uiState.isSending = fromQuietRefresh ? isSending : false
        uiState.isRefreshing = isRefreshing
        uiState.errorMessage = resolvedErrorMessage
        refreshContextUsage()
        SessionPerformanceProbe.emit("styled_timeline_published")
    }

    private func hasStreamingDelta(_ messages: [Message]) -> Bool {
        messages.contains { message in
            message.parts.contains { part in
                if case .text(let textPart) = part {
                    return textPart.isStreaming
                }
                return false
            }
        }
    }

    private func mergeWithPending(_ serverRows: [SessionChatRow], pendingRows: [SessionChatRow]) -> [SessionChatRow] {
        guard !pendingRows.isEmpty else { return serverRows }
        let pendingToKeep =
            pendingRows.filter { pending in
                !serverRows.contains(where: { $0.role == .user && $0.text == pending.text })
            }
        return serverRows + pendingToKeep
    }

    private func applyError(_ error: Error) {
        uiState.timeline = .error
        uiState.messages = []
        uiState.isSending = false
        uiState.errorMessage = errorMessage(error)
    }

    private func awaitLiveStoreTimelineResolution() async {
        guard uiState.timeline == .loading else { return }
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline {
            if uiState.timeline == .loading {
                if let transformTask {
                    await transformTask.value
                }
                if uiState.timeline == .loading {
                    try? await Task.sleep(nanoseconds: 10_000_000)
                    continue
                }
                return
            }
            return
        }
    }

    func row(from message: Message, previous: SessionChatRow? = nil) -> SessionChatRow {
        let isStreaming = message.parts.contains { part in
            if case .text(let t) = part, t.isStreaming { true } else { false }
        }
        let text = message.text
        let shouldRenderMarkdown = OpenCodeRenderedMarkdownDetector.shouldRender(text)
        let preservedMarkdown = previous?.renderedMarkdown.flatMap { document in
            shouldRenderMarkdown && (previous?.text == text || previous?.isStreaming == true) ? document : nil
        }
        return SessionChatRow(
            id: message.id,
            role: message.role,
            text: text,
            metadataLabel: metadataLabel(
                mode: message.mode,
                model: message.model,
                variant: message.variant,
                sentAtEpochMs: message.sentAtEpochMs
            ),
            isPending: false,
            isStreaming: isStreaming,
            renderedMarkdown: shouldRenderMarkdown ? preservedMarkdown : nil,
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
        let current = uiState.composer.selectedModel
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
            remembered: uiState.composer.selectedVariant ?? latestModelMessage?.variant,
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

    private func updateComposer(_ update: (inout SessionChatComposerState) -> Void) {
        var composer = uiState.composer
        update(&composer)
        uiState.composer = composer
        refreshContextUsage()
    }

    private func refreshContextUsage() {
        uiState.contextUsageFraction = Self.contextUsageFraction(
            messages: uiState.messages,
            modelOptions: uiState.composer.modelOptions,
            selectedModel: uiState.composer.selectedModel
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

    private func refreshSessionStatus() async {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: worktree) else { return }
        let status = statuses.first(where: { $0.sessionID == sessionID })
        uiState.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
    }
}

extension SessionChatRow {
    func withRenderedMarkdown(_ document: NativeMarkdownDocument?) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: role,
            text: text,
            metadataLabel: metadataLabel,
            isPending: isPending,
            isStreaming: isStreaming,
            renderedMarkdown: document,
            modelProviderID: modelProviderID,
            modelID: modelID,
            contextInputTokens: contextInputTokens
        )
    }
}

enum SessionChatPickers {
    static let modes = ["Build", "Plan"]
    static let modelFilterThreshold = 10
}
