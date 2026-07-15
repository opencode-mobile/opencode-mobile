import Foundation
import Observation
import OSLog

@MainActor
@Observable
final class SessionChatViewModel {
    private(set) var uiState: SessionChatUiState

    private let sessionID: String
    private let sessionTitle: String
    private let worktree: String

    private let timeline: SessionChatTimeline
    private let composer: SessionChatComposer
    private let sessionInfo: SessionInfo
    private let reviewChanges: SessionChatReviewChanges
    private let sessionRepository: any SessionRepository
    private let permissionLiveStore: any PermissionLiveStore
    private var permissionTask: Task<Void, Never>?
    private var permissionSessions: [Session] = []
    private var permissionRequests: [PermissionRequest] = []
    private var permissionSubmittingID: String?
    private var permissionError: String?
    private var permissionTopologyError: String?
    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelRepository: any ModelRepository,
        sessionRepository: any SessionRepository,
        permissionLiveStore: (any PermissionLiveStore)? = nil,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream,
        sessionModelSelectionStore: any SessionModelSelectionStore,
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.sessionID = sessionID
        self.sessionTitle = sessionTitle
        self.worktree = worktree
        self.uiState = .initial(sessionID: sessionID, sessionTitle: sessionTitle)
        self.sessionRepository = sessionRepository
        self.permissionLiveStore = permissionLiveStore ?? EmptyPermissionLiveStore()
        self.timeline = SessionChatTimeline(
            sessionID: sessionID,
            worktree: worktree,
            messageRepository: messageRepository
        )
        self.composer = SessionChatComposer(
            sessionID: sessionID,
            worktree: worktree,
            messageRepository: messageRepository,
            modelRepository: modelRepository,
            sessionRepository: sessionRepository,
            sessionModelSelectionStore: sessionModelSelectionStore,
            activeConnectionManager: activeConnectionManager
        )
        self.sessionInfo = SessionInfo(
            worktree: worktree,
            sessionRepository: sessionRepository,
            sessionStatusLiveStore: sessionStatusLiveStore,
            foregroundEventStream: foregroundEventStream
        )
        self.reviewChanges = SessionChatReviewChanges(
            sessionID: sessionID,
            worktree: worktree,
            sessionRepository: sessionRepository
        )
        OpenCodeLog.sessionChat.info("bind session=\(sessionID, privacy: .public) worktree=\(worktree, privacy: .private) title=\(sessionTitle, privacy: .public)")
        sessionInfo.bind(sessionID: sessionID, sessionTitle: sessionTitle)
        timeline.onChange = { [weak self] in self?.publishUiState() }
        composer.onChange = { [weak self] in self?.publishUiState() }
        sessionInfo.onChange = { [weak self] in self?.publishUiState() }
        reviewChanges.onChange = { [weak self] in self?.publishUiState() }
        publishUiState()
    }

    func startObserving() {
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID, privacy: .public)")
        sessionInfo.startObserving()
        timeline.startObserving()
        startPermissionObservation()
    }

    func loadMessages() async {
        async let catalogLoad: Void = composer.loadCatalog()
        _ = await catalogLoad
        await waitForTimelineResolution()
        await sessionInfo.refreshStatus()
    }

    func refreshMessages() async {
        await timeline.refreshMessages()
    }

    func updateComposerText(_ text: String) {
        composer.updateComposerText(text)
    }

    func retryModelCatalog() async {
        await composer.loadCatalog()
    }

    func selectModel(_ option: ModelSelectionOption) {
        composer.selectModel(option)
    }

    func selectMode(_ mode: String) {
        composer.selectMode(mode)
    }

    func selectVariant(_ variant: String) {
        composer.selectVariant(variant)
    }

    func restoreComposerText(_ text: String) {
        composer.restoreComposerText(text)
    }

    func sendPrompt() async {
        await composer.sendPrompt(
            sessionID: sessionID,
            onOptimisticRows: { [weak self] rows in
                self?.timeline.beginOptimisticSend(rows)
            },
            onSendAccepted: { [weak self] in
                guard let self else { return }
                await self.sessionInfo.refreshStatus()
                await self.timeline.reloadAfterSend(
                    onComplete: { [weak self] in self?.composer.clearSending() },
                    onReloadFailed: { [weak self] in self?.composer.clearSending() }
                )
            },
            onSendFailed: { [weak self] optimisticID, error in
                self?.composer.setSendError(error)
                self?.timeline.failOptimisticSend(optimisticID: optimisticID, error: error)
            }
        )
    }

    func abortSession() async {
        await composer.abortSession(timelineIsReady: timeline.state.timeline == .ready)
    }

    func allowPermissionOnce(requestID: String, sessionID: String) async {
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .allowOnce)
    }

    func denyPermission(requestID: String, sessionID: String) async {
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .deny)
    }

    private func startPermissionObservation() {
        permissionTask?.cancel()
        permissionTask = Task {
            for await requests in permissionLiveStore.observePermissions(directory: worktree) {
                await applyPermissionSnapshot(requests)
            }
        }
    }

    private func respondToPermission(
        requestID: String,
        sessionID: String,
        decision: PermissionDecision
    ) async {
        guard let request = permissionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              permissionSubmittingID == nil else { return }
        permissionSubmittingID = request.id
        permissionError = nil
        publishUiState()
        do {
            _ = try await sessionRepository.respondToPermission(
                sessionID: request.sessionID,
                permissionID: request.id,
                decision: decision,
                directory: worktree
            )
        } catch {
            permissionSubmittingID = nil
            if case let .http(statusCode, _) = error as? SessionError, statusCode == 404 {
                let refreshed = await refreshPermissionSnapshot()
                if refreshed, !permissionRequests.contains(where: { $0.id == request.id }) {
                    permissionError = nil
                    publishUiState()
                    return
                }
                permissionError = "Permission request is no longer available"
            } else {
                permissionError = error.localizedDescription
            }
            publishUiState()
        }
    }

    private func refreshPermissionSnapshot() async -> Bool {
        do {
            let requests = try await sessionRepository.listPendingPermissions(directory: worktree)
            await applyPermissionSnapshot(requests)
            return true
        } catch {
            return false
        }
    }

    private func applyPermissionSnapshot(_ requests: [PermissionRequest]) async {
        do {
            let sessions = try await sessionRepository.listSessions(directory: worktree)
            permissionSessions = sessions
            permissionTopologyError = nil
        } catch {
            permissionSessions = []
            permissionTopologyError = "Unable to refresh permission session tree"
        }
        permissionRequests = Self.orderedPermissionRequests(
            rootSessionID: sessionID,
            sessions: permissionSessions,
            requests: requests
        )
        if let submitting = permissionSubmittingID, !permissionRequests.contains(where: { $0.id == submitting }) {
            permissionSubmittingID = nil
            permissionError = nil
        }
        publishUiState()
    }

    nonisolated static func orderedPermissionRequests(
        rootSessionID: String,
        sessions: [Session],
        requests: [PermissionRequest]
    ) -> [PermissionRequest] {
        let children = Dictionary(grouping: sessions.compactMap { session in
            session.parentID.map { ($0, session.id) }
        }, by: \.0)
        var sessionOrder: [String] = []
        var visited: Set<String> = []
        var queue = [rootSessionID]
        while let sessionID = queue.first {
            queue.removeFirst()
            guard visited.insert(sessionID).inserted else { continue }
            sessionOrder.append(sessionID)
            queue.append(contentsOf: (children[sessionID] ?? []).map(\.1))
        }
        let requestsBySession = Dictionary(grouping: requests, by: \.sessionID)
        return sessionOrder.flatMap { requestsBySession[$0] ?? [] }
    }

    func stopObserving() {
        OpenCodeLog.sessionChat.info("stop observing session=\(self.sessionID, privacy: .public)")
        timeline.stopObserving()
        sessionInfo.stopObserving()
        permissionTask?.cancel()
        reviewChanges.stop()
    }

    func row(from message: Message, previous: SessionChatRow? = nil) -> SessionChatRow {
        timeline.row(from: message, previous: previous)
    }

    private func waitForTimelineResolution() async {
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, timeline.state.timeline == .loading {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    private func publishUiState() {
        let timelineState = timeline.state
        let composerState = composer.state
        let infoState = sessionInfo.state
        composer.tryApplyInitialSelection(
            timelineState: timelineState,
            historyHints: timeline.modelSelectionHistoryHints()
        )
        reviewChanges.updateCandidate(
            messages: timelineState.messages,
            hasResolvedSessionStatus: infoState.hasResolvedSessionStatus,
            isTurnActive: infoState.sessionStatusLabel != nil,
            isSending: composerState.isSending
        )
        let reviewChangesState = reviewChanges.state
        uiState = SessionChatUiState(
            sessionID: infoState.sessionID.isEmpty ? sessionID : infoState.sessionID,
            sessionTitle: infoState.sessionTitle.isEmpty ? sessionTitle : infoState.sessionTitle,
            timeline: timelineState.timeline,
            messages: timelineState.messages,
            composer: composer.state.composer,
            isSending: composerState.isSending,
            isRefreshing: timelineState.isRefreshing,
            isAborting: composerState.isAborting,
            sessionStatusLabel: infoState.sessionStatusLabel,
            connectionStatus: infoState.connectionStatus,
            contextUsageFraction: sessionInfo.contextUsageFraction(
                messages: timelineState.messages,
                modelOptions: composerState.composer.modelOptions,
                selectedModel: composerState.composer.selectedModel
            ),
            errorMessage: mergedErrorMessage(
                timelineState: timelineState,
                composerState: composerState
            ),
            permissionRequest: permissionRequests.first,
            permissionQueueCount: permissionRequests.count,
            permissionSubmitting: permissionSubmittingID != nil,
            permissionErrorMessage: permissionError ?? permissionTopologyError,
            reviewChangesMessageID: reviewChangesState.messageID,
            reviewChangesSummary: reviewChangesState.summary
        )
    }

    private func mergedErrorMessage(
        timelineState: SessionChatTimelineRegionState,
        composerState: SessionChatComposerRegionState
    ) -> String? {
        if timelineState.timeline == .error {
            return timelineState.errorMessage
        }
        if let error = composerState.errorMessage {
            return error
        }
        if let error = composerState.abortErrorMessage {
            return error
        }
        if permissionRequests.isEmpty, let error = permissionTopologyError {
            return error
        }
        return timelineState.errorMessage
    }
}

private final class EmptyPermissionLiveStore: PermissionLiveStore {
    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        AsyncStream { $0.yield([]); $0.finish() }
    }
    func clearCache() {}
}
