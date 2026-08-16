import Foundation

struct FilesystemDomainAdapter: Sendable {
    func serverPaths(from dto: PathDTO) -> ServerPaths {
        ServerPaths(home: dto.home, directory: dto.directory, worktree: dto.worktree)
    }

    func serverPaths(from dto: V2LocationInfoDTO) -> ServerPaths {
        let directory = dto.directory
        let worktree = dto.project?.directory ?? directory
        return ServerPaths(home: directory, directory: directory, worktree: worktree)
    }

    func directoryNode(from dto: FileNodeDTO) -> DirectoryNode {
        DirectoryNode(
            name: dto.name,
            absolutePath: dto.absolute,
            isDirectory: dto.type == "directory"
        )
    }

    func directoryNode(from dto: V2FsEntryDTO, listingDirectory: String) -> DirectoryNode {
        let absolutePath = Self.absolutePath(dto.path, listingDirectory: listingDirectory)
        return DirectoryNode(
            name: Self.name(from: absolutePath, fallback: dto.path),
            absolutePath: absolutePath,
            isDirectory: dto.type == "directory"
        )
    }

    private static func absolutePath(_ path: String, listingDirectory: String) -> String {
        let normalized = stripTrailingSlashes(path)
        if normalized.hasPrefix("/") { return normalized }
        let relative = normalized.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let base = stripTrailingSlashes(listingDirectory)
        if relative.isEmpty || relative == "." { return base }
        if base == "/" { return "/\(relative)" }
        return "\(base)/\(relative)"
    }

    private static func name(from absolutePath: String, fallback: String) -> String {
        let name = URL(fileURLWithPath: absolutePath).lastPathComponent
        if name.isEmpty {
            return URL(fileURLWithPath: fallback).lastPathComponent
        }
        return name
    }

    private static func stripTrailingSlashes(_ path: String) -> String {
        if path == "/" { return path }
        var stripped = path
        while stripped.count > 1, stripped.hasSuffix("/") {
            stripped.removeLast()
        }
        return stripped.isEmpty ? "/" : stripped
    }
}
