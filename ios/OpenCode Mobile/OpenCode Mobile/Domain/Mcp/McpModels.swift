import Foundation

/// Product status of one configured MCP server on the opencode server.
nonisolated enum McpServerStatus: Sendable, Equatable {
    case connected
    case connecting
    case disabled
    case failed
    case needsSignIn

    var label: String {
        switch self {
        case .connected: "Connected"
        case .connecting: "Connecting"
        case .disabled: "Disabled"
        case .failed: "Failed"
        case .needsSignIn: "Needs sign-in"
        }
    }

    /// True when the user should look at this server before relying on its tools.
    var needsAttention: Bool {
        self == .failed || self == .needsSignIn
    }
}

nonisolated struct McpServer: Sendable, Equatable, Hashable {
    let name: String
    let status: McpServerStatus
    let errorMessage: String?

    init(name: String, status: McpServerStatus, errorMessage: String? = nil) {
        self.name = name
        self.status = status
        self.errorMessage = errorMessage
    }
}

/// Aggregate health of the MCP server list, used for the Commands tile badge.
nonisolated struct McpServerSummary: Sendable, Equatable {
    let total: Int
    let connected: Int
    let attention: Int
    let worst: McpServerStatus?

    var headline: String { "\(connected) of \(total) connected" }

    static let empty = McpServerSummary(total: 0, connected: 0, attention: 0, worst: nil)
}

nonisolated enum McpServerCatalog {
    static func summary(_ servers: [McpServer]) -> McpServerSummary {
        guard !servers.isEmpty else { return .empty }
        let statuses = servers.map(\.status)
        let worst: McpServerStatus =
            if statuses.contains(.failed) { .failed }
            else if statuses.contains(.needsSignIn) { .needsSignIn }
            else if statuses.contains(.connecting) { .connecting }
            else if statuses.contains(.connected) { .connected }
            else { .disabled }
        return McpServerSummary(
            total: servers.count,
            connected: statuses.filter { $0 == .connected }.count,
            attention: statuses.filter(\.needsAttention).count,
            worst: worst
        )
    }
}

nonisolated enum McpError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)
}
