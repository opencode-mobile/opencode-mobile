import Foundation

/// OpenCode HTTP API generation for an active server connection.
///
/// Detection follows desktop `detectServerProtocol`: prefer `/global/health`
/// (v1), else `/api/health` with numeric `pid` (v2), else transitional
/// `/api/health` with `healthy: true` (v1-shaped).
nonisolated enum ServerProtocol: String, Sendable, Equatable {
    case v1
    case v2

    var wireValue: String { rawValue }
}

/// Product capabilities derived from `ServerProtocol`. Screen Models read these
/// flags instead of branching on protocol themselves.
nonisolated struct ServerCapabilities: Sendable, Equatable {
    let supportsSessionMessageDiff: Bool
    let supportsSessionArchive: Bool

    static func forProtocol(_ serverProtocol: ServerProtocol) -> ServerCapabilities {
        switch serverProtocol {
        case .v1:
            return ServerCapabilities(supportsSessionMessageDiff: true, supportsSessionArchive: true)
        case .v2:
            return ServerCapabilities(supportsSessionMessageDiff: false, supportsSessionArchive: false)
        }
    }
}
