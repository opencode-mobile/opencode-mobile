import Foundation

/// New worktree text. App text is keyed (`worktree.*`, same keys on Android); the
/// repository's own error text stays English and is shown inside "Couldn't create the
/// worktree: …".
nonisolated enum WorktreeCreateCopy {
    static var newWorktreeRow: LocalizedStringResource { LocalizedStringResource("worktree.new_row", defaultValue: "New worktree\u{2026}") }
    static var runInRowSubtitle: LocalizedStringResource {
        LocalizedStringResource("worktree.run_in_subtitle", defaultValue: "Create a checkout and start there")
    }
    static var moveRowSubtitle: LocalizedStringResource {
        LocalizedStringResource("worktree.move_subtitle", defaultValue: "Create a checkout and move there")
    }
    static var needsV2: LocalizedStringResource { LocalizedStringResource("worktree.needs_v2", defaultValue: "Needs an opencode v2 server") }
    static var turnOnWorktrees: LocalizedStringResource {
        LocalizedStringResource("worktree.turn_on", defaultValue: "Turn on Worktrees in Settings")
    }
    static var notGit: LocalizedStringResource { LocalizedStringResource("worktree.not_git", defaultValue: "Needs a git repository") }
    static var noCommit: LocalizedStringResource {
        LocalizedStringResource("worktree.no_commit", defaultValue: "Needs a git repository with a commit")
    }
    static var unsupported: LocalizedStringResource {
        LocalizedStringResource("worktree.unsupported", defaultValue: "This server can't create worktrees")
    }
    static var formTitle: LocalizedStringResource { LocalizedStringResource("worktree.form_title", defaultValue: "New worktree") }
    static var nameLabel: LocalizedStringResource { LocalizedStringResource("worktree.name", defaultValue: "Name") }
    static var branchLabel: LocalizedStringResource { LocalizedStringResource("worktree.branch", defaultValue: "Branch") }
    static var baseLabel: LocalizedStringResource { LocalizedStringResource("worktree.base", defaultValue: "Create from") }
    static var v1BranchNote: LocalizedStringResource {
        LocalizedStringResource("worktree.v1_branch_note", defaultValue: "opencode v1 names it after the worktree")
    }
    static var v2NoBranch: LocalizedStringResource { LocalizedStringResource("worktree.v2_no_branch", defaultValue: "No branch (detached)") }
    static var v2BranchNote: LocalizedStringResource {
        LocalizedStringResource("worktree.v2_branch_note", defaultValue: "opencode v2 checkouts have no branch")
    }
    static var currentCheckout: LocalizedStringResource {
        LocalizedStringResource("worktree.current_checkout", defaultValue: "Current checkout")
    }
    static var v1BaseNote: LocalizedStringResource {
        LocalizedStringResource("worktree.v1_base_note", defaultValue: "opencode v1 uses the current checkout")
    }
    static var basePickHint: LocalizedStringResource { LocalizedStringResource("worktree.base_pick_hint", defaultValue: "Tap to pick a branch") }
    static var baseLoading: LocalizedStringResource { LocalizedStringResource("worktree.base_loading", defaultValue: "Loading branches\u{2026}") }
    static var baseFailed: LocalizedStringResource { LocalizedStringResource("worktree.base_failed", defaultValue: "Couldn't load branches") }
    static var nameEmpty: LocalizedStringResource { LocalizedStringResource("worktree.name_empty", defaultValue: "Enter a name") }
    static var nameChars: LocalizedStringResource {
        LocalizedStringResource("worktree.name_chars", defaultValue: "Use letters, numbers, dots, dashes or underscores")
    }
    static var submitMove: LocalizedStringResource { LocalizedStringResource("worktree.submit_move", defaultValue: "Create and move") }
    static var submitting: LocalizedStringResource { LocalizedStringResource("worktree.submitting", defaultValue: "Creating\u{2026}") }

    static func nameLong(_ maxLength: Int) -> LocalizedStringResource {
        LocalizedStringResource("worktree.name_long", defaultValue: "Use at most \(maxLength) characters")
    }

    /// The source checkout's branch marked current: `<branch> (current)`. The branch stays an argument.
    static func currentBranch(_ branch: String) -> LocalizedStringResource {
        LocalizedStringResource("worktree.current_branch", defaultValue: "\(branch) (current)")
    }

    /// The server's message stays an argument, never looked up.
    static func failed(_ message: String) -> LocalizedStringResource {
        LocalizedStringResource("worktree.create_failed", defaultValue: "Couldn't create the worktree: \(message)")
    }

    // Repository errors (domain text, shown verbatim inside `failed(_:)`).
    static let noCommitError = "Needs a git repository with a commit"
    static let unsupportedError = "This server can't create worktrees"

    static func requestFailed(_ statusCode: Int) -> String {
        "Request failed (HTTP \(statusCode))"
    }

    /// v1 branch shown for a name: `opencode/<name>`, `opencode/…` while empty. A branch name, not app text.
    static func v1Branch(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return "opencode/" + (trimmed.isEmpty ? "\u{2026}" : trimmed)
    }
}

/// Worktree name rules and the prefilled random name.
nonisolated enum WorktreeNameRules {
    static let maxLength = 64
    static let adjectives = [
        "brave", "calm", "eager", "fuzzy", "gentle", "happy", "jolly", "lucky",
        "mighty", "nimble", "proud", "quiet", "swift", "tidy", "witty", "zesty",
    ]
    static let nouns = [
        "otter", "falcon", "maple", "comet", "river", "panda", "cedar", "harbor",
        "lynx", "meadow", "pebble", "quartz", "robin", "sparrow", "tulip", "willow",
    ]

    /// Why `name` cannot be used, checked on the trimmed name; nil when it can.
    static func error(for name: String) -> LocalizedStringResource? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return WorktreeCreateCopy.nameEmpty }
        if trimmed.range(of: "^[A-Za-z0-9][A-Za-z0-9._-]*$", options: .regularExpression) == nil {
            return WorktreeCreateCopy.nameChars
        }
        if trimmed.count > maxLength { return WorktreeCreateCopy.nameLong(maxLength) }
        return nil
    }

    /// `<adjective>-<noun>`. `randomIndex(n)` returns a value in `0..<n`.
    static func randomName(_ randomIndex: (Int) -> Int) -> String {
        let adjective = adjectives[clamped(randomIndex(adjectives.count), adjectives.count)]
        let noun = nouns[clamped(randomIndex(nouns.count), nouns.count)]
        return "\(adjective)-\(noun)"
    }

    private static func clamped(_ index: Int, _ count: Int) -> Int {
        min(max(index, 0), count - 1)
    }
}

/// Whether the New worktree row can be used, and why not (the first failing check).
nonisolated struct NewWorktreeAvailability: Equatable, Sendable {
    let enabled: Bool
    let subtitle: LocalizedStringResource?

    /// Run in sheet (Home New session).
    static func runIn(
        worktreesEnabled: Bool,
        isRepository: Bool,
        projectID: String?,
        serverCanCreate: Bool
    ) -> NewWorktreeAvailability {
        if !worktreesEnabled { return disabled(WorktreeCreateCopy.turnOnWorktrees) }
        return common(
            isRepository: isRepository,
            projectID: projectID,
            serverCanCreate: serverCanCreate,
            enabledSubtitle: WorktreeCreateCopy.runInRowSubtitle
        )
    }

    /// Move sheet (Session Chat).
    static func move(
        supportsMove: Bool,
        worktreesEnabled: Bool,
        sessionCreated: Bool,
        isRepository: Bool,
        projectID: String?,
        serverCanCreate: Bool
    ) -> NewWorktreeAvailability {
        if !supportsMove { return disabled(WorktreeCreateCopy.needsV2) }
        if !worktreesEnabled { return disabled(WorktreeCreateCopy.turnOnWorktrees) }
        if !sessionCreated { return NewWorktreeAvailability(enabled: false, subtitle: nil) }
        return common(
            isRepository: isRepository,
            projectID: projectID,
            serverCanCreate: serverCanCreate,
            enabledSubtitle: WorktreeCreateCopy.moveRowSubtitle
        )
    }

    private static func common(
        isRepository: Bool,
        projectID: String?,
        serverCanCreate: Bool,
        enabledSubtitle: LocalizedStringResource
    ) -> NewWorktreeAvailability {
        if !isRepository { return disabled(WorktreeCreateCopy.notGit) }
        let id = projectID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if id.isEmpty || id == HomeProjectGrouping.globalProjectID {
            return disabled(WorktreeCreateCopy.noCommit)
        }
        if !serverCanCreate { return disabled(WorktreeCreateCopy.unsupported) }
        return NewWorktreeAvailability(enabled: true, subtitle: enabledSubtitle)
    }

    private static func disabled(_ subtitle: LocalizedStringResource) -> NewWorktreeAvailability {
        NewWorktreeAvailability(enabled: false, subtitle: subtitle)
    }
}
