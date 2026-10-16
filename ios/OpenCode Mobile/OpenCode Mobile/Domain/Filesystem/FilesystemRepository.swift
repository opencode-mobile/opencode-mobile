import Foundation

protocol FilesystemRepository: Sendable {
    func getPaths() async throws -> ServerPaths
    func listDirectory(path: String) async throws -> [DirectoryNode]
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
    /// folder, hidden ones included, uncapped (see `DirectoryListingPolicy`).
    func listDirectory(path: String) async throws -> [DirectoryNode] {
        let root = ServerPathPolicy.root(path).isEmpty ? path : ServerPathPolicy.root(path)
        let nodes: [DirectoryNode]
        if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
            let envelope = try await api.listFilesV2(absoluteDirectory: path, root: root)
            let base = envelope.location?.directory ?? root
            nodes = envelope.data.map { adapter.directoryNode(from: $0, base: base) }
        } else {
            nodes = try await api.listFiles(absoluteDirectory: path, root: root).map(adapter.directoryNode(from:))
        }
        return DirectoryListingPolicy.directories(nodes)
    }
}
