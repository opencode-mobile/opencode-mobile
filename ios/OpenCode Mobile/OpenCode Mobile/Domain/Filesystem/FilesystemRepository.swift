import Foundation

protocol FilesystemRepository: Sendable {
    func getPaths() async throws -> ServerPaths
    func listDirectory(path: String) async throws -> [DirectoryNode]
}

struct DefaultFilesystemRepository: FilesystemRepository, Sendable {
    private let api: FilesystemAPIClient

    init(api: FilesystemAPIClient) {
        self.api = api
    }

    func getPaths() async throws -> ServerPaths {
        let dto = try await api.getPaths()
        return ServerPaths(home: dto.home, directory: dto.directory, worktree: dto.worktree)
    }

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        let nodes = try await api.listFiles(path: path).map { dto in
            DirectoryNode(
                name: dto.name,
                absolutePath: dto.absolute,
                isDirectory: dto.type == "directory"
            )
        }
        return DirectoryListingPolicy.prepare(nodes)
    }
}
