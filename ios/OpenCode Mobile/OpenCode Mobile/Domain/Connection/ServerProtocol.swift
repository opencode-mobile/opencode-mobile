import Foundation

/// OpenCode HTTP API generation for an active server connection.
///
/// Detection prefers `/global/health` with `healthy: true` (v1), else
/// `/api/info` or `/api/health` with numeric `pid` (v2; `healthy` may be absent).
nonisolated enum ServerProtocol: String, Sendable, Equatable, Hashable {
    case v1
    case v2

    var wireValue: String { rawValue }

    var shortLabel: String { rawValue }

    var apiCaption: String { "API \(rawValue)" }

    var reachableMessage: String { "Server reachable · API \(rawValue)" }
}

/// Product capabilities derived from `ServerProtocol`. Screen Models read these
/// flags instead of branching on protocol themselves.
///
/// v2 session archive depends on the server release, not the protocol: pass
/// `v2SessionArchive` from the server's API contract probe.
nonisolated struct ServerCapabilities: Sendable, Equatable {
    let supportsSessionMessageDiff: Bool
    let supportsSessionArchive: Bool
    let supportsFollowUpDelivery: Bool
    /// Server-tracked unread state (`time.idle` / `time.viewed`, `POST /view`, `session.viewed`).
    let supportsSessionViewed: Bool

    static func forProtocol(_ serverProtocol: ServerProtocol, v2SessionArchive: Bool = false) -> ServerCapabilities {
        switch serverProtocol {
        case .v1:
            return ServerCapabilities(
                supportsSessionMessageDiff: true,
                supportsSessionArchive: true,
                supportsFollowUpDelivery: false,
                supportsSessionViewed: false
            )
        case .v2:
            return ServerCapabilities(
                supportsSessionMessageDiff: false,
                supportsSessionArchive: v2SessionArchive,
                supportsFollowUpDelivery: true,
                supportsSessionViewed: true
            )
        }
    }
}
