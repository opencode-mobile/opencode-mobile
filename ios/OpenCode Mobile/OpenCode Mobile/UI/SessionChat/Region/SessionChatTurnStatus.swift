import Foundation

/// What the open session's turn shows, resolved inside the Screen Model's publish.
struct SessionChatTurnStatusResolution {
    var workingIndicatorLabel: String?
    var workingIndicatorCollapsed: Bool
    var workingIndicatorFading: Bool
    var pendingPrompts: [PendingPrompt]
    var subagents: [SessionChatSubagent]
    var backgroundWorkDoneUnseen: Bool
}

/// What publish hands `SessionChatTurnStatus.resolve`: other regions' states and the
/// values publish already derived from them.
struct SessionChatTurnStatusInputs {
    /// The status shown in the header: the compact label while compacting.
    var sessionStatusLabel: String?
    var isCompacting: Bool
    var isSessionBound: Bool
    var messages: [SessionChatRow]
    var sendProgressPercent: Int?
    /// Server inbox rows and unmerged optimistic busy sends (`SessionChatViewModel.pendingPrompts`).
    var pendingPrompts: [PendingPrompt]
    /// The subagent rows published last.
    var previousSubagents: [SessionChatSubagent]
    var inboxState: SessionChatInboxRegionState
    var infoState: SessionInfoRegionState
}

/// The open session's turn: the working footer (label, collapse, fade, hold and its wake), the
/// pending-prompts strip (gate, wake, and the steer / queue / cancel calls), and the background
/// work rows (subagent finish times, Done-tab seen ids).
@MainActor
final class SessionChatTurnStatus {
    enum PendingPromptAction {
        case steer
        case queue
        case cancel
    }

    /// A footer hold or pending-prompts wake is due: the Screen Model republishes.
    var onWake: (() -> Void)?

    private let sessionRepository: any SessionRepository
    private let clock: any SyncClock
    private let loadedMessages: () -> [Message]
    private let childSessions: () -> [SessionChatChildSessionCandidate]
    private let applyOptimistic: (String, PendingPromptAction) -> Void
    private let reportError: (String) -> Void

    private var loggedWorkingLamp = false
    private var lastWorkingLamp: String?
    private var workingChrome = WorkingTurnIndicator.Chrome.unmounted
    private var workingHold: WorkingTurnIndicator.Hold?
    /// Done rows of the background work panel the user has seen (#966); nil until the first
    /// rows of the open session, which count as seen.
    private var seenDoneSubagentIDs: Set<String>?
    /// The background work panel shows its Done tab, so finishes are seen as they land.
    private var backgroundWorkDoneVisible = false
    /// When subagents seen working finished live, keyed by tool part id, for rows with no server time.
    private var subagentFinishTimes: [String: Int64] = [:]
    /// When the connection last became live; nil while it is not. Rows published before it
    /// were seen before a gap, so a finish found after it is not stamped locally.
    private var subagentLiveSinceMs: Int64?
    /// When the published subagent rows were computed.
    private var subagentsSeenAtMs: Int64?
    private var workingHoldTask: Task<Void, Never>?
    private var pendingPromptsGate = PendingPromptsStripGate()
    private var pendingPromptsWakeTask: Task<Void, Never>?

    init(
        sessionRepository: any SessionRepository,
        clock: any SyncClock,
        loadedMessages: @escaping () -> [Message],
        childSessions: @escaping () -> [SessionChatChildSessionCandidate],
        applyOptimistic: @escaping (String, PendingPromptAction) -> Void,
        reportError: @escaping (String) -> Void
    ) {
        self.sessionRepository = sessionRepository
        self.clock = clock
        self.loadedMessages = loadedMessages
        self.childSessions = childSessions
        self.applyOptimistic = applyOptimistic
        self.reportError = reportError
    }

    /// Called from the Screen Model's publish: never notifies; only its wake tasks call `onWake`.
    func resolve(_ inputs: SessionChatTurnStatusInputs) -> SessionChatTurnStatusResolution {
        let inboxState = inputs.inboxState
        let awaitingUser = !inboxState.permissionRequests.isEmpty || !inboxState.questionRequests.isEmpty
        let lastIsThinking =
            WorkingTurnIndicator.isStreamingThinking(messages: loadedMessages())
            || WorkingTurnIndicator.lastIsThinking(messages: inputs.messages)
        let activeWorkingLabel = WorkingTurnIndicator.labelForActiveTurn(
            statusLabel: inputs.sessionStatusLabel,
            isCompacting: inputs.isCompacting,
            isAwaitingUser: awaitingUser,
            sendProgressPercent: inputs.sendProgressPercent,
            isThinking: lastIsThinking
        )
        let workingResolution = WorkingTurnIndicator.resolveFooter(
            activeLabel: activeWorkingLabel,
            lastIsThinking: lastIsThinking,
            hideImmediately: awaitingUser || !inputs.isSessionBound,
            previous: workingChrome,
            hold: workingHold,
            nowMs: clock.nowMs()
        )
        workingChrome = workingResolution.chrome
        workingHold = workingResolution.hold
        scheduleWorkingFooterWake(workingResolution.wakeAfterMs)
        let workingIndicatorLabel = workingResolution.chrome.label
        let workingIndicatorCollapsed = workingResolution.chrome.collapsed
        let workingIndicatorFading = workingResolution.chrome.fading
        let workingLampKey =
            "\(workingIndicatorLabel ?? "none"):\(workingIndicatorCollapsed ? 1 : 0):\(workingIndicatorFading ? 1 : 0):\(workingHold != nil ? 1 : 0)"
        if !loggedWorkingLamp || lastWorkingLamp != workingLampKey {
            loggedWorkingLamp = true
            lastWorkingLamp = workingLampKey
            OpenCodeLog.sessionChat.info(
                "workingLamp label=\(workingIndicatorLabel ?? "none") collapsed=\(workingIndicatorCollapsed ? 1 : 0) fading=\(workingIndicatorFading ? 1 : 0) hold=\(self.workingHold != nil ? 1 : 0) status=\(inputs.sessionStatusLabel ?? "none") awaitingUser=\(awaitingUser ? 1 : 0) lastIsThinking=\(lastIsThinking ? 1 : 0)"
            )
        }
        let pendingResolution = pendingPromptsGate.resolve(inputs.pendingPrompts, nowMs: clock.nowMs())
        schedulePendingPromptsWake(pendingResolution.wakeAfterMs)
        let subagents = resolveSubagents(
            inboxState: inboxState,
            infoState: inputs.infoState,
            previous: inputs.previousSubagents
        )
        let seenDone = SessionChatBackgroundWork.seenDoneIDs(
            previous: seenDoneSubagentIDs,
            subagents: subagents,
            viewingDone: backgroundWorkDoneVisible
        )
        seenDoneSubagentIDs = seenDone
        return SessionChatTurnStatusResolution(
            workingIndicatorLabel: workingIndicatorLabel,
            workingIndicatorCollapsed: workingIndicatorCollapsed,
            workingIndicatorFading: workingIndicatorFading,
            pendingPrompts: pendingResolution.prompts,
            subagents: subagents,
            backgroundWorkDoneUnseen: SessionChatBackgroundWork.hasUnseenDone(subagents, seenDoneIDs: seenDone)
        )
    }

    /// The background work panel shows (or stops showing) its Done tab (#966). True when the
    /// Screen Model should republish: while it shows, every finished row counts as seen.
    func setDoneVisible(_ visible: Bool) -> Bool {
        guard backgroundWorkDoneVisible != visible else { return false }
        backgroundWorkDoneVisible = visible
        return visible
    }

    /// Drops the working footer's hold and the pending-prompts gate, with their wakes.
    func reset() {
        workingHoldTask?.cancel()
        workingHoldTask = nil
        workingHold = nil
        workingChrome = .unmounted
        pendingPromptsWakeTask?.cancel()
        pendingPromptsWakeTask = nil
        pendingPromptsGate = PendingPromptsStripGate()
    }

    /// Steer, queue, or cancel one pending prompt of `sessionID`. A success applies the change
    /// to the timeline at once; a refusal or failure reports the action error.
    func updatePendingPrompt(_ action: PendingPromptAction, id: String, sessionID: String) {
        let sessionRepository = sessionRepository
        Task {
            do {
                let ok: Bool =
                    switch action {
                    case .steer: try await sessionRepository.steerPendingPrompt(sessionID: sessionID, inboxID: id)
                    case .queue: try await sessionRepository.queuePendingPrompt(sessionID: sessionID, inboxID: id)
                    case .cancel: try await sessionRepository.cancelPendingPrompt(sessionID: sessionID, inboxID: id)
                    }
                if ok {
                    applyOptimistic(id, action)
                } else {
                    reportError(Self.pendingPromptFailure)
                }
            } catch is CancellationError {
                return
            } catch {
                DiagnosticLog.warning(.chat, "pending prompt update failed session=\(sessionID) error=\(error.localizedDescription)")
                reportError(Self.pendingPromptFailure)
            }
        }
    }

    /// Subagent rows from the linked timeline, live session activity and the pending
    /// requests of the session tree; remembers when rows seen working finish.
    private func resolveSubagents(
        inboxState: SessionChatInboxRegionState,
        infoState: SessionInfoRegionState,
        previous: [SessionChatSubagent]
    ) -> [SessionChatSubagent] {
        let nowMs = clock.nowMs()
        let isLive = infoState.connectionStatus == .live
        if !isLive {
            subagentLiveSinceMs = nil
        } else if subagentLiveSinceMs == nil {
            subagentLiveSinceMs = nowMs
        }
        let seenLive = isLive
            && subagentsSeenAtMs.map { seen in subagentLiveSinceMs.map { seen >= $0 } ?? false } == true
        subagentsSeenAtMs = nowMs
        let messages = SessionChatChildSessionLinker.link(
            loadedMessages(),
            children: childSessions()
        )
        func rows(_ finishTimes: [String: Int64]) -> [SessionChatSubagent] {
            SessionChatBackgroundWork.subagents(
                messages: messages,
                sessions: inboxState.permissionSessions + inboxState.questionSessions,
                busySessionIDs: infoState.busySessionIDs,
                requestSessionIDs: inboxState.permissionRequests.map(\.sessionID)
                    + inboxState.questionRequests.map(\.sessionID),
                localFinishedAtMs: finishTimes
            )
        }
        let current = rows(subagentFinishTimes)
        let finishTimes = SessionChatBackgroundWork.finishTimes(
            previous: previous,
            current: current,
            known: subagentFinishTimes,
            nowMs: nowMs,
            seenLive: seenLive
        )
        guard finishTimes != subagentFinishTimes else { return current }
        subagentFinishTimes = finishTimes
        return rows(finishTimes)
    }

    private func schedulePendingPromptsWake(_ wakeAfterMs: Int64?) {
        pendingPromptsWakeTask?.cancel()
        pendingPromptsWakeTask = nil
        guard let wakeAfterMs else { return }
        pendingPromptsWakeTask = Task { [clock] in
            await clock.sleep(milliseconds: Int(wakeAfterMs))
            guard !Task.isCancelled else { return }
            onWake?()
        }
    }

    private func scheduleWorkingFooterWake(_ wakeAfterMs: Int64?) {
        workingHoldTask?.cancel()
        workingHoldTask = nil
        guard let wakeAfterMs else { return }
        workingHoldTask = Task { [clock] in
            await clock.sleep(milliseconds: Int(wakeAfterMs))
            guard !Task.isCancelled else { return }
            onWake?()
        }
    }

    private static var pendingPromptFailure: String {
        String(localized: "session_chat.pending_prompt.update_failed", defaultValue: "Couldn't update pending prompt")
    }
}
