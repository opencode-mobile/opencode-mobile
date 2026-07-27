import Foundation

/// Closed-loop observation health for MessageSync recovery.
enum MessageSyncHealth: Equatable, Sendable {
    case healthy
    case recovering(MessageSyncRecoveryReason)
    case stale(MessageSyncRecoveryReason)

    var isHealthy: Bool {
        if case .healthy = self { return true }
        return false
    }

    var requiresAuthoritativeRecovery: Bool {
        if case .recovering = self { return true }
        return false
    }
}

enum MessageSyncRecoveryReason: String, Equatable, Sendable {
    case overflow
    case reconnect
    case reloadFailed
    case racedAuthority
    case transportUnavailable
}

/// Complete ownership key for observation, recovery, fingerprints, and store slots.
struct MessageSessionKey: Hashable, Sendable {
    let connectionProfileID: String
    let directory: String
    let sessionID: String

    init(connectionProfileID: String, directory: String?, sessionID: String) {
        self.connectionProfileID = connectionProfileID
        self.directory = directory ?? ""
        self.sessionID = sessionID
    }

    /// Opaque store slot that isolates profile + directory + session without
    /// changing the MessageStore API surface.
    var storeSlot: String {
        "\(connectionProfileID)\u{1f}\(directory)\u{1f}\(sessionID)"
    }
}

enum MessageSyncRecoveryPolicy {
    static let initialBackoffMs = 250
    static let maxBackoffMs = 5_000
}
