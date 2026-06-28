import Foundation

enum DisplayMessageListUpdate: Sendable {
    case messages([DisplayMessage])
    case loadFailed(Error)
}
