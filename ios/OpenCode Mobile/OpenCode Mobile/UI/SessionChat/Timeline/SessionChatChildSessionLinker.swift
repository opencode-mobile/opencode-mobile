import Foundation

/// A child session the linker may attach to a subagent tool part.
nonisolated struct SessionChatChildSessionCandidate: Equatable, Sendable {
    let id: String
    let title: String
    let createdAtEpochMs: Int64?
}

/// Fills `metadata.sessionID` on subagent tool parts that lack it, so their
/// rows and chips can open the child session.
///
/// v2 reports a running child's id once, on `session.tool.progress`; the stored
/// running part keeps empty metadata, so a history reload loses it until the
/// subagent finishes. Like the web client's `taskSession` fallback, a part
/// without an id matches a child of this session whose title starts with the
/// part's `description` (the server titles the child with it). A resumed
/// subagent names its child in `input.sessionID`.
nonisolated enum SessionChatChildSessionLinker {
    static func isSubagentTool(_ toolName: String) -> Bool {
        let name = toolName.lowercased()
        return name == "task" || name == "subagent"
    }

    /// Subagent part ids that carry no child id of their own.
    static func unlinkedPartIDs(in messages: [Message]) -> Set<String> {
        var ids: Set<String> = []
        for message in messages {
            for case .tool(let tool) in message.parts
            where isSubagentTool(tool.toolName) && ownSessionID(tool) == nil && description(tool) != nil {
                if let id = tool.id { ids.insert(id) }
            }
        }
        return ids
    }

    /// Links parts in timeline order. Children already named by a part are not
    /// reused, and among the rest the oldest match wins, so parallel subagents
    /// with the same description map to their children in spawn order.
    static func link(_ messages: [Message], children: [SessionChatChildSessionCandidate]) -> [Message] {
        guard messages.contains(where: { hasUnresolvedSubagent($0) }) else { return messages }
        var claimed: Set<String> = []
        for message in messages {
            for case .tool(let tool) in message.parts where isSubagentTool(tool.toolName) {
                if let id = ownSessionID(tool) { claimed.insert(id) }
            }
        }
        var available = children
            .filter { !claimed.contains($0.id) }
            .sorted { ($0.createdAtEpochMs ?? 0, $0.id) < ($1.createdAtEpochMs ?? 0, $1.id) }
        return messages.map { message in
            guard hasUnresolvedSubagent(message) else { return message }
            let parts = message.parts.map { part -> MessagePart in
                guard case .tool(let tool) = part,
                      isSubagentTool(tool.toolName),
                      metadataSessionID(tool) == nil
                else { return part }
                if let resumed = inputSessionID(tool) {
                    return .tool(tool.withMetadataValue(.string(resumed), forKey: "sessionID"))
                }
                guard let description = description(tool),
                      let index = available.firstIndex(where: { $0.title.hasPrefix(description) })
                else { return part }
                let child = available.remove(at: index)
                return .tool(tool.withMetadataValue(.string(child.id), forKey: "sessionID"))
            }
            return message.withParts(parts)
        }
    }

    private static func hasUnresolvedSubagent(_ message: Message) -> Bool {
        message.parts.contains { part in
            guard case .tool(let tool) = part else { return false }
            return isSubagentTool(tool.toolName) && metadataSessionID(tool) == nil
        }
    }

    private static func ownSessionID(_ tool: ToolPart) -> String? {
        metadataSessionID(tool) ?? inputSessionID(tool)
    }

    private static func metadataSessionID(_ tool: ToolPart) -> String? {
        nonEmpty(tool.metadata?["sessionID"]?.stringValue)
            ?? nonEmpty(tool.metadata?["sessionId"]?.stringValue)
    }

    private static func inputSessionID(_ tool: ToolPart) -> String? {
        nonEmpty(tool.input?["sessionID"]?.stringValue)
    }

    private static func description(_ tool: ToolPart) -> String? {
        nonEmpty(tool.input?["description"]?.stringValue)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
