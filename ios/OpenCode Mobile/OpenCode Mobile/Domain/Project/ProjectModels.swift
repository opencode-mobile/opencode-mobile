import Foundation

struct Project: Sendable, Equatable {
    let id: String
    let worktree: String
    let displayName: String
}

enum ServerProbeError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case unhealthy(version: String?)
}

extension ServerProbeError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case let .transport(message):
            return message
        case let .http(_, message):
            return message ?? "Request failed"
        case .unhealthy:
            return "Server reported unhealthy"
        }
    }
}
