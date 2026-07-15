import Foundation

/// Session Chat presentation units inside one message timeline item.
/// Built from structured parts with hide rules and lightweight tool chrome.
enum SessionChatTimelineBlock: Equatable, Sendable {
    case text(String)
    case toolCard(SessionChatToolCardBlock)
    case toolGroup(SessionChatToolGroupBlock)
}

struct SessionChatToolCardBlock: Equatable, Sendable {
    let toolName: String
    let status: ToolStatus
    let title: String
    let subtitle: String?
    let errorText: String?
    /// Non-nil when the card should open a Bottom Sheet with this body.
    let detailText: String?
    /// Non-nil when tap should open this child session's Session Chat.
    let childSessionId: String?

    init(
        toolName: String,
        status: ToolStatus,
        title: String,
        subtitle: String? = nil,
        errorText: String? = nil,
        detailText: String? = nil,
        childSessionId: String? = nil
    ) {
        self.toolName = toolName
        self.status = status
        self.title = title
        self.subtitle = subtitle
        self.errorText = errorText
        self.detailText = detailText
        self.childSessionId = childSessionId
    }
}

struct SessionChatToolGroupBlock: Equatable, Sendable {
    let count: Int
    let status: ToolStatus
    let title: String
    let subtitle: String?
    let errorText: String?
}

enum SessionChatTimelineBlocks {
    private static let contextToolNames: Set<String> = ["read", "glob", "grep"]

    static func build(role: MessageRole, parts: [MessagePart]) -> [SessionChatTimelineBlock] {
        guard !parts.isEmpty else { return [] }

        let allowToolCards = role == .assistant
        var blocks: [SessionChatTimelineBlock] = []
        var pendingText: [String] = []

        func flushText() {
            guard !pendingText.isEmpty else { return }
            let merged = pendingText.joined(separator: "\n")
            pendingText.removeAll()
            if !merged.isEmpty {
                blocks.append(.text(merged))
            }
        }

        var index = 0
        while index < parts.count {
            switch parts[index] {
            case .text(let textPart):
                let trimmed = textPart.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    pendingText.append(trimmed)
                }
                index += 1
            case .tool(let tool):
                guard allowToolCards, isVisibleTool(tool) else {
                    index += 1
                    continue
                }
                flushText()
                if isContextTool(tool.toolName) {
                    var groupTools: [ToolPart] = []
                    var lookAhead = index
                    while lookAhead < parts.count {
                        guard case .tool(let candidate) = parts[lookAhead],
                              isVisibleTool(candidate),
                              isContextTool(candidate.toolName)
                        else { break }
                        groupTools.append(candidate)
                        lookAhead += 1
                    }
                    if groupTools.count >= 2 {
                        blocks.append(.toolGroup(toToolGroup(groupTools)))
                        index = lookAhead
                    } else {
                        blocks.append(.toolCard(toToolCard(tool)))
                        index += 1
                    }
                } else {
                    blocks.append(.toolCard(toToolCard(tool)))
                    index += 1
                }
            }
        }
        flushText()
        return blocks
    }

    private static func isVisibleTool(_ tool: ToolPart) -> Bool {
        let name = tool.toolName.lowercased()
        if name == "todowrite" { return false }
        if name == "question", tool.status == .pending || tool.status == .running {
            return false
        }
        return true
    }

    private static func isContextTool(_ toolName: String) -> Bool {
        contextToolNames.contains(toolName.lowercased())
    }

    private static func toToolGroup(_ tools: [ToolPart]) -> SessionChatToolGroupBlock {
        let status = worstStatus(tools.map(\.status))
        let errorText = tools
            .first(where: { $0.status == .error })?
            .error
            .flatMap { $0.isEmpty ? nil : $0 }
        return SessionChatToolGroupBlock(
            count: tools.count,
            status: status,
            title: contextGroupTitle(tools),
            subtitle: nil,
            errorText: errorText
        )
    }

    private static func contextGroupTitle(_ tools: [ToolPart]) -> String {
        var counts: [(String, Int)] = [
            ("read", 0),
            ("grep", 0),
            ("glob", 0),
        ]
        for tool in tools {
            let key = tool.toolName.lowercased()
            if let index = counts.firstIndex(where: { $0.0 == key }) {
                counts[index].1 += 1
            }
        }
        return counts
            .filter { $0.1 > 0 }
            .map { name, count in
                let label: String =
                    switch name {
                    case "read": count == 1 ? "read" : "reads"
                    case "grep": count == 1 ? "grep" : "greps"
                    case "glob": count == 1 ? "glob" : "globs"
                    default: name
                    }
                return "\(count) \(label)"
            }
            .joined(separator: " · ")
    }

    private static func worstStatus(_ statuses: [ToolStatus]) -> ToolStatus {
        if statuses.contains(.error) { return .error }
        if statuses.contains(where: { $0 == .running || $0 == .pending }) {
            return .running
        }
        return .completed
    }

    private static func toToolCard(_ tool: ToolPart) -> SessionChatToolCardBlock {
        let name = tool.toolName.lowercased()
        let chrome = specializedChrome(name: name, tool: tool) ?? genericChrome(tool)
        let errorText: String? =
            switch tool.status {
            case .error:
                tool.error.flatMap { $0.isEmpty ? nil : $0 }
            case .pending, .running, .completed:
                nil
            }
        return SessionChatToolCardBlock(
            toolName: tool.toolName,
            status: tool.status,
            title: chrome.title,
            subtitle: chrome.subtitle,
            errorText: errorText,
            detailText: chrome.detailText,
            childSessionId: chrome.childSessionId
        )
    }

    private struct Chrome {
        let title: String
        let subtitle: String?
        let detailText: String?
        let childSessionId: String?

        init(
            title: String,
            subtitle: String?,
            detailText: String? = nil,
            childSessionId: String? = nil
        ) {
            self.title = title
            self.subtitle = subtitle
            self.detailText = detailText
            self.childSessionId = childSessionId
        }
    }

    private static func genericChrome(_ tool: ToolPart) -> Chrome {
        let subtitle: String? =
            switch tool.status {
            case .running:
                tool.title.flatMap { $0.isEmpty ? nil : $0 }
            case .pending, .completed, .error:
                nil
            }
        return Chrome(title: "Called \(tool.toolName)", subtitle: subtitle)
    }

    private static func specializedChrome(name: String, tool: ToolPart) -> Chrome? {
        switch name {
        case "read":
            return contextChrome(tool: tool, displayTitle: "Read", subtitle: pathSubtitle(tool.input))
        case "glob":
            return contextChrome(tool: tool, displayTitle: "Glob", subtitle: patternPathSubtitle(tool.input))
        case "grep":
            return contextChrome(tool: tool, displayTitle: "Grep", subtitle: patternPathSubtitle(tool.input))
        case "bash":
            return shellChrome(tool)
        case "webfetch":
            return networkChrome(tool: tool, displayTitle: "Fetch", subtitleKey: "url")
        case "websearch":
            return networkChrome(tool: tool, displayTitle: "Search", subtitleKey: "query")
        case "question":
            return Chrome(
                title: "Question",
                subtitle: inputString(tool.input, keys: "prompt", "text", "question")
                    ?? runningTitleOverride(tool)
            )
        case "skill":
            return Chrome(
                title: "Skill",
                subtitle: inputString(tool.input, keys: "name", "skill")
                    ?? runningTitleOverride(tool)
            )
        case "task":
            return taskChrome(tool)
        default:
            return nil
        }
    }

    private static func taskChrome(_ tool: ToolPart) -> Chrome {
        let title =
            inputString(tool.input, keys: "agent", "name")
            ?? metadataString(tool.metadata, keys: "agent")
            ?? "Task"
        let description =
            inputString(tool.input, keys: "description", "prompt")
            ?? runningTitleOverride(tool)
        let subtitle: String?
        if let description, isTruthyMetadata(tool.metadata, key: "background") {
            subtitle = "\(description) (background)"
        } else {
            subtitle = description
        }
        return Chrome(
            title: title,
            subtitle: subtitle,
            childSessionId: metadataString(tool.metadata, keys: "sessionId")
        )
    }

    private static func contextChrome(tool: ToolPart, displayTitle: String, subtitle: String?) -> Chrome {
        guard let subtitle else {
            return Chrome(
                title: "Called \(tool.toolName)",
                subtitle: runningTitleOverride(tool)
            )
        }
        return Chrome(
            title: displayTitle,
            subtitle: runningTitleOverride(tool) ?? subtitle
        )
    }

    private static func shellChrome(_ tool: ToolPart) -> Chrome {
        let command = inputString(tool.input, keys: "command") ?? runningTitleOverride(tool)
        let detail = nonEmpty(tool.output) ?? nonEmpty(tool.error)
        return Chrome(title: "Shell", subtitle: command, detailText: detail)
    }

    private static func networkChrome(tool: ToolPart, displayTitle: String, subtitleKey: String) -> Chrome {
        let subtitle = inputString(tool.input, keys: subtitleKey) ?? runningTitleOverride(tool)
        let detail: String? =
            switch tool.status {
            case .completed, .error:
                nonEmpty(tool.output) ?? nonEmpty(tool.error)
            case .pending, .running:
                nil
            }
        return Chrome(title: displayTitle, subtitle: subtitle, detailText: detail)
    }

    private static func runningTitleOverride(_ tool: ToolPart) -> String? {
        switch tool.status {
        case .running:
            tool.title.flatMap { $0.isEmpty ? nil : $0 }
        case .pending, .completed, .error:
            nil
        }
    }

    private static func pathSubtitle(_ input: [String: AnyCodable]?) -> String? {
        inputString(input, keys: "filePath", "path", "file")
    }

    private static func patternPathSubtitle(_ input: [String: AnyCodable]?) -> String? {
        let pattern = inputString(input, keys: "pattern")
        let path = inputString(input, keys: "path", "directory")
        switch (pattern, path) {
        case let (pattern?, path?):
            return "\(pattern) · \(path)"
        case let (pattern?, nil):
            return pattern
        case let (nil, path?):
            return path
        case (nil, nil):
            return nil
        }
    }

    private static func inputString(_ input: [String: AnyCodable]?, keys: String...) -> String? {
        guard let input else { return nil }
        for key in keys {
            if let value = input[key]?.stringValue, !value.isEmpty {
                return value
            }
        }
        return nil
    }

    private static func metadataString(_ metadata: [String: AnyCodable]?, keys: String...) -> String? {
        guard let metadata else { return nil }
        for key in keys {
            if let value = metadata[key]?.stringValue, !value.isEmpty {
                return value
            }
        }
        return nil
    }

    private static func isTruthyMetadata(_ metadata: [String: AnyCodable]?, key: String) -> Bool {
        guard let value = metadata?[key] else { return false }
        switch value {
        case .bool(let flag):
            return flag
        case .string(let text):
            let normalized = text.lowercased()
            return normalized == "true" || normalized == "1"
        default:
            return false
        }
    }

    private static func nonEmpty(_ value: String?) -> String? {
        value.flatMap { $0.isEmpty ? nil : $0 }
    }
}
