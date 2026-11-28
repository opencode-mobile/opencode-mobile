import Foundation

/// Session Chat ⋯ "Move to worktree…": when it is offered and what its sheet lists.
enum SessionChatMoveToWorktree {
    static var menuTitle: LocalizedStringResource {
        LocalizedStringResource("session_chat.move_worktree.menu_title", defaultValue: "Move to worktree\u{2026}")
    }
    static var sheetTitle: LocalizedStringResource {
        LocalizedStringResource("session_chat.move_worktree.sheet_title", defaultValue: "Move to worktree")
    }
    static var needsV2: LocalizedStringResource { WorktreeCreateCopy.needsV2 }
    static var turnOnWorktrees: LocalizedStringResource { WorktreeCreateCopy.turnOnWorktrees }
    static var noOtherWorktrees: LocalizedStringResource {
        LocalizedStringResource("session_chat.move_worktree.no_other", defaultValue: "This project has no other worktrees")
    }

    /// Snackbar notice; the worktree title (branch or folder) stays an argument.
    static func movesAfterTurn(_ worktreeTitle: String) -> String {
        String(localized: LocalizedStringResource(
            "session_chat.move_worktree.moves_after_turn",
            defaultValue: "Moves to \(worktreeTitle) after this turn."
        ))
    }

    /// Snackbar error; `message` is the server's words.
    static func failure(_ message: String) -> String {
        String(localized: LocalizedStringResource(
            "session_chat.move_worktree.failed",
            defaultValue: "Couldn't move the session: \(message)"
        ))
    }

    /// The server's own words for a failed move. A 400 is `InvalidRequestError`
    /// (`{"_tag":"InvalidRequestError","message":"…"}`), which the shared mapping keeps as the
    /// raw body. A JSON body with no message reads the app's own "Request failed".
    static func serverMessage(_ error: Error) -> String {
        if case let .http(_, body)? = error as? SessionError, let body {
            let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("{") {
                return ServerErrorMessage.bodyMessage(trimmed)
                    ?? String(localized: "session_chat.activity.request_failed", defaultValue: "Request failed")
            }
            if !trimmed.isEmpty { return trimmed }
        }
        return error.localizedDescription
    }

    struct Availability: Equatable {
        let enabled: Bool
        /// Why it is disabled; nil while enabled, or while the session is still being created.
        let subtitle: LocalizedStringResource?

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

    /// The session a moved Session Chat rebinds to. A chat opened by New session keeps the
    /// optimistic placeholder as its route id; rebinding with it would create another session in
    /// the new directory, so the id the server gave the created session wins.
    static func reboundSessionID(routeID: String, currentID: String?) -> String {
        guard OptimisticSessionCreate.isPlaceholder(routeID),
              let currentID, !currentID.isEmpty, !OptimisticSessionCreate.isPlaceholder(currentID)
        else { return routeID }
        return currentID
    }

    /// The Home project that lists `directory` as one of its worktrees.
    static func project(containing directory: String, in projects: [HomeProject]) -> HomeProject? {
        projects.first { $0.worktree(at: directory) != nil }
    }

    struct Option: Identifiable, Equatable {
        var id: String { directory }
        let directory: String
        /// Branch or folder name: server content.
        let title: String
        /// "main checkout" (already localised), else the folder name.
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
