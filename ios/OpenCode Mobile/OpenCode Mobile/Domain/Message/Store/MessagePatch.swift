import Foundation

nonisolated enum MessagePatch: Sendable {
    case infoUpdated([String: AnySendable])
    case partUpdated(part: [String: AnySendable], delta: String?)
    /// Append a text delta to an existing part by ID (SSE `message.part.delta`).
    case partDelta(messageID: String, partID: String, delta: String)
    case removed(messageID: String)
    case partRemoved(messageID: String, partID: String)
}
