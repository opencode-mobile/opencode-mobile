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

    /// `base` is the listing envelope's location directory. Entry paths are relative to it (or
    /// absolute across drives), in the server's separators: `src\` on Windows, `src/` elsewhere.
    func directoryNode(from dto: V2FsEntryDTO, base: String) -> DirectoryNode {
        let absolutePath = ServerPathPolicy.resolve(base: base, child: dto.path)
        return DirectoryNode(
            name: ServerPathPolicy.name(absolutePath),
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
}
