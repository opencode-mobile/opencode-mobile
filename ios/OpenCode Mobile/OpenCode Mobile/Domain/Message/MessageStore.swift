import Foundation

protocol MessageStore: AnyObject {
    func replaceAll(sessionID: String, messages: [Message])
    func apply(sessionID: String, patch: MessagePatch)
    func messages(sessionID: String) -> [Message]
    func isBaseOnlyMessage(sessionID: String, messageID: String) -> Bool
    func clear()
}
