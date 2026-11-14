import Foundation

/// One file or folder inside a project worktree, addressed by its path relative to the worktree.
public nonisolated struct ProjectEntry: Equatable, Hashable, Sendable, Identifiable {
    public var id: String { relativePath }
    public let name: String
    public let relativePath: String
    public let isDirectory: Bool
    /// v1 only: matched by `.gitignore`. v2 never reports it.
    public let isIgnored: Bool

    public init(name: String, relativePath: String, isDirectory: Bool, isIgnored: Bool = false) {
        self.name = name
        self.relativePath = relativePath
        self.isDirectory = isDirectory
        self.isIgnored = isIgnored
    }

    public var isHidden: Bool { name.hasPrefix(".") }

    /// Parent folder relative to the worktree; empty for root children.
    public var parentPath: String { ProjectPaths.parent(of: relativePath) }
}

/// What the File Viewer can show for one file.
public nonisolated enum ProjectFileContent: Equatable, Sendable {
    /// UTF-8 text. `isTruncated` when only the first `ProjectFileLimits.maxTextBytes` are kept.
    case text(String, isTruncated: Bool, totalBytes: Int?)
    case image(Data, mimeType: String)
    case binary(mimeType: String?, byteCount: Int?)
    /// Past a read cap (v1 response or image). `byteCount` is the file size when known.
    case tooLarge(byteCount: Int?)
    case empty
}

public nonisolated enum ProjectFilesError: Error, Equatable, Sendable {
    case noConnection
    case notFound
    case server(statusCode: Int)
    case invalidResponse
    case transport
}

public nonisolated enum ProjectFileLimits {
    /// Text past this is cut (UTF-8 boundary, trailing partial line dropped).
    public static let maxTextBytes = 1024 * 1024
    /// Images past this show "Too large to preview".
    public static let maxImageBytes = 10 * 1024 * 1024
    /// v1 returns whole files as JSON; reading stops here and the file shows "Too large to preview".
    public static let maxV1ResponseBytes = 16 * 1024 * 1024
    /// v2 text detection: a NUL byte in this prefix means binary.
    public static let binarySniffBytes = 8 * 1024
    public static let findLimit = 50

    public static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp"]
}

public nonisolated enum ProjectPaths {
    /// Strips `./`, leading and trailing slashes; `.` becomes the empty root path.
    public static func normalize(_ path: String) -> String {
        var value = path
        while value.hasPrefix("./") { value.removeFirst(2) }
        value = value.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return value == "." ? "" : value
    }

    public static func name(of relativePath: String) -> String {
        let normalized = normalize(relativePath)
        return normalized.split(separator: "/").last.map(String.init) ?? normalized
    }

    public static func parent(of relativePath: String) -> String {
        let normalized = normalize(relativePath)
        guard let slash = normalized.lastIndex(of: "/") else { return "" }
        return String(normalized[..<slash])
    }

    public static func fileExtension(of name: String) -> String {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return "" }
        return String(name[name.index(after: dot)...]).lowercased()
    }

    public static func isImage(name: String) -> Bool {
        ProjectFileLimits.imageExtensions.contains(fileExtension(of: name))
    }

    public static func isMarkdown(name: String) -> Bool {
        ["md", "markdown"].contains(fileExtension(of: name))
    }

    /// `file://` URL for a project file on the connected server. The worktree is a server path,
    /// so this joins with `/` and does not touch the device filesystem.
    public static func fileURL(worktree: String, relativePath: String) -> String {
        var base = worktree.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        var relative = relativePath.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\", with: "/")
        while relative.hasPrefix("/") { relative.removeFirst() }
        while relative.hasSuffix("/") { relative.removeLast() }
        let absolute: String
        if base.isEmpty {
            absolute = "/\(relative)"
        } else if relative.isEmpty {
            absolute = base
        } else {
            absolute = "\(base)/\(relative)"
        }
        let encoded = absolute.split(separator: "/", omittingEmptySubsequences: false).map { segment in
            segment.isEmpty ? "" : percentEncodePathSegment(String(segment))
        }.joined(separator: "/")
        return "file://\(encoded)"
    }

    public static func filename(of relativePath: String) -> String {
        let trimmed = relativePath.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = trimmed.replacingOccurrences(of: "\\", with: "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let name = normalized.split(separator: "/").last.map(String.init) ?? normalized
        return name.isEmpty ? normalized : name
    }

    private static func percentEncodePathSegment(_ segment: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return segment.addingPercentEncoding(withAllowedCharacters: allowed) ?? segment
    }

    /// Last path component of the worktree, used as the Files subtitle.
    public static func projectName(worktree: String) -> String {
        let trimmed = worktree.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return trimmed.split(separator: "/").last.map(String.init) ?? worktree
    }
}
