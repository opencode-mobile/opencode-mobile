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

/// Product capabilities for the active engine. OpenCode still derives them from
/// `ServerProtocol`. T3 has its own preset: the protocol probe reports v1, and
/// that fallback must not turn OpenCode-only controls back on.
///
/// Screen Models read these flags instead of branching on protocol themselves.
nonisolated struct ServerCapabilities: Sendable, Equatable {
    let supportsSessionMessageDiff: Bool
    let supportsSessionArchive: Bool
    let supportsFollowUpDelivery: Bool
    /// Server-tracked unread state (`time.idle` / `time.viewed`, `POST /view`, `session.viewed`).
    var supportsSessionViewed: Bool = false
    var supportsTerminal: Bool = true
    var supportsRevert: Bool = true
    var supportsMentions: Bool = true
    var supportsSlashCommands: Bool = true
    var supportsDocumentAttachments: Bool = true

    static let t3 = ServerCapabilities(
        supportsSessionMessageDiff: false,
        supportsSessionArchive: true,
        supportsFollowUpDelivery: false,
        supportsTerminal: false,
        supportsRevert: false,
        supportsMentions: false,
        supportsSlashCommands: false,
        supportsDocumentAttachments: false
    )

    /// `supportsDocumentAttachments` is true only when this environment advertised `fileAttachments`.
    static func t3(supportsDocumentAttachments: Bool) -> ServerCapabilities {
        guard supportsDocumentAttachments else { return t3 }
        var capabilities = t3
        capabilities.supportsDocumentAttachments = true
        return capabilities
    }

    static func forProtocol(_ serverProtocol: ServerProtocol) -> ServerCapabilities {
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
                supportsSessionArchive: false,
                supportsFollowUpDelivery: true,
                supportsSessionViewed: true
            )
        }
    }

    static func forEngine(_ engine: ConnectionEngine, protocol serverProtocol: ServerProtocol?) -> ServerCapabilities {
        if engine == .t3 {
            return t3
        }
        return forProtocol(serverProtocol ?? .v1)
    }
}
