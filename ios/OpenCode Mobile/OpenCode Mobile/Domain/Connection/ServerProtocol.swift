import Foundation

/// OpenCode HTTP API generation for an active server connection.
///
/// Detection follows desktop `detectServerProtocol`: prefer `/global/health`
/// (v1), else `/api/health` with numeric `pid` (v2), else transitional
/// `/api/health` with `healthy: true` (v1-shaped).
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
nonisolated struct ServerCapabilities: Sendable, Equatable {
    let supportsSessionMessageDiff: Bool
    let supportsSessionArchive: Bool
    let supportsFollowUpDelivery: Bool

    static func forProtocol(_ serverProtocol: ServerProtocol) -> ServerCapabilities {
        switch serverProtocol {
        case .v1:
            return ServerCapabilities(
                supportsSessionMessageDiff: true,
                supportsSessionArchive: true,
                supportsFollowUpDelivery: false
            )
        case .v2:
            return ServerCapabilities(
                supportsSessionMessageDiff: false,
                supportsSessionArchive: false,
                supportsFollowUpDelivery: true
            )
        }
    }
}
