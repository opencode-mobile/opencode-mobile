import Foundation

protocol MessageStore: AnyObject {
    func replaceAll(sessionID: String, messages: [Message])
    func apply(sessionID: String, patch: MessagePatch) -> MessageStoreApplyResult
    func messages(sessionID: String) -> [Message]
    /// True when a REST-seeded message contains a part without a stable server identity.
    func isRestSeededMessage(sessionID: String, messageID: String) -> Bool
    func hasStreamingText(sessionID: String) -> Bool
    func revision(sessionID: String) -> UInt64
    func replaceAllIfRevision(sessionID: String, messages: [Message], expectedRevision: UInt64) -> Bool
    /// Reconnect REST overlay: patch existing parts in place. Does not rematerialize
    /// unchanged tools. No-op when `expectedRevision` is stale.
    func mergeAuthoritativeIfRevision(sessionID: String, messages: [Message], expectedRevision: UInt64) -> Bool
    /// Prepends unseen older rows from the current store snapshot. No-op when
    /// `expectedRevision` is stale so a live SSE revision cannot be overwritten.
    func prependOlderIfRevision(sessionID: String, older: [Message], expectedRevision: UInt64) -> Bool
    func finishStreamingText(sessionID: String)
    func clear()
}
