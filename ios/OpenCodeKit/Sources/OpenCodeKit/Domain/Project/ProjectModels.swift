import Foundation

public struct Project: Sendable, Equatable {
    public let id: String
    public let worktree: String
    public let displayName: String
    /// Last activity in epoch ms (v2 `time.active`, else `time.updated`), when the server sent it.
    public var activityAtEpochMs: Int64? = nil
    /// True when the server tracks the folder with a version control system (`vcs`, e.g. git).
    public var isRepository = false
    /// True when the server archived the project because its directories are gone (v2 `time.archived`).
    public var isArchived = false
    /// User-set project name, when the server sent one.
    public var name: String? = nil
    /// Main checkout of the project; `worktree` when the server did not say otherwise.
    public var canonical: String? = nil

    public var canonicalWorktree: String {
        if let canonical, !canonical.isEmpty { return canonical }
        return worktree
    }

    public init(
        id: String,
        worktree: String,
        displayName: String,
        activityAtEpochMs: Int64? = nil,
        isRepository: Bool = false,
        isArchived: Bool = false,
        name: String? = nil,
        canonical: String? = nil
    ) {
        self.id = id
        self.worktree = worktree
        self.displayName = displayName
        self.activityAtEpochMs = activityAtEpochMs
        self.isRepository = isRepository
        self.isArchived = isArchived
        self.name = name
        self.canonical = canonical
    }
}

public enum ServerProbeError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case unhealthy(version: String?)
}

extension ServerProbeError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .transport:
            return "Unable to reach the server"
        case let .http(statusCode, _):
            if statusCode == 401 || statusCode == 403 {
                return "Authentication failed"
            }
            return "Unable to reach the server"
        case .unhealthy:
            return "Server reported unhealthy"
        }
    }
}
