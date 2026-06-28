import Foundation
import OSLog

@MainActor
final class SessionChatStatusBar {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatStatusBarState()

    private let sessionID: String
    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let foregroundEventStream: any ForegroundEventStream
    private let eventAdapter = EventDomainAdapter()
    private var observationTask: Task<Void, Never>?

    init(
        sessionID: String,
        worktree: String,
        sessionRepository: any SessionRepository,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.foregroundEventStream = foregroundEventStream
    }

    func startObserving() {
        observationTask?.cancel()
        OpenCodeLog.sessionChat.info("status bar observing session=\(self.sessionID, privacy: .public)")
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

    func refreshStatus() async {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: worktree) else { return }
        let status = statuses.first(where: { $0.sessionID == sessionID })
        state.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
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

    private func observeStatuses() async {
        for await statuses in sessionStatusLiveStore.observeStatuses(directory: worktree) {
            let status = statuses[sessionID]
            OpenCodeLog.sessionChat.debug("live status session=\(self.sessionID, privacy: .public) type=\(status?.raw["type"] ?? "-", privacy: .public)")
            state.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
            notifyChange()
        }
    }

    private func observeConnectionState() async {
        state.connectionStatus = SessionConnectionStatus(connectionState: foregroundEventStream.connectionState)
        notifyChange()
        for await connectionState in foregroundEventStream.connectionStateUpdates() {
            OpenCodeLog.sessionChat.debug("live connection session=\(self.sessionID, privacy: .public) state=\(String(describing: connectionState), privacy: .public)")
            state.connectionStatus = SessionConnectionStatus(connectionState: connectionState)
            notifyChange()
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
