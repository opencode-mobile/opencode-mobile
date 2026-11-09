import Foundation

struct SessionChatGoalRegionState: Equatable {
    /// The session's goal as last known; nil when it has none or the server has no goal plugin.
    var goal: SessionGoal?
    /// The stream is down: the goal shows dimmed and its actions are off until the refetch.
    var isStale = false
    /// The pause, resume or clear in flight; its button shows progress.
    var pendingAction: SessionGoalAction?
    var isSheetOpen = false
    /// A failed goal command, shown once as a snackbar.
    var errorNotice: String?
}

/// Session Chat region for the session's `/goal` (v2 goal plugin).
///
/// Reads the goal through the plugin RPC when the screen starts observing and on every stream
/// (re)connect, and follows `rpc.opencode.goal.changed` in between. A failed read (a server
/// without the plugin) shows no goal and retries quietly after 1, 2 and 4 seconds, like
/// desktop; a later change event still brings it back. On v1 nothing is fetched.
///
/// Pause, resume and clear go through the `goal` session command. The button shows progress
/// until the change event arrives or `commandTimeoutMs` passes; a failure shows the server's
/// message and refetches.
@MainActor
final class SessionChatGoal {
    nonisolated static let retryDelaysMs = [1_000, 2_000, 4_000]
    nonisolated static let commandTimeoutMs = 15_000
    static let reconnectEvents: Set<String> = ["server.connected", "stream.reconnected"]

    var onChange: (() -> Void)?

    private(set) var state = SessionChatGoalRegionState()

    private var sessionID: String
    private var worktree: String
    private let repository: any SessionGoalRepository
    private let foregroundEventStream: (any ForegroundEventStream)?
    private let retryDelaysMs: [Int]
    private let commandTimeoutMs: Int
    private var eventTask: Task<Void, Never>?
    private var fetchTask: Task<Void, Never>?
    private var commandTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    /// Tells a command's late completion from the one that replaced it.
    private var commandGeneration = 0
    private var started = false

    init(
        sessionID: String,
        worktree: String,
        repository: any SessionGoalRepository,
        foregroundEventStream: (any ForegroundEventStream)?,
        retryDelaysMs: [Int] = SessionChatGoal.retryDelaysMs,
        commandTimeoutMs: Int = SessionChatGoal.commandTimeoutMs
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.repository = repository
        self.foregroundEventStream = foregroundEventStream
        self.retryDelaysMs = retryDelaysMs
        self.commandTimeoutMs = commandTimeoutMs
    }

    func start() {
        guard !started, !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        started = true
        if let foregroundEventStream {
            // Subscribe before the first read so a change during it is not lost.
            let events = foregroundEventStream.events()
            eventTask = Task { [weak self] in
                for await event in events {
                    guard !Task.isCancelled else { return }
                    self?.handle(event)
                }
            }
        }
        refetch()
    }

    func stop() {
        started = false
        eventTask?.cancel()
        eventTask = nil
        fetchTask?.cancel()
        fetchTask = nil
        cancelCommand()
    }

    func rebind(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        stop()
        self.sessionID = sessionID
        state = SessionChatGoalRegionState()
        onChange?()
    }

    /// The session's checkout changed (a draft sent into a new worktree, or a moved session):
    /// reads and commands follow it, and the goal is read again there.
    func retarget(worktree: String) {
        guard worktree != self.worktree else { return }
        self.worktree = worktree
        if started { refetch() }
    }

    /// The stream went down or came back. Down keeps the last goal, dimmed; back refetches it,
    /// since changes while disconnected were missed.
    /// The screen calls this while it publishes, so it does not call `onChange` itself.
    func setConnected(_ connected: Bool) {
        let stale = !connected
        guard stale != state.isStale else { return }
        state.isStale = stale
        if stale {
            state.pendingAction = nil
            cancelCommand()
        }
        if connected, started { refetch() }
    }

    func refetch() {
        guard !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        fetchTask?.cancel()
        let repository = repository
        let sessionID = sessionID
        let worktree = worktree
        let delays = retryDelaysMs
        fetchTask = Task { [weak self] in
            guard await repository.isAvailable() else {
                self?.applyFetched(nil)
                return
            }
            var attempt = 0
            while !Task.isCancelled {
                do {
                    let goal = try await repository.goal(sessionID: sessionID, directory: worktree)
                    guard !Task.isCancelled else { return }
                    self?.applyFetched(goal)
                    return
                } catch {
                    guard !Task.isCancelled else { return }
                    // No goal while the read fails (no plugin, or still starting).
                    self?.applyFetched(nil)
                    guard attempt < delays.count else { return }
                    try? await Task.sleep(for: .milliseconds(delays[attempt]))
                    attempt += 1
                }
            }
        }
    }

    func openSheet() {
        guard state.goal != nil, !state.isSheetOpen else { return }
        state.isSheetOpen = true
        onChange?()
    }

    func closeSheet() {
        guard state.isSheetOpen else { return }
        state.isSheetOpen = false
        onChange?()
    }

    func clearNotice() {
        guard state.errorNotice != nil else { return }
        state.errorNotice = nil
        onChange?()
    }

    /// Whether Pause, Resume or Clear can be tapped now.
    var canAct: Bool {
        state.goal != nil && !state.isStale && state.pendingAction == nil
    }

    func perform(_ action: SessionGoalAction) {
        guard canAct, let goal = state.goal, Self.allows(action, for: goal) else { return }
        commandGeneration += 1
        let generation = commandGeneration
        state.pendingAction = action
        state.errorNotice = nil
        onChange?()
        let repository = repository
        let sessionID = sessionID
        let worktree = worktree
        commandTask = Task { [weak self] in
            do {
                try await repository.send(action, sessionID: sessionID, directory: worktree)
                self?.commandSent(generation: generation)
            } catch {
                self?.commandFailed(action, error: error, generation: generation)
            }
        }
    }

    /// Pause needs an active goal, resume a paused one; clear works on any.
    static func allows(_ action: SessionGoalAction, for goal: SessionGoal) -> Bool {
        switch action {
        case .pause: goal.status == .active
        case .resume: goal.status == .paused
        case .clear: true
        }
    }

    private func handle(_ event: OpencodeEvent) {
        if Self.reconnectEvents.contains(event.type) {
            refetch()
            return
        }
        guard let change = SessionGoalAdapter.change(from: event), change.sessionID == sessionID else { return }
        // The event is authoritative: it ends a running read and an action in flight.
        fetchTask?.cancel()
        fetchTask = nil
        let actionDone = state.pendingAction != nil
        if actionDone { cancelCommand() }
        var next = state
        next.goal = change.goal
        next.pendingAction = nil
        if actionDone || change.goal == nil { next.isSheetOpen = false }
        apply(next)
    }

    private func applyFetched(_ goal: SessionGoal?) {
        var next = state
        next.goal = goal
        if goal == nil {
            next.isSheetOpen = false
            next.pendingAction = nil
        }
        apply(next)
    }

    /// Sent; the change event confirms it. If none arrives in time the button frees up and the
    /// goal is read again.
    private func commandSent(generation: Int) {
        guard generation == commandGeneration, state.pendingAction != nil else { return }
        let timeout = commandTimeoutMs
        timeoutTask?.cancel()
        timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(timeout))
            guard !Task.isCancelled, let self, generation == self.commandGeneration,
                  self.state.pendingAction != nil
            else { return }
            self.state.pendingAction = nil
            self.onChange?()
            self.refetch()
        }
    }

    private func commandFailed(_ action: SessionGoalAction, error: Error, generation: Int) {
        guard generation == commandGeneration else { return }
        var next = state
        next.pendingAction = nil
        next.errorNotice = SessionChatGoalCopy.actionFailed(action, message: SessionGoalAdapter.commandErrorMessage(error))
        apply(next)
        refetch()
    }

    /// Forgets the command in flight. Its request is left to finish: the server may already
    /// have applied it, and its late answer is ignored by generation.
    private func cancelCommand() {
        commandGeneration += 1
        commandTask = nil
        timeoutTask?.cancel()
        timeoutTask = nil
    }

    private func apply(_ next: SessionChatGoalRegionState) {
        guard next != state else { return }
        state = next
        onChange?()
    }
}
