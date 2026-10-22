import Foundation

/// Session Chat ⋯ "Move to worktree…": when it is offered and what its sheet lists.
enum SessionChatMoveToWorktree {
    static let menuTitle = "Move to worktree\u{2026}"
    static let sheetTitle = "Move to worktree"
    static let needsV2 = "Needs an opencode v2 server"
    static let turnOnWorktrees = "Turn on Worktrees in Settings"
    static let noOtherWorktrees = "This project has no other worktrees"

    static func movesAfterTurn(_ worktreeTitle: String) -> String {
        "Moves to \(worktreeTitle) after this turn."
    }

    static func failure(_ message: String) -> String {
        "Couldn't move the session: \(message)"
    }

    /// The server's own words for a failed move. A 400 is `InvalidRequestError`
    /// (`{"_tag":"InvalidRequestError","message":"…"}`), which the shared mapping keeps as the
    /// raw body.
    static func serverMessage(_ error: Error) -> String {
        if case let .http(_, body)? = error as? SessionError, let body {
            let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("{") {
                return ServerErrorMessage.bodyMessage(trimmed) ?? "Request failed"
            }
            if !trimmed.isEmpty { return trimmed }
        }
        return error.localizedDescription
    }

    struct Availability: Equatable {
        let enabled: Bool
        /// Why it is disabled; nil while enabled, or while the session is still being created.
        let subtitle: String?

        static let available = Availability(enabled: true, subtitle: nil)
    }

    /// Checks in order (the first failing one explains): a v2 server, the Worktrees setting, a
    /// created session, then somewhere to move: another worktree, or a New worktree row that can
    /// create one.
    static func availability(
        supportsMove: Bool,
        worktreesEnabled: Bool,
        sessionCreated: Bool,
        project: HomeProject?,
        newWorktree: NewWorktreeAvailability
    ) -> Availability {
        if !supportsMove { return Availability(enabled: false, subtitle: needsV2) }
        if !worktreesEnabled { return Availability(enabled: false, subtitle: turnOnWorktrees) }
        if !sessionCreated { return Availability(enabled: false, subtitle: nil) }
        if project?.hasManyWorktrees == true || newWorktree.enabled { return .available }
        return Availability(enabled: false, subtitle: noOtherWorktrees)
    }

    /// The Move sheet's New worktree row for the session's project.
    static func newWorktreeAvailability(
        supportsMove: Bool,
        worktreesEnabled: Bool,
        sessionCreated: Bool,
        project: HomeProject?,
        serverCanCreate: Bool
    ) -> NewWorktreeAvailability {
        NewWorktreeAvailability.move(
            supportsMove: supportsMove,
            worktreesEnabled: worktreesEnabled,
            sessionCreated: sessionCreated,
            isRepository: project?.isRepository ?? false,
            projectID: project?.projectID,
            serverCanCreate: serverCanCreate
        )
    }

    /// The Home project that lists `directory` as one of its worktrees.
    static func project(containing directory: String, in projects: [HomeProject]) -> HomeProject? {
        projects.first { $0.worktree(at: directory) != nil }
    }

    struct Option: Identifiable, Equatable {
        var id: String { directory }
        let directory: String
        let title: String
        let subtitle: String
        /// Where the session runs now: checked and not tappable.
        let isCurrent: Bool
    }

    /// The project's worktrees in Home order (main checkout first).
    static func options(project: HomeProject, currentDirectory: String) -> [Option] {
        let current = ServerPathPolicy.canonical(currentDirectory)
        return project.worktrees.map { worktree in
            Option(
                directory: worktree.directory,
                title: worktree.title,
                subtitle: worktree.subtitle,
                isCurrent: worktree.directory == current
            )
        }
    }
}
