import Foundation
import OSLog

@MainActor
final class SessionInfo {
    var onChange: (() -> Void)?

    private(set) var state = SessionInfoRegionState()

    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let foregroundEventStream: any ForegroundEventStream
    private let eventAdapter = EventDomainAdapter()
    private var observationTask: Task<Void, Never>?
    private var renameTask: Task<Void, Never>?

    init(
        worktree: String,
        sessionRepository: any SessionRepository,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream
    ) {
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.foregroundEventStream = foregroundEventStream
    }

    func bind(sessionID: String, sessionTitle: String) {
        // Session identity is the id; ignore stale title rebinds after rename / session.updated.
        guard state.sessionID != sessionID else { return }
        renameTask?.cancel()
        renameTask = nil
        state.sessionID = sessionID
        state.sessionTitle = sessionTitle
        state.rename = SessionRenameUiState()
        notifyChange()
    }

    func startObserving() {
        observationTask?.cancel()
        OpenCodeLog.sessionChat.info("session info observing session=\(self.state.sessionID, privacy: .public)")
        observationTask = Task {
            async let statusLoop: Void = observeStatuses()
            async let connectionLoop: Void = observeConnectionState()
            async let eventLoop: Void = observeEvents()
            _ = await (statusLoop, connectionLoop, eventLoop)
        }
    }

    func stopObserving() {
        observationTask?.cancel()
        observationTask = nil
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
        OpenCodeLog.sessionChat.info("rename submit session=\(sessionID, privacy: .public)")

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
                OpenCodeLog.sessionChat.info("rename success session=\(sessionID, privacy: .public)")
            } catch {
                guard !Task.isCancelled, state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.error(
                    "rename failed session=\(sessionID, privacy: .public) message=\(error.localizedDescription, privacy: .public)"
                )
                state.rename.isSubmitting = false
                state.rename.errorMessage = renameErrorMessage(error)
                notifyChange()
            }
        }
        await renameTask?.value
    }

    func refreshStatus() async {
        guard !state.sessionID.isEmpty else { return }
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: worktree) else { return }
        let status = statuses.first(where: { $0.sessionID == state.sessionID })
        state.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
        state.hasResolvedSessionStatus = true
        notifyChange()
    }

    func archiveSession() {
        guard !state.sessionID.isEmpty, !state.isLifecycleActionInFlight else { return }
        let sessionID = state.sessionID
        state.isLifecycleActionInFlight = true
        state.lifecycleErrorMessage = nil
        notifyChange()
        OpenCodeLog.sessionChat.info("archive start session=\(sessionID, privacy: .public)")
        Task {
            do {
                _ = try await sessionRepository.archiveSession(id: sessionID, directory: worktree)
                guard state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.info("archive success session=\(sessionID, privacy: .public)")
                state.isLifecycleActionInFlight = false
                state.shouldLeaveSession = true
                notifyChange()
            } catch {
                guard state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.info(
                    "archive failure session=\(sessionID, privacy: .public) error=\(error.localizedDescription, privacy: .public)"
                )
                state.isLifecycleActionInFlight = false
                state.lifecycleErrorMessage = Self.archiveError
                notifyChange()
            }
        }
    }

    func deleteSession() {
        guard !state.sessionID.isEmpty, !state.isLifecycleActionInFlight else { return }
        let sessionID = state.sessionID
        state.isLifecycleActionInFlight = true
        state.lifecycleErrorMessage = nil
        notifyChange()
        OpenCodeLog.sessionChat.info("delete start session=\(sessionID, privacy: .public)")
        Task {
            do {
                let deleted = try await sessionRepository.deleteSession(id: sessionID, directory: worktree)
                guard state.sessionID == sessionID else { return }
                state.isLifecycleActionInFlight = false
                if deleted {
                    OpenCodeLog.sessionChat.info("delete success session=\(sessionID, privacy: .public)")
                    state.shouldLeaveSession = true
                } else {
                    OpenCodeLog.sessionChat.info("delete rejected session=\(sessionID, privacy: .public)")
                    state.lifecycleErrorMessage = Self.deleteError
                }
                notifyChange()
            } catch {
                guard state.sessionID == sessionID else { return }
                OpenCodeLog.sessionChat.info(
                    "delete failure session=\(sessionID, privacy: .public) error=\(error.localizedDescription, privacy: .public)"
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

    private func observeStatuses() async {
        for await statuses in sessionStatusLiveStore.observeStatuses(directory: worktree) {
            let status = statuses[state.sessionID]
            OpenCodeLog.sessionChat.debug(
                "live status session=\(self.state.sessionID, privacy: .public) type=\(status?.raw["type"] ?? "-", privacy: .public)"
            )
            DiagnosticLog.debug(
                .session,
                "live status session=\(state.sessionID) type=\(status?.raw["type"] ?? "-")"
            )
            state.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
            state.hasResolvedSessionStatus = true
            notifyChange()
        }
    }

    private func observeConnectionState() async {
        state.connectionStatus = SessionConnectionStatus(connectionState: foregroundEventStream.connectionState)
        notifyChange()
        for await connectionState in foregroundEventStream.connectionStateUpdates() {
            OpenCodeLog.sessionChat.debug(
                "live connection session=\(self.state.sessionID, privacy: .public) state=\(String(describing: connectionState), privacy: .public)"
            )
            DiagnosticLog.debug(.session, "live connection session=\(state.sessionID) state=\(connectionState)")
            state.connectionStatus = SessionConnectionStatus(connectionState: connectionState)
            notifyChange()
        }
    }

    private func observeEvents() async {
        for await event in foregroundEventStream.events() {
            if event.sessionID == state.sessionID, let message = event.sessionErrorMessage {
                state.errorMessage = message
                notifyChange()
            }
            let scope =
                if event.sessionID == state.sessionID {
                    "bound"
                } else if event.sessionID == nil {
                    "global"
                } else {
                    "other"
                }
            OpenCodeLog.sessionChat.debug(
                "SSE event observed session=\(self.state.sessionID, privacy: .public) type=\(event.type, privacy: .public) eventSession=\(event.sessionID ?? "-", privacy: .public) scope=\(scope, privacy: .public)"
            )
            applySessionUpdatedTitle(event)
        }
    }

    private func applySessionUpdatedTitle(_ event: OpencodeEvent) {
        guard let update = eventAdapter.sessionTitleUpdate(from: event),
              update.sessionID == state.sessionID,
              update.title != state.sessionTitle
        else { return }
        state.sessionTitle = update.title
        notifyChange()
        OpenCodeLog.sessionChat.info(
            "session.updated title session=\(self.state.sessionID, privacy: .public) title=\(update.title, privacy: .public)"
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
