import Foundation

protocol MessageStore: AnyObject {
    func replaceAll(sessionID: String, messages: [Message])
    func apply(sessionID: String, patch: MessagePatch)
    func messages(sessionID: String) -> [Message]
    /// True when the message exists only in the REST snapshot and has no SSE info/part identity cache.
    func isBaseOnlyMessage(sessionID: String, messageID: String) -> Bool
    func hasStreamingText(sessionID: String) -> Bool
    func clear()
}
