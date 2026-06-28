import Foundation

enum MessageListUpdate: Sendable {
    case messages([Message])
    case loadFailed(Error)
}
