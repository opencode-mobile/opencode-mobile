import Foundation

struct SessionContextSnapshot: Equatable, Sendable {
    let sessionID: String
    let sessionTitle: String
    let providerLabel: String?
    let modelLabel: String?
    let tokenStats: SessionContextTokenStats?
    let messageCounts: SessionContextMessageCounts
    let totalCost: Double
    let createdAtEpochMs: Int64?
    let lastActivityEpochMs: Int64?
    let breakdown: [SessionContextBreakdownItem]
}

struct SessionContextTokenStats: Equatable, Sendable {
    let contextLimit: Int64?
    let total: Int64
    let input: Int64
    let output: Int64
    let reasoning: Int64
    let cacheRead: Int64
    let cacheWrite: Int64
    let usagePercent: Int?
}

struct SessionContextMessageCounts: Equatable, Sendable {
    let total: Int
    let user: Int
    let assistant: Int
}

struct SessionContextBreakdownItem: Equatable, Sendable, Identifiable {
    enum Kind: String, Equatable, Sendable {
        case system
        case user
        case assistant
        case tool
        case other
    }

    var id: Kind { kind }

    let kind: Kind
    let tokens: Int64
    let widthPercent: Double
    let displayPercent: Double
}

enum SessionContextError: Error, Equatable {
    case underlying(String)

    var localizedDescription: String {
        switch self {
        case let .underlying(message):
            message
        }
    }
}
