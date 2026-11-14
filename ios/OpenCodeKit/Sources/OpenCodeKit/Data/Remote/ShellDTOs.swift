import Foundation

/// v2 `ShellInfo` (`GET /api/shell`, `shell.created`). See `api/opencode-shells.openapi.yaml`.
public nonisolated struct ShellInfoDTO: Decodable, Sendable, Equatable {
    public let id: String
    /// `running` | `exited` | `timeout` | `killed`.
    public let status: String
    public let command: String?
    public let cwd: String?
    public let exit: Double?
    public let metadata: [String: AnyCodable]?
    public let time: ShellTimeDTO?

    public init(
        id: String,
        status: String,
        command: String?,
        cwd: String?,
        exit: Double?,
        metadata: [String: AnyCodable]?,
        time: ShellTimeDTO?
    ) {
        self.id = id
        self.status = status
        self.command = command
        self.cwd = cwd
        self.exit = exit
        self.metadata = metadata
        self.time = time
    }
}

public nonisolated struct ShellTimeDTO: Decodable, Sendable, Equatable {
    public let started: Double?
    public let completed: Double?

    public init(
        started: Double?,
        completed: Double?
    ) {
        self.started = started
        self.completed = completed
    }
}

/// One page of `GET /api/shell/{id}/output`; `cursor` and `size` are absolute byte offsets.
public nonisolated struct ShellOutputPageDTO: Decodable, Sendable, Equatable {
    public let output: String
    public let cursor: Int64
    public let size: Int64
    public let truncated: Bool?

    public init(
        output: String,
        cursor: Int64,
        size: Int64,
        truncated: Bool?
    ) {
        self.output = output
        self.cursor = cursor
        self.size = size
        self.truncated = truncated
    }
}
