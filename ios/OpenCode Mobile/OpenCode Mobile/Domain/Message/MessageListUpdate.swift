import Foundation

nonisolated enum MessageListUpdate: Sendable {
    case messages([Message], isFromCache: Bool)
    case loadFailed(Error)
}
