import Foundation

/// v2 `ShellInfo` (`GET /api/shell`, `shell.created`). See `api/opencode-shells.openapi.yaml`.
nonisolated struct ShellInfoDTO: Decodable, Sendable, Equatable {
    let id: String
    /// `running` | `exited` | `timeout` | `killed`.
    let status: String
    let command: String?
    let cwd: String?
    let exit: Double?
    let metadata: [String: AnyCodable]?
    let time: ShellTimeDTO?
}

nonisolated struct ShellTimeDTO: Decodable, Sendable, Equatable {
    let started: Double?
    let completed: Double?
}

/// One page of `GET /api/shell/{id}/output`; `cursor` and `size` are absolute byte offsets.
nonisolated struct ShellOutputPageDTO: Decodable, Sendable, Equatable {
    let output: String
    let cursor: Int64
    let size: Int64
    let truncated: Bool?
}
