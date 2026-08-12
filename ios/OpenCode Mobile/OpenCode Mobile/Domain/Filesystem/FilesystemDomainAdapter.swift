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
        if path.hasPrefix("/") { return path }
        let relative = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if relative.isEmpty || relative == "." { return listingDirectory }
        if listingDirectory == "/" { return "/\(relative)" }
        let base = listingDirectory.hasSuffix("/") ? String(listingDirectory.dropLast()) : listingDirectory
        return "\(base)/\(relative)"
    }

    private static func name(from absolutePath: String, fallback: String) -> String {
        let name = URL(fileURLWithPath: absolutePath).lastPathComponent
        if name.isEmpty {
            return URL(fileURLWithPath: fallback).lastPathComponent
        }
        return name
    }
}
