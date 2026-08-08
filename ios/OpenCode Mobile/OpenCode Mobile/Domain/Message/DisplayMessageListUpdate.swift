import Foundation

nonisolated enum DisplayMessageListUpdate: Sendable {
    case messages([DisplayMessage], isFromCache: Bool)
    case loadFailed(Error)
}
