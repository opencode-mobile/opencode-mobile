import Foundation

nonisolated enum TerminalSessionStatus: String, Sendable, Equatable {
    case running
    case exited
}

nonisolated struct TerminalSession: Sendable, Equatable {
    let id: String
    let title: String
    let command: String
    let cwd: String
    let status: TerminalSessionStatus
}

nonisolated enum TerminalError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)
}

extension TerminalError: LocalizedError {
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
