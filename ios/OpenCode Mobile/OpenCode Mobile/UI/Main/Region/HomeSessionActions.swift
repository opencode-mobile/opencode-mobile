import Foundation
import Observation

/// Long-press session actions on home: sheet, rename / fork dialogs, archive, delete.
@MainActor
@Observable
final class HomeSessionActions {
    private(set) var target: HomeSessionActionTarget?
    private(set) var sheetVisible = false
    private(set) var canArchive = true
    private(set) var renameDialog = HomeSessionTitleDialogState()
    private(set) var forkDialog = HomeSessionTitleDialogState()
    private(set) var forkResult = HomeSessionForkResultState()
    private(set) var deleteConfirmVisible = false
    private(set) var actionInFlight = false

    var onLifecycleError: ((String) -> Void)?

    private let sessionRepository: any SessionRepository
    private let messageRepository: any MessageRepository

    private var forkCheckTask: Task<Void, Never>?
    private var forkedOpenTarget: SessionListItem?

    init(
        sessionRepository: any SessionRepository,
        messageRepository: any MessageRepository
    ) {
        self.sessionRepository = sessionRepository
        self.messageRepository = messageRepository
    }

    func begin(sessionId: String, title: String, worktree: String, canArchive: Bool) {
        let next = HomeSessionActionTarget(sessionId: sessionId, title: title, worktree: worktree)
        target = next
        sheetVisible = true
        self.canArchive = canArchive
        forkCheckTask?.cancel()
        forkCheckTask = nil
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
        forkCheckTask?.cancel()
        forkCheckTask = Task { await resolveForkCheck(target) }
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
        if forkDialog.blockedMessage != nil { return ProductAnalytics.Outcome.blocked }
        let trimmed = forkDialog.draftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            forkDialog.errorMessage = Self.emptyTitleError
            return ProductAnalytics.Outcome.blocked
        }
        forkDialog.isSubmitting = true
        forkDialog.errorMessage = nil
        // A quick confirm can beat the check started when the dialog opened.
        await forkCheckTask?.value
        guard self.target == target else { return nil }
        if forkDialog.blockedMessage != nil {
            forkDialog.isSubmitting = false
            return ProductAnalytics.Outcome.blocked
        }
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

    /// Fork parity with Commands: at least one visible user message. The sheet always lists
    /// Fork so it never reflows; this runs once the fork dialog opens and blocks the dialog
    /// when there is nothing to fork. A failed fetch leaves the server to decide.
    private func resolveForkCheck(_ target: HomeSessionActionTarget) async {
        guard let messages = try? await messageRepository.listMessages(
            sessionID: target.sessionId,
            directory: target.worktree
        ) else { return }
        guard !Task.isCancelled, self.target == target, forkDialog.isVisible else { return }
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
        if !ClientSlashActionCatalog.visibleActions(input).contains(.fork) {
            forkDialog.blockedMessage = Self.nothingToForkError
        }
    }

    private var hasOpenDialog: Bool {
        renameDialog.isVisible
            || forkDialog.isVisible
            || forkResult.isVisible
            || deleteConfirmVisible
            || actionInFlight
    }

    private func clear() {
        forkCheckTask?.cancel()
        forkCheckTask = nil
        target = nil
        sheetVisible = false
        canArchive = true
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
    private static let nothingToForkError = "Nothing to fork yet"
    private static let archiveError = "Couldn't archive session."
    private static let deleteError = "Couldn't delete session."
}
