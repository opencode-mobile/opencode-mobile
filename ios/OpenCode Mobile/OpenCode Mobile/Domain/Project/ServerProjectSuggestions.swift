import Foundation

/// The server's own project list as Add project suggestions: repositories only, no global project,
/// nothing archived, nothing already on home, nothing under a temp or hidden folder, one row per worktree (the most
/// recently active), newest first. Projects without an activity time sort last, in server order.
/// At most `limit` rows.
///
/// The server creates a project for every folder a request names, so agents and tools leave
/// plain-folder projects behind (scratch, upload, and evidence folders). Only repositories are
/// worth suggesting; Browse folders still adds any folder.
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
            guard project.id != globalProjectID, project.isRepository, !project.isArchived,
                  !worktree.isEmpty, worktree != "/", !added.contains(worktree), !isScratchLocation(worktree)
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

    /// System temp folders and any path with a hidden (dot) segment, such as `~/.t3/scratch`.
    static func isScratchLocation(_ path: String) -> Bool {
        let posix = path.replacingOccurrences(of: "\\", with: "/").lowercased()
        let segments = posix.split(separator: "/")
        if segments.contains(where: { $0.hasPrefix(".") }) { return true }
        if tempPrefixes.contains(where: { posix == $0 || posix.hasPrefix($0 + "/") }) { return true }
        return posix.contains("/appdata/local/temp/") || posix.hasSuffix("/appdata/local/temp")
    }

    private static let tempPrefixes = ["/tmp", "/private/tmp", "/var/folders", "/private/var/folders", "/var/tmp", "/private/var/tmp"]

    private static func isNewer(_ candidate: Int64?, than current: Int64?) -> Bool {
        guard let candidate else { return false }
        guard let current else { return true }
        return candidate > current
    }
}
