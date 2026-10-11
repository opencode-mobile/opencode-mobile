import Foundation

/// One file or folder inside a project worktree, addressed by its path relative to the worktree.
nonisolated struct ProjectEntry: Equatable, Hashable, Sendable, Identifiable {
    var id: String { relativePath }
    let name: String
    let relativePath: String
    let isDirectory: Bool
    /// v1 only: matched by `.gitignore`. v2 never reports it.
    let isIgnored: Bool

    init(name: String, relativePath: String, isDirectory: Bool, isIgnored: Bool = false) {
        self.name = name
        self.relativePath = relativePath
        self.isDirectory = isDirectory
        self.isIgnored = isIgnored
    }

    var isHidden: Bool { name.hasPrefix(".") }

    /// Parent folder relative to the worktree; empty for root children.
    var parentPath: String { ProjectPaths.parent(of: relativePath) }
}

/// What the File Viewer can show for one file.
nonisolated enum ProjectFileContent: Equatable, Sendable {
    /// UTF-8 text. `isTruncated` when only the first `ProjectFileLimits.maxTextBytes` are kept.
    case text(String, isTruncated: Bool, totalBytes: Int?)
    case image(Data, mimeType: String)
    case binary(mimeType: String?, byteCount: Int?)
    case empty
}

nonisolated enum ProjectFilesError: Error, Equatable, Sendable {
    case noConnection
    case notFound
    case server(statusCode: Int)
    case invalidResponse
    case transport
}

nonisolated enum ProjectFileLimits {
    /// Text past this is cut (UTF-8 boundary, trailing partial line dropped).
    static let maxTextBytes = 1024 * 1024
    /// Images past this show the binary placeholder.
    static let maxImageBytes = 10 * 1024 * 1024
    /// v1 returns whole files as JSON; reading stops here.
    static let maxV1ResponseBytes = 16 * 1024 * 1024
    /// v2 text detection: a NUL byte in this prefix means binary.
    static let binarySniffBytes = 8 * 1024
    static let findLimit = 50

    static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp"]
}

nonisolated enum ProjectPaths {
    /// Strips `./`, leading and trailing slashes; `.` becomes the empty root path.
    static func normalize(_ path: String) -> String {
        var value = path
        while value.hasPrefix("./") { value.removeFirst(2) }
        value = value.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return value == "." ? "" : value
    }

    static func name(of relativePath: String) -> String {
        let normalized = normalize(relativePath)
        return normalized.split(separator: "/").last.map(String.init) ?? normalized
    }

    static func parent(of relativePath: String) -> String {
        let normalized = normalize(relativePath)
        guard let slash = normalized.lastIndex(of: "/") else { return "" }
        return String(normalized[..<slash])
    }

    static func fileExtension(of name: String) -> String {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return "" }
        return String(name[name.index(after: dot)...]).lowercased()
    }

    static func isImage(name: String) -> Bool {
        ProjectFileLimits.imageExtensions.contains(fileExtension(of: name))
    }

    static func isMarkdown(name: String) -> Bool {
        ["md", "markdown"].contains(fileExtension(of: name))
    }

    /// Last path component of the worktree, used as the Files subtitle.
    static func projectName(worktree: String) -> String {
        let trimmed = worktree.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return trimmed.split(separator: "/").last.map(String.init) ?? worktree
    }
}
