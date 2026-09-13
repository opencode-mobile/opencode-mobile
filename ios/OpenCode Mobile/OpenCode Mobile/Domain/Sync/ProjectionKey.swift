import Foundation

nonisolated enum ProjectionKey: Hashable, Sendable {
    case sessionList(directory: String)
    case sessionStatuses(directory: String)
    case todos(directory: String)
    case permissions(directory: String)
    case questions(directory: String)
    case transcript(directory: String, sessionID: String)

    var directory: String {
        switch self {
        case let .sessionList(directory),
             let .sessionStatuses(directory),
             let .todos(directory),
             let .permissions(directory),
             let .questions(directory),
             let .transcript(directory, _):
            directory
        }
    }
}

nonisolated enum ProjectionState: Equatable, Sendable {
    case fresh
    case stale
    case syncing
}

nonisolated enum SyncBootstrapReason: Equatable, Sendable {
    case serverConnected
    case foregroundQuiet
    case manual
}

nonisolated struct SyncRegistration: Hashable, Sendable {
    let id: UUID
    let key: ProjectionKey
}

nonisolated protocol SyncClock: Sendable {
    func nowMs() -> Int64
    func sleep(milliseconds: Int) async
}

nonisolated struct SystemSyncClock: SyncClock {
    func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }

    func sleep(milliseconds: Int) async {
        guard milliseconds > 0 else { return }
        try? await Task.sleep(for: .milliseconds(milliseconds))
    }
}

nonisolated enum SyncRetryBackoff {
    static let initialMs = 500
    static let capMs = 5_000
    static let coalesceMs = 200
    static let foregroundQuietMs: Int64 = 20_000

    static func delayMs(attempt: Int) -> Int {
        let exponent = min(max(attempt - 1, 0), 3)
        let raw = initialMs << exponent
        return min(capMs, raw)
    }
}
