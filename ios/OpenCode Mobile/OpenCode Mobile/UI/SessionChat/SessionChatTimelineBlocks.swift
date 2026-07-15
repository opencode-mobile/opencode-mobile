import Foundation

/// Session Chat presentation units inside one message timeline item.
/// Built from structured parts with OPE-49 hide rules and generic tool chrome.
enum SessionChatTimelineBlock: Equatable, Sendable {
    case text(String)
    case toolCard(SessionChatToolCardBlock)
}

struct SessionChatToolCardBlock: Equatable, Sendable {
    let toolName: String
    let status: ToolStatus
    let title: String
    let subtitle: String?
    let errorText: String?
}

enum SessionChatTimelineBlocks {
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

        for part in parts {
            switch part {
            case .text(let textPart):
                let trimmed = textPart.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    pendingText.append(trimmed)
                }
            case .tool(let tool):
                guard allowToolCards, isVisibleTool(tool) else { continue }
                flushText()
                blocks.append(.toolCard(toToolCard(tool)))
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

    private static func toToolCard(_ tool: ToolPart) -> SessionChatToolCardBlock {
        let subtitle: String? =
            switch tool.status {
            case .running:
                tool.title.flatMap { $0.isEmpty ? nil : $0 }
            case .pending, .completed, .error:
                nil
            }
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
            title: "Called \(tool.toolName)",
            subtitle: subtitle,
            errorText: errorText
        )
    }
}
