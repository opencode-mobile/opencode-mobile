import Foundation

/// v1 (classic) has no shell routes: a shell is a `bash` tool call in the open
/// session's transcript, live through `message.part.updated`. The server keeps the
/// running call's latest output (last 30,000 characters) in `state.metadata.output`.
nonisolated enum V1AgentShellAdapter {
    static let toolName = "bash"

    /// Every bash call of the loaded transcript, in transcript order, any status.
    static func shells(in messages: [Message], sessionID: String) -> [AgentShell] {
        bashParts(in: messages).map { shell(from: $0, sessionID: sessionID) }
    }

    /// The output of the bash call `shellID`; nil when the transcript no longer holds it.
    static func output(shellID: String, in messages: [Message]) -> AgentShellOutput? {
        guard let tool = bashParts(in: messages).last(where: { $0.id == shellID }) else { return nil }
        let live = string(tool.metadata, "output")
        let text: String =
            switch tool.status {
            case .pending, .running: live ?? ""
            case .completed, .error: nonEmpty(tool.output) ?? live ?? ""
            }
        return AgentShellOutput.capped(text, truncated: text.count >= AgentShellOutput.maxCharacters)
    }

    static func shell(from tool: ToolPart, sessionID: String) -> AgentShell {
        AgentShell(
            id: tool.id ?? "",
            sessionID: sessionID,
            command: string(tool.input, "command") ?? "",
            description: string(tool.input, "description"),
            status: status(of: tool),
            startedAtMs: ToolStartTime.normalizeEpochMs(tool.timeStartEpochMs),
            finishedAtMs: ToolStartTime.normalizeEpochMs(tool.timeEndEpochMs),
            canStop: false
        )
    }

    static func status(of tool: ToolPart) -> AgentShellStatus {
        switch tool.status {
        case .pending, .running: .running
        case .completed: .exited(code: int(tool.metadata?["exit"]))
        case .error: tool.isInterrupted ? .stopped : .failed
        }
    }

    private static func bashParts(in messages: [Message]) -> [ToolPart] {
        messages.flatMap { message in
            message.parts.compactMap { part -> ToolPart? in
                guard case .tool(let tool) = part,
                      tool.toolName.lowercased() == toolName,
                      let id = tool.id, !id.isEmpty
                else { return nil }
                return tool
            }
        }
    }

    private static func int(_ value: AnyCodable?) -> Int? {
        switch value {
        case .int(let number)?: number
        case .double(let number)?: Int(number)
        case .string(let text)?: Int(text)
        default: nil
        }
    }

    private static func string(_ values: [String: AnyCodable]?, _ key: String) -> String? {
        guard case let .string(value)? = values?[key] else { return nil }
        return nonEmpty(value)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
