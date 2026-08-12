import Foundation

protocol FilesystemRepository: Sendable {
    func getPaths() async throws -> ServerPaths
    func listDirectory(path: String) async throws -> [DirectoryNode]
}

struct DefaultFilesystemRepository: FilesystemRepository, Sendable {
    private let api: FilesystemAPIClient
    private let adapter: FilesystemDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?
    private let projectRepository: (any ProjectRepository)?

    init(
        api: FilesystemAPIClient,
        adapter: FilesystemDomainAdapter = FilesystemDomainAdapter(),
        activeConnectionManager: ActiveConnectionManager? = nil,
        projectRepository: (any ProjectRepository)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
        self.projectRepository = projectRepository
    }

    private func isV2() async -> Bool {
        if let protocolValue = activeConnectionManager?.serverProtocol {
            return protocolValue == .v2
        }
        guard let projectRepository else { return false }
        _ = try? await projectRepository.probeServer()
        return activeConnectionManager?.serverProtocol == .v2
    }

    func getPaths() async throws -> ServerPaths {
        if await isV2() {
            return adapter.serverPaths(from: try await api.getLocationV2())
        }
        return adapter.serverPaths(from: try await api.getPaths())
    }

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        let nodes: [DirectoryNode]
        if await isV2() {
            nodes = try await api.listFilesV2(absoluteDirectory: path).map {
                adapter.directoryNode(from: $0, listingDirectory: path)
            }
        } else {
            nodes = try await api.listFiles(absoluteDirectory: path).map(adapter.directoryNode(from:))
        }
        return DirectoryListingPolicy.prepare(nodes)
    }
}
