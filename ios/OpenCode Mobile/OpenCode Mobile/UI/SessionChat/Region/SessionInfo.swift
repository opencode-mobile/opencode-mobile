import Foundation

@MainActor
final class SessionInfo {
    var onChange: (() -> Void)?

    private(set) var state = SessionInfoRegionState()

    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let foregroundEventStream: any ForegroundEventStream
    private let liveUpdates: any LiveUpdates
    private let eventAdapter = EventDomainAdapter()
    private var observationTask: Task<Void, Never>?
    private var eventFanoutTask: Task<Void, Never>?
    private var renameTask: Task<Void, Never>?

    init(
        worktree: String,
        sessionRepository: any SessionRepository,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream,
        liveUpdates: any LiveUpdates = FixedLiveUpdates()
    ) {
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.foregroundEventStream = foregroundEventStream
        self.liveUpdates = liveUpdates
    }

    func bind(sessionID: String, sessionTitle: String) {
        // Session identity is the id; ignore stale title rebinds after rename / session.updated.
        guard state.sessionID != sessionID else { return }
        renameTask?.cancel()
        renameTask = nil
        state.sessionID = sessionID
        state.sessionTitle = sessionTitle
        state.connectionStatus = SessionConnectionStatus(label: liveUpdates.chrome.label)
        state.canMutate = liveUpdates.chrome.canMutate
        state.rename = SessionRenameUiState()
        notifyChange()
    }

    func requestLeaveSession() {
        state.shouldLeaveSession = true
        notifyChange()
    }

    func startObserving() {
        stopObserving()
        OpenCodeLog.sessionChat.info("session info observing session=\(self.state.sessionID)")
        let boundSessionID = state.sessionID
        let eventStream = foregroundEventStream
        let eventAdapter = eventAdapter
        let info = self
        eventFanoutTask = Task.detached { [weak info] in
            for await event in eventStream.events() {
                if Task.isCancelled { return }
                let scope: String =
                    if event.sessionID == boundSessionID {
                        "bound"
                    } else if event.sessionID == nil {
                        "global"
                    } else {
                        "other"
                    }
                if event.type != "message.part.delta" {
                    OpenCodeLog.sessionChat.debug(
                        "SSE event observed session=\(boundSessionID) type=\(event.type) eventSession=\(event.sessionID ?? "-") scope=\(scope)"
                    )
                }
                let boundError =
                    event.sessionID == boundSessionID ? event.sessionErrorMessage : nil
                let hasTitleUpdate = eventAdapter.sessionTitleUpdate(from: event) != nil
                guard boundError != nil || hasTitleUpdate else { continue }
                await MainActor.run {
                    guard !Task.isCancelled, let sessionInfo = info else { return }
                    if let boundError {
                        sessionInfo.state.errorMessage = boundError
                        sessionInfo.notifyChange()
                    }
                    if hasTitleUpdate {
                        sessionInfo.applySessionUpdatedTitle(event)
                    }
                }
            }
        }
        observationTask = Task {
            async let statusLoop: Void = info.observeStatuses()
            async let connectionLoop: Void = info.observeConnectionState()
            _ = await (statusLoop, connectionLoop)
        }
    }

    func stopObserving() {
        observationTask?.cancel()
        observationTask = nil
        eventFanoutTask?.cancel()
        eventFanoutTask = nil
    }

    func openRenameSession() {
        state.rename = SessionRenameUiState(
            isDialogVisible: true,
            draftTitle: state.sessionTitle
        )
        notifyChange()
    }

    func cancelRenameSession() {
        renameTask?.cancel()
        renameTask = nil
        state.rename = SessionRenameUiState()
        notifyChange()
    }

    func updateRenameDraft(_ text: String) {
        state.rename.draftTitle = text
        state.rename.errorMessage = nil
        notifyChange()
    }

    func confirmRenameSession() async {
        guard !state.sessionID.isEmpty, !state.rename.isSubmitting else { return }

        let trimmed = state.rename.draftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            state.rename.errorMessage = Self.emptyTitleError
            notifyChange()
            return
        }
        if trimmed == state.sessionTitle {
            cancelRenameSession()
            return
        }

        let sessionID = state.sessionID
        state.rename.isSubmitting = true
        state.rename.errorMessage = nil
        notifyChange()
        OpenCodeLog.sessionChat.info("rename submit session=\(sessionID)")

        renameTask?.cancel()
        renameTask = Task {
            do {
                let session = try await sessionRepository.updateSession(
                    id: sessionID,
                    title: trimmed,
                    directory: worktree
                )
                guard !Task.isCancelled, state.sessionID == sessionID else { return }
                state.sessionTitle = session.title
                state.rename = SessionRenameUiState()
                notifyChange()
                OpenCodeLog.sessionChat.info("rename success session=\(sessionID)")
            } catch {
                guard !Task.isCancelled, state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.error(
                    "rename failed session=\(sessionID) message=\(error.localizedDescription)"
                )
                state.rename.isSubmitting = false
                state.rename.errorMessage = renameErrorMessage(error)
                notifyChange()
            }
        }
        await renameTask?.value
    }

    func markOptimisticBusy() {
        guard !state.sessionID.isEmpty else { return }
        sessionStatusLiveStore.markBusy(directory: worktree, sessionID: state.sessionID)
    }

    func clearOptimisticBusy() {
        guard !state.sessionID.isEmpty else { return }
        sessionStatusLiveStore.clearLocalBusy(directory: worktree, sessionID: state.sessionID)
    }

    func markIdle() {
        guard !state.sessionID.isEmpty else { return }
        sessionStatusLiveStore.markIdle(directory: worktree, sessionID: state.sessionID)
    }

    func archiveSession() {
        guard !state.sessionID.isEmpty,
              !OptimisticSessionCreate.isPlaceholder(state.sessionID),
              !state.isLifecycleActionInFlight else { return }
        let sessionID = state.sessionID
        state.isLifecycleActionInFlight = true
        state.lifecycleErrorMessage = nil
        notifyChange()
        OpenCodeLog.sessionChat.info("archive start session=\(sessionID)")
        Task {
            do {
                _ = try await sessionRepository.archiveSession(id: sessionID, directory: worktree)
                guard state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.info("archive success session=\(sessionID)")
                state.isLifecycleActionInFlight = false
                state.shouldLeaveSession = true
                notifyChange()
            } catch {
                guard state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.info(
                    "archive failure session=\(sessionID) error=\(error.localizedDescription)"
                )
                state.isLifecycleActionInFlight = false
                state.lifecycleErrorMessage = Self.archiveError
                notifyChange()
            }
        }
    }

    func deleteSession() {
        guard !state.sessionID.isEmpty,
              !OptimisticSessionCreate.isPlaceholder(state.sessionID),
              !state.isLifecycleActionInFlight else { return }
        let sessionID = state.sessionID
        state.isLifecycleActionInFlight = true
        state.lifecycleErrorMessage = nil
        notifyChange()
        OpenCodeLog.sessionChat.info("delete start session=\(sessionID)")
        Task {
            do {
                let deleted = try await sessionRepository.deleteSession(id: sessionID, directory: worktree)
                guard state.sessionID == sessionID else { return }
                state.isLifecycleActionInFlight = false
                if deleted {
                    OpenCodeLog.sessionChat.info("delete success session=\(sessionID)")
                    state.shouldLeaveSession = true
                } else {
                    OpenCodeLog.sessionChat.info("delete rejected session=\(sessionID)")
                    state.lifecycleErrorMessage = Self.deleteError
                }
                notifyChange()
            } catch {
                guard state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.info(
                    "delete failure session=\(sessionID) error=\(error.localizedDescription)"
                )
                state.isLifecycleActionInFlight = false
                state.lifecycleErrorMessage = Self.deleteError
                notifyChange()
            }
        }
    }

    func contextUsageFraction(
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

    private static let statusResubscribeLimit = 16

    private func observeStatuses() async {
        for _ in 0...Self.statusResubscribeLimit {
            for await statuses in sessionStatusLiveStore.observeStatuses(directory: worktree) {
                let status = statuses[state.sessionID]
                OpenCodeLog.sessionChat.debug(
                    "live status session=\(self.state.sessionID) type=\(status?.raw["type"] ?? "-")"
                )
                DiagnosticLog.debug(
                    .session,
                    "live status session=\(state.sessionID) type=\(status?.raw["type"] ?? "-")"
                )
                state.applyStatus(
                    authoritative: status.flatMap(eventAdapter.statusLabel(from:)),
                    hasResolved: true
                )
                notifyChange()
            }
            if Task.isCancelled { return }
            await Task.yield()
        }
        DiagnosticLog.warning(
            .session,
            "live status gave up resubscribing session=\(state.sessionID) after \(Self.statusResubscribeLimit) rounds"
        )
    }

    private func observeConnectionState() async {
        applyComposedConnectionStatus()
        for await _ in liveUpdates.updates() {
            applyComposedConnectionStatus()
        }
    }

    private func applyComposedConnectionStatus() {
        let chrome = liveUpdates.chrome
        OpenCodeLog.sessionChat.debug(
            "live connection session=\(self.state.sessionID) label=\(chrome.label.rawValue) canMutate=\(chrome.canMutate)"
        )
        DiagnosticLog.debug(
            .session,
            "live connection session=\(state.sessionID) label=\(chrome.label.rawValue) canMutate=\(chrome.canMutate)"
        )
        state.connectionStatus = SessionConnectionStatus(label: chrome.label)
        state.canMutate = chrome.canMutate
        notifyChange()
    }

    private func applySessionUpdatedTitle(_ event: OpencodeEvent) {
        guard let update = eventAdapter.sessionTitleUpdate(from: event),
              update.sessionID == state.sessionID,
              update.title != state.sessionTitle
        else { return }
        state.sessionTitle = update.title
        notifyChange()
        OpenCodeLog.sessionChat.info(
            "session.updated title session=\(self.state.sessionID) title=\(update.title)"
        )
        DiagnosticLog.info(.session, "session.updated title session=\(state.sessionID)")
    }

    private func renameErrorMessage(_ error: Error) -> String {
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

    func clearTransientErrors() {
        state.errorMessage = nil
        state.lifecycleErrorMessage = nil
        notifyChange()
    }

    private func notifyChange() {
        onChange?()
    }

    private static let emptyTitleError = "Enter a session title"
    private static let archiveError = "Couldn't archive session."
    private static let deleteError = "Couldn't delete session."
}
