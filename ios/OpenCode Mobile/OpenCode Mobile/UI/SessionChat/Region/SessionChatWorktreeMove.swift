import Foundation

struct SessionChatWorktreeMoveRegionState: Equatable {
    var moveSheetVisible = false
    var moveInFlight = false
    var moveErrorMessage: String?
    var moveNotice: String?
    /// Where `session.moved` put the open session; navigation rebuilds the screen there.
    var movedDirectory: String?
}

/// Session Chat ⋯ → Move to worktree (#785): the sheet, its New worktree form, the move
/// request with its notice or error, and `session.moved` for the open session. Capability
/// gates and analytics stay with the Screen Model.
@MainActor
final class SessionChatWorktreeMove {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatWorktreeMoveRegionState()
    /// New worktree form of the Move sheet.
    let form: WorktreeCreateModel

    private var sessionID: String
    private var worktree: String
    private let sessionRepository: any SessionRepository
    private let worktreeRepository: any WorktreeRepository
    private let foregroundEventStream: any ForegroundEventStream
    private let serverProtocol: () -> ServerProtocol?
    private var sessionMoveTask: Task<Void, Never>?

    init(
        sessionID: String,
        worktree: String,
        sessionRepository: any SessionRepository,
        worktreeRepository: any WorktreeRepository,
        foregroundEventStream: any ForegroundEventStream,
        serverProtocol: @escaping () -> ServerProtocol?
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.worktreeRepository = worktreeRepository
        self.foregroundEventStream = foregroundEventStream
        self.serverProtocol = serverProtocol
        self.form = WorktreeCreateModel(repository: worktreeRepository)
    }

    func rebind(sessionID: String) {
        self.sessionID = sessionID
    }

    func retarget(worktree: String) {
        self.worktree = worktree
    }

    /// False once a v1 server answered 404 for its worktree routes.
    var serverCanCreateWorktrees: Bool {
        worktreeRepository.canCreateWorktrees
    }

    func openSheet() {
        form.dismiss()
        state.moveSheetVisible = true
        notifyChange()
    }

    func dismissSheet() {
        form.dismiss()
        guard state.moveSheetVisible else { return }
        state.moveSheetVisible = false
        notifyChange()
    }

    /// Switches the Move sheet to the New worktree form, creating from the session's checkout.
    func openNewWorktreeForm(projectID: String?, sourceDirectory: String, sourceBranch: String?) {
        guard state.moveSheetVisible, !state.moveInFlight else { return }
        form.open(
            origin: .move(sessionID: sessionID),
            serverProtocol: serverProtocol() ?? .v1,
            projectID: projectID,
            sourceDirectory: sourceDirectory,
            sourceBranch: sourceBranch
        )
    }

    /// Creates the worktree, lets Home refresh its checkouts and closes the sheet. Returns the
    /// worktree to move to; nil keeps the form (a failed create shows its error there).
    func submitNewWorktree(
        refreshInventory: @MainActor (CreatedWorktree) async -> Void
    ) async -> CreatedWorktree? {
        guard case .move? = form.state?.origin else { return nil }
        guard let created = await form.submit() else { return nil }
        await refreshInventory(created)
        form.dismiss()
        state.moveSheetVisible = false
        notifyChange()
        return created
    }

    /// `POST /api/session/{id}/move`. The server moves an idle session at once and a busy one
    /// when its turn ends; `session.moved` then rebinds the screen to `directory`. `isBusy` is
    /// read once the sheet shows the move in flight.
    func moveSession(to directory: String, worktreeTitle: String, isBusy: () -> Bool) async {
        guard !state.moveInFlight else { return }
        state.moveInFlight = true
        state.moveErrorMessage = nil
        state.moveNotice = nil
        notifyChange()
        let wasBusy = isBusy()
        do {
            try await sessionRepository.moveSession(id: sessionID, directory: directory)
            if wasBusy {
                state.moveNotice = SessionChatMoveToWorktree.movesAfterTurn(worktreeTitle)
            }
        } catch {
            state.moveErrorMessage = SessionChatMoveToWorktree.failure(SessionChatMoveToWorktree.serverMessage(error))
        }
        state.moveInFlight = false
        state.moveSheetVisible = false
        notifyChange()
    }

    /// The snackbar showing the notice or error was dismissed.
    func clearMessages() {
        state.moveErrorMessage = nil
        state.moveNotice = nil
    }

    func startObserving() {
        sessionMoveTask?.cancel()
        sessionMoveTask = Task { [weak self] in
            guard let self else { return }
            for await event in self.foregroundEventStream.events() {
                guard let moved = event.sessionMoved,
                      moved.sessionID == self.sessionID
                else { continue }
                self.applySessionMoved(to: moved.directory)
            }
        }
    }

    func stopObserving() {
        sessionMoveTask?.cancel()
        sessionMoveTask = nil
    }

    private func applySessionMoved(to directory: String) {
        let canonical = ServerPathPolicy.canonical(directory)
        guard canonical != ServerPathPolicy.canonical(worktree), canonical != state.movedDirectory else { return }
        OpenCodeLog.sessionChat.info("session moved session=\(self.sessionID) directory=\(canonical)")
        state.movedDirectory = canonical
        notifyChange()
    }

    private func notifyChange() {
        onChange?()
    }
}
