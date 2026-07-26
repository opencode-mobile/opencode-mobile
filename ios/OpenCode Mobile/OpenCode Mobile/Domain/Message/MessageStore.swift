import Foundation

protocol MessageStore: AnyObject {
    func replaceAll(sessionID: String, messages: [Message])
    func apply(sessionID: String, patch: MessagePatch)
    func messages(sessionID: String) -> [Message]
    /// True when the message came from REST and therefore has no stable SSE part identities.
    func isRestSeededMessage(sessionID: String, messageID: String) -> Bool
    func hasStreamingText(sessionID: String) -> Bool
    func revision(sessionID: String) -> UInt64
    func replaceAllIfRevision(sessionID: String, messages: [Message], expectedRevision: UInt64) -> Bool
    func finishStreamingText(sessionID: String)
    func clear()
}
