import Foundation

nonisolated enum DisplayMessageListUpdate: Sendable {
    case messages([DisplayMessage], isFromCache: Bool)
    case loadFailed(Error)
    /// Network confirmed cache without a second UI snapshot (fingerprint unchanged).
    case cacheSyncConfirmed
}
