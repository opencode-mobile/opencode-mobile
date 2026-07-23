import Foundation
import OSLog

/// Client slash actions (Commands sheet): new / fork / undo / redo / compact.
/// These are local session behaviors, never routed through `CommandRepository`.
@MainActor
final class SessionChatCommands {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatCommandsRegionState()

    private let sessionID: String
    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let newSessionModelSelectionResolver: NewSessionModelSelectionResolver
    private let activeConnectionManager: ActiveConnectionManager

    private var currentRevertMessageID: String?
    private var forkedTarget: ChildSessionNavigationTarget?
    private var lastMessages: [SessionChatRow] = []
    private var lastHasSelectedComposerModel = false
    private var actionTask: Task<Void, Never>?
    private var forkTask: Task<Void, Never>?
    private var revertTask: Task<Void, Never>?

    init(
        sessionID: String,
        worktree: String,
        sessionRepository: any SessionRepository,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.newSessionModelSelectionResolver = newSessionModelSelectionResolver
        self.activeConnectionManager = activeConnectionManager
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

    func openCommands() {
        state.commandsSheetVisible = true
        notifyChange()
    }

    func dismissCommands() {
        state.commandsSheetVisible = false
        notifyChange()
    }

    func clearActionFeedback() {
        state.actionErrorMessage = nil
        state.actionSuccessMessage = nil
        notifyChange()
    }

    func consumePendingSessionReplace() {
        state.pendingSessionReplace = nil
        notifyChange()
    }

    func runNew() {
        dismissCommands()
        state.actionErrorMessage = nil
        state.actionSuccessMessage = nil
        notifyChange()
        let worktree = worktree
        actionTask?.cancel()
        actionTask = Task {
            do {
                let profile = activeConnectionManager.currentProfile()
                var selection: SessionModelSelection?
                if let profile {
                    selection = await newSessionModelSelectionResolver.resolve(profile: profile, worktree: worktree)
                }
                let session = try await sessionRepository.createSession(parentID: nil, title: nil, directory: worktree)
                if let profile, let selection {
                    await newSessionModelSelectionResolver.seedSession(
                        profile: profile,
                        worktree: worktree,
                        sessionID: session.id,
                        selection: selection
                    )
                }
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info("new session created id=\(session.id, privacy: .public)")
                state.pendingSessionReplace = ChildSessionNavigationTarget(
                    sessionID: session.id,
                    sessionTitle: session.title,
                    worktree: worktree
                )
                notifyChange()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning("new session failed error=\(error.localizedDescription, privacy: .public)")
                state.actionErrorMessage = "Couldn't start a new session."
                notifyChange()
            }
        }
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
                OpenCodeLog.sessionChat.info("fork success sourceSession=\(sessionID, privacy: .public) forkedSession=\(updated.id, privacy: .public)")
                forkedTarget = ChildSessionNavigationTarget(sessionID: updated.id, sessionTitle: updated.title, worktree: worktree)
                state.forkDialogVisible = false
                state.forkSubmitting = false
                state.forkResultVisible = true
                state.forkedSessionTitle = updated.title
                notifyChange()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "fork failed sourceSession=\(sessionID, privacy: .public) error=\(error.localizedDescription, privacy: .public)"
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
            .last(where: { $0.role == .user && !$0.isPending && !$0.isStreaming && !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
        else { return }
        let sessionID = sessionID
        let worktree = worktree
        revertTask?.cancel()
        revertTask = Task {
            if isBusy { _ = try? await sessionRepository.abortSession(id: sessionID, directory: worktree) }
            do {
                _ = try await sessionRepository.revertSession(id: sessionID, messageID: target.id, partID: nil)
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info("undo success session=\(sessionID, privacy: .public) message=\(target.id, privacy: .public)")
                await refreshRevertNow()
                restoreComposerText(target.text)
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning("undo failed session=\(sessionID, privacy: .public) error=\(error.localizedDescription, privacy: .public)")
                state.actionErrorMessage = "Couldn't undo last message."
                notifyChange()
            }
        }
    }

    func runRedo(isBusy: Bool, clearComposerRestore: @escaping () -> Void) {
        dismissCommands()
        guard currentRevertMessageID != nil else { return }
        let sessionID = sessionID
        let worktree = worktree
        revertTask?.cancel()
        revertTask = Task {
            if isBusy { _ = try? await sessionRepository.abortSession(id: sessionID, directory: worktree) }
            do {
                _ = try await sessionRepository.unrevertSession(id: sessionID)
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info("redo success session=\(sessionID, privacy: .public)")
                await refreshRevertNow()
                clearComposerRestore()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning("redo failed session=\(sessionID, privacy: .public) error=\(error.localizedDescription, privacy: .public)")
                state.actionErrorMessage = "Couldn't redo."
                notifyChange()
            }
        }
    }

    func runCompact(providerID: String, modelID: String, onSuccess: @escaping () -> Void) {
        dismissCommands()
        let sessionID = sessionID
        actionTask?.cancel()
        actionTask = Task {
            do {
                _ = try await sessionRepository.summarizeSession(id: sessionID, providerID: providerID, modelID: modelID)
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info("compact success session=\(sessionID, privacy: .public)")
                state.actionSuccessMessage = "Session compacted."
                notifyChange()
                onSuccess()
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning("compact failed session=\(sessionID, privacy: .public) error=\(error.localizedDescription, privacy: .public)")
                state.actionErrorMessage = "Couldn't compact session context."
                state.actionSuccessMessage = nil
                notifyChange()
            }
        }
    }

    private func refreshRevert() {
        Task { await refreshRevertNow() }
    }

    private func refreshRevertNow() async {
        guard let session = try? await sessionRepository.getSession(id: sessionID, directory: worktree) else { return }
        currentRevertMessageID = session.revert?.messageID
        updateVisibility(messages: lastMessages, hasSelectedComposerModel: lastHasSelectedComposerModel)
        notifyChange()
    }

    private func notifyChange() {
        onChange?()
    }
}
