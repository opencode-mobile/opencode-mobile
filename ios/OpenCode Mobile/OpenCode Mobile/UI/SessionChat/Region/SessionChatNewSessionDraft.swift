import Foundation

struct SessionChatNewSessionDraftRegionState: Equatable {
    /// Set while nothing exists on the server yet.
    var draft: NewSessionDraft?
    var context = NewSessionDraftContext()
    var isCreating = false
    var sendError: String?
    var baseBranches: NewSessionBaseBranchesLoad = .idle
    /// The probed protocol: the connection's stored one can still be unset when the draft opens,
    /// which hid Starts from on v2.
    var serverProtocol: ServerProtocol?
}

/// The New session draft (#1004) while Session Chat shows a session that does not exist yet:
/// the per-profile store, the Run-session-in card's project / place / Starts-from choices, the
/// base branches, and the create step of the first send. The Screen Model binds the other
/// regions to the draft's checkout, promotes the screen and sends the prompt.
@MainActor
final class SessionChatNewSessionDraft {
    var onChange: (() -> Void)?
    /// Refreshes Home's checkouts of a directory after a draft's first send created a worktree.
    var refreshWorktreeInventory: (@MainActor @Sendable (String) async -> Void)?

    private(set) var state: SessionChatNewSessionDraftRegionState
    /// Connection Profile the draft belongs to; nil without an active profile.
    let profileKey: String?

    private let store: NewSessionDraftStore
    private let worktreeRepository: any WorktreeRepository
    private let sessionRepository: any SessionRepository
    private let modelSelectionResolver: NewSessionModelSelectionResolver
    private let currentProfile: () -> ConnectionProfile?
    private let currentProtocolOrProbe: @MainActor () async -> ServerProtocol
    private let serverProtocol: () -> ServerProtocol?
    /// What the composer holds, mirrored into the store.
    private let composerContent: () -> (composer: ComposerDraft, attachments: [Attachment])
    private let composerModelSelection: () -> SessionModelSelection?
    private var baseBranchesTask: Task<Void, Never>?

    init(
        draft: NewSessionDraft?,
        profileKey: String?,
        store: NewSessionDraftStore,
        worktreeRepository: any WorktreeRepository,
        sessionRepository: any SessionRepository,
        modelSelectionResolver: NewSessionModelSelectionResolver,
        currentProfile: @escaping () -> ConnectionProfile?,
        currentProtocolOrProbe: @escaping @MainActor () async -> ServerProtocol,
        serverProtocol: @escaping () -> ServerProtocol?,
        composerContent: @escaping () -> (composer: ComposerDraft, attachments: [Attachment]),
        composerModelSelection: @escaping () -> SessionModelSelection?
    ) {
        self.state = SessionChatNewSessionDraftRegionState(draft: draft)
        self.profileKey = profileKey
        self.store = store
        self.worktreeRepository = worktreeRepository
        self.sessionRepository = sessionRepository
        self.modelSelectionResolver = modelSelectionResolver
        self.currentProfile = currentProfile
        self.currentProtocolOrProbe = currentProtocolOrProbe
        self.serverProtocol = serverProtocol
        self.composerContent = composerContent
        self.composerModelSelection = composerModelSelection
    }

    /// Nothing exists on the server yet.
    var isActive: Bool { state.draft != nil }

    var draft: NewSessionDraft? { state.draft }

    /// Probes the server protocol, which decides whether Starts from shows.
    func startObserving() {
        Task { [weak self] in
            guard let probe = self?.currentProtocolOrProbe else { return }
            let detected = await probe()
            guard let self, self.state.serverProtocol != detected else { return }
            self.state.serverProtocol = detected
            self.notifyChange()
        }
    }

    /// Mirrors the draft and the composer's content into the store; leaving keeps it.
    func stop() {
        save()
        baseBranchesTask?.cancel()
        baseBranchesTask = nil
    }

    /// Home's projects, the Worktrees setting and running counts, for the card and its sheets.
    func updateContext(_ context: NewSessionDraftContext) {
        guard state.context != context else { return }
        state.context = context
        guard var current = state.draft else { return }
        // A draft opened before Home knew its project picks the key up once it does.
        if current.projectKey == nil,
           let project = NewSessionDraftPresentation.project(of: current, in: context.projects)
        {
            current.projectKey = project.key
            state.draft = current
            save()
        }
        notifyChange()
    }

    /// Project sheet pick: another project starts on its main checkout. True when the draft
    /// moved, so the Screen Model applies its place.
    func selectProject(key: String) -> Bool {
        guard let current = state.draft, !state.isCreating,
              let project = state.context.projects.first(where: { $0.key == key })
        else { return false }
        guard project.key != NewSessionDraftPresentation.project(of: current, in: state.context.projects)?.key else { return false }
        state.draft = current.movingTo(projectKey: project.key, projectDirectory: project.mainCheckoutDirectory)
        state.baseBranches = .idle
        return true
    }

    /// A project added from the Project sheet's Add project… becomes the draft's project.
    func selectProject(addedPath path: String) -> Bool {
        let canonical = ServerPathPolicy.canonical(path)
        guard let project = state.context.projects.first(where: { $0.hasMember(canonical) || $0.worktree(at: canonical) != nil })
        else { return false }
        return selectProject(key: project.key)
    }

    /// Worktree sheet pick. Picking New worktree again keeps its Starts from branch. True when
    /// the place changed, so the Screen Model applies it.
    func selectPlace(_ place: NewSessionPlace) -> Bool {
        guard var current = state.draft, !state.isCreating else { return false }
        if place.isNewWorktree, current.place.isNewWorktree { return false }
        current.place = place
        if !place.isNewWorktree {
            current.createdWorktree = nil
        }
        state.draft = current
        return true
    }

    /// Starts from sheet pick; nil (or the main checkout's own branch) is the default.
    func selectBaseBranch(_ branch: String?) {
        guard var current = state.draft, !state.isCreating, current.place.isNewWorktree else { return }
        let mainBranch = NewSessionDraftPresentation.project(of: current, in: state.context.projects)?
            .worktree(at: current.projectDirectory)?.branch
        let trimmed = branch?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = (trimmed?.isEmpty ?? true) || trimmed == mainBranch ? nil : trimmed
        guard current.place != .newWorktree(baseBranch: normalized) else { return }
        current.place = .newWorktree(baseBranch: normalized)
        // A worktree made from another base no longer matches.
        current.createdWorktree = nil
        state.draft = current
        save()
        notifyChange()
    }

    /// The Starts from sheet opened: loads the v2 branches of the main checkout once.
    func loadBaseBranches() {
        guard let current = state.draft else { return }
        switch state.baseBranches {
        case .loading, .loaded: return
        case .idle, .failed: break
        }
        state.baseBranches = .loading
        notifyChange()
        let directory = current.projectDirectory
        let repository = worktreeRepository
        baseBranchesTask?.cancel()
        baseBranchesTask = Task { [weak self] in
            let result = try? await repository.baseBranches(directory: directory)
            guard let self, !Task.isCancelled, self.state.draft?.projectDirectory == directory else { return }
            self.state.baseBranches = result.map { .loaded($0) } ?? .failed
            self.notifyChange()
        }
    }

    /// Discard draft (⋯): forgets the draft and everything in it. False while it cannot be
    /// discarded; the Screen Model then resets the composer and leaves.
    func discard() -> Bool {
        guard isActive, !state.isCreating else { return false }
        if let profileKey {
            store.clear(profile: profileKey, discardingAttachments: false)
        }
        state.draft = nil
        state.sendError = nil
        return true
    }

    /// Mirrors the draft (with what the composer holds) into the per-profile store. Silent:
    /// the Screen Model's publish calls it.
    func save() {
        guard var current = state.draft, let profileKey else { return }
        let content = composerContent()
        current.composer = content.composer
        current.attachments = content.attachments
        current.modelSelection = composerModelSelection()
        state.draft = current
        store.save(current, forProfile: profileKey)
    }

    /// The first send is under way. Silent: the Screen Model publishes with the composer.
    func beginCreate() {
        state.isCreating = true
        state.sendError = nil
    }

    /// Creates the worktree (New worktree; reused after a failed attempt), then the session in
    /// that checkout, and seeds its model selection. On success the draft leaves the store and
    /// stops creating; the Screen Model promotes the screen. On failure creating stops and the
    /// error is rethrown for `showCreateError(_:)`.
    func create(
        currentModelSelection: () -> SessionModelSelection?
    ) async throws -> (directory: String, session: Session) {
        guard let current = state.draft else { throw CancellationError() }
        let directory: String
        let session: Session
        do {
            if case let .newWorktree(base) = current.place {
                if let created = current.createdWorktree {
                    directory = created.directory
                } else {
                    let project = NewSessionDraftPresentation.project(of: current, in: state.context.projects)
                    let created = try await worktreeRepository.create(
                        CreateWorktreeRequest(
                            projectID: project?.projectID,
                            sourceDirectory: current.projectDirectory,
                            name: nil,
                            baseRef: await currentProtocolOrProbe() == .v2 ? base : nil
                        )
                    )
                    OpenCodeLog.sessionChat.info("draft worktree created directory=\(created.directory)")
                    state.draft?.createdWorktree = created
                    save()
                    directory = created.directory
                    await refreshWorktreeInventory?(created.directory)
                }
            } else {
                directory = current.directory
            }
            session = try await sessionRepository.createSession(parentID: nil, title: nil, directory: directory)
        } catch {
            OpenCodeLog.sessionChat.warning("draft create failed error=\(error.localizedDescription)")
            state.isCreating = false
            throw error
        }
        OpenCodeLog.sessionChat.info("draft session created id=\(session.id)")
        if let profile = currentProfile() {
            let selection: SessionModelSelection? = if let picked = currentModelSelection() {
                picked
            } else {
                await modelSelectionResolver.resolve(profile: profile, worktree: directory)
            }
            if let selection {
                await modelSelectionResolver.seedSession(
                    profile: profile,
                    worktree: directory,
                    sessionID: session.id,
                    selection: selection
                )
            }
        }
        if let profileKey {
            // The composer now owns the text and attachments; the session row shows on Home.
            store.clear(profile: profileKey, discardingAttachments: false)
        }
        state.isCreating = false
        return (directory, session)
    }

    /// The create step failed: its error shows on the snackbar with Retry. Silent.
    func showCreateError(_ error: Error) {
        state.sendError = Self.createErrorMessage(error)
    }

    /// A send error the Screen Model found before creating. Silent.
    func showSendError(_ message: String) {
        state.sendError = message
    }

    func clearError() {
        state.sendError = nil
    }

    /// The first send created the session: drafting ends, so nothing writes the draft back.
    func end() {
        state.draft = nil
        baseBranchesTask?.cancel()
        baseBranchesTask = nil
    }

    /// The Run-session-in card; nil once the session exists.
    func uiState() -> SessionChatDraftUiState? {
        guard let draft = state.draft else { return nil }
        return NewSessionDraftPresentation.uiState(
            draft: draft,
            context: state.context,
            serverProtocol: state.serverProtocol ?? serverProtocol() ?? .v1,
            serverCanCreateWorktrees: worktreeRepository.canCreateWorktrees,
            isCreating: state.isCreating,
            baseBranches: state.baseBranches
        )
    }

    private static func createErrorMessage(_ error: Error) -> String {
        if let worktreeError = error as? WorktreeCreateError {
            return String(localized: WorktreeCreateCopy.failed(worktreeError.message))
        }
        return createSessionErrorMessage(error)
    }

    private static func createSessionErrorMessage(_ error: Error) -> String {
        guard let sessionError = error as? SessionError else {
            return error.localizedDescription
        }
        switch sessionError {
        case let .http(_, message):
            return message ?? "Unable to create session"
        case .transport:
            return "Unable to reach the server"
        case .decoding:
            return "Unable to read server response"
        }
    }

    private func notifyChange() {
        onChange?()
    }
}
