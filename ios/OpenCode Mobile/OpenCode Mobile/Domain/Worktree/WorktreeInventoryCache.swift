import Foundation

/// The added paths and the last worktree inventory each of them loaded, per server.
///
/// Home records what it loads; Session Chat reads it synchronously when it opens, so a worktree
/// session names its checkout on the first frame instead of after its own inventory fetch.
/// Session Chat records what it loads too, for the next open.
final class WorktreeInventoryCache: @unchecked Sendable {
    struct Snapshot: Equatable, Sendable {
        var addedPaths: [String] = []
        var inventories: [String: WorktreeInventory] = [:]

        func inventory(for addedPath: String) -> WorktreeInventory {
            inventories[addedPath] ?? .unloaded
        }
    }

    private struct Entry {
        var addedPaths: [String]?
        var inventories: [String: WorktreeInventory] = [:]
    }

    private let lock = NSLock()
    private var servers: [String: Entry] = [:]

    /// Nil until the added paths of `server` were recorded.
    func snapshot(server: String) -> Snapshot? {
        let key = Self.key(server)
        guard !key.isEmpty else { return nil }
        return lock.withLock {
            guard let entry = servers[key], let addedPaths = entry.addedPaths else { return nil }
            return Snapshot(addedPaths: addedPaths, inventories: entry.inventories)
        }
    }

    /// Added paths of `server`, in Home order. Inventories of paths no longer added are dropped.
    func setAddedPaths(_ paths: [String], server: String) {
        let key = Self.key(server)
        guard !key.isEmpty else { return }
        lock.withLock {
            var entry = servers[key] ?? Entry()
            entry.addedPaths = paths
            let kept = Set(paths)
            entry.inventories = entry.inventories.filter { kept.contains($0.key) }
            servers[key] = entry
        }
    }

    /// A failed load keeps the last inventory, as Home does.
    func record(_ inventory: WorktreeInventory, addedPath: String, server: String) {
        let key = Self.key(server)
        guard !key.isEmpty, inventory != .failed else { return }
        lock.withLock {
            var entry = servers[key] ?? Entry()
            entry.inventories[addedPath] = inventory
            servers[key] = entry
        }
    }

    func clear() {
        lock.withLock { servers.removeAll() }
    }

    private static func key(_ server: String) -> String {
        ConnectionProfileIdentity.normalizeBaseURL(server)
    }
}
