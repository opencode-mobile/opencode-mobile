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
/// v2 session archive, per-turn diff and side questions depend on the server release, not the protocol:
/// pass `v2Features` from the server's API contract probe.
nonisolated struct ServerCapabilities: Sendable, Equatable {
    let supportsSessionMessageDiff: Bool
    let supportsSessionArchive: Bool
    let supportsFollowUpDelivery: Bool
    /// Server-tracked unread state (`time.idle` / `time.viewed`, `POST /view`, `session.viewed`).
    let supportsSessionViewed: Bool
    /// `POST /api/session/{id}/move` (v2). v1 has no move endpoint.
    let supportsSessionMove: Bool
    /// The command route keeps a client message id (v1 `messageID`); v2 `…/command` takes none.
    var commandKeepsClientMessageID: Bool = false
    /// `/btw` side questions: `POST /api/session/{id}/generate` answers from the session's context
    /// without adding to it (v2 releases that declare it). v1 has no equivalent.
    var supportsSideQuestion: Bool = false
    /// Files can stream to the server (`POST /api/experimental/fs/write`, #644). Off unless the
    /// server's API contract still matches what the app sends.
    var supportsStreamedUpload: Bool = false
    /// The server records agent and model switches as timeline notices (v2). Without them
    /// (v1) Session Chat derives the same notices from consecutive user messages.
    var recordsTimelineChangeNotices: Bool = false

    static func forProtocol(_ serverProtocol: ServerProtocol, v2Features: V2ServerFeatures = V2ServerFeatures()) -> ServerCapabilities {
        switch serverProtocol {
        case .v1:
            return ServerCapabilities(
                supportsSessionMessageDiff: true,
                supportsSessionArchive: true,
                supportsFollowUpDelivery: false,
                supportsSessionViewed: false,
                supportsSessionMove: false,
                commandKeepsClientMessageID: true
            )
        case .v2:
            return ServerCapabilities(
                supportsSessionMessageDiff: v2Features.sessionTurnDiff,
                supportsSessionArchive: v2Features.sessionArchive,
                supportsFollowUpDelivery: true,
                supportsSessionViewed: true,
                supportsSessionMove: true,
                supportsSideQuestion: v2Features.sessionGenerate,
                supportsStreamedUpload: v2Features.streamedUpload,
                recordsTimelineChangeNotices: true
            )
        }
    }
}
