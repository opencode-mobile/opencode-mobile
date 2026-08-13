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

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        let nodes: [DirectoryNode]
        if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
            nodes = try await api.listFilesV2(absoluteDirectory: path).map {
                adapter.directoryNode(from: $0, listingDirectory: path)
            }
        } else {
            nodes = try await api.listFiles(absoluteDirectory: path).map(adapter.directoryNode(from:))
        }
        return DirectoryListingPolicy.prepare(nodes)
    }
}
