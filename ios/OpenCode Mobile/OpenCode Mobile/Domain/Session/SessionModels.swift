import Foundation

struct Session: Sendable, Equatable {
    let id: String
    let title: String
    let slug: String?
    let projectID: String?
    let directory: String?
    let parentID: String?
    var cost: Double? = nil
    var tokens: MessageTokenUsage? = nil
    var modelProviderID: String? = nil
    var modelID: String? = nil
    var variant: String? = nil
    var createdAtEpochMs: Int64? = nil
    let updatedAtEpochMs: Int64?
}

struct SessionStatus: Sendable, Equatable {
    let sessionID: String
    let raw: [String: String]
}

struct SessionTodo: Sendable, Equatable {
    let raw: String
}

struct FileDiff: Sendable, Equatable {
    let raw: String
}

enum PermissionDecision: String, Sendable {
    case approve
    case deny
    case once
}

enum SessionError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)
}

extension SessionError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case let .transport(message):
            return message
        case let .http(_, message):
            return message ?? "Request failed"
        case let .decoding(message):
            return message
        }
    }
}
