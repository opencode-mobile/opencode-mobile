import Foundation

struct PathDTO: Decodable, Sendable {
    let home: String
    let state: String?
    let config: String?
    let worktree: String
    let directory: String
}

struct FileNodeDTO: Decodable, Sendable {
    let name: String
    let path: String
    let absolute: String
    let type: String
    let ignored: Bool?
}

nonisolated struct V2FsEntryDTO: Decodable, Sendable {
    let path: String
    let type: String
}

/// v1 `GET /file/content` body. Text is trimmed server-side; binary content is base64.
nonisolated struct FileContentDTO: Decodable, Sendable {
    let type: String
    let content: String
    let encoding: String?
    let mimeType: String?
}
