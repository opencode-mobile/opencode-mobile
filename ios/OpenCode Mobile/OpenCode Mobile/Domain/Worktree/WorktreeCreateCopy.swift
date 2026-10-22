import Foundation

/// New worktree copy, identical on iOS and Android.
nonisolated enum WorktreeCreateCopy {
    static let newWorktreeRow = "New worktree\u{2026}"
    static let runInRowSubtitle = "Create a checkout and start there"
    static let moveRowSubtitle = "Create a checkout from this one and move there"
    static let needsV2 = "Needs an opencode v2 server"
    static let turnOnWorktrees = "Turn on Worktrees in Settings"
    static let noOtherWorktrees = "This project has no other worktrees"
    static let notGit = "Needs a git repository"
    static let noCommit = "Needs a git repository with a commit"
    static let unsupported = "This opencode server can't create worktrees"
    static let formTitle = "New worktree"
    static let nameLabel = "Name"
    static let branchLabel = "Branch"
    static let baseLabel = "Create from"
    static let back = "Back"
    static let v1BranchNote = "opencode v1 names the branch after the worktree"
    static let v2NoBranch = "No branch (detached)"
    static let v2BranchNote = "opencode v2 creates a detached checkout, shown by its folder name"
    static let currentCheckout = "Current checkout"
    static let v1BaseNote = "opencode v1 creates from the project's current checkout"
    static let basePickHint = "Tap to pick a branch"
    static let currentSuffix = " (current)"
    static let baseLoading = "Loading branches\u{2026}"
    static let baseFailed = "Couldn't load branches"
    static let nameEmpty = "Enter a name"
    static let nameChars = "Use letters, numbers, dots, dashes or underscores"
    static let nameLong = "Use at most 64 characters"
    static let submitRunIn = "Create and start"
    static let submitMove = "Create and move"
    static let submitting = "Creating\u{2026}"

    static func failed(_ message: String) -> String {
        "Couldn't create the worktree: \(message)"
    }

    static func requestFailed(_ statusCode: Int) -> String {
        "Request failed (HTTP \(statusCode))"
    }

    /// v1 branch shown for a name: `opencode/<name>`, `opencode/…` while empty.
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
    static func error(for name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return WorktreeCreateCopy.nameEmpty }
        if trimmed.range(of: "^[A-Za-z0-9][A-Za-z0-9._-]*$", options: .regularExpression) == nil {
            return WorktreeCreateCopy.nameChars
        }
        if trimmed.count > maxLength { return WorktreeCreateCopy.nameLong }
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
    let subtitle: String?

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
        enabledSubtitle: String
    ) -> NewWorktreeAvailability {
        if !isRepository { return disabled(WorktreeCreateCopy.notGit) }
        let id = projectID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if id.isEmpty || id == HomeProjectGrouping.globalProjectID {
            return disabled(WorktreeCreateCopy.noCommit)
        }
        if !serverCanCreate { return disabled(WorktreeCreateCopy.unsupported) }
        return NewWorktreeAvailability(enabled: true, subtitle: enabledSubtitle)
    }

    private static func disabled(_ subtitle: String) -> NewWorktreeAvailability {
        NewWorktreeAvailability(enabled: false, subtitle: subtitle)
    }
}
