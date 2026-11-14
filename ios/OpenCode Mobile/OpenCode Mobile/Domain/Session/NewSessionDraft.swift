import Foundation

/// Where a new session will run (#1004). Nothing exists on the server until the first send.
enum NewSessionPlace: Equatable, Sendable {
    /// The project's main checkout.
    case local
    /// An existing worktree of the project.
    case worktree(directory: String, branch: String?)
    /// A worktree the first send creates. `baseBranch` is the v2 start ref; nil starts from the
    /// main checkout's HEAD (and v1 always does).
    case newWorktree(baseBranch: String?)

    var isLocal: Bool {
        if case .local = self { return true }
        return false
    }

    var isNewWorktree: Bool {
        if case .newWorktree = self { return true }
        return false
    }
}

/// The New session draft of one Connection Profile: what the user typed and chose before the
/// session exists. Kept in memory only (`NewSessionDraftStore`), so an app restart drops it.
struct NewSessionDraft: Equatable, Sendable {
    /// Home project key when known.
    var projectKey: String?
    /// The project's main checkout: where Local (and a New worktree's source) runs.
    var projectDirectory: String
    var place: NewSessionPlace
    var composer: ComposerDraft
    var attachments: [Attachment]
    /// Set only by an explicit model, agent or variant pick in the draft.
    var modelSelection: SessionModelSelection?
    /// A worktree an earlier first send created before it failed, so a retry reuses it.
    var createdWorktree: CreatedWorktree?

    init(
        projectKey: String?,
        projectDirectory: String,
        place: NewSessionPlace = .local,
        composer: ComposerDraft = ComposerDraft(text: ""),
        attachments: [Attachment] = [],
        modelSelection: SessionModelSelection? = nil,
        createdWorktree: CreatedWorktree? = nil
    ) {
        self.projectKey = projectKey
        self.projectDirectory = ServerPathPolicy.canonical(projectDirectory)
        self.place = place
        self.composer = composer
        self.attachments = attachments
        self.modelSelection = modelSelection
        self.createdWorktree = createdWorktree
    }

    /// Where the screen binds while drafting: the checkout itself, or the main checkout for a
    /// New worktree (same project, so commands, agents and models match).
    var directory: String {
        switch place {
        case .local, .newWorktree: projectDirectory
        case let .worktree(directory, _): directory
        }
    }

    /// Text or attachments. A place choice alone is not worth keeping.
    var hasContent: Bool {
        !composer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty
    }

    /// Another project starts on its main checkout and forgets a worktree made for the old one.
    func movingTo(projectKey: String?, projectDirectory: String) -> NewSessionDraft {
        var next = self
        next.projectKey = projectKey
        next.projectDirectory = ServerPathPolicy.canonical(projectDirectory)
        next.place = .local
        next.createdWorktree = nil
        return next
    }

    /// Same content in another place (and project).
    func placed(at start: NewSessionDraft) -> NewSessionDraft {
        var next = self
        next.projectKey = start.projectKey
        next.projectDirectory = start.projectDirectory
        next.place = start.place
        if !start.place.isNewWorktree { next.createdWorktree = nil }
        return next
    }
}

/// The in-memory New session drafts, one per Connection Profile. App-scoped; never written to
/// disk (per-session composer drafts are #469's `ComposerDraftStore`).
@MainActor
final class NewSessionDraftStore {
    private var drafts: [String: NewSessionDraft] = [:]

    init() {}

    /// Profiles saved without an id (old installs, tests) fall back to address and user.
    static func key(for profile: ConnectionProfile) -> String {
        let id = profile.id.trimmingCharacters(in: .whitespacesAndNewlines)
        return id.isEmpty ? profile.normalizedBaseURL + "|" + profile.username : id
    }

    func draft(forProfile key: String) -> NewSessionDraft? {
        drafts[key]
    }

    func save(_ draft: NewSessionDraft, forProfile key: String) {
        drafts[key] = draft
    }

    /// Drops the draft. `discardingAttachments` deletes the files its attachments were copied to;
    /// a successful send hands them to the composer instead.
    func clear(profile key: String, discardingAttachments: Bool) {
        guard let draft = drafts.removeValue(forKey: key) else { return }
        if discardingAttachments {
            draft.attachments.forEach { AttachmentLimits.discard($0.fileURL) }
        }
    }

    /// Clear All Data: every profile's draft and its attachment files.
    func clearAll() {
        let keys = Array(drafts.keys)
        keys.forEach { clear(profile: $0, discardingAttachments: true) }
    }

    /// The draft New session opens. One with text or attachments wins over `start`; one without
    /// is replaced (a place choice alone is not kept). `forcePlace` keeps the content but takes
    /// `start`'s project and place (Worktrees "New session here").
    @discardableResult
    func open(profile key: String, start: NewSessionDraft, forcePlace: Bool = false) -> NewSessionDraft {
        let opened: NewSessionDraft
        if let existing = drafts[key], existing.hasContent {
            opened = forcePlace ? existing.placed(at: start) : existing
        } else {
            opened = start
        }
        drafts[key] = opened
        return opened
    }
}

/// A session Home lists, for the starting-place rule.
struct NewSessionRecentSession: Equatable, Sendable {
    let projectKey: String
    let directory: String
    let updatedAtEpochMs: Int64?
}

/// Where a fresh draft starts.
enum NewSessionDraftStart {
    /// Home's New session: the filter's project (or that worktree when Home is filtered to one);
    /// in the All scope, the place of the most recent session. Nil without projects.
    static func fromHome(
        projects: [HomeProject],
        selectedFilterId: String,
        worktreesEnabled: Bool,
        recentSessions: [NewSessionRecentSession]
    ) -> NewSessionDraft? {
        guard !projects.isEmpty else { return nil }
        let scope = HomeScope.resolve(id: selectedFilterId, projects: projects, worktreesEnabled: worktreesEnabled)
        if worktreesEnabled, case let .worktree(projectKey, directory)? = scope,
           let project = projects.first(where: { $0.key == projectKey })
        {
            return draft(in: project, directory: directory, worktreesEnabled: true)
        }
        if let key = scope?.projectKey, let project = projects.first(where: { $0.key == key }) {
            return NewSessionDraft(projectKey: project.key, projectDirectory: project.mainCheckoutDirectory)
        }
        if projects.count == 1 {
            return NewSessionDraft(projectKey: projects[0].key, projectDirectory: projects[0].mainCheckoutDirectory)
        }
        let recent = recentSessions
            .filter { session in projects.contains { $0.key == session.projectKey } }
            .max { ($0.updatedAtEpochMs ?? .min) < ($1.updatedAtEpochMs ?? .min) }
        if let recent, let project = projects.first(where: { $0.key == recent.projectKey }) {
            return draft(in: project, directory: recent.directory, worktreesEnabled: worktreesEnabled)
        }
        return NewSessionDraft(projectKey: projects[0].key, projectDirectory: projects[0].mainCheckoutDirectory)
    }

    /// A draft that starts at `directory` (an open chat's checkout, `/new`, Worktrees "New session
    /// here"). Without a matching Home project it runs in `directory` itself.
    static func fromDirectory(
        _ directory: String,
        projects: [HomeProject],
        worktreesEnabled: Bool
    ) -> NewSessionDraft {
        let canonical = ServerPathPolicy.canonical(directory)
        guard let project = projects.first(where: { $0.worktree(at: canonical) != nil || $0.hasMember(canonical) }) else {
            return NewSessionDraft(projectKey: nil, projectDirectory: canonical)
        }
        return draft(in: project, directory: canonical, worktreesEnabled: worktreesEnabled)
    }

    /// Local for the main checkout (or while worktrees are off), else that worktree.
    private static func draft(in project: HomeProject, directory: String, worktreesEnabled: Bool) -> NewSessionDraft {
        let canonical = ServerPathPolicy.canonical(directory)
        let main = project.mainCheckoutDirectory
        guard worktreesEnabled, canonical != ServerPathPolicy.canonical(main),
              let worktree = project.worktree(at: canonical), !worktree.isMain
        else {
            return NewSessionDraft(projectKey: project.key, projectDirectory: main)
        }
        return NewSessionDraft(
            projectKey: project.key,
            projectDirectory: main,
            place: .worktree(directory: worktree.directory, branch: worktree.branch)
        )
    }
}
