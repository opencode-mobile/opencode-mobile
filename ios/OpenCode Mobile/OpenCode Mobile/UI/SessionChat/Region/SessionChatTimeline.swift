import Foundation
import OSLog

@MainActor
final class SessionChatTimeline {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatTimelineRegionState()

    private let sessionID: String
    private let worktree: String
    private let messageRepository: any MessageRepository
    private let sentTimeFormatter: DateFormatter
    private var observationTask: Task<Void, Never>?
    private var displayApplyTask: Task<Void, Never>?
    private var observationGeneration = 0
    private var mergePendingWhileSending = false
    private var pendingOptimisticRows: [SessionChatRow] = []
    private var pendingBaselineSignatureCounts: [String: Int] = [:]
    private var lastLoadedMessages: [Message] = []

    init(
        sessionID: String,
        worktree: String,
        messageRepository: any MessageRepository
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageRepository = messageRepository
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "h:mm a"
        self.sentTimeFormatter = formatter
    }

    func reset() {
        cancelObservationTasks()
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
        lastLoadedMessages = []
        state = SessionChatTimelineRegionState()
        notifyChange()
    }

    func startObserving() {
        cancelObservationTasks()
        let generation = observationGeneration
        state.timeline = .loading
        state.errorMessage = nil
        notifyChange()
        observationTask = Task {
            var pendingUpdate: DisplayMessageListUpdate?

            @MainActor
            func kickApply() {
                guard displayApplyTask == nil else { return }
                displayApplyTask = Task { @MainActor [weak self] in
                    guard let self else { return }
                    while let current = pendingUpdate {
                        guard self.observationGeneration == generation, !Task.isCancelled else { return }
                        pendingUpdate = nil
                        switch current {
                        case .loadFailed(let error):
                            applyError(error)
                        case .messages(let displayMessages):
                            await applyLoadedDisplayMessagesTransform(
                                displayMessages,
                                isRefreshing: false,
                                fromQuietRefresh: true
                            )
                        }
                        // Yield so the observation task can land a newer snapshot while we apply;
                        // without this, MainActor serialize makes coalesce-latest a no-op.
                        await Task.yield()
                    }
                    guard self.observationGeneration == generation else { return }
                    self.displayApplyTask = nil
                    if pendingUpdate != nil, !Task.isCancelled {
                        kickApply()
                    }
                }
            }

            for await update in messageRepository.observeDisplayMessages(sessionID: sessionID, directory: worktree) {
                guard observationGeneration == generation, !Task.isCancelled else { return }
                // Coalesce-latest while transform/SwiftUI apply is in flight.
                pendingUpdate = update
                kickApply()
            }
        }
    }

    func stopObserving() {
        cancelObservationTasks()
    }

    private func cancelObservationTasks() {
        observationGeneration &+= 1
        observationTask?.cancel()
        observationTask = nil
        displayApplyTask?.cancel()
        displayApplyTask = nil
    }

    func loadMessages() async {
        state.timeline = .loading
        state.errorMessage = nil
        notifyChange()
        do {
            let displayMessages = try await messageRepository.refreshDisplayMessages(sessionID: sessionID, directory: worktree)
            await applyLoadedDisplayMessagesTransform(displayMessages, isRefreshing: false)
        } catch {
            applyError(error)
        }
    }

    func refreshMessages() async {
        if state.timeline == .ready {
            state.isRefreshing = true
            state.errorMessage = nil
            notifyChange()
        }

        do {
            let displayMessages = try await messageRepository.refreshDisplayMessages(sessionID: sessionID, directory: worktree)
            await applyLoadedDisplayMessagesTransform(displayMessages, isRefreshing: false)
        } catch {
            if state.timeline == .ready {
                state.isRefreshing = false
                state.errorMessage = errorMessage(error)
                notifyChange()
            }
        }
    }

    func beginOptimisticSend(_ rows: [SessionChatRow]) {
        mergePendingWhileSending = true
        pendingOptimisticRows = rows
        pendingBaselineSignatureCounts = signatureCounts(state.messages.filter { !$0.isPending })
        state.messages.append(contentsOf: rows)
        state.timeline = .ready
        state.errorMessage = nil
        notifyChange()
    }

    func failOptimisticSend(optimisticID: String, error: String) {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
        state.messages.removeAll { $0.id == optimisticID }
        state.errorMessage = error
        notifyChange()
    }

    func clearSendingState() {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
    }

    func clearReadyError() {
        guard state.timeline == .ready else { return }
        state.errorMessage = nil
        notifyChange()
    }

    func reloadAfterSend(onComplete: @escaping () -> Void, onReloadFailed: @escaping () -> Void) async {
        do {
            let displayMessages = try await messageRepository.refreshDisplayMessages(sessionID: sessionID, directory: worktree)
            await applyLoadedDisplayMessagesTransform(displayMessages, isRefreshing: false, onPublished: onComplete)
        } catch {
            onReloadFailed()
        }
    }

    func latestModelHint() -> LatestModelHint? {
        latestModelHint(from: lastLoadedMessages)
    }

    func modelSelectionHistoryHints() -> ModelSelectionHistoryHints {
        let sorted = sortedByRecency(lastLoadedMessages)
        return ModelSelectionHistoryHints(
            user: latestModelHint(from: sorted, role: .user),
            assistant: latestModelHint(from: sorted, role: .assistant)
        )
    }

    func loadedMessages() -> [Message] {
        lastLoadedMessages
    }

    func row(from message: Message, previous: SessionChatRow? = nil) -> SessionChatRow? {
        row(from: DisplayMessage(message: message), previous: previous)
    }

    func row(from display: DisplayMessage, previous: SessionChatRow? = nil) -> SessionChatRow? {
        let message = display.message
        let structuralBlocks = SessionChatTimelineBlocks.build(role: message.role, parts: message.parts)
        let resolvedBlocks: [SessionChatTimelineBlock] =
            if !structuralBlocks.isEmpty {
                structuralBlocks
            } else if !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                [.text(message.text)]
            } else {
                []
            }
        guard !resolvedBlocks.isEmpty else { return nil }

        let isStreaming = display.isStreaming
        let renderedMarkdown = display.renderedMarkdown ?? previous?.renderedMarkdown.flatMap { document in
            isStreaming && (previous?.isStreaming == true || previous?.text == message.text) ? document : nil
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
            renderedMarkdown: renderedMarkdown,
            blocks: resolvedBlocks,
            modelProviderID: message.modelProviderID,
            modelID: message.modelID,
            contextInputTokens: message.contextInputTokens
        )
    }

    private func applyLoadedDisplayMessagesTransform(
        _ displayMessages: [DisplayMessage],
        isRefreshing: Bool,
        fromQuietRefresh: Bool = false,
        onPublished: (() -> Void)? = nil
    ) async {
        lastLoadedMessages = displayMessages.map(\.message)
        guard !Task.isCancelled else { return }

        let stateSnapshot = state
        let isSending = fromQuietRefresh ? mergePendingWhileSending : false
        let previousErrorMessage = fromQuietRefresh ? stateSnapshot.errorMessage : nil
        let previousByID = Dictionary(uniqueKeysWithValues: stateSnapshot.messages.map { ($0.id, $0) })
        let serverRows =
            displayMessages
                .enumerated()
                .sorted { left, right in
                    let leftTime = left.element.message.sentAtEpochMs
                    let rightTime = right.element.message.sentAtEpochMs
                    if let leftTime, let rightTime {
                        if leftTime != rightTime { return leftTime < rightTime }
                        return left.element.message.id < right.element.message.id
                    }
                    return left.offset < right.offset
                }
                .compactMap { row(from: $0.element, previous: previousByID[$0.element.id]) }
        let rows =
            if fromQuietRefresh, isSending {
                mergeWithPending(
                    serverRows,
                    pendingRows: pendingOptimisticRows.isEmpty
                        ? stateSnapshot.messages.filter(\.isPending)
                        : pendingOptimisticRows
                )
            } else {
                serverRows
            }
        guard !Task.isCancelled else { return }

        let timeline: SessionChatTimelineState =
            if rows.isEmpty { .empty } else { .ready }
        let resolvedTimeline: SessionChatTimelineState =
            if fromQuietRefresh, stateSnapshot.timeline == .error, rows.isEmpty {
                .error
            } else {
                timeline
            }
        let resolvedErrorMessage: String? =
            if fromQuietRefresh {
                if !rows.isEmpty, stateSnapshot.timeline == .error {
                    nil
                } else {
                    previousErrorMessage
                }
            } else {
                nil
            }

        state.timeline = resolvedTimeline
        state.messages = rows
        state.isRefreshing = isRefreshing
        state.errorMessage = resolvedErrorMessage
        if !fromQuietRefresh {
            mergePendingWhileSending = false
            pendingOptimisticRows = []
            pendingBaselineSignatureCounts = [:]
        }
        notifyChange()
        SessionPerformanceProbe.emit("styled_timeline_published")
        onPublished?()
    }

    private func applyError(_ error: Error) {
        mergePendingWhileSending = false
        pendingOptimisticRows = []
        pendingBaselineSignatureCounts = [:]
        lastLoadedMessages = []
        state.timeline = .error
        state.messages = []
        state.errorMessage = errorMessage(error)
        notifyChange()
    }

    private func latestModelHint(from messages: [Message]) -> LatestModelHint? {
        let sorted = sortedByRecency(messages)
        return latestModelHint(from: sorted, role: .user)
            ?? latestModelHint(from: sorted, role: .assistant)
    }

    private func sortedByRecency(_ messages: [Message]) -> [Message] {
        messages.sorted(by: {
                let leftTime = $0.sentAtEpochMs ?? Int64.min
                let rightTime = $1.sentAtEpochMs ?? Int64.min
                if leftTime != rightTime { return leftTime > rightTime }
                return $0.id > $1.id
            })
    }

    private func latestModelHint(from sortedMessages: [Message], role: MessageRole) -> LatestModelHint? {
        guard let message = sortedMessages.first(where: { $0.role == role && $0.modelProviderID != nil && $0.modelID != nil }),
              let providerID = message.modelProviderID,
              let modelID = message.modelID
        else { return nil }
        return LatestModelHint(
            providerID: providerID,
            modelID: modelID,
            variant: message.variant,
            sentAtEpochMs: message.sentAtEpochMs,
            source: role == .user ? .user : .assistant
        )
    }

    private func mergeWithPending(_ serverRows: [SessionChatRow], pendingRows: [SessionChatRow]) -> [SessionChatRow] {
        guard !pendingRows.isEmpty else { return serverRows }
        var serverCounts = signatureCounts(serverRows)
        let pendingToKeep =
            pendingRows.filter { pending in
                let signature = optimisticSignature(pending)
                let baselineCount = pendingBaselineSignatureCounts[signature, default: 0]
                guard serverCounts[signature, default: 0] > baselineCount else { return true }
                serverCounts[signature, default: 0] -= 1
                return false
            }
        return serverRows + pendingToKeep
    }

    private func signatureCounts(_ rows: [SessionChatRow]) -> [String: Int] {
        rows.reduce(into: [:]) { counts, row in
            guard row.role == .user else { return }
            counts[optimisticSignature(row), default: 0] += 1
        }
    }

    private func optimisticSignature(_ row: SessionChatRow) -> String {
        let content = row.blocks.compactMap { block -> String? in
            switch block {
            case .text(let text):
                "text:\(text)"
            case .attachment(let file):
                "file:\(file.id):\(file.mime):\(file.dataURL)"
            case .toolCard, .toolGroup:
                nil
            }
        }
        return content.isEmpty ? "text:\(row.text)" : content.joined(separator: "|")
    }

    private func metadataLabel(mode: String?, model: String?, variant: String? = nil, sentAtEpochMs: Int64? = nil) -> String {
        let timeLabel = sentAtEpochMs.map { sentTimeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval($0) / 1000)) }
        return [mode, model, variant, timeLabel].compactMap { $0 }.joined(separator: " · ")
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
}
