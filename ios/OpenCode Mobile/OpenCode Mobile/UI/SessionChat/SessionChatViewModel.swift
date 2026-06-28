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

    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelRepository: any ModelRepository,
        sessionRepository: any SessionRepository,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream,
        sessionModelSelectionStore: any SessionModelSelectionStore,
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.sessionID = sessionID
        self.sessionTitle = sessionTitle
        self.worktree = worktree
        self.uiState = .initial(sessionID: sessionID, sessionTitle: sessionTitle)
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
        OpenCodeLog.sessionChat.info("bind session=\(sessionID, privacy: .public) worktree=\(worktree, privacy: .private) title=\(sessionTitle, privacy: .public)")
        sessionInfo.bind(sessionID: sessionID, sessionTitle: sessionTitle)
        timeline.onChange = { [weak self] in self?.publishUiState() }
        composer.onChange = { [weak self] in self?.publishUiState() }
        sessionInfo.onChange = { [weak self] in self?.publishUiState() }
        publishUiState()
    }

    func startObserving() {
        composer.resetInitialModelSelection()
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID, privacy: .public)")
        sessionInfo.startObserving()
        timeline.startObserving()
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

    func stopObserving() {
        OpenCodeLog.sessionChat.info("stop observing session=\(self.sessionID, privacy: .public)")
        timeline.stopObserving()
        sessionInfo.stopObserving()
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
            messageHint: timeline.latestModelHint()
        )
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
            )
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
        return timelineState.errorMessage
    }
}
