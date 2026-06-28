import Foundation

enum MessagePatch: Sendable {
    case infoUpdated([String: AnySendable])
    case partUpdated(part: [String: AnySendable], delta: String?)
    case removed(messageID: String)
    case partRemoved(messageID: String, partID: String)
}
