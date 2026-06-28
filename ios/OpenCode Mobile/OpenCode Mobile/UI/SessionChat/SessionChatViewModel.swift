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
    private let sessionModelSelectionStore: any SessionModelSelectionStore
    private let activeConnectionManager: ActiveConnectionManager
    private var appliedInitialModelSelection = false
    private var cachedSelection: SessionModelSelection?
    private var cacheResolved = false

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
        self.sessionModelSelectionStore = sessionModelSelectionStore
        self.activeConnectionManager = activeConnectionManager
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
            sessionRepository: sessionRepository
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
        Task { await self.loadCachedSelection() }
        publishUiState()
    }

    func startObserving() {
        appliedInitialModelSelection = false
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID, privacy: .public)")
        sessionInfo.startObserving()
        timeline.startObserving()
    }

    func loadMessages() async {
        async let catalogLoad: Void = composer.loadCatalog()
        _ = await catalogLoad
        await waitForTimelineResolution()
        await applyInitialModelSelectionIfNeeded()
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
        persistCurrentSelection()
    }

    func selectMode(_ mode: String) {
        composer.selectMode(mode)
    }

    func selectVariant(_ variant: String) {
        composer.selectVariant(variant)
        persistCurrentSelection()
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
                self.persistCurrentSelection()
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

    private func loadCachedSelection() async {
        defer { cacheResolved = true }
        guard let serverBaseURL = activeConnectionManager.currentProfile()?.baseURL else { return }
        cachedSelection = await sessionModelSelectionStore.load(
            serverBaseURL: serverBaseURL,
            worktree: worktree,
            sessionID: sessionID
        )
        publishUiState()
    }

    private func persistCurrentSelection() {
        guard let serverBaseURL = activeConnectionManager.currentProfile()?.baseURL,
              let selectedModel = composer.state.composer.selectedModel
        else { return }
        let selection = SessionModelSelection(
            providerID: selectedModel.option.providerID,
            modelID: selectedModel.option.modelID,
            variant: composer.state.composer.selectedVariant
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

    private func waitForTimelineResolution() async {
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, timeline.state.timeline == .loading {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    private func applyInitialModelSelectionIfNeeded() async {
        tryApplyInitialModelSelection(
            timelineState: timeline.state,
            composerState: composer.state
        )
    }

    private func tryApplyInitialModelSelection(
        timelineState: SessionChatTimelineRegionState,
        composerState: SessionChatComposerRegionState
    ) {
        guard !appliedInitialModelSelection else { return }
        guard cacheResolved else { return }
        guard !composerState.composer.modelOptions.isEmpty else { return }

        switch timelineState.timeline {
        case .loading:
            return
        case .error, .empty:
            appliedInitialModelSelection = true
        case .ready:
            if let cachedSelection,
               composerState.composer.modelOptions.contains(where: {
                   $0.providerID == cachedSelection.providerID && $0.modelID == cachedSelection.modelID
               }) {
                appliedInitialModelSelection = true
                composer.applyLatestModelHint(
                    LatestModelHint(
                        providerID: cachedSelection.providerID,
                        modelID: cachedSelection.modelID,
                        variant: cachedSelection.variant
                    )
                )
                return
            }
            if let hint = timeline.latestModelHint() {
                appliedInitialModelSelection = true
                composer.applyLatestModelHint(hint)
            } else if !timelineState.messages.isEmpty {
                appliedInitialModelSelection = true
            }
        }
    }

    private func publishUiState() {
        let timelineState = timeline.state
        let composerState = composer.state
        let infoState = sessionInfo.state
        tryApplyInitialModelSelection(timelineState: timelineState, composerState: composerState)
        uiState = SessionChatUiState(
            sessionID: infoState.sessionID.isEmpty ? sessionID : infoState.sessionID,
            sessionTitle: infoState.sessionTitle.isEmpty ? sessionTitle : infoState.sessionTitle,
            timeline: timelineState.timeline,
            messages: timelineState.messages,
            composer: composerState.composer,
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
