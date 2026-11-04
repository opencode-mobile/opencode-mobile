import Foundation

public struct PathDTO: Decodable, Sendable {
    public let home: String
    public let state: String?
    public let config: String?
    public let worktree: String
    public let directory: String

    public init(
        home: String,
        state: String?,
        config: String?,
        worktree: String,
        directory: String
    ) {
        self.home = home
        self.state = state
        self.config = config
        self.worktree = worktree
        self.directory = directory
    }
}

public struct FileNodeDTO: Decodable, Sendable {
    public let name: String
    public let path: String
    public let absolute: String
    public let type: String
    public let ignored: Bool?

    public init(
        name: String,
        path: String,
        absolute: String,
        type: String,
        ignored: Bool?
    ) {
        self.name = name
        self.path = path
        self.absolute = absolute
        self.type = type
        self.ignored = ignored
    }
}

public nonisolated struct V2FsEntryDTO: Decodable, Sendable {
    public let path: String
    public let type: String

    public init(
        path: String,
        type: String
    ) {
        self.path = path
        self.type = type
    }
}

/// v1 `GET /file/content` body. Text is trimmed server-side; binary content is base64.
public nonisolated struct FileContentDTO: Decodable, Sendable {
    public let type: String
    public let content: String
    public let encoding: String?
    public let mimeType: String?

    public init(
        type: String,
        content: String,
        encoding: String?,
        mimeType: String?
    ) {
        self.type = type
        self.content = content
        self.encoding = encoding
        self.mimeType = mimeType
    }
}
