import Foundation

nonisolated enum MessagePatch: Sendable {
    case infoUpdated([String: AnySendable])
    case partUpdated(part: [String: AnySendable], delta: String?)
    /// Append a text delta to an existing part by ID (SSE `message.part.delta`).
    case partDelta(messageID: String, partID: String, delta: String)
    case removed(messageID: String)
    /// A committed revert: drop `messageID` and every message after it.
    case removedFrom(messageID: String)
    case partRemoved(messageID: String, partID: String)
    /// v2 `session.agent.selected` / `session.model.selected`: the session's agent or model
    /// changed. The store applies it to prompts delivered after it (their info carries neither).
    case contextSwitched(agent: String?, model: MessageModelContext?, atEpochMs: Int64?)
}
