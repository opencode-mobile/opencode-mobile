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

    // MARK: File Browser

    /// v1 `file` node. The server reports `path` relative to the worktree.
    nonisolated func projectEntry(from dto: FileNodeDTO, listingDirectory: String) -> ProjectEntry {
        var relative = ProjectPaths.normalize(dto.path)
        if relative.isEmpty {
            relative = Self.join(listingDirectory, dto.name)
        }
        return ProjectEntry(
            name: dto.name.isEmpty ? ProjectPaths.name(of: relative) : dto.name,
            relativePath: relative,
            isDirectory: dto.type == "directory",
            isIgnored: dto.ignored == true
        )
    }

    /// v2 `fs/list` entry: path relative to the location; folders end with `/`.
    nonisolated func projectEntry(from dto: V2FsEntryDTO, listingDirectory: String) -> ProjectEntry {
        let isDirectory = dto.type == "directory" || dto.path.hasSuffix("/")
        var relative = ProjectPaths.normalize(dto.path)
        if !listingDirectory.isEmpty, !relative.hasPrefix(listingDirectory + "/") {
            relative = Self.join(listingDirectory, relative)
        }
        return ProjectEntry(
            name: ProjectPaths.name(of: relative),
            relativePath: relative,
            isDirectory: isDirectory
        )
    }

    /// Find result: a file path relative to the worktree.
    nonisolated func projectEntry(fromFoundPath path: String) -> ProjectEntry {
        let relative = ProjectPaths.normalize(path)
        return ProjectEntry(name: ProjectPaths.name(of: relative), relativePath: relative, isDirectory: false)
    }

    nonisolated private static func join(_ directory: String, _ name: String) -> String {
        directory.isEmpty ? name : "\(directory)/\(name)"
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
