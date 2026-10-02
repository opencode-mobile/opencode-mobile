import Foundation

/// Client slash actions (Commands sheet): new / fork / undo / redo / compact.
/// These are local session behaviors, never routed through `CommandRepository`.
@MainActor
final class SessionChatCommands {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatCommandsRegionState()

    private var sessionID: String
    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let newSessionModelSelectionResolver: NewSessionModelSelectionResolver
    private let activeConnectionManager: ActiveConnectionManager
    private let mcpRepository: any McpRepository
    private var mcpTask: Task<Void, Never>?

    private var currentRevertMessageID: String?
    private var forkedTarget: ChildSessionNavigationTarget?
    private var lastMessages: [SessionChatRow] = []
    private var lastHasSelectedComposerModel = false
    private var lastDomainMessages: [Message] = []
    private var completedCompactionIDsAtStart: Set<String> = []
    /// A v2 compaction the server only enqueued: success waits for the live compaction item.
    private var pendingLiveCompaction: PendingLiveCompaction?
    private var actionTask: Task<Void, Never>?
    private var forkTask: Task<Void, Never>?
    private var revertTask: Task<Void, Never>?

    init(
        sessionID: String,
        worktree: String,
        sessionRepository: any SessionRepository,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
        activeConnectionManager: ActiveConnectionManager,
        mcpRepository: any McpRepository = EmptyMcpRepository()
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.newSessionModelSelectionResolver = newSessionModelSelectionResolver
        self.activeConnectionManager = activeConnectionManager
        self.mcpRepository = mcpRepository
    }

    func rebind(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        actionTask?.cancel()
        forkTask?.cancel()
        revertTask?.cancel()
        self.sessionID = sessionID
        currentRevertMessageID = nil
        forkedTarget = nil
        lastDomainMessages = []
        completedCompactionIDsAtStart = []
        pendingLiveCompaction = nil
        state = SessionChatCommandsRegionState()
        notifyChange()
        refreshRevert()
    }

    /// Revert visibility fetch — must not run from `init` (see SessionChatComposer.startCatalogLoads).
    func start() {
        refreshRevert()
    }

    /// Pure state recomputation; callers invoked synchronously from `SessionChatViewModel.publishUiState`
    /// (which reads `state` right after) must not trigger `onChange` here to avoid re-entrant publishing.
    /// Callers from async contexts (e.g. `refreshRevertNow`) must call `notifyChange()` themselves.
    func updateVisibility(messages: [SessionChatRow], hasSelectedComposerModel: Bool) {
        lastMessages = messages
        lastHasSelectedComposerModel = hasSelectedComposerModel
        let refs = messages.map { message in
            ClientSlashMessageRef(
                id: message.id,
                isUser: message.role == .user,
                isPending: message.isPending,
                isStreaming: message.isStreaming,
                hasNonEmptyText: !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )
        }
        let input = ClientSlashActionCatalog.visibilityInput(
            messages: refs,
            revertMessageID: currentRevertMessageID,
            hasSelectedComposerModel: hasSelectedComposerModel
        )
        state.visibleActions = ClientSlashActionCatalog.visibleActions(input)
    }

    func observeLoadedMessages(_ messages: [Message], isSessionBusy: Bool = false) {
        lastDomainMessages = messages
        guard state.isCompacting else { return }
        if isSessionBusy { pendingLiveCompaction?.sawBusy = true }
        let finished = SessionCompactionPresence.completedCompactions(in: messages)
            .filter { !completedCompactionIDsAtStart.contains($0.key) }
        if !finished.isEmpty {
            OpenCodeLog.sessionChat.info("compact completed via timeline")
            state.isCompacting = false
            if let pending = pendingLiveCompaction {
                pendingLiveCompaction = nil
                // v2 `compaction.ended` carries the summary; `compaction.failed` does not
                // (its error reaches the session error chrome).
                if finished.values.contains(where: { !($0 ?? "").isEmpty }) {
                    postLiveCompactionSuccess(pending)
                }
            }
            return
        }
        if let pending = pendingLiveCompaction, pending.sawBusy, !isSessionBusy,
           !SessionCompactionPresence.isActive(messages: messages, isSessionBusy: true)
        {
            // The session went idle without a completed compaction (failed or cancelled).
            OpenCodeLog.sessionChat.info("compact ended without a summary")
            pendingLiveCompaction = nil
            state.isCompacting = false
        }
    }

    private func postLiveCompactionSuccess(_ pending: PendingLiveCompaction) {
        // Called while publishing UI state: defer the success notice to its own change.
        actionTask = Task { [weak self] in
            let after = await pending.awaitAfterContextFraction()
            guard let self, !Task.isCancelled else { return }
            self.state.actionSuccessMessage = SessionCompactChrome.successMessage(
                beforeFraction: pending.beforeContextFraction,
                afterFraction: after
            )
            self.notifyChange()
        }
    }

    func openCommands() {
        state.commandsSheetVisible = true
        state.mcpPageVisible = false
        notifyChange()
        refreshMcpServers()
    }

    func dismissCommands() {
        state.commandsSheetVisible = false
        state.mcpPageVisible = false
        notifyChange()
    }

    /// Second page of the Commands sheet: read-only MCP server status.
    func openMcpServers() {
        state.mcpPageVisible = true
        notifyChange()
        refreshMcpServers()
    }

    func closeMcpServers() {
        state.mcpPageVisible = false
        notifyChange()
    }

    func refreshMcpServers() {
        let worktree = worktree
        mcpTask?.cancel()
        state.mcpLoading = true
        notifyChange()
        mcpTask = Task {
            do {
                let servers = try await mcpRepository.listServers(directory: worktree)
                guard !Task.isCancelled else { return }
                state.mcpServers = servers
                state.mcpSummary = McpServerCatalog.summary(servers)
                state.mcpLoading = false
                state.mcpErrorMessage = nil
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning("mcp servers load failed error=\(error.localizedDescription)")
                state.mcpLoading = false
                state.mcpErrorMessage = "Couldn't load MCP servers. Check the connection and try again."
            }
            notifyChange()
        }
    }

    func clearActionFeedback() {
        state.actionErrorMessage = nil
        state.actionSuccessMessage = nil
        notifyChange()
    }

    func reportActionError(_ message: String) {
        state.actionErrorMessage = message
        state.actionSuccessMessage = nil
        notifyChange()
    }

    func consumePendingSessionReplace() {
        state.pendingSessionReplace = nil
        notifyChange()
    }

    func consumePendingTerminalOpen() {
        state.pendingTerminalOpen = nil
        notifyChange()
    }

    func runTerminal(projectTitle: String) {
        dismissCommands()
        state.pendingTerminalOpen = PendingTerminalOpen(worktree: worktree, projectTitle: projectTitle)
        notifyChange()
    }

    func runNew() {
        dismissCommands()
        state.actionErrorMessage = nil
        state.actionSuccessMessage = nil
        notifyChange()
        state.pendingSessionReplace = ChildSessionNavigationTarget(
            sessionID: OptimisticSessionCreate.placeholderSessionID,
            sessionTitle: "New Session",
            worktree: worktree,
            optimisticNavigationInstanceID: UUID()
        )
        notifyChange()
    }

    func openForkDialog(currentTitle: String) {
        dismissCommands()
        state.forkDialogVisible = true
        state.forkDraftTitle = currentTitle
        state.forkSubmitting = false
        state.forkErrorMessage = nil
        notifyChange()
    }

    func updateForkDraft(_ text: String) {
        state.forkDraftTitle = text
        state.forkErrorMessage = nil
        notifyChange()
    }

    func cancelForkDialog() {
        state.forkDialogVisible = false
        state.forkSubmitting = false
        state.forkErrorMessage = nil
        notifyChange()
    }

    func confirmFork() {
        guard !state.forkSubmitting else { return }
        let title = state.forkDraftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if title.isEmpty {
            state.forkErrorMessage = "Enter a session title"
            notifyChange()
            return
        }
        let sessionID = sessionID
        let worktree = worktree
        state.forkSubmitting = true
        state.forkErrorMessage = nil
        notifyChange()
        forkTask?.cancel()
        forkTask = Task {
            do {
                let forked = try await sessionRepository.forkSession(id: sessionID, atMessageID: nil)
                let updated = try await sessionRepository.updateSession(id: forked.id, title: title, directory: worktree)
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info("fork success sourceSession=\(sessionID) forkedSession=\(updated.id)")
                forkedTarget = ChildSessionNavigationTarget(
                    sessionID: updated.id,
                    sessionTitle: updated.title,
                    worktree: worktree,
                    optimisticNavigationInstanceID: nil
                )
                state.forkDialogVisible = false
                state.forkSubmitting = false
                state.forkResultVisible = true
                state.forkedSessionTitle = updated.title
                notifyChange()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "fork failed sourceSession=\(sessionID) error=\(error.localizedDescription)"
                )
                state.forkSubmitting = false
                state.forkErrorMessage = error.localizedDescription
                notifyChange()
            }
        }
    }

    func chooseForkOpen() {
        let target = forkedTarget
        forkedTarget = nil
        state.forkResultVisible = false
        state.pendingSessionReplace = target
        notifyChange()
    }

    func chooseForkStay() {
        forkedTarget = nil
        state.forkResultVisible = false
        notifyChange()
    }

    func runUndo(isBusy: Bool, restoreComposerText: @escaping (String) -> Void) {
        dismissCommands()
        let boundary = currentRevertMessageID
        guard let target = lastMessages
            .filter({ boundary == nil || $0.id < boundary! })
            .last(where: isRevertibleUserMessage)
        else { return }
        revert(
            target,
            isBusy: isBusy,
            restoreComposerText: restoreComposerText,
            operation: "undo",
            failureMessage: "Couldn't undo last message."
        )
    }

    func revertMessage(id: String, isBusy: Bool, restoreComposerText: @escaping (String) -> Void) {
        guard let target = lastMessages.first(where: { $0.id == id && isRevertibleUserMessage($0) }) else { return }
        revert(
            target,
            isBusy: isBusy,
            restoreComposerText: restoreComposerText,
            operation: "revert",
            failureMessage: "Couldn't revert message."
        )
    }

    func runRedo(isBusy: Bool, clearComposerRestore: @escaping () -> Void) {
        dismissCommands()
        unrevert(
            isBusy: isBusy,
            clearComposerRestore: clearComposerRestore,
            operation: "redo",
            failureMessage: "Couldn't redo."
        )
    }

    func cancelRevert(isBusy: Bool, clearComposerRestore: @escaping () -> Void) {
        unrevert(
            isBusy: isBusy,
            clearComposerRestore: clearComposerRestore,
            operation: "cancel-revert",
            failureMessage: "Couldn't cancel revert."
        )
    }

    func consumePendingRevert() {
        guard currentRevertMessageID != nil else { return }
        setRevertBoundary(nil)
    }

    func runCompact(
        providerID: String,
        modelID: String,
        beforeContextFraction: Float?,
        awaitAfterContextFraction: @escaping () async -> Float?
    ) {
        dismissCommands()
        let sessionID = sessionID
        actionTask?.cancel()
        completedCompactionIDsAtStart = SessionCompactionPresence.completedCompactionIDs(in: lastDomainMessages)
        pendingLiveCompaction = nil
        state.isCompacting = true
        state.actionErrorMessage = nil
        state.actionSuccessMessage = nil
        notifyChange()
        // Summarize off the MainActor (Android uses ioDispatcher). Compaction can run for
        // minutes; keeping the await on MainActor lets SSE timeline applies starve touches
        // and delays clearing Compacting… until a follow-up full refresh finishes.
        let repository = sessionRepository
        actionTask = Task {
            do {
                let start = try await Task.detached {
                    try await repository.compactSession(
                        id: sessionID,
                        providerID: providerID,
                        modelID: modelID
                    )
                }.value
                guard !Task.isCancelled else {
                    state.isCompacting = false
                    notifyChange()
                    return
                }
                if start == .enqueued {
                    // v2 returns at enqueue time: stay Compacting… until the live compaction
                    // item completes (observeLoadedMessages).
                    OpenCodeLog.sessionChat.info("compact enqueued session=\(sessionID)")
                    guard state.isCompacting else { return }
                    pendingLiveCompaction = PendingLiveCompaction(
                        beforeContextFraction: beforeContextFraction,
                        awaitAfterContextFraction: awaitAfterContextFraction
                    )
                    observeLoadedMessages(lastDomainMessages)
                    notifyChange()
                    return
                }
                OpenCodeLog.sessionChat.info("compact success session=\(sessionID)")
                // Clear chrome before any post-success work so a heavy refresh cannot leave
                // Compacting… stuck while MainActor rebuilds the transcript.
                state.isCompacting = false
                notifyChange()
                let after = await awaitAfterContextFraction()
                guard !Task.isCancelled else { return }
                state.actionSuccessMessage = SessionCompactChrome.successMessage(
                    beforeFraction: beforeContextFraction,
                    afterFraction: after
                )
                notifyChange()
            } catch {
                guard !Task.isCancelled else {
                    state.isCompacting = false
                    notifyChange()
                    return
                }
                OpenCodeLog.sessionChat.warning("compact failed session=\(sessionID) error=\(error.localizedDescription)")
                state.isCompacting = false
                state.actionErrorMessage = SessionCompactChrome.failure
                state.actionSuccessMessage = nil
                notifyChange()
            }
        }
    }

    private func isRevertibleUserMessage(_ message: SessionChatRow) -> Bool {
        message.role == .user
            && !message.isPending
            && !message.isStreaming
            && isBeforeRevertBoundary(message.id)
            && hasRevertibleContent(message)
    }

    private func isBeforeRevertBoundary(_ id: String) -> Bool {
        guard let boundary = currentRevertMessageID else { return true }
        return id < boundary
    }

    private func hasRevertibleContent(_ message: SessionChatRow) -> Bool {
        !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || message.blocks.contains { if case .attachment = $0 { true } else { false } }
    }

    private func revert(
        _ target: SessionChatRow,
        isBusy: Bool,
        restoreComposerText: @escaping (String) -> Void,
        operation: String,
        failureMessage: String
    ) {
        let sessionID = sessionID
        let worktree = worktree
        revertTask?.cancel()
        revertTask = Task {
            if isBusy { _ = try? await sessionRepository.abortSession(id: sessionID, directory: worktree) }
            do {
                _ = try await sessionRepository.revertSession(id: sessionID, messageID: target.id, partID: nil)
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info(
                    "\(operation) success session=\(sessionID) message=\(target.id)"
                )
                setRevertBoundary(target.id)
                await refreshRevertNow()
                restoreComposerText(target.text)
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "\(operation) failed session=\(sessionID) error=\(error.localizedDescription)"
                )
                state.actionErrorMessage = failureMessage
                notifyChange()
            }
        }
    }

    private func unrevert(
        isBusy: Bool,
        clearComposerRestore: @escaping () -> Void,
        operation: String,
        failureMessage: String
    ) {
        guard currentRevertMessageID != nil else { return }
        let sessionID = sessionID
        let worktree = worktree
        revertTask?.cancel()
        revertTask = Task {
            if isBusy { _ = try? await sessionRepository.abortSession(id: sessionID, directory: worktree) }
            do {
                _ = try await sessionRepository.unrevertSession(id: sessionID)
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info("\(operation) success session=\(sessionID)")
                setRevertBoundary(nil)
                await refreshRevertNow()
                clearComposerRestore()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "\(operation) failed session=\(sessionID) error=\(error.localizedDescription)"
                )
                state.actionErrorMessage = failureMessage
                notifyChange()
            }
        }
    }

    private func setRevertBoundary(_ messageID: String?) {
        currentRevertMessageID = messageID
        state.hasPendingRevert = messageID != nil
        updateVisibility(messages: lastMessages, hasSelectedComposerModel: lastHasSelectedComposerModel)
        notifyChange()
    }

    private func refreshRevert() {
        Task { await refreshRevertNow() }
    }

    private func refreshRevertNow() async {
        guard let session = try? await sessionRepository.getSession(id: sessionID, directory: worktree) else { return }
        setRevertBoundary(session.revert?.messageID)
    }

    private func notifyChange() {
        onChange?()
    }
}

@MainActor
private final class PendingLiveCompaction {
    let beforeContextFraction: Float?
    let awaitAfterContextFraction: () async -> Float?
    var sawBusy = false

    init(beforeContextFraction: Float?, awaitAfterContextFraction: @escaping () async -> Float?) {
        self.beforeContextFraction = beforeContextFraction
        self.awaitAfterContextFraction = awaitAfterContextFraction
    }
}
