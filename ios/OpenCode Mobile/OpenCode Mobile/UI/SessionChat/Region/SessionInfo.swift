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
        guard state.sessionID != sessionID || state.sessionTitle != sessionTitle else { return }
        state.sessionID = sessionID
        state.sessionTitle = sessionTitle
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

    func refreshStatus() async {
        guard !state.sessionID.isEmpty else { return }
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: worktree) else { return }
        let status = statuses.first(where: { $0.sessionID == state.sessionID })
        state.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
        notifyChange()
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
            state.sessionStatusLabel = status.flatMap(eventAdapter.statusLabel(from:))
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
            state.connectionStatus = SessionConnectionStatus(connectionState: connectionState)
            notifyChange()
        }
    }

    private func observeEvents() async {
        for await event in foregroundEventStream.events() {
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
        }
    }

    private func notifyChange() {
        onChange?()
    }
}
