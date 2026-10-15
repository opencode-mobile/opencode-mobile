import Foundation

/// The server's own project list as Add project suggestions: no global project, nothing already
/// on home, one row per worktree (the most recently active), newest first. Projects without an
/// activity time sort last, in server order. At most `limit` rows.
nonisolated enum ServerProjectSuggestions {
    static let globalProjectID = "global"
    static let defaultLimit = 5

    static func suggestions(
        from projects: [Project],
        excluding addedWorktrees: [String],
        limit: Int = defaultLimit
    ) -> [Project] {
        let added = Set(addedWorktrees.map(ServerPathPolicy.canonical))
        var order: [String] = []
        var newestByWorktree: [String: Project] = [:]
        for project in projects {
            let worktree = ServerPathPolicy.canonical(project.worktree)
            guard project.id != globalProjectID, !worktree.isEmpty, worktree != "/",
                  !added.contains(worktree)
            else { continue }
            guard let current = newestByWorktree[worktree] else {
                order.append(worktree)
                newestByWorktree[worktree] = project
                continue
            }
            if isNewer(project.activityAtEpochMs, than: current.activityAtEpochMs) {
                newestByWorktree[worktree] = project
            }
        }
        return order.enumerated()
            .compactMap { index, worktree in newestByWorktree[worktree].map { (index, $0) } }
            .sorted { lhs, rhs in
                switch (lhs.1.activityAtEpochMs, rhs.1.activityAtEpochMs) {
                case let (left?, right?) where left != right:
                    return left > right
                case (.some, nil):
                    return true
                case (nil, .some):
                    return false
                default:
                    return lhs.0 < rhs.0
                }
            }
            .prefix(max(0, limit))
            .map(\.1)
    }

    private static func isNewer(_ candidate: Int64?, than current: Int64?) -> Bool {
        guard let candidate else { return false }
        guard let current else { return true }
        return candidate > current
    }
}
