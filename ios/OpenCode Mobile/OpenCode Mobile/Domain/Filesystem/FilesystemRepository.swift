import Foundation

protocol FilesystemRepository: Sendable {
    func getPaths() async throws -> ServerPaths
    func listDirectory(path: String) async throws -> DirectoryListing
}

struct DefaultFilesystemRepository: FilesystemRepository, Sendable {
    private let api: FilesystemAPIClient
    private let adapter: FilesystemDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: FilesystemAPIClient,
        adapter: FilesystemDomainAdapter = FilesystemDomainAdapter(),
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    func getPaths() async throws -> ServerPaths {
        if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
            return adapter.serverPaths(from: try await api.getLocationV2())
        }
        return adapter.serverPaths(from: try await api.getPaths())
    }

    /// Lists an absolute server folder with its root (`/`, `C:\`, `\\server\share`) as the
    /// location, so browsing never makes the server open a project there. Returns every child
    /// folder, hidden ones included, uncapped (see `DirectoryListingPolicy`). Throws
    /// `FilesystemError.directoryNotFound` when the folder does not exist.
    func listDirectory(path: String) async throws -> DirectoryListing {
        do {
            return try await listing(path)
        } catch {
            if try await isMissing(path, after: error) { throw FilesystemError.directoryNotFound }
            throw error
        }
    }

    private func listing(_ path: String) async throws -> DirectoryListing {
        let root = ServerPathPolicy.root(path).isEmpty ? path : ServerPathPolicy.root(path)
        let nodes: [DirectoryNode]
        if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
            let envelope = try await api.listFilesV2(absoluteDirectory: path, root: root)
            let base = envelope.location?.directory ?? root
            nodes = envelope.data.map { adapter.directoryNode(from: $0, base: base) }
        } else {
            nodes = try await api.listFiles(absoluteDirectory: path, root: root).map(adapter.directoryNode(from:))
        }
        return DirectoryListing(
            folders: DirectoryListingPolicy.directories(nodes),
            isGitRepository: nodes.contains { $0.name == ".git" }
        )
    }

    /// v2 answers 404 for a missing folder. v1 answers 500 for a missing folder, a file, and a
    /// folder it cannot read alike, so the nearest ancestor that lists decides: the path is
    /// missing when that ancestor has no folder named like the next segment.
    private func isMissing(_ path: String, after error: Error) async throws -> Bool {
        if error is CancellationError || Task.isCancelled { return false }
        if case ProjectsAPIError.httpStatus(404, _) = error { return true }
        var child = ServerPathPolicy.canonical(path)
        for _ in 0 ..< Self.maxAncestorChecks {
            guard let parent = ServerPathPolicy.parentPath(child) else { return false }
            let siblings: DirectoryListing
            do {
                siblings = try await listing(parent)
            } catch ProjectsAPIError.httpStatus(404, _) {
                return true
            } catch {
                try Task.checkCancellation()
                child = parent
                continue
            }
            let name = ServerPathPolicy.name(child)
            let windows = ServerPathPolicy.isWindows(child)
            return !siblings.folders.contains { node in
                windows ? node.name.caseInsensitiveCompare(name) == .orderedSame : node.name == name
            }
        }
        return false
    }

    private static let maxAncestorChecks = 16
}
