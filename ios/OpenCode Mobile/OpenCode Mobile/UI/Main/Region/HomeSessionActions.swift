import Foundation

/// Long-press session actions on home: sheet, rename / fork dialogs, archive, delete.
struct HomeSessionActionsState: Equatable {
    var target: HomeSessionActionTarget?
    var sheetVisible = false
    var canArchive = true
    var renameDialog = HomeSessionTitleDialogState()
    var forkDialog = HomeSessionTitleDialogState()
    var forkResult = HomeSessionForkResultState()
    var deleteConfirmVisible = false
    var actionInFlight = false
}

@MainActor
final class HomeSessionActions {
    /// Every change reports through `onChange`, so the facade republishes Home's state.
    private(set) var state = HomeSessionActionsState() {
        didSet {
            if state != oldValue { onChange?() }
        }
    }

    var onChange: (() -> Void)?
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
        state.target = next
        state.sheetVisible = true
        state.canArchive = canArchive
        forkCheckTask?.cancel()
        forkCheckTask = nil
    }

    func dismissSheet() {
        state.sheetVisible = false
        guard !hasOpenDialog else { return }
        clear()
    }

    func chooseRename() {
        guard let target = state.target else { return }
        state.sheetVisible = false
        state.renameDialog = HomeSessionTitleDialogState(isVisible: true, draftTitle: target.title)
    }

    func updateRenameDraft(_ text: String) {
        state.renameDialog.draftTitle = text
        state.renameDialog.errorMessage = nil
    }

    func cancelRename() {
        guard !state.renameDialog.isSubmitting else { return }
        state.renameDialog = HomeSessionTitleDialogState()
        clear()
    }

    func confirmRename() async -> String? {
        guard let target = state.target, !state.renameDialog.isSubmitting else { return nil }
        let trimmed = state.renameDialog.draftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            state.renameDialog.errorMessage = Self.emptyTitleError
            return ProductAnalytics.Outcome.blocked
        }
        if trimmed == target.title {
            state.renameDialog = HomeSessionTitleDialogState()
            clear()
            return ProductAnalytics.Outcome.blocked
        }
        state.renameDialog.isSubmitting = true
        state.renameDialog.errorMessage = nil
        OpenCodeLog.sessionChat.info("home rename submit session=\(target.sessionId)")
        do {
            _ = try await sessionRepository.updateSession(
                id: target.sessionId,
                title: trimmed,
                directory: target.worktree
            )
            guard state.target == target else { return nil }
            OpenCodeLog.sessionChat.info("home rename success session=\(target.sessionId)")
            state.renameDialog = HomeSessionTitleDialogState()
            clear()
            return ProductAnalytics.Outcome.success
        } catch {
            guard state.target == target else { return nil }
            OpenCodeLog.sessionChat.warning(
                "home rename failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            state.renameDialog.isSubmitting = false
            state.renameDialog.errorMessage = mutationErrorMessage(error)
            return ProductAnalytics.Outcome.failure
        }
    }

    func chooseFork() {
        guard let target = state.target else { return }
        state.sheetVisible = false
        state.forkDialog = HomeSessionTitleDialogState(
            isVisible: true,
            draftTitle: ProjectListHomeViewModel.suggestedForkTitle(for: target.title)
        )
        forkCheckTask?.cancel()
        forkCheckTask = Task { await resolveForkCheck(target) }
    }

    func updateForkDraft(_ text: String) {
        state.forkDialog.draftTitle = text
        state.forkDialog.errorMessage = nil
    }

    func cancelFork() {
        guard !state.forkDialog.isSubmitting else { return }
        state.forkDialog = HomeSessionTitleDialogState()
        clear()
    }

    func confirmFork() async -> String? {
        guard let target = state.target, !state.forkDialog.isSubmitting else { return nil }
        if state.forkDialog.blockedMessage != nil { return ProductAnalytics.Outcome.blocked }
        let trimmed = state.forkDialog.draftTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            state.forkDialog.errorMessage = Self.emptyTitleError
            return ProductAnalytics.Outcome.blocked
        }
        state.forkDialog.isSubmitting = true
        state.forkDialog.errorMessage = nil
        // A quick confirm can beat the check started when the dialog opened.
        await forkCheckTask?.value
        guard state.target == target else { return nil }
        if state.forkDialog.blockedMessage != nil {
            state.forkDialog.isSubmitting = false
            return ProductAnalytics.Outcome.blocked
        }
        do {
            let forked = try await sessionRepository.forkSession(id: target.sessionId, atMessageID: nil)
            let updated = try await sessionRepository.updateSession(
                id: forked.id,
                title: trimmed,
                directory: target.worktree
            )
            guard state.target == target else { return nil }
            OpenCodeLog.sessionChat.info(
                "home fork success sourceSession=\(target.sessionId) forkedSession=\(updated.id)"
            )
            forkedOpenTarget = SessionListItem(
                id: updated.id,
                title: updated.title,
                worktree: target.worktree
            )
            state.forkDialog = HomeSessionTitleDialogState()
            state.forkResult = HomeSessionForkResultState(isVisible: true, forkedTitle: updated.title)
            return ProductAnalytics.Outcome.success
        } catch {
            guard state.target == target else { return nil }
            OpenCodeLog.sessionChat.warning(
                "home fork failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            state.forkDialog.isSubmitting = false
            state.forkDialog.errorMessage = mutationErrorMessage(error)
            return ProductAnalytics.Outcome.failure
        }
    }

    func takeForkedOpenTarget() -> SessionListItem? {
        let next = forkedOpenTarget
        forkedOpenTarget = nil
        state.forkResult = HomeSessionForkResultState()
        clear()
        return next
    }

    func clearForkedOpenTarget() {
        forkedOpenTarget = nil
        state.forkResult = HomeSessionForkResultState()
        clear()
    }

    func chooseArchive() async {
        guard let target = state.target, state.canArchive, !state.actionInFlight else { return }
        state.sheetVisible = false
        state.actionInFlight = true
        OpenCodeLog.sessionChat.info("home archive start session=\(target.sessionId)")
        do {
            _ = try await sessionRepository.archiveSession(id: target.sessionId, directory: target.worktree)
            OpenCodeLog.sessionChat.info("home archive success session=\(target.sessionId)")
            state.actionInFlight = false
            clear()
        } catch {
            OpenCodeLog.sessionChat.warning(
                "home archive failed session=\(target.sessionId) error=\(error.localizedDescription)"
            )
            state.actionInFlight = false
            clear()
            onLifecycleError?(Self.archiveError)
        }
    }

    func chooseDelete() {
        guard state.target != nil else { return }
        state.sheetVisible = false
        state.deleteConfirmVisible = true
    }

    func dismissDeleteConfirm() {
        guard !state.actionInFlight else { return }
        state.deleteConfirmVisible = false
        clear()
    }

    func confirmDelete() async {
        guard let target = state.target, !state.actionInFlight else { return }
        state.deleteConfirmVisible = false
        state.actionInFlight = true
        OpenCodeLog.sessionChat.info("home delete start session=\(target.sessionId)")
        do {
            let deleted = try await sessionRepository.deleteSession(id: target.sessionId, directory: target.worktree)
            state.actionInFlight = false
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
            state.actionInFlight = false
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
        guard !Task.isCancelled, state.target == target, state.forkDialog.isVisible else { return }
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
            state.forkDialog.blockedMessage = Self.nothingToForkError
        }
    }

    private var hasOpenDialog: Bool {
        state.renameDialog.isVisible
            || state.forkDialog.isVisible
            || state.forkResult.isVisible
            || state.deleteConfirmVisible
            || state.actionInFlight
    }

    private func clear() {
        forkCheckTask?.cancel()
        forkCheckTask = nil
        state.target = nil
        state.sheetVisible = false
        state.canArchive = true
    }

    /// Server and system text stays verbatim; the app's own fallbacks are keyed.
    private func mutationErrorMessage(_ error: Error) -> OpenCodeText {
        guard let sessionError = error as? SessionError else { return .verbatim(error.localizedDescription) }
        switch sessionError {
        case let .http(_, message):
            return OpenCodeText.verbatim(ifPresent: message) ?? "home.session_error.request_failed"
        case .transport:
            return "connection.error.unreachable"
        case .decoding:
            return "home.session_error.unreadable_response"
        }
    }

    private static let emptyTitleError: OpenCodeText = "home.session_error.empty_title"
    private static let nothingToForkError: OpenCodeText = "home.session_error.nothing_to_fork"
    private static var archiveError: String {
        String(localized: "home.session_error.archive_failed", defaultValue: "Couldn't archive session.")
    }
    private static var deleteError: String {
        String(localized: "home.session_error.delete_failed", defaultValue: "Couldn't delete session.")
    }
}
