import Foundation

/// Removes a checkout other than the main one. v2 needs `projectID`; v1 asks in `projectDirectory`.
nonisolated struct RemoveWorktreeRequest: Equatable, Sendable {
    let projectID: String?
    /// The project's main checkout: v1 sends it as `?directory=`.
    let projectDirectory: String
    let directory: String
    /// v2: remove even with uncommitted changes. v1 always does.
    let force: Bool
}

/// Remove failed; `message` is the server's own text when it gave one.
nonisolated enum WorktreeRemoveError: Error, Equatable, Sendable {
    /// v2 refused because the checkout has uncommitted changes and the request did not force.
    case forceRequired(message: String)
    /// The server has no worktree remove route, or the project has no v2 id.
    case unsupported
    case failed(message: String)

    var message: String {
        switch self {
        case let .forceRequired(message), let .failed(message): message
        case .unsupported: WorktreeCreateCopy.unsupportedError
        }
    }
}

/// v2 `WorktreeError` body: `{"name":"WorktreeError","data":{"message","forceRequired"}}`.
nonisolated enum WorktreeErrorBody {
    static func forceRequired(_ body: String) -> Bool {
        guard let data = body.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let payload = root["data"] as? [String: Any]
        else { return false }
        return payload["forceRequired"] as? Bool == true
    }
}
