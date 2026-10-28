import Foundation

/// Read-only worktree inventory for one added project.
enum WorktreeInventory: Equatable, Sendable {
    case unloaded
    case loaded(Set<String>)
    case failed

    static func loaded(_ directories: [String]) -> WorktreeInventory {
        .loaded(
            Set(
                directories
                    .map { ServerPathPolicy.canonical($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
                    .filter { !$0.isEmpty }
            )
        )
    }
}

/// Home badges a row only when that added project's inventory has loaded and the
/// session directory is a listed worktree other than the added path.
enum WorktreeMark {
    static func showsBadge(
        sessionDirectory: String?,
        addedPath: String,
        inventory: WorktreeInventory
    ) -> Bool {
        guard case let .loaded(directories) = inventory else { return false }
        guard let directory = sessionDirectory?.trimmingCharacters(in: .whitespacesAndNewlines),
              !directory.isEmpty
        else { return false }
        let canonicalDirectory = ServerPathPolicy.canonical(directory)
        if canonicalDirectory == ServerPathPolicy.canonical(addedPath.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return false
        }
        return directories.contains(canonicalDirectory)
    }

    static func checkoutName(_ directory: String) -> String {
        ServerPathPolicy.name(ServerPathPolicy.canonical(directory))
    }

    /// Session Chat names the checkout when the bound directory meets the Home badge
    /// predicate for any added project.
    static func checkoutNameIfMarked(
        boundDirectory: String,
        addedPaths: [String],
        inventoryFor: (String) -> WorktreeInventory
    ) -> String? {
        let marked = addedPaths.contains { added in
            showsBadge(sessionDirectory: boundDirectory, addedPath: added, inventory: inventoryFor(added))
        }
        return marked ? checkoutName(boundDirectory) : nil
    }
}
