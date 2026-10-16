import Foundation

struct Project: Sendable, Equatable {
    let id: String
    let worktree: String
    let displayName: String
    /// Last activity in epoch ms (v2 `time.active`, else `time.updated`), when the server sent it.
    var activityAtEpochMs: Int64? = nil
}

enum ServerProbeError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case unhealthy(version: String?)
}

extension ServerProbeError: LocalizedError {
    var errorDescription: String? {
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
