import Foundation
import Observation

/// Long-press session actions on home: sheet, rename / fork dialogs, archive, delete.
@MainActor
@Observable
final class HomeSessionActions {
    private(set) var target: HomeSessionActionTarget?
    private(set) var sheetVisible = false
    private(set) var canFork = false
    private(set) var canArchive = true
    private(set) var lifecycle: HomeSessionLifecycle = .active
    private(set) var renameDialog = HomeSessionTitleDialogState()
    private(set) var forkDialog = HomeSessionTitleDialogState()
    private(set) var forkResult = HomeSessionForkResultState()
    private(set) var deleteConfirmVisible = false
    private(set) var actionInFlight = false

    var onLifecycleError: ((String) -> Void)?

    private let sessionRepository: any SessionRepository
    private let messageRepository: any MessageRepository
    private let capabilities: () -> SessionCapabilities

    private var forkGateTask: Task<Void, Never>?
    private var forkedOpenTarget: SessionListItem?

    init(
        sessionRepository: any SessionRepository,
        messageRepository: any MessageRepository,
        capabilities: @escaping () -> SessionCapabilities = { .opencode }
    ) {
        self.sessionRepository = sessionRepository
        self.messageRepository = messageRepository
        self.capabilities = capabilities
    }

    func begin(sessionId: String, title: String, worktree: String, canArchive: Bool, lifecycle: HomeSessionLifecycle = .active) {
        let next = HomeSessionActionTarget(sessionId: sessionId, title: title, worktree: worktree)
        target = next
        sheetVisible = true
        canFork = false
        self.canArchive = canArchive && lifecycle == .active
        self.lifecycle = lifecycle
        forkGateTask?.cancel()
        // Archived and settled rows only restore or delete; fork stays off.
        guard lifecycle == .active else { return }
        forkGateTask = Task { await resolveForkVisibility(next) }
    }

    /// Restores an archived session; true when the server accepted it.
    func chooseUnarchive() async -> Bool {
        guard let target, lifecycle == .archived, !actionInFlight else { return false }
        sheetVisible = false
        actionInFlight = true
        OpenCodeLog.sessionChat.info("home unarchive start session=\(target.sessionId)")
        do {
            _ = try await sessionRepository.unarchiveSession(id: target.sessionId, directory: target.worktree)
            OpenCodeLog.sessionChat.info("home unarchive success session=\(target.sessionId)")
            actionInFlight = false
            clear()
            return true
        } catch {
            OpenCodeLog.sessionChat.warning(
                "home unarchive failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            actionInFlight = false
            clear()
            onLifecycleError?(Self.unarchiveError)
            return false
        }
    }

    /// Re-activates a settled session; true when the server accepted it.
    func chooseUnsettle() async -> Bool {
        guard let target, lifecycle == .settled, !actionInFlight else { return false }
        sheetVisible = false
        actionInFlight = true
        OpenCodeLog.sessionChat.info("home unsettle start session=\(target.sessionId)")
        do {
            let restored = try await sessionRepository.unsettleSession(id: target.sessionId, directory: target.worktree)
            OpenCodeLog.sessionChat.info("home unsettle \(restored ? "success" : "rejected") session=\(target.sessionId)")
            actionInFlight = false
            clear()
            if !restored { onLifecycleError?(Self.unsettleError) }
            return restored
        } catch {
            OpenCodeLog.sessionChat.warning(
                "home unsettle failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            actionInFlight = false
            clear()
            onLifecycleError?(Self.unsettleError)
            return false
        }
    }

    func dismissSheet() {
        sheetVisible = false
        guard !hasOpenDialog else { return }
        clear()
    }

    func chooseRename() {
        guard let target else { return }
        sheetVisible = false
        renameDialog = HomeSessionTitleDialogState(isVisible: true, draftTitle: target.title)
    }

    func updateRenameDraft(_ text: String) {
        renameDialog.draftTitle = text
        renameDialog.errorMessage = nil
    }

    func cancelRename() {
        guard !renameDialog.isSubmitting else { return }
        renameDialog = HomeSessionTitleDialogState()
        clear()
    }

    func confirmRename() async -> String? {
        guard let target, !renameDialog.isSubmitting else { return nil }
        let trimmed = renameDialog.draftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            renameDialog.errorMessage = Self.emptyTitleError
            return ProductAnalytics.Outcome.blocked
        }
        if trimmed == target.title {
            renameDialog = HomeSessionTitleDialogState()
            clear()
            return ProductAnalytics.Outcome.blocked
        }
        renameDialog.isSubmitting = true
        renameDialog.errorMessage = nil
        OpenCodeLog.sessionChat.info("home rename submit session=\(target.sessionId)")
        do {
            _ = try await sessionRepository.updateSession(
                id: target.sessionId,
                title: trimmed,
                directory: target.worktree
            )
            guard self.target == target else { return nil }
            OpenCodeLog.sessionChat.info("home rename success session=\(target.sessionId)")
            renameDialog = HomeSessionTitleDialogState()
            clear()
            return ProductAnalytics.Outcome.success
        } catch {
            guard self.target == target else { return nil }
            OpenCodeLog.sessionChat.warning(
                "home rename failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            renameDialog.isSubmitting = false
            renameDialog.errorMessage = mutationErrorMessage(error)
            return ProductAnalytics.Outcome.failure
        }
    }

    func chooseFork() {
        guard let target else { return }
        sheetVisible = false
        forkDialog = HomeSessionTitleDialogState(
            isVisible: true,
            draftTitle: ProjectListHomeViewModel.suggestedForkTitle(for: target.title)
        )
    }

    func updateForkDraft(_ text: String) {
        forkDialog.draftTitle = text
        forkDialog.errorMessage = nil
    }

    func cancelFork() {
        guard !forkDialog.isSubmitting else { return }
        forkDialog = HomeSessionTitleDialogState()
        clear()
    }

    func confirmFork() async -> String? {
        guard let target, !forkDialog.isSubmitting else { return nil }
        let trimmed = forkDialog.draftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            forkDialog.errorMessage = Self.emptyTitleError
            return ProductAnalytics.Outcome.blocked
        }
        forkDialog.isSubmitting = true
        forkDialog.errorMessage = nil
        do {
            let forked = try await sessionRepository.forkSession(id: target.sessionId, atMessageID: nil)
            let updated = try await sessionRepository.updateSession(
                id: forked.id,
                title: trimmed,
                directory: target.worktree
            )
            guard self.target == target else { return nil }
            OpenCodeLog.sessionChat.info(
                "home fork success sourceSession=\(target.sessionId) forkedSession=\(updated.id)"
            )
            forkedOpenTarget = SessionListItem(
                id: updated.id,
                title: updated.title,
                worktree: target.worktree
            )
            forkDialog = HomeSessionTitleDialogState()
            forkResult = HomeSessionForkResultState(isVisible: true, forkedTitle: updated.title)
            return ProductAnalytics.Outcome.success
        } catch {
            guard self.target == target else { return nil }
            OpenCodeLog.sessionChat.warning(
                "home fork failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            forkDialog.isSubmitting = false
            forkDialog.errorMessage = mutationErrorMessage(error)
            return ProductAnalytics.Outcome.failure
        }
    }

    func takeForkedOpenTarget() -> SessionListItem? {
        let next = forkedOpenTarget
        forkedOpenTarget = nil
        forkResult = HomeSessionForkResultState()
        clear()
        return next
    }

    func clearForkedOpenTarget() {
        forkedOpenTarget = nil
        forkResult = HomeSessionForkResultState()
        clear()
    }

    func chooseArchive() async {
        guard let target, canArchive, !actionInFlight else { return }
        sheetVisible = false
        actionInFlight = true
        OpenCodeLog.sessionChat.info("home archive start session=\(target.sessionId)")
        do {
            _ = try await sessionRepository.archiveSession(id: target.sessionId, directory: target.worktree)
            OpenCodeLog.sessionChat.info("home archive success session=\(target.sessionId)")
            actionInFlight = false
            clear()
        } catch {
            OpenCodeLog.sessionChat.warning(
                "home archive failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            actionInFlight = false
            clear()
            onLifecycleError?(Self.archiveError)
        }
    }

    func chooseDelete() {
        guard target != nil else { return }
        sheetVisible = false
        deleteConfirmVisible = true
    }

    func dismissDeleteConfirm() {
        guard !actionInFlight else { return }
        deleteConfirmVisible = false
        clear()
    }

    func confirmDelete() async {
        guard let target, !actionInFlight else { return }
        deleteConfirmVisible = false
        actionInFlight = true
        OpenCodeLog.sessionChat.info("home delete start session=\(target.sessionId)")
        do {
            let deleted = try await sessionRepository.deleteSession(id: target.sessionId, directory: target.worktree)
            actionInFlight = false
            clear()
            if deleted {
                OpenCodeLog.sessionChat.info("home delete success session=\(target.sessionId)")
            } else {
                OpenCodeLog.sessionChat.info("home delete rejected session=\(target.sessionId)")
                onLifecycleError?(Self.deleteError)
            }
        } catch {
            OpenCodeLog.sessionChat.warning(
                "home delete failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            actionInFlight = false
            clear()
            onLifecycleError?(Self.deleteError)
        }
    }

    /// Fork parity with Commands: at least one visible user message. A failed fetch
    /// leaves Fork hidden rather than offering an action the server may reject.
    private func resolveForkVisibility(_ target: HomeSessionActionTarget) async {
        guard let messages = try? await messageRepository.listMessages(
            sessionID: target.sessionId,
            directory: target.worktree
        ) else { return }
        guard !Task.isCancelled, self.target == target else { return }
        let refs = messages.map { message in
            ClientSlashMessageRef(
                id: message.id,
                isUser: message.role == .user,
                isPending: false,
                isStreaming: false,
                hasNonEmptyText: !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )
        }
        let input = ClientSlashActionCatalog.visibilityInput(
            messages: refs,
            revertMessageID: nil,
            hasSelectedComposerModel: false
        )
        canFork = ClientSlashActionCatalog.visibleActions(input)
            .filter { capabilities().allowsClientSlashAction($0) }
            .contains(.fork)
    }

    private var hasOpenDialog: Bool {
        renameDialog.isVisible
            || forkDialog.isVisible
            || forkResult.isVisible
            || deleteConfirmVisible
            || actionInFlight
    }

    private func clear() {
        forkGateTask?.cancel()
        forkGateTask = nil
        target = nil
        sheetVisible = false
        canFork = false
        canArchive = true
        lifecycle = .active
    }

    private func mutationErrorMessage(_ error: Error) -> String {
        guard let sessionError = error as? SessionError else { return error.localizedDescription }
        switch sessionError {
        case let .http(_, message):
            return message ?? "Request failed"
        case .transport:
            return "Unable to reach the server"
        case .decoding:
            return "Unable to read server response"
        }
    }

    private static let emptyTitleError = "Enter a session title"
    private static let archiveError = "Couldn't archive session."
    private static let unarchiveError = "Couldn't unarchive session."
    private static let unsettleError = "Couldn't unsettle session."
    private static let deleteError = "Couldn't delete session."
}
