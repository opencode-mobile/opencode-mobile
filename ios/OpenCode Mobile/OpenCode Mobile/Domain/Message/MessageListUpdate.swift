import Foundation

nonisolated enum MessageListUpdate: Sendable {
    case messages([Message], isFromCache: Bool)
    case loadFailed(Error)
    /// Network confirmed cache without a second UI snapshot (fingerprint unchanged).
    case cacheSyncConfirmed
}
