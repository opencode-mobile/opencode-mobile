import Foundation

protocol MessageStore: AnyObject {
    func replaceAll(sessionID: String, messages: [Message])
    func apply(sessionID: String, patch: MessagePatch)
    func messages(sessionID: String) -> [Message]
    /// True when a REST-seeded message contains a part without a stable server identity.
    func isRestSeededMessage(sessionID: String, messageID: String) -> Bool
    func hasStreamingText(sessionID: String) -> Bool
    func revision(sessionID: String) -> UInt64
    func replaceAllIfRevision(sessionID: String, messages: [Message], expectedRevision: UInt64) -> Bool
    func finishStreamingText(sessionID: String)
    func clear()
}
